import * as fs from 'fs';
import * as path from 'path';
import { v4 as uuidv4 } from 'uuid';
import validateBom from '../src/validateBom';

/**
 * Test helper utilities for rebom-backend tests
 */

// Test organization UUID (consistent across tests)
export const TEST_ORG_UUID = '5890423f-2e70-4e92-9abc-3fbb8c9ddb96';

/**
 * Load a test fixture file
 */
export function loadFixture(filename: string): any {
    const fixturePath = path.join(__dirname, 'fixtures', filename);
    const content = fs.readFileSync(fixturePath, 'utf-8');
    return JSON.parse(content);
}

/**
 * Generate a unique serial number for test BOMs
 */
export function generateSerialNumber(): string {
    return `urn:uuid:${uuidv4()}`;
}

/**
 * Create test RebomOptions with defaults
 * IMPORTANT: Always use 'oci' storage for tests (db storage is deprecated)
 */
export function createTestRebomOptions(overrides: any = {}): any {
    return {
        serialNumber: overrides.serialNumber || generateSerialNumber(),
        name: overrides.name || 'test-component',
        group: overrides.group || 'com.test',
        version: overrides.version || '1.0.0',
        purl: overrides.purl,
        structure: overrides.structure || 'FLAT',
        rootComponentMergeMode: overrides.rootComponentMergeMode,
        tldOnly: overrides.tldOnly !== undefined ? overrides.tldOnly : false,
        ignoreDev: overrides.ignoreDev !== undefined ? overrides.ignoreDev : false,
        storage: 'oci', // Always use OCI storage for tests
        mod: overrides.mod || 'raw',
        bomState: overrides.bomState || 'raw',
        stripBom: overrides.stripBom || 'false',
        belongsTo: overrides.belongsTo || '',
        hash: overrides.hash,
        originalFileDigest: overrides.originalFileDigest,
        originalFileSize: overrides.originalFileSize,
        originalMediaType: overrides.originalMediaType,
        notes: overrides.notes || '',
        bomVersion: overrides.bomVersion || '1',
        bomDigest: overrides.bomDigest
    };
}

/**
 * Wait for a condition to be true (useful for async operations)
 */
export async function waitFor(
    condition: () => boolean | Promise<boolean>,
    timeout: number = 5000,
    interval: number = 100
): Promise<void> {
    const startTime = Date.now();
    while (Date.now() - startTime < timeout) {
        if (await condition()) {
            return;
        }
        await new Promise(resolve => setTimeout(resolve, interval));
    }
    throw new Error(`Timeout waiting for condition after ${timeout}ms`);
}

/**
 * Clean up test data from database (use with caution)
 */
export async function cleanupTestBoms(pool: any, serialNumbers: string[]): Promise<void> {
    if (serialNumbers.length === 0) return;
    
    const placeholders = serialNumbers.map((_, i) => `$${i + 1}`).join(',');
    await pool.query(
        `DELETE FROM rebom.boms WHERE meta->>'serialNumber' IN (${placeholders})`,
        serialNumbers
    );
}

/**
 * Validate CycloneDX BOM structure using official CycloneDX validator
 * Ensures BOM conforms to spec requirements
 */
export async function validateCycloneDxStructure(bom: any, specVersion: string = '1.6'): Promise<void> {
    if (!bom) {
        throw new Error('BOM is null or undefined');
    }
    
    // Use official CycloneDX validator from validateBom.ts
    const isValid = await validateBom(bom);
    if (!isValid) {
        throw new Error('BOM failed CycloneDX validation');
    }
    
    // Additional regression check: dependsOn must be array
    if (bom.dependencies && Array.isArray(bom.dependencies)) {
        bom.dependencies.forEach((dep: any, index: number) => {
            if ('dependsOn' in dep && !Array.isArray(dep.dependsOn)) {
                throw new Error(`dependencies[${index}].dependsOn must be an array, got ${typeof dep.dependsOn}`);
            }
        });
    }
}

/**
 * Validate BOM metadata structure
 */
export function validateBomMetadata(meta: any): void {
    if (!meta) {
        throw new Error('Metadata is null or undefined');
    }
    if (!meta.serialNumber || !meta.serialNumber.match(/^urn:uuid:/)) {
        throw new Error(`Invalid metadata serialNumber: ${meta.serialNumber}`);
    }
    if (!meta.bomVersion) {
        throw new Error('Metadata missing bomVersion');
    }
}

/**
 * Validate BOM has rebom tool in metadata
 */
export function validateRebomTool(bom: any, specVersion: string = '1.6'): void {
    if (!bom.metadata || !bom.metadata.tools) {
        throw new Error('BOM missing metadata.tools');
    }
    
    const tools = specVersion === '1.6' ? bom.metadata.tools.components : bom.metadata.tools;
    if (!Array.isArray(tools)) {
        throw new Error('Tools must be an array');
    }
    
    const rebomTool = tools.find((t: any) => t.name === 'rebom');
    if (!rebomTool) {
        throw new Error('BOM missing rebom tool in metadata');
    }
    if (rebomTool.type !== 'application') {
        throw new Error(`rebom tool type should be 'application', got '${rebomTool.type}'`);
    }
    
    // Spec 1.6 uses 'authors' array, older specs use 'author' string
    if (specVersion === '1.6') {
        if (!rebomTool.authors || !Array.isArray(rebomTool.authors)) {
            throw new Error('Spec 1.6 rebom tool should have authors array');
        }
    }
}
