import { logger } from '../../logger';
import { BomInput, BomRecord, BomFormat, RebomOptions, BomSearch, BomDto } from '../../types';
import * as BomRepository from '../../bomRepository';
import * as SpdxRepository from '../../spdxRepository';
import { SpdxService } from '../spdx';
import { fetchFromOci, OASResponse, pushToOci } from '../oci';
import { computeBomDigest } from './bomProcessingService';
import { findBom, findBomByMeta } from './bomSearchService';
import validateBom from '../../validateBom';
import { v4 as uuidv4 } from 'uuid';
import { runQuery } from '../../utils';

export async function addBom(bomInput: BomInput): Promise<BomRecord> {
  const format: BomFormat = (bomInput.bomInput as any).format || 'CYCLONEDX';
  if (format === 'SPDX') {
    return await addSpdxBom(bomInput);
  } else {
    return await addCycloneDxBom(bomInput);
  }
}

async function addCycloneDxBom(bomInput: BomInput): Promise<BomRecord> {
  // Preserve the truly raw, unmodified BOM artifact (preserves signatures)
  const rawBom = bomInput.bomInput.bom;
  const rawBomSha = computeBomDigest(rawBom, 'false'); // Hash of raw artifact
  
  // Process BOM for internal use (sanitize, deduplicate, validate)
  let processedBom = await processBomObj(rawBom)

  let proceed: boolean = await validateBom(processedBom)
  const rebomOptions: RebomOptions = bomInput.bomInput.rebomOptions ?? {}
  rebomOptions.serialNumber = processedBom.serialNumber
  const bomSha: string = computeBomDigest(processedBom, rebomOptions.stripBom)
  rebomOptions.bomDigest = bomSha
  rebomOptions.bomVersion = processedBom.version
  const newUuid = uuidv4();
  
  let oasResponse: OASResponse
  let bomRows: BomRecord[]
  let bomRecord: BomRecord

  if (process.env.OCI_STORAGE_ENABLED) {
    // CRITICAL: Store raw unmodified artifact (preserves signatures)
    // Use separate UUID suffix to distinguish raw from processed
    const rawUuid = newUuid + '-raw';
    await pushToOci(rawUuid, rawBom);
    
    // Store processed BOM for internal operations (this is what gets fetched by default)
    oasResponse = await pushToOci(newUuid, processedBom);
    rebomOptions.storage = 'oci';
  } else {
    throw new Error("OCI Storage not enabled");
  }

  rebomOptions.mod = 'raw';
  
  // Check for duplicates BEFORE adding raw artifact metadata
  // This ensures duplicate detection works correctly
  let queryText = 'INSERT INTO rebom.boms (uuid, meta, bom, tags, organization, source_format) VALUES ($1, $2, $3, $4, $5, $6) RETURNING *';
  let queryParams = [newUuid, rebomOptions, oasResponse, bomInput.bomInput.tags, bomInput.bomInput.org, 'CYCLONEDX'];
  
  if (rebomOptions.serialNumber) {
    let bomSearch: BomSearch = {
      bomSearch: {
        serialNumber: rebomOptions.serialNumber as string,
        version: '',
        componentVersion: '',
        componentGroup: '',
        componentName: '',
        singleQuery: '',
        page: 0,
        offset: 0
      }
    }
    
    let bomDtos = await findBom(bomSearch);

    if (!bomDtos || !bomDtos.length)
      bomDtos = await findBomByMeta(rebomOptions);

    if (bomDtos && bomDtos.length && bomDtos[0].uuid) {
      queryText = 'UPDATE rebom.boms SET meta = $1, bom = $2, tags = $3 WHERE uuid = $4 RETURNING *';
      queryParams = [rebomOptions, oasResponse, bomInput.bomInput.tags, bomDtos[0].uuid];
    }
  }
  
  // NOW add raw artifact metadata after duplicate check
  // Store raw artifact reference in meta for retrieval
  const rawUuid = newUuid + '-raw';
  (rebomOptions as any).rawBomUuid = rawUuid;
  (rebomOptions as any).rawBomDigest = rawBomSha;

  let queryRes = await runQuery(queryText, queryParams)
  bomRows = queryRes.rows
  bomRecord = bomRows[0]
  
  return bomRecord
}

async function addSpdxBom(bomInput: BomInput): Promise<BomRecord> {
  try {
    const spdxContent = bomInput.bomInput.bom;
    
    if (!SpdxService.validateSpdxFormat(spdxContent)) {
      throw new Error('Invalid SPDX format');
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
          throw new Error(`SPDX document exists but linked BOM record not found`);
        }
        
        throw new Error(
          `SPDX document with namespace "${spdxMetadata.documentNamespace}" already exists with different content. ` +
          `To update an existing artifact, use the update flow with existingSerialNumber.`
        );
      }
    }
    
    const spdxUuid = uuidv4();
    let spdxOciResponse: OASResponse;
    
    if (process.env.OCI_STORAGE_ENABLED) {
      spdxOciResponse = await pushToOci(spdxUuid, spdxContent);
    } else {
      throw new Error("OCI Storage not enabled");
    }

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
      throw new Error(`SPDX conversion failed: ${conversionResult.error}`);
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
    const bomDigest = computeBomDigest(convertedBom, mergedOptions.stripBom);
    mergedOptions.bomDigest = bomDigest;
    mergedOptions.originalFileDigest = fileHash;
    mergedOptions.originalFileSize = JSON.stringify(spdxContent).length;
    mergedOptions.originalMediaType = 'application/spdx+json';
    mergedOptions.bomVersion = String(bomVersion);
    
    const convertedBomUuid = uuidv4();
    let cycloneDxOciResponse: OASResponse;
    
    if (process.env.OCI_STORAGE_ENABLED) {
      cycloneDxOciResponse = await pushToOci(convertedBomUuid, conversionResult.convertedBom);
    } else {
      throw new Error("OCI Storage not enabled");
    }

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
  outBom.components = out_components
  if ('dependencies' in bom) {
    // Fix dependencies structure - ensure dependsOn is always an array
    outBom.dependencies = bom.dependencies.map((dep: any) => {
      if (dep.dependsOn && !Array.isArray(dep.dependsOn)) {
        return { ...dep, dependsOn: [dep.dependsOn] };
      }
      return dep;
    });
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
    throw new Error("Error sanitizing bom: " + (e instanceof Error ? e.message : String(e)));
  }
}
