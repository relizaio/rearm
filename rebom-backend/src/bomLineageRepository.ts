import { TxClient } from './db';

/**
 * Keys and locks a serialNumber lineage: the versions of one serialNumber in one
 * organization (SCORE-46 ADR-13). Stores nothing; the stored rows are those of
 * rebom.boms. The functions taking a TxClient run their statements on that
 * client and nothing else, so the caller owns the transaction.
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

/**
 * The lineage lock (SCORE-46 ADR-13): a transaction-scoped advisory lock on
 * (organization, canonical serialNumber), taken as the first statement of every
 * transaction that may write a row of the lineage. It writes nothing and is
 * released by the commit or the rollback, so the version decisions of two
 * uploads of one serialNumber commit in a serial order. Two-key lock space, apart from the one-key session lock of
 * services/advisoryLock.ts. The organization is keyed by its uuid value
 * ($1::uuid::text), so every spelling the uuid type parses takes the one lock
 * its stored rows are under.
 */
export async function lockLineage(client: TxClient, org: string, key: string): Promise<void> {
    await client.query('SELECT pg_advisory_xact_lock(hashtext($1::uuid::text), hashtext($2))', [org, key]);
}

/**
 * The lineage's newest stored row of any format, in either serialNumber form,
 * read on the transaction's own client (never through runQuery: a transaction
 * holding one pool connection and waiting for another starves the pool).
 * Authoritative only under lockLineage. The organization is compared as a uuid,
 * so any spelling of it reads the rows stored under it. Null when the lineage
 * has no row.
 */
export async function latestStoredVersion(
    client: TxClient, org: string, key: string
): Promise<{ uuid: string, bom_version: string | null } | null> {
    const serialWithoutUrn = key.replace(URN_UUID_PREFIX, '');
    const queryRes = await client.query(
        `SELECT uuid, meta->>'bomVersion' AS bom_version FROM rebom.boms
         WHERE (meta->>'serialNumber' = $1 OR meta->>'serialNumber' = $2)
         AND organization = $3::uuid
         ORDER BY (meta->>'bomVersion')::numeric DESC NULLS LAST
         LIMIT 1`,
        [key, serialWithoutUrn, org]
    );
    const row = queryRes.rows[0];
    return row ? { uuid: row.uuid, bom_version: row.bom_version } : null;
}
