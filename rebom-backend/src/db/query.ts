import { pool } from './pool';
import { logger } from '../logger';

export async function runQuery (query: string, params: any[]) : Promise<any> {
    const client = await pool.connect();
    try {
        return await client.query(query, params);
    } catch (error: any) {
        logger.error(`Error running query: ${error}`);
        if (error.code === '23505') {
            console.log('Unique constraint violation:', error.detail);
            throw new Error(`Duplicate entry: ${error.detail}`);
        } else if (error.code === '23502') {
            console.log('Not-null constraint violation:', error.detail);
            throw new Error(`Cannot be null: ${error.detail}`);
        } else if (error.code === '23503') {
            console.log('Foreign key constraint violation:', error.detail);
            throw new Error(`Invalid reference: ${error.detail}`);
        }
        throw error;
    } finally {
        client.release();
    }
}
