import { IntegrationType, IntegrationConfig, BearIntegrationDto } from '../types';
import * as IntegrationRepository from '../integrationRepository';
import { encrypt, decrypt, isEncryptionConfigured } from './encryptionService';
import { logger } from '../logger';

/**
 * Sets (creates or updates) a BEAR integration for an organization.
 * Encrypts the API key and stores it in the secrets table.
 */
export async function setBearIntegration(
    org: string,
    uri: string,
    apiKey: string,
    skipPatterns?: string[]
): Promise<BearIntegrationDto> {
    if (!isEncryptionConfigured()) {
        throw new Error('Encryption service not initialized — cannot store secrets');
    }

    const encryptedApiKey = encrypt(apiKey);

    // Check if integration already exists
    const existing = await IntegrationRepository.findIntegrationByTypeAndOrg(IntegrationType.BEAR, org);
    let secretUuid: string;

    if (existing && existing.config.secretUuid) {
        // Update existing secret
        await IntegrationRepository.updateSecret(existing.config.secretUuid, encryptedApiKey);
        secretUuid = existing.config.secretUuid;
    } else {
        // Create new secret
        const secret = await IntegrationRepository.createSecret(encryptedApiKey, org);
        secretUuid = secret.uuid;
    }

    const config: IntegrationConfig = {
        type: IntegrationType.BEAR,
        uri,
        secretUuid,
        skipPatterns: skipPatterns && skipPatterns.length > 0 ? skipPatterns : undefined
    };

    await IntegrationRepository.upsertIntegration(config, org);

    logger.info({ org }, 'BEAR integration configured successfully');

    return {
        uri,
        configured: true,
        hasSkipPatterns: !!(skipPatterns && skipPatterns.length > 0)
    };
}

/**
 * Retrieves the BEAR integration for an organization.
 * Returns URI and configuration status — never returns the secret.
 */
export async function getBearIntegration(org: string): Promise<BearIntegrationDto> {
    const integration = await IntegrationRepository.findIntegrationByTypeAndOrg(IntegrationType.BEAR, org);

    if (!integration) {
        return { uri: null, configured: false, hasSkipPatterns: false };
    }

    return {
        uri: integration.config.uri || null,
        configured: !!(integration.config.uri && integration.config.secretUuid),
        hasSkipPatterns: !!(integration.config.skipPatterns && integration.config.skipPatterns.length > 0)
    };
}

/**
 * Retrieves the decrypted BEAR credentials from the DB for use by the enrichment service.
 * Falls back to environment variables if no DB integration is configured.
 * 
 * @returns { bearUri, bearApiKey, skipPatterns } or null if not configured
 */
export async function getBearCredentials(org: string): Promise<{
    bearUri: string;
    bearApiKey: string;
    skipPatterns: string[];
} | null> {
    // Try DB first
    const integration = await IntegrationRepository.findIntegrationByTypeAndOrg(IntegrationType.BEAR, org);

    if (integration && integration.config.uri && integration.config.secretUuid) {
        try {
            const secret = await IntegrationRepository.findSecretById(integration.config.secretUuid);
            if (secret && isEncryptionConfigured()) {
                const decryptedApiKey = decrypt(secret.encrypted_value);
                if (decryptedApiKey) {
                    return {
                        bearUri: integration.config.uri,
                        bearApiKey: decryptedApiKey,
                        skipPatterns: integration.config.skipPatterns || []
                    };
                }
            }
            logger.warn({ org }, 'Failed to decrypt BEAR API key from DB');
        } catch (error) {
            logger.error({ error, org }, 'Error retrieving BEAR credentials from DB');
        }
    }
    return null;
}
