import * as CDX from '@cyclonedx/cyclonedx-library';
import ExcelJS from 'exceljs';
import * as BomRepository from './bomRespository';
import { logger } from './logger';
import { fetchFromOci, OASResponse, pushToOci } from './ociService';
import { BomDto, BomMetaDto, BomInput, BomRecord, BomSearch, HIERARCHICHAL, RebomOptions, SearchObject, BomFormat, SpdxBomRecord } from './types';
import validateBom from './validateBom';
const canonicalize = require ('canonicalize')
import { createHash } from 'crypto';
import { PackageURL } from 'packageurl-js'
import { v4 as uuidv4 } from 'uuid'
import { SpdxService } from './spdxService';
import * as SpdxRepository from './spdxRepository';

const utils = require('./utils')

export async function bomToExcel(bom: any): Promise<string> {
  const fields = [
    'name',
    'group',
    'version',
    'purl',
    'author',
    'license'
  ];

  const workbook = new ExcelJS.Workbook();
  const worksheet = workbook.addWorksheet('SBOM');

  worksheet.addRow(fields);

  const boms = Array.isArray(bom) ? bom : [bom];
  if (boms.length !== 0) {

    boms.forEach(b => {
      if (b && b.components && Array.isArray(b.components)) {
        b.components.forEach((component: any) => {
          const row = fields.map(field => {
            if (field === 'license') {
              if (component.licenses && Array.isArray(component.licenses)) {
                const licenseIds = component.licenses
                  .map((license: any) => {
                    if (license.license && license.license.id) return license.license.id;
                    if (license.license && license.license.name) return license.license.name;
                    if (typeof license === 'string') return license;
                    return '';
                  })
                  .filter(Boolean);
                return licenseIds.join('; ');
              }
              return '';
            }
            if (field === 'author') {
              const author = component.publisher || component.author || '';
              return author;
            }
            return component[field] || '';
          });
          worksheet.addRow(row);
        });
      }
    });
  }

  const xlsxBuffer = await workbook.xlsx.writeBuffer()
  const xlsxContent = Buffer.from(xlsxBuffer).toString('base64')
  return xlsxContent
}

// Converts CycloneDX BOM object(s) to CSV string with component information
export function bomToCsv(bom: any): string {

  // Define the fields we want for components
  const fields = [
    'name',
    'group',
    'version',
    'purl',
    'author',
    'license'
  ];

  // CSV header
  const header = fields.join(',');
  let rows: string[] = [];

  // Process single or array of boms
  const boms = Array.isArray(bom) ? bom : [bom];
  if (boms.length === 0) return '';

  // Process each bom
  boms.forEach(b => {
    // Check if this is a CDX bom with components
    if (b && b.components && Array.isArray(b.components)) {
      // Process each component
      b.components.forEach((component: any) => {
        const row = fields.map(field => {
          // Handle special cases
          if (field === 'license') {
            // License might be in different formats in CycloneDX
            if (component.licenses && Array.isArray(component.licenses)) {
              const licenseIds = component.licenses
                .map((license: any) => {
                  if (license.license && license.license.id) return license.license.id;
                  if (license.license && license.license.name) return license.license.name;
                  if (typeof license === 'string') return license;
                  return '';
                })
                .filter(Boolean);
              return JSON.stringify(licenseIds.join('; '));
            }
            return '';
          }
          
          if (field === 'author') {
            // Author might be in publisher or author field
            const author = component.publisher || component.author || '';
            return JSON.stringify(author);
          }
          
          // Regular field access
          return JSON.stringify(component[field] || '');
        }).join(',');
        
        rows.push(row);
      });
    }
  });

  return [header, ...rows].join('\n');
}

async function bomRecordToDto(bomRecord: BomRecord, rootOverride: boolean = true): Promise<BomDto> {
    let version = ''
    let group = ''
    let name = ''
    let bomVersion = ''
    if(process.env.OCI_STORAGE_ENABLED){
      bomRecord.bom = await fetchFromOci(bomRecord.uuid)
    }
    
    if(rootOverride)
      bomRecord.bom = rootComponentOverride(bomRecord)

    if (bomRecord.bom) bomVersion = bomRecord.bom.version
    if (bomRecord.bom && bomRecord.bom.metadata && bomRecord.bom.metadata.component) {
        version = bomRecord.bom.metadata.component.version
        name = bomRecord.bom.metadata.component.name
        group = bomRecord.bom.metadata.component.group
    }
    const bomDto: BomDto = {
        uuid: bomRecord.uuid,
        createdDate: bomRecord.created_date,
        lastUpdatedDate: bomRecord.last_updated_date,
        meta: bomRecord.meta,
        bom: bomRecord.bom as CDX.Models.Bom,
        tags: bomRecord.tags,
        organization: bomRecord.organization,
        public: bomRecord.public,
        bomVersion: bomVersion,
        group: group,
        name: name,
        version: version,
    }
    return bomDto
}

async function bomRecordToBomMetaDto(bomRecord: BomRecord): Promise<BomMetaDto> {
  const bomMetaDto : BomMetaDto = {
    name: bomRecord.meta.name,
    group: bomRecord.meta.group,
    bomVersion: bomRecord.meta.bomVersion,
    hash: bomRecord.meta.hash,
    belongsTo: bomRecord.meta.belongsTo,
    tldOnly: bomRecord.meta.tldOnly,
    structure: bomRecord.meta.structure,
    notes: bomRecord.meta.notes,
    stripBom: bomRecord.meta.stripBom,
    serialNumber: bomRecord.meta.serialNumber
  }
  return bomMetaDto
}

export async function findAllBoms(): Promise<BomDto[]> {
    let bomRecords = await BomRepository.findAllBoms();
    return await Promise.all(bomRecords.map(async(b) => bomRecordToDto(b)))
}

// TODO switch this one back to use UUID instead of serial number
export async function findBomObjectById(id: string, org: string): Promise<Object> {
  logger.debug({ id, org }, "findBomObjectById called");
  
  const bomResults = await BomRepository.bomBySerialNumber(id, org);
  logger.debug({ resultsCount: bomResults.length, id, org }, "bomBySerialNumber results");
  
  const bomById = bomResults[0];
  if (!bomById) {
    throw new Error(`BOM not found for id: ${id}, org: ${org}`);
  }
  
  const bomDto = await bomRecordToDto(bomById)
    // logger.info("writing to file bomrecord")
    // await writeFileAsync("/home/r/work/reliza/rebom/boms/"+id+".byID.json", JSON.stringify(bomById))
    // await writeFileAsync("/home/r/work/reliza/rebom/boms/"+id+".dto.json", JSON.stringify(bomDto))
  return bomDto.bom
}

export async function findBomMetasBySerialNumber(serialNumber: string, org: string): Promise<BomMetaDto[]> {
  const bomsBySerialNumber = await BomRepository.bomBySerialNumber(serialNumber, org)
  const bomMetaPromises = bomsBySerialNumber.map((x: BomRecord) => bomRecordToBomMetaDto(x))
  return await Promise.all(bomMetaPromises)
}

export async function findBomBySerialNumberAndVersion(serialNumber: string, version: number, org: string, raw?: boolean): Promise<Object> {
  const allBoms = await BomRepository.allBomsBySerialNumber(serialNumber, org)
  // bomVersion can be stored as number or string, so normalize to number for comparison
  const versionedBom = allBoms.filter((b: BomRecord) => {
    const bomVersion = b.meta.bomVersion;
    const bomVersionNum = typeof bomVersion === 'string' ? parseInt(bomVersion, 10) : bomVersion;
    return bomVersionNum === version;
  })[0]
  if (!versionedBom) {
    throw new Error(`BOM version ${version} not found for serialNumber: ${serialNumber}`);
  }
  
  // For SPDX source format, return the raw SPDX content
  if (versionedBom.source_format === 'SPDX' && versionedBom.source_spdx_uuid) {
    const spdxBom = await SpdxRepository.findSpdxBomById(versionedBom.source_spdx_uuid, org);
    if (spdxBom?.oci_response) {
      const fetchId = spdxBom.oci_response.ociResponse?.digest || spdxBom.uuid;
      return await fetchFromOci(fetchId);
    }
  }
  
  // For CycloneDX: if raw requested, fetch directly from OCI storage
  if (raw) {
    return await fetchFromOci(versionedBom.uuid);
  }
  
  // Otherwise return the augmented/processed BOM content
  const bomDto = await bomRecordToDto(versionedBom)
  return bomDto.bom
}

export async function findRawBomObjectById(id: string, org: string, format?: BomFormat): Promise<Object> {
  // Always start by fetching the BOM record from boms table
  logger.debug({ id, org, format }, "findRawBomObjectById called");
  
  let bomById = (await BomRepository.bomBySerialNumber(id, org))[0]
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
    // Always return raw CycloneDX regardless of SPDX relation
    return await fetchFromOci(bomById.uuid);
  } 
  else if (format === 'SPDX') {
    // Query boms table and find SPDX relation from there
    if (bomById.source_format === 'SPDX' && bomById.source_spdx_uuid) {
      const spdxBom = await SpdxRepository.findSpdxBomById(bomById.source_spdx_uuid, org);
      if (spdxBom?.oci_response) {
        return await fetchFromOci(spdxBom.oci_response.ociResponse?.digest || spdxBom.uuid);
      }
    }
    throw new Error(`No SPDX source found for BOM: ${id}`);
  } 
  else {
    // No format specified - check for SPDX relation and return raw SPDX if exists
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
    // Fallback to CycloneDX if no SPDX relation exists
    logger.debug("Falling back to CycloneDX BOM");
    return await fetchFromOci(bomById.uuid);
  }
}


  export async function findBom(bomSearch: BomSearch): Promise<BomDto[]> {
    let searchObject = {
      queryText: `select * from rebom.boms where 1 = 1`,
      queryParams: [],
      paramId: 1
    }

    let bomDtos: BomDto[] = []

    if (bomSearch.bomSearch.singleQuery) {
      bomDtos = await findBomViaSingleQuery(bomSearch.bomSearch.singleQuery)
    } else {
      if (bomSearch.bomSearch.serialNumber) {
        if (!bomSearch.bomSearch.serialNumber.startsWith('urn')) {
          bomSearch.bomSearch.serialNumber = 'urn:uuid:' + bomSearch.bomSearch.serialNumber
        }
        updateSearchObj(searchObject, `bom->>'serialNumber'`, bomSearch.bomSearch.serialNumber)
      }

      if (bomSearch.bomSearch.version) updateSearchObj(searchObject, `bom->>'version'`, bomSearch.bomSearch.version)

      if (bomSearch.bomSearch.componentVersion) updateSearchObj(searchObject, `bom->'metadata'->'component'->>'version'`,
        bomSearch.bomSearch.componentVersion)

      if (bomSearch.bomSearch.componentGroup) updateSearchObj(searchObject, `bom->'metadata'->'component'->>'group'`,
        bomSearch.bomSearch.componentGroup)

      if (bomSearch.bomSearch.componentName) updateSearchObj(searchObject, `bom->'metadata'->'component'->>'name'`,
        bomSearch.bomSearch.componentName)

      let queryRes = await utils.runQuery(searchObject.queryText, searchObject.queryParams)
      let bomRecords = queryRes.rows as BomRecord[]
      bomDtos = await  Promise.all(bomRecords.map(async(b) => bomRecordToDto(b)))
    }
    return bomDtos
  }

  export async function findBomByMeta(bomMeta: RebomOptions): Promise<BomDto[]> {
    let bomDtos: BomDto[] = []
    const queryText = 'SELECT * FROM rebom.boms WHERE meta = $1::jsonb;';
    const values = [JSON.stringify(bomMeta)];
    
    let queryRes = await utils.runQuery(queryText, values)
    let bomRecords = queryRes.rows as BomRecord[]
    if(bomRecords.length)
      bomDtos = await Promise.all(bomRecords.map(async(b) => bomRecordToDto(b)))
    
    return bomDtos
  }


  export async function findBomViaSingleQuery(singleQuery: string): Promise<BomDto[]> {
    let proceed: boolean = false
    // 1. search by uuid
    let queryRes = await utils.runQuery(`select * from rebom.boms where bom->>'serialNumber' = $1`, [singleQuery])
    proceed = (queryRes.rows.length < 1)

    if (proceed) {
      queryRes = await utils.runQuery(`select * from rebom.boms where bom->>'serialNumber' = $1`, ['urn:uuid:' + singleQuery])
      proceed = (queryRes.rows.length < 1)
    }

    // 2. search by name
    if (proceed) {
      queryRes = await utils.runQuery(`select * from rebom.boms where bom->'metadata'->'component'->>'name' like $1`, ['%' + singleQuery + '%'])
      proceed = (queryRes.rows.length < 1)
    }

    // 3. search by group
    if (proceed) {
      queryRes = await utils.runQuery(`select * from rebom.boms where bom->'metadata'->'component'->>'group' like $1`, ['%' + singleQuery + '%'])
      proceed = (queryRes.rows.length < 1)
    }

    // 3. search by version
    if (proceed) {
      queryRes = await utils.runQuery(`select * from rebom.boms where bom->'metadata'->'component'->>'version' = $1`, [singleQuery])
      proceed = (queryRes.rows.length < 1)
    }

    let bomRecords = queryRes.rows as BomRecord[]
    return await Promise.all(bomRecords.map(async(b) => bomRecordToDto(b)))
  }

  export function updateSearchObj(searchObject: SearchObject, queryPath: string, addParam: string) {
    searchObject.queryText += ` AND ${queryPath} = $${searchObject.paramId}`
    searchObject.queryParams.push(addParam)
    ++searchObject.paramId
  }

  // export async function exportMergedBom(ids: string[], rebomOptions: RebomOptions): Promise<any> {
  //   return JSON.stringify(mergeBoms(ids, rebomOptions))
  // }

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

  export async function mergeAndStoreBoms(ids: string[], rebomOptions: RebomOptions, org: string): Promise<BomRecord> {
    try {
      logger.info({ bomIds: ids, serialNumber: rebomOptions.serialNumber, org }, "Starting BOM merge and store operation")
      
      const mergedBom = await mergeBoms(ids, rebomOptions, org)
      logger.debug({ componentCount: mergedBom?.components?.length || 0 }, "BOM merge completed")
      
      const bomInput : BomInput = {
        bomInput: {
          format: 'CYCLONEDX',
          rebomOptions: rebomOptions,
          bom: mergedBom,
          org: org
        }
      }
      const bomRecord = await addBom(bomInput)
      logger.info({ bomUuid: bomRecord.uuid, serialNumber: rebomOptions.serialNumber }, "BOM merge and store completed successfully")
      
      return bomRecord
    } catch (e) {
      logger.error({ err: e, bomIds: ids, serialNumber: rebomOptions.serialNumber }, "Error during BOM merge and store")
      throw e
    }
  }

  async function findBomsForMerge(ids: string[], tldOnly: boolean, ignoreDev: boolean, org: string) {
    logger.info(`findBomsForMerge: ${ids}`)
    let bomRecords =  await Promise.all(ids.map(async(id) => findBomObjectById(id, org)))
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



  function extractTldFromBom(bom: any) {
    let newBom: any = {}
    let rootComponentRef: string
    try {
      // const bomAuthor = bom.metadata.tools.components[0].name
      // if (bomAuthor !== 'cdxgen') {
      //   logger.error("Top level dependecy can be extracted only for cdxgen boms")
      //   throw new Error("Top level dependecy can be extracted only for cdxgen boms")

      // }
      rootComponentRef = bom.metadata.component['bom-ref']
      if (!rootComponentRef) {
        logger.error("Need root component purl to be defined to extract top level dependencies")
        throw new Error("Need root component purl to be defined to extract top level dependencies")
      }
    } catch (e) {
      logger.error({ err: e })
      throw new Error("Top level dependecy can be extracted only for cdxgen boms")
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
        // Ensure dependsOn remains an array
        newBom.dependencies[0] = {
          ...rootDepObj,
          dependsOn: Array.isArray(rootDepObj.dependsOn) ? rootDepObj.dependsOn : []
        }
      }
    }

    const finalBom = Object.assign(bom, newBom)
    logger.info(`Bom components length FATER tld extract: ${finalBom.components.length}`)

    return finalBom
}

/**
 * Development dependency detection patterns for different ecosystems
 */
const DEV_DEPENDENCY_PATTERNS = {
  maven: {
    propertyName: 'cdx:maven:component_scope',
    devValues: ['test']  // 'runtime' and 'compile' are production
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
  // Extensible for future ecosystems
  gradle: {
    propertyName: 'cdx:gradle:component_scope',
    devValues: ['testImplementation', 'testCompile', 'testRuntime']
  }
};

/**
 * Checks if a component is a development dependency based on its properties
 */
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

/**
 * Filters out development dependencies from BOM components and dependencies
 * Similar to extractTldFromBom but focuses on dev dependency filtering
 */
function extractDevFilteredBom(bom: any): any {
  logger.info(`Filtering dev dependencies - original components: ${bom.components?.length || 0}`);
  
  if (!bom.components || !Array.isArray(bom.components)) {
    logger.warn('No components found in BOM for dev filtering');
    return bom;
  }

  // Filter out dev dependencies from components
  const prodComponents = bom.components.filter((component: any) => !isDevDependency(component));
  const filteredComponentRefs = new Set(prodComponents.map((comp: any) => comp['bom-ref']));
  
  logger.info(`After dev filtering - remaining components: ${prodComponents.length}`);
  
  // Update dependencies to remove references to dev dependencies
  let newDependencies = [];
  if (bom.dependencies && Array.isArray(bom.dependencies)) {
    newDependencies = bom.dependencies.map((dep: any) => {
      if (!dep.dependsOn || !Array.isArray(dep.dependsOn)) {
        return dep;
      }
      
      // Filter dependsOn to only include production components
      const filteredDependsOn = dep.dependsOn.filter((ref: string) => 
        filteredComponentRefs.has(ref)
      );
      
      return {
        ...dep,
        dependsOn: filteredDependsOn
      };
    }).filter((dep: any) => {
      // Keep dependency if its ref is in production components or if it's the root component
      return filteredComponentRefs.has(dep.ref) || dep.ref === bom.metadata?.component?.['bom-ref'];
    });
  }

  return {
    ...bom,
    components: prodComponents,
    dependencies: newDependencies
  };
}



  export async function mergeBomObjects(bomObjects: any[], rebomOptions: RebomOptions): Promise<any> {
    let bomPaths: string[] = []
    try {
      bomObjects.forEach(async (bobjs: any) => {
        await validateBom(bobjs)
      })
      
      const purl = establishPurl(undefined, rebomOptions)
      bomPaths = await utils.createTmpFiles(bomObjects)
      const command = ['bomutils', 'merge-boms']
      
      if (rebomOptions.rootComponentMergeMode) {
        command.push('--root-component-merge-mode', rebomOptions.rootComponentMergeMode);
      }
      if (rebomOptions.structure) {
        command.push('--structure', rebomOptions.structure)
      }else {
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
      const mergeResponse: string = await utils.shellExec('rearm-cli',command)
      
      // Clean up temporary files after successful merge
      await utils.deleteTmpFiles(bomPaths)

      const jsonObj = JSON.parse(mergeResponse)
      jsonObj.metadata.tools = []
      const processedBom = await processBomObj(jsonObj)
      // let bomRoots = bomObjects.map(bomObj => bomObj.metadata.component)
      // use the bom roots to prep the root level dep obj if doesn't already exist!
      // check if the root level dep obj is there?
      // const postMergeBom = postMergeOps(processedBom, rebomOptions)
      await validateBom(processedBom)
      return processedBom

    } catch (e) {
      logger.error({ err: e }, "Error During merge")
      // Clean up temporary files in case of error
      if (bomPaths.length > 0) {
        await utils.deleteTmpFiles(bomPaths)
      }
      throw e
    }

  }

  function postMergeOps(bomObj: any, rebomOptions: RebomOptions): any {
    // set bom-ref and purl for the root mreged component + we would need somekinda identifiers as well?
    const purl = establishPurl(undefined, rebomOptions)
    // bomObj.serialNumber = `urn:uuid:${rebomOptions.releaseId}`
    bomObj.metadata.component['bom-ref'] = purl
    bomObj.metadata.component['purl'] = purl
    addMissingDependecyGraph(bomObj, rebomOptions)
    return bomObj
  }

  function addMissingDependecyGraph(bomObj: any, dependencyMap: any){
    let deps = bomObj.dependencies
    // see if the dependencies graph has any info about root level
    // logger.info('deps', deps)

  }

function establishPurl(origPurl: string | undefined, rebomOverride: RebomOptions): string {
    let purlStr = rebomOverride.purl
    if (!purlStr) {
      // Ensure required fields exist
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

export function overrideRootComponent(bom: any, rebomOverride: RebomOptions, lastUpdatedDate?: string | Date): any {
    const newMetadata = { ...bom.metadata };
    // Generate new purl
    const origPurl = (bom.metadata && bom.metadata.component && bom.metadata.component.purl) ? bom.metadata.component.purl : undefined;
    const newPurl = establishPurl(origPurl, rebomOverride);
    logger.debug(`established purl: ${newPurl}`);

    // Override metadata
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

    // Update dependencies
    const rootdepIndex = computeRootDepIndex(bom);
    const dependenciesArray = Array.isArray(bom.dependencies) ? bom.dependencies : [];
    const newDependencies = [...dependenciesArray];
    if (rootdepIndex > -1) newDependencies[rootdepIndex]['ref'] = newPurl;

    // Return new BOM object
    return {
        ...bom,
        metadata: newMetadata,
        dependencies: newDependencies
    };
}

function rootComponentOverride(bomRecord: BomRecord): any {
  const rebomOverride = bomRecord.meta;
  const bom = bomRecord.bom;
  if (!rebomOverride) return bom;
  const overriddenBom = overrideRootComponent(bom, rebomOverride, bomRecord.last_updated_date);
  return attachRebomToolToBom(overriddenBom);
}


function createRebomToolObject(specVersion: string) {
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

function attachRebomToolToBom(finalBom: any): any {
    const rebomTool = createRebomToolObject(finalBom.specVersion);
    if (!finalBom.metadata.tools) finalBom.metadata.tools = { components: [] };
    if (!finalBom.metadata.tools.components) finalBom.metadata.tools.components = [];
    finalBom.metadata.tools.components.push(rebomTool);
    return finalBom;
}

function computeRootDepIndex (bom: any) : number {
    const rootComponentPurl: string = bom.metadata?.component?.["bom-ref"]
    if (!rootComponentPurl) {
        logger.error("No bom-ref found in metadata.component");
        return -1;
    }
    
    let rootdepIndex : number = bom.dependencies?.findIndex((dep: any) => {
        return dep.ref === rootComponentPurl
    })
    if (rootdepIndex < 0) {
        // Try with decoded comparison
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
    if (rootdepIndex < 0) {
        logger.error(`root dependecy not found ! - rootComponentPurl: ${rootComponentPurl}, \nserialNumber: ${bom.serialNumber}`)
        logger.debug(JSON.stringify(bom.dependencies))
    }
    return rootdepIndex
}

  /**
   * Extracts only the identity-relevant fields from components for deduplication.
   * Uses a whitelist approach to include only fields that define component identity,
   * ignoring transient data like build paths, evidence, properties, etc.
   */
  function extractComponentIdentity(components: any[]): any[] {
    if (!components) return components
    
    return components.map(comp => {
      // Only include fields that define component identity
      const identity: any = {}
      
      // Core identity fields
      if (comp.purl) identity.purl = comp.purl
      if (comp['bom-ref']) identity['bom-ref'] = comp['bom-ref']
      if (comp.name) identity.name = comp.name
      if (comp.version) identity.version = comp.version
      if (comp.group) identity.group = comp.group
      if (comp.type) identity.type = comp.type
      
      // Include hashes as they're part of identity
      if (comp.hashes) identity.hashes = comp.hashes
      
      // Include licenses as they affect compliance
      if (comp.licenses) identity.licenses = comp.licenses
      
      return identity
    })
  }

  function computeBomDigest(bom: any, stripBom: string): string {
    // strip meta
    let bomForDigest: any = {}
    // if(stripBom === 'TRUE'){
      // Extract only identity-relevant fields from components
      bomForDigest["components"] = extractComponentIdentity(bom["components"])
      // Deep clone dependencies to avoid mutating original (handle missing dependencies)
      bomForDigest["dependencies"] = bom["dependencies"] ? JSON.parse(JSON.stringify(bom["dependencies"])) : []
      
      // Normalize root component ref - use placeholder to ignore root identity variations
      // This handles: UUIDs, OCI purls with tag=, standard purls with @version
      const ROOT_PLACEHOLDER = '__ROOT_COMPONENT__'
      const rootComponentRef: string = bom.metadata?.component?.['bom-ref']
      
      const rootdepIndex = computeRootDepIndex(bom)
      if(rootdepIndex > -1){
        bomForDigest["dependencies"][rootdepIndex]['ref'] = ROOT_PLACEHOLDER
      }
      
      // Also replace any dependsOn references to root component and sort for consistency
      for (const dep of bomForDigest["dependencies"]) {
        if (dep.dependsOn && Array.isArray(dep.dependsOn)) {
          dep.dependsOn = dep.dependsOn
            .map((ref: string) => ref === rootComponentRef ? ROOT_PLACEHOLDER : ref)
            .sort() // Sort to ensure consistent ordering
        }
      }
      
      // Sort dependencies array by ref for consistent ordering
      bomForDigest["dependencies"].sort((a: any, b: any) => 
        (a.ref || '').localeCompare(b.ref || '')
      )
      
      // Sort components array by bom-ref for consistent ordering
      if (bomForDigest["components"]) {
        bomForDigest["components"].sort((a: any, b: any) => 
          (a['bom-ref'] || a.purl || '').localeCompare(b['bom-ref'] || b.purl || '')
        )
      }
    // } else {
    //   bomForDigest = bom
    // }
   
    // canoncicalize
    const canonBom = canonicalize(bomForDigest)

    // compute digest
    return computeSha256Hash(canonBom)
  }

  function computeSha256Hash(obj: string): string {
    const spaces = 2
    try {
        const hash = createHash('sha256');
        hash.update(obj);
        return hash.digest('hex');
    } catch (error) {
        throw new Error(`Failed to compute hash: ${error instanceof Error ? error.message : 'Unknown error'}`);
    }
}

  export async function addBom(bomInput: BomInput): Promise<BomRecord> {
    const format: BomFormat = (bomInput.bomInput as any).format || 'CYCLONEDX';
    if (format === 'SPDX') {
        return await addSpdxBom(bomInput);
    } else {
        return await addCycloneDxBom(bomInput);
    }
  }

  async function addCycloneDxBom(bomInput: BomInput): Promise<BomRecord> {
    let bomObj = await processBomObj(bomInput.bomInput.bom)

    let proceed: boolean = await validateBom(bomObj)
    const rebomOptions : RebomOptions = bomInput.bomInput.rebomOptions ?? {}
    rebomOptions.serialNumber = bomObj.serialNumber
    const bomSha: string = computeBomDigest(bomObj, rebomOptions.stripBom)
    rebomOptions.bomDigest = bomSha
    rebomOptions.bomVersion = bomObj.version
    const newUuid = uuidv4(); 
    // find bom by digest
    let oasResponse: OASResponse 
    let bomRows: BomRecord[]
    let bomRecord: BomRecord
    // bomRows = await BomRepository.bomByOrgAndDigest(bomSha, bomInput.bomInput.org)
    // logger.info(`RGDEBUG: bom rows found by digest and org: ${bomRows.length}`)
    // if (!bomRows || !bomRows.length){
      if(process.env.OCI_STORAGE_ENABLED){
        oasResponse = await pushToOci(newUuid, bomObj)
        rebomOptions.storage = 'oci'
      }else {
        throw new Error("OCI Storage not enabled")
      }
  
      rebomOptions.mod = 'raw'
      // urn must be unique - if same urn is supplied, we update current record
      // similarly it works for version, component group, component name, component version
      // check if urn is set on bom
      let queryText = 'INSERT INTO rebom.boms (uuid, meta, bom, tags, organization, source_format) VALUES ($1, $2, $3, $4, $5, $6) RETURNING *'
      let queryParams = [newUuid, rebomOptions, oasResponse, bomInput.bomInput.tags, bomInput.bomInput.org, 'CYCLONEDX']
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
        // if bom record found then update, otherwisebomRows insert
        let bomDtos = await findBom(bomSearch)
  
        // if not found, re-try search by meta
        if (!bomDtos || !bomDtos.length)
          bomDtos = await findBomByMeta(rebomOptions)
  
        if (bomDtos && bomDtos.length && bomDtos[0].uuid) {
          queryText = 'UPDATE rebom.boms SET meta = $1, bom = $2, tags = $3 WHERE uuid = $4 RETURNING *'
          queryParams = [rebomOptions, oasResponse, bomInput.bomInput.tags, bomDtos[0].uuid]
        }
      }
  
      let queryRes = await utils.runQuery(queryText, queryParams)
      bomRows = queryRes.rows
      bomRecord = bomRows[0]
    // }
    // else{
    //   bomRecord = bomRows[0]
    //   bomRecord.duplicate = true
    // }
    return bomRecord
  }

  async function addSpdxBom(bomInput: BomInput): Promise<BomRecord> {
    try {
        const spdxContent = bomInput.bomInput.bom;
        
        // 1. Validate SPDX format
        if (!SpdxService.validateSpdxFormat(spdxContent)) {
            throw new Error('Invalid SPDX format');
        }

        // 2. Extract metadata from SPDX for database storage
        const spdxMetadata = SpdxService.extractSpdxMetadata(spdxContent);
        const fileHash = SpdxService.calculateSpdxHash(spdxContent);
        
        // 3. Determine version and check for updates
        let bomVersion = 1;
        const existingSerialNumber = bomInput.bomInput.existingSerialNumber;
        
        if (existingSerialNumber) {
            // User is updating an existing SPDX artifact
            // The existingSerialNumber is the serialNumber from the converted CycloneDX BOM (internalBom.id)
            // We need to find the SPDX record by looking up the bom by serialNumber first
            const existingSpdx = await SpdxRepository.findSpdxBomBySerialNumber(existingSerialNumber, bomInput.bomInput.org);
            if (existingSpdx) {
                bomVersion = existingSpdx.bom_version + 1;
                logger.info({ existingSerialNumber, oldVersion: existingSpdx.bom_version, newVersion: bomVersion }, 
                    "User updating existing SPDX artifact - incrementing version");
            } else {
                logger.warn({ existingSerialNumber }, "existingSerialNumber provided but no existing SPDX found - treating as new upload");
            }
        } else {
            // New upload - validate namespace is unique
            const existingByNamespace = await SpdxRepository.findSpdxBomByNamespace(
                spdxMetadata.documentNamespace || '',
                bomInput.bomInput.org
            );
            
            if (existingByNamespace) {
                // Compare digests - if same content, return existing record
                if (existingByNamespace.file_sha256 === fileHash) {
                    logger.warn({ 
                        namespace: spdxMetadata.documentNamespace, 
                        fileHash 
                    }, "SPDX document with same namespace and content already exists - returning existing record");
                    
                    // Return the existing BomRecord
                    if (existingByNamespace.converted_bom_uuid) {
                        const existingBomRecords = await BomRepository.bomById(existingByNamespace.converted_bom_uuid);
                        if (existingBomRecords && existingBomRecords.length > 0) {
                            return existingBomRecords[0];
                        }
                    }
                    throw new Error(`SPDX document exists but linked BOM record not found`);
                }
                
                // Different content - this is an error
                throw new Error(
                    `SPDX document with namespace "${spdxMetadata.documentNamespace}" already exists with different content. ` +
                    `To update an existing artifact, use the update flow with existingSerialNumber.`
                );
            }
        }
        
        // 4. Upload SPDX to OCI
        const spdxUuid = uuidv4();
        let spdxOciResponse: OASResponse;
        
        if (process.env.OCI_STORAGE_ENABLED) {
            spdxOciResponse = await pushToOci(spdxUuid, spdxContent);
        } else {
            throw new Error("OCI Storage not enabled");
        }

        // 5. Store SPDX metadata in spdx_boms table with OCI response and version
        const spdxRecord = await SpdxRepository.createSpdxBom({
            uuid: spdxUuid, // Use the same UUID as OCI upload
            spdx_metadata: spdxMetadata,
            oci_response: spdxOciResponse,
            organization: bomInput.bomInput.org,
            file_sha256: fileHash,
            conversion_status: 'pending',
            tags: bomInput.bomInput.tags,
            public: false,
            bom_version: bomVersion
        });

        // 5. Convert SPDX to CycloneDX
        const conversionResult = await SpdxService.convertSpdxToCycloneDx(spdxContent);
        
        if (!conversionResult.success) {
            // Update conversion status to failed
            await SpdxRepository.updateSpdxBomConversionStatus(
                spdxRecord.uuid, 
                'failed', 
                conversionResult.error
            );
            throw new Error(`SPDX conversion failed: ${conversionResult.error}`);
        }

        // 6. Generate RebomOptions from SPDX metadata with version and serialNumber
        const rebomOptions = SpdxService.generateRebomOptionsFromSpdx(spdxMetadata, bomVersion, existingSerialNumber);
        const mergedOptions = { ...rebomOptions, ...bomInput.bomInput.rebomOptions };
        
        // Determine serialNumber: use existing for updates, otherwise from conversion or generate
        let serialNumber: string;
        if (existingSerialNumber) {
            // SPDX update: reuse existing serialNumber for DTrack/artifact continuity
            serialNumber = existingSerialNumber;
            mergedOptions.serialNumber = serialNumber;
            logger.info({ serialNumber, bomVersion }, "Using existing serial number for SPDX update continuity");
        } else if (conversionResult.convertedBom.serialNumber) {
            // New upload: use serial number from rearm-cli converted BOM
            serialNumber = conversionResult.convertedBom.serialNumber;
            mergedOptions.serialNumber = serialNumber;
            logger.info({ serialNumber }, "Using serial number from rearm-cli converted BOM");
        } else {
            // Fallback: generate UUID if rearm-cli didn't provide serial number
            const generatedSerialNumber = uuidv4();
            serialNumber = `urn:uuid:${generatedSerialNumber}`;
            mergedOptions.serialNumber = serialNumber;
            logger.warn({ serialNumber }, "Generated fallback serial number - rearm-cli output missing serialNumber");
        }
        
        // 7. Compute bomDigest on converted CycloneDX BOM for deduplication
        const convertedBom = conversionResult.convertedBom;
        const bomDigest = computeBomDigest(convertedBom, mergedOptions.stripBom);
        mergedOptions.bomDigest = bomDigest;
        mergedOptions.bomVersion = String(bomVersion);  // Use Rearm-managed version, not CycloneDX version
        
        // 8. Store converted BOM in boms table with source reference
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

        const queryRes = await utils.runQuery(queryText, queryParams);
        const bomRecord: BomRecord = queryRes.rows[0];

        // 8. Link SPDX and CycloneDX records
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
    let processedBom = {}

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
  

    let proceed: boolean = await validateBom(processedBom)
    // const bomModel = new CDX.Models.Bom(bom) <- doesn't yet support deserialization

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
    // Preserve optional top-level CycloneDX fields if present
    if ('signature' in bom) {
      outBom.signature = bom.signature
    }
    if ('annotations' in bom) {
      outBom.annotations = bom.annotations
    }
    if ('formulation' in bom) {
      outBom.formulation = bom.formulation
    }
    if ('declarations' in bom) {
      outBom.declarations = bom.declarations
    }
    if ('definitions' in bom) {
      outBom.definitions = bom.definitions
    }
    if ('vulnerabilities' in bom) {
      outBom.vulnerabilities = bom.vulnerabilities
    }
    if ('compositions' in bom) {
      outBom.compositions = bom.compositions
    }
    if ('services' in bom) {
      outBom.services = bom.services
    }
    if ('externalReferences' in bom) {
      outBom.externalReferences = bom.externalReferences
    }
    if ('properties' in bom) {
      outBom.properties = bom.properties
    }
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
      outBom.dependencies = bom.dependencies
    }

    logger.info(`Dedup BOM ${bom.serialNumber} - reduced json from ${Object.keys(bom).length} to ${Object.keys(outBom).length}`)
    return outBom
  }

  async function sanitizeBom(bom: any, patterns: Record<string, string>): Promise<any> {
    try {
      let jsonString = JSON.stringify(bom);
      // logger.info('jsonstring', jsonString)
      Object.entries(patterns).forEach(([search, replace]) => {
        jsonString = jsonString.replaceAll(search, replace);
        // logger.info('replaced', jsonString)
      });
      return JSON.parse(jsonString)
      // return bom
    } catch (e) {
      logger.error({ err: e }, "Error sanitizing bom")
      throw new Error("Error sanitizing bom: " + (e instanceof Error ? e.message : String(e)));
    }
  }