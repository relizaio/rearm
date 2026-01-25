import { BomRecord, BomDto, BomMetaDto } from '../../types';
import { fetchFromOci } from '../oci';

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
            meta: record.meta,
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
     * Convert BomRecord to BomDto WITH actual BOM content from OCI.
     * This fetches the full BOM JSON from OCI storage.
     * Use this when you need the actual BOM content (components, dependencies, etc.).
     */
    static async toDtoWithContent(record: BomRecord): Promise<BomDto> {
        const dto = this.toDto(record);
        dto.bom = await fetchFromOci(record.uuid);
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
            hash: record.meta.hash,
            belongsTo: record.meta.belongsTo,
            tldOnly: record.meta.tldOnly,
            structure: record.meta.structure,
            notes: record.meta.notes,
            stripBom: record.meta.stripBom,
            serialNumber: record.meta.serialNumber,
            ignoreDev: record.meta.ignoreDev,
            enrichmentStatus: record.meta.enrichmentStatus,
            enrichmentTimestamp: record.meta.enrichmentTimestamp,
            enrichmentError: record.meta.enrichmentError
        };
    }

    static toDtoArray(records: BomRecord[]): BomDto[] {
        return records.map(record => this.toDto(record));
    }

    static toMetaDtoArray(records: BomRecord[]): BomMetaDto[] {
        return records.map(record => this.toMetaDto(record));
    }
}
