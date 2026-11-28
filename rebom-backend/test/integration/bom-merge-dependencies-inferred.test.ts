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
import * as BomRepository from '../../src/bomRepository';
import { pool } from '../../src/utils';
import { clearMockOciStorage, fetchFromOci, extractRepositoryNameFromBom } from '../../src/services/oci';
import { resolveProcessedTag } from '../../src/services/oci/processedBomFetcher';
import { TEST_ORG_UUID, createTestRebomOptions, generateSerialNumber } from '../helpers';
import { DEPENDENCIES_INFERRED_PROPERTY, ORPHAN_COMPONENTS_LINKED_PROPERTY } from '../../src/services/bom/bomProcessingService';

/**
 * FDAW-160 3.1 (architecture tests 3 to 5, threat model T-1): a merge of an SBOM without
 * `dependencies[]` records on its root how many components it linked under it and states that root's
 * dependencies unknown in a compositions entry; a product merged over that merge output keeps the record
 * on the component's node (real `rearm bomutils merge-boms`) and names that node; a full graph yields
 * neither; and an uploaded copy of the record never reaches what rebom serves.
 */

const ORG = 'Inferred Org';
const LIBS = ['axios', 'express', 'left-pad', 'lodash', 'minimist'];

function firmware(withGraph: boolean, rootProperties?: any[]): any {
    const root = 'pkg:generic/acme/insupump-firmware@2.1.0';
    const libs = LIBS.map((n) => ({ type: 'library', name: n, version: '1.0.0', purl: `pkg:npm/${n}@1.0.0`, 'bom-ref': `pkg:npm/${n}@1.0.0` }));
    return {
        bomFormat: 'CycloneDX',
        specVersion: '1.6',
        serialNumber: generateSerialNumber(),
        version: 1,
        metadata: {
            timestamp: '2026-11-28T00:00:00Z',
            component: { type: 'firmware', name: 'insupump-firmware', version: '2.1.0', purl: root, 'bom-ref': root, ...(rootProperties ? { properties: rootProperties } : {}) }
        },
        components: libs,
        ...(withGraph ? { dependencies: [{ ref: root, dependsOn: libs.map((l) => l['bom-ref']) }, ...libs.map((l) => ({ ref: l['bom-ref'], dependsOn: [] }))] } : {})
    };
}

const recordsOf = (node: any) => (node?.properties || []).filter((p: any) => p.name === DEPENDENCIES_INFERRED_PROPERTY);
const metadataCount = (bom: any) => (bom.metadata.properties || []).find((p: any) => p.name === ORPHAN_COMPONENTS_LINKED_PROPERTY)?.value;

function allNodes(bom: any): any[] {
    const out: any[] = [];
    const visit = (c: any) => { if (!c) return; out.push(c); (c.components || []).forEach(visit); };
    visit(bom.metadata?.component);
    (bom.components || []).forEach(visit);
    return out;
}

describe('a merge discloses the dependency links it inferred (FDAW-160)', () => {
    const created: string[] = [];

    beforeEach(() => {
        clearMockOciStorage();
    });

    afterEach(async () => {
        if (created.length > 0) {
            await pool.query('DELETE FROM rebom.boms WHERE uuid = ANY($1::uuid[])', [created]);
            created.length = 0;
        }
    });

    async function upload(bom: any, name: string): Promise<string> {
        const record = await BomService.addBom({ bomInput: { format: 'CYCLONEDX' as const, bom, org: TEST_ORG_UUID,
            rebomOptions: createTestRebomOptions({ serialNumber: bom.serialNumber, group: ORG, name, version: '1.0.0' }) } });
        created.push(record.uuid);
        return record.uuid;
    }

    async function merge(ids: string[], name: string, mode: string): Promise<string> {
        const record = await BomService.mergeAndStoreBoms(ids,
            createTestRebomOptions({ serialNumber: generateSerialNumber(), group: ORG, name, version: '1.0.0', rootComponentMergeMode: mode, mergeVersion: 21 }),
            TEST_ORG_UUID, BomService.addBom);
        created.push(record.uuid);
        return record.uuid;
    }

    async function storedBlob(id: string): Promise<any> {
        const [r] = await BomRepository.bomById(id);
        return fetchFromOci(resolveProcessedTag(r), extractRepositoryNameFromBom(r));
    }

    it('a component merge of an input without dependencies[] ends with the root record, the entry and orphanComponentsLinked equal', async () => {
        const component = await BomService.findBomObjectById(await merge([await upload(firmware(false), 'fw')], 'fw-release', 'FLATTEN_UNDER_NEW_ROOT'), TEST_ORG_UUID);

        const linked = metadataCount(component);
        expect(Number(linked)).toBeGreaterThanOrEqual(LIBS.length);
        expect(recordsOf(component.metadata.component)).toStrictEqual([{ name: DEPENDENCIES_INFERRED_PROPERTY, value: linked }]);
        expect(component.compositions).toContainEqual({ aggregate: 'unknown', dependencies: [component.metadata.component['bom-ref']] });
    });

    it('a product merged over that merge output keeps the record on the component node, not on its own root, and names that node', async () => {
        const componentId = await merge([await upload(firmware(false), 'fw')], 'fw-release', 'FLATTEN_UNDER_NEW_ROOT');
        const component = await BomService.findBomObjectById(componentId, TEST_ORG_UUID);
        const componentRef = component.metadata.component['bom-ref'];
        const product = await BomService.findBomObjectById(await merge([componentId], 'device', 'PRESERVE_UNDER_NEW_ROOT'), TEST_ORG_UUID);

        expect(recordsOf(product.metadata.component)).toStrictEqual([]);
        const node = allNodes(product).find((c: any) => c !== product.metadata.component && c['bom-ref'] === componentRef);
        expect(node).toBeDefined();
        expect(recordsOf(node)).toStrictEqual(recordsOf(component.metadata.component));
        const inferredEntries = (product.compositions || []).filter((e: any) => e.dependencies?.includes(componentRef));
        expect(inferredEntries).toStrictEqual([{ aggregate: 'unknown', dependencies: [componentRef] }]);
    });

    it('a merge of an input with a full graph adds no record and no entry', async () => {
        const componentId = await merge([await upload(firmware(true), 'fw-graph')], 'fw-graph-release', 'FLATTEN_UNDER_NEW_ROOT');
        const component = await BomService.findBomObjectById(componentId, TEST_ORG_UUID);
        const product = await BomService.findBomObjectById(await merge([componentId], 'device-graph', 'PRESERVE_UNDER_NEW_ROOT'), TEST_ORG_UUID);

        for (const bom of [component, product]) {
            expect(allNodes(bom).flatMap(recordsOf)).toStrictEqual([]);
            expect(JSON.stringify(bom.compositions || [])).not.toContain(component.metadata.component['bom-ref']);
        }
    });

    it('an upload whose root carries the record is stored and served without it (T-1)', async () => {
        const id = await upload(firmware(true, [{ name: DEPENDENCIES_INFERRED_PROPERTY, value: '7' }, { name: 'acme:keep', value: 'yes' }]), 'forged');

        expect(recordsOf((await storedBlob(id)).metadata.component)).toStrictEqual([]);
        const served = await BomService.findBomObjectById(id, TEST_ORG_UUID);
        expect(recordsOf(served.metadata.component)).toStrictEqual([]);
        expect(served.metadata.component.properties).toContainEqual({ name: 'acme:keep', value: 'yes' });
        // and a merge over it records nothing: its graph is full
        const merged = await BomService.findBomObjectById(await merge([id], 'forged-release', 'FLATTEN_UNDER_NEW_ROOT'), TEST_ORG_UUID);
        expect(allNodes(merged).flatMap(recordsOf)).toStrictEqual([]);
    });
});
