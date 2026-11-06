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
import {
    MERGE_COUNT_PROPERTIES,
    DEV_COMPONENTS_EXCLUDED_PROPERTY,
    EXCLUDED_SCOPE_DROPPED_PROPERTY,
    PERSONAL_DATA_STRIPPED_PROPERTY,
    LOCAL_PATHS_STRIPPED_PROPERTY,
    CREDENTIALS_STRIPPED_PROPERTY,
    ORPHAN_COMPONENTS_LINKED_PROPERTY
} from '../../src/services/bom/bomProcessingService';
import { TEST_ORG_UUID, loadFixture, createTestRebomOptions, generateSerialNumber } from '../helpers';

/**
 * FDAW-6 T12 (architecture 4): the six records on rebom's existing cdxgen container fixtures, each
 * merged alone under the default knobs. Real `rearm bomutils merge-boms`.
 */

function allComponents(components: any[] = []): any[] {
    return components.flatMap((c: any) => [c, ...allComponents(c.components)]);
}

describe('the six merge records on the existing cdxgen fixtures (FDAW-6 T12)', () => {
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

    for (const file of ['cyclonedx-raw-cdxgen.json', 'cyclonedx-augmented.json']) {
        it(`${file}: 214 absolute SrcFile paths, 93 author names with an address, 204 orphans linked, nothing else`, async () => {
            const input = loadFixture(file);
            input.serialNumber = generateSerialNumber();
            const uploaded = await BomService.addBom({
                bomInput: {
                    format: 'CYCLONEDX' as const, bom: input, org: TEST_ORG_UUID,
                    rebomOptions: createTestRebomOptions({ serialNumber: input.serialNumber, name: input.metadata.component.name, version: input.metadata.component.version })
                }
            });
            createdUuids.push(uploaded.uuid);
            const stored = await BomService.mergeAndStoreBoms([uploaded.uuid],
                createTestRebomOptions({ serialNumber: generateSerialNumber(), name: 'merged', group: 'Example', version: '1.0.0' }), TEST_ORG_UUID, BomService.addBom);
            createdUuids.push(stored.uuid);
            const bom: any = await BomService.findBomObjectById(stored.uuid, TEST_ORG_UUID);

            // 251 components, no scope or dev property: nothing dropped; the 214 SrcFile values are all
            // absolute (/usr/local/lib/node_modules/...); no URL carries a userinfo with a credential; the
            // 180 authors carry no email field, but 93 of their names carry an address token (`Name <a@b.c>`,
            // the FDAW-6 departure on S3; `me@shannonmoeller` has no domain and stays); 204 of the 251
            // top-level components are in no dependency entry of the input (a container SBOM with 43 entries)
            expect(Object.fromEntries(MERGE_COUNT_PROPERTIES.map(n => [n, bom.metadata.properties.find((p: any) => p.name === n)?.value]))).toStrictEqual({
                [DEV_COMPONENTS_EXCLUDED_PROPERTY]: '0',
                [EXCLUDED_SCOPE_DROPPED_PROPERTY]: '0',
                [PERSONAL_DATA_STRIPPED_PROPERTY]: '93',
                [LOCAL_PATHS_STRIPPED_PROPERTY]: '214',
                [CREDENTIALS_STRIPPED_PROPERTY]: '0',
                [ORPHAN_COMPONENTS_LINKED_PROPERTY]: '204'
            });
            const components = allComponents(bom.components);
            expect(components.flatMap((c: any) => c.properties || []).filter((p: any) => p.name === 'SrcFile')).toStrictEqual([]);
            expect(components.flatMap((c: any) => c.properties || []).filter((p: any) => p.name === 'ResolvedUrl').length)
                .toBe(allComponents(input.components).flatMap((c: any) => c.properties || []).filter((p: any) => p.name === 'ResolvedUrl').length);
            // the 251 uploaded components and the uploaded root, unfolded under the merged root
            expect(bom.components).toHaveLength(252);
            const root = bom.dependencies.find((d: any) => d.ref === bom.metadata.component['bom-ref']);
            const named = new Set(bom.dependencies.flatMap((d: any) => [d.ref, ...(d.dependsOn || [])]));
            expect(bom.components.every((c: any) => named.has(c['bom-ref']))).toBe(true);
            expect(root.dependsOn.length).toBeGreaterThanOrEqual(204);
        });
    }
});
