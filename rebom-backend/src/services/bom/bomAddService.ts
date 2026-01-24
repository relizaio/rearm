import { logger } from '../../logger';
import { BomInput, BomRecord, BomFormat, RebomOptions, BomSearch, BomDto } from '../../types';
import { BomValidationError, BomStorageError, BomConversionError, OciStorageError, BomNotFoundError } from '../../types/errors';
import * as BomRepository from '../../bomRepository';
import * as SpdxRepository from '../../spdxRepository';
import { SpdxService } from '../spdx';
import { pushToOci } from '../oci';
import { computeBomDigest, augmentBomForStorage, enrichCycloneDxBom } from './bomProcessingService';
import validateBom from '../../validateBom';
import { v4 as uuidv4 } from 'uuid';
import { runQuery } from '../../utils';

/**
 * Configuration flag: When true, BOMs are augmented with component context before storage.
 * When false, BOMs are stored processed but not augmented (augmentation happens on-demand).
 * 
 * Set to true to match SPDX and merged BOM behavior.
 * Set to false to revert to on-demand augmentation.
 */
const AUGMENT_ON_STORAGE = true;

export async function addBom(bomInput: BomInput): Promise<BomRecord> {
  const format: BomFormat = (bomInput.bomInput as any).format || 'CYCLONEDX';
  if (format === 'SPDX') {
    return await addSpdxBom(bomInput);
  } else {
    return await addCycloneDxBom(bomInput);
  }
}

/**
 * Find the latest BOM version by serial number.
 * Returns the BOM with the highest version number for the given serialNumber.
 * Used for version comparison and deduplication checks.
 */
async function findLatestBomBySerialNumber(serialNumber: string, org: string): Promise<BomRecord | null> {
  const existingBoms = await BomRepository.allBomsBySerialNumber(serialNumber, org);
  
  if (existingBoms.length === 0) {
    return null;
  }
  
  // allBomsBySerialNumber returns BOMs ordered by bomVersion DESC
  // So the first one is the latest version
  const latestBom = existingBoms[0];
  
  logger.debug({ 
    serialNumber, 
    totalVersions: existingBoms.length,
    latestVersion: latestBom.meta?.bomVersion,
    latestUuid: latestBom.uuid
  }, "Found existing BOM versions");
  
  return latestBom;
}

/**
 * Find BOM by exact raw file digest across all versions.
 * Used for deduplication - checks if this exact file was already uploaded.
 */
async function findBomByRawDigest(serialNumber: string, rawDigest: string, org: string): Promise<BomRecord | null> {
  const existingBoms = await BomRepository.allBomsBySerialNumber(serialNumber, org);
  
  // Check all versions for matching raw file digest
  const matchingBom = existingBoms.find(bom => bom.meta?.originalFileDigest === rawDigest);
  
  if (matchingBom) {
    logger.debug({ 
      serialNumber,
      rawDigest,
      matchingUuid: matchingBom.uuid,
      matchingVersion: matchingBom.meta?.bomVersion
    }, "Found BOM with matching raw file digest");
  }
  
  return matchingBom || null;
}

/**
 * Adds a CycloneDX BOM to the system.
 * 
 * Flow:
 * 1. Process and validate BOM (sanitize, deduplicate, validate)
 * 2. Auto-detect existing BOM by serialNumber from BOM content
 * 3. If exists and identical content → return existing (deduplication)
 * 4. If exists and different content → UPDATE existing record
 * 5. If not exists → INSERT new record
 * 
 * @param bomInput - BOM input containing the raw BOM and metadata
 * @returns BOM record (existing or newly created)
 */
async function addCycloneDxBom(bomInput: BomInput): Promise<BomRecord> {
  // Step 1: Process and validate BOM
  const rawBom = bomInput.bomInput.bom;
  const processedBom = await processBomObj(rawBom);

  const isValid = await validateBom(processedBom);
  if (!isValid) {
    throw new BomValidationError('BOM validation failed', {
      field: 'bom',
      constraint: 'must pass schema validation'
    });
  }

  // Step 2: Prepare metadata
  const rebomOptions: RebomOptions = bomInput.bomInput.rebomOptions ?? {};
  rebomOptions.serialNumber = processedBom.serialNumber;
  rebomOptions.bomVersion = processedBom.version; // Use version from CycloneDX (set by rearm-saas)
  rebomOptions.mod = 'raw';

  // Step 3: Enrich BOM (if BEAR env vars are set) and optionally augment with component context
  let finalBom = processedBom;
  if (AUGMENT_ON_STORAGE) {
    logger.debug({ serialNumber: rebomOptions.serialNumber }, "Enriching BOM on BEAR");
    const enrichedBom = await enrichCycloneDxBom(processedBom);
    logger.debug({ serialNumber: rebomOptions.serialNumber }, "Augmenting BOM with component context before storage");
    finalBom = augmentBomForStorage(enrichedBom, rebomOptions, new Date());
  }
  
  // Compute digest on the final BOM (augmented or processed, depending on config)
  rebomOptions.bomDigest = computeBomDigest(finalBom);

  const newUuid = uuidv4();
  const rawUuid = newUuid + '-raw';

  // Step 4: Store artifacts in OCI
  const rawOasResponse = await pushToOci(rawUuid, rawBom);  // Raw BOM (original, untouched)
  const oasResponse = await pushToOci(newUuid, finalBom);  // Processed (and optionally augmented) BOM
  
  // Track raw BOM metadata for rearm-saas (use actual file digest from OCI)
  // Note: rawBomUuid is always `uuid + '-raw'` so rearm-saas can reconstruct it
  rebomOptions.originalFileDigest = rawOasResponse.fileSHA256Digest;  // Actual file digest from OCI
  rebomOptions.originalFileSize = rawOasResponse.originalSize;
  rebomOptions.originalMediaType = rawOasResponse.originalMediaType;

  // Step 5: Check for deduplication and determine INSERT vs UPDATE
  const serialNumber = rebomOptions.serialNumber;
  const newRawDigest = rebomOptions.originalFileDigest;
  
  // First check if this exact file was already uploaded (any version)
  if (newRawDigest) {
    const duplicateBom = await findBomByRawDigest(serialNumber, newRawDigest, bomInput.bomInput.org);
    if (duplicateBom) {
      logger.info({ 
        serialNumber, 
        rawFileDigest: newRawDigest,
        existingUuid: duplicateBom.uuid,
        existingVersion: duplicateBom.meta?.bomVersion
      }, "Duplicate CycloneDX BOM detected (identical raw file) - returning existing record");
      
      return duplicateBom;
    }
  }
  
  // Not a duplicate - check for latest version to compare
  const latestBom = await findLatestBomBySerialNumber(serialNumber, bomInput.bomInput.org);
  
  let queryText: string;
  let queryParams: any[];
  
  if (latestBom) {
    // Different raw file - determine if this is a version increment or replacement
    const latestVersion = parseInt(latestBom.meta?.bomVersion) || 0;
    const newVersion = parseInt(rebomOptions.bomVersion) || 0;
    
    if (newVersion > latestVersion) {
      // Version increment - INSERT new version
      logger.info({ 
        serialNumber,
        bomVersion: rebomOptions.bomVersion,
        uuid: newUuid,
        latestVersion: latestBom.meta?.bomVersion,
        latestRawDigest: latestBom.meta?.originalFileDigest,
        newRawDigest
      }, "Inserting new version of existing BOM (version increment)");
      
      queryText = 'INSERT INTO rebom.boms (uuid, meta, bom, tags, organization, source_format) VALUES ($1, $2, $3, $4, $5, $6) RETURNING *';
      queryParams = [newUuid, rebomOptions, oasResponse, bomInput.bomInput.tags, bomInput.bomInput.org, 'CYCLONEDX'];
    } else {
      // Same or lower version - REPLACE/UPDATE existing record
      logger.info({ 
        serialNumber,
        bomVersion: rebomOptions.bomVersion,
        latestUuid: latestBom.uuid,
        latestVersion: latestBom.meta?.bomVersion,
        latestRawDigest: latestBom.meta?.originalFileDigest,
        newRawDigest
      }, "Replacing existing BOM record (same or lower version - correction/replacement)");
      
      // Use existing UUID for storage to replace old files
      const existingUuid = latestBom.uuid;
      const existingRawUuid = existingUuid + '-raw';
      
      // Re-upload to existing UUIDs (overwrites old files in OCI)
      await pushToOci(existingRawUuid, rawBom);
      const replacementOasResponse = await pushToOci(existingUuid, finalBom);
      
      queryText = 'UPDATE rebom.boms SET meta = $1, bom = $2, tags = $3, last_updated_date = NOW() WHERE uuid = $4 RETURNING *';
      queryParams = [rebomOptions, replacementOasResponse, bomInput.bomInput.tags, existingUuid];
    }
  } else {
    // No existing BOM - INSERT new record
    logger.info({ 
      serialNumber,
      bomVersion: rebomOptions.bomVersion,
      uuid: newUuid
    }, "Inserting new BOM (no existing record)");
    
    queryText = 'INSERT INTO rebom.boms (uuid, meta, bom, tags, organization, source_format) VALUES ($1, $2, $3, $4, $5, $6) RETURNING *';
    queryParams = [newUuid, rebomOptions, oasResponse, bomInput.bomInput.tags, bomInput.bomInput.org, 'CYCLONEDX'];
  }
  
  // Step 6: Execute database operation
  logger.info({ 
    queryType: queryText.startsWith('INSERT') ? 'INSERT' : 'UPDATE',
    serialNumber,
    bomVersion: rebomOptions.bomVersion,
    bomDigest: rebomOptions.bomDigest,
    augmented: AUGMENT_ON_STORAGE
  }, "Executing database operation");

  const queryRes = await runQuery(queryText, queryParams);
  const bomRecord = queryRes.rows[0];
  
  if (!bomRecord) {
    throw new BomStorageError('Failed to store BOM record', undefined, {
      operation: queryText.startsWith('INSERT') ? 'INSERT' : 'UPDATE',
      bomId: queryText.startsWith('INSERT') ? newUuid : latestBom?.uuid,
      serialNumber
    });
  }
  
  logger.info({ 
    bomRecordUuid: bomRecord.uuid,
    bomVersion: rebomOptions.bomVersion,
    operation: queryText.startsWith('INSERT') ? 'INSERT' : 'UPDATE'
  }, "BOM record stored successfully");
  
  return bomRecord;
}

async function addSpdxBom(bomInput: BomInput): Promise<BomRecord> {
  try {
    const spdxContent = bomInput.bomInput.bom;
    
    if (!SpdxService.validateSpdxFormat(spdxContent)) {
      throw new BomValidationError('Invalid SPDX format', {
        field: 'bom',
        constraint: 'must be valid SPDX format'
      });
    }

    const spdxMetadata = SpdxService.extractSpdxMetadata(spdxContent);
    const fileHash = SpdxService.calculateSpdxHash(spdxContent);
    
    let bomVersion = 1;
    const existingSerialNumber = bomInput.bomInput.existingSerialNumber;
    
    if (existingSerialNumber) {
      const existingSpdx = await SpdxRepository.findSpdxBomBySerialNumber(existingSerialNumber, bomInput.bomInput.org);
      if (existingSpdx) {
        bomVersion = existingSpdx.bom_version + 1;
        logger.info({ existingSerialNumber, oldVersion: existingSpdx.bom_version, newVersion: bomVersion },
          "User updating existing SPDX artifact - incrementing version");
      } else {
        logger.warn({ existingSerialNumber }, "existingSerialNumber provided but no existing SPDX found - treating as new upload");
      }
    } else {
      const existingByNamespace = await SpdxRepository.findSpdxBomByNamespace(
        spdxMetadata.documentNamespace || '',
        bomInput.bomInput.org
      );
      
      if (existingByNamespace) {
        if (existingByNamespace.file_sha256 === fileHash) {
          logger.warn({
            namespace: spdxMetadata.documentNamespace,
            fileHash
          }, "SPDX document with same namespace and content already exists - returning existing record");
          
          if (existingByNamespace.converted_bom_uuid) {
            const existingBomRecords = await BomRepository.bomById(existingByNamespace.converted_bom_uuid);
            if (existingBomRecords && existingBomRecords.length > 0) {
              return existingBomRecords[0];
            }
          }
          throw new BomNotFoundError(
            `SPDX document exists but linked BOM record not found`,
            existingByNamespace.converted_bom_uuid,
            { namespace: spdxMetadata.documentNamespace, context: 'spdx_duplicate_check' }
          );
        }
        
        throw new BomValidationError(
          `SPDX document with namespace "${spdxMetadata.documentNamespace}" already exists with different content. ` +
          `To update an existing artifact, use the update flow with existingSerialNumber.`,
          {
            field: 'documentNamespace',
            value: spdxMetadata.documentNamespace,
            constraint: 'namespace must be unique or use update flow with existingSerialNumber'
          }
        );
      }
    }
    
    const spdxUuid = uuidv4();
    const spdxOciResponse = await pushToOci(spdxUuid, spdxContent);

    const spdxRecord = await SpdxRepository.createSpdxBom({
      uuid: spdxUuid,
      spdx_metadata: spdxMetadata,
      oci_response: spdxOciResponse,
      organization: bomInput.bomInput.org,
      file_sha256: fileHash,
      conversion_status: 'pending',
      tags: bomInput.bomInput.tags,
      public: false,
      bom_version: bomVersion
    });

    const conversionResult = await SpdxService.convertSpdxToCycloneDx(spdxContent);
    
    if (!conversionResult.success) {
      await SpdxRepository.updateSpdxBomConversionStatus(
        spdxRecord.uuid,
        'failed',
        conversionResult.error
      );
      throw new BomConversionError(
        `SPDX conversion failed: ${conversionResult.error}`,
        'SPDX',
        'CYCLONEDX',
        new Error(conversionResult.error)
      );
    }

    const rebomOptions = SpdxService.generateRebomOptionsFromSpdx(spdxMetadata, bomVersion, existingSerialNumber);
    const mergedOptions = { ...rebomOptions, ...bomInput.bomInput.rebomOptions };
    
    let serialNumber: string;
    if (existingSerialNumber) {
      serialNumber = existingSerialNumber.startsWith('urn:uuid:')
        ? existingSerialNumber
        : `urn:uuid:${existingSerialNumber}`;
      mergedOptions.serialNumber = serialNumber;
      logger.info({ serialNumber, bomVersion }, "Using existing serial number for SPDX update continuity");
    } else if (conversionResult.convertedBom.serialNumber) {
      serialNumber = conversionResult.convertedBom.serialNumber;
      mergedOptions.serialNumber = serialNumber;
      logger.info({ serialNumber }, "Using serial number from rearm-cli converted BOM");
    } else {
      const generatedSerialNumber = uuidv4();
      serialNumber = `urn:uuid:${generatedSerialNumber}`;
      mergedOptions.serialNumber = serialNumber;
      logger.warn({ serialNumber }, "Generated fallback serial number - rearm-cli output missing serialNumber");
    }
    
    const convertedBom = conversionResult.convertedBom;
    const bomDigest = computeBomDigest(convertedBom);
    mergedOptions.bomDigest = bomDigest;
    mergedOptions.originalFileDigest = fileHash;
    mergedOptions.originalFileSize = JSON.stringify(spdxContent).length;
    mergedOptions.originalMediaType = 'application/spdx+json';
    mergedOptions.bomVersion = String(bomVersion);
    
    const convertedBomUuid = uuidv4();
    const enrichedBom = await enrichCycloneDxBom(conversionResult.convertedBom);
    const cycloneDxOciResponse = await pushToOci(convertedBomUuid, enrichedBom);

    const queryText = 'INSERT INTO rebom.boms (uuid, meta, bom, tags, organization, source_format, source_spdx_uuid) VALUES ($1, $2, $3, $4, $5, $6, $7) RETURNING *';
    const queryParams = [
      convertedBomUuid,
      mergedOptions,
      cycloneDxOciResponse,
      bomInput.bomInput.tags,
      bomInput.bomInput.org,
      'SPDX',
      spdxRecord.uuid
    ];

    const queryRes = await runQuery(queryText, queryParams);
    const bomRecord: BomRecord = queryRes.rows[0];

    await SpdxRepository.linkConvertedBom(spdxRecord.uuid, bomRecord.uuid);
    await SpdxRepository.updateSpdxBomConversionStatus(spdxRecord.uuid, 'success');

    logger.info(`Successfully processed SPDX BOM: ${spdxRecord.uuid} -> ${bomRecord.uuid}`);
    return bomRecord;

  } catch (error) {
    logger.error({ err: error }, "Error processing SPDX BOM");
    throw error;
  }
}

async function processBomObj(bom: any): Promise<any> {
  let processedBom: any = {}

  processedBom = await sanitizeBom(bom, {
    '\\u003c': '<',
    '\\u003e': '>',
    '\\u0022': '',
    '\\u002B': '+',
    '\\u0027': ',',
    '\\u0060': '',
    'Purl': 'purl',
    ':git@github': ':ssh://git@github',
    'git+https://github': 'ssh://git@github',
  })

  // Fix dependencies structure BEFORE validation - ensure dependsOn is always an array
  if (processedBom.dependencies && Array.isArray(processedBom.dependencies)) {
    processedBom.dependencies = processedBom.dependencies.map((dep: any) => {
      if ('dependsOn' in dep) {
        if (!Array.isArray(dep.dependsOn)) {
          logger.debug({ ref: dep.ref, dependsOn: dep.dependsOn, type: typeof dep.dependsOn }, "Fixing non-array dependsOn");
          // Convert any non-array value to an array
          if (dep.dependsOn === null || dep.dependsOn === undefined || dep.dependsOn === '') {
            return { ...dep, dependsOn: [] };
          } else {
            return { ...dep, dependsOn: [dep.dependsOn] };
          }
        }
      }
      return dep;
    });
  }

  let proceed: boolean = await validateBom(processedBom)

  if (proceed)
    processedBom = deduplicateBom(processedBom)

  proceed = await validateBom(processedBom)

  if (!proceed) {
    return null
  }

  return processedBom
}

function deduplicateBom(bom: any): any {
  let outBom: any = {
    'bomFormat': bom.bomFormat,
    'specVersion': bom.specVersion,
    'serialNumber': bom.serialNumber,
    'version': bom.version,
    'metadata': bom.metadata
  }
  
  if ('signature' in bom) outBom.signature = bom.signature
  if ('annotations' in bom) outBom.annotations = bom.annotations
  if ('formulation' in bom) outBom.formulation = bom.formulation
  if ('declarations' in bom) outBom.declarations = bom.declarations
  if ('definitions' in bom) outBom.definitions = bom.definitions
  if ('vulnerabilities' in bom) outBom.vulnerabilities = bom.vulnerabilities
  if ('compositions' in bom) outBom.compositions = bom.compositions
  if ('services' in bom) outBom.services = bom.services
  if ('externalReferences' in bom) outBom.externalReferences = bom.externalReferences
  if ('properties' in bom) outBom.properties = bom.properties
  
  let purl_dedup_map: any = {}
  let name_dedup_map: any = {}
  let out_components: any[] = []
  if (bom.components && Array.isArray(bom.components)) {
    bom.components.forEach((component: any) => {
      if ('purl' in component) {
        if (!(component.purl in purl_dedup_map)) {
          out_components.push(component)
          purl_dedup_map[component.purl] = true
        } else {
          logger.info(`deduped comp by purl: ${component.purl}`)
        }
      } else if ('name' in component && 'version' in component) {
        let nver: string = component.name + '_' + component.version
        if (!(nver in name_dedup_map)) {
          out_components.push(component)
          name_dedup_map[nver] = true
        } else {
          logger.info(`deduped comp by name: ${nver}`)
        }
      } else {
        out_components.push(component)
      }
    })
  }
  outBom.components = out_components
  if ('dependencies' in bom) {
    const dependencyMap = new Map<string, any>();
    bom.dependencies.forEach((dep: any) => {
      // Ensure dependsOn is always an array before creating key
      const normalizedDep = {
        ...dep,
        dependsOn: Array.isArray(dep.dependsOn) ? dep.dependsOn : (dep.dependsOn ? [dep.dependsOn] : [])
      };
      const key = JSON.stringify({ ref: normalizedDep.ref, dependsOn: normalizedDep.dependsOn.sort() });
      if (!dependencyMap.has(key)) {
        dependencyMap.set(key, normalizedDep);
      }
    });
    outBom.dependencies = Array.from(dependencyMap.values());
    const dedupedCount = bom.dependencies.length - outBom.dependencies.length;
    if (dedupedCount > 0) {
      logger.info(`Deduped ${dedupedCount} duplicate dependencies from BOM ${bom.serialNumber}`);
    }
  }

  logger.info(`Dedup BOM ${bom.serialNumber} - reduced json from ${Object.keys(bom).length} to ${Object.keys(outBom).length}`)
  return outBom
}

async function sanitizeBom(bom: any, patterns: Record<string, string>): Promise<any> {
  try {
    let jsonString = JSON.stringify(bom);
    Object.entries(patterns).forEach(([search, replace]) => {
      jsonString = jsonString.replaceAll(search, replace);
    });
    return JSON.parse(jsonString)
  } catch (e) {
    logger.error({ err: e }, "Error sanitizing bom")
    throw new BomStorageError(
      "Error sanitizing bom: " + (e instanceof Error ? e.message : String(e)),
      e instanceof Error ? e : new Error(String(e)),
      { operation: 'sanitizeBom' }
    );
  }
}
