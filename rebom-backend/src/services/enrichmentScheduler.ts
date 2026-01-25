import { logger } from '../logger';
import { runQuery } from '../utils';
import { isEnrichmentConfigured, enrichBomAsync } from './bom/bomProcessingService';
import { fetchFromOci } from './oci';
import { EnrichmentStatus } from '../types';

const SCHEDULER_INTERVAL_MS = 5 * 60 * 1000; // 5 minutes
const ENRICHMENT_BATCH_LIMIT = 10;

let schedulerInterval: NodeJS.Timeout | null = null;
let isRunning = false;

/**
 * Finds BOMs that need enrichment (status is null, FAILED, or SKIPPED).
 * Returns up to ENRICHMENT_BATCH_LIMIT records.
 */
async function findBomsNeedingEnrichment(): Promise<Array<{ uuid: string; organization: string; serialNumber: string }>> {
  const queryText = `
    SELECT uuid, organization, meta->>'serialNumber' as "serialNumber"
    FROM rebom.boms 
    WHERE meta->>'enrichmentStatus' IS NULL 
       OR meta->>'enrichmentStatus' = $1
       OR meta->>'enrichmentStatus' = $2
    ORDER BY created_date ASC
    LIMIT $3
  `;
  
  const result = await runQuery(queryText, [
    EnrichmentStatus.FAILED,
    EnrichmentStatus.SKIPPED,
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
  
  if (!isEnrichmentConfigured()) {
    logger.debug('Enrichment scheduler: BEAR not configured, skipping cycle');
    return;
  }
  
  isRunning = true;
  logger.info('Enrichment scheduler: Starting cycle');
  
  try {
    const bomsToEnrich = await findBomsNeedingEnrichment();
    
    if (bomsToEnrich.length === 0) {
      logger.debug('Enrichment scheduler: No BOMs need enrichment');
      isRunning = false;
      return;
    }
    
    logger.info({ count: bomsToEnrich.length }, 'Enrichment scheduler: Found BOMs needing enrichment');
    
    for (const bom of bomsToEnrich) {
      try {
        logger.info({ bomUuid: bom.uuid, serialNumber: bom.serialNumber }, 'Enrichment scheduler: Triggering enrichment');
        
        // Fetch BOM content from OCI
        const bomContent = await fetchFromOci(bom.uuid);
        
        await enrichBomAsync(bom.uuid, bomContent, bom.organization).catch(err => {
          logger.error({ err, bomUuid: bom.uuid }, 'Enrichment scheduler: Async enrichment failed');
        });
        
      } catch (error) {
        logger.error({ 
          error: error instanceof Error ? error.message : String(error),
          bomUuid: bom.uuid 
        }, 'Enrichment scheduler: Failed to trigger enrichment for BOM');
      }
    }
    
    logger.info({ processed: bomsToEnrich.length }, 'Enrichment scheduler: Cycle completed');
    
  } catch (error) {
    logger.error({ 
      error: error instanceof Error ? error.message : String(error) 
    }, 'Enrichment scheduler: Cycle failed');
  } finally {
    isRunning = false;
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
  
  if (!isEnrichmentConfigured()) {
    logger.info('Enrichment scheduler: BEAR not configured, scheduler will not start');
    return;
  }
  
  logger.info({ intervalMs: SCHEDULER_INTERVAL_MS }, 'Starting enrichment scheduler');
  
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
