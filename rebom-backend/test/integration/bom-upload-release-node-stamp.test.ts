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

import { v4 as uuidv4 } from 'uuid';
import * as BomService from '../../src/bomService';
import * as BomRepository from '../../src/bomRepository';
import { pool } from '../../src/utils';
import { clearMockOciStorage, fetchFromOci, extractRepositoryNameFromBom } from '../../src/services/oci';
import { resolveProcessedTag } from '../../src/services/oci/processedBomFetcher';
import { TEST_ORG_UUID, createTestRebomOptions, generateSerialNumber, loadFixture } from '../helpers';
import { RELEASE_NODE_PROPERTY } from '../../src/services/bom/bomProcessingService';
import { REBOM_MERGE_MOD, UPLOAD_MOD } from '../../src/services/bom/releaseNodeStamp';
import type { BomOrigin } from '../../src/services/bom/bomAddService';
import { SpdxService } from '../../src/services/spdx';

/**
 * SCORE-60 (T-25, ADR-9; security F-1): an uploader cannot author rebom's release node stamp. rebom
 * removes `reliza:export:releaseNode` from an upload's processed document when it stores it (the raw
 * copy stays the uploaded document), serves a row it did not merge without it (an upload stored
 * before the ingest strip included), and stores its own merges with `meta.mod` `rebom`, served with
 * their stamps. Real addBom and mergeAndStoreBoms, the mock OCI, real `rearm bomutils merge-boms`.
 * The SPDX route's PROPERTIES are not exercised: the converter writes none. Its `meta.mod`, which
 * ADR-9 (b) guarantees is never the request's, IS exercised below (case (e)): addSpdxBom forces
 * `UPLOAD_MOD` after the `rebomOptions` spread, so a forged `mod: "rebom"` in the request never wins.
 */

const STAMP = { name: RELEASE_NODE_PROPERTY, value: 'true' };
const ORG = 'Stamp Org';

function namesEverywhere(node: any, out: string[] = []): string[] {
    if (Array.isArray(node)) { node.forEach((n) => namesEverywhere(n, out)); return out; }
    if (node && typeof node === 'object') {
        if (Array.isArray(node.properties)) node.properties.forEach((p: any) => out.push(p?.name));
        Object.values(node).forEach((v) => namesEverywhere(v, out));
    }
    return out;
}

const stampCount = (bom: any) => namesEverywhere(bom).filter((n) => n === RELEASE_NODE_PROPERTY).length;

/** An upload carrying a forged stamp on its root, a top-level library, a library nested under it and in metadata.properties. */
function forged(): any {
    return {
        bomFormat: 'CycloneDX',
        specVersion: '1.6',
        serialNumber: generateSerialNumber(),
        version: 1,
        metadata: {
            timestamp: '2026-10-23T00:00:00Z',
            component: { type: 'application', name: 'forger', version: '1.0.0', purl: 'pkg:npm/forger@1.0.0', 'bom-ref': 'pkg:npm/forger@1.0.0', properties: [STAMP] },
            properties: [STAMP]
        },
        components: [{
            type: 'library', name: 'third-party', version: '1.0.0', purl: 'pkg:npm/third-party@1.0.0', 'bom-ref': 'pkg:npm/third-party@1.0.0', properties: [STAMP],
            components: [{ type: 'library', name: 'nested', version: '1.0.0', purl: 'pkg:npm/nested@1.0.0', 'bom-ref': 'pkg:npm/nested@1.0.0', properties: [STAMP] }]
        }],
        dependencies: [
            { ref: 'pkg:npm/forger@1.0.0', dependsOn: ['pkg:npm/third-party@1.0.0'] },
            { ref: 'pkg:npm/third-party@1.0.0', dependsOn: ['pkg:npm/nested@1.0.0'] },
            { ref: 'pkg:npm/nested@1.0.0', dependsOn: [] }
        ]
    };
}

function clean(): any {
    return {
        bomFormat: 'CycloneDX',
        specVersion: '1.6',
        serialNumber: generateSerialNumber(),
        version: 1,
        metadata: { timestamp: '2026-10-23T00:00:00Z', component: { type: 'application', name: 'plain', version: '1.0.0', purl: 'pkg:npm/plain@1.0.0', 'bom-ref': 'pkg:npm/plain@1.0.0' } },
        components: [{ type: 'library', name: 'lib', version: '2.0.0', purl: 'pkg:npm/lib@2.0.0', 'bom-ref': 'pkg:npm/lib@2.0.0' }],
        dependencies: [{ ref: 'pkg:npm/plain@1.0.0', dependsOn: ['pkg:npm/lib@2.0.0'] }, { ref: 'pkg:npm/lib@2.0.0', dependsOn: [] }]
    };
}

describe('an uploaded release node stamp never reaches what rebom serves', () => {
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

    async function upload(bom: any, name: string, origin?: BomOrigin): Promise<string> {
        const input = { bomInput: { format: 'CYCLONEDX' as const, bom, org: TEST_ORG_UUID,
            rebomOptions: createTestRebomOptions({ serialNumber: bom.serialNumber, group: ORG, name, version: '1.0.0' }) } };
        const record = origin ? await BomService.addBom(input, origin) : await BomService.addBom(input);
        created.push(record.uuid);
        return record.uuid;
    }

    async function merge(ids: string[], name: string, mode: string): Promise<string> {
        const record = await BomService.mergeAndStoreBoms(ids,
            createTestRebomOptions({ serialNumber: generateSerialNumber(), group: ORG, name, version: '1.0.0', rootComponentMergeMode: mode, mergeVersion: 12 }),
            TEST_ORG_UUID, BomService.addBom);
        created.push(record.uuid);
        return record.uuid;
    }

    async function row(id: string): Promise<any> {
        const [r] = await BomRepository.bomById(id);
        return r;
    }

    async function storedBlob(id: string): Promise<any> {
        const r = await row(id);
        return fetchFromOci(resolveProcessedTag(r), extractRepositoryNameFromBom(r));
    }

    async function rawBlob(id: string): Promise<any> {
        const r = await row(id);
        return fetchFromOci(`${r.uuid}-raw`, extractRepositoryNameFromBom(r));
    }

    it('(a) stores an upload without the stamp under its processed tag, keeps the raw copy as uploaded, and serves none', async () => {
        const id = await upload(forged(), 'forger');

        expect(stampCount(await storedBlob(id))).toBe(0);
        expect(stampCount(await rawBlob(id))).toBe(4);
        expect((await row(id)).meta.mod).toBe(UPLOAD_MOD);
        expect(stampCount(await BomService.findBomObjectById(id, TEST_ORG_UUID))).toBe(0);
    });

    it('(b) stores a merge as rebom\'s own output, stamped on its root only, persisted and served', async () => {
        const forger = await upload(forged(), 'forger');
        const plain = await upload(clean(), 'plain');
        const component = await merge([forger, plain], 'stamp-api', 'FLATTEN_UNDER_NEW_ROOT');
        const product = await merge([component, plain], 'stamp-product', 'PRESERVE_UNDER_NEW_ROOT');

        for (const id of [component, product]) {
            expect((await row(id)).meta.mod).toBe(REBOM_MERGE_MOD);
        }
        const stored = await storedBlob(component);
        expect(stampCount(stored)).toBe(1);
        expect(stored.metadata.component.properties.filter((p: any) => p.name === RELEASE_NODE_PROPERTY)).toStrictEqual([STAMP]);
        const served = await BomService.findBomObjectById(component, TEST_ORG_UUID);
        expect(stampCount(served)).toBe(1);
        expect(served.metadata.component.properties.filter((p: any) => p.name === RELEASE_NODE_PROPERTY)).toStrictEqual([STAMP]);
    });

    it('(c) serves a stamped row stored before the ingest strip without the stamp, and merges it so', async () => {
        const legacy = await upload(forged(), 'forger', 'MERGE');
        await pool.query(`UPDATE rebom.boms SET meta = meta || '{"mod":"raw"}'::jsonb WHERE uuid = $1`, [legacy]);
        expect(stampCount(await storedBlob(legacy))).toBe(4);

        expect(stampCount(await BomService.findBomObjectById(legacy, TEST_ORG_UUID))).toBe(0);
        expect(JSON.stringify(await BomService.parseBomById(legacy, TEST_ORG_UUID))).not.toContain(RELEASE_NODE_PROPERTY);
        const plain = await upload(clean(), 'plain');
        const merged = await BomService.findBomObjectById(await merge([legacy, plain], 'legacy-api', 'FLATTEN_UNDER_NEW_ROOT'), TEST_ORG_UUID);
        expect(stampCount(merged)).toBe(1);
        expect(merged.metadata.component.properties.filter((p: any) => p.name === RELEASE_NODE_PROPERTY)).toStrictEqual([STAMP]);

        // the control: the row decides, not the content
        await pool.query(`UPDATE rebom.boms SET meta = meta || '{"mod":"rebom"}'::jsonb WHERE uuid = $1`, [legacy]);
        expect(stampCount(await BomService.findBomObjectById(legacy, TEST_ORG_UUID))).toBe(4);
    });

    it('(d) keeps a merge output\'s root stamped inside a product merged over it', async () => {
        const forger = await upload(forged(), 'forger');
        const plain = await upload(clean(), 'plain');
        const component = await merge([forger, plain], 'stamp-api', 'FLATTEN_UNDER_NEW_ROOT');
        const componentRoot = (await BomService.findBomObjectById(component, TEST_ORG_UUID)).metadata.component['bom-ref'];
        const other = await upload(clean(), 'other');
        const product = await BomService.findBomObjectById(await merge([component, other], 'stamp-product', 'PRESERVE_UNDER_NEW_ROOT'), TEST_ORG_UUID);

        expect(product.metadata.component.properties.filter((p: any) => p.name === RELEASE_NODE_PROPERTY)).toStrictEqual([STAMP]);
        const inner = product.components.find((c: any) => c['bom-ref'] === componentRoot);
        expect(inner.properties.filter((p: any) => p.name === RELEASE_NODE_PROPERTY)).toStrictEqual([STAMP]);
        expect(stampCount(product)).toBe(2);
    });

    it('(e) an SPDX upload cannot forge meta.mod: a request mod of "rebom" is still stored as UPLOAD_MOD', async () => {
        const convertSpy = vi.spyOn(SpdxService, 'convertSpdxToCycloneDx');
        const sn = generateSerialNumber();
        const converted = loadFixture('cyclonedx-simple.json');
        converted.serialNumber = sn;
        converted.version = 1;
        convertSpy.mockResolvedValueOnce({ success: true, convertedBom: converted } as any);

        const ns = `https://spdx.example.test/score-60/${uuidv4()}`;
        const spdxDoc = { SPDXID: 'SPDXRef-DOCUMENT', spdxVersion: 'SPDX-2.3', dataLicense: 'CC0-1.0',
            name: 'forged-mod-doc', documentNamespace: ns, packages: [] };

        const record = await BomService.addBom({
            bomInput: { bom: spdxDoc, format: 'SPDX', org: TEST_ORG_UUID,
                rebomOptions: { mod: REBOM_MERGE_MOD } }
        } as any);
        created.push(record.uuid);

        expect((await row(record.uuid)).meta.mod).toBe(UPLOAD_MOD);
        convertSpy.mockRestore();
    });
});
