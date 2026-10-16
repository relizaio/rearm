import { pool } from './pool';
import { logger } from '../logger';

/**
 * The client a withTransaction callback runs its statements on: the pool
 * client's query, with a failing statement logged and mapped exactly as
 * runQuery does it.
 */
export type TxClient = {
    query(text: string, params: any[]): Promise<any>;
};

async function acquireClient(): Promise<any> {
    try {
        return await pool.connect();
    } catch (error: any) {
        // Connection pool error - log and throw wrapped error
        logger.error({
            err: error,
            message: 'Failed to acquire database connection from pool'
        }, 'Database connection error');
        throw new Error(`Database connection failed: ${error.message || 'Unknown error'}`);
    }
}

async function releaseClient(client: any): Promise<void> {
    try {
        await client.release();
    } catch (releaseError: any) {
        // Log release errors but don't throw - this is cleanup
        logger.error({ err: releaseError }, 'Error releasing database client to pool');
    }
}

function logDatabaseError(error: any, query: string): void {
    // Log detailed error information for debugging
    logger.error({
        err: error,
        query: query.substring(0, 200), // Log first 200 chars of query
        errorCode: error.code,
        errorDetail: error.detail,
        errorHint: error.hint,
        constraint: error.constraint
    }, 'Database query execution error');
}

/**
 * Map a PostgreSQL error to the plain Error rebom surfaces: a user-friendly
 * message for the known codes, a generic one otherwise. Shared by runQuery and
 * the withTransaction client so a statement fails the same way in both.
 */
export function mapDatabaseError(error: any, query: string): Error {
    // Handle specific PostgreSQL error codes with user-friendly messages
    if (error.code === '23505') {
        // Unique constraint violation
        const constraintName = error.constraint || 'unknown';
        return new Error(`Duplicate entry: ${error.detail || constraintName}`);
    } else if (error.code === '23502') {
        // Not-null constraint violation
        return new Error(`Required field cannot be null: ${error.detail || error.column}`);
    } else if (error.code === '23503') {
        // Foreign key constraint violation
        return new Error(`Invalid reference: ${error.detail || 'Referenced record does not exist'}`);
    } else if (error.code === '42P01') {
        // Undefined table
        return new Error(`Database table not found: ${error.message}`);
    } else if (error.code === '42703') {
        // Undefined column
        return new Error(`Database column not found: ${error.message}`);
    } else if (error.code === '22P02') {
        // Invalid text representation
        return new Error(`Invalid data format: ${error.message}`);
    } else if (error.code === '23514') {
        // Check constraint violation
        return new Error(`Data validation failed: ${error.detail || error.message}`);
    } else if (error.code === '08006' || error.code === '08003') {
        // Connection failure
        return new Error(`Database connection lost: ${error.message}`);
    } else if (error.code === '57014') {
        // Query canceled
        return new Error(`Query was canceled: ${error.message}`);
    } else if (error.code === '53300') {
        // Too many connections
        return new Error(`Database connection limit reached: ${error.message}`);
    }

    // For any other database error, wrap it with a generic message
    // This ensures the original error is logged but a safe message is thrown
    return new Error(`Database operation failed: ${error.message || 'Unknown database error'}`);
}

/**
 * Execute a database query with proper error handling.
 * Errors are caught, logged, and re-thrown as application errors.
 * This ensures database errors never crash the Node.js process.
 *
 * @param query - SQL query string
 * @param params - Query parameters
 * @returns Query result
 * @throws Error with user-friendly message (never crashes the process)
 */
export async function runQuery (query: string, params: any[]) : Promise<any> {
    const client = await acquireClient();

    try {
        const result = await client.query(query, params);
        return result;
    } catch (error: any) {
        logDatabaseError(error, query);
        throw mapDatabaseError(error, query);
    } finally {
        // Always release the client back to the pool
        await releaseClient(client);
    }
}

/**
 * Run fn inside one transaction on one pool client: BEGIN, the callback,
 * COMMIT. When the callback or the commit throws, ROLLBACK runs and the error
 * is rethrown as it is. The client is released in every case.
 */
export async function withTransaction<T>(fn: (client: TxClient) => Promise<T>): Promise<T> {
    const client = await acquireClient();
    const txClient: TxClient = {
        query: async (text: string, params: any[]) => {
            try {
                return await client.query(text, params);
            } catch (error: any) {
                logDatabaseError(error, text);
                throw mapDatabaseError(error, text);
            }
        }
    };

    try {
        await client.query('BEGIN');
        const result = await fn(txClient);
        await client.query('COMMIT');
        return result;
    } catch (error) {
        try {
            await client.query('ROLLBACK');
        } catch (rollbackError: any) {
            logger.error({ err: rollbackError }, 'Error rolling back database transaction');
        }
        throw error;
    } finally {
        await releaseClient(client);
    }
}
