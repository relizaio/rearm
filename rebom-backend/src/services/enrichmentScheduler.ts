import { logger } from '../logger';
import { EnrichmentStatus, IntegrationType } from '../types';
import * as BomRepository from '../bomRepository';
import { fetchFromOci, extractRepositoryNameFromBom } from './oci';
import { enrichBomAsync } from './bom/bomProcessingService';
import { getBearCredentials } from './integrationService';
import { runQuery } from '../utils';
import { AdvisoryLockKey, tryAdvisoryLock, releaseAdvisoryLock } from './advisoryLock';

const SCHEDULER_INTERVAL_MS = 30 * 60 * 1000; // 30 minutes
const ENRICHMENT_BATCH_LIMIT = 50;

// A BOM stuck in PENDING past this window is treated as abandoned: the
// fire-and-forget enrich kicked off at ingest never reached a terminal status
// — the pod rolled mid-enrichment, or BEAR was configured after the BOM was
// uploaded so the ingest-time attempt early-returned with no credentials.
// Matches triggerEnrichment's timeout(30m)+grace(5m) so the scheduler never
// races an enrich that is genuinely still in flight.
const STALE_PENDING_THRESHOLD_MS = 35 * 60 * 1000;

let schedulerInterval: NodeJS.Timeout | null = null;
let isRunning = false;

// TODO: implement retry counter and permanently failed status on bom enrichment 

/**
 * Finds BOMs that need enrichment. Picks up:
 *  - never-attempted / retryable rows: enrichmentStatus null, FAILED, or SKIPPED;
 *  - abandoned PENDING rows: stuck in PENDING past STALE_PENDING_THRESHOLD_MS,
 *    but ONLY for orgs that actually have a BEAR integration configured.
 *    Without this branch a BOM whose ingest-time enrich never reached a
 *    terminal status (pod rolled mid-run, or BEAR was configured after the
 *    upload) stays PENDING forever — the manual triggerEnrichment mutation was
 *    the only way to recover it. The org-has-BEAR guard keeps un-enrichable
 *    PENDING rows (no integration → getBearCredentials returns null) out of the
 *    batch so they can't starve enrichable ones under the LIMIT.
 * Returns up to ENRICHMENT_BATCH_LIMIT records.
 * IMPORTANT: Must include 'bom' field so extractRepositoryNameFromBom can find repository name.
 */
async function findBomsNeedingEnrichment(): Promise<Array<{ uuid: string; organization: string; serialNumber: string; meta: any; bom: any }>> {
  const queryText = `
    SELECT b.uuid, b.organization, b.meta->>'serialNumber' as "serialNumber", b.bom
    FROM rebom.boms b
    WHERE b.meta->>'enrichmentStatus' IS NULL
       OR b.meta->>'enrichmentStatus' = $1
       OR b.meta->>'enrichmentStatus' = $2
       OR (
            b.meta->>'enrichmentStatus' = $3
            AND b.created_date < $4
            AND EXISTS (
              SELECT 1 FROM rebom.integrations i
              WHERE i.organization = b.organization
                AND i.config->>'type' = $5
                AND i.config->>'uri' IS NOT NULL
                AND i.config->>'secretUuid' IS NOT NULL
            )
          )
    ORDER BY b.created_date ASC
    LIMIT $6
  `;

  const stalePendingCutoff = new Date(Date.now() - STALE_PENDING_THRESHOLD_MS).toISOString();
  const result = await runQuery(queryText, [
    EnrichmentStatus.FAILED,
    EnrichmentStatus.SKIPPED,
    EnrichmentStatus.PENDING,
    stalePendingCutoff,
    IntegrationType.BEAR,
    ENRICHMENT_BATCH_LIMIT
  ]);

  return result.rows;
}

/**
 * Runs one cycle of the enrichment scheduler.
 * Finds BOMs needing enrichment and triggers enrichment for each.
 */
async function runEnrichmentCycle(): Promise<void> {
  if (isRunning) {
    logger.debug('Enrichment scheduler: Previous cycle still running, skipping');
    return;
  }

  // Acquire DB-level advisory lock so only one pod runs enrichment at a time
  let lockClient = null;
  try {
    lockClient = await tryAdvisoryLock(AdvisoryLockKey.ENRICHMENT_SCHEDULER);
  } catch (error) {
    logger.error({ error: error instanceof Error ? error.message : String(error) }, 'Enrichment scheduler: Failed to acquire advisory lock');
    return;
  }

  if (!lockClient) {
    logger.debug('Enrichment scheduler: Another pod holds the advisory lock, skipping cycle');
    return;
  }

  isRunning = true;
  logger.info('Enrichment scheduler: Starting cycle');
  
  try {
    const bomsToEnrich = await findBomsNeedingEnrichment();
    
    if (bomsToEnrich.length === 0) {
      logger.debug('Enrichment scheduler: No BOMs need enrichment');
      return;
    }
    
    logger.info({ count: bomsToEnrich.length }, 'Enrichment scheduler: Found BOMs needing enrichment');
    
    // Cache credential lookups per org to avoid redundant DB calls
    const credentialsByOrg = new Map<string, Awaited<ReturnType<typeof getBearCredentials>>>();
    let processed = 0;
    let skippedNoCredentials = 0;
    
    for (const bom of bomsToEnrich) {
      try {
        // Check credentials before fetching BOM content
        if (!credentialsByOrg.has(bom.organization)) {
          credentialsByOrg.set(bom.organization, await getBearCredentials(bom.organization));
        }
        
        if (!credentialsByOrg.get(bom.organization)) {
          skippedNoCredentials++;
          logger.debug({ bomUuid: bom.uuid, org: bom.organization }, 'Enrichment scheduler: No credentials for org, skipping');
          continue;
        }
        
        logger.info({ bomUuid: bom.uuid, serialNumber: bom.serialNumber }, 'Enrichment scheduler: Triggering enrichment');
        
        // Fetch BOM content from OCI (augmented BOM - validate with processedFileDigest)
        const storedRepositoryName = extractRepositoryNameFromBom(bom);
        const expectedDigest = bom.meta?.processedFileDigest;
        const bomContent = await fetchFromOci(bom.uuid, storedRepositoryName, expectedDigest);
        
        await enrichBomAsync(bom.uuid, bomContent, bom.organization, credentialsByOrg.get(bom.organization)).catch(err => {
          logger.error({ err, bomUuid: bom.uuid }, 'Enrichment scheduler: Async enrichment failed');
        });
        
        processed++;
      } catch (error) {
        logger.error({ 
          error: error instanceof Error ? error.message : String(error),
          bomUuid: bom.uuid 
        }, 'Enrichment scheduler: Failed to trigger enrichment for BOM');
      }
    }
    
    logger.info({ processed, skippedNoCredentials }, 'Enrichment scheduler: Cycle completed');
    
  } catch (error) {
    logger.error({ 
      error: error instanceof Error ? error.message : String(error) 
    }, 'Enrichment scheduler: Cycle failed');
  } finally {
    isRunning = false;
    if (lockClient) {
      await releaseAdvisoryLock(lockClient, AdvisoryLockKey.ENRICHMENT_SCHEDULER).catch(err => {
        logger.error({ err }, 'Enrichment scheduler: Failed to release advisory lock');
      });
    }
  }
}

/**
 * Starts the enrichment scheduler.
 * Runs immediately on start, then every 15 minutes.
 */
export function startEnrichmentScheduler(): void {
  if (schedulerInterval) {
    logger.warn('Enrichment scheduler already running');
    return;
  }
  
  logger.info({ intervalMs: SCHEDULER_INTERVAL_MS }, 'Starting enrichment scheduler (credentials resolved per-org from DB)');
  
  // Run immediately on startup
  runEnrichmentCycle().catch(err => {
    logger.error({ err }, 'Enrichment scheduler: Initial cycle failed');
  });
  
  // Schedule recurring runs
  schedulerInterval = setInterval(() => {
    runEnrichmentCycle().catch(err => {
      logger.error({ err }, 'Enrichment scheduler: Scheduled cycle failed');
    });
  }, SCHEDULER_INTERVAL_MS);
}

/**
 * Stops the enrichment scheduler.
 */
export function stopEnrichmentScheduler(): void {
  if (schedulerInterval) {
    clearInterval(schedulerInterval);
    schedulerInterval = null;
    logger.info('Enrichment scheduler stopped');
  }
}
