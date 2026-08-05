import { describe, it, expect, vi, beforeEach } from 'vitest';

// Stub DB access: stamping is observed through the mock, no live PG needed.
vi.mock('../../src/utils', () => ({
    runQuery: vi.fn(async () => ({ rows: [] })),
    pool: {}
}));

import { resolveAndFetchRawBom } from '../../src/services/oci/rawBomResolver';
import { OciNotFoundError, DigestValidationError } from '../../src/types/errors';
import { runQuery } from '../../src/utils';
import { getMonthlyRepositoryName } from '../../src/services/oci';

const RAW_DIGEST = 'raw-digest';
const PROCESSED_DIGEST = 'processed-digest';
const UUID = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';

function record(overrides: any = {}): any {
    return {
        uuid: UUID,
        created_date: new Date('2026-05-10T12:00:00Z'),
        meta: {
            originalFileDigest: RAW_DIGEST,
            processedFileDigest: PROCESSED_DIGEST,
            ...(overrides.meta || {})
        },
        bom: { ociRepositoryName: overrides.recordedRepo ?? 'rebom-artifacts-2026-06' }
    };
}

const notFound = () => { throw new OciNotFoundError('not found'); };

beforeEach(() => {
    vi.mocked(runQuery).mockClear();
});

describe('resolveAndFetchRawBom', () => {
    it('fetches raw from the stamped pointer without re-stamping', async () => {
        const fetch = vi.fn(async (tag: string, repo?: string) => {
            expect(tag).toBe(UUID + '-raw');
            expect(repo).toBe('rebom-artifacts-2026-05');
            return { ok: true };
        });
        const rec = record({ meta: { rawOciRepositoryName: 'rebom-artifacts-2026-05' } });
        expect(await resolveAndFetchRawBom(rec, fetch)).toEqual({ ok: true });
        expect(fetch).toHaveBeenCalledTimes(1);
        expect(runQuery).not.toHaveBeenCalled();
    });

    it('resolves a split row via the upload-month repo and stamps the pointer', async () => {
        // Recorded repo (2026-06, post-enrichment) lacks the raw copy; the
        // upload month derived from created_date (2026-05) has it.
        const fetch = vi.fn(async (tag: string, repo?: string, digest?: string) => {
            if (tag === UUID + '-raw' && repo === 'rebom-artifacts-2026-05') {
                expect(digest).toBe(RAW_DIGEST);
                return { raw: true };
            }
            notFound();
        });
        expect(await resolveAndFetchRawBom(record(), fetch)).toEqual({ raw: true });
        const stamp = vi.mocked(runQuery).mock.calls.find(c => String(c[0]).includes('rawOciRepositoryName'));
        expect(stamp).toBeTruthy();
        expect(stamp![1]).toEqual([UUID, JSON.stringify('rebom-artifacts-2026-05')]);
    });

    it('rethrows a digest failure on the raw artifact immediately -- no substitute fetch', async () => {
        const fetch = vi.fn(async (tag: string) => {
            if (tag === UUID + '-raw') {
                throw new DigestValidationError('corrupt', tag, 'repo', RAW_DIGEST, 'other');
            }
            return { should: 'never-happen' };
        });
        await expect(resolveAndFetchRawBom(record(), fetch)).rejects.toBeInstanceOf(DigestValidationError);
        // Exactly one fetch: the raw attempt. No fallback, no further probing.
        expect(fetch).toHaveBeenCalledTimes(1);
        expect(runQuery).not.toHaveBeenCalled();
    });

    it('serves the processed substitute (validated with ITS digest) and stamps raw-missing on all-404', async () => {
        const fetch = vi.fn(async (tag: string, repo?: string, digest?: string) => {
            if (tag === UUID + '-raw') notFound();
            expect(tag).toBe(UUID);
            expect(digest).toBe(PROCESSED_DIGEST);
            return { substitute: true };
        });
        expect(await resolveAndFetchRawBom(record(), fetch)).toEqual({ substitute: true });
        const stamp = vi.mocked(runQuery).mock.calls.find(c => String(c[0]).includes('rawBomMissing'));
        expect(stamp).toBeTruthy();
    });

    it('still serves the substitute on ambiguous (non-404) errors but makes no durable decisions', async () => {
        const fetch = vi.fn(async (tag: string, repo?: string, digest?: string) => {
            if (tag === UUID + '-raw') {
                if (repo === 'rebom-artifacts-2026-06') throw new Error('OCI fetch returned HTTP 500');
                notFound();
            }
            expect(digest).toBe(PROCESSED_DIGEST);
            return { substitute: true };
        });
        expect(await resolveAndFetchRawBom(record(), fetch)).toEqual({ substitute: true });
        // 500 in the mix -> neither pointer nor raw-missing may be stamped.
        expect(runQuery).not.toHaveBeenCalled();
    });

    it('short-circuits straight to the substitute when raw-missing is stamped', async () => {
        const fetch = vi.fn(async (tag: string, repo?: string, digest?: string) => {
            expect(tag).toBe(UUID);
            expect(digest).toBe(PROCESSED_DIGEST);
            return { substitute: true };
        });
        const rec = record({ meta: { rawBomMissing: true } });
        expect(await resolveAndFetchRawBom(rec, fetch)).toEqual({ substitute: true });
        expect(fetch).toHaveBeenCalledTimes(1);
    });

    it('probes the base legacy repository last for pre-rotation rows', async () => {
        const tried: Array<string | undefined> = [];
        const fetch = vi.fn(async (tag: string, repo?: string) => {
            if (tag === UUID + '-raw') { tried.push(repo); notFound(); }
            return { substitute: true };
        });
        const rec = record({ recordedRepo: undefined });
        rec.bom = {}; // legacy row: no recorded repository at all
        await resolveAndFetchRawBom(rec, fetch);
        expect(tried[tried.length - 1]).toBeUndefined(); // base repo fallback
        expect(tried).toContain(getMonthlyRepositoryName(new Date('2026-05-10T12:00:00Z')));
    });
});
