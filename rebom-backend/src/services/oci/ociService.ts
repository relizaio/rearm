import axios, { AxiosResponse } from 'axios';
import FormData from 'form-data';
import { createHash } from 'crypto';
import { logger } from '../../logger';

// Mock OCI storage for CI/testing
const MOCK_OCI = process.env.MOCK_OCI === 'true';
const mockStorage = new Map<string, any>();
const tagToDigestMap = new Map<string, string>();

// Generate mock digest for content
function generateMockDigest(content: any): string {
    const str = JSON.stringify(content);
    return createHash('sha256').update(str).digest('hex');
}

const client = axios.create({
    baseURL: process.env.OCI_ARTIFACT_SERVICE_HOST ? process.env.OCI_ARTIFACT_SERVICE_HOST : `http://[::1]:8083/`,
  });

const registryHost = process.env.OCIARTIFACTS_REGISTRY_HOST 
const repository = process.env.OCIARTIFACTS_REGISTRY_NAMESPACE + '/rebom-artifacts'

export async function fetchFromOci(tag: string): Promise<Object>{
    // Mock mode for tests
    if (MOCK_OCI) {
        const originalTag = tag;
        
        // Apply same transformations as real OCI logic
        if(tag.startsWith("urn")){
            tag = tag.replace("urn:uuid:","")
        }
        if(!tag.startsWith('rebom') && !tag.startsWith('sha256:')){
            tag = 'rebom-' + tag
        }
        
        logger.debug({ originalTag, transformedTag: tag }, "Fetching from mock OCI storage");
        
        // Try direct lookup first
        let data = mockStorage.get(tag);
        
        // If not found and tag doesn't look like a digest, try via tag-to-digest mapping
        if (!data && !tag.startsWith('sha256:')) {
            const digest = tagToDigestMap.get(tag);
            if (digest) {
                logger.debug({ tag, digest }, "Found digest mapping for tag");
                data = mockStorage.get(digest);
            }
        }
        
        if (!data) {
            throw new Error(`Mock OCI: Tag not found: ${tag}`);
        }
        return data;
    }
    
    // Real OCI logic
    if(tag.startsWith("urn")){
        tag = tag.replace("urn:uuid:","")
    }
    // Don't add rebom- prefix to digests (sha256:...)
    if(!tag.startsWith('rebom') && !tag.startsWith('sha256:')){
        tag = 'rebom-' + tag
    }
    logger.debug({ originalTag: arguments[0], processedTag: tag }, "Fetching from OCI");
    
    try {
        const resp: AxiosResponse = await client.get('/pull', { 
            params: {
                registry: registryHost,
                repo: repository,
                tag: tag
            },
            headers: {
                Accept: 'application/json'
            }
        });
        
        logger.debug({ 
            status: resp.status, 
            dataType: typeof resp.data,
            dataKeys: resp.data ? Object.keys(resp.data) : 'null'
        }, "OCI response received");
        
        const bom: any = resp.data
        return bom
    } catch (error) {
        logger.error({ tag, error: error instanceof Error ? error.message : String(error) }, "OCI fetch failed");
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
    compressedSize?: number
}
export type OciResponse = {
    mediaType?: string,
    digest?: string,
    size?: string,
    artifactType?: string
}

export async function pushToOci(tag: string, bom: any): Promise<OASResponse>{
    if(tag.startsWith("urn")){
        tag = tag.replace("urn:uuid:","")
    }
    if(!tag.startsWith('rebom')){
        tag = 'rebom-' + tag
    }
    
    // Mock mode for tests
    if (MOCK_OCI) {
        const digest = `sha256:${generateMockDigest(bom)}`;
        logger.debug({ tag, digest }, "Storing in mock OCI storage");
        
        // Store by both tag and digest for proper retrieval
        mockStorage.set(tag, bom);
        mockStorage.set(digest, bom);
        tagToDigestMap.set(tag, digest);
        
        return {
            ociResponse: {
                digest: digest,
                size: String(JSON.stringify(bom).length),
                mediaType: 'application/json'
            },
            fileSHA256Digest: digest
        };
    }
    
    // Real OCI logic
    let resp: OASResponse = {}
    const formData = new FormData();
    formData.append('registry', registryHost)
    formData.append('repo', repository)
    formData.append('tag', tag)
    const jsonBuffer = Buffer.from(JSON.stringify(bom));
    formData.append('file', jsonBuffer, 'file.json')
    try {
        const response = await client.post('/push', formData, {
            headers: {
            ...formData.getHeaders(),
            },
        });
        resp = response.data
        } catch (error) {
            logger.error(`Error sending request: ${error}`);
        }
    return resp
}

// For tests: clear mock storage
export function clearMockOciStorage(): void {
    mockStorage.clear();
}