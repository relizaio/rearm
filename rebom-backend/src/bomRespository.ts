const utils = require('./utils')
import { BomRecord } from './types';

export async function findAllBoms(): Promise<BomRecord[]> {
    let queryRes = await utils.runQuery('select * from rebom.boms', [])
    let boms = queryRes.rows as BomRecord[]
    return boms
}

export async function bomById(id: string): Promise<BomRecord[]> {
    let byIdRows = await utils.runQuery(`select * from rebom.boms where uuid = $1`, [id])
    let boms = byIdRows.rows as BomRecord[]
    return boms
}

export async function bomBySerialNumber(serialNumber: string, org: string): Promise<BomRecord[]> {
    // Handle both with and without urn:uuid: prefix
    const serialWithUrn = serialNumber.startsWith('urn:uuid:') ? serialNumber : `urn:uuid:${serialNumber}`;
    const serialWithoutUrn = serialNumber.replace('urn:uuid:', '');
    
    // Returns the latest version (highest bomVersion)
    let byIdRows = await utils.runQuery(
        `SELECT * FROM rebom.boms 
         WHERE (meta->>'serialNumber' = $1 OR meta->>'serialNumber' = $2) 
         AND organization::text = $3 
         ORDER BY (meta->>'bomVersion')::numeric DESC NULLS LAST
         LIMIT 1;`, 
        [serialWithUrn, serialWithoutUrn, org]
    );
    let boms = byIdRows.rows as BomRecord[]
    return boms
}

export async function allBomsBySerialNumber(serialNumber: string, org: string): Promise<BomRecord[]> {
    // Handle both with and without urn:uuid: prefix
    const serialWithUrn = serialNumber.startsWith('urn:uuid:') ? serialNumber : `urn:uuid:${serialNumber}`;
    const serialWithoutUrn = serialNumber.replace('urn:uuid:', '');
    
    // Returns all versions ordered by bomVersion DESC
    let byIdRows = await utils.runQuery(
        `SELECT * FROM rebom.boms 
         WHERE (meta->>'serialNumber' = $1 OR meta->>'serialNumber' = $2) 
         AND organization::text = $3 
         ORDER BY (meta->>'bomVersion')::numeric DESC NULLS LAST;`, 
        [serialWithUrn, serialWithoutUrn, org]
    );
    let boms = byIdRows.rows as BomRecord[]
    return boms
}

export async function bomsByIds(ids: string[]): Promise<BomRecord[]> {
    let queryRes = await utils.runQuery(`select * from rebom.boms where uuid::text in ('` + ids.join('\',\'') + `')`)
    let boms = queryRes.rows as BomRecord[]
    return boms
}

export async function bomByOrgAndDigest(digest: string, org: string): Promise<BomRecord[]> {
    let queryRes = await utils.runQuery(`select * from rebom.boms where meta->>'bomDigest' = $1 and organization::text = $2`, [digest, org])
    let boms = queryRes.rows as BomRecord[]
    return boms
}