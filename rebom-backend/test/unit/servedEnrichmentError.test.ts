import { describe, it, expect, vi, beforeEach } from 'vitest';

const runQuery = vi.hoisted(() => vi.fn());
const fetchProcessedBomWithRetry = vi.hoisted(() => vi.fn());

vi.mock('../../src/utils', async (importOriginal) => {
    const actual: any = await importOriginal();
    return { ...actual, runQuery: (...a: any[]) => runQuery(...a) };
});
vi.mock('../../src/services/oci', async (importOriginal) => {
    const actual: any = await importOriginal();
    return { ...actual, fetchProcessedBomWithRetry: (...a: any[]) => fetchProcessedBomWithRetry(...a) };
});

import { BomMapper } from '../../src/services/bom/bomMapper';
import { findBomByMeta } from '../../src/services/bom/bomSearchService';
import { BomRecord, RebomOptions } from '../../src/types';

/**
 * Old rows are served bounded and clean, not migrated (TPF-261 design 3.9; row T-1248). A row
 * written before the change holds the child's whole stdout in meta.enrichmentError. USERW, PASSW,
 * BODYW and FILLERW are plain marker words.
 */

const OLD_TEXT = 'Using BEAR at http://USERW:PASSW@host\n' + 'filler FILLERW line\n'.repeat(55000)
    + 'Error during enrichment: failed to enrich batch 1-1: BEAR API returned status 502: {"message":"BODYW"} BODYW\n';
const SERVED = 'Error during enrichment: failed to enrich batch 1-1: BEAR API returned status 502';
const MARKERS = ['USERW', 'PASSW', 'BODYW', 'FILLERW'];

function record(enrichmentError?: unknown, withField = true): BomRecord {
    const meta: any = {
        name: 'app', group: 'org.example', bomVersion: '3', serialNumber: 'urn:uuid:1', version: '1.0.0',
        enrichmentStatus: 'FAILED', enrichmentTimestamp: '2026-11-27T00:00:00.000Z'
    };
    if (withField) meta.enrichmentError = enrichmentError;
    return {
        uuid: '11111111-2222-3333-4444-555555555555', created_date: new Date(0), last_updated_date: new Date(0),
        meta: meta as RebomOptions, bom: { ociDigest: 'sha256:x' }, tags: {}, organization: 'org-1', public: false, duplicate: false
    };
}

function expectOtherKeysAsStored(served: any, stored: any): void {
    for (const key of Object.keys(stored)) if (key !== 'enrichmentError') expect(served[key]).toStrictEqual(stored[key]);
}

describe('T-1248 the mappers and the search serve enrichmentError bounded; the record is not changed', () => {
    beforeEach(() => {
        runQuery.mockReset();
        fetchProcessedBomWithRetry.mockReset();
        fetchProcessedBomWithRetry.mockImplementation(async () => ({
            bomFormat: 'CycloneDX', specVersion: '1.6', version: 1,
            metadata: { component: { type: 'application', name: 'app', version: '1.0.0' } }, components: []
        }));
    });

    it('the old text is a megabyte long', () => {
        expect(OLD_TEXT.length).toBeGreaterThan(1000000);
    });

    it('toMetaDto', () => {
        const rec = record(OLD_TEXT);
        const dto = BomMapper.toMetaDto(rec);
        expect(dto.enrichmentError).toBe(SERVED);
        for (const m of MARKERS) expect(JSON.stringify(dto)).not.toContain(m);
        expect(rec.meta.enrichmentError).toBe(OLD_TEXT);
        expect(dto.enrichmentStatus).toBe('FAILED');
        expect(dto.name).toBe('app');
    });

    it('toDto: the served meta is a copy with the bounded text', () => {
        const rec = record(OLD_TEXT);
        const storedMeta = rec.meta;
        const dto = BomMapper.toDto(rec);
        expect((dto.meta as any).enrichmentError).toBe(SERVED);
        expect(dto.meta).not.toBe(storedMeta);
        expectOtherKeysAsStored(dto.meta, storedMeta);
        for (const m of MARKERS) expect(JSON.stringify(dto)).not.toContain(m);
        expect(rec.meta).toBe(storedMeta);
        expect(rec.meta.enrichmentError).toBe(OLD_TEXT);
    });

    it('findBomByMeta', async () => {
        const rec = record(OLD_TEXT);
        runQuery.mockResolvedValue({ rows: [rec] });

        const dtos = await findBomByMeta({ name: 'app' } as RebomOptions);

        expect(dtos).toHaveLength(1);
        expect((dtos[0].meta as any).enrichmentError).toBe(SERVED);
        expectOtherKeysAsStored(dtos[0].meta, rec.meta);
        for (const m of MARKERS) expect(JSON.stringify(dtos)).not.toContain(m);
        expect(rec.meta.enrichmentError).toBe(OLD_TEXT);
    });

    it('absent stays absent, null stays null, the other fields are served unchanged', async () => {
        const absent = record(undefined, false);
        const nul = record(null);
        expect(BomMapper.toMetaDto(absent).enrichmentError).toBeUndefined();
        expect(BomMapper.toMetaDto(nul).enrichmentError).toBeNull();
        expect(BomMapper.toDto(absent).meta).toBe(absent.meta);
        expect(BomMapper.toDto(nul).meta).toBe(nul.meta);
        expect(Object.prototype.hasOwnProperty.call(BomMapper.toDto(absent).meta, 'enrichmentError')).toBe(false);
        expect((BomMapper.toDto(nul).meta as any).enrichmentError).toBeNull();
        runQuery.mockResolvedValue({ rows: [absent, nul] });
        const dtos = await findBomByMeta({ name: 'app' } as RebomOptions);
        expect(Object.prototype.hasOwnProperty.call(dtos[0].meta, 'enrichmentError')).toBe(false);
        expect((dtos[1].meta as any).enrichmentError).toBeNull();
        expect(BomMapper.servedMeta(null)).toBeNull();
        expect(BomMapper.servedMeta(undefined)).toBeUndefined();
    });

    it('a text the store already bounded is served as it is', () => {
        const bounded = 'rearm exited with code 1: ' + SERVED;
        expect(BomMapper.toMetaDto(record(bounded)).enrichmentError).toBe(bounded);
        expect((BomMapper.toDto(record(bounded)).meta as any).enrichmentError).toBe(bounded);
    });
});
