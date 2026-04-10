import { logger } from '../../logger';
import { RebomOptions, HIERARCHICHAL, EnrichmentStatus, BomRecord } from '../../types';
import { BomValidationError, BomStorageError, OciStorageError, BomConversionError } from '../../types/errors';
import { PackageURL } from 'packageurl-js';
import { createTempFile, deleteTempFile, shellExec, runQuery } from '../../utils';
import { 
  fetchFromOci, 
  pushToOci, 
  getMonthlyRepositoryName,
  extractRepositoryNameFromBom,
  extractRepositoryNameFromSpdxOciResponse,
  validateOciPushResult,
  fetchRawBomWithFallback
} from '../oci';
import * as BomRepository from '../../bomRepository';
import * as SpdxRepository from '../../spdxRepository';
import { getBearCredentials, getBearIntegration } from '../integrationService';
import { SpdxService } from '../spdx';
const canonicalize = require('canonicalize');
import { createHash } from 'crypto';
import * as fs from 'fs';

// Enrichment timeout constant - used by both enrichCycloneDxBom and triggerEnrichment
const ENRICHMENT_TIMEOUT_MS = 3600000; // 60 minutes timeout
const ENRICHMENT_GRACE_PERIOD_MS = 300000; // 5 minutes grace period for stale PENDING status

export function extractTldFromBom(bom: any): any {
  let newBom: any = {}
  let rootComponentRef: string
  try {
    rootComponentRef = bom.metadata?.component?.['bom-ref']
    if (!rootComponentRef) {
      logger.warn("BOM does not have metadata.component['bom-ref'], skipping TLD extraction and returning full BOM")
      return bom
    }
  } catch (e) {
    logger.warn({ err: e }, "Cannot extract TLD from BOM, returning full BOM")
    return bom
  }
  let rootDepObj: any
  if (!bom.components || !Array.isArray(bom.components)) {
    logger.warn("BOM does not have components array, skipping TLD extraction and returning full BOM")
    return bom
  }
  if (!bom.dependencies || !Array.isArray(bom.dependencies)) {
    logger.warn("BOM does not have dependencies array, skipping TLD extraction and returning full BOM")
    return bom
  }
  logger.info(`Bom components length before tld extract: ${bom.components.length}`)
  logger.info(`rootComponentRef: ${rootComponentRef}`)
  if (rootComponentRef && bom.dependencies.length) {
    rootDepObj = bom.dependencies.find((dep: any) => dep.ref === rootComponentRef)

    if (rootDepObj && rootDepObj.dependsOn && Array.isArray(rootDepObj.dependsOn) && rootDepObj.dependsOn.length && bom.components && bom.components.length) {
      newBom.components = bom.components.filter((comp: any) => {
        return rootDepObj.dependsOn.includes(comp["bom-ref"])
      })
      newBom.dependencies = []
      newBom.dependencies[0] = {
        ...rootDepObj,
        dependsOn: Array.isArray(rootDepObj.dependsOn) ? rootDepObj.dependsOn : []
      }
    }
  }

  const finalBom = Object.assign(bom, newBom)
  logger.info(`Bom components length AFTER tld extract: ${finalBom.components.length}`)

  return finalBom
}

const DEV_DEPENDENCY_PATTERNS = {
  maven: {
    propertyName: 'cdx:maven:component_scope',
    devValues: ['test']
  },
  npm: {
    propertyName: 'cdx:npm:package:development', 
    devValues: ['true']
  },
  nuget: {
    propertyName: 'cdx:nuget:development',
    devValues: ['true']
  },
  golang: {
    propertyName: 'cdx:go:build_tag',
    devValues: ['test', 'testing', 'dev', 'development']
  },
  gradle: {
    propertyName: 'cdx:gradle:component_scope',
    devValues: ['testImplementation', 'testCompile', 'testRuntime']
  }
};

function isDevDependency(component: any): boolean {
  if (component.scope === 'optional' || component.scope === 'excluded') {
    return true;
  }
  
  if (!component.properties || !Array.isArray(component.properties)) {
    return false;
  }

  for (const [ecosystem, pattern] of Object.entries(DEV_DEPENDENCY_PATTERNS)) {
    const property = component.properties.find((prop: any) => 
      prop.name === pattern.propertyName
    );
    
    if (property && pattern.devValues.includes(property.value)) {
      logger.debug(`Component ${component['bom-ref']} marked as dev dependency (${ecosystem}): ${property.name}=${property.value}`);
      return true;
    }
  }
  
  return false;
}

export function extractDevFilteredBom(bom: any): any {
  logger.info(`Filtering dev dependencies - original components: ${bom.components?.length || 0}`);
  
  if (!bom.components || !Array.isArray(bom.components)) {
    logger.warn('No components found in BOM for dev filtering');
    return bom;
  }

  const prodComponents = bom.components.filter((component: any) => !isDevDependency(component));
  const filteredComponentRefs = new Set(prodComponents.map((comp: any) => comp['bom-ref']));
  
  logger.info(`After dev filtering - remaining components: ${prodComponents.length}`);
  
  let newDependencies = [];
  if (bom.dependencies && Array.isArray(bom.dependencies)) {
    newDependencies = bom.dependencies.map((dep: any) => {
      if (!dep.dependsOn || !Array.isArray(dep.dependsOn)) {
        return dep;
      }
      
      const filteredDependsOn = dep.dependsOn.filter((ref: string) => 
        filteredComponentRefs.has(ref)
      );
      
      return {
        ...dep,
        dependsOn: filteredDependsOn
      };
    }).filter((dep: any) => {
      return filteredComponentRefs.has(dep.ref) || dep.ref === bom.metadata?.component?.['bom-ref'];
    });
  }

  return {
    ...bom,
    components: prodComponents,
    dependencies: newDependencies
  };
}

export function establishPurl(origPurl: string | undefined, rebomOverride: RebomOptions): string {
  let purlStr = rebomOverride.purl
  if (!purlStr) {
    if (!rebomOverride.name || !rebomOverride.version || !rebomOverride.group) {
      logger.error({ rebomOverride }, "Missing required fields for PURL generation");
      throw new BomValidationError(
        `Missing required fields for PURL generation: name=${rebomOverride.name}, version=${rebomOverride.version}, group=${rebomOverride.group}`,
        {
          field: 'rebomOptions',
          constraint: 'name, version, and group are required for PURL generation',
          value: { name: rebomOverride.name, version: rebomOverride.version, group: rebomOverride.group }
        }
      );
    }
    
    let origPurlParsed: PackageURL | undefined = undefined
    if (origPurl) {
      try {
        origPurlParsed = PackageURL.fromString(origPurl)
      } catch (e: any) {
        throw new BomValidationError(
          e.message,
          {
            field: 'purl',
            value: origPurl,
            constraint: 'must be a valid Package URL'
          }
        );
      }
    }
    const type = (origPurlParsed && origPurlParsed.type && origPurlParsed.type !== "container" && origPurlParsed.type !== "application") ? origPurlParsed.type : 'generic'
    const namespace = (origPurlParsed && (origPurlParsed.namespace || type === 'oci')) ? origPurlParsed.namespace : encodeURIComponent(rebomOverride.group)
    const name = (origPurlParsed && origPurlParsed.name && origPurlParsed.name !== 'app' && origPurlParsed.name !== '.') ? origPurlParsed.name : encodeURIComponent(rebomOverride.name)

    const version = rebomOverride.version
    const qualifiers = (origPurlParsed && origPurlParsed.qualifiers) ? origPurlParsed.qualifiers : {}
    if (rebomOverride.belongsTo) qualifiers.belongsTo = rebomOverride.belongsTo
    if (rebomOverride.hash) qualifiers.hash = rebomOverride.hash
    if (rebomOverride.tldOnly) qualifiers.tldOnly = 'true'
    if (rebomOverride.structure && rebomOverride.structure.toLowerCase() === HIERARCHICHAL.toLowerCase()) qualifiers.structure = HIERARCHICHAL
    try {
      const purl = new PackageURL(
        type,
        namespace,
        name,
        version,
        qualifiers,
        undefined
      )
      purlStr = purl.toString()
    } catch (e: any) {
      throw new BomValidationError(
        e.message,
        {
          field: 'purl',
          constraint: 'must be a valid Package URL',
          value: { type, namespace, name, version }
        }
      );
    }
  }
  return purlStr || ''
}

export function computeRootDepIndex(bom: any): number {
  const rootComponentPurl: string = bom.metadata?.component?.["bom-ref"]
  if (!rootComponentPurl) {
    logger.error("No bom-ref found in metadata.component");
    return -1;
  }
  
  let rootdepIndex: number = bom.dependencies?.findIndex((dep: any) => {
    return dep.ref === rootComponentPurl
  })
  if (rootdepIndex < 0) {
    const decodedRootPurl = decodeURIComponent(rootComponentPurl)
    rootdepIndex = bom.dependencies?.findIndex((dep: any) => {
      return decodeURIComponent(dep.ref) === decodedRootPurl
    })
  }
  if (rootdepIndex < 0) {
    const versionStrippedRootComponentPurl = rootComponentPurl.split("@")[0]
    rootdepIndex = bom.dependencies?.findIndex((dep: any) => {
      return dep.ref === versionStrippedRootComponentPurl
    })
  }
  return rootdepIndex
}

/**
 * Augments a BOM's root component with release/component context.
 * Adds: name, version, group, purl, authors, supplier, timestamp.
 * 
 * This augmentation adds component metadata to the BOM's root component,
 * making it clear which release/component this BOM belongs to.
 * 
 * @param bom - Processed BOM (already sanitized, deduplicated, validated)
 * @param componentDetails - Release/component metadata (name, version, group, etc.)
 * @param lastUpdatedDate - Optional timestamp for metadata
 * @returns BOM with augmented root component
 */
export function augmentBomWithComponentContext(bom: any, componentDetails: RebomOptions, lastUpdatedDate?: string | Date): any {
  const newMetadata = { ...bom.metadata };
  const origPurl = (bom.metadata && bom.metadata.component && bom.metadata.component.purl) ? bom.metadata.component.purl : undefined;
  const newPurl = establishPurl(origPurl, componentDetails);
  logger.debug(`established purl: ${newPurl}`);

  newMetadata.component = {
    ...newMetadata.component,
    purl: newPurl,
    ['bom-ref']: newPurl,
    name: componentDetails.name,
    version: componentDetails.version,
    group: componentDetails.group
  };
  newMetadata['authors'] = [{ name: componentDetails.group }];
  newMetadata['supplier'] = { name: componentDetails.group };
  if (lastUpdatedDate) {
    newMetadata['timestamp'] = (new Date(lastUpdatedDate)).toISOString();
  }

  const rootdepIndex = computeRootDepIndex(bom);
  const dependenciesArray = Array.isArray(bom.dependencies) ? bom.dependencies : [];
  const newDependencies = [...dependenciesArray];
  if (rootdepIndex > -1) newDependencies[rootdepIndex]['ref'] = newPurl;

  return {
    ...bom,
    metadata: newMetadata,
    dependencies: newDependencies
  };
}

/**
 * @deprecated Use augmentBomWithComponentContext instead. This alias is kept for backward compatibility.
 */
export function overrideRootComponent(bom: any, rebomOverride: RebomOptions, lastUpdatedDate?: string | Date): any {
  return augmentBomWithComponentContext(bom, rebomOverride, lastUpdatedDate);
}

export function createRebomToolObject(specVersion: string): any {
  const rebomTool: any = {
    type: "application",
    name: "rearm",
    group: "io.reliza",
    version: process.env.npm_package_version,
    supplier: { name: "Reliza Incorporated" },
    description: "The evidence store for your entire supply chain",
    licenses: [
      { license: { id: "AGPL-3.0-only" } }
    ],
    externalReferences: [
      { url: "ssh://git@github.com/relizaio/rearm.git", type: "vcs" },
      { url: "https://rearmhq.com", type: "website" }
    ]
  };
  if (specVersion === '1.6' || specVersion === '1.7') {
    rebomTool["authors"] = [{ name: "Reliza Incorporated", email: "info@reliza.io" }];
  } else {
    rebomTool["author"] = "Reliza Incorporated";
  }
  return rebomTool;
}

/**
 * Checks if the rearm tool is already present in the BOM's metadata.tools.
 * 
 * @param finalBom - BOM to check
 * @returns true if rearm tool is already present
 */
function hasRearmTool(finalBom: any): boolean {
  const specVersion = finalBom.specVersion || '1.4';
  const majorMinor = specVersion.split('.').slice(0, 2).join('.');
  const isLegacyFormat = parseFloat(majorMinor) < 1.5;
  
  if (isLegacyFormat) {
    // CycloneDX 1.4 and earlier: tools is an array
    if (Array.isArray(finalBom.metadata?.tools)) {
      return finalBom.metadata.tools.some((tool: any) => 
        tool.name === 'rearm' && tool.group === 'io.reliza'
      );
    }
  } else {
    // CycloneDX 1.5+: tools is an object with components array
    if (Array.isArray(finalBom.metadata?.tools?.components)) {
      return finalBom.metadata.tools.components.some((tool: any) => 
        tool.name === 'rearm' && tool.group === 'io.reliza'
      );
    }
  }
  return false;
}

/**
 * Attaches rebom tool information to a BOM's metadata.
 * This marks the BOM as having been processed by rebom.
 * 
 * This function is idempotent - if the rearm tool is already present,
 * it will not be added again.
 * 
 * Handles both CycloneDX formats:
 * - 1.4 and earlier: metadata.tools is an array
 * - 1.5 and later: metadata.tools is an object with components array
 * 
 * @param finalBom - BOM to attach tool information to
 * @returns BOM with rebom tool information added
 */
export function attachRebomToolToBom(finalBom: any): any {
  // Check if rearm tool is already present to prevent duplicates
  if (hasRearmTool(finalBom)) {
    return finalBom;
  }
  
  const rebomTool = createRebomToolObject(finalBom.specVersion);
  
  // Determine spec version to handle format differences
  const specVersion = finalBom.specVersion || '1.4';
  const majorMinor = specVersion.split('.').slice(0, 2).join('.');
  const isLegacyFormat = parseFloat(majorMinor) < 1.5;
  
  if (isLegacyFormat) {
    // CycloneDX 1.4 and earlier: tools is an array
    if (!finalBom.metadata.tools) {
      finalBom.metadata.tools = [];
    }
    // Ensure it's an array (in case it was incorrectly set as object)
    if (!Array.isArray(finalBom.metadata.tools)) {
      finalBom.metadata.tools = [];
    }
    finalBom.metadata.tools.push(rebomTool);
  } else {
    // CycloneDX 1.5+: tools is an object with components array
    if (!finalBom.metadata.tools) {
      finalBom.metadata.tools = { components: [] };
    }
    // Ensure it's an object (in case it was incorrectly set as array)
    if (Array.isArray(finalBom.metadata.tools)) {
      finalBom.metadata.tools = { components: [] };
    }
    if (!finalBom.metadata.tools.components) {
      finalBom.metadata.tools.components = [];
    }
    finalBom.metadata.tools.components.push(rebomTool);
  }
  
  return finalBom;
}

/**
 * Fully augments a BOM with component context and rebom tool information.
 * This is a convenience function that combines augmentBomWithComponentContext
 * and attachRebomToolToBom in a single call.
 * 
 * Use this when preparing a BOM for storage that should include full augmentation.
 * 
 * @param bom - Processed BOM (already sanitized, deduplicated, validated)
 * @param componentDetails - Release/component metadata (name, version, group, etc.)
 * @param lastUpdatedDate - Optional timestamp for metadata
 * @returns Fully augmented BOM ready for storage
 */
export function augmentBomForStorage(bom: any, componentDetails: RebomOptions, lastUpdatedDate?: string | Date): any {
  const augmentedBom = augmentBomWithComponentContext(bom, componentDetails, lastUpdatedDate);
  return attachRebomToolToBom(augmentedBom);
}

function extractComponentIdentity(components: any[]): any[] {
  if (!components || !Array.isArray(components)) return []
  
  return components.map(comp => {
    const identity: any = {}
    
    if (comp.purl) identity.purl = comp.purl
    if (comp['bom-ref']) identity['bom-ref'] = comp['bom-ref']
    if (comp.name) identity.name = comp.name
    if (comp.version) identity.version = comp.version
    if (comp.group) identity.group = comp.group
    if (comp.type) identity.type = comp.type
    if (comp.hashes) identity.hashes = comp.hashes
    if (comp.licenses) identity.licenses = comp.licenses
    
    return identity
  })
}

export function computeBomDigest(bom: any): string {
  let bomForDigest: any = {}
  
  bomForDigest["components"] = extractComponentIdentity(bom["components"])
  bomForDigest["dependencies"] = bom["dependencies"] ? JSON.parse(JSON.stringify(bom["dependencies"])) : []
  
  const ROOT_PLACEHOLDER = '__ROOT_COMPONENT__'
  const rootComponentRef: string = bom.metadata?.component?.['bom-ref']
  
  const rootdepIndex = computeRootDepIndex(bom)
  if (rootdepIndex > -1) {
    bomForDigest["dependencies"][rootdepIndex]['ref'] = ROOT_PLACEHOLDER
  }
  
  for (const dep of bomForDigest["dependencies"]) {
    if (dep.dependsOn && Array.isArray(dep.dependsOn)) {
      dep.dependsOn = dep.dependsOn
        .map((ref: string) => ref === rootComponentRef ? ROOT_PLACEHOLDER : ref)
        .sort()
    }
  }
  
  bomForDigest["dependencies"].sort((a: any, b: any) => 
    (a.ref || '').localeCompare(b.ref || '')
  )
  
  if (bomForDigest["components"]) {
    bomForDigest["components"].sort((a: any, b: any) => 
      (a['bom-ref'] || a.purl || '').localeCompare(b['bom-ref'] || b.purl || '')
    )
  }
 
  const canonBom = canonicalize(bomForDigest)
  return computeSha256Hash(canonBom)
}

function computeSha256Hash(obj: string): string {
  try {
    const hash = createHash('sha256');
    hash.update(obj);
    return hash.digest('hex');
  } catch (error) {
    throw new BomStorageError(
      `Failed to compute hash: ${error instanceof Error ? error.message : 'Unknown error'}`,
      error instanceof Error ? error : new Error(String(error)),
      { operation: 'computeDigest' }
    );
  }
}

/**
 * Enriches a CycloneDX BOM using rearm-cli bomutils enrich command.
 * Credentials are passed as parameters — resolved by the caller from DB or env vars.
 * 
 * @param bom - CycloneDX BOM to enrich
 * @param bearUri - BEAR service URI
 * @param bearApiKey - BEAR API key
 * @param skipPatterns - Optional skip patterns for enrichment
 * @param bomUuid - UUID of the BOM record (for logging)
 * @returns EnrichmentResult with success status and enriched BOM or error
 */
export async function enrichCycloneDxBom(
  bom: any,
  bearUri: string,
  bearApiKey: string,
  skipPatterns: string[] = [],
  bomUuid?: string
): Promise<EnrichmentResult> {
  let inputFile: string | null = null;
  let outputFile: string | null = null;

  try {
    logger.info({ serialNumber: bom.serialNumber, bomUuid }, 'Starting CycloneDX BOM enrichment using rearm-cli');

    inputFile = await createTempFile(bom);
    outputFile = await createTempFile({});
    logger.debug({ inputFile, outputFile }, 'Created temporary files for enrichment');

    const args = [
      'bomutils',
      'enrich',
      '--bearUri', bearUri,
      '--bearApiKey', bearApiKey,
      '-f', inputFile,
      '-o', outputFile
    ];
    
    // Add skip patterns
    for (const pattern of skipPatterns) {
      args.push('--skipPattern', pattern);
    }
    if (skipPatterns.length > 0) {
      logger.debug({ skipPatterns }, 'Added skip patterns to enrichment command');
    }

    logger.info('Running enrichment: rearm-cli bomutils enrich');
    const result = await shellExec('rearm', args, ENRICHMENT_TIMEOUT_MS);
    logger.debug({ enrichmentOutput: result }, 'rearm-cli enrichment completed');

    const enrichedContent = await fs.promises.readFile(outputFile, 'utf8');
    const enrichedBom = JSON.parse(enrichedContent);
    logger.info({
      componentCount: enrichedBom?.components?.length || 0,
      serialNumber: enrichedBom?.serialNumber,
      bomUuid
    }, 'CycloneDX BOM enrichment successful');

    await Promise.all([
      deleteTempFile(inputFile),
      deleteTempFile(outputFile)
    ]);

    return { success: true, enrichedBom };

  } catch (error) {
    if (inputFile) await deleteTempFile(inputFile);
    if (outputFile) await deleteTempFile(outputFile);

    const errorMessage = error instanceof Error ? error.message : String(error);
    const isTimeout = errorMessage.includes('timeout') || errorMessage.includes('TIMEOUT');
    
    logger.error({ 
      error: errorMessage, 
      serialNumber: bom.serialNumber, 
      bomUuid,
      isTimeout,
      timeoutMs: isTimeout ? ENRICHMENT_TIMEOUT_MS : undefined
    }, isTimeout ? 'CycloneDX BOM enrichment timed out' : 'CycloneDX BOM enrichment failed');
    
    return { success: false, error: errorMessage };
  }
}

/**
 * Checks if BEAR enrichment is configured by looking up the integration in the DB.
 */
export async function isEnrichmentConfigured(org: string): Promise<boolean> {
  const integration = await getBearIntegration(org);
  return integration.configured;
}

/**
 * Gets the initial enrichment status based on configuration.
 * Returns PENDING if BEAR is configured in DB.
 * If not configured, returns PENDING when ENRICHMENT_PENDING_IF_NOT_CONFIGURED=true
 * (useful for new environments where enrichment will be set up later).
 * Otherwise returns SKIPPED.
 */
export async function getInitialEnrichmentStatus(org: string): Promise<EnrichmentStatus> {
  const pendingIfNotConfigured = process.env.ENRICHMENT_PENDING_IF_NOT_CONFIGURED === 'true';
  if (pendingIfNotConfigured) {
    return EnrichmentStatus.PENDING;
  }
  return (await isEnrichmentConfigured(org)) ? EnrichmentStatus.PENDING : EnrichmentStatus.SKIPPED;
}

export async function computeBomDigestOnly(format: string, bom: any): Promise<string> {
  let cdxBom = bom;
  if (format === 'SPDX') {
    const result = await SpdxService.convertSpdxToCycloneDx(bom);
    if (!result.success || !result.convertedBom) {
      throw new BomConversionError(`SPDX to CycloneDX conversion failed: ${result.error}`);
    }
    cdxBom = result.convertedBom;
  }
  return computeBomDigest(cdxBom);
}

export interface EnrichedBomProbeResult {
  status: EnrichmentStatus;
  enrichedBom?: string;
}

export async function computeEnrichedBomContent(format: string, bom: any, org: string): Promise<EnrichedBomProbeResult> {
  let cdxBom = bom;
  if (format === 'SPDX') {
    const convResult = await SpdxService.convertSpdxToCycloneDx(bom);
    if (!convResult.success || !convResult.convertedBom) {
      throw new BomConversionError(`SPDX to CycloneDX conversion failed: ${convResult.error}`);
    }
    cdxBom = convResult.convertedBom;
  }
  const credentials = await getBearCredentials(org);
  if (!credentials) {
    return { status: EnrichmentStatus.SKIPPED };
  }
  const result = await enrichCycloneDxBom(cdxBom, credentials.bearUri, credentials.bearApiKey, credentials.skipPatterns);
  if (!result.success) {
    return { status: EnrichmentStatus.FAILED };
  }
  return { status: EnrichmentStatus.COMPLETED, enrichedBom: JSON.stringify(result.enrichedBom) };
}

export interface EnrichmentResult {
  success: boolean;
  enrichedBom?: any;
  error?: string;
}

/**
 * Performs async BOM enrichment and updates the database record.
 * This function is meant to be called without await (fire-and-forget).
 * 
 * @param bomUuid - UUID of the BOM record to enrich
 * @param bom - The BOM content to enrich
 * @param org - Organization ID
 * @param existingCredentials - Pre-resolved credentials (skips DB lookup if provided)
 */
export async function enrichBomAsync(bomUuid: string, bom: any, org: string, existingCredentials?: { bearUri: string; bearApiKey: string; skipPatterns: string[] } | null): Promise<void> {
  const credentials = existingCredentials ?? await getBearCredentials(org);
  if (!credentials) {
    logger.debug({ bomUuid }, 'Enrichment not configured, skipping async enrichment');
    return;
  }

  // Perform enrichment
  const result = await enrichCycloneDxBom(bom, credentials.bearUri, credentials.bearApiKey, credentials.skipPatterns, bomUuid);
  
  if (!result.success) {
    // Enrichment failed (timeout or other error)
    await updateEnrichmentStatus(bomUuid, EnrichmentStatus.FAILED, result.error);
    return;
  }
  
  // Check if enrichment actually changed the BOM
  const wasEnriched = result.enrichedBom !== bom;
  
  if (wasEnriched) {
    // Push enriched BOM to OCI (overwrites existing)
    //use current month's repository
    try {
      const repositoryName = getMonthlyRepositoryName();
      
      const pushResult = await pushToOci(bomUuid, result.enrichedBom, repositoryName);
      
      // Validate repository name was set
      validateOciPushResult(pushResult, 'enrichment', bomUuid);
      
      // Update database with new BOM reference, status, and repository name
      await updateEnrichmentStatusWithBom(bomUuid, EnrichmentStatus.COMPLETED, pushResult, pushResult.ociRepositoryName);
      
      logger.info({ 
        bomUuid, 
        serialNumber: bom.serialNumber, 
        repositoryName: pushResult.ociRepositoryName
      }, 'Async BOM enrichment completed successfully');
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : String(error);
      logger.error({ bomUuid, error: errorMessage }, 'Failed to push enriched BOM to OCI');
      await updateEnrichmentStatus(bomUuid, EnrichmentStatus.FAILED, errorMessage);
    }
  } else {
    logger.info({ bomUuid, serialNumber: bom.serialNumber }, 'BOM enrichment skipped - no enrichment needed');
    await updateEnrichmentStatus(bomUuid, EnrichmentStatus.COMPLETED);
  }
}

async function updateEnrichmentStatus(
  bomUuid: string, 
  status: EnrichmentStatus, 
  errorMessage?: string
): Promise<void> {
  try {
    const queryText = `
      UPDATE rebom.boms 
      SET meta = jsonb_set(
        jsonb_set(
          jsonb_set(meta, '{enrichmentStatus}', $2::jsonb),
          '{enrichmentTimestamp}', $3::jsonb
        ),
        '{enrichmentError}', $4::jsonb
      ),
      last_updated_date = NOW()
      WHERE uuid = $1
    `;
    
    await runQuery(queryText, [
      bomUuid,
      JSON.stringify(status),
      JSON.stringify(new Date().toISOString()),
      JSON.stringify(errorMessage || null)
    ]);
  } catch (error) {
    logger.error({ bomUuid, status, error }, 'Failed to update enrichment status');
  }
}

export interface EnrichmentTriggerResult {
  triggered: boolean;
  message?: string;
  bomUuid?: string;
}

/**
 * Triggers enrichment for a BOM if conditions are met:
 * 1. enrichmentStatus is FAILED, SKIPPED, or null/undefined
 * 2. enrichmentStatus is PENDING but more time than timeout + grace period has passed since creation
 * 3. If force=true, also triggers on COMPLETED status (for API calls)
 * 
 * @param id - UUID or serial number of the BOM
 * @param org - Organization ID
 * @param force - If true, triggers enrichment even if status is COMPLETED (default: false)
 * @returns EnrichmentTriggerResult indicating if enrichment was triggered
 */
export async function triggerEnrichment(id: string, org: string, force: boolean = false): Promise<EnrichmentTriggerResult> {
  logger.info({ id, org, force }, 'triggerEnrichment called');
  
  const credentials = await getBearCredentials(org);
  if (!credentials) {
    return { triggered: false, message: 'Enrichment not configured (no BEAR integration found in DB or env vars)' };
  }
  
  // Find BOM by UUID or serial number
  let bomResults = await BomRepository.bomById(id);
  if (!bomResults || bomResults.length === 0) {
    bomResults = await BomRepository.bomBySerialNumber(id, org);
  }
  
  if (!bomResults || bomResults.length === 0) {
    return { triggered: false, message: `BOM not found: ${id}` };
  }
  
  const bomRecord = bomResults[0];
  const enrichmentStatus = bomRecord.meta?.enrichmentStatus;
  const createdDate = new Date(bomRecord.created_date);
  const now = new Date();
  const timeSinceCreation = now.getTime() - createdDate.getTime();
  const staleThreshold = ENRICHMENT_TIMEOUT_MS + ENRICHMENT_GRACE_PERIOD_MS;
  
  // Check if enrichment should be triggered
  let shouldTrigger = false;
  let reason = '';
  
  if (!enrichmentStatus || enrichmentStatus === EnrichmentStatus.FAILED || enrichmentStatus === EnrichmentStatus.SKIPPED) {
    shouldTrigger = true;
    reason = `Status is ${enrichmentStatus || 'null'}`;
  } else if (enrichmentStatus === EnrichmentStatus.PENDING && timeSinceCreation > staleThreshold) {
    shouldTrigger = true;
    reason = `Status is PENDING and ${Math.round(timeSinceCreation / 1000)}s elapsed (threshold: ${Math.round(staleThreshold / 1000)}s)`;
  } else if (enrichmentStatus === EnrichmentStatus.PENDING) {
    return { 
      triggered: false, 
      message: `Enrichment already pending (${Math.round(timeSinceCreation / 1000)}s elapsed, threshold: ${Math.round(staleThreshold / 1000)}s)`,
      bomUuid: bomRecord.uuid 
    };
  } else if (enrichmentStatus === EnrichmentStatus.COMPLETED) {
    if (force) {
      shouldTrigger = true;
      reason = 'Force re-enrichment requested (status was COMPLETED)';
    } else {
      return { triggered: false, message: 'Enrichment already completed', bomUuid: bomRecord.uuid };
    }
  }
  
  if (!shouldTrigger) {
    return { triggered: false, message: 'Enrichment conditions not met', bomUuid: bomRecord.uuid };
  }
  
  logger.info({ bomUuid: bomRecord.uuid, reason, force }, 'Triggering enrichment');
  
  // For forced re-enrichment, we need to re-process from raw artifacts
  if (force) {
    reprocessAndEnrichAsync(bomRecord, org, credentials).catch(err => {
      logger.error({ err, bomUuid: bomRecord.uuid }, 'Forced re-enrichment failed');
    });
  } else {
    // Normal enrichment - fetch current augmented BOM (validate with processedFileDigest)
    const storedRepositoryName = extractRepositoryNameFromBom(bomRecord);
    const expectedDigest = bomRecord.meta?.processedFileDigest;
    const bomContent = await fetchFromOci(bomRecord.uuid, storedRepositoryName, expectedDigest);
    enrichBomAsync(bomRecord.uuid, bomContent, org).catch(err => {
      logger.error({ err, bomUuid: bomRecord.uuid }, 'Async enrichment trigger failed');
    });
  }
  
  return { triggered: true, message: reason, bomUuid: bomRecord.uuid };
}

/**
 * Re-processes a BOM from raw artifacts and performs enrichment.
 * For CycloneDX: pulls raw artifact (uuid-raw), re-augments, then enriches.
 * For SPDX: pulls raw SPDX, re-converts, re-augments, then enriches.
 * Only pushes the final enriched artifact.
 */
async function reprocessAndEnrichAsync(bomRecord: BomRecord, org: string, credentials?: { bearUri: string; bearApiKey: string; skipPatterns: string[] }): Promise<void> {
  const bomUuid = bomRecord.uuid;
  const sourceFormat = bomRecord.source_format;
  const sourceSpdxUuid = bomRecord.source_spdx_uuid;
  
  logger.info({ bomUuid, sourceFormat, sourceSpdxUuid }, 'Starting forced re-enrichment from raw artifacts');
  
  try {
    let bomToEnrich: any;
    
    if (sourceFormat === 'SPDX' && sourceSpdxUuid) {
      // SPDX-sourced BOM - fetch raw SPDX and re-convert
      bomToEnrich = await reprocessSpdxBom(bomRecord, sourceSpdxUuid, org);
    } else {
      // CycloneDX BOM - fetch raw and re-augment
      bomToEnrich = await reprocessCycloneDxBom(bomRecord);
    }
    
    if (!bomToEnrich) {
      await updateEnrichmentStatus(bomUuid, EnrichmentStatus.FAILED, 'Failed to reprocess raw artifact');
      return;
    }
    
    // Now perform enrichment - resolve credentials if not passed
    const creds = credentials || await getBearCredentials(org);
    if (!creds) {
      await updateEnrichmentStatus(bomUuid, EnrichmentStatus.FAILED, 'No BEAR credentials available');
      return;
    }
    const result = await enrichCycloneDxBom(bomToEnrich, creds.bearUri, creds.bearApiKey, creds.skipPatterns, bomUuid);
    
    if (!result.success) {
      await updateEnrichmentStatus(bomUuid, EnrichmentStatus.FAILED, result.error);
      return;
    }
    
    // Push only the final enriched BOM
    // SIMPLIFIED: Always use current month's repository for better backup rotation
    const repositoryName = getMonthlyRepositoryName();
    
    const pushResult = await pushToOci(bomUuid, result.enrichedBom, repositoryName);
    
    // Validate repository name was set
    if (!pushResult.ociRepositoryName) {
      throw new OciStorageError('Re-enrichment OCI push succeeded but repository name is missing', 'push', bomUuid);
    }
    
    await updateEnrichmentStatusWithBom(bomUuid, EnrichmentStatus.COMPLETED, pushResult, pushResult.ociRepositoryName);
    
    logger.info({ bomUuid }, 'Forced re-enrichment completed successfully');
    
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    logger.error({ bomUuid, error: errorMessage }, 'Forced re-enrichment failed');
    await updateEnrichmentStatus(bomUuid, EnrichmentStatus.FAILED, errorMessage);
  }
}

/**
 * Reprocesses a CycloneDX BOM from its raw artifact.
 * Fetches uuid-raw, applies augmentation, returns BOM ready for enrichment.
 */
async function reprocessCycloneDxBom(bomRecord: BomRecord): Promise<any | null> {
  try {
    const storedRepositoryName = extractRepositoryNameFromBom(bomRecord);
    const expectedDigest = bomRecord.meta?.originalFileDigest;
    const rawBom = await fetchRawBomWithFallback(bomRecord.uuid, storedRepositoryName, fetchFromOci, expectedDigest);
    
    // Re-augment the BOM with component context
    const rebomOptions = bomRecord.meta;
    const augmentedBom = augmentBomForStorage(rawBom, rebomOptions, new Date());
    
    logger.debug({ bomUuid: bomRecord.uuid }, 'CycloneDX BOM reprocessed (augmented)');
    return augmentedBom;
    
  } catch (error) {
    logger.error({ 
      bomUuid: bomRecord.uuid, 
      error: error instanceof Error ? error.message : String(error) 
    }, 'Failed to fetch/reprocess raw CycloneDX BOM');
    return null;
  }
}

/**
 * Reprocesses an SPDX-sourced BOM from its raw SPDX artifact.
 * Fetches raw SPDX, re-converts to CycloneDX, returns BOM ready for enrichment.
 */
async function reprocessSpdxBom(bomRecord: BomRecord, spdxUuid: string, org: string): Promise<any | null> {
  try {
    logger.debug({ spdxUuid, bomUuid: bomRecord.uuid }, 'Fetching raw SPDX BOM for reprocessing');
    
    // Fetch the SPDX record to get OCI reference
    const spdxRecord = await SpdxRepository.findSpdxBomById(spdxUuid, org);
    if (!spdxRecord || !spdxRecord.oci_response) {
      logger.error({ spdxUuid }, 'SPDX record not found or missing OCI response');
      return null;
    }
    
    // Fetch raw SPDX content from OCI
    const fetchId = spdxRecord.oci_response.ociResponse?.digest || spdxRecord.uuid;
    const spdxRepositoryName = extractRepositoryNameFromSpdxOciResponse(spdxRecord.oci_response);
    // Use file_sha256 field as authoritative source for SPDX digest validation
    const spdxDigest = spdxRecord.file_sha256;
    const spdxContent = await fetchFromOci(fetchId, spdxRepositoryName, spdxDigest);
    
    // Re-convert SPDX to CycloneDX
    const conversionResult = await SpdxService.convertSpdxToCycloneDx(spdxContent);
    if (!conversionResult.success) {
      logger.error({ spdxUuid, error: conversionResult.error }, 'SPDX re-conversion failed');
      return null;
    }
    
    logger.debug({ bomUuid: bomRecord.uuid }, 'SPDX BOM reprocessed (converted to CycloneDX)');
    return conversionResult.convertedBom;
    
  } catch (error) {
    logger.error({ 
      spdxUuid, 
      bomUuid: bomRecord.uuid, 
      error: error instanceof Error ? error.message : String(error) 
    }, 'Failed to fetch/reprocess raw SPDX BOM');
    return null;
  }
}

async function updateEnrichmentStatusWithBom(
  bomUuid: string,
  status: EnrichmentStatus,
  oasResponse: any,
  repositoryName?: string
): Promise<void> {
  try {
    // Update enrichment status in meta and repository name in bom field
    // Repository name is stored in bom.ociRepositoryName (OASResponse), not in meta
    // This ensures extractRepositoryNameFromBom() can find it correctly
    
    // If repository name is provided, update it in the OASResponse
    let updatedOasResponse = oasResponse;
    if (repositoryName) {
      updatedOasResponse = {
        ...oasResponse,
        ociRepositoryName: repositoryName
      };
    }
    
    const queryText = `
      UPDATE rebom.boms 
      SET 
        bom = $2,
        meta = jsonb_set(
          jsonb_set(
            jsonb_set(
              jsonb_set(
                jsonb_set(meta, '{enrichmentStatus}', $3::jsonb),
                '{enrichmentTimestamp}', $4::jsonb
              ),
              '{enrichmentError}', $5::jsonb
            ),
            '{processedFileDigest}', $6::jsonb
          ),
          '{processedFileSize}', $7::jsonb
        ),
        last_updated_date = NOW()
      WHERE uuid = $1
    `;
    
    await runQuery(queryText, [
      bomUuid,
      updatedOasResponse,
      JSON.stringify(status),
      JSON.stringify(new Date().toISOString()),
      JSON.stringify(null),
      JSON.stringify(oasResponse.fileSHA256Digest || null),
      JSON.stringify(oasResponse.originalSize || null)
    ]);
    
    if (repositoryName) {
      logger.debug({ bomUuid, repositoryName }, 'Updated OCI repository name in bom field during enrichment');
    }
  } catch (error) {
    logger.error({ bomUuid, status, error }, 'Failed to update enrichment status with BOM');
  }
}
