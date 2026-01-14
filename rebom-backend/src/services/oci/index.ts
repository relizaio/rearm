/**
 * OCI Service Factory
 * 
 * Selects the appropriate OCI implementation based on environment:
 * - Production: Real OCI service
 * - Test (MOCK_OCI=true): Mock OCI service
 */

import type { OASResponse } from './ociService';
import * as realService from './ociService';
import * as mockService from '../../test-utils/mockOciService';

// Determine which implementation to use at module load time
const useMock = process.env.MOCK_OCI === 'true';

// Select the appropriate implementation
export const fetchFromOci = useMock 
    ? mockService.mockFetchFromOci 
    : realService.fetchFromOci;

export const pushToOci = useMock 
    ? mockService.mockPushToOci 
    : realService.pushToOci;

export const clearMockOciStorage = useMock
    ? async () => mockService.clearMockOciStorage()
    : async () => {}; // No-op in production

// Re-export types
export type { OASResponse, OciResponse } from './ociService';
