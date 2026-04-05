import { pool } from '../utils';
import { logger } from '../logger';
import type { PoolClient } from 'pg';

/**
 * PostgreSQL advisory lock keys.
 * Each value must be a unique integer across the application.
 * Mirrors the AdvisoryLockKey enum pattern used in the Java backend.
 */
export enum AdvisoryLockKey {
  ENRICHMENT_SCHEDULER = 1
}

/**
 * Attempts to acquire a PostgreSQL session-level advisory lock (non-blocking).
 *
 * IMPORTANT: Advisory locks are connection-scoped. This function checks out a
 * dedicated client from the pool and holds it open. The caller MUST call
 * releaseAdvisoryLock() with the returned client to unlock and return the
 * connection to the pool. If the process crashes, PostgreSQL automatically
 * releases the lock when the connection is dropped.
 *
 * @returns The dedicated PoolClient if the lock was acquired, null otherwise.
 */
export async function tryAdvisoryLock(key: AdvisoryLockKey): Promise<PoolClient | null> {
  const client = await pool.connect();
  try {
    const result = await client.query('SELECT pg_try_advisory_lock($1) AS acquired', [key]);
    const acquired = result.rows[0]?.acquired as boolean;
    logger.debug({ key, acquired }, 'Advisory lock attempt');
    if (acquired) {
      return client;
    }
    client.release();
    return null;
  } catch (error) {
    client.release();
    throw error;
  }
}

/**
 * Releases a previously acquired PostgreSQL session-level advisory lock and
 * returns the dedicated client back to the pool.
 *
 * @param client - The PoolClient returned by tryAdvisoryLock.
 * @param key - Must match the key passed to tryAdvisoryLock.
 */
export async function releaseAdvisoryLock(client: PoolClient, key: AdvisoryLockKey): Promise<void> {
  try {
    await client.query('SELECT pg_advisory_unlock($1)', [key]);
    logger.debug({ key }, 'Advisory lock released');
  } finally {
    client.release();
  }
}
