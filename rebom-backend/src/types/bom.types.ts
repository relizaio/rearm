export type BomRecord = {
    uuid: string,
    created_date: Date,
    last_updated_date: Date,
    meta: RebomOptions,
    bom: any,
    tags: Object,
    organization: string,
    public: boolean,
    duplicate: boolean,
    source_format?: string,
    source_spdx_uuid?: string
}

export type BomDto = {
    uuid: string,
    createdDate: Date,
    lastUpdatedDate: Date,
    meta: RebomOptions,
    bom: Object,
    tags: Object,
    organization: string,
    public: boolean,
    bomVersion: string,
    group: string,
    name: string,
    version: string
}

export type BomMetaDto = {
    name: string,
    group: string,
    bomVersion: string,
    hash: string | undefined,
    belongsTo: string,
    tldOnly: boolean,
    structure: string,
    notes: string,
    stripBom: string,
    serialNumber: string
}

export type BomInput = {
    bomInput: { 
        format: BomFormat,
        bom: any,
        tags?: Object,
        rebomOptions: RebomOptions,
        org: string,
        existingSerialNumber?: string,
    }
}

export enum RootComponentMergeMode {
    PRESERVE_UNDER_NEW_ROOT = 'PRESERVE_UNDER_NEW_ROOT',
    FLATTEN_UNDER_NEW_ROOT = 'FLATTEN_UNDER_NEW_ROOT'
}

export type RebomOptions = {
    serialNumber: string,
    name: string,
    group: string,
    version: string,
    belongsTo: string,
    hash?: string,
    notes: string,
    tldOnly: boolean,
    structure: string,
    bomState: string,
    mod: string,
    storage: string,
    bomDigest?: string,
    originalFileDigest?: string,
    originalFileSize?: number,
    originalMediaType?: string,
    stripBom: string,
    bomVersion: string,
    purl?: string,
    rootComponentMergeMode?: RootComponentMergeMode,
    ignoreDev?: boolean
}

export type BomSearch = {
    bomSearch: {
        serialNumber: string,
        version: string,
        componentVersion: string,
        componentGroup: string,
        componentName: string,
        singleQuery: string,
        page: number,
        offset: number
    }
}

export type BomFormat = 'CYCLONEDX' | 'SPDX';

export const HIERARCHICHAL = 'hierarchical'
