import { BomDto, BomMetaDto, BomRecord } from '../../types';
import { fetchProcessedBomWithRetry } from '../oci';
import { boundFailureText } from '../../childFailure';

export class BomMapper {
    /**
     * Convert BomRecord to BomDto without fetching OCI content.
     * The bom field will contain OCI metadata (digest, size), not actual BOM content.
     * Use toDtoWithContent() if you need the actual BOM content.
     */
    static toDto(record: BomRecord): BomDto {
        const meta = record.meta as any;
        return {
            uuid: record.uuid,
            createdDate: record.created_date,
            lastUpdatedDate: record.last_updated_date,
            meta: BomMapper.servedMeta(record.meta),
            bom: record.bom,
            tags: record.tags,
            organization: record.organization,
            public: record.public,
            bomVersion: record.meta.bomVersion,
            group: record.meta.group,
            name: record.meta.name,
            version: record.meta.version
        };
    }

    /**
     * Converts a BomRecord to BomDto with full BOM content.
     * This fetches the full BOM JSON from OCI storage.
     * Use this when you need the actual BOM content (components, dependencies, etc.).
     */
    static async toDtoWithContent(record: BomRecord): Promise<BomDto> {
        const dto = this.toDto(record);
        // Digest-validated, race-tolerant (see fetchProcessedBomWithRetry)
        dto.bom = await fetchProcessedBomWithRetry(record);
        return dto;
    }

    /**
     * Convert multiple BomRecords to BomDtos WITH actual BOM content from OCI.
     * Fetches all BOM contents in parallel for better performance.
     */
    static async toDtoArrayWithContent(records: BomRecord[]): Promise<BomDto[]> {
        return Promise.all(records.map(record => this.toDtoWithContent(record)));
    }

    /**
     * Convert BomRecord to BomMetaDto (metadata only, no OCI fetch).
     * Use this for list operations where you only need metadata.
     */
    static toMetaDto(record: BomRecord): BomMetaDto {
        return {
            uuid: record.uuid,
            name: record.meta.name,
            group: record.meta.group,
            bomVersion: record.meta.bomVersion,
            version: record.meta.version,
            bomDigest: record.meta.bomDigest,
            belongsTo: record.meta.belongsTo,
            tldOnly: record.meta.tldOnly,
            structure: record.meta.structure,
            notes: record.meta.notes,
            stripBom: record.meta.stripBom,
            serialNumber: record.meta.serialNumber,
            processedSerialNumber: record.meta.processedSerialNumber,
            createdDate: record.created_date,
            lastUpdatedDate: record.last_updated_date,
            ignoreDev: record.meta.ignoreDev,
            excludeFileComponents: record.meta.excludeFileComponents,
            mergeVersion: record.meta.mergeVersion,
            enrichmentStatus: record.meta.enrichmentStatus,
            enrichmentTimestamp: record.meta.enrichmentTimestamp,
            enrichmentError: typeof record.meta.enrichmentError === 'string' ? boundFailureText(record.meta.enrichmentError) : record.meta.enrichmentError,
            enrichments: record.meta.enrichments
        };
    }

    /**
     * The meta of a row as it is served: a shallow copy with enrichmentError bounded and cleaned when it
     * is a string (rows written before TPF-261 hold a child's whole stdout there). The row itself is
     * never changed; a meta without such a string is passed as it is.
     */
    static servedMeta<T>(meta: T): T {
        const m = meta as { enrichmentError?: unknown } | null | undefined;
        if (!m || typeof m.enrichmentError !== 'string') return meta;
        return { ...m, enrichmentError: boundFailureText(m.enrichmentError) } as T;
    }

    static toDtoArray(records: BomRecord[]): BomDto[] {
        return records.map(record => this.toDto(record));
    }

    static toMetaDtoArray(records: BomRecord[]): BomMetaDto[] {
        return records.map(record => this.toMetaDto(record));
    }
}
