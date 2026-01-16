import * as BomRepository from '../../bomRepository';
import { BomDto, BomMetaDto, BomRecord, BomSearch, SearchObject } from '../../types';
import { BomNotFoundError, BomDataIntegrityError } from '../../types/errors';
import { BomMapper } from './bomMapper';
import { logger } from '../../logger';
import { fetchFromOci } from '../oci';

export async function findAllBoms(): Promise<BomDto[]> {
    const bomRecords = await BomRepository.findAllBoms();
    return BomMapper.toDtoArray(bomRecords);
}

export async function findBomObjectById(id: string, org: string): Promise<Object> {
    logger.debug({ id, org }, "findBomObjectById called");
    
    // Try to find by UUID first
    let bomResults = await BomRepository.bomById(id);
    
    // If not found by UUID, try by serialNumber (for artifacts that store serialNumber as internalBom.id)
    if (!bomResults || bomResults.length === 0) {
        logger.debug({ id, org }, "BOM not found by UUID, trying serialNumber lookup");
        bomResults = await BomRepository.bomBySerialNumber(id, org);
    }
    
    if (!bomResults || bomResults.length === 0) {
        logger.warn({ id, org }, "No BOM found by UUID or serialNumber");
        throw new BomNotFoundError(
            `BOM not found: ${id}`,
            id,
            { searchType: 'uuid_or_serialNumber', org }
        );
    }
    
    // Validate that only one BOM was found (data integrity check)
    if (bomResults.length > 1) {
        logger.error({ id, org, count: bomResults.length }, "Multiple BOMs found for single ID - data integrity issue");
        throw new BomDataIntegrityError(
            `Multiple BOMs found for single identifier`,
            id,
            bomResults.length,
            { org, searchType: 'uuid_or_serialNumber' }
        );
    }
    
    const bomRecord = bomResults[0];
    
    // Fetch the actual BOM content from OCI storage using the BOM's UUID
    const bomContent = await fetchFromOci(bomRecord.uuid);
    return bomContent;
}

export async function findBomMetasBySerialNumber(serialNumber: string, org: string): Promise<BomMetaDto[]> {
    const bomsBySerialNumber = await BomRepository.bomBySerialNumber(serialNumber, org);
    return BomMapper.toMetaDtoArray(bomsBySerialNumber);
}

export async function findBomBySerialNumberAndVersion(serialNumber: string, version: number, org: string, raw?: boolean): Promise<Object> {
    const allBoms = await BomRepository.allBomsBySerialNumber(serialNumber, org);
    
    logger.debug({ 
        serialNumber, 
        version, 
        org, 
        totalBoms: allBoms.length,
        bomVersions: allBoms.map(b => ({ uuid: b.uuid, bomVersion: b.meta?.bomVersion }))
    }, "Finding BOM by serial number and version");
    
    const versionedBom = allBoms.filter((b: BomRecord) => {
        const bomVersionNum = typeof b.meta.bomVersion === 'string' 
            ? parseInt(b.meta.bomVersion) 
            : b.meta.bomVersion;
        return bomVersionNum === version;
    });
    
    if (!versionedBom || versionedBom.length === 0) {
        // Backward compatibility: If requesting version 1 and only one BOM exists without proper versioning,
        // assume it's the first/only version
        if (version === 1 && allBoms.length === 1) {
            logger.info({ 
                serialNumber, 
                version, 
                bomUuid: allBoms[0].uuid,
                actualBomVersion: allBoms[0].meta?.bomVersion 
            }, "Legacy BOM without version tracking - treating as version 1");
            const bomContent = await fetchFromOci(allBoms[0].uuid);
            return bomContent;
        }
        
        logger.warn({ 
            serialNumber, 
            version, 
            org, 
            availableVersions: allBoms.map(b => b.meta?.bomVersion) 
        }, "No BOM found for version");
        throw new BomNotFoundError(
            `BOM not found: ${serialNumber} version ${version}`,
            serialNumber,
            { version, org, searchType: 'serialNumber_and_version', availableVersions: allBoms.map(b => b.meta?.bomVersion) }
        );
    }
    
    // Validate that only one BOM was found for this version (data integrity check)
    if (versionedBom.length > 1) {
        logger.error({ serialNumber, version, org, count: versionedBom.length }, "Multiple BOMs found for same version - data integrity issue");
        throw new BomDataIntegrityError(
            `Multiple BOMs found for same version`,
            serialNumber,
            versionedBom.length,
            { org, version, searchType: 'serialNumber_and_version' }
        );
    }
    
    const bomRecord = versionedBom[0];
    
    // Fetch the actual BOM content from OCI storage
    if (raw) {
        // Raw BOM requested - check if this is SPDX-sourced or native CycloneDX
        if (bomRecord.source_spdx_uuid) {
            // SPDX-sourced - fetch original SPDX
            const SpdxRepository = require('../../spdxRepository');
            const spdxBom = await SpdxRepository.findSpdxBomById(bomRecord.source_spdx_uuid, org);
            if (spdxBom?.oci_response) {
                const fetchId = spdxBom.oci_response.ociResponse?.digest || spdxBom.uuid;
                logger.debug({ fetchId, version, serialNumber }, "Fetching raw SPDX BOM by version");
                return await fetchFromOci(fetchId);
            }
        }
        // Native CycloneDX - fetch raw BOM
        const rawBomUuid = bomRecord.uuid + '-raw';
        logger.debug({ rawBomUuid, version, serialNumber }, "Fetching raw CycloneDX BOM by version");
        try {
            return await fetchFromOci(rawBomUuid);
        } catch (error) {
            // Fallback for older BOMs without -raw suffix
            logger.warn({ rawBomUuid, bomUuid: bomRecord.uuid, error: error instanceof Error ? error.message : String(error) }, 
                "Failed to fetch raw BOM with -raw suffix, retrying without suffix for legacy BOM");
            return await fetchFromOci(bomRecord.uuid);
        }
    } else {
        // Augmented/processed BOM requested
        logger.debug({ uuid: bomRecord.uuid, version, serialNumber }, "Fetching augmented BOM by version");
        return await fetchFromOci(bomRecord.uuid);
    }
}

export async function findBomsByIds(ids: string[]): Promise<BomDto[]> {
    const bomRecords = await BomRepository.bomsByIds(ids);
    return BomMapper.toDtoArray(bomRecords);
}

export async function findBomByOrgAndDigest(digest: string, org: string): Promise<BomDto[]> {
    const bomRecords = await BomRepository.bomByOrgAndDigest(digest, org);
    return BomMapper.toDtoArray(bomRecords);
}

export async function findRawBomObjectById(id: string, org: string, format?: string): Promise<Object> {
    const { fetchFromOci } = require('../oci/ociService');
    const SpdxRepository = require('../../spdxRepository');
    
    logger.debug({ id, org, format }, "findRawBomObjectById called");
    
    const bomResults = await BomRepository.bomBySerialNumber(id, org);
    
    // Validate that only one BOM was found (data integrity check)
    if (bomResults && bomResults.length > 1) {
        logger.error({ id, org, count: bomResults.length }, "Multiple BOMs found for single serial number - data integrity issue");
        throw new BomDataIntegrityError(
            `Multiple BOMs found for single serial number`,
            id,
            bomResults.length,
            { org, searchType: 'serialNumber', context: 'raw_bom_lookup' }
        );
    }
    
    const bomById = bomResults[0];
    
    const bomMeta = bomById?.meta as any;
    
    logger.debug({ 
        found: !!bomById, 
        bomUuid: bomById?.uuid,
        sourceFormat: bomById?.source_format,
        sourceSpdxUuid: bomById?.source_spdx_uuid,
        rawBomUuid: bomMeta?.rawBomUuid
    }, "BOM record lookup result");
    
    if (!bomById) {
        throw new BomNotFoundError(
            `BOM not found with id: ${id}`,
            id,
            { searchType: 'serialNumber', org, context: 'raw_bom_lookup' }
        );
    }

    if (format === 'CYCLONEDX') {
        // Check if this is an SPDX-sourced BOM
        if (bomById.source_spdx_uuid) {
            // For SPDX-sourced BOMs, return the converted CycloneDX (not raw SPDX)
            // We don't store a raw CycloneDX for SPDX sources
            logger.debug({ 
                bomUuid: bomById.uuid, 
                sourceSpdxUuid: bomById.source_spdx_uuid 
            }, "Fetching converted CycloneDX from SPDX source");
            return await fetchFromOci(bomById.uuid);
        }
        
        // Native CycloneDX - fetch raw BOM
        const rawBomUuid = bomById.uuid + '-raw';
        logger.debug({ rawBomUuid, bomUuid: bomById.uuid }, "Fetching raw CycloneDX BOM");
        try {
            return await fetchFromOci(rawBomUuid);
        } catch (error) {
            // Fallback for older BOMs without -raw suffix
            logger.warn({ rawBomUuid, bomUuid: bomById.uuid, error: error instanceof Error ? error.message : String(error) }, 
                "Failed to fetch raw BOM with -raw suffix, retrying without suffix for legacy BOM");
            return await fetchFromOci(bomById.uuid);
        }
    } 
    else if (format === 'SPDX') {
        if (bomById.source_format === 'SPDX' && bomById.source_spdx_uuid) {
            const spdxBom = await SpdxRepository.findSpdxBomById(bomById.source_spdx_uuid, org);
            if (spdxBom?.oci_response) {
                return await fetchFromOci(spdxBom.oci_response.ociResponse?.digest || spdxBom.uuid);
            }
        }
        throw new BomNotFoundError(
            `No SPDX source found for BOM: ${id}`,
            id,
            { format: 'SPDX', org, searchType: 'spdx_source' }
        );
    } 
    else {
        // No format specified - check if this is an SPDX-sourced BOM first
        if (bomById.source_spdx_uuid) {
            logger.debug({ sourceSpdxUuid: bomById.source_spdx_uuid, sourceFormat: bomById.source_format }, 
                "BOM has SPDX source - fetching original SPDX");
            const spdxBom = await SpdxRepository.findSpdxBomById(bomById.source_spdx_uuid, org);
            logger.debug({ 
                spdxBomFound: !!spdxBom,
                spdxBomUuid: spdxBom?.uuid,
                hasOciResponse: !!spdxBom?.oci_response,
                ociDigest: spdxBom?.oci_response?.ociResponse?.digest
            }, "SPDX BOM record lookup result");
            
            if (spdxBom?.oci_response) {
                const fetchId = spdxBom.oci_response.ociResponse?.digest || spdxBom.uuid;
                logger.debug({ fetchId }, "Fetching SPDX content from OCI");
                return await fetchFromOci(fetchId);
            }
        }
        
        // Not SPDX-sourced - return raw CycloneDX BOM
        if (bomById.source_format === 'CYCLONEDX' || !bomById.source_spdx_uuid) {
            const rawBomUuid = bomById.uuid + '-raw';
            logger.debug({ rawBomUuid, bomUuid: bomById.uuid, sourceFormat: bomById.source_format }, 
                "Fetching raw CycloneDX BOM (no format specified)");
            try {
                return await fetchFromOci(rawBomUuid);
            } catch (error) {
                // Fallback for older BOMs without -raw suffix
                logger.warn({ rawBomUuid, bomUuid: bomById.uuid, error: error instanceof Error ? error.message : String(error) }, 
                    "Failed to fetch raw BOM with -raw suffix, retrying without suffix for legacy BOM");
                return await fetchFromOci(bomById.uuid);
            }
        }
        
        // Fallback to primary UUID (processed/augmented BOM)
        logger.debug({ uuid: bomById.uuid }, "Falling back to primary BOM UUID");
        return await fetchFromOci(bomById.uuid);
    }
}
