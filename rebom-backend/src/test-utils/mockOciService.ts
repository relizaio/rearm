/**
 * Mock OCI Service for Testing
 * 
 * This module provides an in-memory mock implementation of OCI storage
 * for use in tests. It should NOT be imported in production code.
 */

import { createHash } from 'crypto';
import { logger } from '../logger';
import { OASResponse } from '../services/oci/ociService';

// In-memory storage for mock OCI
const mockStorage = new Map<string, any>();
const tagToDigestMap = new Map<string, string>();

/**
 * Generate a mock digest for content (deterministic based on content)
 */
function generateMockDigest(content: any): string {
    const str = JSON.stringify(content);
    return createHash('sha256').update(str).digest('hex');
}

/**
 * Transform tag according to OCI service rules
 */
function transformTag(tag: string): string {
    let transformed = tag;
    
    if (transformed.startsWith("urn")) {
        transformed = transformed.replace("urn:uuid:", "");
    }
    
    if (!transformed.startsWith('rebom') && !transformed.startsWith('sha256:')) {
        transformed = 'rebom-' + transformed;
    }
    
    return transformed;
}

/**
 * Mock implementation of fetchFromOci
 */
export async function mockFetchFromOci(tag: string): Promise<Object> {
    const originalTag = tag;
    const transformedTag = transformTag(tag);
    
    logger.debug({ originalTag, transformedTag }, "Fetching from mock OCI storage");
    
    // Try direct lookup first
    let data = mockStorage.get(transformedTag);
    
    // If not found and tag doesn't look like a digest, try via tag-to-digest mapping
    if (!data && !transformedTag.startsWith('sha256:')) {
        const digest = tagToDigestMap.get(transformedTag);
        if (digest) {
            logger.debug({ tag: transformedTag, digest }, "Found digest mapping for tag");
            data = mockStorage.get(digest);
        }
    }
    
    if (!data) {
        throw new Error(`Mock OCI: Tag not found: ${transformedTag}`);
    }
    
    return data;
}

/**
 * Mock implementation of pushToOci
 */
export async function mockPushToOci(tag: string, bom: any): Promise<OASResponse> {
    const transformedTag = transformTag(tag);
    const digest = `sha256:${generateMockDigest(bom)}`;
    
    logger.debug({ tag: transformedTag, digest }, "Storing in mock OCI storage");
    
    // Store by both tag and digest for proper retrieval
    mockStorage.set(transformedTag, bom);
    mockStorage.set(digest, bom);
    tagToDigestMap.set(transformedTag, digest);
    
    return {
        ociResponse: {
            digest: digest,
            size: String(JSON.stringify(bom).length),
            mediaType: 'application/json'
        },
        fileSHA256Digest: digest
    };
}

/**
 * Clear all mock OCI storage (for test cleanup)
 */
export function clearMockOciStorage(): void {
    mockStorage.clear();
    tagToDigestMap.clear();
}

/**
 * Get current mock storage state (for debugging tests)
 */
export function getMockStorageState(): {
    storage: Map<string, any>;
    tagMappings: Map<string, string>;
} {
    return {
        storage: new Map(mockStorage),
        tagMappings: new Map(tagToDigestMap)
    };
}
