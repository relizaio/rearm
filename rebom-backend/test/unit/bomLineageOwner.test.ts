import { describe, it, expect, vi, beforeEach } from 'vitest';

// SCORE-46: the serialNumber lineage owner check and claim in addBom (CycloneDX).
// Storage, registry and the lineage table are observed through mocks; the
// transaction client's statements land on the same runQuery spy, so "nothing
// written" is runQuery not called, as in bomVersionConflict.test.ts.
const runQuery = vi.fn(async (..._a: any[]) => ({ rows: [{ uuid: 'inserted' }] }));
const pushToOci = vi.fn(async (tag: string) => ({
    ociRepositoryName: 'rebom-artifacts-2026-09',
    fileSHA256Digest: `digest-of-${tag}`,
    originalSize: 10
}));
const allBomsBySerialNumber = vi.fn(async () => [] as any[]);
const find = vi.fn(async (..._a: any[]) => null as any);
const claimInTransaction = vi.fn(async (..._a: any[]) => null as any);

vi.mock('../../src/utils', () => ({
    runQuery: (...a: any[]) => runQuery.apply(null, a as any),
    pool: {},
    withTransaction: async (fn: any) => fn({ query: (...a: any[]) => runQuery.apply(null, a as any) })
}));
vi.mock('../../src/logger', () => ({
    logger: { info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn() }
}));
vi.mock('../../src/bomRepository', () => ({
    allBomsBySerialNumber: (...a: any[]) => allBomsBySerialNumber.apply(null, a as any),
    bomById: vi.fn(async () => [])
}));
vi.mock('../../src/bomLineageRepository', async (importOriginal) => ({
    ...(await importOriginal<any>()),
    find: (...a: any[]) => find.apply(null, a as any),
    claimInTransaction: (...a: any[]) => claimInTransaction.apply(null, a as any)
}));
vi.mock('../../src/services/oci', async (importOriginal) => {
    const actual: any = await importOriginal();
    return {
        ...actual,
        pushToOci: (...a: any[]) => pushToOci.apply(null, a as any),
        getMonthlyRepositoryName: () => 'rebom-artifacts-2026-09'
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
import { BomLineageOwnerError, BomValidationError, BomVersionConflictError } from '../../src/types/errors';
import { logger } from '../../src/logger';

const warn = logger.warn as unknown as ReturnType<typeof vi.fn>;

const SERIAL = 'urn:uuid:11111111-2222-3333-4444-555555555555';
const ORG = '00000000-0000-0000-0000-000000000000';
const A = 'aaaaaaaa-0000-4000-8000-000000000001';
const B = 'bbbbbbbb-0000-4000-8000-000000000002';
const MSG = `BOM with serialNumber ${SERIAL} belongs to another component of this organization; ` +
    'a new version of it can only be uploaded to that component. Upload an SBOM with its own serialNumber instead.';

function bom(version: number, componentName = 'left-pad') {
    return {
        bomFormat: 'CycloneDX', specVersion: '1.5', serialNumber: SERIAL, version,
        metadata: { timestamp: '2026-09-18T01:00:00Z' },
        components: [{ type: 'library', 'bom-ref': 'c1', name: componentName, version: '1.3.0',
            purl: `pkg:npm/${componentName}@1.3.0` }]
    };
}

function storedRow(version: number, rawDigest: string) {
    return {
        uuid: 'existing-uuid',
        meta: { bomVersion: String(version), originalFileDigest: rawDigest, serialNumber: SERIAL },
        bom: { ociRepositoryName: 'rebom-artifacts-2026-08' }
    };
}

const rebomOptions = () => ({
    name: 'demo-component', group: 'io.reliza', version: '1.0.0',
    belongsTo: 'COMPONENT', structure: 'FLAT', bomState: 'RAW', stripBom: 'false',
    tldOnly: false, notes: '', storage: 'OCI', mod: 'raw'
});

const upload = (b: any, owner?: any) => addBom({
    bomInput: { format: 'CYCLONEDX', org: ORG, bom: b, tags: [],
        rebomOptions: owner === undefined ? rebomOptions() : { ...rebomOptions(), ownerComponent: owner } }
} as any);

function insertedMeta(): any {
    const call = runQuery.mock.calls.find(c => String(c[0]).startsWith('INSERT INTO rebom.boms'));
    return call ? (call[1] as any[])[1] : undefined;
}

beforeEach(() => {
    vi.clearAllMocks();
    allBomsBySerialNumber.mockResolvedValue([]);
    find.mockResolvedValue(null);
    claimInTransaction.mockResolvedValue(null);
});

describe('owner matches', () => {
    it('T-1: the owner uploads a higher version: claim, then the insert, in the transaction', async () => {
        find.mockResolvedValue({ owner_component: A });
        allBomsBySerialNumber.mockResolvedValue([storedRow(1, 'some-other-digest')] as any);
        claimInTransaction.mockResolvedValue(A);

        await upload(bom(2, 'right-pad'), A);

        expect(pushToOci).toHaveBeenCalledTimes(2);
        expect(claimInTransaction).toHaveBeenCalledTimes(1);
        expect(claimInTransaction).toHaveBeenCalledWith(expect.objectContaining({ query: expect.any(Function) }), ORG, SERIAL, A);
        expect(runQuery).toHaveBeenCalledTimes(1);
        expect(String(runQuery.mock.calls[0][0]).startsWith('INSERT INTO rebom.boms')).toBe(true);
        expect(claimInTransaction.mock.invocationCallOrder[0]).toBeLessThan(runQuery.mock.invocationCallOrder[0]);
        expect(insertedMeta().ownerComponent).toBe(A);
    });
});

describe('foreign owner', () => {
    it('T-2: another component is refused before anything is pushed or written', async () => {
        find.mockResolvedValue({ owner_component: A });
        allBomsBySerialNumber.mockResolvedValue([storedRow(1, 'some-other-digest')] as any);

        const result = upload(bom(2, 'right-pad'), B);
        await expect(result).rejects.toBeInstanceOf(BomLineageOwnerError);
        await result.catch((e: any) => {
            expect(e.message).toBe(MSG);
            expect(e.serialNumber).toBe(SERIAL);
        });
        expect(pushToOci).not.toHaveBeenCalled();
        expect(claimInTransaction).not.toHaveBeenCalled();
        expect(runQuery).not.toHaveBeenCalled();
        expect(warn).toHaveBeenCalledTimes(1);
        expect(warn).toHaveBeenCalledWith(expect.objectContaining({
            uploadingComponent: B, ownerComponent: A, stage: 'precheck', path: 'CYCLONEDX', serialNumber: SERIAL
        }), 'BOM lineage owner mismatch: upload refused');
    });
});

describe('null owner', () => {
    it('T-3 a: a null caller is refused on an owned lineage, nothing pushed', async () => {
        find.mockResolvedValue({ owner_component: A });
        allBomsBySerialNumber.mockResolvedValue([storedRow(1, 'some-other-digest')] as any);

        await expect(upload(bom(2, 'right-pad'))).rejects.toBeInstanceOf(BomLineageOwnerError);
        expect(pushToOci).not.toHaveBeenCalled();
        expect(runQuery).not.toHaveBeenCalled();
    });

    it('T-3 b: a null caller on an unowned lineage stores as today, the claim step with a null caller', async () => {
        allBomsBySerialNumber.mockResolvedValue([storedRow(1, 'some-other-digest')] as any);

        await upload(bom(2, 'right-pad'));

        expect(pushToOci).toHaveBeenCalledTimes(2);
        expect(claimInTransaction).toHaveBeenCalledTimes(1);
        expect(claimInTransaction.mock.calls[0][3]).toBeNull();
        expect(runQuery).toHaveBeenCalledTimes(1);
        expect(String(runQuery.mock.calls[0][0]).startsWith('INSERT INTO rebom.boms')).toBe(true);
        expect(claimInTransaction.mock.invocationCallOrder[0]).toBeLessThan(runQuery.mock.invocationCallOrder[0]);
    });

    it('T-3 c: a lineage row with no owner does not refuse a null caller', async () => {
        find.mockResolvedValue({ owner_component: null });
        allBomsBySerialNumber.mockResolvedValue([storedRow(1, 'some-other-digest')] as any);

        await upload(bom(2, 'right-pad'));

        expect(pushToOci).toHaveBeenCalledTimes(2);
        expect(claimInTransaction.mock.calls[0][3]).toBeNull();
        expect(runQuery).toHaveBeenCalledTimes(1);
    });

    it('T-3 d: an empty ownerComponent is a null owner', async () => {
        allBomsBySerialNumber.mockResolvedValue([storedRow(1, 'some-other-digest')] as any);

        await upload(bom(2, 'right-pad'), '');

        expect(pushToOci).toHaveBeenCalledTimes(2);
        expect(claimInTransaction.mock.calls[0][3]).toBeNull();
        expect(runQuery).toHaveBeenCalledTimes(1);
    });
});

describe('identical bytes', () => {
    async function storedDigestOf(b: any) {
        const { createHash } = await import('crypto');
        return createHash('sha256').update(JSON.stringify(b)).digest('hex');
    }

    it('T-4: identical bytes from a foreign owner dedupe before the lineage is read', async () => {
        const raw = bom(1);
        allBomsBySerialNumber.mockResolvedValue([storedRow(1, await storedDigestOf(raw))] as any);
        find.mockResolvedValue({ owner_component: A });

        const result: any = await upload(raw, B);

        expect(result.uuid).toBe('existing-uuid');
        expect(find).not.toHaveBeenCalled();
        expect(pushToOci).not.toHaveBeenCalled();
        expect(claimInTransaction).not.toHaveBeenCalled();
        expect(runQuery).not.toHaveBeenCalled();
    });

    it('T-4: the same with a null owner', async () => {
        const raw = bom(1);
        allBomsBySerialNumber.mockResolvedValue([storedRow(1, await storedDigestOf(raw))] as any);
        find.mockResolvedValue({ owner_component: A });

        const result: any = await upload(raw);

        expect(result.uuid).toBe('existing-uuid');
        expect(find).not.toHaveBeenCalled();
        expect(pushToOci).not.toHaveBeenCalled();
        expect(runQuery).not.toHaveBeenCalled();
    });
});

describe('ordering', () => {
    it('T-5: a non-owner gets the lineage refusal, not the version conflict, at any version', async () => {
        find.mockResolvedValue({ owner_component: A });
        allBomsBySerialNumber.mockResolvedValue([storedRow(3, 'some-other-digest')] as any);

        await expect(upload(bom(3, 'right-pad'), B)).rejects.toBeInstanceOf(BomLineageOwnerError);
        await expect(upload(bom(1, 'right-pad'), B)).rejects.toBeInstanceOf(BomLineageOwnerError);
        await expect(upload(bom(3, 'right-pad'), A)).rejects.toBeInstanceOf(BomVersionConflictError);
        expect(pushToOci).not.toHaveBeenCalled();
    });
});

describe('ownerComponent validation', () => {
    for (const bad of ['not-a-uuid', `${A} `, 'aaaaaaaa-0000-4000-000000000001']) {
        it(`T-6: refuses ${JSON.stringify(bad)} before any read`, async () => {
            const result = upload(bom(2, 'right-pad'), bad);
            await expect(result).rejects.toBeInstanceOf(BomValidationError);
            await result.catch((e: any) => expect(e.details.field).toBe('ownerComponent'));
            expect(allBomsBySerialNumber).not.toHaveBeenCalled();
            expect(find).not.toHaveBeenCalled();
            expect(pushToOci).not.toHaveBeenCalled();
        });
    }

    it('T-6: an upper-case uuid is accepted and compared lowercased', async () => {
        find.mockResolvedValue({ owner_component: A });
        allBomsBySerialNumber.mockResolvedValue([storedRow(1, 'some-other-digest')] as any);
        claimInTransaction.mockResolvedValue(A);

        await upload(bom(2, 'right-pad'), A.toUpperCase());

        expect(claimInTransaction).toHaveBeenCalledWith(expect.anything(), ORG, SERIAL, A);
        expect(insertedMeta().ownerComponent).toBe(A);
    });
});
