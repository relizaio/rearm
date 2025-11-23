import { createHash } from 'crypto';
import { logger } from './logger';
import { SpdxMetadata, ConversionResult, RebomOptions } from './types';
import { v4 as uuidv4 } from 'uuid';
import { shellExec, createTempFile, deleteTempFile } from './utils';

// Known SPDX conversion fixes
const SPDX_FIXES = {
    referenceCategory: {
        'PACKAGE-MANAGER': 'PACKAGE_MANAGER',
        'BUILD-SYSTEM': 'BUILD_SYSTEM',
        'PACKAGE-MANAGER-TOOL': 'PACKAGE_MANAGER',
        // Add more mappings as discovered
    }
};

export class SpdxService {
    /**
     * Extract key metadata from SPDX for database storage
     */
    static extractSpdxMetadata(spdxContent: any): SpdxMetadata {
        try {
            const originalSpdxId = spdxContent.SPDXID || spdxContent.spdxId || '';
            logger.debug({ spdxId: originalSpdxId, documentName: spdxContent.documentName }, "Extracting SPDX metadata");
            
            // Create a unique SPDXID by combining original ID with timestamp and document namespace hash
            // This prevents duplicate key constraint violations while preserving original SPDX structure
            const namespaceHash = spdxContent.documentNamespace ? 
                createHash('sha256').update(spdxContent.documentNamespace).digest('hex').substring(0, 8) : 
                'unknown';
            const timestamp = Date.now();
            const uniqueSpdxId = `${originalSpdxId}-${timestamp}-${namespaceHash}`;
            
            const metadata: SpdxMetadata = {
                SPDXID: uniqueSpdxId,
                originalSPDXID: originalSpdxId, // Preserve original for reference
                name: spdxContent.name,
                documentName: spdxContent.documentName,
                documentNamespace: spdxContent.documentNamespace,
            };

            // Extract creation info
            if (spdxContent.creationInfo) {
                metadata.creationInfo = {
                    created: spdxContent.creationInfo.created,
                    creators: spdxContent.creationInfo.creators,
                    licenseListVersion: spdxContent.creationInfo.licenseListVersion
                };
            }

            // Store additional top-level fields that might be useful
            if (spdxContent.dataLicense) metadata.dataLicense = spdxContent.dataLicense;
            if (spdxContent.spdxVersion) metadata.spdxVersion = spdxContent.spdxVersion;
            if (spdxContent.documentDescribes) metadata.documentDescribes = spdxContent.documentDescribes;

            logger.debug({ metadataKeys: Object.keys(metadata) }, "SPDX metadata extraction completed");
            return metadata;
        } catch (error) {
            logger.error({ err: error }, 'Error extracting SPDX metadata');
            throw new Error(`Failed to extract SPDX metadata: ${error instanceof Error ? error.message : String(error)}`);
        }
    }

    /**
     * Pre-process SPDX to fix known conversion issues
     */
    static preprocessSpdx(spdxContent: any): any {
        try {
            const processed = JSON.parse(JSON.stringify(spdxContent)); // Deep clone

            // Fix referenceCategory issues in externalDocumentRefs
            if (processed.externalDocumentRefs && Array.isArray(processed.externalDocumentRefs)) {
                processed.externalDocumentRefs.forEach((ref: any) => {
                    if (ref.referenceCategory && ref.referenceCategory in SPDX_FIXES.referenceCategory) {
                        const fixedCategory = SPDX_FIXES.referenceCategory[ref.referenceCategory as keyof typeof SPDX_FIXES.referenceCategory];
                        logger.info(`Fixing referenceCategory: ${ref.referenceCategory} -> ${fixedCategory}`);
                        ref.referenceCategory = fixedCategory;
                    }
                });
            }

            // Fix referenceCategory issues in packages' externalRefs
            if (processed.packages && Array.isArray(processed.packages)) {
                processed.packages.forEach((pkg: any) => {
                    if (pkg.externalRefs && Array.isArray(pkg.externalRefs)) {
                        pkg.externalRefs.forEach((ref: any) => {
                            if (ref.referenceCategory && ref.referenceCategory in SPDX_FIXES.referenceCategory) {
                                const fixedCategory = SPDX_FIXES.referenceCategory[ref.referenceCategory as keyof typeof SPDX_FIXES.referenceCategory];
                                logger.info(`Fixing package externalRef referenceCategory: ${ref.referenceCategory} -> ${fixedCategory}`);
                                ref.referenceCategory = fixedCategory;
                            }
                        });
                    }
                });
            }

            // Fix relationships referenceCategory
            if (processed.relationships && Array.isArray(processed.relationships)) {
                processed.relationships.forEach((rel: any) => {
                    if (rel.referenceCategory && rel.referenceCategory in SPDX_FIXES.referenceCategory) {
                        const fixedCategory = SPDX_FIXES.referenceCategory[rel.referenceCategory as keyof typeof SPDX_FIXES.referenceCategory];
                        logger.info(`Fixing relationship referenceCategory: ${rel.referenceCategory} -> ${fixedCategory}`);
                        rel.referenceCategory = fixedCategory;
                    }
                });
            }

            // Ensure required fields exist
            if (!processed.SPDXID && !processed.spdxId) {
                processed.SPDXID = `SPDXRef-DOCUMENT-${uuidv4()}`;
                logger.warn('Added missing SPDXID to document');
            }

            return processed;
        } catch (error) {
            logger.error({ err: error }, 'Error preprocessing SPDX');
            throw new Error(`Failed to preprocess SPDX: ${error instanceof Error ? error.message : String(error)}`);
        }
    }

    /**
     * Convert SPDX to CycloneDX using rearm-cli
     */
    static async convertSpdxToCycloneDx(spdxContent: any): Promise<ConversionResult> {
        let inputFile: string | null = null;
        let outputFile: string | null = null;

        try {
            logger.info({ spdxId: spdxContent.SPDXID || spdxContent.spdxId }, "Starting SPDX to CycloneDX conversion using rearm-cli");
            
            // Preprocess SPDX to fix known issues
            // const processedSpdx = this.preprocessSpdx(spdxContent);
            const processedSpdx = spdxContent
            logger.debug("SPDX preprocessing completed");

            // Create temporary files using utils
            inputFile = await createTempFile(processedSpdx);
            outputFile = await createTempFile({}); // Create empty output file
            logger.debug({ inputFile, outputFile }, "Created temporary files for conversion");

            // Run rearm-cli bomutils convert-spdx command using shellExec
            const args = [
                'bomutils',
                'convert-spdx',
                '--infile', inputFile,
                '--outfile', outputFile
            ];
            
            logger.info(`Running conversion: rearm-cli ${args.join(' ')}`);
            const result = await shellExec('rearm-cli', args, 60000); // 60 second timeout for rearm-cli
            logger.debug({ conversionOutput: result }, "rearm-cli conversion completed");

            // Validate the converted CycloneDX file
            const validateCommand = [
                'validate',
                '--input-file',
                outputFile
            ];
            try {
                logger.debug("Starting CycloneDX validation");
                await shellExec('cyclonedx-cli', validateCommand);
                logger.debug("CycloneDX validation passed");
            } catch (validationError) {
                logger.error({ validationError, outputFile }, "CycloneDX validation failed");
                throw new Error(`CycloneDX validation failed: ${validationError}`);
            }

            // Read converted CycloneDX file
            const convertedContent = await require('fs/promises').readFile(outputFile, 'utf8');
            const convertedBom = JSON.parse(convertedContent);
            logger.info({ 
                componentCount: convertedBom?.components?.length || 0,
                bomFormat: convertedBom?.bomFormat,
                specVersion: convertedBom?.specVersion,
                serialNumber: convertedBom?.serialNumber
            }, "SPDX to CycloneDX conversion successful using rearm-cli");

            // Clean up temporary files
            await Promise.all([
                deleteTempFile(inputFile),
                deleteTempFile(outputFile)
            ]);

            return {
                success: true,
                convertedBom,
                warnings: result ? [result] : undefined
            };

        } catch (error) {
            // Clean up temporary files on error
            if (inputFile) await deleteTempFile(inputFile);
            if (outputFile) await deleteTempFile(outputFile);

            logger.error({ error: error instanceof Error ? error.message : String(error), spdxId: spdxContent.SPDXID || spdxContent.spdxId }, 'SPDX to CycloneDX conversion failed');
            
            return {
                success: false,
                error: `Conversion failed: ${error}`
            };
        }
    }

    /**
     * Validate SPDX format
     */
    static validateSpdxFormat(content: any): boolean {
        try {
            // Basic SPDX validation
            if (!content || typeof content !== 'object') {
                return false;
            }

            // Check for required SPDX fields
            const hasRequiredFields = (
                (content.SPDXID || content.spdxId) &&
                (content.spdxVersion || content.version) &&
                content.dataLicense
            );

            if (!hasRequiredFields) {
                logger.warn('SPDX validation failed: missing required fields');
                return false;
            }

            // Check SPDX version format
            const version = content.spdxVersion || content.version;
            if (typeof version === 'string' && !version.startsWith('SPDX-')) {
                logger.warn('SPDX validation failed: invalid version format');
                return false;
            }

            return true;
        } catch (error) {
            logger.error({ err: error }, 'Error validating SPDX format');
            return false;
        }
    }

    /**
     * Generate RebomOptions from SPDX metadata
     */
    static generateRebomOptionsFromSpdx(spdxMetadata: SpdxMetadata): Partial<RebomOptions> {
        const firstPackage = spdxMetadata.packages?.[0];
        const metadataString = JSON.stringify(spdxMetadata);
        
        return {
            serialNumber: spdxMetadata.SPDXID || `urn:uuid:${uuidv4()}`,
            name: firstPackage?.name || spdxMetadata.documentName || spdxMetadata.name || 'SPDX Document',
            version: firstPackage?.versionInfo || '1.0.0',
            bomState: 'raw',
            mod: 'raw',
            storage: 'oci',
            bomVersion: '1',
            structure: 'flat',
            belongsTo: 'application',
            hash: createHash('sha256').update(metadataString).digest('hex').substring(0, 16),
            tldOnly: false,
            ignoreDev: false
        };
    }

    /**
     * Calculate SHA256 hash of SPDX content
     */
    static calculateSpdxHash(spdxContent: any): string {
        return createHash('sha256').update(JSON.stringify(spdxContent)).digest('hex');
    }
}
