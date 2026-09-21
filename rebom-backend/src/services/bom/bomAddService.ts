import { logger } from '../../logger';
import { BomInput, BomRecord, BomFormat, RebomOptions, BomSearch, BomDto } from '../../types';
import { BomValidationError, BomStorageError, BomConversionError, OciStorageError, BomNotFoundError, BomVersionConflictError } from '../../types/errors';
import * as BomRepository from '../../bomRepository';
import * as SpdxRepository from '../../spdxRepository';
import { SpdxService } from '../spdx';
import { 
  pushToOci, 
  getMonthlyRepositoryName,
  validateDualBomPush,
  validateOciPushResult,
  extractRepositoryNameFromBom
} from '../oci';
import { computeBomDigest, augmentBomForStorage, getInitialEnrichmentStatus, enrichBomAsync, normalizeLicensesInBom, mintProcessedSerialNumber } from './bomProcessingService';
import { downgradeCycloneDxSpecIfNeeded, isProcessableCycloneDxSpec } from '../cyclonedx/cdxSpecDowngrade';
import validateBom from '../../validateBom';
import { v4 as uuidv4 } from 'uuid';
import { runQuery } from '../../utils';
import { createHash } from 'crypto';

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
  // CycloneDX 1.7 is published upstream but no library in our stack
  // (cyclonedx-go used by rearm-cli for BEAR enrichment, cyclonedx-core-java
  // used by ReARM backend for SBOM-component parsing, cyclonedx-javascript-library
  // used here for validation) supports it yet. Deep-clone the raw BOM and
  // downgrade the clone's specVersion to 1.6 in place so all downstream sees
  // a recognised spec. The UNDOWNGRADED document is what goes under the
  // `<uuid>-raw` OCI key below, so once libraries catch up that copy can be
  // re-augmented at the new spec.
  //
  // "raw" here means UNPROCESSED, not UNMODIFIED, and the distinction matters.
  // What lands under that key is `JSON.stringify` of an object that arrived
  // already parsed: ReARM reads the upload into a JsonNode before it ever calls
  // rebom, so the publisher's actual bytes -- their whitespace, key order and
  // number formatting -- do not exist on this side of the wire and never have.
  // An earlier version of this comment claimed they were "stored verbatim",
  // which sent a byte-fidelity investigation looking for them here. If you need
  // the bytes as uploaded, they are retained by rearm-core and addressed by the
  // artifact's AS_UPLOADED digest; `meta.originalFileDigest` below is the digest
  // of THIS document, not of that file.
  //
  // CDX 2.0+ (e.g. HBOM prototype) can't go through the 1.x processing/validation
  // stack -- store the document unprocessed and skip processing/validation. That
  // copy is what gets stored under both keys below, so parseBom/parseHbom read the
  // real 2.0 content. Re-process once the libraries support the new spec.
  const processable = isProcessableCycloneDxSpec(rawBom?.specVersion);
  let processedBom: any;
  if (processable) {
    const inputForProcessing = downgradeCycloneDxSpecIfNeeded(structuredClone(rawBom));
    processedBom = await processBomObj(inputForProcessing);
  } else {
    logger.info({ specVersion: rawBom?.specVersion },
      'CycloneDX spec not supported by processing/validation stack — storing raw verbatim, skipping processing/validation/augmentation');
    processedBom = structuredClone(rawBom);
  }

  // serialNumber is the BOM's identity and must come from the producing tool —
  // we do not mint one on our side (it keys deduplication / version lineage).
  // Reject a BOM without one with a clear error instead of crashing downstream
  // where BomRepository runs serialNumber.startsWith('urn:uuid:') on undefined.
  if (processedBom && !processedBom.serialNumber) {
    throw new BomValidationError(
      'CycloneDX BOM is missing a serialNumber. A serialNumber (e.g. urn:uuid:...) is required; rebom does not generate one.',
      { field: 'serialNumber', constraint: 'CycloneDX serialNumber is required', value: processedBom.serialNumber }
    );
  }

  if (processable) {
    await validateBom(processedBom); // throws BomValidationError on failure
  }

  // Step 2: Prepare metadata
  const rebomOptions: RebomOptions = bomInput.bomInput.rebomOptions ?? {};
  rebomOptions.serialNumber = processedBom.serialNumber;
  rebomOptions.bomVersion = processedBom.version; // Use version from CycloneDX (set by ReARM backend)
  rebomOptions.mod = 'raw';

  // Step 3: Set enrichment status and optionally augment with component context
  // Enrichment will happen asynchronously after BOM is stored
  rebomOptions.enrichmentStatus = await getInitialEnrichmentStatus(bomInput.bomInput.org);
  
  let finalBom = processedBom;
  if (AUGMENT_ON_STORAGE && processable) {
    logger.debug({ serialNumber: rebomOptions.serialNumber }, "Augmenting BOM with component context before storage");
    finalBom = augmentBomForStorage(processedBom, rebomOptions, new Date());
  }

  // Everything processable is a different document from the one uploaded by the
  // time it gets here -- processBomObj has sanitized, deduplicated and repaired
  // its dependencies, whether or not augmentation ran on top -- so it is pushed
  // under an identity of its own, with a link back to the producer's. Gated on
  // `processable` and not on AUGMENT_ON_STORAGE on purpose: tying it to
  // augmentation would mean flipping that flag silently republished a
  // deduplicated document under the producer's serialNumber.
  //
  // meta.serialNumber stays the producer's -- it is the row's identity and what
  // every lookup keys on; this records which document the row currently serves.
  // The non-processable path is left alone: that copy really is the uploaded
  // bytes, verbatim, so it keeps the uploaded identity.
  if (processable) {
    finalBom = mintProcessedSerialNumber(finalBom);
    rebomOptions.processedSerialNumber = finalBom.serialNumber;
  }
  
  // Compute digest on the final BOM (augmented or processed, depending on config)
  rebomOptions.bomDigest = computeBomDigest(finalBom);

  const newUuid = uuidv4();
  const rawUuid = newUuid + '-raw';

  // Step 4: Decide BEFORE pushing anything.
  //
  // The digest that decides duplication is computed here rather than read back
  // from the push, so a duplicate and a refused upload both cost zero writes to
  // the registry. It is byte-for-byte what pushToOci sends -- the same
  // JSON.stringify of the same object -- and the push result is compared against
  // it below, so a divergence would be visible rather than assumed.
  const serialNumber = rebomOptions.serialNumber;
  const newRawDigest = createHash('sha256').update(JSON.stringify(rawBom)).digest('hex');

  // First check if this exact file was already uploaded (any version)
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

  // Not a duplicate - a different file claiming the same identity has to claim
  // a higher version, or it is asking us to rewrite artifacts that are already
  // stored and already scanned.
  const latestBom = await findLatestBomBySerialNumber(serialNumber, bomInput.bomInput.org);
  if (latestBom) {
    const latestVersion = parseInt(latestBom.meta?.bomVersion) || 0;
    const newVersion = parseInt(rebomOptions.bomVersion) || 0;
    if (newVersion <= latestVersion) {
      throw new BomVersionConflictError(
        `BOM with serialNumber ${serialNumber} already exists at version ${latestVersion}; ` +
        `uploaded version ${newVersion} must be greater. ` +
        `Raw artifacts are immutable and are never replaced.`,
        serialNumber, latestVersion, newVersion, latestBom.uuid);
    }
  }

  // Step 5: Store artifacts in OCI
  // Calculate repository name ONCE to prevent month boundary race conditions
  const uploadTimestamp = new Date();
  const repositoryName = getMonthlyRepositoryName(uploadTimestamp);
  
  logger.debug({ repositoryName, uploadTimestamp: uploadTimestamp.toISOString() }, 'Calculated repository name for upload');
  
  const rawPushResult = await pushToOci(rawUuid, rawBom, repositoryName);  // Raw BOM (original, untouched)
  const pushResult = await pushToOci(newUuid, finalBom, repositoryName);  // Processed (and optionally augmented) BOM
  
  // Validate both BOMs went to same repository and have repository names set
  validateDualBomPush(rawPushResult, pushResult, 'upload', newUuid);

  // The decision above was made on a locally computed digest. If the registry
  // reports a different one, it transformed the bytes on the way in, and every
  // duplicate check from here on is comparing against something we never sent.
  if (rawPushResult.fileSHA256Digest && rawPushResult.fileSHA256Digest !== newRawDigest) {
    logger.error({
      serialNumber,
      uuid: newUuid,
      localDigest: newRawDigest,
      ociDigest: rawPushResult.fileSHA256Digest
    }, 'OCI-reported raw digest differs from the bytes rebom sent -- deduplication is comparing different things');
  }
  
  // Track raw BOM metadata for ReARM backend (use actual file digest from OCI)
  // Note: rawBomUuid is always `uuid + '-raw'` so ReARM backend can reconstruct it
  // Digest of the STORED document (this push's bytes), not of the file the publisher
  // uploaded -- see addCycloneDxBom's note on what "raw" means here.
  rebomOptions.originalFileDigest = rawPushResult.fileSHA256Digest;
  rebomOptions.originalFileSize = rawPushResult.originalSize;
  rebomOptions.originalMediaType = rawPushResult.originalMediaType;
  // Pin the raw copy's repository: enrichment later re-pushes the PROCESSED
  // BOM to the then-current month's repository (bom.ociRepositoryName moves
  // with it), but the raw copy stays here -- without its own pointer a
  // cross-month enrichment strands it (see rawBomResolver).
  rebomOptions.rawOciRepositoryName = rawPushResult.ociRepositoryName;
  
  // Track processed/augmented BOM metadata for validation
  rebomOptions.processedFileDigest = pushResult.fileSHA256Digest;  // Augmented BOM digest
  rebomOptions.processedFileSize = pushResult.originalSize;
  // The first processed artifact lives at the bare uuid. Set explicitly so every
  // new row carries the pointer and the legacy fallback only serves old rows.
  rebomOptions.processedTag = newUuid;
  
  // Repository name is already stored in pushResult.ociRepositoryName (bom field)
  // No need to duplicate it in meta

  // Step 6: Insert. Every accepted upload is a new row now -- the only paths
  // that reach here are a first upload and a version increment.
  logger.info({ 
    serialNumber,
    bomVersion: rebomOptions.bomVersion,
    uuid: newUuid,
    latestVersion: latestBom?.meta?.bomVersion,
    newRawDigest
  }, latestBom ? "Inserting new version of existing BOM (version increment)" : "Inserting new BOM (no existing record)");

  const queryText = 'INSERT INTO rebom.boms (uuid, meta, bom, tags, organization, source_format) VALUES ($1, $2, $3, $4, $5, $6) RETURNING *';
  const queryParams = [newUuid, rebomOptions, pushResult, bomInput.bomInput.tags, bomInput.bomInput.org, 'CYCLONEDX'];
  
  // Step 7: Execute database operation
  logger.info({ 
    serialNumber,
    bomVersion: rebomOptions.bomVersion,
    bomDigest: rebomOptions.bomDigest,
    augmented: AUGMENT_ON_STORAGE
  }, "Inserting BOM record");

  const queryRes = await runQuery(queryText, queryParams);
  const bomRecord = queryRes.rows[0];
  
  if (!bomRecord) {
    throw new BomStorageError('Failed to store BOM record', undefined, {
      operation: 'INSERT',
      bomId: newUuid,
      serialNumber
    });
  }
  
  logger.info({ 
    bomRecordUuid: bomRecord.uuid,
    bomVersion: rebomOptions.bomVersion,
    operation: queryText.startsWith('INSERT') ? 'INSERT' : 'UPDATE'
  }, "BOM record stored successfully");
  
  // Trigger async enrichment (fire-and-forget). Skipped for specs the 1.x
  // stack can't process — enrichment assumes purl-keyed 1.x component shape.
  if (processable) {
    enrichBomAsync(bomRecord.uuid, finalBom, bomInput.bomInput.org).catch(err => {
      logger.error({ err, bomUuid: bomRecord.uuid }, 'Async enrichment trigger failed');
    });
  }
  
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
    const spdxTimestamp = new Date();
    const spdxRepositoryName = getMonthlyRepositoryName(spdxTimestamp);
    const spdxPushResult = await pushToOci(spdxUuid, spdxContent, spdxRepositoryName);
    
    // Validate that repository name was set
    validateOciPushResult(spdxPushResult, 'SPDX push', spdxUuid);

    // Repository name is already in spdxPushResult.ociRepositoryName (oci_response field)
    // No need to duplicate it in spdx_metadata
    const spdxRecord = await SpdxRepository.createSpdxBom({
      uuid: spdxUuid,
      spdx_metadata: spdxMetadata,
      oci_response: spdxPushResult,
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
    
    // Converted, therefore a distinct document from the SPDX file that was
    // uploaded, and given its own identity for the same reason the augmented
    // CycloneDX copy is. No source reference: the document it came from is
    // SPDX, and BOM-Link has no form for one -- better no link than an
    // invented one.
    const convertedBom = mintProcessedSerialNumber(conversionResult.convertedBom, null);
    const bomDigest = computeBomDigest(convertedBom);
    mergedOptions.bomDigest = bomDigest;
    // fileHash is sha256(JSON.stringify(spdxContent)) -- the parsed document, not the
    // uploaded file. Same misnomer as the CycloneDX path; see the note on the field.
    mergedOptions.originalFileDigest = fileHash;
    mergedOptions.originalFileSize = JSON.stringify(spdxContent).length;
    mergedOptions.originalMediaType = 'application/spdx+json';
    mergedOptions.bomVersion = String(bomVersion);
    
    const convertedBomUuid = uuidv4();
    // Set enrichment status - enrichment will happen asynchronously
    mergedOptions.enrichmentStatus = await getInitialEnrichmentStatus(bomInput.bomInput.org);
    // Use same repository as SPDX upload to ensure consistency
    const cycloneDxPushResult = await pushToOci(convertedBomUuid, convertedBom, spdxRepositoryName);
    
    // Validate that repository name was set
    validateOciPushResult(cycloneDxPushResult, 'converted CycloneDX push', convertedBomUuid);
    
    // Store processed BOM digest for validation (converted CycloneDX is the processed version)
    mergedOptions.processedFileDigest = cycloneDxPushResult.fileSHA256Digest;
    mergedOptions.processedFileSize = cycloneDxPushResult.originalSize;
    // Same reason as the CycloneDX path: the pointer is explicit on every new row.
    mergedOptions.processedTag = convertedBomUuid;
    mergedOptions.processedSerialNumber = convertedBom.serialNumber;
    
    // Repository name is already in cycloneDxPushResult.ociRepositoryName

    const queryText = 'INSERT INTO rebom.boms (uuid, meta, bom, tags, organization, source_format, source_spdx_uuid) VALUES ($1, $2, $3, $4, $5, $6, $7) RETURNING *';
    const queryParams = [
      convertedBomUuid,
      mergedOptions,
      cycloneDxPushResult,
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
    
    // Trigger async enrichment (fire-and-forget)
    enrichBomAsync(bomRecord.uuid, convertedBom, bomInput.bomInput.org).catch(err => {
      logger.error({ err, bomUuid: bomRecord.uuid }, 'Async enrichment trigger failed');
    });
    
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

  normalizeLicensesInBom(processedBom);

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
