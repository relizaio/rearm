import { describe, it, expect, vi } from 'vitest';

vi.mock('../../src/utils', () => ({
    runQuery: vi.fn(async () => ({ rows: [] })),
    pool: {}
}));

import { fetchProcessedBomWithRetry } from '../../src/services/oci/processedBomFetcher';
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
