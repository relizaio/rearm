import { logger } from '../../logger';
import { runQuery } from '../../utils';
import { OciNotFoundError, DigestValidationError } from '../../types/errors';
import { fetchFromOci as defaultFetchFromOci, getMonthlyRepositoryName } from './index';
import { extractRepositoryNameFromBom } from './ociRepositoryHelpers';
import { fetchProcessedBomWithRetry } from './processedBomFetcher';
import type { BomRecord } from '../../types/bom.types';

/**
 * Raw-BOM fetch with verified repository resolution.
 *
 * WHY: the record's single repository pointer (bom.ociRepositoryName) tracks
 * the PROCESSED BOM, which enrichment re-pushes into the CURRENT month's
 * repository (deliberately -- closed monthly repositories are the backup unit
 * and are never written to). The '-raw' copy stays in the upload month's
 * repository, so any BOM enriched in a later month than it was uploaded has
 * its pair split across repositories. The old fetchRawBomWithFallback then
 * missed the raw copy in the recorded repository and fell back to the
 * processed tag while still validating against the RAW digest -- surfacing a
 * misplaced artifact as "Digest validation failed".
 *
 * Resolution order (every candidate fetch is digest-validated against
 * meta.originalFileDigest, so probing can never serve the wrong bytes):
 *   1. meta.rawOciRepositoryName (stamped at upload for new rows, or by a
 *      previous resolution);
 *   2. the recorded processed repository (covers unsplit rows);
 *   3. the upload month derived from created_date, then the adjacent
 *      previous month (upload vs row-insert can straddle midnight);
 *   4. the base legacy repository (pre-monthly-rotation rows).
 *
 * Error semantics:
 *   - OciNotFoundError (authoritative 404): try the next candidate.
 *   - DigestValidationError: rethrow IMMEDIATELY -- a present-but-corrupt raw
 *     artifact must surface as exactly that, never masked by a substitute.
 *   - anything else (5xx, transport): try the next candidate for
 *     availability, but make NO durable decisions this call (no pointer
 *     stamp, no raw-missing stamp) -- also covers version skew with an older
 *     OCI artifact service that reported missing artifacts as 500.
 *
 * When no candidate has the raw copy: serve the processed BOM as an explicit,
 * logged substitute (validated against meta.processedFileDigest), preserving
 * the legacy pre-raw-copy behavior -- and stamp meta.rawBomMissing when every
 * candidate answered with an authoritative 404 so later requests skip the
 * probing entirely.
 */
export async function resolveAndFetchRawBom(
    bomRecord: BomRecord,
    fetchFromOci: (tag: string, repo?: string, digest?: string) => Promise<any> = defaultFetchFromOci
): Promise<any> {
    const bomUuid = bomRecord.uuid;
    const rawTag = bomUuid + '-raw';
    const meta = bomRecord.meta || ({} as any);
    const recordedRepo = extractRepositoryNameFromBom(bomRecord);
    const rawDigest = meta.originalFileDigest;

    if (meta.rawBomMissing) {
        logger.debug({ bomUuid }, 'Raw BOM marked missing; serving processed BOM substitute');
        return fetchProcessedSubstitute(bomRecord, recordedRepo, fetchFromOci);
    }

    const candidates = buildCandidateRepos(meta.rawOciRepositoryName, recordedRepo, bomRecord.created_date);

    let sawAmbiguousError = false;
    for (const repo of candidates) {
        try {
            const content = await fetchFromOci(rawTag, repo, rawDigest);
            // Stamp the resolved monthly repository so this row never probes
            // again. Base-repo resolutions (repo undefined) are left unstamped
            // -- the base repository is already the terminal fallback.
            if (repo && repo !== meta.rawOciRepositoryName && !sawAmbiguousError) {
                await stampRawRepository(bomUuid, repo);
            }
            return content;
        } catch (error) {
            if (error instanceof DigestValidationError) {
                // Raw copy is PRESENT here but its bytes are wrong -- that is
                // the integrity failure this machinery exists to catch.
                throw error;
            }
            if (error instanceof OciNotFoundError) {
                logger.debug({ bomUuid, rawTag, repo }, 'Raw BOM not in candidate repository, trying next');
                continue;
            }
            sawAmbiguousError = true;
            logger.warn({
                bomUuid, rawTag, repo,
                error: error instanceof Error ? error.message : String(error)
            }, 'Non-404 error probing for raw BOM; continuing without making durable decisions');
        }
    }

    if (!sawAmbiguousError && rawDigest) {
        // Every candidate said an authoritative 404 -- the raw copy does not
        // exist (pre-raw-storage upload, or removed by retention). Remember
        // that so future requests go straight to the substitute.
        await stampRawMissing(bomUuid);
    }

    logger.warn({
        bomUuid,
        candidatesTried: candidates.map(c => c ?? '<base>'),
        ambiguousErrors: sawAmbiguousError
    }, 'Raw BOM unavailable in all candidate repositories; serving processed BOM as substitute');
    return fetchProcessedSubstitute(bomRecord, recordedRepo, fetchFromOci);
}

/** Ordered, de-duplicated candidate repositories; `undefined` = base legacy repo. */
function buildCandidateRepos(
    rawPointer: string | undefined,
    recordedRepo: string | undefined,
    createdDate: Date | string | undefined
): Array<string | undefined> {
    const candidates: Array<string | undefined> = [];
    const push = (repo: string | undefined) => {
        if (!candidates.some(c => c === repo)) candidates.push(repo);
    };
    if (rawPointer) push(rawPointer);
    if (recordedRepo) push(recordedRepo);
    if (createdDate) {
        const created = new Date(createdDate);
        if (!isNaN(created.getTime())) {
            push(getMonthlyRepositoryName(created));
            const prevMonth = new Date(Date.UTC(created.getUTCFullYear(), created.getUTCMonth() - 1, 15));
            push(getMonthlyRepositoryName(prevMonth));
        }
    }
    push(undefined); // base legacy repository, pre-monthly-rotation
    return candidates;
}

/** The processed BOM served in place of a missing raw copy -- validated with ITS
 * digest, via the race-tolerant fetch (a concurrent enrichment push must not
 * turn the substitute into a spurious digest failure either). */
async function fetchProcessedSubstitute(
    bomRecord: BomRecord,
    recordedRepo: string | undefined,
    fetchFromOci: (tag: string, repo?: string, digest?: string) => Promise<any>
): Promise<any> {
    logger.info({
        bomUuid: bomRecord.uuid,
        repository: recordedRepo,
        validated: !!bomRecord.meta?.processedFileDigest
    }, 'Serving processed BOM as substitute for unavailable raw BOM');
    return fetchProcessedBomWithRetry(bomRecord, fetchFromOci);
}

async function stampRawRepository(bomUuid: string, repositoryName: string): Promise<void> {
    try {
        await runQuery(
            `UPDATE rebom.boms SET meta = jsonb_set(meta, '{rawOciRepositoryName}', $2::jsonb) WHERE uuid = $1`,
            [bomUuid, JSON.stringify(repositoryName)]
        );
        logger.info({ bomUuid, repositoryName }, 'Stamped resolved raw BOM repository');
    } catch (error) {
        // Stamping is an optimization; the fetch already succeeded.
        logger.error({ bomUuid, repositoryName, error }, 'Failed to stamp raw BOM repository');
    }
}

async function stampRawMissing(bomUuid: string): Promise<void> {
    try {
        await runQuery(
            `UPDATE rebom.boms SET meta = jsonb_set(meta, '{rawBomMissing}', 'true'::jsonb) WHERE uuid = $1`,
            [bomUuid]
        );
        logger.info({ bomUuid }, 'Stamped raw BOM as missing (no copy in any candidate repository)');
    } catch (error) {
        logger.error({ bomUuid, error }, 'Failed to stamp raw BOM as missing');
    }
}

/**
 * Startup census (SQL-only, no OCI traffic): how many CycloneDX rows still
 * need on-demand raw-repository resolution, and how many of those are in the
 * known-split shape (recorded repository differs from the upload month).
 * Always prints one line, mirroring the duplicate-components census.
 */
export async function logRawRepositoryCensus(): Promise<void> {
    try {
        const res = await runQuery(
            `SELECT count(*) AS unresolved,
                    count(*) FILTER (
                        WHERE bom->>'ociRepositoryName' IS NOT NULL
                          AND bom->>'ociRepositoryName' != ('rebom-artifacts-' || to_char(created_date, 'YYYY-MM'))
                    ) AS split
             FROM rebom.boms
             WHERE source_spdx_uuid IS NULL
               AND meta->>'rawOciRepositoryName' IS NULL
               AND meta->>'rawBomMissing' IS NULL`,
            []
        );
        const row = res.rows?.[0] || {};
        logger.error({
            unresolvedRawRepoRows: Number(row.unresolved || 0),
            knownSplitRows: Number(row.split || 0)
        }, '[RAW-REPO-CENSUS] rows pending on-demand raw-repository resolution (split rows would previously fail raw fetches with a digest error)');
    } catch (error) {
        logger.error({ error }, '[RAW-REPO-CENSUS] census query failed');
    }
}
