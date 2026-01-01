import { logger } from '../../logger';
import { BomSearch, BomDto, RebomOptions, SearchObject, BomRecord } from '../../types';
import { fetchFromOci } from '../oci';
import { overrideRootComponent, attachRebomToolToBom } from './bomProcessingService';
import { runQuery } from '../../utils';

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

    let queryRes = await runQuery(searchObject.queryText, searchObject.queryParams)
    let bomRecords = queryRes.rows as BomRecord[]
    bomDtos = await Promise.all(bomRecords.map(async (b) => bomRecordToDto(b)))
  }
  return bomDtos
}

export async function findBomByMeta(bomMeta: RebomOptions): Promise<BomDto[]> {
  let bomDtos: BomDto[] = []
  const queryText = 'SELECT * FROM rebom.boms WHERE meta = $1::jsonb;';
  const values = [JSON.stringify(bomMeta)];
  
  let queryRes = await runQuery(queryText, values)
  let bomRecords = queryRes.rows as BomRecord[]
  if (bomRecords.length)
    bomDtos = await Promise.all(bomRecords.map(async (b) => bomRecordToDto(b)))
  
  return bomDtos
}

export async function findBomViaSingleQuery(singleQuery: string): Promise<BomDto[]> {
  let proceed: boolean = false
  let queryRes = await runQuery(`select * from rebom.boms where bom->>'serialNumber' = $1`, [singleQuery])
  proceed = (queryRes.rows.length < 1)

  if (proceed) {
    queryRes = await runQuery(`select * from rebom.boms where bom->>'serialNumber' = $1`, ['urn:uuid:' + singleQuery])
    proceed = (queryRes.rows.length < 1)
  }

  if (proceed) {
    queryRes = await runQuery(`select * from rebom.boms where bom->'metadata'->'component'->>'name' like $1`, ['%' + singleQuery + '%'])
    proceed = (queryRes.rows.length < 1)
  }

  if (proceed) {
    queryRes = await runQuery(`select * from rebom.boms where bom->'metadata'->'component'->>'group' like $1`, ['%' + singleQuery + '%'])
    proceed = (queryRes.rows.length < 1)
  }

  if (proceed) {
    queryRes = await runQuery(`select * from rebom.boms where bom->'metadata'->'component'->>'version' = $1`, [singleQuery])
    proceed = (queryRes.rows.length < 1)
  }

  let bomRecords = queryRes.rows as BomRecord[]
  return await Promise.all(bomRecords.map(async (b) => bomRecordToDto(b)))
}

export function updateSearchObj(searchObject: SearchObject, queryPath: string, addParam: string) {
  searchObject.queryText += ` AND ${queryPath} = $${searchObject.paramId}`
  searchObject.queryParams.push(addParam)
  ++searchObject.paramId
}

async function bomRecordToDto(bomRecord: BomRecord, rootOverride: boolean = true): Promise<BomDto> {
  let version = ''
  let group = ''
  let name = ''
  let bomVersion = ''
  if (process.env.OCI_STORAGE_ENABLED) {
    bomRecord.bom = await fetchFromOci(bomRecord.uuid)
  }
  
  if (rootOverride)
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
    bom: bomRecord.bom as any,
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

function rootComponentOverride(bomRecord: BomRecord): any {
  const rebomOverride = bomRecord.meta;
  const bom = bomRecord.bom;
  if (!rebomOverride) return bom;
  const overriddenBom = overrideRootComponent(bom, rebomOverride, bomRecord.last_updated_date);
  return attachRebomToolToBom(overriddenBom);
}
