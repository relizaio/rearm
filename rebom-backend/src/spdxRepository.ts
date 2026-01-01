import { logger } from './logger';
import { SpdxBomRecord, SpdxMetadata } from './types';
import { OASResponse } from './services/oci';
import { runQuery } from './utils';

export async function createSpdxBom(spdxRecord: Partial<SpdxBomRecord>): Promise<SpdxBomRecord> {
    const query = `
        INSERT INTO rebom.spdx_boms (
            uuid, spdx_metadata, oci_response, organization, file_sha256, 
            conversion_status, tags, public, bom_version
        ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
        RETURNING *
    `;
    
    const values: any[] = [
        spdxRecord.uuid || null, // Use provided UUID or null
        spdxRecord.spdx_metadata || {},
        spdxRecord.oci_response || null,
        spdxRecord.organization || '',
        spdxRecord.file_sha256 || '',
        spdxRecord.conversion_status || 'pending',
        spdxRecord.tags || null,
        spdxRecord.public || false,
        spdxRecord.bom_version || 1
    ];

    try {
        const result = await runQuery(query, values);
        const row = result.rows[0];
        
        return {
            uuid: row.uuid,
            created_date: row.created_date,
            last_updated_date: row.last_updated_date,
            spdx_metadata: row.spdx_metadata,
            oci_response: row.oci_response,
            converted_bom_uuid: row.converted_bom_uuid,
            organization: row.organization,
            file_sha256: row.file_sha256,
            conversion_status: row.conversion_status,
            conversion_error: row.conversion_error,
            tags: row.tags,
            public: row.public,
            bom_version: row.bom_version
        };
    } catch (error) {
        logger.error({ err: error }, 'Error creating SPDX BOM');
        throw error;
    }
}

export async function findSpdxBomById(uuid: string, org?: string): Promise<SpdxBomRecord | null> {
    let query = 'SELECT * FROM rebom.spdx_boms WHERE uuid = $1';
    const values: string[] = [uuid];
    
    if (org) {
        query += ' AND organization = $2';
        values.push(org);
    }

    try {
        const result = await runQuery(query, values);
        if (result.rows.length === 0) {
            return null;
        }
        
        const row = result.rows[0];
        return {
            uuid: row.uuid,
            created_date: row.created_date,
            last_updated_date: row.last_updated_date,
            spdx_metadata: row.spdx_metadata,
            oci_response: row.oci_response,
            converted_bom_uuid: row.converted_bom_uuid,
            organization: row.organization,
            file_sha256: row.file_sha256,
            conversion_status: row.conversion_status,
            conversion_error: row.conversion_error,
            tags: row.tags,
            public: row.public,
            bom_version: row.bom_version
        };
    } catch (error) {
        logger.error({ err: error }, 'Error finding SPDX BOM by ID');
        throw error;
    }
}

export async function findSpdxBomBySpdxId(spdxId: string, org?: string): Promise<SpdxBomRecord | null> {
    let query = `SELECT * FROM rebom.spdx_boms WHERE spdx_metadata->>'SPDXID' = $1`;
    const values: string[] = [spdxId];
    
    if (org) {
        query += ' AND organization = $2';
        values.push(org);
    }

    try {
        const result = await runQuery(query, values);
        if (result.rows.length === 0) {
            return null;
        }
        
        const row = result.rows[0];
        return {
            uuid: row.uuid,
            created_date: row.created_date,
            last_updated_date: row.last_updated_date,
            spdx_metadata: row.spdx_metadata,
            oci_response: row.oci_response,
            converted_bom_uuid: row.converted_bom_uuid,
            organization: row.organization,
            file_sha256: row.file_sha256,
            conversion_status: row.conversion_status,
            conversion_error: row.conversion_error,
            tags: row.tags,
            public: row.public,
            bom_version: row.bom_version
        };
    } catch (error) {
        logger.error({ err: error }, 'Error finding SPDX BOM by SPDX ID');
        throw error;
    }
}

export async function updateSpdxBomConversionStatus(
    uuid: string, 
    status: 'pending' | 'success' | 'failed', 
    error?: string
): Promise<void> {
    const query = `
        UPDATE rebom.spdx_boms 
        SET conversion_status = $1, conversion_error = $2, last_updated_date = now()
        WHERE uuid = $3
    `;
    
    const values = [status, error || '', uuid];

    try {
        await runQuery(query, values);
    } catch (error) {
        logger.error({ err: error }, 'Error updating SPDX BOM conversion status');
        throw error;
    }
}

export async function linkConvertedBom(spdxUuid: string, bomUuid: string): Promise<void> {
    const query = `
        UPDATE rebom.spdx_boms 
        SET converted_bom_uuid = $1, last_updated_date = now()
        WHERE uuid = $2
    `;
    
    const values = [bomUuid, spdxUuid];

    try {
        await runQuery(query, values);
    } catch (error) {
        logger.error({ err: error }, 'Error linking converted BOM');
        throw error;
    }
}

export async function updateSpdxBomOciResponse(uuid: string, ociResponse: OASResponse): Promise<void> {
    const query = `
        UPDATE rebom.spdx_boms 
        SET oci_response = $1, last_updated_date = now()
        WHERE uuid = $2
    `;
    
    const values = [JSON.stringify(ociResponse), uuid];

    try {
        await runQuery(query, values);
    } catch (error) {
        logger.error({ err: error }, 'Error updating SPDX BOM OCI response');
        throw error;
    }
}

/**
 * Check if documentNamespace already exists for this organization.
 * Used to validate uniqueness of new SPDX uploads.
 */
export async function findSpdxBomByNamespace(
    documentNamespace: string, 
    org: string
): Promise<SpdxBomRecord | null> {
    const query = `
        SELECT * FROM rebom.spdx_boms 
        WHERE spdx_metadata->>'documentNamespace' = $1 AND organization = $2
    `;
    
    try {
        const result = await runQuery(query, [documentNamespace, org]);
        if (result.rows.length === 0) {
            return null;
        }
        
        const row = result.rows[0];
        return {
            uuid: row.uuid,
            created_date: row.created_date,
            last_updated_date: row.last_updated_date,
            spdx_metadata: row.spdx_metadata,
            oci_response: row.oci_response,
            converted_bom_uuid: row.converted_bom_uuid,
            organization: row.organization,
            file_sha256: row.file_sha256,
            conversion_status: row.conversion_status,
            conversion_error: row.conversion_error,
            tags: row.tags,
            public: row.public,
            bom_version: row.bom_version
        };
    } catch (error) {
        logger.error({ err: error }, 'Error finding SPDX BOM by namespace');
        throw error;
    }
}

/**
 * Get the latest bom_version for an SPDX document by its converted BOM UUID.
 * Used when updating an existing SPDX artifact to determine next version.
 */
export async function getSpdxBomVersionByConvertedUuid(
    convertedBomUuid: string
): Promise<number> {
    const query = `
        SELECT bom_version FROM rebom.spdx_boms 
        WHERE converted_bom_uuid = $1
        ORDER BY bom_version DESC
        LIMIT 1
    `;
    
    try {
        const result = await runQuery(query, [convertedBomUuid]);
        return result.rows[0]?.bom_version || 0;
    } catch (error) {
        logger.error({ err: error }, 'Error getting SPDX BOM version by converted UUID');
        throw error;
    }
}

/**
 * Find SPDX BOM by its converted CycloneDX BOM UUID.
 * Used to get existing SPDX info when updating an artifact.
 */
export async function findSpdxBomByConvertedUuid(
    convertedBomUuid: string,
    org?: string
): Promise<SpdxBomRecord | null> {
    let query = 'SELECT * FROM rebom.spdx_boms WHERE converted_bom_uuid = $1';
    const values: string[] = [convertedBomUuid];
    
    if (org) {
        query += ' AND organization = $2';
        values.push(org);
    }
    
    // Get the latest version if multiple exist
    query += ' ORDER BY bom_version DESC LIMIT 1';

    try {
        const result = await runQuery(query, values);
        if (result.rows.length === 0) {
            return null;
        }
        
        const row = result.rows[0];
        return {
            uuid: row.uuid,
            created_date: row.created_date,
            last_updated_date: row.last_updated_date,
            spdx_metadata: row.spdx_metadata,
            oci_response: row.oci_response,
            converted_bom_uuid: row.converted_bom_uuid,
            organization: row.organization,
            file_sha256: row.file_sha256,
            conversion_status: row.conversion_status,
            conversion_error: row.conversion_error,
            tags: row.tags,
            public: row.public,
            bom_version: row.bom_version
        };
    } catch (error) {
        logger.error({ err: error }, 'Error finding SPDX BOM by converted UUID');
        throw error;
    }
}

/**
 * Find SPDX BOM by the serialNumber of its converted CycloneDX BOM.
 * This is used when updating an SPDX artifact - the internalBom.id from rearm-saas
 * is the serialNumber of the converted CycloneDX BOM, not the converted_bom_uuid.
 */
export async function findSpdxBomBySerialNumber(
    serialNumber: string,
    org: string
): Promise<SpdxBomRecord | null> {
    // First, find the bom record by serialNumber (with or without urn:uuid: prefix)
    const serialWithUrn = serialNumber.startsWith('urn:uuid:') ? serialNumber : `urn:uuid:${serialNumber}`;
    const serialWithoutUrn = serialNumber.replace('urn:uuid:', '');
    
    // Look up in boms table to find the source_spdx_uuid
    const bomQuery = `
        SELECT source_spdx_uuid FROM rebom.boms 
        WHERE (meta->>'serialNumber' = $1 OR meta->>'serialNumber' = $2)
        AND organization = $3
        AND source_format = 'SPDX'
        ORDER BY created_date DESC LIMIT 1
    `;
    
    try {
        const bomResult = await runQuery(bomQuery, [serialWithUrn, serialWithoutUrn, org]);
        if (bomResult.rows.length === 0) {
            logger.debug({ serialNumber, org }, 'No bom found with this serialNumber');
            return null;
        }
        
        const sourceSpdxUuid = bomResult.rows[0].source_spdx_uuid;
        if (!sourceSpdxUuid) {
            logger.debug({ serialNumber }, 'Bom found but no source_spdx_uuid');
            return null;
        }
        
        // Now find the SPDX record
        return await findSpdxBomById(sourceSpdxUuid, org);
    } catch (error) {
        logger.error({ err: error, serialNumber }, 'Error finding SPDX BOM by serial number');
        throw error;
    }
}
