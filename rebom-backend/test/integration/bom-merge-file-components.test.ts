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
import { FILE_COMPONENTS_EXCLUDED_PROPERTY } from '../../src/services/bom/bomProcessingService';
import { TEST_ORG_UUID, createTestRebomOptions, generateSerialNumber } from '../helpers';

/**
 * SCORE-11 (T-4): mergeAndStoreBoms with excludeFileComponents leaves the file components
 * out of the stored merged document and records the option in the row's meta, which is
 * what ReARM's merged-BOM cache matches on. Runs the real `rearm bomutils merge-boms`.
 */

function uploadWithFiles(name: string, version: string, files: string[], libs: string[]): any {
    const rootRef = `pkg:npm/${name}@${version}`;
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
            ...libs.map(lib => ({ type: 'library', name: lib, version: '1.0.0', purl: `pkg:npm/${lib}@1.0.0`, 'bom-ref': `pkg:npm/${lib}@1.0.0` })),
            ...files.map(file => ({ type: 'file', name: `/opt/${file}`, 'bom-ref': `file-${file}` }))
        ],
        dependencies: [
            { ref: rootRef, dependsOn: [...libs.map(lib => `pkg:npm/${lib}@1.0.0`), ...files.map(file => `file-${file}`)] }
        ]
    };
}

function allComponents(components: any[] = []): any[] {
    return components.flatMap((c: any) => [c, ...allComponents(c.components)]);
}

describe('BOM merge leaving out file components', () => {
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

    it('stores a merge without file components, its meta carrying the option', async () => {
        const id1 = await upload(uploadWithFiles('file-app-one', '1.0.0', ['tool', 'conf'], ['lodash']));
        const id2 = await upload(uploadWithFiles('file-app-two', '2.0.0', ['data'], ['axios']));

        const { record, bom } = await merge([id1, id2], { excludeFileComponents: true });

        const components = allComponents(bom.components);
        expect(components.filter((c: any) => c.type === 'file')).toEqual([]);
        expect(components.map((c: any) => c.name)).toEqual(expect.arrayContaining(['lodash', 'axios']));
        const refs = new Set(components.map((c: any) => c['bom-ref']));
        for (const dep of bom.dependencies) {
            expect(dep.ref.startsWith('file-')).toBe(false);
            for (const r of dep.dependsOn || []) expect(r.startsWith('file-')).toBe(false);
        }
        expect(refs.size).toBeGreaterThan(0);
        // The name written out on purpose: ReARM's export keeps exactly this name through its strips.
        expect(bom.metadata.properties.filter((p: any) => p.name === FILE_COMPONENTS_EXCLUDED_PROPERTY))
            .toEqual([{ name: 'reliza:export:fileComponentsExcluded', value: '3' }]);
        expect(record.meta.excludeFileComponents).toBe(true);
    });

    it('keeps the file components and writes no property when the option is absent', async () => {
        const id1 = await upload(uploadWithFiles('file-app-three', '1.0.0', ['tool', 'conf'], ['lodash']));
        const id2 = await upload(uploadWithFiles('file-app-four', '2.0.0', ['data'], ['axios']));

        const { record, bom } = await merge([id1, id2], {});

        expect(allComponents(bom.components).filter((c: any) => c.type === 'file')).toHaveLength(3);
        expect((bom.metadata.properties || []).some((p: any) => p.name === FILE_COMPONENTS_EXCLUDED_PROPERTY)).toBe(false);
        expect(record.meta.excludeFileComponents).toBeUndefined();
    });

    it('adds the counts a product merge inherits from filtered component merges', async () => {
        const id1 = await upload(uploadWithFiles('file-app-five', '1.0.0', ['tool', 'conf'], ['lodash']));
        const id2 = await upload(uploadWithFiles('file-app-six', '2.0.0', ['data'], ['axios']));
        const component1 = await merge([id1], { name: 'component-one', excludeFileComponents: true });
        const component2 = await merge([id2], { name: 'component-two', excludeFileComponents: true });

        const { bom } = await merge([component1.record.uuid, component2.record.uuid], { name: 'product', excludeFileComponents: true });

        expect(allComponents(bom.components).filter((c: any) => c.type === 'file')).toEqual([]);
        expect(bom.metadata.properties.filter((p: any) => p.name === FILE_COMPONENTS_EXCLUDED_PROPERTY))
            .toEqual([{ name: FILE_COMPONENTS_EXCLUDED_PROPERTY, value: '3' }]);
    });
});
