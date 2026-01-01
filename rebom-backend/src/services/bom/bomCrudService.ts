import * as BomRepository from '../../bomRepository';
import { BomDto, BomMetaDto, BomRecord, BomSearch, SearchObject } from '../../types';
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
        throw new Error(`BOM not found: ${id}`);
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
    const versionedBom = allBoms.filter((b: BomRecord) => {
        const bomVersionNum = typeof b.meta.bomVersion === 'string' 
            ? parseInt(b.meta.bomVersion) 
            : b.meta.bomVersion;
        return bomVersionNum === version;
    });
    
    if (!versionedBom || versionedBom.length === 0) {
        logger.warn({ serialNumber, version, org }, "No BOM found for version");
        throw new Error(`BOM not found: ${serialNumber} version ${version}`);
    }
    
    // Fetch the actual BOM content from OCI storage using the UUID
    const bomContent = await fetchFromOci(versionedBom[0].uuid);
    return bomContent;
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
    const { fetchFromOci } = require('../../ociService');
    const SpdxRepository = require('../../spdxRepository');
    
    logger.debug({ id, org, format }, "findRawBomObjectById called");
    
    const bomResults = await BomRepository.bomBySerialNumber(id, org);
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
        throw new Error(`BOM not found with id: ${id}`);
    }

    if (format === 'CYCLONEDX') {
        // Check if raw BOM UUID is stored in metadata (new approach)
        if (bomMeta?.rawBomUuid) {
            logger.debug({ rawBomUuid: bomMeta.rawBomUuid }, "Fetching truly raw CycloneDX BOM");
            return await fetchFromOci(bomMeta.rawBomUuid);
        }
        // Fallback to processed BOM for backward compatibility
        logger.debug({ uuid: bomById.uuid }, "Fetching processed CycloneDX BOM (no raw UUID found)");
        return await fetchFromOci(bomById.uuid);
    } 
    else if (format === 'SPDX') {
        if (bomById.source_format === 'SPDX' && bomById.source_spdx_uuid) {
            const spdxBom = await SpdxRepository.findSpdxBomById(bomById.source_spdx_uuid, org);
            if (spdxBom?.oci_response) {
                return await fetchFromOci(spdxBom.oci_response.ociResponse?.digest || spdxBom.uuid);
            }
        }
        throw new Error(`No SPDX source found for BOM: ${id}`);
    } 
    else {
        if (bomById.source_format === 'SPDX' && bomById.source_spdx_uuid) {
            logger.debug({ sourceSpdxUuid: bomById.source_spdx_uuid }, "Fetching SPDX BOM record");
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
        logger.debug("Falling back to CycloneDX BOM");
        return await fetchFromOci(bomById.uuid);
    }
}
