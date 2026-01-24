import { logger } from '../../logger';
import { BomSearch, BomDto, BomMetaDto, RebomOptions, SearchObject, BomRecord } from '../../types';
import { BomNotFoundError } from '../../types/errors';
import { fetchFromOci } from '../oci';
import { augmentBomWithComponentContext, attachRebomToolToBom } from './bomProcessingService';
import { runQuery } from '../../utils';
import { BomMapper } from './bomMapper';
import * as BomRepository from '../../bomRepository';

/**
 * Get BOM metadata by UUID or serial number.
 * Returns metadata including enrichment status without fetching full BOM content.
 * 
 * @param id - UUID or serial number of the BOM
 * @param org - Organization ID
 * @returns BOM metadata or null if not found
 */
export async function bomMetadataById(id: string, org: string): Promise<BomMetaDto | null> {
  logger.debug({ id, org }, "bomMetadataById called");
  
  // Try to find by UUID first
  let bomResults = await BomRepository.bomById(id);
  
  // If not found by UUID, try by serialNumber
  if (!bomResults || bomResults.length === 0) {
    logger.debug({ id, org }, "BOM not found by UUID, trying serialNumber lookup");
    bomResults = await BomRepository.bomBySerialNumber(id, org);
  }
  
  if (!bomResults || bomResults.length === 0) {
    logger.debug({ id, org }, "No BOM found by UUID or serialNumber");
    return null;
  }
  
  // Return the first match's metadata
  const bomRecord = bomResults[0];
  return BomMapper.toMetaDto(bomRecord);
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

async function findBomViaSingleQueryRecords(singleQuery: string): Promise<BomRecord[]> {
  let proceed: boolean = false
  let queryRes = await runQuery(`select * from rebom.boms where meta->>'serialNumber' = $1`, [singleQuery])
  proceed = (queryRes.rows.length < 1)

  if (proceed) {
    queryRes = await runQuery(`select * from rebom.boms where meta->>'serialNumber' = $1`, ['urn:uuid:' + singleQuery])
    proceed = (queryRes.rows.length < 1)
  }

  if (proceed) {
    queryRes = await runQuery(`select * from rebom.boms where meta->>'name' like $1`, ['%' + singleQuery + '%'])
    proceed = (queryRes.rows.length < 1)
  }

  if (proceed) {
    queryRes = await runQuery(`select * from rebom.boms where meta->>'group' like $1`, ['%' + singleQuery + '%'])
    proceed = (queryRes.rows.length < 1)
  }

  if (proceed) {
    queryRes = await runQuery(`select * from rebom.boms where meta->>'version' = $1`, [singleQuery])
    proceed = (queryRes.rows.length < 1)
  }

  return queryRes.rows as BomRecord[]
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
  
  // Fetch BOM content from OCI storage
  bomRecord.bom = await fetchFromOci(bomRecord.uuid)
  
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

/**
 * Augments a BOM record with component context on-demand.
 * Used for legacy search operations that need augmented BOMs.
 * 
 * @param bomRecord - BOM record from database
 * @returns Augmented BOM with component context and rebom tool
 */
function rootComponentOverride(bomRecord: BomRecord): any {
  const rebomOverride = bomRecord.meta;
  const bom = bomRecord.bom;
  if (!rebomOverride) return bom;
  const augmentedBom = augmentBomWithComponentContext(bom, rebomOverride, bomRecord.last_updated_date);
  return attachRebomToolToBom(augmentedBom);
}
