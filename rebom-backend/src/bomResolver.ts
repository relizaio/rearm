import * as BomService from './bomService';
import * as DiffService from './services/bom/bomDiffService';
import { SarifService } from './services/sarif';
import { CycloneDxService } from './services/cyclonedx';
import { BomDto, BomMetaDto, BomInput, BomRecord, BomSearch, WeaknessDto, VulnerabilityDto, BearIntegrationDto } from './types';
import * as IntegrationService from './services/integrationService';
import { toGraphQLError } from './types/errors';
import { bomToExcel, bomToCsv } from './services/bom/bomExportService';
import { verifySignature as runVerifySignature, VerifyInput, VerifyResult } from './services/signatureVerifier';
import { GraphQLError } from 'graphql';
import { randomBytes } from 'crypto';
import { logger } from './logger';
import * as TeaService from './services/tea/teaService';
import { requireTeaCaller, TeaRequestContext } from './services/tea/teaCallerGate';
import { internalMessage, TeaOutcome, TeaReason, teaResult, TeaResultBase } from './services/tea/teaOutcome';

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

/**
 * A TEA resolver (TPF-180 design 3.2, 3.4): the caller gate is its first statement, before any
 * validation, probe, slot or log line; then the service function. An exception nobody expected is
 * FAILED with reason INTERNAL and a reference that is also in the one log line.
 */
function teaResolver<R extends TeaResultBase>(
	operationName: string,
	run: (args: any) => Promise<R>
): (parent: unknown, args: any, context: TeaRequestContext) => Promise<R | TeaResultBase> {
	return async (_parent, args, context) => {
		const refused = requireTeaCaller(context);
		if (refused) return refused;
		try {
			return await run(args);
		} catch (error) {
			const ref = randomBytes(4).toString('hex');
			logger.error({ err: error, ref, operationName }, 'TEA operation failed unexpectedly');
			return teaResult(TeaOutcome.FAILED, TeaReason.INTERNAL, null, internalMessage(ref));
		}
	};
}

// A map of functions which return data for the schema.
const resolvers = {
	Query: {
		hello: () => 'world',
		allBoms: async (): Promise<BomDto[]> => BomService.findAllBoms(),
		bomMetadataById: async (_:any, input: { id: string, org: string }): Promise<BomMetaDto | null> => BomService.bomMetadataById(input.id, input.org),
		bomById: async (_:any, input: any): Promise<Object> => BomService.findBomObjectById(input.id, input.org),
		rawBomId: async (_:any, input: any): Promise<Object> => BomService.findRawBomObjectById(input.id, input.org, input.format),
		bomByIdCsv: async (_:any, input: any): Promise<string> => {
			const bom = await BomService.findBomObjectById(input.id, input.org);
			return bomToCsv(bom);
		},
		rawBomIdCsv: async (_:any, input: any): Promise<string> => {
			const rawBom = await BomService.findRawBomObjectById(input.id, input.org, input.format);
			return bomToCsv(rawBom);
		},
		bomBySerialNumberAndVersion: async (_:any, input: any): Promise<Object> => BomService.findBomBySerialNumberAndVersion(input.serialNumber, input.version, input.org, input.raw),
		bomMetaBySerialNumber: async (_:any, input: any): Promise<Object> => BomService.findBomMetasBySerialNumber(input.serialNumber, input.org),
		parseBomById: withErrorHandling(
			async (_:any, input: { id: string, org: string }) =>
				BomService.parseBomById(input.id, input.org),
			'parseBomById'
		),
		parseHbomById: withErrorHandling(
			async (_:any, input: { id: string, org: string }) =>
				BomService.parseHbomById(input.id, input.org),
			'parseHbomById'
		),
		bomDiff: async (_:any, input: any): Promise<Object> => DiffService.bomDiff(input.fromIds, input.toIds, input.org),
		// Excel endpoints
		bomByIdExcel: async (_:any, input: any): Promise<string> => {
			const bom = await BomService.findBomObjectById(input.id, input.org);
			return await bomToExcel(bom);
		},
		rawBomIdExcel: async (_:any, input: any): Promise<string> => {
			const rawBom = await BomService.findRawBomObjectById(input.id, input.org, input.format);
			return await bomToExcel(rawBom);
		},
		parseSarifContent: async (_:any, input: { sarifContent: string }): Promise<WeaknessDto[]> => {
			return SarifService.parseSarifContent(input.sarifContent);
		},
		parseCycloneDxContent: async (_:any, input: { vdrContent: string }): Promise<VulnerabilityDto[]> => {
			return CycloneDxService.parseCycloneDxContent(input.vdrContent);
		},
		getBearIntegration: withErrorHandling(async (_:any, input: { org: string }): Promise<BearIntegrationDto> => {
			return IntegrationService.getBearIntegration(input.org);
		}, 'getBearIntegration'),
		getBomDigestProbe: withErrorHandling(
			async (_: any, { bomContent }: { bomContent: { format: string; bom: any } }): Promise<string> =>
				BomService.computeBomDigestOnly(bomContent.format, bomContent.bom),
			'getBomDigestProbe'
		),
		getEnrichedBomProbe: withErrorHandling(
			async (_: any, { bomContent }: { bomContent: { format: string; bom: any; org: string } }) =>
				BomService.computeEnrichedBomContent(bomContent.format, bomContent.bom, bomContent.org),
			'getEnrichedBomProbe'
		),
		scoreBomProbe: withErrorHandling(
			async (_: any, input: { bom: string; profiles: string[] }): Promise<string> =>
				BomService.scoreBom(input.bom, input.profiles),
			'scoreBomProbe'
		),
		isEnrichmentConfigured: withErrorHandling(
			async (_: any, input: { org: string }): Promise<boolean> =>
				BomService.isEnrichmentConfigured(input.org),
			'isEnrichmentConfigured'
		),
		teaStatus: teaResolver('teaStatus', () => TeaService.teaStatus()),
		teaFetchedFileChunk: teaResolver('teaFetchedFileChunk', (args) => TeaService.teaFetchedFileChunk(args))
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
		}, 'mergeAndStoreBomsExcel'),
		triggerEnrichment: withErrorHandling(async (_:any, input: { id: string, org: string, force?: boolean }) => {
			return BomService.triggerEnrichment(input.id, input.org, input.force ?? true);
		}, 'triggerEnrichment'),
		setBearIntegration: withErrorHandling(async (_:any, input: { org: string, uri: string, apiKey: string, skipPatterns?: string[] }): Promise<BearIntegrationDto> => {
			return IntegrationService.setBearIntegration(input.org, input.uri, input.apiKey, input.skipPatterns ?? undefined);
		}, 'setBearIntegration'),
		updateBearSkipPatterns: withErrorHandling(async (_:any, input: { org: string, skipPatterns?: string[] }): Promise<BearIntegrationDto> => {
			return IntegrationService.updateBearSkipPatterns(input.org, input.skipPatterns ?? undefined);
		}, 'updateBearSkipPatterns'),
		deleteBearIntegration: withErrorHandling(async (_:any, input: { org: string }): Promise<boolean> => {
			return IntegrationService.deleteBearIntegration(input.org);
		}, 'deleteBearIntegration'),
		verifySignature: withErrorHandling(async (_:any, input: { input: VerifyInput }): Promise<VerifyResult> => {
			return runVerifySignature(input.input);
		}, 'verifySignature'),
		teaResolve: teaResolver('teaResolve', (args) => TeaService.teaResolve(args.input)),
		teaProduct: teaResolver('teaProduct', (args) => TeaService.teaProduct(args.input)),
		teaComponent: teaResolver('teaComponent', (args) => TeaService.teaComponent(args.input)),
		teaReleases: teaResolver('teaReleases', (args) => TeaService.teaReleases(args.input)),
		teaRelease: teaResolver('teaRelease', (args) => TeaService.teaRelease(args.input)),
		teaFetchCollection: teaResolver('teaFetchCollection', (args) => TeaService.teaFetchCollection(args.input)),
		teaStoreFetchedSbom: teaResolver('teaStoreFetchedSbom', (args) => TeaService.teaStoreFetchedSbom(args.input)),
		teaReleaseFetch: teaResolver('teaReleaseFetch', (args) => TeaService.teaReleaseFetch(args.fetchId))
	}
}
export default resolvers;