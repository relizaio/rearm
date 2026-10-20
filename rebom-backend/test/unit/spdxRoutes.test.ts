import { describe, it, expect, vi, beforeEach } from 'vitest';

// SCORE-51 (SCORE-50a) T-2: the SPDX routes with no lineage owner. The route
// cases of the removed bomLineageOwnerSpdx.test.ts (SCORE-46 T-15 b/e/f/g) are
// rewritten here without the owner. The owner cases were removed in SCORE-54
// (the input field no longer exists): every route case runs once with empty
// rebomOptions, and these are the SPDX addBom coverage. The SPDX repository
// and the rearm-cli conversion are mocked; runQuery is also the transaction
// client, so it records every statement in order (SCORE-46 r3 shape).
const LOCK = 'SELECT pg_advisory_xact_lock(hashtext($1::uuid::text), hashtext($2))';
let latestRows: any[] = [];
const runQuery = vi.fn(async (text: string, _params?: any[]) => {
    if (text === LOCK) return { rows: [] as any[] };
    if (/^SELECT uuid, meta->>'bomVersion' AS bom_version FROM rebom\.boms/.test(text)) return { rows: latestRows };
    return { rows: [{ uuid: 'inserted' }] };
});
const pushToOci = vi.fn(async (tag: string, _doc?: any, _repo?: string) => ({
    ociRepositoryName: 'rebom-artifacts-2026-10',
    fileSHA256Digest: `digest-of-${tag}`,
    originalSize: 10
}));
const allBomsBySerialNumber = vi.fn(async (..._a: any[]) => [] as any[]);
const bomById = vi.fn(async (..._a: any[]) => [] as any[]);
const findSpdxBomBySerialNumber = vi.fn(async (..._a: any[]) => null as any);
const findSpdxBomByNamespace = vi.fn(async (..._a: any[]) => null as any);
const createSpdxBom = vi.fn(async (rec: any) => ({ ...rec }));
const linkConvertedBom = vi.fn(async (..._a: any[]) => undefined);
const updateSpdxBomConversionStatus = vi.fn(async (..._a: any[]) => undefined);

vi.mock('../../src/utils', async (importOriginal) => ({
    ...(await importOriginal<any>()),
    runQuery: (...a: any[]) => runQuery.apply(null, a as any),
    pool: {},
    withTransaction: async (fn: any) => fn({ query: (...a: any[]) => runQuery.apply(null, a as any) })
}));
vi.mock('../../src/logger', () => ({
    logger: { info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn() }
}));
vi.mock('../../src/bomRepository', () => ({
    allBomsBySerialNumber: (...a: any[]) => allBomsBySerialNumber.apply(null, a as any),
    bomById: (...a: any[]) => bomById.apply(null, a as any)
}));
vi.mock('../../src/spdxRepository', () => ({
    findSpdxBomBySerialNumber: (...a: any[]) => findSpdxBomBySerialNumber.apply(null, a as any),
    findSpdxBomByNamespace: (...a: any[]) => findSpdxBomByNamespace.apply(null, a as any),
    createSpdxBom: (...a: any[]) => createSpdxBom.apply(null, a as any),
    linkConvertedBom: (...a: any[]) => linkConvertedBom.apply(null, a as any),
    updateSpdxBomConversionStatus: (...a: any[]) => updateSpdxBomConversionStatus.apply(null, a as any)
}));
vi.mock('../../src/services/oci', async (importOriginal) => {
    const actual: any = await importOriginal();
    return {
        ...actual,
        pushToOci: (...a: any[]) => pushToOci.apply(null, a as any),
        getMonthlyRepositoryName: () => 'rebom-artifacts-2026-10'
    };
});
vi.mock('../../src/services/bom/bomProcessingService', async (importOriginal) => {
    const actual: any = await importOriginal();
    return {
        ...actual,
        getInitialEnrichmentStatus: vi.fn(async () => 'SKIPPED'),
        enrichBomAsync: vi.fn(async () => undefined)
    };
});

import { addBom } from '../../src/services/bom/bomAddService';
import { SpdxService } from '../../src/services/spdx';
import { BomValidationError, BomVersionConflictError } from '../../src/types/errors';

const S = 'urn:uuid:11111111-2222-3333-4444-555555555555';
const S_BARE = '11111111-2222-3333-4444-555555555555';
const SC = 'urn:uuid:99999999-8888-4777-8666-555555555555';
const ORG = '00000000-0000-0000-0000-000000000000';

function converted(serialNumber: string) {
    return {
        bomFormat: 'CycloneDX', specVersion: '1.5', serialNumber, version: 1,
        metadata: { timestamp: '2026-10-19T01:00:00Z' },
        components: [{ type: 'library', 'bom-ref': 'c1', name: 'left-pad', version: '1.3.0',
            purl: 'pkg:npm/left-pad@1.3.0' }]
    };
}

const spdx = (ns: string, name = 'doc') => ({
    SPDXID: 'SPDXRef-DOCUMENT', spdxVersion: 'SPDX-2.3', dataLicense: 'CC0-1.0', name,
    documentNamespace: ns, packages: []
});

const uploadSpdx = (doc: any, rebomOptions: any, existing?: string) => addBom({
    bomInput: { bom: doc, format: 'SPDX', org: ORG, existingSerialNumber: existing, rebomOptions }
} as any);

const convertSpy = vi.spyOn(SpdxService, 'convertSpdxToCycloneDx');

const statements = () => runQuery.mock.calls.map(c => String(c[0]));
const kinds = () => statements().map(text => {
    if (text === LOCK) return 'lock';
    if (text.startsWith("SELECT uuid, meta->>'bomVersion' AS bom_version FROM rebom.boms")) return 'latest';
    if (text.startsWith('INSERT INTO rebom.boms')) return 'insert';
    return text;
});
const insertCall = () => runQuery.mock.calls.find(c => String(c[0]).startsWith('INSERT INTO rebom.boms')) as any[] | undefined;

function expectNoLineageTable() {
    for (const text of statements()) {
        expect(text).not.toContain('bom_lineage');
    }
}

beforeEach(() => {
    vi.clearAllMocks();
    latestRows = [];
    allBomsBySerialNumber.mockResolvedValue([]);
    bomById.mockResolvedValue([]);
    findSpdxBomBySerialNumber.mockResolvedValue(null);
    findSpdxBomByNamespace.mockResolvedValue(null);
    convertSpy.mockResolvedValue({ success: true, convertedBom: converted(SC) } as any);
});

describe('T-2 (o): SPDX first upload', () => {
    it('accepted and stored, under the lock', async () => {
        const created: any = await uploadSpdx(spdx('ns-1'), {});

        expect(created.uuid).toBe('inserted');
        expect(pushToOci).toHaveBeenCalledTimes(2);
        expect(createSpdxBom).toHaveBeenCalledTimes(1);
        expect(createSpdxBom.mock.calls[0][0].bom_version).toBe(1);
        // T-3, SPDX: the lock, the latest-version read and the insert, nothing else.
        expect(kinds()).toStrictEqual(['lock', 'latest', 'insert']);
        expect(runQuery.mock.calls[0][1]).toStrictEqual([ORG, SC]);
        const meta = (insertCall()![1] as any[])[1];
        expect(meta.serialNumber).toBe(SC);
        expectNoLineageTable();
    });
});

describe('T-2 (a): SPDX update accepted', () => {
    it('the next version, under the lock, then linked', async () => {
        findSpdxBomBySerialNumber.mockResolvedValue({ uuid: 'spdx-old', bom_version: 1 });

        await uploadSpdx(spdx('ns-2'), {}, S_BARE);

        expect(pushToOci).toHaveBeenCalledTimes(2);
        expect(createSpdxBom).toHaveBeenCalledTimes(1);
        expect(createSpdxBom.mock.calls[0][0].bom_version).toBe(2);
        expect(kinds()).toStrictEqual(['lock', 'latest', 'insert']);
        expect(runQuery.mock.calls[0][1]).toStrictEqual([ORG, S]);
        const params = insertCall()![1] as any[];
        expect(params[5]).toBe('SPDX');
        expect(params[1].serialNumber).toBe(S);
        expect(params[1].bomVersion).toBe('2');
        const insertOrder = runQuery.mock.invocationCallOrder[2];
        expect(linkConvertedBom.mock.invocationCallOrder[0]).toBeGreaterThan(insertOrder);
        expect(updateSpdxBomConversionStatus).toHaveBeenCalledWith(expect.any(String), 'success');
        expect(updateSpdxBomConversionStatus.mock.invocationCallOrder[0]).toBeGreaterThan(insertOrder);
        expectNoLineageTable();
    });
});

describe('T-2 (b): SPDX update with no SPDX row of the serialNumber', () => {
    it('with no stored row: accepted at version 1 under the prefixed serialNumber', async () => {
        await uploadSpdx(spdx('ns-2'), {}, S_BARE);

        expect(createSpdxBom.mock.calls[0][0].bom_version).toBe(1);
        expect(kinds()).toStrictEqual(['lock', 'latest', 'insert']);
        const meta = (insertCall()![1] as any[])[1];
        expect(meta.serialNumber).toBe(S);
        expect(meta.bomVersion).toBe('1');
    });

    it('with a CycloneDX row at version 1: refused before any push or write', async () => {
        allBomsBySerialNumber.mockResolvedValue([{ uuid: 'cdx-1', meta: { bomVersion: '1', serialNumber: S } }]);

        await expect(uploadSpdx(spdx('ns-2'), {}, S_BARE)).rejects.toBeInstanceOf(BomVersionConflictError);
        expect(pushToOci).not.toHaveBeenCalled();
        expect(createSpdxBom).not.toHaveBeenCalled();
        expect(runQuery).not.toHaveBeenCalled();
    });
});

describe('T-2 (f): SPDX update not above a CycloneDX row', () => {
    it('refused before any push, naming both versions', async () => {
        findSpdxBomBySerialNumber.mockResolvedValue({ uuid: 'spdx-old', bom_version: 1 });
        allBomsBySerialNumber.mockResolvedValue([{ uuid: 'cdx-2', meta: { bomVersion: '2', serialNumber: S } }]);

        const result = uploadSpdx(spdx('ns-2'), {}, S_BARE);
        await expect(result).rejects.toBeInstanceOf(BomVersionConflictError);
        await result.catch((e: any) => {
            expect(e.storedVersion).toBe(2);
            expect(e.newVersion).toBe(2);
        });
        expect(allBomsBySerialNumber).toHaveBeenCalledWith(S, ORG);
        expect(pushToOci).not.toHaveBeenCalled();
        expect(createSpdxBom).not.toHaveBeenCalled();
        expect(runQuery).not.toHaveBeenCalled();
    });
});

describe('T-2 (c): SPDX update refused in the transaction', () => {
    it('a row stored since the pre-checks stops the insert', async () => {
        findSpdxBomBySerialNumber.mockResolvedValue({ uuid: 'spdx-old', bom_version: 1 });
        latestRows = [{ uuid: 'late', bom_version: '2' }];

        await expect(uploadSpdx(spdx('ns-2'), {}, S_BARE)).rejects.toBeInstanceOf(BomVersionConflictError);
        expect(pushToOci).toHaveBeenCalledTimes(2);
        expect(createSpdxBom).toHaveBeenCalledTimes(1);
        expect(kinds()).toStrictEqual(['lock', 'latest']);
        expect(insertCall()).toBeUndefined();
    });
});

describe('T-2 (d): identical SPDX first upload', () => {
    it('answers the stored converted row, with no push and no write', async () => {
        const doc = spdx('ns-1');
        const stored = { uuid: 'converted-1', meta: { serialNumber: SC, bomVersion: '1' } };
        findSpdxBomByNamespace.mockResolvedValue({
            uuid: 'spdx-1', file_sha256: SpdxService.calculateSpdxHash(doc), converted_bom_uuid: 'converted-1'
        });
        bomById.mockResolvedValue([stored]);

        const result: any = await uploadSpdx(doc, {});

        expect(result).toBe(stored);
        expect(bomById).toHaveBeenCalledWith('converted-1');
        expect(pushToOci).not.toHaveBeenCalled();
        expect(createSpdxBom).not.toHaveBeenCalled();
        expect(runQuery).not.toHaveBeenCalled();
    });

    it('the same namespace with different content is refused as today', async () => {
        findSpdxBomByNamespace.mockResolvedValue({
            uuid: 'spdx-1', file_sha256: 'another-hash', converted_bom_uuid: 'converted-1'
        });

        await expect(uploadSpdx(spdx('ns-1', 'changed'), {})).rejects.toBeInstanceOf(BomValidationError);
        expect(pushToOci).not.toHaveBeenCalled();
        expect(createSpdxBom).not.toHaveBeenCalled();
        expect(runQuery).not.toHaveBeenCalled();
    });
});
