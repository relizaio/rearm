import { logger } from '../../logger';
import { withTransaction } from '../../db';
import * as BomLineageRepository from '../../bomLineageRepository';
import { BomValidationError } from '../../types/errors';
import {
  BomLineageDto,
  BomLineageOwnerEntry,
  BomLineageOwnerMode,
  BomLineageOwnerOutcome,
  BomLineageOwnerResult,
  isUuid
} from '../../types';

/**
 * The service-to-service lineage operations (SCORE-45): the backfill's claim of
 * unowned lineages, the admin reassign, and the read behind the admin query.
 * rebom has no principal; rearm-saas authorises both callers.
 */

export const MAX_LINEAGE_OWNER_ENTRIES = 500;

function requireUuid(field: string, value: unknown): string {
  if (!isUuid(value)) {
    throw new BomValidationError(`${field} must be a uuid`, { field, value });
  }
  return value.toLowerCase();
}

function requireSerialNumber(value: unknown): string {
  const key = typeof value === 'string' ? BomLineageRepository.canonicalSerialNumber(value) : '';
  if (!key) {
    throw new BomValidationError('serialNumber must not be blank', { field: 'serialNumber', value });
  }
  return key;
}

/**
 * Set the owner of each entry's lineage, in input order, each entry in its own
 * transaction. The whole batch is validated before any write.
 */
export async function setBomLineageOwners(
  org: string,
  entries: BomLineageOwnerEntry[],
  mode: BomLineageOwnerMode,
  actor?: string | null
): Promise<BomLineageOwnerResult[]> {
  const orgUuid = requireUuid('org', org);
  if (mode !== BomLineageOwnerMode.CLAIM_IF_UNOWNED && mode !== BomLineageOwnerMode.REASSIGN) {
    throw new BomValidationError('mode must be CLAIM_IF_UNOWNED or REASSIGN', { field: 'mode', value: mode });
  }
  const list = entries ?? [];
  if (list.length > MAX_LINEAGE_OWNER_ENTRIES) {
    throw new BomValidationError(`setBomLineageOwners accepts at most ${MAX_LINEAGE_OWNER_ENTRIES} entries`,
      { field: 'entries', value: list.length });
  }
  const validated = list.map(entry => ({
    serialNumber: entry.serialNumber,
    key: requireSerialNumber(entry.serialNumber),
    owner: requireUuid('ownerComponent', entry.ownerComponent)
  }));
  const actorText = actor ?? null;

  const results: BomLineageOwnerResult[] = [];
  for (const entry of validated) {
    const result = await setOwner(orgUuid, entry.serialNumber, entry.key, entry.owner, mode, actorText);
    logger.info({ org: orgUuid, serialNumber: entry.key, ownerComponent: entry.owner, mode, actor: actorText,
      outcome: result.outcome }, 'BOM lineage owner entry');
    results.push(result);
  }
  return results;
}

async function setOwner(
  org: string, serialNumber: string, key: string, owner: string, mode: BomLineageOwnerMode, actor: string | null
): Promise<BomLineageOwnerResult> {
  // A claim cannot pre-own a serialNumber nothing was uploaded under: rebom.boms
  // decides, not the lineage table.
  if (!(await BomLineageRepository.hasBomRows(org, key))) {
    return { serialNumber, outcome: BomLineageOwnerOutcome.NO_SUCH_LINEAGE };
  }
  if (mode === BomLineageOwnerMode.REASSIGN) {
    const previousOwner = await withTransaction(client => BomLineageRepository.reassign(client, org, key, owner, actor));
    const outcome = previousOwner === owner ? BomLineageOwnerOutcome.UNCHANGED_SAME_OWNER : BomLineageOwnerOutcome.SET;
    return { serialNumber, outcome, ownerComponent: owner };
  }
  const claim = await withTransaction(client => BomLineageRepository.claimIfUnowned(client, org, key, owner, actor));
  if (claim.previousOwner === null) {
    return { serialNumber, outcome: BomLineageOwnerOutcome.SET, ownerComponent: claim.owner };
  }
  if (claim.previousOwner === owner) {
    return { serialNumber, outcome: BomLineageOwnerOutcome.UNCHANGED_SAME_OWNER, ownerComponent: claim.owner };
  }
  // Never name the actual owner here: the admin read is the place for it.
  return { serialNumber, outcome: BomLineageOwnerOutcome.REFUSED_OWNED_BY_OTHER };
}

/** The lineage row of the canonical serialNumber, or null when none exists. */
export async function bomLineage(org: string, serialNumber: string): Promise<BomLineageDto | null> {
  const orgUuid = requireUuid('org', org);
  const key = requireSerialNumber(serialNumber);
  const row = await BomLineageRepository.find(orgUuid, key);
  if (!row) return null;
  return {
    serialNumber: row.serial_number,
    ownerComponent: row.owner_component,
    claimedAt: row.claimed_at,
    claimedVia: row.claimed_via,
    claimedBy: row.claimed_by
  };
}
