import { logger } from '../../logger';
import { canonicalSerialNumber } from '../../bomLineageRepository';
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
 * Deprecated compatibility stubs since SCORE-50: rebom stores no lineage owner.
 * The rearm-saas that still calls them (its backfill, supersession read and
 * admin reassign) gets the answers of an unowned lineage. Removed in SCORE-50
 * child d once rearm-saas stops calling them.
 */

export const MAX_LINEAGE_OWNER_ENTRIES = 500;

function requireUuid(field: string, value: unknown): string {
  if (!isUuid(value)) {
    throw new BomValidationError(`${field} must be a uuid`, { field, value });
  }
  return value.toLowerCase();
}

function requireSerialNumber(value: unknown): string {
  const key = typeof value === 'string' ? canonicalSerialNumber(value) : '';
  if (!key) {
    throw new BomValidationError('serialNumber must not be blank', { field: 'serialNumber', value });
  }
  return key;
}

/**
 * Answers NO_SUCH_LINEAGE for every entry, in input order, with no query. The
 * whole batch is validated before any answer, as before.
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

  return validated.map(entry => {
    const outcome = BomLineageOwnerOutcome.NO_SUCH_LINEAGE;
    logger.info({ org: orgUuid, serialNumber: entry.key, ownerComponent: entry.owner, mode, actor: actorText,
      outcome }, 'BOM lineage owner entry');
    return { serialNumber: entry.serialNumber, outcome };
  });
}

/** Always null, with no query; the arguments are validated as before. */
export async function bomLineage(org: string, serialNumber: string): Promise<BomLineageDto | null> {
  requireUuid('org', org);
  requireSerialNumber(serialNumber);
  return null;
}
