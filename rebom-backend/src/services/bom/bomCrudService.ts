import * as BomRepository from '../../bomRepository';
import { BomDto, BomMetaDto, BomRecord, BomSearch, SearchObject } from '../../types';
import { BomNotFoundError, BomDataIntegrityError } from '../../types/errors';
import { BomMapper } from './bomMapper';
import { logger } from '../../logger';
import { fetchFromOci, extractRepositoryNameFromBom, extractRepositoryNameFromSpdxOciResponse, fetchRawBomWithFallback } from '../oci';
import { extractBomComponents, ParsedBomComponent } from './bomComponentExtractor';

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
    
    // Extract repository name from bom field (OASResponse)
    const storedRepositoryName = extractRepositoryNameFromBom(bomRecord);
    
    // Fetch the actual BOM content from OCI storage using the BOM's UUID
    // Use stored repository name, or fall back to default 'rebom-artifacts' for legacy records
    // Validate using processedFileDigest (augmented BOM digest)
    const expectedDigest = bomRecord.meta?.processedFileDigest;
    const bomContent = await fetchFromOci(bomRecord.uuid, storedRepositoryName, expectedDigest);
    return bomContent;
}

/**
 * Return parsed SBOM components for a BOM identified by its UUID or serialNumber.
 * The BOM is fetched from OCI (same path as `findBomObjectById`) and run through
 * the component extractor. Returns an empty array if the BOM has no `components`
 * array; throws BomNotFoundError if the identifier does not resolve.
 */
export async function findBomComponentsById(id: string, org: string): Promise<ParsedBomComponent[]> {
    const bom: any = await findBomObjectById(id, org);
    if (!bom || !Array.isArray(bom.components)) {
        return [];
    }
    return extractBomComponents(bom);
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
        throw new BomNotFoundError(
            `No BOM found with serial number ${serialNumber} and version ${version}`,
            serialNumber,
            { searchType: 'serialNumber_and_version', org, version, availableVersions: allBoms.map(b => b.meta?.bomVersion) }
        );
    }
    
    const bomRecord = versionedBom[0];
    
    logger.debug({ uuid: bomRecord.uuid, version, serialNumber }, "Found BOM record");
    
    // Extract repository name from bom field (OASResponse)
    const storedRepositoryName = extractRepositoryNameFromBom(bomRecord);
    
    // Fetch the actual BOM content from OCI storage
    if (raw) {
        // Raw BOM requested - check if this is SPDX-sourced or native CycloneDX
        if (bomRecord.source_spdx_uuid) {
            // SPDX-sourced - fetch original SPDX
            const SpdxRepository = require('../../spdxRepository');
            const spdxBom = await SpdxRepository.findSpdxBomById(bomRecord.source_spdx_uuid, org);
            if (spdxBom?.oci_response) {
                const fetchId = spdxBom.oci_response.ociResponse?.digest || spdxBom.uuid;
                // Use SPDX BOM's repository name from oci_response, not the CycloneDX BOM's
                const spdxRepositoryName = extractRepositoryNameFromSpdxOciResponse(spdxBom.oci_response);
                logger.debug({ fetchId, version, serialNumber, spdxRepositoryName }, "Fetching raw SPDX BOM by version");
                // Use file_sha256 field as authoritative source for SPDX digest validation
                const spdxDigest = spdxBom.file_sha256;
                return await fetchFromOci(fetchId, spdxRepositoryName, spdxDigest);
            }
        }
        // Native CycloneDX - fetch raw BOM with automatic fallback for legacy BOMs
        logger.debug({ bomUuid: bomRecord.uuid, version, serialNumber }, "Fetching raw CycloneDX BOM by version");
        const expectedDigest = bomRecord.meta?.originalFileDigest;
        return await fetchRawBomWithFallback(bomRecord.uuid, storedRepositoryName, fetchFromOci, expectedDigest);
    } else {
        // Augmented/processed BOM requested - validate using processedFileDigest
        logger.debug({ uuid: bomRecord.uuid, version, serialNumber }, "Fetching augmented BOM by version");
        const expectedDigest = bomRecord.meta?.processedFileDigest;
        return await fetchFromOci(bomRecord.uuid, storedRepositoryName, expectedDigest);
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

    // Extract repository name from bom field (OASResponse)
    const storedRepositoryName = extractRepositoryNameFromBom(bomById);

    if (format === 'CYCLONEDX') {
        // Check if this is an SPDX-sourced BOM
        if (bomById.source_spdx_uuid) {
            // For SPDX-sourced BOMs, return the converted CycloneDX (not raw SPDX)
            // We don't store a raw CycloneDX for SPDX sources
            logger.debug({ 
                bomUuid: bomById.uuid, 
                sourceSpdxUuid: bomById.source_spdx_uuid 
            }, "Fetching converted CycloneDX from SPDX source");
            // Converted BOMs are processed versions - validate using processedFileDigest
            const expectedDigest = bomById.meta?.processedFileDigest;
            return await fetchFromOci(bomById.uuid, storedRepositoryName, expectedDigest);
        }
        
        // Native CycloneDX - fetch raw BOM with automatic fallback for legacy BOMs
        logger.debug({ bomUuid: bomById.uuid }, "Fetching raw CycloneDX BOM");
        const expectedDigest = bomById.meta?.originalFileDigest;
        return await fetchRawBomWithFallback(bomById.uuid, storedRepositoryName, fetchFromOci, expectedDigest);
    } 
    else if (format === 'SPDX') {
        if (bomById.source_format === 'SPDX' && bomById.source_spdx_uuid) {
            const spdxBom = await SpdxRepository.findSpdxBomById(bomById.source_spdx_uuid, org);
            if (spdxBom?.oci_response) {
                // Use SPDX BOM's repository name from oci_response
                const spdxRepositoryName = extractRepositoryNameFromSpdxOciResponse(spdxBom.oci_response);
                // Use file_sha256 field as authoritative source for SPDX digest validation
                const spdxDigest = spdxBom.file_sha256;
                return await fetchFromOci(spdxBom.oci_response.ociResponse?.digest || spdxBom.uuid, spdxRepositoryName, spdxDigest);
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
                // Use SPDX BOM's repository name from oci_response
                const spdxRepositoryName = extractRepositoryNameFromSpdxOciResponse(spdxBom.oci_response);
                logger.debug({ fetchId, spdxRepositoryName }, "Fetching SPDX content from OCI");
                // Use file_sha256 field as authoritative source for SPDX digest validation
                const spdxDigest = spdxBom.file_sha256;
                return await fetchFromOci(fetchId, spdxRepositoryName, spdxDigest);
            }
        }
        
        // Not SPDX-sourced - return raw CycloneDX BOM with automatic fallback for legacy BOMs
        if (bomById.source_format === 'CYCLONEDX' || !bomById.source_spdx_uuid) {
            logger.debug({ bomUuid: bomById.uuid, sourceFormat: bomById.source_format }, 
                "Fetching raw CycloneDX BOM (no format specified)");
            const expectedDigest = bomById.meta?.originalFileDigest;
            return await fetchRawBomWithFallback(bomById.uuid, storedRepositoryName, fetchFromOci, expectedDigest);
        }
        
        // Fallback to primary UUID (processed/augmented BOM) - validate using processedFileDigest
        logger.debug({ uuid: bomById.uuid }, "Falling back to primary BOM UUID");
        const expectedDigest = bomById.meta?.processedFileDigest;
        return await fetchFromOci(bomById.uuid, storedRepositoryName, expectedDigest);
    }
}
