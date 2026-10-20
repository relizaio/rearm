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
import { BomVersionConflictError } from '../../src/types/errors';

/**
 * SCORE-51 (SCORE-50a) T-2: the SPDX routes with no lineage owner, against the
 * migrated database. The route cases of the removed
 * bom-lineage-owner-spdx.test.ts (SCORE-46 T-16 a+d) are rewritten here without
 * the owner; the ownerComponent cases were removed in SCORE-54 (the input field
 * no longer exists). The rearm-cli conversion is not under test: it answers
 * cyclonedx-simple.json under the serialNumber the case names.
 */

const createdSerialNumbers: string[] = [];

function fresh(): string {
    const sn = generateSerialNumber();
    createdSerialNumbers.push(sn);
    return sn;
}

const bare = (sn: string) => sn.replace('urn:uuid:', '');
const namespace = () => `https://spdx.example.test/score-51/${uuidv4()}`;

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

function uploadSpdx(ns: string, existing?: string) {
    return BomService.addBom({
        bomInput: { bom: spdx(ns), format: 'SPDX', org: TEST_ORG_UUID, existingSerialNumber: existing, rebomOptions: {} }
    } as any);
}

function uploadCycloneDx(serialNumber: string, version: number) {
    const bom = loadFixture('cyclonedx-simple.json');
    bom.serialNumber = serialNumber;
    bom.version = version;
    const rebomOptions = createTestRebomOptions({ serialNumber });
    return BomService.addBom({
        bomInput: { format: 'CYCLONEDX', bom, org: TEST_ORG_UUID, rebomOptions }
    } as any);
}

async function spdxRows(ns: string): Promise<any[]> {
    const res = await pool.query(
        `SELECT * FROM rebom.spdx_boms WHERE spdx_metadata->>'documentNamespace' = $1 AND organization = $2`,
        [ns, TEST_ORG_UUID]);
    return res.rows;
}

const bomRows = (serialNumber: string) => BomRepository.allBomsBySerialNumber(serialNumber, TEST_ORG_UUID);

afterAll(async () => {
    await cleanupTestBoms(pool, createdSerialNumbers);
});

describe('T-2 (o): SPDX first upload', () => {
    it('accepted and stored', async () => {
        const S = fresh();
        const N = namespace();
        convertsTo(S);

        const created: any = await uploadSpdx(N);

        expect(created.source_format).toBe('SPDX');
        expect(created.meta.serialNumber).toBe(S);
        expect(await bomRows(S)).toHaveLength(1);
        const rows = await spdxRows(N);
        expect(rows).toHaveLength(1);
        expect(rows[0].conversion_status).toBe('success');
    });
});

describe('T-2 (a): SPDX update continues the lineage across formats', () => {
    it('from SPDX to SPDX to CycloneDX', async () => {
        const S = fresh();
        const N1 = namespace();
        const N2 = namespace();
        convertsTo(S);
        await uploadSpdx(N1);

        convertsTo(generateSerialNumber());
        const updated: any = await uploadSpdx(N2, bare(S));

        expect(updated.meta.bomVersion).toBe('2');
        expect(updated.source_format).toBe('SPDX');
        const rows = await spdxRows(N2);
        expect(rows).toHaveLength(1);
        expect(rows[0].bom_version).toBe(2);
        const served: any = await BomService.findBomObjectById(bare(S), TEST_ORG_UUID);
        expect(served.serialNumber).toBe(updated.meta.processedSerialNumber);

        await uploadCycloneDx(S, 3);
        expect(await bomRows(S)).toHaveLength(3);
    });
});

describe('T-2 (f): SPDX update not above a CycloneDX row', () => {
    it('is refused before any SPDX row is written', async () => {
        const S = fresh();
        const N1 = namespace();
        const N2 = namespace();
        convertsTo(S);
        await uploadSpdx(N1);
        await uploadCycloneDx(S, 2);

        const refusal: any = await uploadSpdx(N2, bare(S)).catch(e => e);

        expect(refusal).toBeInstanceOf(BomVersionConflictError);
        expect(refusal.storedVersion).toBe(2);
        expect(refusal.newVersion).toBe(2);
        expect(await spdxRows(N2)).toHaveLength(0);
        expect(await bomRows(S)).toHaveLength(2);
    });
});

describe('T-2 (d): identical SPDX first upload', () => {
    it('answers the stored converted row, with no second row', async () => {
        const S = fresh();
        const N = namespace();
        convertsTo(S);
        const first: any = await uploadSpdx(N);

        const again: any = await uploadSpdx(N);

        expect(again.uuid).toBe(first.uuid);
        expect(await spdxRows(N)).toHaveLength(1);
        expect(await bomRows(S)).toHaveLength(1);
    });
});
