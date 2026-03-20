import axios, { AxiosResponse } from 'axios';
import FormData from 'form-data';
import { logger } from '../../logger';
import { createHash } from 'crypto';

const client = axios.create({
    baseURL: process.env.OCI_ARTIFACT_SERVICE_HOST ? process.env.OCI_ARTIFACT_SERVICE_HOST : `http://[::1]:8083/`,
  });

// OCI Configuration Constants
const OCI_CONFIG = {
    DEFAULT_NAMESPACE: 'default-namespace',
    REPOSITORY_SUFFIX: 'rebom-artifacts',
    TAG_PREFIX: 'rebom-',
    URN_PREFIX: 'urn:uuid:',
    DIGEST_PREFIX: 'sha256:'
} as const;

// Validate and get namespace
const getNamespace = (): string => {
  const namespace = process.env.OCIARTIFACTS_REGISTRY_NAMESPACE;
  if (!namespace) {
    logger.warn('OCIARTIFACTS_REGISTRY_NAMESPACE not set, using default namespace');
    return OCI_CONFIG.DEFAULT_NAMESPACE;
  }
  return namespace;
};

const baseRepository = `${getNamespace()}/${OCI_CONFIG.REPOSITORY_SUFFIX}`;

/**
 * Construct full repository path from repository name
 * @param repositoryName Optional monthly repository name (e.g., 'rebom-artifacts-2026-03')
 * @returns Full repository path with namespace
 */
function constructFullRepositoryPath(repositoryName?: string): string {
    // Check for both null/undefined and empty/whitespace strings
    if (!repositoryName?.trim()) {
        logger.debug('No repository name provided, using base repository');
        return baseRepository;
    }
    
    // Validate repository name format
    if (!validateRepositoryName(repositoryName)) {
        logger.warn({ repositoryName }, 'Invalid repository name format, using base repository');
        return baseRepository;
    }
    
    return `${getNamespace()}/${repositoryName}`;
}

/**
 * Validate repository name format
 * @param name Repository name to validate
 * @returns true if valid, false otherwise
 */
function validateRepositoryName(name: string): boolean {
    if (!name || !name.trim()) {
        return false;
    }
    // Repository name should not contain namespace separator
    if (name.includes('/')) {
        return false;
    }
    // Should follow expected pattern: rebom-artifacts-YYYY-MM or rebom-artifacts
    // Use constant to avoid hardcoded string duplication
    const validPattern = new RegExp(`^${OCI_CONFIG.REPOSITORY_SUFFIX}(-\\d{4}-\\d{2})?$`);
    return validPattern.test(name);
}

/**
 * Generate monthly repository name based on current UTC date
 * Format: rebom-artifacts-YYYY-MM (e.g., rebom-artifacts-2026-03)
 * @param timestamp Optional timestamp to use instead of current time (for consistency across operations)
 * @returns Repository name without namespace (e.g., 'rebom-artifacts-2026-03')
 */
export function getMonthlyRepositoryName(timestamp?: Date): string {
    const now = timestamp || new Date();
    const year = now.getUTCFullYear();
    const month = String(now.getUTCMonth() + 1).padStart(2, '0');
    const suffix = `${year}-${month}`;
    return `${OCI_CONFIG.REPOSITORY_SUFFIX}-${suffix}`;
}

export async function fetchFromOci(tag: string, repositoryName?: string, expectedDigest?: string): Promise<Object>{
    if(tag.startsWith(OCI_CONFIG.URN_PREFIX)){
        tag = tag.replace(OCI_CONFIG.URN_PREFIX, "")
    }
    // Don't add rebom- prefix to digests (sha256:...)
    if(!tag.startsWith(OCI_CONFIG.TAG_PREFIX) && !tag.startsWith(OCI_CONFIG.DIGEST_PREFIX)){
        tag = OCI_CONFIG.TAG_PREFIX + tag
    }
    
    // Use provided repository name or fall back to base repository for legacy artifacts
    const repo = constructFullRepositoryPath(repositoryName);
    const isFallback = !repositoryName?.trim() || !validateRepositoryName(repositoryName);
    
    // Log if we're falling back to default repository
    if (!repositoryName?.trim()) {
        logger.info({ tag, repository: repo }, "No repository name provided, using default base repository for fetch (likely legacy BOM)");
    } else if (!validateRepositoryName(repositoryName)) {
        // CRITICAL: Invalid repository name suggests data corruption
        logger.error({ 
            tag, 
            invalidRepositoryName: repositoryName, 
            repository: repo 
        }, "Invalid repository name format detected - possible data corruption. Falling back to base repository, but fetch may fail.");
    }
    
    logger.debug({ originalTag: arguments[0], processedTag: tag, repository: repo, providedRepositoryName: repositoryName }, "Fetching from OCI");
    
    try {
        // Get raw response data for digest validation
        // transformResponse: [] prevents axios from parsing JSON automatically
        const resp: AxiosResponse = await client.get('/pull', { 
            params: {
                repo: repo,
                tag: tag
            },
            headers: {
                Accept: 'application/json'
            },
            transformResponse: [] // Get raw response as string/buffer
        });
        
        logger.debug({ 
            status: resp.status, 
            dataType: typeof resp.data
        }, "OCI response received");
        
        // Validate downloaded artifact digest if expected digest is provided
        // Must validate BEFORE parsing to match how OCI service calculated the digest
        if (expectedDigest) {
            // resp.data is the raw response string/buffer
            const rawContent = typeof resp.data === 'string' ? resp.data : Buffer.from(resp.data).toString();
            const actualDigest = createHash('sha256').update(rawContent).digest('hex');
            
            if (actualDigest !== expectedDigest) {
                const error = new Error(`Digest validation failed for artifact. Expected: ${expectedDigest}, Actual: ${actualDigest}`);
                logger.error({ 
                    tag, 
                    repository: repo,
                    expectedDigest,
                    actualDigest
                }, "Downloaded artifact digest does not match stored digest");
                throw error;
            }
            
            logger.debug({ tag, repository: repo, digest: actualDigest, expected: expectedDigest }, "Artifact digest validated successfully");
        } else {
            logger.warn({ tag, repository: repo }, "No expected digest provided for artifact validation - skipping digest check");
        }
        
        // Parse JSON after validation
        const bom: any = JSON.parse(typeof resp.data === 'string' ? resp.data : Buffer.from(resp.data).toString());
        return bom
    } catch (error) {
        const errorMessage = error instanceof Error ? error.message : String(error);
        
        // Provide more context if we fell back to base repository
        if (isFallback && repositoryName) {
            logger.error({ 
                tag, 
                repository: repo,
                providedRepositoryName: repositoryName,
                error: errorMessage 
            }, "OCI fetch failed from fallback repository - BOM may be in monthly repository but metadata is missing/invalid");
        } else if (isFallback) {
            logger.error({ 
                tag, 
                repository: repo,
                error: errorMessage 
            }, "OCI fetch failed from base repository - this may be a legacy BOM that was migrated");
        } else {
            logger.error({ 
                tag, 
                repository: repo,
                repositoryName,
                error: errorMessage 
            }, "OCI fetch failed from specified repository");
        }
        
        throw error;
    }
}

export type OASResponse = {
    ociResponse?: OciResponse,
    fileSHA256Digest?: string,
    compressed?: boolean,
    compressionStats?: string,
    originalMediaType?: string,
    originalSize?: number,
    compressedSize?: number,
    ociRepositoryName?: string
}
export type OciResponse = {
    mediaType?: string,
    digest?: string,
    size?: string,
    artifactType?: string
}

/**
 * Push a BOM to OCI storage
 * @param tag - UUID or identifier for the BOM
 * @param bom - BOM content to store
 * @param repositoryName - Optional repository name (without namespace). If not provided, uses current month.
 * @returns OASResponse with repository name included
 */
export async function pushToOci(tag: string, bom: any, repositoryName?: string): Promise<OASResponse>{
    if(tag.startsWith(OCI_CONFIG.URN_PREFIX)){
        tag = tag.replace(OCI_CONFIG.URN_PREFIX, "")
    }
    if(!tag.startsWith(OCI_CONFIG.TAG_PREFIX)){
        tag = OCI_CONFIG.TAG_PREFIX + tag
    }
    
    // Use provided repository name or generate monthly repository for current month
    const repoName = repositoryName || getMonthlyRepositoryName();
    
    // Validate repository name
    if (!validateRepositoryName(repoName)) {
        const error = new Error(`Invalid repository name format: ${repoName}`);
        logger.error({ tag, repositoryName: repoName }, 'Invalid repository name');
        throw error;
    }
    
    // Construct full repository path with namespace
    const fullRepoPath = constructFullRepositoryPath(repoName);
    
    let resp: OASResponse = {}
    const formData = new FormData();
    formData.append('repo', fullRepoPath)
    formData.append('tag', tag)
    const jsonBuffer = Buffer.from(JSON.stringify(bom));
    formData.append('file', jsonBuffer, 'file.json')
    
    logger.debug({ tag, repository: fullRepoPath, repositoryName: repoName }, "Pushing to OCI with monthly repository");
    
    try {
        const response = await client.post('/push', formData, {
            headers: {
            ...formData.getHeaders(),
            },
        });
        resp = response.data;
    } catch (error) {
        logger.error({ tag, repository: fullRepoPath, error: error instanceof Error ? error.message : String(error) }, 'Error pushing to OCI');
        throw error;
    }
    
    // Validate response and set repository name
    if (!resp) {
        throw new Error('OCI push returned empty response');
    }
    
    resp.ociRepositoryName = repoName;
    
    return resp;
}
