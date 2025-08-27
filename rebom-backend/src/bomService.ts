import * as CDX from '@cyclonedx/cyclonedx-library';
import ExcelJS from 'exceljs';
import * as BomRepository from './bomRespository';
import { logger } from './logger';
import { fetchFromOci, OASResponse, pushToOci } from './ociService';
import { BomDto, BomMetaDto, BomInput, BomRecord, BomSearch, HIERARCHICHAL, RebomOptions, SearchObject } from './types';
import validateBom from './validateBom';
const canonicalize = require ('canonicalize')
import { createHash } from 'crypto';
import { PackageURL } from 'packageurl-js'
import { v4 as uuidv4 } from 'uuid'

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

  const bomById = (await BomRepository.bomBySerialNumber(id, org))[0]
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
  const bomsBySerialNumber = await BomRepository.bomBySerialNumber(serialNumber, org)
  const versionedBom = bomsBySerialNumber.filter((b: BomRecord) => b.meta.bomVersion === version.toString())[0]
  const bomDto = await bomRecordToDto(versionedBom)
  return bomDto.bom
}

export async function findRawBomObjectById(id: string, org: string): Promise<Object> {
  let bomById = (await BomRepository.bomBySerialNumber(id, org))[0]

    return await fetchFromOci(bomById.uuid)
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
      const mergedBom = await mergeBoms(ids, rebomOptions, org)
      const bomInput : BomInput = {
        bomInput: {
          rebomOptions: rebomOptions,
          bom: mergedBom,
          org: org
        }
      }
      const bomRecord = await addBom(bomInput)
      return bomRecord
    } catch (e) {
      logger.error({ err: e }, "Error During merge")
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
      if (rebomOverride.structure.toLowerCase() === HIERARCHICHAL.toLowerCase()) qualifiers.structure = HIERARCHICHAL
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
    return purlStr
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
    const rootComponentPurl: string = bom.metadata.component["bom-ref"]
    let rootdepIndex : number = bom.dependencies?.findIndex((dep: any) => {
        return dep.ref === rootComponentPurl
    })
    if (rootdepIndex < 0) {
        // Try with decoded comparison
        const decodedRootPurl = decodeURIComponent(rootComponentPurl)
        rootdepIndex = bom.dependencies.findIndex((dep: any) => {
            return decodeURIComponent(dep.ref) === decodedRootPurl
        })
    }
    if (rootdepIndex < 0) {
        const versionStrippedRootComponentPurl = rootComponentPurl.split("@")[0]
        rootdepIndex = bom.dependencies.findIndex((dep: any) => {
            return dep.ref === versionStrippedRootComponentPurl
        })
    }
    if (rootdepIndex < 0) {
        logger.error(`root dependecy not found ! - rootComponentPurl: ${rootComponentPurl}, \nserialNumber: ${bom.serialNumber}`)
        logger.debug(JSON.stringify(bom.dependencies))
    }
    return rootdepIndex
}

  // function computeSha
  // enforce StripBom Flag for now
  function computeBomDigest(bom: any, stripBom: string): string {
    // strip meta
    let bomForDigest: any = {}
    // if(stripBom === 'TRUE'){
      bomForDigest["components"] = bom["components"]
      bomForDigest["dependencies"] = bom["dependencies"]
      //strip version
      const rootComponentPurl: string = bom.metadata.component["bom-ref"]
      const versionStrippedRootComponentPurl = rootComponentPurl.split("@")[0]
      
      const rootdepIndex = computeRootDepIndex(bom)

      if(rootdepIndex > -1){
        bomForDigest["dependencies"][rootdepIndex]['ref'] = versionStrippedRootComponentPurl
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
      let queryText = 'INSERT INTO rebom.boms (uuid, meta, bom, tags, organization) VALUES ($1, $2, $3, $4, $5) RETURNING *'
      let queryParams = [newUuid, rebomOptions, oasResponse, bomInput.bomInput.tags, bomInput.bomInput.org]
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

    logger.info(`Dedup reduced json from ${Object.keys(bom).length} to ${Object.keys(outBom).length}`)
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