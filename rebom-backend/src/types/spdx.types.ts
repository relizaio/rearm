import { OASResponse } from '../services/oci';

export type SpdxMetadata = {
    SPDXID: string;
    originalSPDXID?: string;
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
    bom_version: number;
}

export type ConversionResult = {
    success: boolean;
    convertedBom?: any;
    error?: string;
    warnings?: string[];
}
