import * as BomService from './bomService';
import { bomToExcel } from './bomService';
import * as DiffService from './bomDiffService';
import { SarifService } from './sarifService';
import { CycloneDxService } from './cycloneDxService';
import { BomDto, BomInput, BomRecord, BomSearch, WeaknessDto, VulnerabilityDto } from './types';
// A map of functions which return data for the schema.
const resolvers = {
	Query: {
		hello: () => 'world',
		allBoms: async (): Promise<BomDto[]> => BomService.findAllBoms(),
		findBom: async (_:any, bomSearch: BomSearch): Promise<BomDto[]> => BomService.findBom(bomSearch),
		bomById: async (_:any, input: any): Promise<Object> => BomService.findBomObjectById(input.id, input.org),
		rawBomId: async (_:any, input: any): Promise<Object> => BomService.findRawBomObjectById(input.id, input.org, input.format),
		bomByIdCsv: async (_:any, input: any): Promise<string> => {
			const bom = await BomService.findBomObjectById(input.id, input.org);
			return BomService.bomToCsv(bom);
		},
		rawBomIdCsv: async (_:any, input: any): Promise<string> => {
			const rawBom = await BomService.findRawBomObjectById(input.id, input.org);
			return BomService.bomToCsv(rawBom);
		},
		bomBySerialNumberAndVersion: async (_:any, input: any): Promise<Object> => BomService.findBomBySerialNumberAndVersion(input.serialNumber, input.version, input.org, input.raw),
		bomMetaBySerialNumber: async (_:any, input: any): Promise<Object> => BomService.findBomMetasBySerialNumber(input.serialNumber, input.org),
		bomDiff: async (_:any, input: any): Promise<Object> => DiffService.bomDiff(input.fromIds, input.toIds, input.org),
		// Excel endpoints
		bomByIdExcel: async (_:any, input: any): Promise<string> => {
			const bom = await BomService.findBomObjectById(input.id, input.org);
			return await bomToExcel(bom);
		},
		rawBomIdExcel: async (_:any, input: any): Promise<string> => {
			const rawBom = await BomService.findRawBomObjectById(input.id, input.org);
			return await bomToExcel(rawBom);
		},
		parseSarifContent: async (_:any, input: { sarifContent: string }): Promise<WeaknessDto[]> => {
			return SarifService.parseSarifContent(input.sarifContent);
		},
		parseCycloneDxContent: async (_:any, input: { vdrContent: string }): Promise<VulnerabilityDto[]> => {
			return CycloneDxService.parseCycloneDxContent(input.vdrContent);
		}
	},
	Mutation: {
		addBom: async (_:any, bomInput: BomInput): Promise<BomRecord> => BomService.addBom(bomInput),
		mergeAndStoreBoms: async (_:any, mergeInput: any): Promise<BomRecord> => {
			return BomService.mergeAndStoreBoms(mergeInput.ids, mergeInput.rebomOptions, mergeInput.org)},
		mergeAndStoreBomsCsv: async (_:any, mergeInput: any): Promise<string> => {
			const mergedBom = await BomService.mergeAndStoreBoms(mergeInput.ids, mergeInput.rebomOptions, mergeInput.org);
			return BomService.bomToCsv(mergedBom);
		},
		mergeAndStoreBomsExcel: async (_:any, mergeInput: any): Promise<string> => {
			const mergedBom = await BomService.mergeAndStoreBoms(mergeInput.ids, mergeInput.rebomOptions, mergeInput.org);
			return await bomToExcel(mergedBom);
		}
	}
}
export default resolvers;