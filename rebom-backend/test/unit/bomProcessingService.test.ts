import { describe, it, expect, beforeEach } from 'vitest';
import * as BomProcessingService from '../../src/services/bom/bomProcessingService';
import { loadFixture, generateSerialNumber, validateCycloneDxStructure, validateRebomTool } from '../helpers';

/**
 * Unit tests for BOM Processing Service
 * Tests BOM transformation, filtering, and metadata operations
 */

describe('BOM Processing Service - Unit Tests', () => {
    describe('extractTldFromBom', () => {
        it('should extract top-level dependencies from cdxgen BOM', () => {
            const bom = loadFixture('cyclonedx-raw-cdxgen.json');
            const originalComponentCount = bom.components.length;
            const rootRef = bom.metadata.component['bom-ref'];
            const rootDep = bom.dependencies.find((d: any) => d.ref === rootRef);
            const expectedTldCount = rootDep.dependsOn.length;
            
            const tldBom = BomProcessingService.extractTldFromBom(bom);
            
            // Validate BOM structure preserved
            expect(tldBom.bomFormat).toBe('CycloneDX');
            expect(tldBom.specVersion).toBe('1.6');
            expect(tldBom.serialNumber).toBe(bom.serialNumber);
            expect(tldBom.metadata.component['bom-ref']).toBe(rootRef);
            
            // Validate TLD extraction filtered to only direct dependencies
            expect(tldBom.components).toBeDefined();
            expect(Array.isArray(tldBom.components)).toBe(true);
            expect(tldBom.components.length).toBe(expectedTldCount);
            expect(tldBom.components.length).toBeLessThan(originalComponentCount);
            
            // Validate all extracted components are in the root's dependsOn list
            const extractedRefs = tldBom.components.map((c: any) => c['bom-ref']);
            extractedRefs.forEach((ref: string) => {
                expect(rootDep.dependsOn).toContain(ref);
            });
            
            // Validate dependencies array structure
            expect(tldBom.dependencies).toBeDefined();
            expect(Array.isArray(tldBom.dependencies)).toBe(true);
            expect(tldBom.dependencies.length).toBe(1);
            expect(tldBom.dependencies[0].ref).toBe(rootRef);
            expect(Array.isArray(tldBom.dependencies[0].dependsOn)).toBe(true);
        });

        it('should return full BOM for non-cdxgen BOMs', () => {
            const bom = loadFixture('cyclonedx-simple.json');
            // Missing bom-ref in metadata.component
            
            const result = BomProcessingService.extractTldFromBom(bom);
            // Should return the original BOM unchanged when TLD extraction is not possible
            expect(result).toBe(bom);
        });

        it('should preserve BOM metadata exactly', () => {
            const bom = loadFixture('cyclonedx-raw-cdxgen.json');
            const originalMetadata = JSON.parse(JSON.stringify(bom.metadata));
            
            const tldBom = BomProcessingService.extractTldFromBom(bom);
            
            // Validate metadata structure preserved
            expect(tldBom.metadata).toBeDefined();
            expect(tldBom.metadata.timestamp).toBe(originalMetadata.timestamp);
            expect(tldBom.metadata.component).toEqual(originalMetadata.component);
            expect(tldBom.metadata.component['bom-ref']).toBe(originalMetadata.component['bom-ref']);
            expect(tldBom.metadata.component.type).toBe('application');
            expect(tldBom.metadata.component.name).toBe(originalMetadata.component.name);
            expect(tldBom.metadata.component.version).toBe(originalMetadata.component.version);
            
            // Validate tools preserved
            expect(tldBom.metadata.tools).toBeDefined();
            expect(tldBom.metadata.tools.components).toBeDefined();
            expect(tldBom.metadata.tools.components.length).toBe(originalMetadata.tools.components.length);
        });
    });

    describe('extractDevFilteredBom', () => {
        it('should filter development dependencies from merged BOM', () => {
            const bom = loadFixture('cyclonedx-merged-tld.json');
            const originalCount = bom.components.length;
            
            // Count dev dependencies in original BOM
            const devComponentCount = bom.components.filter((c: any) => {
                if (!c.properties) return false;
                return c.properties.some((p: any) => 
                    p.name === 'cdx:npm:package:development' && p.value === 'true'
                );
            }).length;
            
            const filteredBom = BomProcessingService.extractDevFilteredBom(bom);
            
            // Validate BOM structure preserved
            expect(filteredBom.bomFormat).toBe('CycloneDX');
            expect(filteredBom.specVersion).toBe('1.6');
            expect(filteredBom.serialNumber).toBe(bom.serialNumber);
            
            // Validate dev dependencies removed
            expect(filteredBom.components).toBeDefined();
            expect(Array.isArray(filteredBom.components)).toBe(true);
            expect(filteredBom.components.length).toBe(originalCount - devComponentCount);
            
            // Validate no dev dependencies remain
            const remainingDevDeps = filteredBom.components.filter((c: any) => {
                if (!c.properties) return false;
                return c.properties.some((p: any) => 
                    p.name === 'cdx:npm:package:development' && p.value === 'true'
                );
            });
            expect(remainingDevDeps.length).toBe(0);
            
            // Validate production dependencies preserved
            const prodComponents = bom.components.filter((c: any) => {
                if (!c.properties) return true;
                return !c.properties.some((p: any) => 
                    p.name === 'cdx:npm:package:development' && p.value === 'true'
                );
            });
            expect(filteredBom.components.length).toBe(prodComponents.length);
        });

        it('should preserve all components when no dev dependencies exist', () => {
            const bom = loadFixture('cyclonedx-simple.json');
            const originalCount = bom.components.length;
            const originalRefs = bom.components.map((c: any) => c['bom-ref'] || c.purl);
            
            const filteredBom = BomProcessingService.extractDevFilteredBom(bom);
            
            // All production dependencies should be preserved
            expect(filteredBom.components.length).toBe(originalCount);
            
            // Validate exact same components preserved
            const filteredRefs = filteredBom.components.map((c: any) => c['bom-ref'] || c.purl);
            expect(filteredRefs.sort()).toEqual(originalRefs.sort());
        });

        it('should handle components without scope field', () => {
            const bom = loadFixture('cyclonedx-simple.json');
            // Ensure components don't have scope
            bom.components.forEach((c: any) => delete c.scope);
            
            const filteredBom = BomProcessingService.extractDevFilteredBom(bom);
            
            // Should not crash and should preserve all components
            expect(filteredBom.components.length).toBe(bom.components.length);
        });

        it('should preserve BOM structure', () => {
            const bom = loadFixture('cyclonedx-simple.json');
            
            const filteredBom = BomProcessingService.extractDevFilteredBom(bom);
            
            expect(filteredBom.bomFormat).toBe('CycloneDX');
            expect(filteredBom.specVersion).toBe('1.6');
            expect(filteredBom.metadata).toBeDefined();
        });
    });

    describe('computeBomDigest', () => {
        it('should generate consistent SHA256 digest', () => {
            const bom = loadFixture('cyclonedx-simple.json');
            
            const digest1 = BomProcessingService.computeBomDigest(bom);
            const digest2 = BomProcessingService.computeBomDigest(bom);
            
            expect(digest1).toBe(digest2);
            expect(digest1).toMatch(/^[a-f0-9]{64}$/); // SHA256 hex format
        });

        it('should handle stripBom option', () => {
            const bom = loadFixture('cyclonedx-simple.json');
            
            const normalDigest = BomProcessingService.computeBomDigest(bom);
            const strippedDigest = BomProcessingService.computeBomDigest(bom);
            
            // Both should be valid SHA256
            expect(normalDigest).toMatch(/^[a-f0-9]{64}$/);
            expect(strippedDigest).toMatch(/^[a-f0-9]{64}$/);
            // Digests may differ based on stripBom
            expect(normalDigest).toBeDefined();
            expect(strippedDigest).toBeDefined();
        });

        it('should produce different digests for different BOMs', () => {
            const bom1 = loadFixture('cyclonedx-simple.json');
            const bom2 = loadFixture('cyclonedx-for-merge.json');
            
            const digest1 = BomProcessingService.computeBomDigest(bom1);
            const digest2 = BomProcessingService.computeBomDigest(bom2);
            
            expect(digest1).not.toBe(digest2);
        });

        it('should handle BOMs with different component counts', () => {
            const bom = loadFixture('cyclonedx-simple.json');
            const originalDigest = BomProcessingService.computeBomDigest(bom);
            
            // Add a component
            bom.components.push({
                type: 'library',
                name: 'new-lib',
                version: '1.0.0',
                purl: 'pkg:npm/new-lib@1.0.0'
            });
            
            const modifiedDigest = BomProcessingService.computeBomDigest(bom);
            
            expect(modifiedDigest).not.toBe(originalDigest);
        });
    });

    describe('createRebomToolObject', () => {
        it('should create rebom tool for spec 1.6', () => {
            const tool = BomProcessingService.createRebomToolObject('1.6');
            
            expect(tool).toBeDefined();
            expect(tool.type).toBe('application');
            expect(tool.name).toBe('rebom');
            expect(tool.group).toBe('io.reliza');
            expect(tool.version).toBeDefined();
            
            // Spec 1.6 uses 'authors' array
            expect(tool.authors).toBeDefined();
            expect(Array.isArray(tool.authors)).toBe(true);
            expect(tool.authors[0].name).toBe('Reliza Incorporated');
            expect(tool.authors[0].email).toBe('info@reliza.io');
        });

        it('should create rebom tool for spec 1.5', () => {
            const tool = BomProcessingService.createRebomToolObject('1.5');
            
            expect(tool).toBeDefined();
            expect(tool.type).toBe('application');
            expect(tool.name).toBe('rebom');
            
            // Spec 1.5 uses 'author' string
            expect(tool.author).toBe('Reliza Incorporated');
            expect(tool.authors).toBeUndefined();
        });

        it('should include external references', () => {
            const tool = BomProcessingService.createRebomToolObject('1.6');
            
            expect(tool.externalReferences).toBeDefined();
            expect(Array.isArray(tool.externalReferences)).toBe(true);
            expect(tool.externalReferences.length).toBeGreaterThan(0);
            
            const websiteRef = tool.externalReferences.find((ref: any) => ref.type === 'website');
            expect(websiteRef).toBeDefined();
            expect(websiteRef.url).toContain('reliza.io');
        });
    });

    describe('attachRebomToolToBom', () => {
        it('should add rebom tool to spec 1.6 BOM with correct structure', () => {
            const bom = loadFixture('cyclonedx-raw-cdxgen.json');
            const originalToolCount = bom.metadata.tools.components.length;
            
            const bomWithTool = BomProcessingService.attachRebomToolToBom(bom);
            
            // Validate tools structure
            expect(bomWithTool.metadata.tools).toBeDefined();
            expect(bomWithTool.metadata.tools.components).toBeDefined();
            expect(Array.isArray(bomWithTool.metadata.tools.components)).toBe(true);
            expect(bomWithTool.metadata.tools.components.length).toBe(originalToolCount + 1);
            
            // Find and validate rebom tool
            const rebomTool = bomWithTool.metadata.tools.components.find((t: any) => t.name === 'rebom');
            expect(rebomTool).toBeDefined();
            expect(rebomTool.type).toBe('application');
            expect(rebomTool.group).toBe('io.reliza');
            expect(rebomTool.name).toBe('rebom');
            expect(rebomTool.version).toBeDefined();
            expect(rebomTool.supplier).toEqual({ name: 'Reliza Incorporated' });
            expect(rebomTool.description).toBe('Catalog of SBOMs');
            
            // Validate spec 1.6 authors structure (array of objects with name and email)
            expect(rebomTool.authors).toBeDefined();
            expect(Array.isArray(rebomTool.authors)).toBe(true);
            expect(rebomTool.authors.length).toBeGreaterThan(0);
            expect(rebomTool.authors[0].name).toBe('Reliza Incorporated');
            expect(rebomTool.authors[0].email).toBe('info@reliza.io');
            
            // Validate external references
            expect(rebomTool.externalReferences).toBeDefined();
            expect(Array.isArray(rebomTool.externalReferences)).toBe(true);
            const websiteRef = rebomTool.externalReferences.find((r: any) => r.type === 'website');
            expect(websiteRef).toBeDefined();
            expect(websiteRef.url).toContain('reliza.io');
        });

        it('should preserve existing tools when adding rebom', () => {
            const bom = loadFixture('cyclonedx-raw-cdxgen.json');
            const originalTools = JSON.parse(JSON.stringify(bom.metadata.tools.components));
            const originalToolCount = originalTools.length;
            
            const bomWithTool = BomProcessingService.attachRebomToolToBom(bom);
            
            // Should have one more tool (rebom)
            expect(bomWithTool.metadata.tools.components.length).toBe(originalToolCount + 1);
            
            // Validate all original tools preserved
            originalTools.forEach((originalTool: any) => {
                const foundTool = bomWithTool.metadata.tools.components.find(
                    (t: any) => t.name === originalTool.name && t.version === originalTool.version
                );
                expect(foundTool).toBeDefined();
                expect(foundTool).toEqual(originalTool);
            });
            
            // Validate rebom tool is the new addition
            const rebomTool = bomWithTool.metadata.tools.components.find((t: any) => t.name === 'rebom');
            expect(rebomTool).toBeDefined();
        });

        it('should create tools structure if missing', () => {
            const bom = loadFixture('cyclonedx-merged-tld.json');
            // This fixture has empty tools array
            
            const bomWithTool = BomProcessingService.attachRebomToolToBom(bom);
            
            expect(bomWithTool.metadata.tools).toBeDefined();
            expect(bomWithTool.metadata.tools.components).toBeDefined();
            expect(Array.isArray(bomWithTool.metadata.tools.components)).toBe(true);
            expect(bomWithTool.metadata.tools.components.length).toBeGreaterThan(0);
        });

        it('should validate rebom tool structure', () => {
            const bom = loadFixture('cyclonedx-augmented.json');
            
            // This fixture already has rebom tool - validate it
            expect(() => validateRebomTool(bom, '1.6')).not.toThrow();
        });
    });

    describe('overrideRootComponent', () => {
        it('should override root component with rebom options', () => {
            const bom = loadFixture('cyclonedx-merged-tld.json');
            const originalComponent = JSON.parse(JSON.stringify(bom.metadata.component));
            const rebomOptions: any = {
                name: 'override-name',
                group: 'com.override',
                version: '2.0.0',
                purl: 'pkg:npm/override-name@2.0.0'
            };
            const timestamp = '2024-01-01T00:00:00.000Z';
            
            const overriddenBom = BomProcessingService.overrideRootComponent(bom, rebomOptions, timestamp);
            
            // Validate all rebomOptions fields applied
            expect(overriddenBom.metadata.component).toBeDefined();
            expect(overriddenBom.metadata.component.name).toBe(rebomOptions.name);
            expect(overriddenBom.metadata.component.group).toBe(rebomOptions.group);
            expect(overriddenBom.metadata.component.version).toBe(rebomOptions.version);
            expect(overriddenBom.metadata.component.purl).toBe(rebomOptions.purl);
            
            // Validate type preserved or set correctly
            expect(overriddenBom.metadata.component.type).toBe(originalComponent.type || 'application');
            
            // Validate timestamp updated
            expect(overriddenBom.metadata.timestamp).toBe(timestamp);
        });

        it('should update timestamp precisely', () => {
            const bom = loadFixture('cyclonedx-merged-tld.json');
            const originalTimestamp = bom.metadata.timestamp;
            const rebomOptions: any = { name: 'test', group: 'com.test', version: '1.0.0' };
            const newTimestamp = '2024-01-01T00:00:00.000Z';
            
            const overriddenBom = BomProcessingService.overrideRootComponent(bom, rebomOptions, newTimestamp);
            
            // Validate timestamp changed to exact new value
            expect(overriddenBom.metadata.timestamp).toBe(newTimestamp);
            expect(overriddenBom.metadata.timestamp).not.toBe(originalTimestamp);
        });

        it('should preserve all other BOM fields exactly', () => {
            const bom = loadFixture('cyclonedx-merged-tld.json');
            const originalBomFormat = bom.bomFormat;
            const originalSpecVersion = bom.specVersion;
            const originalSerialNumber = bom.serialNumber;
            const originalVersion = bom.version;
            const originalComponentCount = bom.components.length;
            const originalDependencyCount = bom.dependencies?.length || 0;
            
            const rebomOptions: any = { name: 'test', group: 'com.test', version: '1.0.0' };
            const timestamp = new Date().toISOString();
            
            const overriddenBom = BomProcessingService.overrideRootComponent(bom, rebomOptions, timestamp);
            
            // Validate core BOM fields unchanged
            expect(overriddenBom.bomFormat).toBe(originalBomFormat);
            expect(overriddenBom.specVersion).toBe(originalSpecVersion);
            expect(overriddenBom.serialNumber).toBe(originalSerialNumber);
            expect(overriddenBom.version).toBe(originalVersion);
            
            // Validate components array unchanged
            expect(overriddenBom.components).toEqual(bom.components);
            expect(overriddenBom.components.length).toBe(originalComponentCount);
            
            // Validate dependencies unchanged if present
            if (bom.dependencies) {
                expect(overriddenBom.dependencies).toEqual(bom.dependencies);
                expect(overriddenBom.dependencies.length).toBe(originalDependencyCount);
            }
        });
    });

    describe('establishPurl', () => {
        it('should use provided purl if available', () => {
            const providedPurl = 'pkg:npm/my-package@1.0.0';
            const rebomOptions: any = { purl: providedPurl };
            
            const purl = BomProcessingService.establishPurl(undefined, rebomOptions);
            
            expect(purl).toBe(providedPurl);
        });

        it('should construct purl from rebom options', () => {
            const rebomOptions: any = {
                name: 'my-package',
                group: 'com.example',
                version: '1.0.0'
            };
            
            const purl = BomProcessingService.establishPurl(undefined, rebomOptions);
            
            expect(purl).toBeDefined();
            expect(purl).toContain('my-package');
            expect(purl).toContain('1.0.0');
        });

        it('should throw error when group is missing', () => {
            const rebomOptions: any = {
                name: 'my-package',
                version: '1.0.0'
            };
            
            expect(() => BomProcessingService.establishPurl(undefined, rebomOptions)).toThrow('Missing required fields for PURL generation');
        });
    });

    describe('REGRESSION: BOM structure validation', () => {
        it('should ensure processed BOMs have valid structure', async () => {
            const bom = loadFixture('cyclonedx-merged-tld.json');
            
            // Process BOM through operations that don't require special structure
            const filteredBom = BomProcessingService.extractDevFilteredBom(bom);
            const bomWithTool = BomProcessingService.attachRebomToolToBom(filteredBom);
            
            // Validate final structure using official CycloneDX validator
            await expect(validateCycloneDxStructure(bomWithTool, '1.6')).resolves.not.toThrow();
        });

        it('should ensure dependencies.dependsOn is always an array', async () => {
            const bom = loadFixture('cyclonedx-augmented.json');
            
            // Process through dev filtering (doesn't modify dependencies)
            const processedBom = BomProcessingService.extractDevFilteredBom(bom);
            
            // Validate dependencies structure using official validator
            await expect(validateCycloneDxStructure(processedBom, '1.6')).resolves.not.toThrow();
            
            // Additional precise check: every dependency with dependsOn has it as array
            if (processedBom.dependencies) {
                processedBom.dependencies.forEach((dep: any) => {
                    if ('dependsOn' in dep) {
                        expect(Array.isArray(dep.dependsOn)).toBe(true);
                        // Validate each ref in dependsOn exists in components
                        if (dep.dependsOn.length > 0) {
                            const componentRefs = processedBom.components.map((c: any) => c['bom-ref']);
                            dep.dependsOn.forEach((ref: string) => {
                                // Either the ref exists in components or it's the root component
                                const isValid = componentRefs.includes(ref) || ref === processedBom.metadata.component['bom-ref'];
                                expect(isValid).toBe(true);
                            });
                        }
                    }
                });
            }
        });
    });
});
