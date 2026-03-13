import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { getBearCredentials } from '../../src/services/integrationService';
import { IntegrationType } from '../../src/types';

// Mock the integrationRepository module
vi.mock('../../src/integrationRepository', () => ({
    findIntegrationByTypeAndOrg: vi.fn(),
    upsertIntegration: vi.fn(),
    createSecret: vi.fn(),
    findSecretById: vi.fn(),
    updateSecret: vi.fn(),
    deleteSecret: vi.fn(),
    deleteIntegration: vi.fn(),
}));

// Mock the encryptionService module
vi.mock('../../src/services/encryptionService', () => ({
    encrypt: vi.fn((val: string) => `encrypted_${val}`),
    decrypt: vi.fn((val: string) => val.replace('encrypted_', '')),
    isEncryptionConfigured: vi.fn(() => true),
}));

// Mock logger
vi.mock('../../src/logger', () => ({
    logger: {
        info: vi.fn(),
        warn: vi.fn(),
        error: vi.fn(),
        debug: vi.fn(),
    }
}));

import * as IntegrationRepository from '../../src/integrationRepository';
import * as EncryptionService from '../../src/services/encryptionService';

const TEST_ORG = '00000000-0000-0000-0000-000000000001';

describe('integrationService', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        delete process.env.BEAR_URI;
        delete process.env.BEAR_API_KEY;
        delete process.env.BEAR_SKIP_PATTERN;
    });

    afterEach(() => {
        delete process.env.BEAR_URI;
        delete process.env.BEAR_API_KEY;
        delete process.env.BEAR_SKIP_PATTERN;
    });

    describe('getBearCredentials', () => {
        it('should return credentials from DB when integration exists', async () => {
            const mockIntegration = {
                uuid: 'int-uuid',
                created_date: new Date().toISOString(),
                last_updated_date: new Date().toISOString(),
                config: {
                    type: IntegrationType.BEAR,
                    uri: 'https://bear.example.com',
                    secretUuid: 'secret-uuid',
                    skipPatterns: ['pkg:npm/*']
                },
                organization: TEST_ORG
            };

            const mockSecret = {
                uuid: 'secret-uuid',
                created_date: new Date().toISOString(),
                last_updated_date: new Date().toISOString(),
                encrypted_value: 'encrypted_my-api-key',
                organization: TEST_ORG
            };

            vi.mocked(IntegrationRepository.findIntegrationByTypeAndOrg).mockResolvedValue(mockIntegration);
            vi.mocked(IntegrationRepository.findSecretById).mockResolvedValue(mockSecret);

            const result = await getBearCredentials(TEST_ORG);

            expect(result).not.toBeNull();
            expect(result!.bearUri).toBe('https://bear.example.com');
            expect(result!.bearApiKey).toBe('my-api-key');
            expect(result!.skipPatterns).toEqual(['pkg:npm/*']);
        });

        it('should return null when no DB integration exists', async () => {
            vi.mocked(IntegrationRepository.findIntegrationByTypeAndOrg).mockResolvedValue(null);

            const result = await getBearCredentials(TEST_ORG);

            expect(result).toBeNull();
        });

        it('should return null when secret cannot be found', async () => {
            const mockIntegration = {
                uuid: 'int-uuid',
                created_date: new Date().toISOString(),
                last_updated_date: new Date().toISOString(),
                config: {
                    type: IntegrationType.BEAR,
                    uri: 'https://bear.example.com',
                    secretUuid: 'secret-uuid',
                },
                organization: TEST_ORG
            };

            vi.mocked(IntegrationRepository.findIntegrationByTypeAndOrg).mockResolvedValue(mockIntegration);
            vi.mocked(IntegrationRepository.findSecretById).mockResolvedValue(null);

            const result = await getBearCredentials(TEST_ORG);

            expect(result).toBeNull();
        });

        it('should return empty skipPatterns from DB when not set', async () => {
            const mockIntegration = {
                uuid: 'int-uuid',
                created_date: new Date().toISOString(),
                last_updated_date: new Date().toISOString(),
                config: {
                    type: IntegrationType.BEAR,
                    uri: 'https://bear.example.com',
                    secretUuid: 'secret-uuid',
                },
                organization: TEST_ORG
            };

            const mockSecret = {
                uuid: 'secret-uuid',
                created_date: new Date().toISOString(),
                last_updated_date: new Date().toISOString(),
                encrypted_value: 'encrypted_my-api-key',
                organization: TEST_ORG
            };

            vi.mocked(IntegrationRepository.findIntegrationByTypeAndOrg).mockResolvedValue(mockIntegration);
            vi.mocked(IntegrationRepository.findSecretById).mockResolvedValue(mockSecret);

            const result = await getBearCredentials(TEST_ORG);

            expect(result).not.toBeNull();
            expect(result!.skipPatterns).toEqual([]);
        });
    });

    describe('setBearIntegration', () => {
        it('should create new secret and integration', async () => {
            // Need to import after mocks are set up
            const { setBearIntegration } = await import('../../src/services/integrationService');

            vi.mocked(IntegrationRepository.findIntegrationByTypeAndOrg).mockResolvedValue(null);
            vi.mocked(IntegrationRepository.createSecret).mockResolvedValue({
                uuid: 'new-secret-uuid',
                created_date: new Date().toISOString(),
                last_updated_date: new Date().toISOString(),
                encrypted_value: 'encrypted_my-api-key',
                organization: TEST_ORG
            });
            vi.mocked(IntegrationRepository.upsertIntegration).mockResolvedValue({
                uuid: 'new-int-uuid',
                created_date: new Date().toISOString(),
                last_updated_date: new Date().toISOString(),
                config: {
                    type: IntegrationType.BEAR,
                    uri: 'https://bear.example.com',
                    secretUuid: 'new-secret-uuid',
                },
                organization: TEST_ORG
            });

            const result = await setBearIntegration(TEST_ORG, 'https://bear.example.com', 'my-api-key');

            expect(result.configured).toBe(true);
            expect(result.uri).toBe('https://bear.example.com');
            expect(result.skipPatterns).toEqual([]);

            expect(IntegrationRepository.createSecret).toHaveBeenCalledWith('encrypted_my-api-key', TEST_ORG);
            expect(IntegrationRepository.upsertIntegration).toHaveBeenCalledWith(
                expect.objectContaining({
                    type: IntegrationType.BEAR,
                    uri: 'https://bear.example.com',
                    secretUuid: 'new-secret-uuid',
                }),
                TEST_ORG
            );
        });

        it('should update existing secret when integration exists', async () => {
            const { setBearIntegration } = await import('../../src/services/integrationService');

            vi.mocked(IntegrationRepository.findIntegrationByTypeAndOrg).mockResolvedValue({
                uuid: 'existing-int-uuid',
                created_date: new Date().toISOString(),
                last_updated_date: new Date().toISOString(),
                config: {
                    type: IntegrationType.BEAR,
                    uri: 'https://old-bear.example.com',
                    secretUuid: 'existing-secret-uuid',
                },
                organization: TEST_ORG
            });

            vi.mocked(IntegrationRepository.updateSecret).mockResolvedValue({
                uuid: 'existing-secret-uuid',
                created_date: new Date().toISOString(),
                last_updated_date: new Date().toISOString(),
                encrypted_value: 'encrypted_new-api-key',
                organization: TEST_ORG
            });

            vi.mocked(IntegrationRepository.upsertIntegration).mockResolvedValue({
                uuid: 'existing-int-uuid',
                created_date: new Date().toISOString(),
                last_updated_date: new Date().toISOString(),
                config: {
                    type: IntegrationType.BEAR,
                    uri: 'https://bear.example.com',
                    secretUuid: 'existing-secret-uuid',
                },
                organization: TEST_ORG
            });

            const result = await setBearIntegration(TEST_ORG, 'https://bear.example.com', 'new-api-key', ['pkg:npm/*']);

            expect(result.configured).toBe(true);
            expect(result.uri).toBe('https://bear.example.com');
            expect(result.skipPatterns).toEqual(['pkg:npm/*']);

            expect(IntegrationRepository.updateSecret).toHaveBeenCalledWith('existing-secret-uuid', 'encrypted_new-api-key');
            expect(IntegrationRepository.createSecret).not.toHaveBeenCalled();
        });

        it('should throw when encryption is not configured', async () => {
            const { setBearIntegration } = await import('../../src/services/integrationService');

            vi.mocked(EncryptionService.isEncryptionConfigured).mockReturnValue(false);

            await expect(
                setBearIntegration(TEST_ORG, 'https://bear.example.com', 'my-api-key')
            ).rejects.toThrow('Encryption service not initialized');
        });
    });

    describe('getBearIntegration', () => {
        it('should return configured=false when no integration exists', async () => {
            const { getBearIntegration } = await import('../../src/services/integrationService');

            vi.mocked(IntegrationRepository.findIntegrationByTypeAndOrg).mockResolvedValue(null);

            const result = await getBearIntegration(TEST_ORG);

            expect(result.configured).toBe(false);
            expect(result.uri).toBeNull();
            expect(result.skipPatterns).toEqual([]);
        });

        it('should return uri and configured=true when integration exists', async () => {
            const { getBearIntegration } = await import('../../src/services/integrationService');

            vi.mocked(IntegrationRepository.findIntegrationByTypeAndOrg).mockResolvedValue({
                uuid: 'int-uuid',
                created_date: new Date().toISOString(),
                last_updated_date: new Date().toISOString(),
                config: {
                    type: IntegrationType.BEAR,
                    uri: 'https://bear.example.com',
                    secretUuid: 'secret-uuid',
                    skipPatterns: ['pkg:npm/*'],
                },
                organization: TEST_ORG
            });

            const result = await getBearIntegration(TEST_ORG);

            expect(result.configured).toBe(true);
            expect(result.uri).toBe('https://bear.example.com');
            expect(result.skipPatterns).toEqual(['pkg:npm/*']);
        });

        it('should never expose the secret', async () => {
            const { getBearIntegration } = await import('../../src/services/integrationService');

            vi.mocked(IntegrationRepository.findIntegrationByTypeAndOrg).mockResolvedValue({
                uuid: 'int-uuid',
                created_date: new Date().toISOString(),
                last_updated_date: new Date().toISOString(),
                config: {
                    type: IntegrationType.BEAR,
                    uri: 'https://bear.example.com',
                    secretUuid: 'secret-uuid',
                },
                organization: TEST_ORG
            });

            const result = await getBearIntegration(TEST_ORG);

            // Verify the result object has no secret-related fields
            const resultStr = JSON.stringify(result);
            expect(resultStr).not.toContain('secret');
            expect(resultStr).not.toContain('apiKey');
            expect(resultStr).not.toContain('encrypted');
        });
    });

    describe('updateBearSkipPatterns', () => {
        it('should update skip patterns without modifying URI or secret', async () => {
            const { updateBearSkipPatterns } = await import('../../src/services/integrationService');

            vi.mocked(IntegrationRepository.findIntegrationByTypeAndOrg).mockResolvedValue({
                uuid: 'existing-int-uuid',
                created_date: new Date().toISOString(),
                last_updated_date: new Date().toISOString(),
                config: {
                    type: IntegrationType.BEAR,
                    uri: 'https://bear.example.com',
                    secretUuid: 'existing-secret-uuid',
                    skipPatterns: ['pkg:npm/*']
                },
                organization: TEST_ORG
            });

            vi.mocked(IntegrationRepository.upsertIntegration).mockResolvedValue({
                uuid: 'existing-int-uuid',
                created_date: new Date().toISOString(),
                last_updated_date: new Date().toISOString(),
                config: {
                    type: IntegrationType.BEAR,
                    uri: 'https://bear.example.com',
                    secretUuid: 'existing-secret-uuid',
                    skipPatterns: ['pkg:maven/*', 'pkg:pypi/*']
                },
                organization: TEST_ORG
            });

            const result = await updateBearSkipPatterns(TEST_ORG, ['pkg:maven/*', 'pkg:pypi/*']);

            expect(result.configured).toBe(true);
            expect(result.uri).toBe('https://bear.example.com');
            expect(result.skipPatterns).toEqual(['pkg:maven/*', 'pkg:pypi/*']);

            // Verify upsertIntegration was called with preserved URI and secretUuid
            expect(IntegrationRepository.upsertIntegration).toHaveBeenCalledWith(
                expect.objectContaining({
                    type: IntegrationType.BEAR,
                    uri: 'https://bear.example.com',
                    secretUuid: 'existing-secret-uuid',
                    skipPatterns: ['pkg:maven/*', 'pkg:pypi/*']
                }),
                TEST_ORG
            );

            // Verify updateSecret was NOT called
            expect(IntegrationRepository.updateSecret).not.toHaveBeenCalled();
        });

        it('should throw error if integration does not exist', async () => {
            const { updateBearSkipPatterns } = await import('../../src/services/integrationService');

            vi.mocked(IntegrationRepository.findIntegrationByTypeAndOrg).mockResolvedValue(null);

            await expect(updateBearSkipPatterns(TEST_ORG, ['pkg:npm/*']))
                .rejects.toThrow('BEAR integration not found for organization');
        });

        it('should throw error if integration is not properly configured', async () => {
            const { updateBearSkipPatterns } = await import('../../src/services/integrationService');

            vi.mocked(IntegrationRepository.findIntegrationByTypeAndOrg).mockResolvedValue({
                uuid: 'existing-int-uuid',
                created_date: new Date().toISOString(),
                last_updated_date: new Date().toISOString(),
                config: {
                    type: IntegrationType.BEAR,
                    uri: '',
                    secretUuid: undefined,
                },
                organization: TEST_ORG
            });

            await expect(updateBearSkipPatterns(TEST_ORG, ['pkg:npm/*']))
                .rejects.toThrow('BEAR integration is not properly configured');
        });

        it('should clear skip patterns when empty array is provided', async () => {
            const { updateBearSkipPatterns } = await import('../../src/services/integrationService');

            vi.mocked(IntegrationRepository.findIntegrationByTypeAndOrg).mockResolvedValue({
                uuid: 'existing-int-uuid',
                created_date: new Date().toISOString(),
                last_updated_date: new Date().toISOString(),
                config: {
                    type: IntegrationType.BEAR,
                    uri: 'https://bear.example.com',
                    secretUuid: 'existing-secret-uuid',
                    skipPatterns: ['pkg:npm/*']
                },
                organization: TEST_ORG
            });

            vi.mocked(IntegrationRepository.upsertIntegration).mockResolvedValue({
                uuid: 'existing-int-uuid',
                created_date: new Date().toISOString(),
                last_updated_date: new Date().toISOString(),
                config: {
                    type: IntegrationType.BEAR,
                    uri: 'https://bear.example.com',
                    secretUuid: 'existing-secret-uuid',
                },
                organization: TEST_ORG
            });

            const result = await updateBearSkipPatterns(TEST_ORG, []);

            expect(result.skipPatterns).toEqual([]);
            expect(IntegrationRepository.upsertIntegration).toHaveBeenCalledWith(
                expect.objectContaining({
                    skipPatterns: undefined
                }),
                TEST_ORG
            );
        });
    });
});
