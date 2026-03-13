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
        skipPatterns: skipPatterns && skipPatterns.length > 0 ? skipPatterns : []
    };
}

/**
 * Retrieves the BEAR integration for an organization.
 * Returns URI and configuration status — never returns the secret.
 */
export async function getBearIntegration(org: string): Promise<BearIntegrationDto> {
    const integration = await IntegrationRepository.findIntegrationByTypeAndOrg(IntegrationType.BEAR, org);

    if (!integration) {
        return { uri: null, configured: false, skipPatterns: [] };
    }

    return {
        uri: integration.config.uri || null,
        configured: !!(integration.config.uri && integration.config.secretUuid),
        skipPatterns: integration.config.skipPatterns || []
    };
}

/**
 * Updates only the skip patterns for an existing BEAR integration.
 * Does not modify the URI or API key.
 * Returns the updated integration configuration.
 */
export async function updateBearSkipPatterns(
    org: string,
    skipPatterns?: string[]
): Promise<BearIntegrationDto> {
    const integration = await IntegrationRepository.findIntegrationByTypeAndOrg(IntegrationType.BEAR, org);

    if (!integration) {
        throw new Error('BEAR integration not found for organization');
    }

    if (!integration.config.uri || !integration.config.secretUuid) {
        throw new Error('BEAR integration is not properly configured');
    }

    // Update config with new skip patterns, preserving existing URI and secretUuid
    const config: IntegrationConfig = {
        type: IntegrationType.BEAR,
        uri: integration.config.uri,
        secretUuid: integration.config.secretUuid,
        skipPatterns: skipPatterns && skipPatterns.length > 0 ? skipPatterns : undefined
    };

    await IntegrationRepository.upsertIntegration(config, org);

    logger.debug({ org, skipPatternsCount: skipPatterns?.length || 0 }, 'BEAR skip patterns updated successfully');

    return {
        uri: integration.config.uri,
        configured: true,
        skipPatterns: skipPatterns && skipPatterns.length > 0 ? skipPatterns : []
    };
}

/**
 * Deletes the BEAR integration and its associated secret for an organization.
 * Returns true if deleted, false if no integration existed.
 */
export async function deleteBearIntegration(org: string): Promise<boolean> {
    const integration = await IntegrationRepository.findIntegrationByTypeAndOrg(IntegrationType.BEAR, org);

    if (!integration) {
        return false;
    }

    // Delete secret first (if exists)
    if (integration.config.secretUuid) {
        await IntegrationRepository.deleteSecret(integration.config.secretUuid);
    }

    await IntegrationRepository.deleteIntegration(integration.uuid);

    logger.info({ org }, 'BEAR integration deleted');
    return true;
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
