import * as BomRepository from '../../bomRepository';
import { BomDto, BomMetaDto, BomRecord, BomSearch, SearchObject } from '../../types';
import { BomMapper } from './bomMapper';
import { logger } from '../../logger';

export async function findAllBoms(): Promise<BomDto[]> {
    const bomRecords = await BomRepository.findAllBoms();
    return BomMapper.toDtoArray(bomRecords);
}

export async function findBomObjectById(id: string, org: string): Promise<Object> {
    logger.debug({ id, org }, "findBomObjectById called");
    
    const bomResults = await BomRepository.bomBySerialNumber(id, org);
    
    if (!bomResults || bomResults.length === 0) {
        logger.warn({ id, org }, "No BOM found");
        throw new Error(`BOM not found: ${id}`);
    }
    
    const bomDto = BomMapper.toDto(bomResults[0]);
    return bomDto.bom;
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
    
    const bomDto = BomMapper.toDto(versionedBom[0]);
    return bomDto.bom;
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
    
    logger.debug({ 
        found: !!bomById, 
        bomUuid: bomById?.uuid,
        sourceFormat: bomById?.source_format,
        sourceSpdxUuid: bomById?.source_spdx_uuid 
    }, "BOM record lookup result");
    
    if (!bomById) {
        throw new Error(`BOM not found with id: ${id}`);
    }

    if (format === 'CYCLONEDX') {
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
