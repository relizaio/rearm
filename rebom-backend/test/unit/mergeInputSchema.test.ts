import { describe, it, expect, vi } from 'vitest';
import { buildASTSchema, coerceInputValue, GraphQLInputType } from 'graphql';
import typeDefs from '../../src/schema.graphql';
import { MissingSbomReason } from '../../src/types';

/**
 * SCORE-40 (T-7, ADR-1, ADR-5): the merge options rebom's GraphQL schema accepts. The release node
 * facts and the placeholder entries take licenseExpression and licenseUrl; the request of the
 * current backend, without them, is still valid; no other licence field exists.
 */

const schema = buildASTSchema(typeDefs);
const rebomOptions = schema.getType('RebomOptions') as GraphQLInputType;

const SHA256 = '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08';
const PLACEHOLDER_UUID = '0b0f4a52-6c55-4d1f-9a8e-000000000301';

function placeholderEntry(extra: any = {}): any {
    return { releaseUuid: PLACEHOLDER_UUID, name: 'worker', version: '4.0.0', type: 'library', reason: MissingSbomReason.NO_SBOM_ARTIFACT, ...extra };
}

/** The value coerced, with every error the coercion reported as [path, message]. */
function coerce(value: any): { value: any, errors: [string, string][] } {
    const errors: [string, string][] = [];
    const coerced = coerceInputValue(value, rebomOptions, (path, _invalid, error) => {
        errors.push([path.join('.'), error.message]);
    });
    return { value: coerced, errors };
}

describe('the merge input schema', () => {
    it('accepts licenseExpression and licenseUrl on the release node and on a placeholder (T-7)', () => {
        const { value, errors } = coerce({
            releaseNode: {
                supplierName: 'Acme',
                hashes: [{ alg: 'SHA-256', content: SHA256 }],
                licenseExpression: 'LicenseRef-Acme-Commercial',
                licenseUrl: 'https://acme.example/licence'
            },
            missingSbomComponents: [
                placeholderEntry({ licenseExpression: 'AGPL-3.0-only', licenseUrl: 'https://agpl.example/text' }),
                placeholderEntry({ releaseUuid: '0b0f4a52-6c55-4d1f-9a8e-000000000302', licenseExpression: 'MIT OR Apache-2.0' })
            ]
        });

        expect(errors).toStrictEqual([]);
        expect({ ...value.releaseNode }).toStrictEqual({
            supplierName: 'Acme',
            hashes: [expect.objectContaining({ alg: 'SHA-256', content: SHA256 })],
            licenseExpression: 'LicenseRef-Acme-Commercial',
            licenseUrl: 'https://acme.example/licence'
        });
        expect(value.missingSbomComponents.map((m: any) => [m.licenseExpression, m.licenseUrl])).toStrictEqual([
            ['AGPL-3.0-only', 'https://agpl.example/text'],
            ['MIT OR Apache-2.0', undefined]
        ]);
    });

    it('accepts the SCORE-30 request without the licence fields, and adds no licence key (T-7, ADR-5)', () => {
        const { value, errors } = coerce({
            mergeVersion: 6,
            releaseNode: { supplierName: 'Acme', hashes: [{ alg: 'SHA-256', content: SHA256 }] },
            missingSbomComponents: [placeholderEntry({ supplierName: 'Acme', group: 'Acme' }), placeholderEntry()]
        });

        expect(errors).toStrictEqual([]);
        expect(Object.keys(value.releaseNode)).toStrictEqual(['supplierName', 'hashes']);
        expect(Object.keys(value.missingSbomComponents[0])).toStrictEqual(['releaseUuid', 'name', 'version', 'type', 'supplierName', 'group', 'reason']);
        expect(Object.keys(value.missingSbomComponents[1])).toStrictEqual(['releaseUuid', 'name', 'version', 'type', 'reason']);
    });

    it('accepts a null release node (T-7)', () => {
        const { value, errors } = coerce({ releaseNode: null });

        expect(errors).toStrictEqual([]);
        expect(value.releaseNode).toBeNull();
    });

    it('refuses releaseNode.licenseName, naming the field (T-7)', () => {
        const onError = vi.fn();
        coerceInputValue({ releaseNode: { licenseExpression: 'MIT', licenseName: 'MIT License' } }, rebomOptions, onError);

        expect(onError).toHaveBeenCalledTimes(1);
        expect(onError.mock.calls[0][0]).toStrictEqual(['releaseNode']);
        expect(onError.mock.calls[0][2].message).toContain('"licenseName"');
        expect(onError.mock.calls[0][2].message).toContain('"ReleaseNodeFactsInput"');
    });

    it('refuses missingSbomComponents[0].licenses, naming the field (T-7)', () => {
        const onError = vi.fn();
        coerceInputValue({ missingSbomComponents: [placeholderEntry({ licenses: [{ expression: 'MIT' }] })] }, rebomOptions, onError);

        expect(onError).toHaveBeenCalledTimes(1);
        expect(onError.mock.calls[0][0]).toStrictEqual(['missingSbomComponents', 0]);
        expect(onError.mock.calls[0][2].message).toContain('"licenses"');
        expect(onError.mock.calls[0][2].message).toContain('"MissingSbomComponentInput"');
    });

    it('names the two fields exactly as rearm-saas sends them, on both input types (T-7)', () => {
        for (const type of ['ReleaseNodeFactsInput', 'MissingSbomComponentInput']) {
            const fields = (schema.getType(type) as any).getFields();
            expect(String(fields['licenseExpression']?.type), type).toBe('String');
            expect(String(fields['licenseUrl']?.type), type).toBe('String');
        }
    });
});
