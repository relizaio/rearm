import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { execFileSync } from 'child_process';

// Mock integrationRepository before importing bomService (table may not exist in test DB)
vi.mock('../../src/integrationRepository', () => ({
    findIntegrationByTypeAndOrg: vi.fn().mockResolvedValue(null),
    upsertIntegration: vi.fn(),
    createSecret: vi.fn(),
    findSecretById: vi.fn(),
    updateSecret: vi.fn(),
    deleteSecret: vi.fn(),
    deleteIntegration: vi.fn(),
}));

import * as BomService from '../../src/bomService';
import { pool } from '../../src/utils';
import { clearMockOciStorage } from '../../src/services/oci';
import {
    COMPONENTS_WITHOUT_SBOM_PROPERTY,
    DANGLING_REFS_DROPPED_PROPERTY,
    FILE_COMPONENTS_EXCLUDED_PROPERTY
} from '../../src/services/bom/bomProcessingService';
import { TEST_ORG_UUID, createTestRebomOptions, generateSerialNumber } from '../helpers';

/**
 * SCORE-15 (T-11, T-12; SCORE-9 T-5): a product merge lists the component releases ReARM holds
 * no SBOM for as placeholder components, direct dependencies of the product root, whose own
 * dependencies a compositions entry states unknown. Runs the real `rearm bomutils merge-boms`
 * (T-11) and the real `rearm bomutils score` on what it stored (T-12).
 */

const FIXTURES = path.join(__dirname, '..', 'fixtures', 'merge');
const PRODUCT_PURL = 'pkg:generic/Example/product@1.0.0';
// The upload rewrites the app root's bom-ref to its purl under the test group.
const APP = 'pkg:npm/com.test/app@1.0.0';
const NGINX = 'pkg:oci/nginx@1.31.3';
const HTTPD_UUID = '0b0f4a52-6c55-4d1f-9a8e-0000000000a1';
const HTTPD = `urn:rearm:release:${HTTPD_UUID}`;

function fixture(name: string): any {
    const bom = JSON.parse(fs.readFileSync(path.join(FIXTURES, name), 'utf8'));
    bom.serialNumber = generateSerialNumber();
    return bom;
}

function httpd(extra: any = {}): any {
    return {
        releaseUuid: HTTPD_UUID, name: 'httpd', version: '2.4.68', type: 'container',
        supplierName: 'Example', group: 'Example', purl: null, reason: 'NO_SBOM_ARTIFACT', ...extra
    };
}

/** The placeholder component as the merge stores it: what ReARM sent, nothing more. */
const HTTPD_COMPONENT = {
    type: 'container', name: 'httpd', version: '2.4.68', group: 'Example', supplier: { name: 'Example' },
    'bom-ref': HTTPD, properties: [{ name: 'reliza:sbom:missing', value: 'NO_SBOM_ARTIFACT' }]
};

function property(bom: any, name: string): any[] {
    return (bom.metadata.properties || []).filter((p: any) => p.name === name);
}

function rootEntry(bom: any): any {
    return bom.dependencies.find((d: any) => d.ref === bom.metadata.component['bom-ref']);
}

function placeholders(bom: any): any[] {
    return (bom.components || []).filter((c: any) => c['bom-ref'] === HTTPD);
}

describe('product merge with a component release that has no SBOM', () => {
    const createdUuids: string[] = [];

    beforeEach(() => {
        clearMockOciStorage();
    });

    afterEach(async () => {
        if (createdUuids.length > 0) {
            await pool.query('DELETE FROM rebom.boms WHERE uuid = ANY($1::uuid[])', [createdUuids]);
            createdUuids.length = 0;
        }
    });

    async function upload(bom: any): Promise<string> {
        const record = await BomService.addBom({
            bomInput: {
                format: 'CYCLONEDX' as const,
                bom,
                org: TEST_ORG_UUID,
                rebomOptions: createTestRebomOptions({
                    serialNumber: bom.serialNumber,
                    name: bom.metadata.component.name,
                    version: bom.metadata.component.version
                })
            }
        });
        createdUuids.push(record.uuid);
        return record.uuid;
    }

    async function productMerge(ids: string[], missing: any[], overrides: any = {}): Promise<any> {
        const options = {
            ...createTestRebomOptions({
                serialNumber: generateSerialNumber(), name: 'product', group: 'Example', version: '1.0.0',
                purl: PRODUCT_PURL, rootComponentMergeMode: 'PRESERVE_UNDER_NEW_ROOT', mergeVersion: 4, ...overrides
            }),
            missingSbomComponents: missing
        };
        const record = await BomService.mergeAndStoreBoms(ids, options, TEST_ORG_UUID, BomService.addBom);
        createdUuids.push(record.uuid);
        return BomService.findBomObjectById(record.uuid, TEST_ORG_UUID);
    }

    async function twoInputs(): Promise<string[]> {
        return [await upload(fixture('app.input.cdx.json')), await upload(fixture('nginx-services.input.cdx.json'))];
    }

    for (const structure of ['FLAT', 'HIERARCHICAL']) {
        it(`lists the release as a top-level component the root depends on, dependencies unknown (${structure}, T-11)`, async () => {
            const bom = await productMerge(await twoInputs(), [httpd()], { structure });

            expect(rootEntry(bom)).toStrictEqual({ ref: PRODUCT_PURL, dependsOn: [APP, NGINX, HTTPD] });
            expect(placeholders(bom)).toStrictEqual([HTTPD_COMPONENT]);
            expect(bom.dependencies.filter((d: any) => d.ref === HTTPD)).toStrictEqual([{ ref: HTTPD, dependsOn: [] }]);
            expect(bom.compositions).toStrictEqual([{ aggregate: 'unknown', dependencies: [HTTPD] }]);
            expect(property(bom, COMPONENTS_WITHOUT_SBOM_PROPERTY)).toStrictEqual([{ name: COMPONENTS_WITHOUT_SBOM_PROPERTY, value: '1' }]);
            expect(property(bom, DANGLING_REFS_DROPPED_PROPERTY)).toStrictEqual([]);
        });
    }

    it('nests the real inputs and only them under their roots in the hierarchical merge (T-11)', async () => {
        const bom = await productMerge(await twoInputs(), [httpd()], { structure: 'HIERARCHICAL' });

        expect(bom.components.map((c: any) => c['bom-ref'])).toStrictEqual([APP, NGINX, HTTPD]);
        expect(bom.components[0].components.map((c: any) => c['bom-ref'])).toStrictEqual(['pkg:npm/a@1.0.0']);
    });

    it('leaves the placeholder untouched when the file filter takes a file out of a real input (T-11)', async () => {
        const withFile = fixture('app.input.cdx.json');
        withFile.components.push({ type: 'file', name: '/opt/app/run.sh', 'bom-ref': 'file-run' });
        withFile.dependencies[0].dependsOn.push('file-run');
        const ids = [await upload(withFile), await upload(fixture('nginx-services.input.cdx.json'))];

        const bom = await productMerge(ids, [httpd()], { excludeFileComponents: true });

        expect(property(bom, FILE_COMPONENTS_EXCLUDED_PROPERTY)).toStrictEqual([{ name: FILE_COMPONENTS_EXCLUDED_PROPERTY, value: '1' }]);
        expect(placeholders(bom)).toStrictEqual([HTTPD_COMPONENT]);
        expect(rootEntry(bom)).toStrictEqual({ ref: PRODUCT_PURL, dependsOn: [APP, NGINX, HTTPD] });
        expect(bom.compositions).toStrictEqual([{ aggregate: 'unknown', dependencies: [HTTPD] }]);
    });

    it('writes no composition and no count when every release has an SBOM (T-11)', async () => {
        const bom = await productMerge(await twoInputs(), []);

        expect(rootEntry(bom)).toStrictEqual({ ref: PRODUCT_PURL, dependsOn: [APP, NGINX] });
        expect(bom).not.toHaveProperty('compositions');
        expect(property(bom, COMPONENTS_WITHOUT_SBOM_PROPERTY)).toStrictEqual([]);
    });

    // T-12: the image pins a CLI with `bomutils score` for linux/amd64 only (SCORE-4 design 3.7).
    it.skipIf(process.arch !== 'x64')('scores the merge the same with and without the unknown composition, which no check reads (T-12)', async () => {
        const bom = await productMerge(await twoInputs(), [httpd({ purl: 'pkg:oci/httpd@2.4.68' })]);
        expect(bom.compositions).toStrictEqual([{ aggregate: 'unknown', dependencies: [HTTPD] }]);
        const withoutComposition = structuredClone(bom);
        delete withoutComposition.compositions;

        const report = score(bom);
        const checks = [...report.profiles[0].checks, ...report.structure.checks];
        const status = (id: string) => checks.find((c: any) => c.id === id)?.status;

        expect(report.errors).toStrictEqual([]);
        expect(status('cisa-2026.component-dependency-relationship')).toBe('PASS');
        expect(status('structure.refs-resolve')).toBe('PASS');
        expect(status('structure.no-orphans')).toBe('PASS');
        expect(checks.find((c: any) => c.id === 'cisa-2026.practice.unknowns')).toMatchObject({ level: 'INFO', status: 'NOT_ASSESSED' });
        const without = score(withoutComposition);
        expect(without.profiles).toStrictEqual(report.profiles);
        expect(without.structure).toStrictEqual(report.structure);
        // Nothing fails because of the placeholder: every check ends as it does for the same
        // merge without it (the per-component gaps it shares, hash and license, it shares with all).
        const plain = score(await productMerge(await twoInputs(), []));
        const statuses = (r: any) => [...r.profiles[0].checks, ...r.structure.checks].map((c: any) => [c.id, c.status]);
        expect(statuses(report)).toStrictEqual(statuses(plain));
    });
});

function score(bom: any): any {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'score15-'));
    try {
        const file = path.join(dir, 'bom.cdx.json');
        fs.writeFileSync(file, JSON.stringify(bom));
        return JSON.parse(execFileSync('rearm', ['bomutils', 'score', '--profile', 'cisa-2026', '--format', 'json', '--infile', file], { encoding: 'utf8' }));
    } finally {
        fs.rmSync(dir, { recursive: true, force: true });
    }
}
