import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../../src/utils', () => ({
    runQuery: vi.fn(async () => ({ rows: [] })),
    pool: {}
}));

vi.mock('../../src/logger', () => ({
    logger: { info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn() }
}));

import { fetchProcessedBomWithRetry, resolveProcessedTag } from '../../src/services/oci/processedBomFetcher';
import { logger } from '../../src/logger';
import { DigestValidationError, OciNotFoundError } from '../../src/types/errors';

const UUID = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';
const OLD_DIGEST = 'digest-before-enrichment';
const NEW_DIGEST = 'digest-after-enrichment';

function row(digest: string, repo: string = 'rebom-artifacts-2026-08'): any {
    return { uuid: UUID, meta: { processedFileDigest: digest }, bom: { ociRepositoryName: repo } };
}

const digestFailure = () => {
    throw new DigestValidationError('mismatch', UUID, 'repo', OLD_DIGEST, NEW_DIGEST);
};

describe('fetchProcessedBomWithRetry', () => {
    it('returns first-try content when digests agree (no row re-read)', async () => {
        const fetch = vi.fn(async () => ({ ok: true }));
        const reRead = vi.fn();
        expect(await fetchProcessedBomWithRetry(row(OLD_DIGEST), fetch, reRead)).toEqual({ ok: true });
        expect(reRead).not.toHaveBeenCalled();
    });

    it('enrichment race: row digest changed under the reader -> one retry with fresh digest+repo succeeds', async () => {
        // Reader holds the pre-enrichment row (OLD_DIGEST, old repo); the
        // registry already serves post-enrichment bytes. The fresh row read
        // reveals the completed update (NEW_DIGEST, possibly a new repo).
        const calls: Array<[string | undefined, string | undefined]> = [];
        const fetch = vi.fn(async (tag: string, repo?: string, digest?: string) => {
            calls.push([repo, digest]);
            if (digest === OLD_DIGEST) digestFailure();
            return { enriched: true };
        });
        const reRead = vi.fn(async () => [row(NEW_DIGEST, 'rebom-artifacts-2026-09')]);

        expect(await fetchProcessedBomWithRetry(row(OLD_DIGEST), fetch, reRead)).toEqual({ enriched: true });
        expect(calls).toEqual([
            ['rebom-artifacts-2026-08', OLD_DIGEST],
            ['rebom-artifacts-2026-09', NEW_DIGEST]
        ]);
    });

    it('row unchanged -> genuine corruption, ORIGINAL digest error rethrown, no second fetch', async () => {
        const fetch = vi.fn(async () => digestFailure());
        const reRead = vi.fn(async () => [row(OLD_DIGEST)]);
        await expect(fetchProcessedBomWithRetry(row(OLD_DIGEST), fetch, reRead))
            .rejects.toBeInstanceOf(DigestValidationError);
        expect(fetch).toHaveBeenCalledTimes(1);
    });

    it('row deleted between fetch and re-read -> original error rethrown', async () => {
        const fetch = vi.fn(async () => digestFailure());
        const reRead = vi.fn(async () => []);
        await expect(fetchProcessedBomWithRetry(row(OLD_DIGEST), fetch, reRead))
            .rejects.toBeInstanceOf(DigestValidationError);
        expect(fetch).toHaveBeenCalledTimes(1);
    });

    it('retry is one-shot: a second mismatch against the fresh row propagates', async () => {
        const fetch = vi.fn(async () => digestFailure());
        const reRead = vi.fn(async () => [row(NEW_DIGEST)]);
        await expect(fetchProcessedBomWithRetry(row(OLD_DIGEST), fetch, reRead))
            .rejects.toBeInstanceOf(DigestValidationError);
        expect(fetch).toHaveBeenCalledTimes(2);
        expect(reRead).toHaveBeenCalledTimes(1);
    });

    it('non-digest errors pass through untouched (no re-read, no retry)', async () => {
        const fetch = vi.fn(async () => { throw new OciNotFoundError('gone'); });
        const reRead = vi.fn();
        await expect(fetchProcessedBomWithRetry(row(OLD_DIGEST), fetch, reRead))
            .rejects.toBeInstanceOf(OciNotFoundError);
        expect(reRead).not.toHaveBeenCalled();
    });
});

describe('processed tag resolution (enrichment writes a new artifact, never in place)', () => {
    beforeEach(() => vi.clearAllMocks());

    it('fetches by the tag the row points at, not by the uuid', async () => {
        const enriched = { uuid: UUID, meta: { processedTag: `${UUID}-e2`, processedFileDigest: NEW_DIGEST },
            bom: { ociRepositoryName: 'rebom-artifacts-2026-09' } };
        const fetch = vi.fn(async () => ({ enriched: true }));
        await fetchProcessedBomWithRetry(enriched as any, fetch, vi.fn());
        expect(fetch).toHaveBeenCalledWith(`${UUID}-e2`, 'rebom-artifacts-2026-09', NEW_DIGEST);
    });

    it('legacy row without processedTag still resolves to the bare uuid', () => {
        expect(resolveProcessedTag(row(OLD_DIGEST))).toBe(UUID);
        expect(resolveProcessedTag({ uuid: UUID, meta: { processedTag: `${UUID}-e1` } } as any))
            .toBe(`${UUID}-e1`);
    });

    it('an enrichment landing mid-read no longer disturbs the reader at all', async () => {
        // The reader holds the pre-enrichment row. Enrichment has since pushed
        // <uuid>-e1 and moved the row on, but it did not touch <uuid>, so the
        // bytes this reader asks for still hash to the digest it is holding.
        // No mismatch, no retry, no error line -- the race is gone rather than
        // survived.
        const stale = { uuid: UUID, meta: { processedTag: UUID, processedFileDigest: OLD_DIGEST },
            bom: { ociRepositoryName: 'rebom-artifacts-2026-08' } };
        const fetch = vi.fn(async (tag: string, _repo?: string, digest?: string) => {
            if (tag === UUID && digest === OLD_DIGEST) return { original: true };
            throw new DigestValidationError('mismatch', tag, 'repo', digest, 'something-else');
        });
        const reRead = vi.fn();

        expect(await fetchProcessedBomWithRetry(stale as any, fetch, reRead)).toEqual({ original: true });
        expect(reRead).not.toHaveBeenCalled();
        expect(logger.error).not.toHaveBeenCalled();
    });

    it('a genuine mismatch on an unchanged row logs at error exactly once', async () => {
        const fetch = vi.fn(async () => digestFailure());
        const reRead = vi.fn(async () => [row(OLD_DIGEST)]);
        await expect(fetchProcessedBomWithRetry(row(OLD_DIGEST), fetch, reRead))
            .rejects.toBeInstanceOf(DigestValidationError);
        expect(logger.error).toHaveBeenCalledTimes(1);
    });

    it('a recovered race does not log at error', async () => {
        const fetch = vi.fn(async (_tag: string, _repo?: string, digest?: string) => {
            if (digest === OLD_DIGEST) digestFailure();
            return { enriched: true };
        });
        const reRead = vi.fn(async () => [{ uuid: UUID,
            meta: { processedTag: `${UUID}-e1`, processedFileDigest: NEW_DIGEST },
            bom: { ociRepositoryName: 'rebom-artifacts-2026-09' } }]);

        expect(await fetchProcessedBomWithRetry(row(OLD_DIGEST), fetch, reRead)).toEqual({ enriched: true });
        expect(logger.error).not.toHaveBeenCalled();
        expect(logger.warn).toHaveBeenCalledTimes(1);
        expect(fetch).toHaveBeenLastCalledWith(`${UUID}-e1`, 'rebom-artifacts-2026-09', NEW_DIGEST);
    });
});
