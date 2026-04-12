import { getPool } from '../../utils';
import { logger } from '../../logger';

export interface HealthStatus {
    status: string;
    database: boolean;
    oci: boolean;
    version: string;
}

export async function checkDatabaseConnection(): Promise<boolean> {
    try {
        const pool = getPool();
        const client = await pool.connect();
        await client.query('SELECT 1');
        client.release();
        return true;
    } catch (error) {
        logger.error({ error: error instanceof Error ? error.message : String(error) }, 'Database health check failed');
        return false;
    }
}

export async function checkOciConnection(): Promise<boolean> {
    try {
        const ociHost = process.env.OCI_ARTIFACT_SERVICE_HOST || 'http://[::1]:8083/';
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 5000);
        try {
            const response = await fetch(`${ociHost}health`, { signal: controller.signal });
            return response.status === 200;
        } finally {
            clearTimeout(timeoutId);
        }
    } catch (error) {
        logger.error({ error: error instanceof Error ? error.message : String(error) }, 'OCI health check failed');
        return false;
    }
}

export async function getHealthStatus(): Promise<HealthStatus> {
    const dbOk = await checkDatabaseConnection();
    const ociOk = await checkOciConnection();
    
    return {
        status: dbOk && ociOk ? 'HEALTHY' : 'DEGRADED',
        database: dbOk,
        oci: ociOk,
        version: process.env.npm_package_version ?? '0.0.0'
    };
}
