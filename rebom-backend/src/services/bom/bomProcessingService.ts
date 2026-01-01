import { logger } from '../../logger';
import { RebomOptions, HIERARCHICHAL } from '../../types';
import { PackageURL } from 'packageurl-js';
const canonicalize = require('canonicalize');
import { createHash } from 'crypto';

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
      throw new Error(`Missing required fields for PURL generation: name=${rebomOverride.name}, version=${rebomOverride.version}, group=${rebomOverride.group}`);
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

export function overrideRootComponent(bom: any, rebomOverride: RebomOptions, lastUpdatedDate?: string | Date): any {
  const newMetadata = { ...bom.metadata };
  const origPurl = (bom.metadata && bom.metadata.component && bom.metadata.component.purl) ? bom.metadata.component.purl : undefined;
  const newPurl = establishPurl(origPurl, rebomOverride);
  logger.debug(`established purl: ${newPurl}`);

  newMetadata.component = {
    ...newMetadata.component,
    purl: newPurl,
    ['bom-ref']: newPurl,
    name: rebomOverride.name,
    version: rebomOverride.version,
    group: rebomOverride.group
  };
  newMetadata['authors'] = [{ name: rebomOverride.group }];
  newMetadata['supplier'] = { name: rebomOverride.group };
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
  if (specVersion === '1.6') {
    rebomTool["authors"] = [{ name: "Reliza Incorporated", email: "info@reliza.io" }];
  } else {
    rebomTool["author"] = "Reliza Incorporated";
  }
  return rebomTool;
}

export function attachRebomToolToBom(finalBom: any): any {
  const rebomTool = createRebomToolObject(finalBom.specVersion);
  if (!finalBom.metadata.tools) finalBom.metadata.tools = { components: [] };
  if (!finalBom.metadata.tools.components) finalBom.metadata.tools.components = [];
  finalBom.metadata.tools.components.push(rebomTool);
  return finalBom;
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

export function computeBomDigest(bom: any, stripBom: string): string {
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
    throw new Error(`Failed to compute hash: ${error instanceof Error ? error.message : 'Unknown error'}`);
  }
}
