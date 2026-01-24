import { logger } from '../../logger';
import { RebomOptions, HIERARCHICHAL, EnrichmentStatus } from '../../types';
import { BomValidationError, BomStorageError } from '../../types/errors';
import { PackageURL } from 'packageurl-js';
import { createTempFile, deleteTempFile, shellExec, runQuery } from '../../utils';
import { pushToOci } from '../oci';
const canonicalize = require('canonicalize');
import { createHash } from 'crypto';
import * as fs from 'fs';

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
    if (origPurl) origPurlParsed = PackageURL.fromString(origPurl)
    const type = (origPurlParsed && origPurlParsed.type && origPurlParsed.type !== "container" && origPurlParsed.type !== "application") ? origPurlParsed.type : 'generic'
    const namespace = (origPurlParsed && (origPurlParsed.namespace || type === 'oci')) ? origPurlParsed.namespace : encodeURIComponent(rebomOverride.group)
    const name = (origPurlParsed && origPurlParsed.name && origPurlParsed.name !== 'app' && origPurlParsed.name !== '.') ? origPurlParsed.name : encodeURIComponent(rebomOverride.name)

    const version = rebomOverride.version
    const qualifiers = (origPurlParsed && origPurlParsed.qualifiers) ? origPurlParsed.qualifiers : {}
    if (rebomOverride.belongsTo) qualifiers.belongsTo = rebomOverride.belongsTo
    if (rebomOverride.hash) qualifiers.hash = rebomOverride.hash
    if (rebomOverride.tldOnly) qualifiers.tldOnly = 'true'
    if (rebomOverride.structure && rebomOverride.structure.toLowerCase() === HIERARCHICHAL.toLowerCase()) qualifiers.structure = HIERARCHICHAL
    const purl = new PackageURL(
      type,
      namespace,
      name,
      version,
      qualifiers,
      undefined
    )
    purlStr = purl.toString()
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
    name: "rebom",
    group: "io.reliza",
    version: process.env.npm_package_version,
    supplier: { name: "Reliza Incorporated" },
    description: "Catalog of SBOMs",
    licenses: [
      { license: { id: "MIT" } }
    ],
    externalReferences: [
      { url: "ssh://git@github.com/relizaio/rebom.git", type: "vcs" },
      { url: "https://reliza.io", type: "website" }
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
 * Attaches rebom tool information to a BOM's metadata.
 * This marks the BOM as having been processed by rebom.
 * 
 * Handles both CycloneDX formats:
 * - 1.4 and earlier: metadata.tools is an array
 * - 1.5 and later: metadata.tools is an object with components array
 * 
 * @param finalBom - BOM to attach tool information to
 * @returns BOM with rebom tool information added
 */
export function attachRebomToolToBom(finalBom: any): any {
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
 * Requires BEAR_URI and BEAR_API_KEY environment variables to be set.
 * If either env var is missing, returns the original BOM unchanged.
 * 
 * @param bom - CycloneDX BOM to enrich
 * @returns Enriched BOM, or original BOM if enrichment is skipped/fails
 */
export async function enrichCycloneDxBom(bom: any): Promise<any> {
  const bearUri = process.env.BEAR_URI;
  const bearApiKey = process.env.BEAR_API_KEY;

  if (!bearUri || !bearApiKey) {
    logger.info('BEAR_URI or BEAR_API_KEY not set, skipping BOM enrichment');
    return bom;
  }

  let inputFile: string | null = null;
  let outputFile: string | null = null;

  try {
    logger.info({ serialNumber: bom.serialNumber }, 'Starting CycloneDX BOM enrichment using rearm-cli');

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

    logger.info('Running enrichment: rearm-cli bomutils enrich');
    const result = await shellExec('rearm-cli', args, 600000); // 600 second timeout
    logger.debug({ enrichmentOutput: result }, 'rearm-cli enrichment completed');

    const enrichedContent = await fs.promises.readFile(outputFile, 'utf8');
    const enrichedBom = JSON.parse(enrichedContent);
    logger.info({
      componentCount: enrichedBom?.components?.length || 0,
      serialNumber: enrichedBom?.serialNumber
    }, 'CycloneDX BOM enrichment successful');

    await Promise.all([
      deleteTempFile(inputFile),
      deleteTempFile(outputFile)
    ]);

    return enrichedBom;

  } catch (error) {
    if (inputFile) await deleteTempFile(inputFile);
    if (outputFile) await deleteTempFile(outputFile);

    logger.error({ error: error instanceof Error ? error.message : String(error), serialNumber: bom.serialNumber }, 'CycloneDX BOM enrichment failed, returning original BOM');
    return bom;
  }
}

/**
 * Checks if BEAR enrichment is configured (env vars are set).
 * Used to determine initial enrichment status.
 */
export function isEnrichmentConfigured(): boolean {
  return !!(process.env.BEAR_URI && process.env.BEAR_API_KEY);
}

/**
 * Gets the initial enrichment status based on configuration.
 * Returns PENDING if BEAR is configured, SKIPPED otherwise.
 */
export function getInitialEnrichmentStatus(): EnrichmentStatus {
  return isEnrichmentConfigured() ? EnrichmentStatus.PENDING : EnrichmentStatus.SKIPPED;
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
 */
export async function enrichBomAsync(bomUuid: string, bom: any, org: string): Promise<void> {
  if (!isEnrichmentConfigured()) {
    logger.debug({ bomUuid }, 'Enrichment not configured, skipping async enrichment');
    return;
  }

  try {
    // Perform enrichment
    const enrichedBom = await enrichCycloneDxBom(bom);
    
    // Check if enrichment actually happened (compare with original)
    const wasEnriched = enrichedBom !== bom;
    
    if (wasEnriched) {
      // Push enriched BOM to OCI (overwrites existing)
      const oasResponse = await pushToOci(bomUuid, enrichedBom);
      
      // Update database with new BOM reference and status
      await updateEnrichmentStatusWithBom(bomUuid, EnrichmentStatus.COMPLETED, oasResponse);
      
      logger.info({ bomUuid, serialNumber: bom.serialNumber }, 'Async BOM enrichment completed successfully');
    } else {
      // Enrichment returned original BOM (likely failed silently)
      await updateEnrichmentStatus(bomUuid, EnrichmentStatus.COMPLETED);
      logger.info({ bomUuid }, 'Async BOM enrichment completed (no changes)');
    }

  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    logger.error({ bomUuid, error: errorMessage }, 'Async BOM enrichment failed');
    
    await updateEnrichmentStatus(bomUuid, EnrichmentStatus.FAILED, errorMessage);
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

async function updateEnrichmentStatusWithBom(
  bomUuid: string,
  status: EnrichmentStatus,
  oasResponse: any
): Promise<void> {
  try {
    const queryText = `
      UPDATE rebom.boms 
      SET 
        bom = $2,
        meta = jsonb_set(
          jsonb_set(
            jsonb_set(meta, '{enrichmentStatus}', $3::jsonb),
            '{enrichmentTimestamp}', $4::jsonb
          ),
          '{enrichmentError}', 'null'::jsonb
        ),
        last_updated_date = NOW()
      WHERE uuid = $1
    `;
    
    await runQuery(queryText, [
      bomUuid,
      oasResponse,
      JSON.stringify(status),
      JSON.stringify(new Date().toISOString())
    ]);
  } catch (error) {
    logger.error({ bomUuid, status, error }, 'Failed to update enrichment status with BOM');
  }
}
