import { logger } from '../../logger';
import { OciStorageError } from '../../types/errors';
import { OASResponse } from './ociService';

/**
 * Centralized helper functions for OCI repository name handling.
 * This module reduces code duplication across BOM services.
 */

/**
 * Extract repository name from BOM record.
 * The repository name is stored in the bom field (OASResponse), not in meta.
 * 
 * @param bomRecord - BOM record containing the OASResponse in the bom field
 * @returns Repository name or undefined if not present
 */
export function extractRepositoryNameFromBom(bomRecord: any): string | undefined {
    // Validate record structure
    if (!bomRecord) {
        logger.warn('extractRepositoryNameFromBom called with null/undefined bomRecord');
        return undefined;
    }
    
    if (!bomRecord.bom) {
        logger.warn({ 
            uuid: bomRecord.uuid,
            hasBomField: 'bom' in bomRecord,
            bomType: typeof bomRecord.bom
        }, 'BOM record missing bom field - possible data structure issue');
        return undefined;
    }
    
    // Check for empty object case
    if (typeof bomRecord.bom === 'object' && Object.keys(bomRecord.bom).length === 0) {
        logger.warn({
            uuid: bomRecord.uuid
        }, 'BOM field is empty object - possible data corruption or incomplete record');
        return undefined;
    }
    
    const repositoryName = bomRecord.bom.ociRepositoryName;
    
    // Log if bom field exists but has unexpected structure
    if (bomRecord.bom && !repositoryName && typeof bomRecord.bom === 'object') {
        logger.debug({ 
            uuid: bomRecord.uuid,
            bomKeys: Object.keys(bomRecord.bom).slice(0, 5) // First 5 keys for debugging
        }, 'BOM field exists but no ociRepositoryName found (likely legacy BOM)');
    }
    
    return repositoryName;
}

/**
 * Extract repository name from SPDX BOM record's oci_response.
 * Repository name is stored in oci_response.ociRepositoryName, NOT in spdx_metadata.
 * This maintains consistency with CycloneDX BOMs where it's stored in bom.ociRepositoryName.
 * 
 * @param ociResponse - OCI response object from SPDX BOM record
 * @returns Repository name or undefined if not present
 */
export function extractRepositoryNameFromSpdxOciResponse(ociResponse: OASResponse | null | undefined): string | undefined {
    return ociResponse?.ociRepositoryName;
}

/**
 * Validate that an OCI push result contains a repository name.
 * Throws OciStorageError if validation fails.
 * 
 * @param pushResult - Result from pushToOci operation
 * @param context - Context for error message (e.g., 'push', 'enrichment')
 * @param uuid - BOM UUID for error reporting
 * @throws OciStorageError if repository name is missing
 */
export function validateOciPushResult(
    pushResult: OASResponse,
    context: string,
    uuid: string
): void {
    if (!pushResult.ociRepositoryName) {
        throw new OciStorageError(
            `OCI ${context} succeeded but repository name is missing`,
            'push',
            uuid
        );
    }
}

/**
 * Verify that two OCI push results used the same repository.
 * This is critical for ensuring raw and processed BOMs stay together.
 * 
 * @param result1 - First push result (e.g., raw BOM)
 * @param result2 - Second push result (e.g., processed BOM)
 * @param context - Context for error message (e.g., 'upload', 'replacement')
 * @param uuid - BOM UUID for error reporting
 * @throws OciStorageError if repositories don't match
 */
export function validateRepositoryMatch(
    result1: OASResponse,
    result2: OASResponse,
    context: string,
    uuid: string
): void {
    if (result1.ociRepositoryName !== result2.ociRepositoryName) {
        logger.error({
            repo1: result1.ociRepositoryName,
            repo2: result2.ociRepositoryName,
            context,
            uuid
        }, 'CRITICAL: Repository mismatch detected');
        
        throw new OciStorageError(
            `Repository mismatch during ${context}: first BOM in ${result1.ociRepositoryName}, second BOM in ${result2.ociRepositoryName}`,
            'push',
            uuid
        );
    }
}


/**
 * Validate and extract repository name from OCI push result.
 * Combines validation and extraction in one step.
 * 
 * @param pushResult - Result from pushToOci operation
 * @param context - Context for error message
 * @param uuid - BOM UUID for error reporting
 * @returns Repository name
 * @throws OciStorageError if repository name is missing
 */
export function getValidatedRepositoryName(
    pushResult: OASResponse,
    context: string,
    uuid: string
): string {
    validateOciPushResult(pushResult, context, uuid);
    return pushResult.ociRepositoryName!;
}

/**
 * Complete validation for dual BOM push (raw + processed).
 * Validates both results and ensures they match.
 * 
 * @param rawResult - Raw BOM push result
 * @param processedResult - Processed BOM push result
 * @param context - Context for error message (e.g., 'upload', 'replacement')
 * @param uuid - BOM UUID for error reporting
 * @throws OciStorageError if validation fails
 */
export function validateDualBomPush(
    rawResult: OASResponse,
    processedResult: OASResponse,
    context: string,
    uuid: string
): void {
    // Validate both have repository names
    validateOciPushResult(rawResult, `${context} (raw)`, uuid);
    validateOciPushResult(processedResult, `${context} (processed)`, uuid);
    
    // Validate they match
    validateRepositoryMatch(rawResult, processedResult, context, uuid);
}

/**
 * Fetches raw BOM with automatic fallback for legacy BOMs.
 * First tries to fetch with '-raw' suffix, then falls back to UUID without suffix.
 * @param bomUuid - BOM UUID (without -raw suffix)
 * @param repositoryName - Repository name to fetch from
 * @param fetchFromOci - fetchFromOci function to use
 * @param expectedDigest - Optional expected SHA256 digest for validation
 * @returns Raw BOM content
 * @throws Error if both attempts fail
 */
export async function fetchRawBomWithFallback(
    bomUuid: string,
    repositoryName: string | undefined,
    fetchFromOci: (tag: string, repo?: string, digest?: string) => Promise<any>,
    expectedDigest?: string
): Promise<any> {
    const rawBomUuid = bomUuid + '-raw';
    
    try {
        logger.debug({ rawBomUuid, bomUuid, repositoryName }, 'Fetching raw CycloneDX BOM');
        return await fetchFromOci(rawBomUuid, repositoryName, expectedDigest);
    } catch (error) {
        // Fallback for older BOMs without -raw suffix
        logger.warn({ 
            rawBomUuid, 
            bomUuid, 
            repositoryName,
            error: error instanceof Error ? error.message : String(error) 
        }, 'Failed to fetch raw BOM with -raw suffix, retrying without suffix for legacy BOM');
        return await fetchFromOci(bomUuid, repositoryName, expectedDigest);
    }
}
