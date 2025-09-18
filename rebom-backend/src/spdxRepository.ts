import { logger } from './logger';
import { SpdxBomRecord, SpdxMetadata } from './types';
import { OASResponse } from './ociService';
import { runQuery } from './utils';

export async function createSpdxBom(spdxRecord: Partial<SpdxBomRecord>): Promise<SpdxBomRecord> {
    const query = `
        INSERT INTO rebom.spdx_boms (
            uuid, spdx_metadata, oci_response, organization, file_sha256, 
            conversion_status, tags, public
        ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
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
        spdxRecord.public || false
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
            public: row.public
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
            public: row.public
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
            public: row.public
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
