import { describe, it, expect } from 'vitest';
import { settleLicenseFolds } from '../../src/services/bom/bomProcessingService';
import validateBom from '../../src/validateBom';

/**
 * SCORE-40 (T-8, ADR-6): a `licenses` array a fold left mixed is settled by its first entry's
 * kind, the survivor's: an expression first stands alone, otherwise the expression entries go
 * and the license entries stay in order. Everything else is left as it is.
 */

const MIT = { license: { id: 'MIT' } };
const CUSTOM = { license: { name: 'Custom', url: 'https://x' } };

function doc(components: any[], root: any = { type: 'application', name: 'root', 'bom-ref': 'ROOT' }): any {
    return {
        bomFormat: 'CycloneDX',
        specVersion: '1.6',
        serialNumber: 'urn:uuid:3f1d2c4b-6a5e-4b7d-8c9e-0a1b2c3d4e5f',
        version: 1,
        metadata: { component: root },
        components
    };
}

function lib(ref: string, extra: any = {}): any {
    return { type: 'library', name: ref.toLowerCase(), version: '1', 'bom-ref': ref, ...extra };
}

describe('settleLicenseFolds', () => {
    it('keeps a leading expression alone and counts the node (T-8)', async () => {
        const input = doc([lib('A', { licenses: [{ expression: 'MIT OR Apache-2.0' }, MIT] }), lib('B')]);
        const expected = structuredClone(input);
        expected.components[0].licenses = [{ expression: 'MIT OR Apache-2.0' }];
        const original = input;

        expect(settleLicenseFolds(input)).toBe(1);

        expect(input).toBe(original);
        expect(input).toStrictEqual(expected);
        await expect(validateBom(input)).resolves.toBeTruthy();
    });

    it('drops the expression entries after a leading license entry and keeps the rest in order (T-8)', async () => {
        const input = doc([lib('A', { licenses: [MIT, { expression: 'BSD-3-Clause OR MIT' }, CUSTOM] })]);

        expect(settleLicenseFolds(input)).toBe(1);

        expect(input.components[0].licenses).toStrictEqual([{ license: { id: 'MIT' } }, { license: { name: 'Custom', url: 'https://x' } }]);
        await expect(validateBom(input)).resolves.toBeTruthy();
    });

    it('keeps the first of two expressions (T-8)', () => {
        const input = doc([lib('A', { licenses: [{ expression: 'A OR B' }, { expression: 'C OR D' }] })]);

        expect(settleLicenseFolds(input)).toBe(1);

        expect(input.components[0].licenses).toStrictEqual([{ expression: 'A OR B' }]);
    });

    it('leaves a pure list, a single expression, an empty array, null, no key and a non-array the same object (T-8)', () => {
        const pure = [MIT, { license: { id: 'Apache-2.0' } }];
        const single = [{ expression: 'MIT OR Apache-2.0' }];
        const empty: any[] = [];
        const notArray = { expression: 'MIT OR Apache-2.0' };
        const input = doc([
            lib('PURE', { licenses: pure }),
            lib('SINGLE', { licenses: single }),
            lib('EMPTY', { licenses: empty }),
            lib('NULL', { licenses: null }),
            lib('NOKEY'),
            lib('OBJECT', { licenses: notArray })
        ]);
        const before = structuredClone(input);

        expect(settleLicenseFolds(input)).toBe(0);

        expect(input).toStrictEqual(before);
        expect(input.components[0].licenses).toBe(pure);
        expect(input.components[1].licenses).toBe(single);
        expect(input.components[2].licenses).toBe(empty);
        expect(input.components[3].licenses).toBeNull();
        expect(input.components[4]).not.toHaveProperty('licenses');
        expect(input.components[5].licenses).toBe(notArray);
    });

    it('settles metadata.component and a component nested two levels down, and counts both (T-8)', async () => {
        const root = { type: 'application', name: 'root', 'bom-ref': 'ROOT', licenses: [{ expression: 'MIT OR Apache-2.0' }, MIT] };
        const deep = lib('DEEP', { licenses: [CUSTOM, { expression: 'BSD-3-Clause OR MIT' }] });
        const input = doc([lib('A', { components: [lib('B', { components: [deep] })] }), lib('C', { licenses: [MIT] })], root);
        const expected = structuredClone(input);
        expected.metadata.component.licenses = [{ expression: 'MIT OR Apache-2.0' }];
        expected.components[0].components[0].components[0].licenses = [CUSTOM];

        expect(settleLicenseFolds(input)).toBe(2);

        expect(input).toStrictEqual(expected);
        await expect(validateBom(input)).resolves.toBeTruthy();
    });

    it('does not walk services[] (T-8)', () => {
        const mixed = [{ expression: 'MIT OR Apache-2.0' }, MIT];
        const input = { ...doc([lib('A')]), services: [{ name: 'svc', 'bom-ref': 'SVC', licenses: mixed }] };
        const before = structuredClone(input);

        expect(settleLicenseFolds(input)).toBe(0);

        expect(input).toStrictEqual(before);
        expect(input.services[0].licenses).toBe(mixed);
    });

    it('returns 0 for a document without components or a non-object (T-8)', () => {
        expect(settleLicenseFolds({ bomFormat: 'CycloneDX', specVersion: '1.6' })).toBe(0);
        expect(settleLicenseFolds(null)).toBe(0);
        expect(settleLicenseFolds(undefined)).toBe(0);
    });
});
