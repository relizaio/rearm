import { describe, it, expect, beforeEach, vi } from 'vitest';
import { pushToOci, fetchFromOci, clearMockOciStorage } from '../../src/ociService';

/**
 * Unit tests for OCI Service
 * Tests the mock OCI storage functionality used in tests
 */

describe('OCI Service - Mock Mode', () => {
    beforeEach(() => {
        // Clear mock storage before each test
        clearMockOciStorage();
        // Ensure we're in mock mode
        process.env.MOCK_OCI = 'true';
    });

    describe('pushToOci', () => {
        it('should store BOM with rebom- prefix tag', async () => {
            const uuid = '123e4567-e89b-12d3-a456-426614174000';
            const bom = { serialNumber: 'urn:uuid:test', components: [] };

            const response = await pushToOci(uuid, bom);

            expect(response).toBeDefined();
            expect(response.ociResponse).toBeDefined();
            expect(response.ociResponse!.digest).toMatch(/^sha256:[a-f0-9]{64}$/);
            expect(response.fileSHA256Digest).toBe(response.ociResponse!.digest);
        });

        it('should generate consistent digest for same content', async () => {
            const uuid = '123e4567-e89b-12d3-a456-426614174000';
            const bom = { serialNumber: 'urn:uuid:test', components: [] };

            const response1 = await pushToOci(uuid, bom);
            const response2 = await pushToOci(uuid, bom);

            expect(response1.ociResponse!.digest).toBe(response2.ociResponse!.digest);
        });

        it('should generate different digests for different content', async () => {
            const uuid1 = '123e4567-e89b-12d3-a456-426614174000';
            const uuid2 = '223e4567-e89b-12d3-a456-426614174000';
            const bom1 = { serialNumber: 'urn:uuid:test1', components: [] };
            const bom2 = { serialNumber: 'urn:uuid:test2', components: ['comp1'] };

            const response1 = await pushToOci(uuid1, bom1);
            const response2 = await pushToOci(uuid2, bom2);

            expect(response1.ociResponse!.digest).not.toBe(response2.ociResponse!.digest);
        });

        it('should include size in response', async () => {
            const uuid = '123e4567-e89b-12d3-a456-426614174000';
            const bom = { serialNumber: 'urn:uuid:test', components: [] };

            const response = await pushToOci(uuid, bom);

            expect(response.ociResponse).toBeDefined();
            expect(response.ociResponse!.size).toBeDefined();
            expect(parseInt(response.ociResponse!.size!)).toBeGreaterThan(0);
        });
    });

    describe('fetchFromOci', () => {
        it('should fetch BOM by UUID tag', async () => {
            const uuid = '123e4567-e89b-12d3-a456-426614174000';
            const bom = { serialNumber: 'urn:uuid:test', components: [] };

            await pushToOci(uuid, bom);
            const fetched = await fetchFromOci(uuid);

            expect(fetched).toEqual(bom);
        });

        it('should fetch BOM by rebom- prefixed tag', async () => {
            const uuid = '123e4567-e89b-12d3-a456-426614174000';
            const bom = { serialNumber: 'urn:uuid:test', components: [] };

            await pushToOci(uuid, bom);
            const fetched = await fetchFromOci(`rebom-${uuid}`);

            expect(fetched).toEqual(bom);
        });

        it('should fetch BOM by digest', async () => {
            const uuid = '123e4567-e89b-12d3-a456-426614174000';
            const bom = { serialNumber: 'urn:uuid:test', components: [] };

            const response = await pushToOci(uuid, bom);
            const fetched = await fetchFromOci(response.ociResponse!.digest!);

            expect(fetched).toEqual(bom);
        });

        it('should throw error for non-existent tag', async () => {
            const nonExistentUuid = '999e4567-e89b-12d3-a456-426614174999';

            await expect(fetchFromOci(nonExistentUuid)).rejects.toThrow('Mock OCI: Tag not found');
        });

        it('should handle urn:uuid: prefix in tag', async () => {
            const uuid = '123e4567-e89b-12d3-a456-426614174000';
            const bom = { serialNumber: 'urn:uuid:test', components: [] };

            await pushToOci(uuid, bom);
            const fetched = await fetchFromOci(`urn:uuid:${uuid}`);

            expect(fetched).toEqual(bom);
        });
    });

    describe('Tag-to-Digest Mapping', () => {
        it('should maintain tag-to-digest mapping', async () => {
            const uuid = '123e4567-e89b-12d3-a456-426614174000';
            const bom = { serialNumber: 'urn:uuid:test', components: [] };

            const response = await pushToOci(uuid, bom);
            
            // Should be able to fetch by tag
            const fetchedByTag = await fetchFromOci(uuid);
            expect(fetchedByTag).toEqual(bom);

            // Should be able to fetch by digest
            const fetchedByDigest = await fetchFromOci(response.ociResponse!.digest!);
            expect(fetchedByDigest).toEqual(bom);
        });

        it('should allow multiple tags for same content', async () => {
            const uuid1 = '123e4567-e89b-12d3-a456-426614174000';
            const uuid2 = '223e4567-e89b-12d3-a456-426614174000';
            const bom = { serialNumber: 'urn:uuid:test', components: [] };

            const response1 = await pushToOci(uuid1, bom);
            const response2 = await pushToOci(uuid2, bom);

            // Same content should have same digest
            expect(response1.ociResponse!.digest).toBe(response2.ociResponse!.digest);

            // Both tags should retrieve the same content
            const fetched1 = await fetchFromOci(uuid1);
            const fetched2 = await fetchFromOci(uuid2);
            expect(fetched1).toEqual(fetched2);
        });
    });

    describe('clearMockOciStorage', () => {
        it('should clear all stored BOMs', async () => {
            const uuid = '123e4567-e89b-12d3-a456-426614174000';
            const bom = { serialNumber: 'urn:uuid:test', components: [] };

            await pushToOci(uuid, bom);
            
            // Verify it's stored
            const fetched = await fetchFromOci(uuid);
            expect(fetched).toEqual(bom);

            // Clear storage
            clearMockOciStorage();

            // Should no longer be retrievable
            await expect(fetchFromOci(uuid)).rejects.toThrow('Mock OCI: Tag not found');
        });
    });
});
