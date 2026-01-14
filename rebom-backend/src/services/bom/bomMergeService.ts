import { logger } from '../../logger';
import { BomDto, BomRecord, RebomOptions, BomInput } from '../../types';
import { BomStorageError } from '../../types/errors';
import { findBomObjectById } from './bomCrudService';
import { extractTldFromBom, extractDevFilteredBom, establishPurl, attachRebomToolToBom } from './bomProcessingService';
import validateBom from '../../validateBom';
import { createTmpFiles, deleteTmpFiles, shellExec } from '../../utils';

export async function mergeBoms(ids: string[], rebomOptions: RebomOptions, org: string): Promise<any> {
  try {
    let mergedBom = null
    const bomObjs = await findBomsForMerge(ids, rebomOptions.tldOnly, rebomOptions.ignoreDev || false, org)
    if (bomObjs && bomObjs.length)
      mergedBom = await mergeBomObjects(bomObjs, rebomOptions)
    return mergedBom
  } catch (e) {
    logger.error({ err: e }, "Error During merge")
    throw e
  }
}

export async function mergeAndStoreBoms(ids: string[], rebomOptions: RebomOptions, org: string, addBomFn: (bomInput: BomInput) => Promise<BomRecord>): Promise<BomRecord> {
  try {
    logger.info({ bomIds: ids, serialNumber: rebomOptions.serialNumber, org }, "Starting BOM merge and store operation")
    
    const mergedBom = await mergeBoms(ids, rebomOptions, org)
    logger.debug({ componentCount: mergedBom?.components?.length || 0 }, "BOM merge completed")
    
    const bomInput: BomInput = {
      bomInput: {
        format: 'CYCLONEDX',
        rebomOptions: rebomOptions,
        bom: mergedBom,
        org: org
      }
    }
    const bomRecord = await addBomFn(bomInput)
    logger.info({ bomUuid: bomRecord.uuid, serialNumber: rebomOptions.serialNumber }, "BOM merge and store completed successfully")
    
    return bomRecord
  } catch (e) {
    logger.error({ err: e, bomIds: ids, serialNumber: rebomOptions.serialNumber }, "Error during BOM merge and store")
    throw e
  }
}


async function findBomsForMerge(ids: string[], tldOnly: boolean, ignoreDev: boolean, org: string) {
  logger.info(`findBomsForMerge: ${ids}`)
  let bomRecords = await Promise.all(ids.map(async (id) => findBomObjectById(id, org)))
  let bomObjs: any[] = []
  logger.info(`bomrecords found # ${bomRecords.length}`)
  if (bomRecords && bomRecords.length) {
    bomObjs = bomRecords.map(bomRecord => {
      let processedBom = bomRecord;
      if (tldOnly) {
        processedBom = extractTldFromBom(processedBom);
      }
      if (ignoreDev) {
        processedBom = extractDevFilteredBom(processedBom);
      }
      return processedBom;
    });
  }
  return bomObjs
}

export async function mergeBomObjects(bomObjects: any[], rebomOptions: RebomOptions): Promise<any> {
  let bomPaths: string[] = []
  try {
    // Note: BOMs were already validated when stored, no need to re-validate here
    // The forEach + async pattern was also broken (doesn't actually await)
    
    const purl = establishPurl(undefined, rebomOptions)
    bomPaths = await createTmpFiles(bomObjects)
    const command = ['bomutils', 'merge-boms']
    
    if (rebomOptions.rootComponentMergeMode) {
      command.push('--root-component-merge-mode', rebomOptions.rootComponentMergeMode);
    }
    if (rebomOptions.structure) {
      command.push('--structure', rebomOptions.structure)
    } else {
      command.push('--structure', 'FLAT')
    }
    command.push(
      '--group', rebomOptions.group,
      '--name', rebomOptions.name,
      '--version', rebomOptions.version
    )
    bomPaths.forEach(path => {
      command.push('--input-files', path)
    })
    command.push('--purl', purl)
    const mergeResponse: string = await shellExec('rearm-cli', command)
    
    await deleteTmpFiles(bomPaths)

    const jsonObj = JSON.parse(mergeResponse)
    
    // Ensure required CycloneDX fields are present
    if (!jsonObj.specVersion) {
      jsonObj.specVersion = '1.6'; 
    }
    if (!jsonObj.bomFormat) {
      jsonObj.bomFormat = 'CycloneDX';
    }
    
    // Fix dependencies structure - ensure dependsOn is always an array
    if (jsonObj.dependencies && Array.isArray(jsonObj.dependencies)) {
      jsonObj.dependencies = jsonObj.dependencies.map((dep: any) => {
        if (dep.dependsOn && !Array.isArray(dep.dependsOn)) {
          // Convert non-array dependsOn to array
          dep.dependsOn = dep.dependsOn ? [dep.dependsOn] : [];
        }
        return dep;
      });
    }
    
    // Fix metadata structure for CycloneDX 1.6+
    if (!jsonObj.metadata) {
      jsonObj.metadata = {};
    }
    if (!jsonObj.metadata.timestamp) {
      jsonObj.metadata.timestamp = new Date().toISOString();
    }
    // Ensure tools structure exists for spec 1.6+ (tools.components array)
    if (!jsonObj.metadata.tools) {
      jsonObj.metadata.tools = { components: [] };
    }
    if (!jsonObj.metadata.tools.components) {
      jsonObj.metadata.tools.components = [];
    }
    
    const processedBom = await processBomObj(jsonObj)
    await validateBom(processedBom)
    
    // Attach rebom tool to the merged BOM
    const finalBom = attachRebomToolToBom(processedBom)
    return finalBom

  } catch (e) {
    logger.error({ err: e }, "Error During merge")
    if (bomPaths.length > 0) {
      await deleteTmpFiles(bomPaths)
    }
    throw e
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
    let fixedCount = 0;
    processedBom.dependencies = processedBom.dependencies.map((dep: any) => {
      if ('dependsOn' in dep) {
        if (!Array.isArray(dep.dependsOn)) {
          fixedCount++;
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
    if (fixedCount > 0) {
      logger.info(`Fixed ${fixedCount} dependencies with non-array dependsOn`);
    }
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
    // Fix dependencies structure - ensure dependsOn is always an array and deduplicate items
    outBom.dependencies = bom.dependencies.map((dep: any) => {
      if (dep.dependsOn && !Array.isArray(dep.dependsOn)) {
        return { ...dep, dependsOn: [dep.dependsOn] };
      }
      // Deduplicate dependsOn array items
      if (dep.dependsOn && Array.isArray(dep.dependsOn)) {
        const uniqueDependsOn = [...new Set(dep.dependsOn)];
        if (uniqueDependsOn.length !== dep.dependsOn.length) {
          logger.debug({ ref: dep.ref, original: dep.dependsOn.length, deduped: uniqueDependsOn.length }, "Deduplicating dependsOn array");
        }
        return { ...dep, dependsOn: uniqueDependsOn };
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
    throw new BomStorageError(
      "Error sanitizing bom: " + (e instanceof Error ? e.message : String(e)),
      e instanceof Error ? e : new Error(String(e)),
      { operation: 'sanitizeBom' }
    );
  }
}
