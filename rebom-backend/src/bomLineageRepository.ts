import { runQuery, TxClient } from './db';
import { BomLineageClaimedVia, BomLineageRecord } from './types';
import { logger } from './logger';

/**
 * Ownership of serialNumber lineages (SCORE-45): one row of rebom.bom_lineage per
 * organization and canonical serialNumber. The functions taking a TxClient run
 * their statements on that client and nothing else, so the caller owns the
 * transaction (claim and row insert commit or roll back together).
 */

const URN_UUID_PREFIX = 'urn:uuid:';

/**
 * The lineage key: trimmed, with the urn:uuid: prefix added when absent. No
 * case folding. Maps the two forms bomBySerialNumber matches (with and
 * without the prefix) to one key; a blank value gives ''.
 */
export function canonicalSerialNumber(value: string): string {
    const trimmed = (value ?? '').trim();
    if (!trimmed) return '';
    return trimmed.startsWith(URN_UUID_PREFIX) ? trimmed : `${URN_UUID_PREFIX}${trimmed}`;
}

/** The lineage row of (org, key), or null when none exists. One primary-key read. */
export async function find(org: string, key: string): Promise<BomLineageRecord | null> {
    const queryRes = await runQuery(
        'SELECT * FROM rebom.bom_lineage WHERE organization = $1 AND serial_number = $2',
        [org, key]
    );
    return (queryRes.rows[0] as BomLineageRecord) ?? null;
}

/** Whether rebom.boms holds any row of the serialNumber in the organization, in either form. */
export async function hasBomRows(org: string, key: string): Promise<boolean> {
    const serialWithoutUrn = key.replace(URN_UUID_PREFIX, '');
    const queryRes = await runQuery(
        `SELECT 1 FROM rebom.boms
         WHERE (meta->>'serialNumber' = $1 OR meta->>'serialNumber' = $2)
         AND organization::text = $3
         LIMIT 1`,
        [key, serialWithoutUrn, org]
    );
    return queryRes.rows.length > 0;
}

async function ensureRow(client: TxClient, org: string, key: string): Promise<void> {
    await client.query(
        'INSERT INTO rebom.bom_lineage (organization, serial_number) VALUES ($1, $2) ON CONFLICT (organization, serial_number) DO NOTHING',
        [org, key]
    );
}

async function lockOwner(client: TxClient, org: string, key: string): Promise<{ exists: boolean, owner: string | null }> {
    const queryRes = await client.query(
        'SELECT owner_component FROM rebom.bom_lineage WHERE organization = $1 AND serial_number = $2 FOR UPDATE',
        [org, key]
    );
    const row = queryRes.rows[0];
    return { exists: !!row, owner: row ? row.owner_component : null };
}

async function claimUnowned(
    client: TxClient, org: string, key: string, owner: string, via: BomLineageClaimedVia, actor: string | null
): Promise<void> {
    await client.query(
        `UPDATE rebom.bom_lineage SET owner_component = $3, claimed_at = now(), claimed_via = $4, claimed_by = $5
         WHERE organization = $1 AND serial_number = $2 AND owner_component IS NULL`,
        [org, key, owner, via, actor]
    );
    logger.info({ org, serialNumber: key, ownerComponent: owner, via }, 'BOM lineage claimed');
}

/**
 * The upload's claim, inside the caller's transaction: create the row (owner
 * only), lock it, claim it when unowned. Answers the owner now stored, which
 * is another component when the lineage was claimed since the pre-check; the
 * caller refuses then. A null owner creates no row and claims nothing.
 */
export async function claimInTransaction(
    client: TxClient, org: string, key: string, owner: string | null
): Promise<string | null> {
    if (owner) {
        await ensureRow(client, org, key);
    }
    const locked = await lockOwner(client, org, key);
    if (!locked.exists) {
        return null;
    }
    if (locked.owner === null && owner) {
        await claimUnowned(client, org, key, owner, BomLineageClaimedVia.UPLOAD, null);
        return owner;
    }
    return locked.owner;
}

/**
 * The backfill's claim: set the owner only when the lineage is unowned.
 * Answers the owner before the call (null when it was unowned) and the owner now
 * stored.
 */
export async function claimIfUnowned(
    client: TxClient, org: string, key: string, owner: string, actor: string | null
): Promise<{ previousOwner: string | null, owner: string | null }> {
    await ensureRow(client, org, key);
    const locked = await lockOwner(client, org, key);
    if (locked.owner === null) {
        await claimUnowned(client, org, key, owner, BomLineageClaimedVia.BACKFILL, actor);
        return { previousOwner: null, owner };
    }
    return { previousOwner: locked.owner, owner: locked.owner };
}

/**
 * The admin remedy: set the owner unconditionally and record who did it, also
 * when it is already the owner. Answers the owner before the call.
 */
export async function reassign(
    client: TxClient, org: string, key: string, owner: string, actor: string | null
): Promise<string | null> {
    await ensureRow(client, org, key);
    const locked = await lockOwner(client, org, key);
    await client.query(
        `UPDATE rebom.bom_lineage SET owner_component = $3, claimed_at = now(), claimed_via = $4, claimed_by = $5
         WHERE organization = $1 AND serial_number = $2`,
        [org, key, owner, BomLineageClaimedVia.REASSIGN, actor]
    );
    logger.info({ org, serialNumber: key, ownerComponent: owner, previousOwner: locked.owner, via: BomLineageClaimedVia.REASSIGN }, 'BOM lineage reassigned');
    return locked.owner;
}
