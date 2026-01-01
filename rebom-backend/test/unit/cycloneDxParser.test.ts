import { describe, it, expect } from 'vitest';
import { parseCycloneDxFromString } from '../../src/services/cyclonedx/cycloneDxParser';

/**
 * Unit tests for CycloneDX VDR (Vulnerability Disclosure Report) parser
 * Tests parsing of CycloneDX content with vulnerabilities
 * Used by rearm-saas/backend for vulnerability scanning
 */

describe('CycloneDX Parser - Unit Tests', () => {
    describe('parseCycloneDxFromString', () => {
        it('should parse CycloneDX VDR with vulnerabilities', () => {
            const vdrContent = {
                bomFormat: 'CycloneDX',
                specVersion: '1.6',
                serialNumber: 'urn:uuid:3e671687-395b-41f5-a30f-a58921a69b79',
                version: 1,
                metadata: {
                    timestamp: '2023-01-01T00:00:00Z',
                    tools: {
                        components: []
                    }
                },
                components: [
                    {
                        type: 'library',
                        name: 'lodash',
                        version: '4.17.20',
                        purl: 'pkg:npm/lodash@4.17.20'
                    }
                ],
                vulnerabilities: [
                    {
                        id: 'CVE-2021-23337',
                        source: {
                            name: 'NVD',
                            url: 'https://nvd.nist.gov/vuln/detail/CVE-2021-23337'
                        },
                        ratings: [
                            {
                                severity: 'high',
                                score: 7.5,
                                method: 'CVSSv3'
                            }
                        ],
                        description: 'Command injection vulnerability',
                        affects: [
                            {
                                ref: 'pkg:npm/lodash@4.17.20'
                            }
                        ]
                    }
                ]
            };

            const result = parseCycloneDxFromString(JSON.stringify(vdrContent));

            expect(result).toBeDefined();
            expect(Array.isArray(result)).toBe(true);
            expect(result.length).toBe(1);
            
            // Verify specific vulnerability data
            const vuln = result[0];
            expect(vuln.vulnId).toBe('CVE-2021-23337');
            expect(vuln.purl).toBe('pkg:npm/lodash@4.17.20');
            expect(vuln.severity).toBe('HIGH');
            
            // Verify it matches the input data
            expect(vuln.vulnId).toBe(vdrContent.vulnerabilities[0].id);
            expect(vuln.purl).toBe(vdrContent.vulnerabilities[0].affects[0].ref);
        });

        it('should handle CycloneDX VDR with no vulnerabilities', () => {
            const vdrContent = {
                bomFormat: 'CycloneDX',
                specVersion: '1.6',
                serialNumber: 'urn:uuid:3e671687-395b-41f5-a30f-a58921a69b79',
                version: 1,
                metadata: {
                    timestamp: '2023-01-01T00:00:00Z',
                    tools: {
                        components: []
                    }
                },
                components: [
                    {
                        type: 'library',
                        name: 'express',
                        version: '4.18.2',
                        purl: 'pkg:npm/express@4.18.2'
                    }
                ],
                vulnerabilities: []
            };

            const result = parseCycloneDxFromString(JSON.stringify(vdrContent));

            expect(result).toBeDefined();
            expect(Array.isArray(result)).toBe(true);
            expect(result.length).toBe(0);
        });

        it('should handle CycloneDX VDR with multiple vulnerabilities', () => {
            const vdrContent = {
                bomFormat: 'CycloneDX',
                specVersion: '1.6',
                serialNumber: 'urn:uuid:3e671687-395b-41f5-a30f-a58921a69b79',
                version: 1,
                metadata: {
                    timestamp: '2023-01-01T00:00:00Z',
                    tools: {
                        components: []
                    }
                },
                components: [
                    {
                        type: 'library',
                        name: 'lodash',
                        version: '4.17.20',
                        purl: 'pkg:npm/lodash@4.17.20'
                    }
                ],
                vulnerabilities: [
                    {
                        id: 'CVE-2021-23337',
                        source: {
                            name: 'NVD'
                        },
                        ratings: [
                            {
                                severity: 'high',
                                score: 7.5
                            }
                        ],
                        affects: [
                            {
                                ref: 'pkg:npm/lodash@4.17.20'
                            }
                        ]
                    },
                    {
                        id: 'GHSA-1234-5678-9abc',
                        source: {
                            name: 'GitHub'
                        },
                        ratings: [
                            {
                                severity: 'medium',
                                score: 5.3
                            }
                        ],
                        affects: [
                            {
                                ref: 'pkg:npm/lodash@4.17.20'
                            }
                        ]
                    }
                ]
            };

            const result = parseCycloneDxFromString(JSON.stringify(vdrContent));

            expect(result).toBeDefined();
            expect(Array.isArray(result)).toBe(true);
            expect(result.length).toBe(2);
            
            // Verify first vulnerability (CVE)
            const cveVuln = result.find(v => v.vulnId.includes('CVE'));
            expect(cveVuln).toBeDefined();
            expect(cveVuln!.vulnId).toBe('CVE-2021-23337');
            expect(cveVuln!.severity).toBe('HIGH');
            expect(cveVuln!.purl).toBe('pkg:npm/lodash@4.17.20');
            
            // Verify second vulnerability (GHSA)
            const ghsaVuln = result.find(v => v.vulnId.includes('GHSA'));
            expect(ghsaVuln).toBeDefined();
            expect(ghsaVuln!.vulnId).toBe('GHSA-1234-5678-9abc');
            expect(ghsaVuln!.severity).toBe('MEDIUM');
            expect(ghsaVuln!.purl).toBe('pkg:npm/lodash@4.17.20');
        });

        it('should handle invalid JSON gracefully', () => {
            const invalidJson = 'not valid json {';

            expect(() => parseCycloneDxFromString(invalidJson)).toThrow();
        });

        it('should handle empty string', () => {
            expect(() => parseCycloneDxFromString('')).toThrow();
        });

        it('should parse vulnerability with CVE ID', () => {
            const vdrContent = {
                bomFormat: 'CycloneDX',
                specVersion: '1.6',
                serialNumber: 'urn:uuid:3e671687-395b-41f5-a30f-a58921a69b79',
                version: 1,
                vulnerabilities: [
                    {
                        id: 'CVE-2023-1234',
                        source: {
                            name: 'NVD'
                        },
                        ratings: [
                            {
                                severity: 'critical',
                                score: 9.8
                            }
                        ],
                        affects: [
                            {
                                ref: 'pkg:npm/test@1.0.0'
                            }
                        ]
                    }
                ]
            };

            const result = parseCycloneDxFromString(JSON.stringify(vdrContent));

            expect(result).toBeDefined();
            expect(result.length).toBe(1);
            
            // Verify CVE-specific parsing
            const vuln = result[0];
            expect(vuln.vulnId).toBe('CVE-2023-1234');
            expect(vuln.severity).toBe('CRITICAL');
            expect(vuln.purl).toBe('pkg:npm/test@1.0.0');
            
            // Verify severity mapping from score
            expect(vdrContent.vulnerabilities[0].ratings[0].score).toBe(9.8);
            expect(vuln.severity).toBe('CRITICAL'); // 9.8 should map to CRITICAL
        });

        it('should parse vulnerability with GHSA ID', () => {
            const vdrContent = {
                bomFormat: 'CycloneDX',
                specVersion: '1.6',
                serialNumber: 'urn:uuid:3e671687-395b-41f5-a30f-a58921a69b79',
                version: 1,
                vulnerabilities: [
                    {
                        id: 'GHSA-abcd-1234-efgh',
                        source: {
                            name: 'GitHub'
                        },
                        ratings: [
                            {
                                severity: 'low',
                                score: 3.1
                            }
                        ],
                        affects: [
                            {
                                ref: 'pkg:npm/test@1.0.0'
                            }
                        ]
                    }
                ]
            };

            const result = parseCycloneDxFromString(JSON.stringify(vdrContent));

            expect(result).toBeDefined();
            expect(result.length).toBe(1);
            
            // Verify GHSA-specific parsing
            const vuln = result[0];
            expect(vuln.vulnId).toBe('GHSA-abcd-1234-efgh');
            expect(vuln.vulnId).toMatch(/^GHSA-[a-z0-9]{4}-[a-z0-9]{4}-[a-z0-9]{4}$/);
            expect(vuln.severity).toBe('LOW');
            expect(vuln.purl).toBe('pkg:npm/test@1.0.0');
            
            // Verify severity mapping from score
            expect(vdrContent.vulnerabilities[0].ratings[0].score).toBe(3.1);
            expect(vuln.severity).toBe('LOW'); // 3.1 should map to LOW
        });
    });
});
