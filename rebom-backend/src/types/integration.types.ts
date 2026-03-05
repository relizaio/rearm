export enum IntegrationType {
    BEAR = 'BEAR'
}

export interface IntegrationConfig {
    type: IntegrationType;
    uri?: string;
    secretUuid?: string;
    skipPatterns?: string[];
}

export interface IntegrationRecord {
    uuid: string;
    created_date: string;
    last_updated_date: string;
    config: IntegrationConfig;
    organization: string;
}

export interface SecretRecord {
    uuid: string;
    created_date: string;
    last_updated_date: string;
    encrypted_value: string;
    organization: string;
}

export interface BearIntegrationDto {
    uri: string | null;
    configured: boolean;
    skipPatterns: string[];
}
