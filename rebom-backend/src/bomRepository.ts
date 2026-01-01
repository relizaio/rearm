import { runQuery } from './db';
import { BomRecord } from './types';
import { logger } from './logger';

export async function findAllBoms(): Promise<BomRecord[]> {
    try {
        const queryRes = await runQuery('SELECT * FROM rebom.boms', []);
        return queryRes.rows as BomRecord[];
    } catch (error) {
        logger.error({ error }, 'Error finding all BOMs');
        throw error;
    }
}

export async function bomById(id: string): Promise<BomRecord[]> {
    try {
        const queryRes = await runQuery('SELECT * FROM rebom.boms WHERE uuid = $1', [id]);
        return queryRes.rows as BomRecord[];
    } catch (error) {
        logger.error({ error, id }, 'Error finding BOM by ID');
        throw error;
    }
}

export async function bomBySerialNumber(serialNumber: string, org: string): Promise<BomRecord[]> {
    try {
        const serialWithUrn = serialNumber.startsWith('urn:uuid:') ? serialNumber : `urn:uuid:${serialNumber}`;
        const serialWithoutUrn = serialNumber.replace('urn:uuid:', '');
        
        const queryRes = await runQuery(
            `SELECT * FROM rebom.boms 
             WHERE (meta->>'serialNumber' = $1 OR meta->>'serialNumber' = $2) 
             AND organization::text = $3 
             ORDER BY (meta->>'bomVersion')::numeric DESC NULLS LAST
             LIMIT 1`, 
            [serialWithUrn, serialWithoutUrn, org]
        );
        return queryRes.rows as BomRecord[];
    } catch (error) {
        logger.error({ error, serialNumber, org }, 'Error finding BOM by serial number');
        throw error;
    }
}

export async function allBomsBySerialNumber(serialNumber: string, org: string): Promise<BomRecord[]> {
    try {
        const serialWithUrn = serialNumber.startsWith('urn:uuid:') ? serialNumber : `urn:uuid:${serialNumber}`;
        const serialWithoutUrn = serialNumber.replace('urn:uuid:', '');
        
        const queryRes = await runQuery(
            `SELECT * FROM rebom.boms 
             WHERE (meta->>'serialNumber' = $1 OR meta->>'serialNumber' = $2) 
             AND organization::text = $3 
             ORDER BY (meta->>'bomVersion')::numeric DESC NULLS LAST`, 
            [serialWithUrn, serialWithoutUrn, org]
        );
        return queryRes.rows as BomRecord[];
    } catch (error) {
        logger.error({ error, serialNumber, org }, 'Error finding all BOMs by serial number');
        throw error;
    }
}

export async function bomsByIds(ids: string[]): Promise<BomRecord[]> {
    try {
        if (!ids || ids.length === 0) {
            return [];
        }
        
        const placeholders = ids.map((_, index) => `$${index + 1}`).join(', ');
        const queryRes = await runQuery(
            `SELECT * FROM rebom.boms WHERE uuid::text IN (${placeholders})`,
            ids
        );
        return queryRes.rows as BomRecord[];
    } catch (error) {
        logger.error({ error, idsCount: ids?.length }, 'Error finding BOMs by IDs');
        throw error;
    }
}

export async function bomByOrgAndDigest(digest: string, org: string): Promise<BomRecord[]> {
    try {
        const queryRes = await runQuery(
            `SELECT * FROM rebom.boms WHERE meta->>'bomDigest' = $1 AND organization::text = $2`,
            [digest, org]
        );
        return queryRes.rows as BomRecord[];
    } catch (error) {
        logger.error({ error, digest, org }, 'Error finding BOM by digest and organization');
        throw error;
    }
}