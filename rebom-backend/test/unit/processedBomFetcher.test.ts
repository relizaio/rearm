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
import { RELEASE_NODE_PROPERTY } from '../../src/services/bom/releaseNodeStamp';

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

/**
 * SCORE-60 (T-24, ADR-9 (c), (e)): only rebom's own release node stamp is served. A row that is not a
 * merge output (meta.mod other than `rebom`) is served without any `reliza:export:releaseNode`, on
 * both fetch paths; and a row handed without its `meta` key (the enrichment scheduler's shape before
 * ADR-9 (d)) is re-read by uuid before the tag, the digest or the strip is decided on it.
 */
describe('release node stamps on a served document', () => {
    beforeEach(() => vi.clearAllMocks());

    const STAMP = { name: RELEASE_NODE_PROPERTY, value: 'true' };
    const KEEP = { name: 'acme:keep', value: 'yes' };
    const stamped = () => ({
        bomFormat: 'CycloneDX',
        metadata: { component: { name: 'root', properties: [STAMP, KEEP] } },
        components: [{ name: 'lib', components: [{ name: 'nested', properties: [STAMP] }] }]
    });
    const clean = () => ({
        bomFormat: 'CycloneDX',
        metadata: { component: { name: 'root', properties: [KEEP] } },
        components: [{ name: 'lib', components: [{ name: 'nested', properties: [] }] }]
    });
    const withMeta = (meta: any) => ({ uuid: UUID, meta, bom: { ociRepositoryName: 'rebom-artifacts-2026-08' } });
    const partial = () => ({ uuid: UUID, organization: 'org', serialNumber: 'urn:uuid:x', bom: { ociRepositoryName: 'rebom-artifacts-2026-08' } });

    it.each([
        ['meta.mod raw', { mod: 'raw', processedFileDigest: OLD_DIGEST }],
        ['meta without mod', { processedFileDigest: OLD_DIGEST }],
        ['meta {}', {}],
        ['meta null', null]
    ])('serves a row that is not a merge output (%s) without the stamp, and re-reads nothing', async (_label, meta) => {
        const fetch = vi.fn(async () => stamped());
        const reRead = vi.fn();

        expect(await fetchProcessedBomWithRetry(withMeta(meta) as any, fetch, reRead)).toStrictEqual(clean());
        expect(reRead).not.toHaveBeenCalled();
        expect(logger.debug).toHaveBeenCalledWith({ bomUuid: UUID, removed: 2 }, expect.any(String));
    });

    it('serves a merge output as fetched', async () => {
        const content = stamped();
        const fetch = vi.fn(async () => content);

        expect(await fetchProcessedBomWithRetry(withMeta({ mod: 'rebom', processedFileDigest: OLD_DIGEST }) as any, fetch, vi.fn())).toBe(content);
        expect(logger.debug).not.toHaveBeenCalled();
    });

    it('lets the fresh row decide on the retry path: a merge output keeps its stamps', async () => {
        const content = stamped();
        const fetch = vi.fn(async (_t: string, _r?: string, digest?: string) => {
            if (digest === OLD_DIGEST) digestFailure();
            return content;
        });
        const reRead = vi.fn(async () => [withMeta({ mod: 'rebom', processedFileDigest: NEW_DIGEST })]);

        expect(await fetchProcessedBomWithRetry(withMeta({ mod: 'raw', processedFileDigest: OLD_DIGEST }) as any, fetch, reRead)).toBe(content);
    });

    it('lets the fresh row decide on the retry path: an upload is stripped', async () => {
        const fetch = vi.fn(async (_t: string, _r?: string, digest?: string) => {
            if (digest === OLD_DIGEST) digestFailure();
            return stamped();
        });
        const reRead = vi.fn(async () => [withMeta({ mod: 'raw', processedFileDigest: NEW_DIGEST })]);

        expect(await fetchProcessedBomWithRetry(withMeta({ mod: 'rebom', processedFileDigest: OLD_DIGEST }) as any, fetch, reRead)).toStrictEqual(clean());
    });

    it('re-reads a row handed without meta before any fetch, and fetches what the whole row points at', async () => {
        const content = stamped();
        const fetch = vi.fn(async () => content);
        const reRead = vi.fn(async () => [{ uuid: UUID, meta: { mod: 'rebom', processedTag: `${UUID}-e1`, processedFileDigest: NEW_DIGEST },
            bom: { ociRepositoryName: 'rebom-artifacts-2026-09' } }]);

        expect(await fetchProcessedBomWithRetry(partial() as any, fetch, reRead)).toBe(content);
        expect(reRead).toHaveBeenCalledTimes(1);
        expect(reRead).toHaveBeenCalledWith(UUID);
        expect(reRead.mock.invocationCallOrder[0]).toBeLessThan(fetch.mock.invocationCallOrder[0]);
        expect(fetch).toHaveBeenCalledTimes(1);
        expect(fetch).toHaveBeenCalledWith(`${UUID}-e1`, 'rebom-artifacts-2026-09', NEW_DIGEST);
    });

    it('serves a row handed without meta stripped when the whole row is an upload', async () => {
        const fetch = vi.fn(async () => stamped());
        const reRead = vi.fn(async () => [withMeta({ mod: 'raw', processedFileDigest: NEW_DIGEST })]);

        expect(await fetchProcessedBomWithRetry(partial() as any, fetch, reRead)).toStrictEqual(clean());
    });

    it('refuses a row handed without meta that re-reads as nothing, and fetches nothing', async () => {
        const fetch = vi.fn();
        const reRead = vi.fn(async () => []);

        await expect(fetchProcessedBomWithRetry(partial() as any, fetch, reRead)).rejects.toThrow(new RegExp(`${UUID}.*meta`));
        expect(fetch).not.toHaveBeenCalled();
    });

    it('keeps the one-shot digest retry for a row handed without meta, and keeps a merge output its stamps', async () => {
        const content = stamped();
        const fetch = vi.fn(async (_t: string, _r?: string, digest?: string) => {
            if (digest === OLD_DIGEST) digestFailure();
            return content;
        });
        const reRead = vi.fn()
            .mockResolvedValueOnce([withMeta({ mod: 'rebom', processedTag: `${UUID}-e1`, processedFileDigest: OLD_DIGEST })])
            .mockResolvedValueOnce([withMeta({ mod: 'rebom', processedTag: `${UUID}-e2`, processedFileDigest: NEW_DIGEST })]);

        expect(await fetchProcessedBomWithRetry(partial() as any, fetch, reRead)).toBe(content);
        expect(reRead).toHaveBeenCalledTimes(2);
        expect(fetch).toHaveBeenCalledTimes(2);
        expect(fetch).toHaveBeenLastCalledWith(`${UUID}-e2`, 'rebom-artifacts-2026-08', NEW_DIGEST);
    });
});
