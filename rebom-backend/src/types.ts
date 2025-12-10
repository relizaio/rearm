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
        // For SPDX updates: existing serialNumber to maintain continuity
        existingSerialNumber?: string,
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
    originalFileDigest?: string, // SHA256 of original file (for SPDX, this is the original SPDX file hash)
    originalFileSize?: number,   // Size of original file in bytes (for SPDX)
    originalMediaType?: string,  // Media type of original file (for SPDX)
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
    UNKNOWN = 'UNKNOWN'
}

export type WeaknessDto = {
    cweId: string;
    ruleId: string;
    location: string;
    fingerprint: string;
    severity: VulnerabilitySeverity;
}

export type VulnerabilityDto = {
    purl: string;
    vulnId: string;
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
    // Additional, non-standardized fields some tools include
    properties?: {
        [key: string]: any;
        tags?: string[];
        'security-severity'?: string;
        severity?: string;
    };
}

export type SarifRule = {
    id: string;
    properties?: {
        tags?: string[];
        'security-severity'?: string;
        // Allow arbitrary extra properties that some tools add
        [key: string]: any;
    };
    // Some tools specify default configuration including a default level
    defaultConfiguration?: {
        level?: string;
        [key: string]: any;
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

// SPDX-related types
import { OASResponse } from './ociService';

export type BomFormat = 'CYCLONEDX' | 'SPDX';

export type SpdxMetadata = {
    SPDXID: string;
    originalSPDXID?: string; // Preserve original SPDXID before uniqueness transformation
    name?: string;
    documentName?: string;
    documentNamespace?: string;
    creationInfo?: {
        created?: string;
        creators?: string[];
        licenseListVersion?: string;
    };
    packages?: Array<{
        SPDXID?: string;
        name?: string;
        versionInfo?: string;
        downloadLocation?: string;
        filesAnalyzed?: boolean;
        packageVerificationCode?: any;
    }>;
    // Extensible for future SPDX fields
    [key: string]: any;
}

export type SpdxBomRecord = {
    uuid: string;
    created_date: Date;
    last_updated_date: Date;
    spdx_metadata: SpdxMetadata;
    oci_response?: OASResponse;
    converted_bom_uuid?: string;
    organization?: string;
    file_sha256?: string;
    conversion_status: 'pending' | 'success' | 'failed';
    conversion_error?: string;
    tags?: Object;
    public: boolean;
    bom_version: number;  // Rearm-managed incrementing version (1, 2, 3...)
}

export type ConversionResult = {
    success: boolean;
    convertedBom?: any;
    error?: string;
    warnings?: string[];
}

