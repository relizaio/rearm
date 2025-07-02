import * as BomService from './bomService';
import * as DiffService from './bomDiffService';
import { BomDto, BomInput, BomRecord, BomSearch } from './types';
// A map of functions which return data for the schema.
const resolvers = {
	Query: {
		hello: () => 'world',
		allBoms: async (): Promise<BomDto[]> => BomService.findAllBoms(),
		findBom: async (_:any, bomSearch: BomSearch): Promise<BomDto[]> => BomService.findBom(bomSearch),
		bomById: async (_:any, input: any): Promise<Object> => BomService.findBomObjectById(input.id, input.org),
		rawBomId: async (_:any, input: any): Promise<Object> => BomService.findRawBomObjectById(input.id, input.org),
		bomBySerialNumberAndVersion: async (_:any, input: any): Promise<Object> => BomService.findBomBySerialNumberAndVersion(input.serialNumber, input.version, input.org, input.raw),
		bomMetaBySerialNumber: async (_:any, input: any): Promise<Object> => BomService.findBomMetasBySerialNumber(input.serialNumber, input.org),
		// mergeBoms: async (_:any, mergeInput: any): Promise<any> => {
		// 	return BomService.exportMergedBom(mergeInput.ids, mergeInput.rebomOptions)},
		bomDiff: async (_:any, input: any): Promise<Object> => DiffService.bomDiff(input.fromIds, input.toIds, input.org)
	},
	Mutation: {
		addBom: async (_:any, bomInput: BomInput): Promise<BomRecord> => BomService.addBom(bomInput),
		mergeAndStoreBoms: async (_:any, mergeInput: any): Promise<BomRecord> => {
			return BomService.mergeAndStoreBoms(mergeInput.ids, mergeInput.rebomOptions, mergeInput.org)},
	}
}
export default resolvers;