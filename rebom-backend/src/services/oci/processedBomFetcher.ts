import { logger } from '../../logger';
import { DigestValidationError } from '../../types/errors';
import { fetchFromOci as defaultFetchFromOci } from './index';
import { extractRepositoryNameFromBom } from './ociRepositoryHelpers';
import type { BomRecord } from '../../types/bom.types';

/**
 * Processed-BOM fetch tolerant of the enrichment write race.
 *
 * Enrichment updates the processed BOM in TWO steps: it overwrites the bytes
 * at tag {@code <uuid>} in the registry, THEN writes the new
 * processedFileDigest (+ repository pointer) to the row. A reader whose row
 * snapshot predates step two but whose download lands after step one computes
 * the NEW bytes against the OLD digest and fails validation -- even though
 * nothing is corrupt. The window is milliseconds for request-scoped readers
 * but minutes for the enrichment scheduler (it loads its candidate rows up
 * front) and the per-minute reconcile in the ReARM backend, so purely
 * programmatic collisions are the common case.
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

export async function fetchProcessedBomWithRetry(
    bomRecord: ProcessedBomSource,
    fetchFromOci: (tag: string, repo?: string, digest?: string) => Promise<any> = defaultFetchFromOci,
    reReadRow: (uuid: string) => Promise<BomRecord[]> = defaultReReadRow
): Promise<any> {
    const bomUuid = bomRecord.uuid;
    const repo = extractRepositoryNameFromBom(bomRecord);
    const digest = bomRecord.meta?.processedFileDigest;

    try {
        return await fetchFromOci(bomUuid, repo, digest);
    } catch (error) {
        if (!(error instanceof DigestValidationError)) throw error;

        const freshRows = await reReadRow(bomUuid).catch(() => []);
        const fresh = freshRows[0];
        const freshDigest = fresh?.meta?.processedFileDigest;
        const freshRepo = fresh ? extractRepositoryNameFromBom(fresh) : undefined;

        if (!fresh || (freshDigest === digest && freshRepo === repo)) {
            // Row unchanged: the bytes genuinely do not match their stored
            // digest. Surface the original failure.
            throw error;
        }

        logger.warn({
            bomUuid,
            staleDigest: digest,
            freshDigest,
            staleRepo: repo,
            freshRepo
        }, 'Digest mismatch was a concurrent enrichment write (row changed under the reader); retrying with fresh digest');
        return fetchFromOci(bomUuid, freshRepo, freshDigest);
    }
}

async function defaultReReadRow(uuid: string): Promise<BomRecord[]> {
    // Lazy require: bomRepository sits above this module in the import graph.
    const BomRepository = require('../../bomRepository');
    return BomRepository.bomById(uuid);
}
