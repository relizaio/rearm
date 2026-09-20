import { describe, it, expect, vi, beforeEach } from 'vitest';

// Storage and registry are observed through mocks: what matters here is the
// identity of the documents that reach the registry, not that they reach it.
const runQuery = vi.fn(async () => ({ rows: [{ uuid: 'inserted' }] }));
const pushed: Array<{ tag: string; bom: any }> = [];
const pushToOci = vi.fn(async (tag: string, bom: any) => {
    pushed.push({ tag, bom });
    return { ociRepositoryName: 'rebom-artifacts-2026-09', fileSHA256Digest: `digest-of-${tag}`, originalSize: 10 };
});

vi.mock('../../src/utils', () => ({ runQuery: (...a: any[]) => runQuery.apply(null, a as any), pool: {} }));
vi.mock('../../src/logger', () => ({
    logger: { info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn() }
}));
const bomById = vi.fn(async (_id: string) => [] as any[]);
vi.mock('../../src/bomRepository', () => ({
    allBomsBySerialNumber: vi.fn(async () => [] as any[]),
    bomById: (...a: any[]) => bomById.apply(null, a as any)
}));
vi.mock('../../src/services/oci', async (importOriginal) => {
    const actual: any = await importOriginal();
    return {
        ...actual,
        pushToOci: (...a: any[]) => pushToOci.apply(null, a as any),
        getMonthlyRepositoryName: () => 'rebom-artifacts-2026-09'
    };
});
// AUGMENT_ON_STORAGE is a module constant in bomAddService, so the only way to
// exercise the augmentation-off world is to make augmentation a no-op here.
const augmentation = { on: true };
vi.mock('../../src/services/bom/bomProcessingService', async (importOriginal) => {
    const actual: any = await importOriginal();
    return {
        ...actual,
        augmentBomForStorage: (bom: any, opts: any, date: any) =>
            augmentation.on ? actual.augmentBomForStorage(bom, opts, date) : bom,
        getInitialEnrichmentStatus: vi.fn(async () => 'SKIPPED'),
        enrichBomAsync: vi.fn(async () => undefined),
    };
});

import { addBom } from '../../src/services/bom/bomAddService';
import {
    mintProcessedSerialNumber, producerBomLink, bomLink, updateEnrichmentStatusWithBom,
    resolveProducerLink, PRODUCER_BOM_REFERENCE_COMMENT
} from '../../src/services/bom/bomProcessingService';
import { EnrichmentStatus } from '../../src/types';
import validateBom from '../../src/validateBom';

const PRODUCER_SERIAL = 'urn:uuid:11111111-2222-3333-4444-555555555555';
const ORG = '00000000-0000-0000-0000-000000000000';
const MINTED = /^urn:uuid:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

function producerBom(version = 1) {
    return {
        bomFormat: 'CycloneDX', specVersion: '1.5', serialNumber: PRODUCER_SERIAL, version,
        metadata: { timestamp: '2026-09-20T01:00:00Z' },
        components: [{ type: 'library', 'bom-ref': 'c1', name: 'left-pad', version: '1.3.0', purl: 'pkg:npm/left-pad@1.3.0' }]
    };
}

const rebomOptions = () => ({
    name: 'demo-component', group: 'io.reliza', version: '1.0.0',
    belongsTo: 'COMPONENT', structure: 'FLAT', bomState: 'RAW', stripBom: 'false',
    tldOnly: false, notes: '', storage: 'OCI', mod: 'raw'
});

const producerRef = (serial: string, version: number | string) => ({
    type: 'bom',
    url: `urn:cdx:${String(serial).replace('urn:uuid:', '')}/${version}`,
    comment: PRODUCER_BOM_REFERENCE_COMMENT
});

/**
 * serialNumber + version is the identity of ONE CycloneDX document. rebom writes
 * several documents per upload -- the producer's bytes, the augmented copy, one
 * more for every enrichment run -- and they all used to claim the producer's
 * identity. These pin the split: the row keeps the producer's serial, every
 * document rebom pushes gets its own, and the provenance survives as a BOM-Link.
 */
describe('minting an identity for a document rebom is about to push', () => {
    it('mints a fresh serial every time', () => {
        const a = mintProcessedSerialNumber(producerBom());
        const b = mintProcessedSerialNumber(producerBom());
        expect(a.serialNumber).toMatch(MINTED);
        expect(a.serialNumber).not.toBe(PRODUCER_SERIAL);
        expect(a.serialNumber).not.toBe(b.serialNumber);
    });

    it('leaves the version alone -- rearm-core reads it off this document', () => {
        expect(mintProcessedSerialNumber(producerBom(4)).version).toBe(4);
    });

    it('does not touch the document it was given, whose array the raw copy may share', () => {
        const input: any = producerBom();
        input.externalReferences = [{ type: 'website', url: 'https://example.invalid' }];
        const minted = mintProcessedSerialNumber(input);

        expect(input.serialNumber).toBe(PRODUCER_SERIAL);
        expect(input.externalReferences).toHaveLength(1);
        expect(minted.externalReferences).toHaveLength(2);
        expect(minted.externalReferences[0]).toEqual({ type: 'website', url: 'https://example.invalid' });
    });

    it('links back to the producer, whose identity the document still carries on the first pass', () => {
        const minted = mintProcessedSerialNumber(producerBom(2));
        expect(producerBomLink(minted)).toBe(`urn:cdx:11111111-2222-3333-4444-555555555555/2`);
        expect(minted.externalReferences).toContainEqual(producerRef(PRODUCER_SERIAL, 2));
    });

    it('reads an absent version as 1, the way CycloneDX does', () => {
        const noVersion: any = producerBom();
        delete noVersion.version;
        expect(producerBomLink(mintProcessedSerialNumber(noVersion))).toBe(bomLink(PRODUCER_SERIAL, undefined));
        expect(bomLink(PRODUCER_SERIAL, undefined)).toBe('urn:cdx:11111111-2222-3333-4444-555555555555/1');
    });

    it('refreshes the link it finds instead of appending a second one', () => {
        // Every enrichment run mints again over the previous run's output. The
        // link must keep pointing at the producer, and there must be one of it.
        const first = mintProcessedSerialNumber(producerBom(2));
        const second = mintProcessedSerialNumber(first);
        const third = mintProcessedSerialNumber(second);

        const serials = [PRODUCER_SERIAL, first.serialNumber, second.serialNumber, third.serialNumber];
        expect(new Set(serials).size).toBe(4);
        for (const doc of [second, third]) {
            expect(doc.externalReferences.filter((r: any) => r.comment === PRODUCER_BOM_REFERENCE_COMMENT))
                .toEqual([producerRef(PRODUCER_SERIAL, 2)]);
        }
    });

    it('writes no link when the source has none to give -- an SPDX upload', () => {
        const converted = mintProcessedSerialNumber(producerBom(), null);
        expect(converted.serialNumber).toMatch(MINTED);
        expect(producerBomLink(converted)).toBeNull();
        expect(converted.externalReferences).toBeUndefined();
    });

    it('builds no link out of a document with no serial', () => {
        expect(bomLink(undefined, 1)).toBeNull();
        expect(bomLink('', 1)).toBeNull();
    });

    it('stays schema-valid at a spec that predates BOM-Link', async () => {
        // The ingest case below runs at 1.5. BOM-Link is a 1.4 concept, so the
        // reference is forward-looking on 1.2/1.3 -- but it has to remain
        // LEGAL there, and it is: `bom` has been an external-reference type
        // since 1.2 and `url` is an unconstrained string. Validated on a bare
        // document on purpose: attachRebomToolToBom emits a tool with a `type`
        // field, which the pre-1.5 tools[] shape rejects, and that is a
        // separate pre-existing problem this test must not be coupled to.
        const old: any = { ...producerBom(1), specVersion: '1.3' };
        const minted = mintProcessedSerialNumber(old);
        expect(minted.externalReferences).toContainEqual(producerRef(PRODUCER_SERIAL, 1));
        await expect(validateBom(minted)).resolves.not.toThrow();
    });
});

describe('an accepted CycloneDX upload', () => {
    beforeEach(() => { vi.clearAllMocks(); pushed.length = 0; augmentation.on = true; });

    it('stores the producer bytes and a processed document under different identities', async () => {
        const raw = producerBom(3);
        await addBom({ bomInput: { format: 'CYCLONEDX', org: ORG, bom: raw, tags: [], rebomOptions: rebomOptions() } } as any);

        expect(pushed).toHaveLength(2);
        const rawPush = pushed.find(p => p.tag.endsWith('-raw'))!;
        const processedPush = pushed.find(p => !p.tag.endsWith('-raw'))!;

        // Invariant 1: the stored raw document is the producer's, untouched.
        expect(rawPush.bom).toBe(raw);
        expect(rawPush.bom.serialNumber).toBe(PRODUCER_SERIAL);

        // Invariants 2-4: distinct identity, same version, provenance preserved.
        expect(processedPush.bom.serialNumber).toMatch(MINTED);
        expect(processedPush.bom.serialNumber).not.toBe(PRODUCER_SERIAL);
        expect(processedPush.bom.version).toBe(3);
        expect(processedPush.bom.externalReferences).toContainEqual(producerRef(PRODUCER_SERIAL, 3));
    });

    it('pushes a document that still validates against the CycloneDX schema', async () => {
        // The reference is added after ingest validation, and Dependency-Track
        // strict-validates what it is handed -- so validate the bytes we push.
        await addBom({ bomInput: { format: 'CYCLONEDX', org: ORG, bom: producerBom(1), tags: [], rebomOptions: rebomOptions() } } as any);
        const processedPush = pushed.find(p => !p.tag.endsWith('-raw'))!;
        await expect(validateBom(processedPush.bom)).resolves.not.toThrow();
    });

    it('mints even when augmentation is off, because processing alone already changed the document', async () => {
        // augmentBomForStorage is not what makes the stored copy different from
        // the uploaded one -- sanitization, deduplication and the dependency
        // repairs in processBomObj did that before it ran. If the mint were
        // gated on augmentation, turning augmentation off would republish a
        // deduplicated document under the producer's serialNumber.
        augmentation.on = false;
        await addBom({ bomInput: { format: 'CYCLONEDX', org: ORG, bom: producerBom(1), tags: [], rebomOptions: rebomOptions() } } as any);

        const processedPush = pushed.find(p => !p.tag.endsWith('-raw'))!;
        expect(processedPush.bom.serialNumber).toMatch(MINTED);
        expect(processedPush.bom.serialNumber).not.toBe(PRODUCER_SERIAL);
        expect(processedPush.bom.externalReferences).toContainEqual(producerRef(PRODUCER_SERIAL, 1));
        expect((runQuery.mock.calls[0] as any)[1][1].processedSerialNumber).toBe(processedPush.bom.serialNumber);
    });

    it('keeps the producer serial as the row identity and records what it serves', async () => {
        await addBom({ bomInput: { format: 'CYCLONEDX', org: ORG, bom: producerBom(1), tags: [], rebomOptions: rebomOptions() } } as any);

        const [query, params] = runQuery.mock.calls[0] as unknown as [string, any[]];
        expect(query.startsWith('INSERT')).toBe(true);
        const meta = params[1];
        const processedPush = pushed.find(p => !p.tag.endsWith('-raw'))!;

        // Invariant 5, and the reason D1 exists: every lookup keys on
        // meta.serialNumber, so it is the one thing that must not move.
        expect(meta.serialNumber).toBe(PRODUCER_SERIAL);
        expect(meta.processedSerialNumber).toBe(processedPush.bom.serialNumber);
        expect(meta.processedTag).toBe(processedPush.tag);
    });
});

describe('the write that moves a row onto an enrichment run output', () => {
    beforeEach(() => vi.clearAllMocks());

    it('records the served identity next to the tag and on the run entry', async () => {
        runQuery.mockResolvedValueOnce({ rows: [], rowCount: 1 } as any);
        const serial = 'urn:uuid:99999999-8888-7777-6666-555555555555';
        await updateEnrichmentStatusWithBom(
            'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee', EnrichmentStatus.COMPLETED,
            { fileSHA256Digest: 'digest', originalSize: 10, ociRepositoryName: 'rebom-artifacts-2026-09' },
            'rebom-artifacts-2026-09', 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee-e1', 1, serial);

        const [query, params] = runQuery.mock.calls[0] as unknown as [string, any[]];
        expect(query).toContain("'{processedSerialNumber}'");
        expect(JSON.parse(params[8])).toBe(serial);
        expect(JSON.parse(params[11]).serialNumber).toBe(serial);
        expect(JSON.parse(params[11]).tag).toBe('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee-e1');
    });

    it('writes no serial for a call that does not name one, rather than a stale one', async () => {
        runQuery.mockResolvedValueOnce({ rows: [], rowCount: 1 } as any);
        await updateEnrichmentStatusWithBom(
            'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee', EnrichmentStatus.COMPLETED,
            { fileSHA256Digest: 'digest', originalSize: 10 });
        const params = runQuery.mock.calls[0][1] as unknown as any[];
        expect(JSON.parse(params[8])).toBeNull();
    });
});

/**
 * Rows written before this change have a processed document whose serial IS the
 * producer's, and no reference to refresh. The next enrichment run has to find
 * the producer's identity anyway -- that is how those rows converge.
 */
describe('finding the producer identity for a row mid-enrichment', () => {
    const ROW = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';
    beforeEach(() => { vi.clearAllMocks(); bomById.mockResolvedValue([]); });

    it('trusts the reference the document carries, without reading the row', async () => {
        const carried = mintProcessedSerialNumber(producerBom(2));
        await expect(resolveProducerLink(ROW, carried)).resolves.toBe('urn:cdx:11111111-2222-3333-4444-555555555555/2');
        expect(bomById).not.toHaveBeenCalled();
    });

    it('falls back to the row identity for a legacy CycloneDX row', async () => {
        bomById.mockResolvedValue([{ uuid: ROW, source_format: 'CYCLONEDX',
            meta: { serialNumber: PRODUCER_SERIAL, bomVersion: '4' } }] as any);
        await expect(resolveProducerLink(ROW, producerBom(4)))
            .resolves.toBe('urn:cdx:11111111-2222-3333-4444-555555555555/4');
    });

    it('gives an SPDX row no link rather than pointing at a document that is not CycloneDX', async () => {
        bomById.mockResolvedValue([{ uuid: ROW, source_format: 'SPDX', source_spdx_uuid: 'spdx-uuid',
            meta: { serialNumber: PRODUCER_SERIAL, bomVersion: '1' } }] as any);
        await expect(resolveProducerLink(ROW, producerBom())).resolves.toBeNull();
    });

    it('enriches without a link rather than failing when the row cannot be read', async () => {
        bomById.mockRejectedValue(new Error('connection terminated'));
        await expect(resolveProducerLink(ROW, producerBom())).resolves.toBeNull();
    });
});
