import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';

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
import { BOM_REFS_DEDUPLICATED_PROPERTY, FILE_COMPONENTS_EXCLUDED_PROPERTY } from '../../src/services/bom/bomProcessingService';
import { TEST_ORG_UUID, createTestRebomOptions, generateSerialNumber } from '../helpers';

/**
 * SCORE-13: an uploaded SBOM that repeats a component inside its own subtree under the same
 * bom-ref (cdxgen-style nesting) reaches the stored merge once, with the copy's fields and
 * children kept, in FLAT and HIERARCHICAL, with and without the file switch. Runs the real
 * `rearm bomutils merge-boms`, which reads only the top level of its inputs.
 */

const SUPPLIER = { name: 'Example Supplier' };

/** A top-level component X whose components[] holds X again, without a supplier. */
function uploadWithNestedSelf(name: string, version: string): any {
    const rootRef = `pkg:npm/${name}@${version}`;
    const x = `pkg:maven/io.example/${name}-backend@${version}?type=jar`;
    return {
        bomFormat: 'CycloneDX',
        specVersion: '1.6',
        serialNumber: generateSerialNumber(),
        version: 1,
        metadata: {
            timestamp: '2026-10-07T00:00:00Z',
            component: { type: 'application', name, version, purl: rootRef, 'bom-ref': rootRef }
        },
        components: [
            { type: 'library', name: 'lodash', version: '1.0.0', purl: 'pkg:npm/lodash@1.0.0', 'bom-ref': 'pkg:npm/lodash@1.0.0' },
            {
                type: 'application', name: `${name}-backend`, version, purl: x, 'bom-ref': x, supplier: SUPPLIER,
                components: [
                    {
                        type: 'application', name: `${name}-backend`, version, purl: x, 'bom-ref': x,
                        properties: [{ name: 'cdx:nested', value: 'true' }],
                        components: [
                            { type: 'library', name: `${name}-inner`, version: '1.0.0', purl: `pkg:maven/io.example/${name}-inner@1.0.0`, 'bom-ref': `pkg:maven/io.example/${name}-inner@1.0.0` },
                            { type: 'file', name: '/opt/inner-file', 'bom-ref': `file-${name}` }
                        ]
                    }
                ]
            },
            { type: 'library', name: 'axios', version: '1.0.0', purl: 'pkg:npm/axios@1.0.0', 'bom-ref': 'pkg:npm/axios@1.0.0' }
        ],
        dependencies: [
            { ref: rootRef, dependsOn: ['pkg:npm/lodash@1.0.0', x, 'pkg:npm/axios@1.0.0'] },
            { ref: x, dependsOn: [`pkg:maven/io.example/${name}-inner@1.0.0`, `file-${name}`] }
        ]
    };
}

function allComponents(components: any[] = []): any[] {
    return components.flatMap((c: any) => [c, ...allComponents(c.components)]);
}

function declaredRefs(bom: any): string[] {
    const root = bom.metadata?.component;
    return [root, ...allComponents(root?.components), ...allComponents(bom.components)]
        .map((c: any) => c?.['bom-ref'])
        .filter((r: any) => typeof r === 'string');
}

function property(bom: any, name: string): any[] {
    return (bom.metadata.properties || []).filter((p: any) => p.name === name);
}

describe('BOM merge with a component nested inside itself', () => {
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

    async function upload(bom: any, extra: any = {}): Promise<string> {
        const record = await BomService.addBom({
            bomInput: {
                format: 'CYCLONEDX' as const,
                bom,
                org: TEST_ORG_UUID,
                rebomOptions: createTestRebomOptions({
                    serialNumber: bom.serialNumber,
                    name: bom.metadata.component.name,
                    version: bom.metadata.component.version,
                    ...extra
                })
            }
        });
        createdUuids.push(record.uuid);
        return record.uuid;
    }

    async function merge(ids: string[], overrides: any): Promise<{ record: any, bom: any }> {
        const record = await BomService.mergeAndStoreBoms(
            ids,
            createTestRebomOptions({ serialNumber: generateSerialNumber(), name: 'merged', version: '1.0.0', ...overrides }),
            TEST_ORG_UUID,
            BomService.addBom
        );
        createdUuids.push(record.uuid);
        const bom = await BomService.findBomObjectById(record.uuid, TEST_ORG_UUID) as any;
        return { record, bom };
    }

    for (const structure of ['FLAT', 'HIERARCHICAL']) {
        it(`stores one copy with the fields and children of both, ${structure}`, async () => {
            const id1 = await upload(uploadWithNestedSelf('dup-app-one', '1.0.0'));
            const id2 = await upload(uploadWithNestedSelf('dup-app-two', '2.0.0'));

            const { record, bom } = await merge([id1, id2], { structure, mergeVersion: 2 });

            const refs = declaredRefs(bom);
            expect(new Set(refs).size).toBe(refs.length);
            const backends = allComponents(bom.components).filter((c: any) => c.name.endsWith('-backend'));
            expect(backends.map((c: any) => c.name)).toStrictEqual(['dup-app-one-backend', 'dup-app-two-backend']);
            for (const backend of backends) {
                expect(backend.supplier).toStrictEqual(SUPPLIER);
                expect(backend.properties).toStrictEqual([{ name: 'cdx:nested', value: 'true' }]);
                expect(backend.components.map((c: any) => c.name)).toStrictEqual([`${backend.name.replace(/-backend$/, '')}-inner`, '/opt/inner-file']);
            }
            // FLAT: the two nested copies. HIERARCHICAL also hangs the libraries both inputs
            // share (lodash, axios) under each input's root, with no key check: two more.
            const folded = structure === 'FLAT' ? '2' : '4';
            expect(property(bom, BOM_REFS_DEDUPLICATED_PROPERTY)).toStrictEqual([{ name: 'reliza:export:bomRefsDeduplicated', value: folded }]);
            // Once each; their order is merge-boms' own (FLAT sorts), not this pass's.
            expect(allComponents(bom.components).filter((c: any) => ['lodash', 'axios'].includes(c.name)).map((c: any) => c.name).sort())
                .toStrictEqual(['axios', 'lodash']);
            expect(record.meta.mergeVersion).toBe(2);
        });
    }

    it('leaves the nested file out once with the file switch', async () => {
        const id1 = await upload(uploadWithNestedSelf('dup-app-three', '1.0.0'));
        const id2 = await upload(uploadWithNestedSelf('dup-app-four', '2.0.0'));

        const { bom } = await merge([id1, id2], { excludeFileComponents: true, mergeVersion: 2 });

        const refs = declaredRefs(bom);
        expect(new Set(refs).size).toBe(refs.length);
        expect(allComponents(bom.components).filter((c: any) => c.type === 'file')).toStrictEqual([]);
        expect(property(bom, FILE_COMPONENTS_EXCLUDED_PROPERTY)).toStrictEqual([{ name: FILE_COMPONENTS_EXCLUDED_PROPERTY, value: '2' }]);
        expect(property(bom, BOM_REFS_DEDUPLICATED_PROPERTY)).toStrictEqual([{ name: BOM_REFS_DEDUPLICATED_PROPERTY, value: '2' }]);
    });

    it('folds a root that repeats itself under metadata.component.components', async () => {
        const name = 'dup-root-app';
        const rootPurl = `pkg:npm/${name}@1.0.0`;
        const bomIn = uploadWithNestedSelf(name, '1.0.0');
        bomIn.metadata.component.supplier = SUPPLIER;
        bomIn.metadata.component.components = [
            { type: 'application', name, version: '1.0.0', purl: rootPurl, 'bom-ref': rootPurl, description: 'module copy' }
        ];
        // The release purl equals the uploaded root's, so the root keeps its bom-ref at upload.
        const id1 = await upload(bomIn, { purl: rootPurl });
        const id2 = await upload(uploadWithNestedSelf('dup-root-other', '2.0.0'));

        const { bom } = await merge([id1, id2], { mergeVersion: 2 });

        const refs = declaredRefs(bom);
        expect(new Set(refs).size).toBe(refs.length);
        expect(refs.filter((r: string) => r === rootPurl)).toHaveLength(1);
    });

    it('stores the merge unchanged, without the property or a mergeVersion, when nothing repeats', async () => {
        const plain = uploadWithNestedSelf('plain-app', '1.0.0');
        delete plain.components[1].components;
        const id1 = await upload(plain);

        const { record, bom } = await merge([id1], {});

        expect(property(bom, BOM_REFS_DEDUPLICATED_PROPERTY)).toStrictEqual([]);
        expect(record.meta.mergeVersion).toBeUndefined();
    });
});
