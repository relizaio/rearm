import { describe, it, expect, vi, beforeEach } from 'vitest';

// SCORE-46 T-15: the lineage owner check and claim on the SPDX path (ADR-12).
// Same shape as bomLineageOwner.test.ts, with the SPDX repository and the
// rearm-cli conversion mocked.
const runQuery = vi.fn(async (..._a: any[]) => ({ rows: [{ uuid: 'inserted' }] }));
const pushToOci = vi.fn(async (tag: string) => ({
    ociRepositoryName: 'rebom-artifacts-2026-09',
    fileSHA256Digest: `digest-of-${tag}`,
    originalSize: 10
}));
const find = vi.fn(async (..._a: any[]) => null as any);
const claimInTransaction = vi.fn(async (..._a: any[]) => null as any);
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
    allBomsBySerialNumber: vi.fn(async () => [] as any[]),
    bomById: vi.fn(async () => [])
}));
vi.mock('../../src/bomLineageRepository', async (importOriginal) => ({
    ...(await importOriginal<any>()),
    find: (...a: any[]) => find.apply(null, a as any),
    claimInTransaction: (...a: any[]) => claimInTransaction.apply(null, a as any)
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
import { SpdxService } from '../../src/services/spdx';
import { BomLineageOwnerError, BomValidationError } from '../../src/types/errors';
import { logger } from '../../src/logger';

const warn = logger.warn as unknown as ReturnType<typeof vi.fn>;

const S = 'urn:uuid:11111111-2222-3333-4444-555555555555';
const S_BARE = '11111111-2222-3333-4444-555555555555';
const SC = 'urn:uuid:99999999-8888-4777-8666-555555555555';
const ORG = '00000000-0000-0000-0000-000000000000';
const A = 'aaaaaaaa-0000-4000-8000-000000000001';
const B = 'bbbbbbbb-0000-4000-8000-000000000002';
const MSG = `BOM with serialNumber ${S} belongs to another component of this organization; ` +
    'a new version of it can only be uploaded to that component. Upload an SBOM with its own serialNumber instead.';

function converted(serialNumber: string) {
    return {
        bomFormat: 'CycloneDX', specVersion: '1.5', serialNumber, version: 1,
        metadata: { timestamp: '2026-09-18T01:00:00Z' },
        components: [{ type: 'library', 'bom-ref': 'c1', name: 'left-pad', version: '1.3.0',
            purl: 'pkg:npm/left-pad@1.3.0' }]
    };
}

const spdx = (ns: string) => ({
    SPDXID: 'SPDXRef-DOCUMENT', spdxVersion: 'SPDX-2.3', dataLicense: 'CC0-1.0', name: 'doc',
    documentNamespace: ns, packages: []
});

const uploadSpdx = (doc: any, owner?: any, existing?: string) => addBom({
    bomInput: { bom: doc, format: 'SPDX', org: ORG, existingSerialNumber: existing,
        rebomOptions: { ownerComponent: owner } }
} as any);

const convertSpy = vi.spyOn(SpdxService, 'convertSpdxToCycloneDx');

function insertCall(): any[] | undefined {
    return runQuery.mock.calls.find(c => String(c[0]).startsWith('INSERT INTO rebom.boms')) as any;
}

beforeEach(() => {
    vi.clearAllMocks();
    find.mockResolvedValue(null);
    claimInTransaction.mockResolvedValue(null);
    findSpdxBomBySerialNumber.mockResolvedValue(null);
    findSpdxBomByNamespace.mockResolvedValue(null);
    convertSpy.mockResolvedValue({ success: true, convertedBom: converted(SC) } as any);
});

describe('SPDX update onto an owned lineage', () => {
    for (const [label, caller] of [['another component', B], ['a null caller', undefined]] as const) {
        it(`T-15 a: ${label} is refused before the SPDX push, the spdx_boms row and the conversion`, async () => {
            findSpdxBomBySerialNumber.mockResolvedValue({ uuid: 'spdx-old', bom_version: 1 });
            find.mockResolvedValue({ owner_component: A });

            const result = uploadSpdx(spdx('ns-2'), caller, S_BARE);
            await expect(result).rejects.toBeInstanceOf(BomLineageOwnerError);
            await result.catch((e: any) => {
                expect(e.message).toBe(MSG);
                expect(e.serialNumber).toBe(S);
            });
            expect(pushToOci).not.toHaveBeenCalled();
            expect(createSpdxBom).not.toHaveBeenCalled();
            expect(convertSpy).not.toHaveBeenCalled();
            expect(claimInTransaction).not.toHaveBeenCalled();
            expect(runQuery).not.toHaveBeenCalled();
            expect(warn).toHaveBeenCalledTimes(1);
            expect(warn).toHaveBeenCalledWith(expect.objectContaining({
                uploadingComponent: caller ?? null, path: 'SPDX', stage: 'precheck', serialNumber: S
            }), 'BOM lineage owner mismatch: upload refused');
        });
    }

    it('T-15 b: the owner updates: claim, then the insert at the next version', async () => {
        findSpdxBomBySerialNumber.mockResolvedValue({ uuid: 'spdx-old', bom_version: 1 });
        find.mockResolvedValue({ owner_component: A });
        claimInTransaction.mockResolvedValue(A);

        await uploadSpdx(spdx('ns-2'), A, S_BARE);

        expect(pushToOci).toHaveBeenCalledTimes(2);
        expect(createSpdxBom).toHaveBeenCalledTimes(1);
        expect(createSpdxBom.mock.calls[0][0].bom_version).toBe(2);
        expect(claimInTransaction).toHaveBeenCalledTimes(1);
        expect(claimInTransaction).toHaveBeenCalledWith(expect.objectContaining({ query: expect.any(Function) }), ORG, S, A);
        expect(runQuery).toHaveBeenCalledTimes(1);
        const insert = insertCall()!;
        expect(insert).toBeDefined();
        expect(claimInTransaction.mock.invocationCallOrder[0]).toBeLessThan(runQuery.mock.invocationCallOrder[0]);
        const params = insert[1] as any[];
        expect(params[5]).toBe('SPDX');
        expect(params[1].serialNumber).toBe(S);
        expect(params[1].bomVersion).toBe('2');
        expect(params[1].ownerComponent).toBe(A);
        expect(linkConvertedBom.mock.invocationCallOrder[0]).toBeGreaterThan(runQuery.mock.invocationCallOrder[0]);
        expect(updateSpdxBomConversionStatus).toHaveBeenCalledWith(expect.any(String), 'success');
        expect(updateSpdxBomConversionStatus.mock.invocationCallOrder[0]).toBeGreaterThan(runQuery.mock.invocationCallOrder[0]);
    });

    it('T-15 e: an update with no SPDX row of the serialNumber is checked the same way', async () => {
        findSpdxBomBySerialNumber.mockResolvedValue(null);
        find.mockResolvedValue({ owner_component: A });

        await expect(uploadSpdx(spdx('ns-2'), B, S_BARE)).rejects.toBeInstanceOf(BomLineageOwnerError);
        expect(pushToOci).not.toHaveBeenCalled();
        expect(createSpdxBom).not.toHaveBeenCalled();
        expect(runQuery).not.toHaveBeenCalled();
    });
});

describe('SPDX first upload', () => {
    it('T-15 c: the converter serialNumber is checked before the converted push and claimed by the owner', async () => {
        await uploadSpdx(spdx('ns-1'), A);

        expect(find).toHaveBeenCalledWith(ORG, SC);
        expect(find.mock.invocationCallOrder[0]).toBeGreaterThan(convertSpy.mock.invocationCallOrder[0]);
        expect(pushToOci).toHaveBeenCalledTimes(2);
        expect(find.mock.invocationCallOrder[0]).toBeLessThan(pushToOci.mock.invocationCallOrder[1]);
        expect(claimInTransaction).toHaveBeenCalledTimes(1);
        expect(claimInTransaction).toHaveBeenCalledWith(expect.anything(), ORG, SC, A);
        expect((insertCall()![1] as any[])[1].serialNumber).toBe(SC);
    });

    it('T-15 c: with no owner the claim step runs with a null caller', async () => {
        await uploadSpdx(spdx('ns-1'));

        expect(claimInTransaction).toHaveBeenCalledTimes(1);
        expect(claimInTransaction.mock.calls[0][3]).toBeNull();
        expect(runQuery).toHaveBeenCalledTimes(1);
    });
});

describe('SPDX ownerComponent validation', () => {
    it('T-15 d: a malformed ownerComponent is refused before any read, on a first upload', async () => {
        const result = uploadSpdx(spdx('ns-1'), 'not-a-uuid');
        await expect(result).rejects.toBeInstanceOf(BomValidationError);
        await result.catch((e: any) => expect(e.details.field).toBe('ownerComponent'));
        expect(findSpdxBomBySerialNumber).not.toHaveBeenCalled();
        expect(findSpdxBomByNamespace).not.toHaveBeenCalled();
        expect(pushToOci).not.toHaveBeenCalled();
    });

    it('T-15 d: and on an update', async () => {
        const result = uploadSpdx(spdx('ns-2'), 'not-a-uuid', S_BARE);
        await expect(result).rejects.toBeInstanceOf(BomValidationError);
        expect(findSpdxBomBySerialNumber).not.toHaveBeenCalled();
        expect(findSpdxBomByNamespace).not.toHaveBeenCalled();
        expect(pushToOci).not.toHaveBeenCalled();
    });
});
