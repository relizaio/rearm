import { describe, it, expect, vi, beforeEach } from 'vitest';

// Storage and registry are both observed through mocks: the point of these
// tests is what is NOT written when an upload is refused.
const runQuery = vi.fn(async () => ({ rows: [{ uuid: 'inserted' }] }));
const pushToOci = vi.fn(async (tag: string) => ({
    ociRepositoryName: 'rebom-artifacts-2026-09',
    fileSHA256Digest: `digest-of-${tag}`,
    originalSize: 10
}));
const allBomsBySerialNumber = vi.fn(async () => [] as any[]);

vi.mock('../../src/utils', () => ({ runQuery: (...a: any[]) => runQuery.apply(null, a as any), pool: {} }));
vi.mock('../../src/logger', () => ({
    logger: { info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn() }
}));
vi.mock('../../src/bomRepository', () => ({
    allBomsBySerialNumber: (...a: any[]) => allBomsBySerialNumber.apply(null, a as any),
    bomById: vi.fn(async () => [])
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
import { BomVersionConflictError } from '../../src/types/errors';

const SERIAL = 'urn:uuid:11111111-2222-3333-4444-555555555555';
const ORG = '00000000-0000-0000-0000-000000000000';

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

// Augmentation needs the component context ReARM sends with every upload.
const rebomOptions = () => ({
    name: 'demo-component', group: 'io.reliza', version: '1.0.0',
    belongsTo: 'COMPONENT', structure: 'FLAT', bomState: 'RAW', stripBom: 'false',
    tldOnly: false, notes: '', storage: 'OCI', mod: 'raw'
});

const upload = (b: any) => addBom({
    bomInput: { format: 'CYCLONEDX', org: ORG, bom: b, tags: [], rebomOptions: rebomOptions() }
} as any);

describe('same serialNumber, non-incremented version', () => {
    beforeEach(() => { vi.clearAllMocks(); allBomsBySerialNumber.mockResolvedValue([]); });

    it('is refused, and nothing is pushed', async () => {
        // The replacement branch this replaces re-pushed BOTH artifacts at the
        // existing uuid: it rewrote a stored raw artifact, which is supposed to
        // be the immutable record of what was uploaded, and the bytes behind an
        // artifact row ReARM had already scanned.
        allBomsBySerialNumber.mockResolvedValue([storedRow(3, 'some-other-digest')] as any);

        await expect(upload(bom(3, 'right-pad'))).rejects.toBeInstanceOf(BomVersionConflictError);
        expect(pushToOci).not.toHaveBeenCalled();
        expect(runQuery).not.toHaveBeenCalled();
    });

    it('says what is wrong and what the versions are', async () => {
        allBomsBySerialNumber.mockResolvedValue([storedRow(5, 'some-other-digest')] as any);
        await expect(upload(bom(2, 'right-pad'))).rejects.toThrow(
            /already exists at version 5; uploaded version 2 must be greater/);
    });

    it('a lower version is refused too, not silently accepted', async () => {
        allBomsBySerialNumber.mockResolvedValue([storedRow(9, 'some-other-digest')] as any);
        await expect(upload(bom(1, 'right-pad'))).rejects.toBeInstanceOf(BomVersionConflictError);
        expect(pushToOci).not.toHaveBeenCalled();
    });
});

describe('uploads that are still accepted', () => {
    beforeEach(() => { vi.clearAllMocks(); allBomsBySerialNumber.mockResolvedValue([]); });

    it('a higher version inserts a new row and pushes both artifacts', async () => {
        allBomsBySerialNumber.mockResolvedValue([storedRow(1, 'some-other-digest')] as any);
        await upload(bom(2, 'right-pad'));
        expect(pushToOci).toHaveBeenCalledTimes(2);
        const query = runQuery.mock.calls[0][0] as unknown as string;
        expect(query.startsWith('INSERT')).toBe(true);
    });

    it('identical bytes dedupe to the stored row without pushing anything', async () => {
        // The digest that decides this is computed locally now, so a duplicate
        // costs the registry nothing.
        const { createHash } = await import('crypto');
        const raw = bom(1);
        const digest = createHash('sha256').update(JSON.stringify(raw)).digest('hex');
        allBomsBySerialNumber.mockResolvedValue([storedRow(1, digest)] as any);

        const result: any = await upload(raw);
        expect(result.uuid).toBe('existing-uuid');
        expect(pushToOci).not.toHaveBeenCalled();
        expect(runQuery).not.toHaveBeenCalled();
    });

    it('a first upload of an unseen serialNumber inserts', async () => {
        await upload(bom(1));
        expect(pushToOci).toHaveBeenCalledTimes(2);
        expect(runQuery).toHaveBeenCalledTimes(1);
    });
});
