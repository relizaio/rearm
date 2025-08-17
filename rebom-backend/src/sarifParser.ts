import { WeaknessDto, VulnerabilitySeverity, SarifReport, SarifResult, SarifRule } from './types';
import { logger } from './logger';

/**
 * Parses a SARIF report and extracts weakness information
 * @param sarifData - The parsed SARIF JSON data
 * @returns Array of WeaknessDto objects
 */
export function parseSarifToWeaknesses(sarifData: SarifReport): WeaknessDto[] {
    const weaknesses: WeaknessDto[] = [];

    try {
        for (const run of sarifData.runs) {
            const rules = run.tool.driver.rules || [];
            const results = run.results || [];

            // Create a map of rule index to rule for quick lookup
            const ruleMap = new Map<number, SarifRule>();
            rules.forEach((rule, index) => {
                ruleMap.set(index, rule);
            });

            for (const result of results) {
                const weakness = extractWeaknessFromResult(result, ruleMap);
                if (weakness) {
                    weaknesses.push(weakness);
                }
            }
        }
    } catch (error) {
        logger.error(`Error parsing SARIF data: ${error}`);
        throw new Error(`Failed to parse SARIF data: ${error}`);
    }

    return weaknesses;
}

/**
 * Extracts weakness information from a single SARIF result
 */
function extractWeaknessFromResult(result: SarifResult, ruleMap: Map<number, SarifRule>): WeaknessDto | null {
    try {
        // Get rule information
        let rule: SarifRule | undefined;
        if (result.ruleIndex !== undefined) {
            rule = ruleMap.get(result.ruleIndex);
        }

        const ruleId = result.ruleId || rule?.id || 'unknown';

        // Extract CWE ID from rule relationships or tags
        const cweId = extractCweId(rule);

        // Extract location information
        const location = extractLocation(result);

        // Extract fingerprint
        const fingerprint = extractFingerprint(result);

        // Determine severity
        const severity = determineSeverity(result, rule);

        return {
            cweId,
            ruleId,
            location,
            fingerprint,
            severity
        };
    } catch (error) {
        logger.error(`Error extracting weakness from result: ${error}`);
        return null;
    }
}

/**
 * Extracts CWE ID from rule information
 */
function extractCweId(rule?: SarifRule): string {
    if (!rule) return 'unknown';

    // Check relationships for CWE references
    if (rule.relationships) {
        for (const relationship of rule.relationships) {
            if (relationship.target.toolComponent.name.toUpperCase() === 'CWE' || 
                relationship.target.id.toUpperCase().startsWith('CWE-')) {
                return relationship.target.id;
            }
        }
    }

    // Check tags for CWE references
    if (rule.properties?.tags) {
        for (const tag of rule.properties.tags) {
            if (tag.toUpperCase().startsWith('CWE-') || tag.toUpperCase().includes('CWE')) {
                const cweMatch = tag.toUpperCase().match(/CWE-(\d+)/);
                if (cweMatch) {
                    return `CWE-${cweMatch[1]}`;
                }
            }
        }
    }

    // Check rule ID itself for CWE pattern
    const cweMatch = rule.id.toUpperCase().match(/CWE-(\d+)/);
    if (cweMatch) {
        return `CWE-${cweMatch[1]}`;
    }

    return 'unknown'; // Default when no CWE is found
}

/**
 * Extracts location information from result
 */
function extractLocation(result: SarifResult): string {
    if (!result.locations || result.locations.length === 0) {
        return 'unknown';
    }

    const location = result.locations[0];
    const physicalLocation = location.physicalLocation;

    if (!physicalLocation) {
        return 'unknown';
    }

    let locationStr = physicalLocation.artifactLocation?.uri || 'unknown';

    if (physicalLocation.region) {
        const region = physicalLocation.region;
        if (region.startLine) {
            locationStr += `:${region.startLine}`;
            if (region.startColumn) {
                locationStr += `:${region.startColumn}`;
            }
        }
    }

    return locationStr;
}

/**
 * Extracts fingerprint from result
 */
function extractFingerprint(result: SarifResult): string {
    // Try fingerprints first
    if (result.fingerprints) {
        const fingerprintKeys = Object.keys(result.fingerprints);
        if (fingerprintKeys.length > 0) {
            return result.fingerprints[fingerprintKeys[0]];
        }
    }

    // Try partial fingerprints
    if (result.partialFingerprints) {
        const partialKeys = Object.keys(result.partialFingerprints);
        if (partialKeys.length > 0) {
            return result.partialFingerprints[partialKeys[0]];
        }
    }
    return 'unknown';
}

/**
 * Determines vulnerability severity from result and rule information
 */
function determineSeverity(result: SarifResult, rule?: SarifRule): VulnerabilitySeverity {
    // Check result level first
    if (result.level) {
        switch (result.level.toLowerCase()) {
            case 'error':
                return VulnerabilitySeverity.HIGH;
            case 'warning':
                return VulnerabilitySeverity.MEDIUM;
            case 'note':
            case 'info':
                return VulnerabilitySeverity.LOW;
        }
    }

    // Check rule properties for security severity
    if (rule?.properties?.['security-severity']) {
        const securitySeverity = parseFloat(rule.properties['security-severity']);
        if (securitySeverity >= 9.0) {
            return VulnerabilitySeverity.CRITICAL;
        } else if (securitySeverity >= 7.0) {
            return VulnerabilitySeverity.HIGH;
        } else if (securitySeverity >= 4.0) {
            return VulnerabilitySeverity.MEDIUM;
        } else {
            return VulnerabilitySeverity.LOW;
        }
    }

    // Check tags for severity indicators
    if (rule?.properties?.tags) {
        for (const tag of rule.properties.tags) {
            const lowerTag = tag.toLowerCase();
            if (lowerTag.includes('critical')) {
                return VulnerabilitySeverity.CRITICAL;
            } else if (lowerTag.includes('high')) {
                return VulnerabilitySeverity.HIGH;
            } else if (lowerTag.includes('medium')) {
                return VulnerabilitySeverity.MEDIUM;
            } else if (lowerTag.includes('low')) {
                return VulnerabilitySeverity.LOW;
            }
        }
    }

    return VulnerabilitySeverity.UNKNOWN;
}

/**
 * Validates that the input is a valid SARIF report
 */
export function validateSarifReport(data: any): data is SarifReport {
    if (!data || typeof data !== 'object') {
        return false;
    }

    if (!data.version || !data.runs || !Array.isArray(data.runs)) {
        return false;
    }

    // Validate SARIF version is 2.0.0 or higher
    const version = data.version;
    if (typeof version !== 'string') {
        return false;
    }
    
    const versionParts = version.split('.').map(Number);
    if (versionParts.length < 2 || isNaN(versionParts[0]) || isNaN(versionParts[1])) {
        return false;
    }
    
    const majorVersion = versionParts[0];
    
    // Require SARIF version 2.0.0 or higher
    if (majorVersion < 2) {
        return false;
    }

    // Basic validation of runs structure
    for (const run of data.runs) {
        if (!run.tool || !run.tool.driver || !run.tool.driver.name) {
            return false;
        }
    }

    return true;
}

/**
 * Parses SARIF from JSON string
 */
export function parseSarifFromString(sarifJsonString: string): WeaknessDto[] {
    try {
        const sarifData = JSON.parse(sarifJsonString);
        
        if (!validateSarifReport(sarifData)) {
            throw new Error('Invalid SARIF report format');
        }

        return parseSarifToWeaknesses(sarifData);
    } catch (error) {
        logger.error(`Error parsing SARIF from string: ${error}`);
        throw new Error(`Failed to parse SARIF: ${error}`);
    }
}
