import { describe, it, expect } from 'vitest';
import { parseSarifFromString } from '../../src/services/sarif/sarifParser';

/**
 * Unit tests for SARIF (Static Analysis Results Interchange Format) parser
 * Tests parsing of SARIF security scan results
 * Used by rearm-saas/backend for security weakness scanning
 */

describe('SARIF Parser - Unit Tests', () => {
    describe('parseSarifFromString', () => {
        it('should parse SARIF with security findings', () => {
            const sarifContent = {
                version: '2.1.0',
                $schema: 'https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json',
                runs: [
                    {
                        tool: {
                            driver: {
                                name: 'ESLint',
                                version: '8.0.0',
                                rules: [
                                    {
                                        id: 'no-eval',
                                        shortDescription: {
                                            text: 'Disallow eval()'
                                        },
                                        properties: {
                                            tags: ['security']
                                        }
                                    }
                                ]
                            }
                        },
                        results: [
                            {
                                ruleId: 'no-eval',
                                level: 'error',
                                message: {
                                    text: 'eval can be harmful'
                                },
                                locations: [
                                    {
                                        physicalLocation: {
                                            artifactLocation: {
                                                uri: 'file:///src/app.js'
                                            },
                                            region: {
                                                startLine: 42
                                            }
                                        }
                                    }
                                ]
                            }
                        ]
                    }
                ]
            };

            const result = parseSarifFromString(JSON.stringify(sarifContent));

            expect(result).toBeDefined();
            expect(Array.isArray(result)).toBe(true);
            expect(result.length).toBe(1);
            
            // Verify specific weakness data
            const weakness = result[0];
            expect(weakness.ruleId).toBe('no-eval');
            expect(weakness.location).toBe('file:///src/app.js:42');
            expect(weakness.severity).toBe('HIGH'); // error level maps to HIGH
            
            // Verify location format is correct
            expect(weakness.location).toMatch(/^file:\/\/\/.+:\d+$/);
            
            // Verify severity mapping from SARIF level
            expect(sarifContent.runs[0].results[0].level).toBe('error');
            expect(weakness.severity).toBe('HIGH'); // error -> HIGH
        });

        it('should handle SARIF with no results', () => {
            const sarifContent = {
                version: '2.1.0',
                $schema: 'https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json',
                runs: [
                    {
                        tool: {
                            driver: {
                                name: 'ESLint',
                                version: '8.0.0'
                            }
                        },
                        results: []
                    }
                ]
            };

            const result = parseSarifFromString(JSON.stringify(sarifContent));

            expect(result).toBeDefined();
            expect(Array.isArray(result)).toBe(true);
            expect(result.length).toBe(0);
        });

        it('should handle SARIF with multiple findings', () => {
            const sarifContent = {
                version: '2.1.0',
                $schema: 'https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json',
                runs: [
                    {
                        tool: {
                            driver: {
                                name: 'CodeQL',
                                version: '2.0.0',
                                rules: [
                                    {
                                        id: 'js/sql-injection',
                                        shortDescription: {
                                            text: 'SQL injection'
                                        }
                                    },
                                    {
                                        id: 'js/xss',
                                        shortDescription: {
                                            text: 'Cross-site scripting'
                                        }
                                    }
                                ]
                            }
                        },
                        results: [
                            {
                                ruleId: 'js/sql-injection',
                                level: 'error',
                                message: {
                                    text: 'Potential SQL injection'
                                },
                                locations: [
                                    {
                                        physicalLocation: {
                                            artifactLocation: {
                                                uri: 'file:///src/db.js'
                                            },
                                            region: {
                                                startLine: 10
                                            }
                                        }
                                    }
                                ]
                            },
                            {
                                ruleId: 'js/xss',
                                level: 'warning',
                                message: {
                                    text: 'Potential XSS vulnerability'
                                },
                                locations: [
                                    {
                                        physicalLocation: {
                                            artifactLocation: {
                                                uri: 'file:///src/render.js'
                                            },
                                            region: {
                                                startLine: 25
                                            }
                                        }
                                    }
                                ]
                            }
                        ]
                    }
                ]
            };

            const result = parseSarifFromString(JSON.stringify(sarifContent));

            expect(result).toBeDefined();
            expect(Array.isArray(result)).toBe(true);
            expect(result.length).toBe(2);
            
            // Verify SQL injection finding
            const sqlInjection = result.find(w => w.ruleId === 'js/sql-injection');
            expect(sqlInjection).toBeDefined();
            expect(sqlInjection!.location).toBe('file:///src/db.js:10');
            expect(sqlInjection!.severity).toBe('HIGH'); // error -> HIGH
            expect(sqlInjection!.ruleId).toBe('js/sql-injection');
            
            // Verify XSS finding
            const xss = result.find(w => w.ruleId === 'js/xss');
            expect(xss).toBeDefined();
            expect(xss!.location).toBe('file:///src/render.js:25');
            expect(xss!.severity).toBe('MEDIUM'); // warning -> MEDIUM
            expect(xss!.ruleId).toBe('js/xss');
            
            // Verify severity mapping is correct
            expect(sarifContent.runs[0].results[0].level).toBe('error');
            expect(sarifContent.runs[0].results[1].level).toBe('warning');
        });

        it('should handle invalid JSON gracefully', () => {
            const invalidJson = 'not valid json {';

            expect(() => parseSarifFromString(invalidJson)).toThrow();
        });

        it('should handle empty string', () => {
            expect(() => parseSarifFromString('')).toThrow();
        });

        it('should parse SARIF with CWE mapping', () => {
            const sarifContent = {
                version: '2.1.0',
                runs: [
                    {
                        tool: {
                            driver: {
                                name: 'Semgrep',
                                rules: [
                                    {
                                        id: 'cwe-79',
                                        shortDescription: {
                                            text: 'XSS vulnerability'
                                        },
                                        properties: {
                                            cwe: ['CWE-79']
                                        }
                                    }
                                ]
                            }
                        },
                        results: [
                            {
                                ruleId: 'cwe-79',
                                level: 'error',
                                message: {
                                    text: 'XSS found'
                                },
                                locations: [
                                    {
                                        physicalLocation: {
                                            artifactLocation: {
                                                uri: 'file:///src/view.js'
                                            },
                                            region: {
                                                startLine: 15
                                            }
                                        }
                                    }
                                ]
                            }
                        ]
                    }
                ]
            };

            const result = parseSarifFromString(JSON.stringify(sarifContent));

            expect(result).toBeDefined();
            expect(result.length).toBe(1);
            
            // Verify CWE mapping is extracted correctly
            const weakness = result[0];
            expect(weakness.ruleId).toBe('cwe-79');
            expect(weakness.cweId).toBe('CWE-79'); // Should extract from properties.cwe
            expect(weakness.location).toBe('file:///src/view.js:15');
            expect(weakness.severity).toBe('HIGH');
            
            // Verify CWE was in the rule properties
            const rule = sarifContent.runs[0].tool.driver.rules![0];
            expect(rule.properties?.cwe).toContain('CWE-79');
            expect(weakness.cweId).toBe(rule.properties!.cwe[0]);
        });

        it('should handle SARIF with multiple runs', () => {
            const sarifContent = {
                version: '2.1.0',
                runs: [
                    {
                        tool: {
                            driver: {
                                name: 'Tool1',
                                rules: [
                                    {
                                        id: 'rule1',
                                        shortDescription: {
                                            text: 'Rule 1'
                                        }
                                    }
                                ]
                            }
                        },
                        results: [
                            {
                                ruleId: 'rule1',
                                level: 'warning',
                                message: {
                                    text: 'Finding 1'
                                },
                                locations: [
                                    {
                                        physicalLocation: {
                                            artifactLocation: {
                                                uri: 'file:///src/file1.js'
                                            },
                                            region: {
                                                startLine: 1
                                            }
                                        }
                                    }
                                ]
                            }
                        ]
                    },
                    {
                        tool: {
                            driver: {
                                name: 'Tool2',
                                rules: [
                                    {
                                        id: 'rule2',
                                        shortDescription: {
                                            text: 'Rule 2'
                                        }
                                    }
                                ]
                            }
                        },
                        results: [
                            {
                                ruleId: 'rule2',
                                level: 'error',
                                message: {
                                    text: 'Finding 2'
                                },
                                locations: [
                                    {
                                        physicalLocation: {
                                            artifactLocation: {
                                                uri: 'file:///src/file2.js'
                                            },
                                            region: {
                                                startLine: 2
                                            }
                                        }
                                    }
                                ]
                            }
                        ]
                    }
                ]
            };

            const result = parseSarifFromString(JSON.stringify(sarifContent));

            expect(result).toBeDefined();
            expect(Array.isArray(result)).toBe(true);
            expect(result.length).toBe(2);
            
            // Verify findings from first tool run
            const tool1Finding = result.find(w => w.ruleId === 'rule1');
            expect(tool1Finding).toBeDefined();
            expect(tool1Finding!.location).toBe('file:///src/file1.js:1');
            expect(tool1Finding!.severity).toBe('MEDIUM'); // warning -> MEDIUM
            
            // Verify findings from second tool run
            const tool2Finding = result.find(w => w.ruleId === 'rule2');
            expect(tool2Finding).toBeDefined();
            expect(tool2Finding!.location).toBe('file:///src/file2.js:2');
            expect(tool2Finding!.severity).toBe('HIGH'); // error -> HIGH
            
            // Verify both runs were processed
            expect(sarifContent.runs.length).toBe(2);
            expect(result.length).toBe(sarifContent.runs.length);
        });
    });
});
