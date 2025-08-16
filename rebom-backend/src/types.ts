export type BomRecord = {
    uuid: string,
    created_date: Date,
    last_updated_date: Date,
    meta: RebomOptions,
    bom: any,
    tags: Object,
    organization: string,
    public: boolean,
    duplicate: boolean
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
        bom: any,
        tags?: Object,
        rebomOptions: RebomOptions,
        org: string,
    }
}

/**
 * Enum to control how the root component is handled during merge operations in RebomOptions.
 */
export enum RootComponentMergeMode {
    PRESERVE_UNDER_NEW_ROOT = 'PRESERVE_UNDER_NEW_ROOT',
    FLATTEN_UNDER_NEW_ROOT = 'FLATTEN_UNDER_NEW_ROOT'
}

export type RebomOptions = {
    serialNumber: string,
    name: string,
    group: string,
    version: string,
    belongsTo: string, // belongsTo = application and hash = null || belongsTo = '' = a cross merged bom
    hash?: string,
    notes: string,
    tldOnly: boolean,
    structure: string,
    bomState: string, //[raw, merged, cross-merged]
    mod: string, //[raw, rebom, user?]
    storage: string, //[oci, db]
    bomDigest?: string,
    stripBom: string,
    bomVersion: string,
    purl?: string,
    rootComponentMergeMode?: RootComponentMergeMode
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

export type SearchObject = {    
    queryText: string,
    queryParams: string[],
    paramId: number
}

export const HIERARCHICHAL = 'hierarchical'

// SARIF-related types
export enum VulnerabilitySeverity {
    CRITICAL = 'CRITICAL',
    HIGH = 'HIGH',
    MEDIUM = 'MEDIUM',
    LOW = 'LOW',
    INFO = 'INFO',
    UNKNOWN = 'UNKNOWN'
}

export type WeaknessDto = {
    cweId: string;
    ruleId: string;
    location: string;
    fingerprint: string;
    severity: VulnerabilitySeverity;
}

export type SarifResult = {
    ruleId?: string;
    ruleIndex?: number;
    level?: string;
    message: {
        text: string;
    };
    locations?: Array<{
        physicalLocation?: {
            artifactLocation?: {
                uri: string;
            };
            region?: {
                startLine?: number;
                startColumn?: number;
            };
        };
    }>;
    fingerprints?: {
        [key: string]: string;
    };
    partialFingerprints?: {
        [key: string]: string;
    };
}

export type SarifRule = {
    id: string;
    properties?: {
        tags?: string[];
        'security-severity'?: string;
    };
    relationships?: Array<{
        target: {
            id: string;
            toolComponent: {
                name: string;
            };
        };
    }>;
}

export type SarifRun = {
    tool: {
        driver: {
            name: string;
            rules?: SarifRule[];
        };
    };
    results?: SarifResult[];
}

export type SarifReport = {
    version: string;
    $schema: string;
    runs: SarifRun[];
}
