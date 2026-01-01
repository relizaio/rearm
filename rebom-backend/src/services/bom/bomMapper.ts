import { BomRecord, BomDto, BomMetaDto } from '../../types';

export class BomMapper {
    static toDto(record: BomRecord): BomDto {
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

    static toMetaDto(record: BomRecord): BomMetaDto {
        return {
            name: record.meta.name,
            group: record.meta.group,
            bomVersion: record.meta.bomVersion,
            hash: record.meta.hash,
            belongsTo: record.meta.belongsTo,
            tldOnly: record.meta.tldOnly,
            structure: record.meta.structure,
            notes: record.meta.notes,
            stripBom: record.meta.stripBom,
            serialNumber: record.meta.serialNumber
        };
    }

    static toDtoArray(records: BomRecord[]): BomDto[] {
        return records.map(record => this.toDto(record));
    }

    static toMetaDtoArray(records: BomRecord[]): BomMetaDto[] {
        return records.map(record => this.toMetaDto(record));
    }
}
