import * as BomService from './bomService';
import * as DiffService from './services/bom/bomDiffService';
import { SarifService } from './services/sarif';
import { CycloneDxService } from './services/cyclonedx';
import { BomDto, BomInput, BomRecord, BomSearch, WeaknessDto, VulnerabilityDto } from './types';
import { toGraphQLError } from './types/errors';
import { bomToExcel, bomToCsv } from './services/bom/bomExportService';
import { GraphQLError } from 'graphql';
import { logger } from './logger';

/**
 * Wrap resolver function with structured error handling
 */
function withErrorHandling<T>(
	resolverFn: (...args: any[]) => Promise<T>,
	operationName: string
): (...args: any[]) => Promise<T> {
	return async (...args: any[]): Promise<T> => {
		try {
			return await resolverFn(...args);
		} catch (error) {
			logger.error({ error, operationName }, `Error in ${operationName}`);
			const graphqlError = toGraphQLError(error as Error);
			throw new GraphQLError(graphqlError.message, {
				extensions: graphqlError.extensions,
			});
		}
	};
}

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
			return bomToCsv(bom);
		},
		rawBomIdCsv: async (_:any, input: any): Promise<string> => {
			const rawBom = await BomService.findRawBomObjectById(input.id, input.org);
			return bomToCsv(rawBom);
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
		addBom: withErrorHandling(async (_:any, bomInput: BomInput): Promise<BomRecord> => BomService.addBom(bomInput), 'addBom'),
		mergeAndStoreBoms: withErrorHandling(async (_:any, mergeInput: any): Promise<BomRecord> => {
			return BomService.mergeAndStoreBoms(mergeInput.ids, mergeInput.rebomOptions, mergeInput.org, BomService.addBom)}, 'mergeAndStoreBoms'),
		mergeAndStoreBomsCsv: withErrorHandling(async (_:any, mergeInput: any): Promise<string> => {
			const mergedBom = await BomService.mergeAndStoreBoms(mergeInput.ids, mergeInput.rebomOptions, mergeInput.org, BomService.addBom);
			return bomToCsv(mergedBom);
		}, 'mergeAndStoreBomsCsv'),
		mergeAndStoreBomsExcel: withErrorHandling(async (_:any, mergeInput: any): Promise<string> => {
			const mergedBom = await BomService.mergeAndStoreBoms(mergeInput.ids, mergeInput.rebomOptions, mergeInput.org, BomService.addBom);
			return await bomToExcel(mergedBom);
		}, 'mergeAndStoreBomsExcel')
	}
}
export default resolvers;