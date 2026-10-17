import { describe, it, expect, afterAll, vi } from 'vitest';

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
import { SpdxService } from '../../src/services/spdx';
import { pool } from '../../src/utils';
import { TEST_ORG_UUID, loadFixture, createTestRebomOptions, generateSerialNumber, cleanupTestBoms } from '../helpers';
import { BomLineageOwnerError } from '../../src/types/errors';

/**
 * SCORE-46 T-16: serialNumber lineage ownership on the SPDX path (ADR-12),
 * against the migrated database. The rearm-cli conversion is not under test:
 * it answers cyclonedx-simple.json under the serialNumber the case names.
 */

const A = 'aaaaaaaa-0000-4000-8000-000000000001';
const B = 'bbbbbbbb-0000-4000-8000-000000000002';

const createdSerialNumbers: string[] = [];

function fresh(): string {
    const sn = generateSerialNumber();
    createdSerialNumbers.push(sn);
    return sn;
}

function bare(sn: string): string {
    return sn.replace('urn:uuid:', '');
}

function namespace(): string {
    return `https://spdx.example.test/score-46/${uuidv4()}`;
}

const convertSpy = vi.spyOn(SpdxService, 'convertSpdxToCycloneDx');

function convertsTo(serialNumber: string) {
    const converted = loadFixture('cyclonedx-simple.json');
    converted.serialNumber = serialNumber;
    converted.version = 1;
    convertSpy.mockResolvedValueOnce({ success: true, convertedBom: converted } as any);
}

const spdx = (ns: string) => ({
    SPDXID: 'SPDXRef-DOCUMENT', spdxVersion: 'SPDX-2.3', dataLicense: 'CC0-1.0', name: 'doc',
    documentNamespace: ns, packages: []
});

function uploadSpdx(ns: string, owner?: string, existing?: string) {
    return BomService.addBom({
        bomInput: { bom: spdx(ns), format: 'SPDX', org: TEST_ORG_UUID, existingSerialNumber: existing,
            rebomOptions: { ownerComponent: owner } }
    } as any);
}

function uploadCycloneDx(serialNumber: string, version: number, owner?: string) {
    const bom = loadFixture('cyclonedx-simple.json');
    bom.serialNumber = serialNumber;
    bom.version = version;
    const rebomOptions = createTestRebomOptions({ serialNumber });
    if (owner !== undefined) rebomOptions.ownerComponent = owner;
    return BomService.addBom({
        bomInput: { format: 'CYCLONEDX', bom, org: TEST_ORG_UUID, rebomOptions }
    } as any);
}

async function lineageRow(serialNumber: string): Promise<any> {
    const res = await pool.query(
        'SELECT * FROM rebom.bom_lineage WHERE organization = $1 AND serial_number = $2', [TEST_ORG_UUID, serialNumber]);
    return res.rows[0];
}

async function spdxRows(ns: string): Promise<any[]> {
    const res = await pool.query(
        `SELECT * FROM rebom.spdx_boms WHERE spdx_metadata->>'documentNamespace' = $1 AND organization = $2`,
        [ns, TEST_ORG_UUID]);
    return res.rows;
}

async function bomRows(serialNumber: string): Promise<any[]> {
    return BomRepository.allBomsBySerialNumber(serialNumber, TEST_ORG_UUID);
}

afterAll(async () => {
    await cleanupTestBoms(pool, createdSerialNumbers);
});

describe('SPDX-born lineage', () => {
    const SC1 = fresh();
    const N1 = namespace();
    const N2 = namespace();

    it('T-16 a: a first SPDX upload with an owner claims the converter serialNumber', async () => {
        convertsTo(SC1);
        const created: any = await uploadSpdx(N1, A);

        const row = await lineageRow(SC1);
        expect(row.owner_component).toBe(A);
        expect(row.claimed_via).toBe('UPLOAD');
        expect(created.source_format).toBe('SPDX');
        expect(created.source_spdx_uuid).toBeTruthy();
        expect(created.meta.ownerComponent).toBe(A);
        const spdxRecord = await spdxRows(N1);
        expect(spdxRecord).toHaveLength(1);
        expect(spdxRecord[0].conversion_status).toBe('success');
    });

    it('T-16 b: an SPDX update from another component is refused and writes nothing', async () => {
        await expect(uploadSpdx(N2, B, bare(SC1))).rejects.toBeInstanceOf(BomLineageOwnerError);
        expect(await bomRows(SC1)).toHaveLength(1);
        expect(await spdxRows(N2)).toHaveLength(0);
        expect((await lineageRow(SC1)).owner_component).toBe(A);
    });

    it('T-16 c: an SPDX update with no owner is refused the same way', async () => {
        await expect(uploadSpdx(N2, undefined, bare(SC1))).rejects.toBeInstanceOf(BomLineageOwnerError);
        expect(await bomRows(SC1)).toHaveLength(1);
        expect(await spdxRows(N2)).toHaveLength(0);
    });

    it('T-16 d: the owner updates, and continues its lineage across formats', async () => {
        convertsTo(generateSerialNumber());
        const updated: any = await uploadSpdx(N2, A, bare(SC1));

        expect(updated.meta.bomVersion).toBe('2');
        expect(updated.source_format).toBe('SPDX');
        const spdxRecord = await spdxRows(N2);
        expect(spdxRecord).toHaveLength(1);
        expect(spdxRecord[0].bom_version).toBe(2);
        const row = await lineageRow(SC1);
        expect(row.owner_component).toBe(A);
        expect(row.claimed_via).toBe('UPLOAD');
        const served: any = await BomService.findBomObjectById(bare(SC1), TEST_ORG_UUID);
        expect(served.serialNumber).toBe(updated.meta.processedSerialNumber);

        await expect(uploadCycloneDx(SC1, 3, B)).rejects.toBeInstanceOf(BomLineageOwnerError);
        expect(await bomRows(SC1)).toHaveLength(2);
        await uploadCycloneDx(SC1, 3, A);
        expect(await bomRows(SC1)).toHaveLength(3);
    });
});

describe('SPDX first upload with no owner', () => {
    it('T-16 e: leaves the lineage unowned until an owner-bearing upload claims it', async () => {
        const SC2 = fresh();
        convertsTo(SC2);
        await uploadSpdx(namespace());
        expect(await lineageRow(SC2)).toBeUndefined();

        await uploadCycloneDx(SC2, 2, A);
        const row = await lineageRow(SC2);
        expect(row.owner_component).toBe(A);
        expect(row.claimed_via).toBe('UPLOAD');
    });
});

describe('cleanup', () => {
    it('T-16: cleanupTestBoms leaves no boms, spdx_boms or bom_lineage row behind', async () => {
        const SC3 = generateSerialNumber();
        const N3 = namespace();
        convertsTo(SC3);
        await uploadSpdx(N3, A);
        expect(await lineageRow(SC3)).toBeDefined();

        await cleanupTestBoms(pool, [SC3]);
        expect(await bomRows(SC3)).toHaveLength(0);
        expect(await spdxRows(N3)).toHaveLength(0);
        expect(await lineageRow(SC3)).toBeUndefined();
    });
});
