import { pool } from './pool';
import { logger } from '../logger';

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
    let client;
    try {
        client = await pool.connect();
    } catch (error: any) {
        // Connection pool error - log and throw wrapped error
        logger.error({ 
            err: error, 
            message: 'Failed to acquire database connection from pool'
        }, 'Database connection error');
        throw new Error(`Database connection failed: ${error.message || 'Unknown error'}`);
    }

    try {
        const result = await client.query(query, params);
        return result;
    } catch (error: any) {
        // Log detailed error information for debugging
        logger.error({ 
            err: error,
            query: query.substring(0, 200), // Log first 200 chars of query
            errorCode: error.code,
            errorDetail: error.detail,
            errorHint: error.hint,
            constraint: error.constraint
        }, 'Database query execution error');

        // Handle specific PostgreSQL error codes with user-friendly messages
        if (error.code === '23505') {
            // Unique constraint violation
            const constraintName = error.constraint || 'unknown';
            throw new Error(`Duplicate entry: ${error.detail || constraintName}`);
        } else if (error.code === '23502') {
            // Not-null constraint violation
            throw new Error(`Required field cannot be null: ${error.detail || error.column}`);
        } else if (error.code === '23503') {
            // Foreign key constraint violation
            throw new Error(`Invalid reference: ${error.detail || 'Referenced record does not exist'}`);
        } else if (error.code === '42P01') {
            // Undefined table
            throw new Error(`Database table not found: ${error.message}`);
        } else if (error.code === '42703') {
            // Undefined column
            throw new Error(`Database column not found: ${error.message}`);
        } else if (error.code === '22P02') {
            // Invalid text representation
            throw new Error(`Invalid data format: ${error.message}`);
        } else if (error.code === '23514') {
            // Check constraint violation
            throw new Error(`Data validation failed: ${error.detail || error.message}`);
        } else if (error.code === '08006' || error.code === '08003') {
            // Connection failure
            throw new Error(`Database connection lost: ${error.message}`);
        } else if (error.code === '57014') {
            // Query canceled
            throw new Error(`Query was canceled: ${error.message}`);
        } else if (error.code === '53300') {
            // Too many connections
            throw new Error(`Database connection limit reached: ${error.message}`);
        }

        // For any other database error, wrap it with a generic message
        // This ensures the original error is logged but a safe message is thrown
        throw new Error(`Database operation failed: ${error.message || 'Unknown database error'}`);
    } finally {
        // Always release the client back to the pool
        if (client) {
            try {
                await client.release();
            } catch (releaseError: any) {
                // Log release errors but don't throw - this is cleanup
                logger.error({ err: releaseError }, 'Error releasing database client to pool');
            }
        }
    }
}
