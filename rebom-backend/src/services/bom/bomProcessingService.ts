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
  resolveAndFetchRawBom,
  fetchProcessedBomWithRetry
} from '../oci';
import * as BomRepository from '../../bomRepository';
import * as SpdxRepository from '../../spdxRepository';
import { getBearCredentials, getBearIntegration } from '../integrationService';
import { SpdxService } from '../spdx';
import { downgradeCycloneDxSpecIfNeeded } from '../cyclonedx/cdxSpecDowngrade';
import { effectiveSkipPatterns } from './enrichmentSkipPatterns';
import { SPDX as CDXSpdx } from '@cyclonedx/cyclonedx-library';
const canonicalize = require('canonicalize');
import { createHash } from 'crypto';
import { v4 as uuidv4 } from 'uuid';

/** Stamped on every enrichment entry so a bad ruleset can be found by its runs. */
const ENRICHER_VERSION = process.env.REBOM_VERSION || require('../../../package.json').version;
import * as fs from 'fs';

// Enrichment timeout constant - used by both enrichCycloneDxBom and triggerEnrichment
const ENRICHMENT_TIMEOUT_MS = 1800000; // 30 minutes timeout
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

  // metadata.component.type is required by CycloneDX. Some scanners
  // emit a BOM with no metadata.component at all (or with one missing
  // the type field) — without this fallback those BOMs fail downstream
  // schema validation, e.g. Dependency-Track rejects with
  // `$.metadata.component.type: does not have a value in the
  // enumeration [...]`. Existing type wins; "application" is the
  // safest default for a release-level BOM (the BOM identifies a
  // component the org ships, not a library it consumes).
  const existingType = (bom.metadata && bom.metadata.component && bom.metadata.component.type) || undefined;
  newMetadata.component = {
    ...newMetadata.component,
    type: existingType || 'application',
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
 * Marks the external reference that points a processed document back at the
 * document it was derived from. rebom writes it and rebom recognises it, so a
 * refresh replaces the entry it finds instead of appending a second one.
 */
export const PRODUCER_BOM_REFERENCE_COMMENT = 'Source document as uploaded by the producer';

function isProducerBomReference(ref: any): boolean {
  return !!ref && ref.type === 'bom' && ref.comment === PRODUCER_BOM_REFERENCE_COMMENT;
}

/**
 * A CycloneDX BOM-Link for one document: `urn:cdx:<serial>/<version>`.
 *
 * Returns null when there is no serial to point at. A link we cannot build
 * honestly is a link we do not write.
 */
export function bomLink(serialNumber?: string | null, version?: unknown): string | null {
  if (typeof serialNumber !== 'string' || !serialNumber) return null;
  const uuid = serialNumber.replace(/^urn:uuid:/, '');
  const parsed = parseInt(String(version ?? ''), 10);
  // CycloneDX treats an absent version as 1, and BOM-Link has no form without one.
  return `urn:cdx:${uuid}/${Number.isFinite(parsed) && parsed > 0 ? parsed : 1}`;
}

/** The producer link a document already carries, if rebom wrote one. */
export function producerBomLink(bom: any): string | null {
  const refs = bom?.externalReferences;
  if (!Array.isArray(refs)) return null;
  const ref = refs.find(isProducerBomReference);
  return typeof ref?.url === 'string' ? ref.url : null;
}

/**
 * Give a document rebom is about to push its own CycloneDX identity.
 *
 * serial + version is the identity of ONE CycloneDX document. rebom writes more
 * than one document per upload -- the producer's bytes, the augmented copy, and
 * a further copy for every enrichment run -- and until this existed they all
 * claimed the producer's identity. A BOM-Link to that identity was ambiguous, a
 * cached augmented copy was indistinguishable from a later enrichment of it, and
 * a verifier checking the augmented download against the producer's signature
 * failed on a document the producer never claimed.
 *
 * `version` is deliberately left alone: rearm-core reads the artifact's latest
 * version out of the processed document body, so a processed copy cannot carry a
 * version counter of its own. A fresh serial is spec-correct without one --
 * version is only meaningful within a serial.
 *
 * @param bom document about to be pushed
 * @param sourceLink BOM-Link of the document this one was derived from. Omit to
 *        derive it: the link the document already carries, else the document's
 *        own identity, which is the right answer on the first pass because the
 *        document still carries the producer's serial. Pass null to write no
 *        link, for a source that has no BOM-Link form (an SPDX upload).
 * @returns a new document; its `serialNumber` is the minted serial
 */
export function mintProcessedSerialNumber(bom: any, sourceLink?: string | null): any {
  if (!bom || typeof bom !== 'object') return bom;

  const link = sourceLink === undefined
    ? (producerBomLink(bom) ?? bomLink(bom.serialNumber, bom.version))
    : sourceLink;

  // Rebuilt rather than mutated: the caller's array may be shared with the raw
  // document, which is never ours to touch.
  const kept = Array.isArray(bom.externalReferences)
    ? bom.externalReferences.filter((ref: any) => !isProducerBomReference(ref))
    : [];
  const externalReferences = link
    ? [...kept, { type: 'bom', url: link, comment: PRODUCER_BOM_REFERENCE_COMMENT }]
    : kept;

  const minted: any = { ...bom, serialNumber: `urn:uuid:${uuidv4()}` };
  if (externalReferences.length || Array.isArray(bom.externalReferences)) {
    minted.externalReferences = externalReferences;
  }
  return minted;
}

/**
 * Fully augments a BOM with component context and rebom tool information.
 * This is a convenience function that combines augmentBomWithComponentContext
 * and attachRebomToolToBom in a single call.
 * 
 * Use this when preparing a BOM for storage that should include full augmentation.
 *
 * Deliberately does NOT mint a serialNumber. Augmentation is not what makes a
 * stored copy a different document from the producer's -- processing,
 * deduplication and the dependency fixes upstream of it already did that -- so
 * the identity is minted where the document is pushed (see
 * mintProcessedSerialNumber and its callers), not here. Tying the two together
 * would mean turning augmentation off silently republished a deduplicated
 * document under the producer's serial.
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

/**
 * Fix the narrow case where an upstream source (notably npm) emits a license
 * name like "CC BY-SA 4.0" inside `{expression: ...}` alongside the properly
 * resolved `{license: {id: "CC-BY-SA-4.0"}}`. The CycloneDX `licenses`
 * `oneOf` accepts an array of license-objects OR a single expression — never
 * a mix — so the BOM fails both branches.
 *
 * Convert any expression that resolves to a known SPDX id via fixupSpdxId
 * (retried with whitespace-to-dash for the npm variant) into a license
 * object, then dedupe by `license.id`. We deliberately do NOT touch
 * compound expressions ("MIT OR Apache-2.0"), do NOT dedupe by `name`
 * (freeform — siblings may differ in url/text), and do NOT try to reconcile
 * mixed expression+license arrays beyond what dedupe-by-id resolves.
 * Anything ambiguous falls through to validation as before.
 */
export function normalizeLicenses(licenses: any): any[] | undefined {
  if (!Array.isArray(licenses) || licenses.length === 0) return licenses;

  const converted = licenses.map((entry: any) => {
    if (entry && typeof entry === 'object') {
      // A bare license expression that resolves to a single SPDX id -> {license:{id}}.
      if (typeof entry.expression === 'string') {
        const expr = entry.expression;
        const fixedId = CDXSpdx.fixupSpdxId(expr) ?? CDXSpdx.fixupSpdxId(expr.replace(/\s+/g, '-'));
        if (fixedId) return { license: { id: fixedId } };
        return entry;
      }
      // license.id must be a recognized SPDX id — Dependency-Track strict-validates
      // it against the SPDX enum, so a non-SPDX value (e.g. "LGPL", "Apache 2.0", a
      // proprietary string) fails the whole BOM upload. Canonicalize the casing of a
      // valid id; otherwise move the value to the freeform name field (unconstrained,
      // always valid). Mirrors the backend CdxLicenseUtil emit-time guard.
      const lic = entry.license;
      if (lic && typeof lic === 'object' && typeof lic.id === 'string') {
        const fixedId = CDXSpdx.fixupSpdxId(lic.id);
        if (fixedId) {
          const { name, ...rest } = lic;   // drop a conflicting name; a valid id wins
          return { license: { ...rest, id: fixedId } };
        }
        const { id, ...rest } = lic;
        return { license: { ...rest, name: typeof lic.name === 'string' && lic.name ? lic.name : lic.id } };
      }
    }
    return entry;
  });

  const seenIds = new Set<string>();
  const out: any[] = [];
  for (const entry of converted) {
    const id = entry?.license?.id;
    if (typeof id === 'string') {
      if (seenIds.has(id)) continue;
      seenIds.add(id);
    }
    out.push(entry);
  }
  return out;
}

export function normalizeLicensesInBom(bom: any): any {
  if (!bom || typeof bom !== 'object') return bom;

  const walkComponents = (comps: any) => {
    if (!Array.isArray(comps)) return;
    for (const c of comps) {
      if (!c || typeof c !== 'object') continue;
      if (c.licenses) c.licenses = normalizeLicenses(c.licenses);
      if (c.components) walkComponents(c.components);
    }
  };

  walkComponents(bom.components);
  walkComponents(bom.services);
  if (bom.metadata?.component) {
    if (bom.metadata.component.licenses) {
      bom.metadata.component.licenses = normalizeLicenses(bom.metadata.component.licenses);
    }
    walkComponents(bom.metadata.component.components);
  }
  if (bom.metadata?.licenses) {
    bom.metadata.licenses = normalizeLicenses(bom.metadata.licenses);
  }
  return bom;
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

  // Build set of known component refs to detect self-referential dependency entries.
  // These are entries in dependencies whose ref is not in the components list
  // (e.g. the Maven JAR of the app itself: pkg:maven/io.reliza/rearm-pro-backend@26.04.x?type=jar)
  // which cdxgen adds for container image BOMs but does not include in the components array.
  const componentRefSet = new Set<string>(
    (bom["components"] || []).map((c: any) => c['bom-ref'] || c.purl || '').filter((r: string) => r !== '')
  )
  for (const dep of bomForDigest["dependencies"]) {
    if (dep.ref !== ROOT_PLACEHOLDER && !componentRefSet.has(dep.ref)) {
      // Self-referential entry: normalize by stripping the version from the purl
      // (version is the segment between @ and ? or end-of-string)
      dep.ref = dep.ref.replace(/@[^?#]*/, '')
    }
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
    
    // Built-in skip patterns (unresolvable purl types) merged with the
    // org-configured ones — see enrichmentSkipPatterns.ts for why.
    const patterns = effectiveSkipPatterns(skipPatterns);
    for (const pattern of patterns) {
      args.push('--skipPattern', pattern);
    }
    logger.debug({ skipPatterns: patterns }, 'Added skip patterns to enrichment command');

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
 *
 * ENRICHMENT_PENDING_IF_NOT_CONFIGURED=true forces this to true as well, mirroring
 * getInitialEnrichmentStatus: the flag means "treat enrichment as configured and
 * pending" for environments where BEAR will be wired up later. The synthetic
 * Dependency-Track gate then holds components back (they never enrich, so
 * enriched_at is never stamped) instead of shipping them un-enriched — letting the
 * gate be exercised before a real BEAR integration exists.
 */
export async function isEnrichmentConfigured(org: string): Promise<boolean> {
  if (process.env.ENRICHMENT_PENDING_IF_NOT_CONFIGURED === 'true') {
    return true;
  }
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
 * The producer's document identity for a row, as a BOM-Link.
 *
 * Read off the document when it already carries one -- every processed document
 * rebom has written since serials diverged does. A legacy row's processed copy
 * does not, but it still carries the producer's own serial, so for a CycloneDX
 * upload that document IS the answer. An SPDX upload has no CycloneDX document
 * to point at and gets no link rather than an invented one.
 */
export async function resolveProducerLink(bomUuid: string, bom: any): Promise<string | null> {
  const carried = producerBomLink(bom);
  if (carried) return carried;
  try {
    const row: any = (await BomRepository.bomById(bomUuid))?.[0];
    if (!row) return null;
    if (row.source_format === 'SPDX' || row.source_spdx_uuid) return null;
    return bomLink(row.meta?.serialNumber, row.meta?.bomVersion);
  } catch (error) {
    logger.warn({ bomUuid, error },
      'Could not resolve the producer BOM-Link; the enriched document will carry no source reference');
    return null;
  }
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

  // Defensive shim: an artifact uploaded *before* the addCycloneDxBom shim
  // deployed has its canonical OCI copy stored at an unsupported spec
  // (e.g. 1.7), and the enrichment scheduler re-queries FAILED rows every
  // cycle — without this line the BEAR call would fail "invalid
  // specification version" forever and the FAILED→retry→FAILED loop never
  // clears. Applying the shim here means one successful cycle rewrites the
  // canonical OCI copy at the supported spec, so the next scheduler run
  // sees a 1.6 BOM and the loop self-heals. No-op when the BOM is already
  // at a supported spec (covers new uploads that were shimmed on ingest).
  downgradeCycloneDxSpecIfNeeded(bom);

  // Perform enrichment
  const result = await enrichCycloneDxBom(bom, credentials.bearUri, credentials.bearApiKey, credentials.skipPatterns, bomUuid);
  
  if (!result.success) {
    // Enrichment failed (timeout or other error)
    await updateEnrichmentStatus(bomUuid, EnrichmentStatus.FAILED, result.error);
    return;
  }
  
  // Check if enrichment actually changed the BOM
  const wasEnriched = result.enrichedBom !== bom;
  
  // Reserve first. Without a sequence there is no tag to push to, and the only
  // tag available without one is the bare uuid -- which is the in-place
  // overwrite this whole change exists to remove. A run that cannot reserve
  // does not run: it is marked FAILED and the next cycle retries it.
  let sequence: number;
  try {
    sequence = await reserveEnrichmentRun(bomUuid, 'scheduler');
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    logger.error({ bomUuid, error: errorMessage }, 'Could not reserve an enrichment sequence; skipping the run');
    await updateEnrichmentStatus(bomUuid, EnrichmentStatus.FAILED,
      `Could not reserve an enrichment sequence: ${errorMessage}`);
    return;
  }

  if (wasEnriched) {
    // A NEW tag every time. Overwriting the bytes at <uuid> is what let a
    // reader validate fresh bytes against the digest it had already read.
    try {
      const repositoryName = getMonthlyRepositoryName();
      const tag = enrichmentTag(bomUuid, sequence);

      // A new tag holds a new document, and a new document gets a new identity
      // -- otherwise the second enrichment reintroduces the ambiguity the first
      // one resolved. Resolved off the pre-enrichment document, which is what
      // the row currently points at, so a BEAR run that drops the reference
      // cannot lose the provenance.
      const enrichedBom = mintProcessedSerialNumber(result.enrichedBom,
        await resolveProducerLink(bomUuid, bom));
      const pushResult = await pushToOci(tag, enrichedBom, repositoryName);

      // Validate repository name was set
      validateOciPushResult(pushResult, 'enrichment', bomUuid);

      // Pointer and history move together, in one write: a crash between them
      // would leave the row pointing at -e<n> while its entry still read
      // RUNNING, breaking the invariant that the pointer is the last COMPLETED
      // entry -- which is the thing retention will read.
      await updateEnrichmentStatusWithBom(bomUuid, EnrichmentStatus.COMPLETED, pushResult,
        pushResult.ociRepositoryName, tag, sequence, enrichedBom.serialNumber);

      logger.info({ 
        bomUuid, 
        serialNumber: enrichedBom.serialNumber,
        producerSerialNumber: bom.serialNumber,
        tag,
        repositoryName: pushResult.ociRepositoryName
      }, 'Async BOM enrichment completed successfully');
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : String(error);
      logger.error({ bomUuid, error: errorMessage }, 'Failed to push enriched BOM to OCI');
      await updateEnrichmentStatus(bomUuid, EnrichmentStatus.FAILED, errorMessage);
      // A failed run closes its own entry and moves no pointer.
      await closeEnrichmentRun(bomUuid, sequence, { status: 'FAILED', error: errorMessage });
    }
  } else {
    logger.info({ bomUuid, serialNumber: bom.serialNumber }, 'BOM enrichment skipped - no enrichment needed');
    await updateEnrichmentStatus(bomUuid, EnrichmentStatus.COMPLETED);
    // Completed, pushed nothing: the entry has no tag, which is the difference
    // between "ran and changed nothing" and "never ran". No pointer to move.
    await closeEnrichmentRun(bomUuid, sequence, { status: 'COMPLETED', error: null });
  }
}

/**
 * Reserve the next enrichment sequence for a row, appending its RUNNING entry.
 *
 * The sequence has to exist before the push, because it names the tag the bytes
 * go to. Two schedulers reserving at once must not mint the same tag, so the
 * number is read and written inside one UPDATE: postgres serialises the two
 * statements on the row and the second sees the first's entry. A reservation
 * that never completes stays as a RUNNING entry, which is the honest record of
 * a run that died mid-flight.
 *
 * Legacy rows get entry 0 synthesised here from the fields they already carry,
 * so the history explains every artifact the row has ever pointed at rather
 * than starting mid-story.
 */
/** A run that has not reported back in this long is not coming back. */
const ABANDON_RUNNING_AFTER = '6 hours';

/**
 * Mark runs that started and never reported back, across the table.
 *
 * Swept by the enrichment scheduler rather than at reservation time. Ageing at
 * reservation only ever tidied rows that get ANOTHER run, which are the rows
 * that need it least -- a row whose single run died never reserves again, and
 * its entry would have stayed RUNNING for ever. It is also not the reservation's
 * job: that statement's correctness argument is narrow enough without carrying
 * housekeeping beside it.
 *
 * Bounded by LIMIT so one cycle cannot turn into a long write, and idempotent,
 * so whatever is left over is picked up next time. Six hours rather than minutes
 * because two schedulers working the same row at once is a legitimate state: a
 * run is only abandoned when no plausible reading has it still alive.
 */
export async function abandonStaleEnrichmentRuns(limit = 200): Promise<number> {
  const staleEntry = `
    SELECT 1 FROM jsonb_array_elements(b.meta->'enrichments') e
    WHERE e->>'status' = 'RUNNING'
      AND (e->>'startedAt')::timestamptz < NOW() - INTERVAL '${ABANDON_RUNNING_AFTER}'`;
  const queryText = `
    WITH stale AS (
      SELECT b.uuid
      FROM rebom.boms b
      WHERE jsonb_typeof(b.meta->'enrichments') = 'array'
        AND EXISTS (${staleEntry})
      LIMIT $1
    )
    UPDATE rebom.boms b
    SET meta = jsonb_set(b.meta, '{enrichments}', (
          SELECT jsonb_agg(
            CASE WHEN e->>'status' = 'RUNNING'
                  AND (e->>'startedAt')::timestamptz < NOW() - INTERVAL '${ABANDON_RUNNING_AFTER}'
                 THEN e || jsonb_build_object('status', 'ABANDONED',
                        'error', 'run did not report back within ${ABANDON_RUNNING_AFTER}')
                 ELSE e END)
          FROM jsonb_array_elements(b.meta->'enrichments') e)),
        last_updated_date = NOW()
    FROM stale
    WHERE b.uuid = stale.uuid
  `;
  try {
    const res = await runQuery(queryText, [limit]);
    const count = res.rowCount || 0;
    if (count) {
      logger.warn({ rows: count },
        'Marked enrichment runs abandoned: they started and never reported back');
    }
    return count;
  } catch (error) {
    logger.error({ error }, 'Could not age out stale enrichment runs');
    return 0;
  }
}

export async function reserveEnrichmentRun(
  bomUuid: string,
  source: 'scheduler' | 'on-upload' | 'manual'
): Promise<number> {
  // One statement, and every expression in it reads the TARGET row's own
  // columns. That is what makes it safe under concurrency, and it is a narrow
  // property worth stating: under READ COMMITTED, when a concurrent statement
  // has updated the target row, Postgres re-evaluates the command against the
  // NEW version of that row -- but only for the target. Rows pulled in by any
  // other scan in the same statement, including a CTE or a self-join over this
  // same table, keep the snapshot they were read with. An earlier version of
  // this query computed the run list in a CTE for readability, which meant two
  // schedulers reserving at once both read the pre-update list, both minted the
  // same sequence, and the second write dropped the first's entry -- exactly
  // the collision the reservation exists to prevent.
  //
  // So the CASE is inlined twice rather than named once. The duplication is the
  // price of correctness here; do not refactor it into a CTE.
  const runsExpr = `
    CASE
      WHEN jsonb_typeof(meta->'enrichments') = 'array' THEN meta->'enrichments'
      ELSE jsonb_build_array(jsonb_strip_nulls(jsonb_build_object(
             'sequence', 0,
             'tag', COALESCE(meta->>'processedTag', uuid::text),
             'repository', bom->>'ociRepositoryName',
             'digest', meta->>'processedFileDigest',
             'size', meta->'processedFileSize',
             -- Absent on a row uploaded before processed documents carried
             -- their own identity, and stripped rather than written as null:
             -- on those rows the upload's serial is the producer's, and it is
             -- already in meta.serialNumber.
             'serialNumber', meta->>'processedSerialNumber',
             'status', 'COMPLETED',
             'source', 'on-upload')))
    END`;
  const queryText = `
    UPDATE rebom.boms
    SET meta = jsonb_set(meta, '{enrichments}',
          (${runsExpr}) || jsonb_build_object(
            'sequence', jsonb_array_length(${runsExpr}),
            -- The tag is written NOW, not at close. A run that dies after its
            -- push has still created an artifact, and if its name only appeared
            -- on completion nothing would know that artifact exists -- which is
            -- a hole in the one thing this history is for. Built from the same
            -- length as the sequence so the two cannot disagree.
            'tag', $1::text || '-e' || jsonb_array_length(${runsExpr})::text,
            'status', 'RUNNING',
            'startedAt', $2::text,
            'source', $3::text,
            'enricherVersion', $4::text)),
        last_updated_date = NOW()
    WHERE uuid = $1
    RETURNING jsonb_array_length(meta->'enrichments') - 1 AS sequence
  `;
  const res = await runQuery(queryText, [bomUuid, new Date().toISOString(), source, ENRICHER_VERSION]);
  const sequence = res.rows[0]?.sequence;
  if (typeof sequence !== 'number') {
    throw new BomStorageError('Enrichment sequence reservation returned no sequence', undefined,
      { bomId: bomUuid, operation: 'reserveEnrichmentRun' });
  }
  return sequence;
}

/** Close the reserved entry, whatever happened, without touching the pointer. */
async function closeEnrichmentRun(
  bomUuid: string,
  sequence: number,
  fields: Record<string, unknown>
): Promise<void> {
  const queryText = `
    UPDATE rebom.boms
    SET meta = jsonb_set(meta, ARRAY['enrichments', $2::text],
          COALESCE(meta->'enrichments'->$3::int, '{}'::jsonb) || $4::jsonb),
        last_updated_date = NOW()
    WHERE uuid = $1 AND jsonb_typeof(meta->'enrichments') = 'array'
  `;
  try {
    await runQuery(queryText, [bomUuid, String(sequence), sequence,
      JSON.stringify({ completedAt: new Date().toISOString(), ...fields })]);
  } catch (error) {
    logger.error({ bomUuid, sequence, error }, 'Failed to close the enrichment run entry');
  }
}

/** The tag an enrichment run writes to. Never the bare uuid: that artifact is somebody's current. */
function enrichmentTag(bomUuid: string, sequence: number): string {
  return `${bomUuid}-e${sequence}`;
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
    // Normal enrichment - fetch current augmented BOM. Race-tolerant: a manual
    // trigger's most likely collision is ANOTHER enrichment finishing its push
    // just before its row update lands.
    const bomContent = await fetchProcessedBomWithRetry(bomRecord);
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
    
    // Push only the final enriched BOM, to its own tag in the current month's
    // repository. The artifact the row points at right now is left alone.
    const repositoryName = getMonthlyRepositoryName();
    // Same rule as the scheduler path: no sequence, no run. The catch below
    // marks it FAILED -- it never falls back to overwriting the bare uuid.
    const sequence = await reserveEnrichmentRun(bomUuid, 'manual');
    const tag = enrichmentTag(bomUuid, sequence);

    // Its own identity, same as every other pushed document. The source link is
    // taken from the row rather than derived: this path rebuilt the document
    // from the raw artifact, and for an SPDX upload that artifact is SPDX --
    // there is no CycloneDX document to link to.
    const enrichedBom = mintProcessedSerialNumber(result.enrichedBom,
      (sourceFormat === 'SPDX' || sourceSpdxUuid)
        ? null
        : bomLink(bomRecord.meta?.serialNumber, bomRecord.meta?.bomVersion));

    let pushResult;
    try {
      pushResult = await pushToOci(tag, enrichedBom, repositoryName);
    } catch (error) {
      await closeEnrichmentRun(bomUuid, sequence, {
        status: 'FAILED', error: error instanceof Error ? error.message : String(error) });
      throw error;
    }

    // Validate repository name was set
    if (!pushResult.ociRepositoryName) {
      throw new OciStorageError('Re-enrichment OCI push succeeded but repository name is missing', 'push', bomUuid);
    }
    
    // Pointer and history in one write; see the scheduler path. If it throws,
    // the artifact exists and the row still points elsewhere: close the entry
    // FAILED so the history says so, and let the outer catch mark the run.
    try {
      await updateEnrichmentStatusWithBom(bomUuid, EnrichmentStatus.COMPLETED, pushResult,
        pushResult.ociRepositoryName, tag, sequence, enrichedBom.serialNumber);
    } catch (error) {
      await closeEnrichmentRun(bomUuid, sequence, {
        status: 'FAILED', error: error instanceof Error ? error.message : String(error) });
      throw error;
    }
    
    logger.info({ bomUuid, tag, serialNumber: enrichedBom.serialNumber },
      'Forced re-enrichment completed successfully');
    
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
    const rawBom = await resolveAndFetchRawBom(bomRecord);

    // Apply the same 1.7→1.6 downgrade shim that addCycloneDxBom uses on
    // ingest. Forced re-enrichment is the recovery path for artifacts that
    // were uploaded *before* the shim deployed and got stuck in the
    // FAILED-enrichment loop because their canonical copy is still 1.7.
    // Mutating in place is safe — `rawBom` was just fetched fresh from OCI.
    downgradeCycloneDxSpecIfNeeded(rawBom);

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

/**
 * Move the row onto the artifact this run produced: pointer, digest, size,
 * repository, serial AND the run's history entry, in one statement.
 *
 * The history entry is closed here rather than in a following write because the
 * two have to agree. A crash between them would leave processedTag at
 * <uuid>-e<n> with that entry still RUNNING, and the invariant retention will
 * read -- the pointer is the last COMPLETED entry -- would be quietly false.
 */
export async function updateEnrichmentStatusWithBom(
  bomUuid: string,
  status: EnrichmentStatus,
  oasResponse: any,
  repositoryName?: string,
  processedTag?: string,
  sequence?: number,
  processedSerialNumber?: string
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
                       jsonb_set(
                         jsonb_set(
                           jsonb_set(meta, '{enrichmentStatus}', $3::jsonb),
                           '{enrichmentTimestamp}', $4::jsonb),
                         '{enrichmentError}', $5::jsonb),
                       '{processedFileDigest}', $6::jsonb),
                     '{processedFileSize}', $7::jsonb),
                   '{processedTag}', $8::jsonb),
                 '{processedSerialNumber}', $9::jsonb),
        last_updated_date = NOW()
      WHERE uuid = $1
    `;
    // With a sequence, the same statement also closes that run's entry, so the
    // pointer and the history it summarises can never disagree.
    const closingQueryText = `
      UPDATE rebom.boms
      SET 
        bom = $2,
        meta = jsonb_set(
                 jsonb_set(
                   jsonb_set(
                     jsonb_set(
                       jsonb_set(
                         jsonb_set(
                           jsonb_set(
                             jsonb_set(meta, '{enrichmentStatus}', $3::jsonb),
                             '{enrichmentTimestamp}', $4::jsonb),
                           '{enrichmentError}', $5::jsonb),
                         '{processedFileDigest}', $6::jsonb),
                       '{processedFileSize}', $7::jsonb),
                     '{processedTag}', $8::jsonb),
                   '{processedSerialNumber}', $9::jsonb),
                 ARRAY['enrichments', $10::text],
                 COALESCE(meta->'enrichments'->$11::int, '{}'::jsonb) || $12::jsonb),
        last_updated_date = NOW()
      WHERE uuid = $1 AND jsonb_typeof(meta->'enrichments') = 'array'
    `;

    const params: any[] = [
      bomUuid,
      updatedOasResponse,
      JSON.stringify(status),
      JSON.stringify(new Date().toISOString()),
      JSON.stringify(null),
      JSON.stringify(oasResponse.fileSHA256Digest || null),
      JSON.stringify(oasResponse.originalSize || null),
      JSON.stringify(processedTag || bomUuid),
      // The serial travels with the pointer for the same reason the digest
      // does: it describes the bytes the row now points at, and a reader
      // comparing the served document against the row has nothing else to
      // check the identity against.
      JSON.stringify(processedSerialNumber || null)
    ];
    if (typeof sequence === 'number') {
      params.push(String(sequence), sequence, JSON.stringify({
        status: 'COMPLETED',
        completedAt: new Date().toISOString(),
        tag: processedTag || bomUuid,
        repository: repositoryName || null,
        digest: oasResponse.fileSHA256Digest || null,
        size: oasResponse.originalSize || null,
        serialNumber: processedSerialNumber || null,
        error: null
      }));
      await runQuery(closingQueryText, params);
    } else {
      await runQuery(queryText, params);
    }
    
    if (repositoryName) {
      logger.debug({ bomUuid, repositoryName }, 'Updated OCI repository name in bom field during enrichment');
    }
  } catch (error) {
    // Rethrown, not swallowed. This write is what makes the pushed artifact the
    // row's current one; if it fails, the artifact exists and nothing points at
    // it. Swallowing meant the caller went on to log the run as completed
    // successfully and to leave its history entry RUNNING for ever -- a false
    // success and a stale record from one failed statement. The callers mark
    // the run FAILED and close its entry.
    logger.error({ bomUuid, status, error }, 'Failed to update enrichment status with BOM');
    throw error;
  }
}
