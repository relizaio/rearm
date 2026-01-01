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
        [key: string]: any;
    };
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
