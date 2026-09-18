import { logger } from '../../logger';
import { DigestValidationError } from '../../types/errors';
import { fetchFromOci as defaultFetchFromOci } from './index';
import { extractRepositoryNameFromBom } from './ociRepositoryHelpers';
import type { BomRecord } from '../../types/bom.types';

/**
 * Processed-BOM fetch, by the tag the row points at.
 *
 * Enrichment no longer overwrites bytes in place: it pushes to a fresh
 * {@code <uuid>-e<n>} tag and moves the row's processedTag, digest and
 * repository pointer to it in one write. A reader holding an older row
 * therefore fetches the older tag, whose bytes still hash to the digest that
 * row carries -- the race is closed by construction rather than survived.
 *
 * The retry below remains for rows written BEFORE that change, whose processed
 * bytes live at the bare {@code <uuid>} tag and were overwritten in place. For
 * those, a reader whose snapshot predates the row update but whose download
 * lands after the overwrite validates new bytes against an old digest. The
 * window is milliseconds for request-scoped readers but minutes for the
 * enrichment scheduler (it loads its candidate rows up front) and the
 * per-minute reconcile in the ReARM backend.
 *
 * Recovery discipline: on a digest failure, RE-READ the row once. If the
 * stored digest (or repository pointer) changed while we were looking, it was
 * the race -- refetch against the fresh values. If the row still describes
 * exactly what we validated against, the mismatch is real corruption and the
 * ORIGINAL error is rethrown untouched. One retry only: a second mismatch
 * against fresh row state is not a race artifact.
 */
/** Structural minimum: callers like the enrichment scheduler carry partial rows. */
type ProcessedBomSource = Pick<BomRecord, 'uuid' | 'meta' | 'bom'>;

/**
 * Which artifact holds this row's processed BOM.
 *
 * Rows written before enrichment stopped overwriting in place have no
 * processedTag and their bytes are at the bare uuid, which is exactly what the
 * fallback resolves to -- nothing was moved or migrated for them.
 */
export function resolveProcessedTag(bomRecord: Pick<BomRecord, 'uuid' | 'meta'>): string {
    return bomRecord.meta?.processedTag || bomRecord.uuid;
}

export async function fetchProcessedBomWithRetry(
    bomRecord: ProcessedBomSource,
    fetchFromOci: (tag: string, repo?: string, digest?: string) => Promise<any> = defaultFetchFromOci,
    reReadRow: (uuid: string) => Promise<BomRecord[]> = defaultReReadRow
): Promise<any> {
    const bomUuid = bomRecord.uuid;
    const tag = resolveProcessedTag(bomRecord);
    const repo = extractRepositoryNameFromBom(bomRecord);
    const digest = bomRecord.meta?.processedFileDigest;

    try {
        return await fetchFromOci(tag, repo, digest);
    } catch (error) {
        if (!(error instanceof DigestValidationError)) throw error;

        const freshRows = await reReadRow(bomUuid).catch(() => []);
        const fresh = freshRows[0];
        const freshDigest = fresh?.meta?.processedFileDigest;
        const freshRepo = fresh ? extractRepositoryNameFromBom(fresh) : undefined;
        const freshTag = fresh ? resolveProcessedTag(fresh) : undefined;

        if (!fresh || (freshDigest === digest && freshRepo === repo && freshTag === tag)) {
            // Row unchanged: the bytes genuinely do not match their stored
            // digest. This is the only path that knows the mismatch is a
            // failure rather than a race, so it is the only one that logs at
            // error -- fetchFromOci cannot tell the two apart from where it
            // stands.
            logger.error({
                bomUuid,
                tag,
                repository: repo,
                expectedDigest: digest,
                actualDigest: (error as DigestValidationError).actualDigest
            }, 'Processed BOM does not match its stored digest and the row is unchanged');
            throw error;
        }

        logger.warn({
            bomUuid,
            staleTag: tag,
            freshTag,
            staleDigest: digest,
            freshDigest,
            staleRepo: repo,
            freshRepo
        }, 'Digest mismatch was a concurrent enrichment write (row changed under the reader); retrying with fresh row state');
        return fetchFromOci(freshTag!, freshRepo, freshDigest);
    }
}

async function defaultReReadRow(uuid: string): Promise<BomRecord[]> {
    // Lazy require: bomRepository sits above this module in the import graph.
    const BomRepository = require('../../bomRepository');
    return BomRepository.bomById(uuid);
}
