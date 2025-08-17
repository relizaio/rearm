import { VulnerabilityDto, VulnerabilitySeverity } from './types';
import { logger } from './logger';
// Note: using our own lightweight JSON types and parser; no external CycloneDX model usage

// Lightweight structural types aligned with CycloneDX VDR/BOV JSON
type CdxAffect = {
    ref?: string;
};

type CdxRating = {
    method?: string;
    score?: number;
    severity?: string;
};

type CdxVulnerability = {
    id?: string;
    affects?: CdxAffect[];
    ratings?: CdxRating[];
    bom_ref?: string;
};

type CdxComponent = {
    bom_ref?: string;
    purl?: string;
};

type CdxBom = {
    bomFormat?: string;
    specVersion?: string;
    components?: CdxComponent[];
    vulnerabilities?: CdxVulnerability[];
};

/**
 * Parses a CycloneDX VDR/BOV report and extracts vulnerability information
 * @param vdrData - The parsed CycloneDX VDR JSON data
 * @returns Array of VulnerabilityDto objects
 */
export function parseCycloneDxToVulnerabilities(vdrData: CdxBom): VulnerabilityDto[] {
    const vulnerabilities: VulnerabilityDto[] = [];

    try {
        if (!vdrData.vulnerabilities || vdrData.vulnerabilities.length === 0) {
            logger.warn('No vulnerabilities found in CycloneDX VDR');
            return vulnerabilities;
        }

        // Create a map of component references to PURLs for quick lookup
        const componentMap = new Map<string, string>();
        if (vdrData.components) {
            vdrData.components.forEach((component: CdxComponent) => {
                if (component.bom_ref && component.purl) {
                    componentMap.set(component.bom_ref, component.purl);
                }
            });
        }

        for (const vuln of vdrData.vulnerabilities) {
            const vulnerability = extractVulnerabilityFromCycloneDx(vuln, componentMap);
            if (vulnerability) {
                vulnerabilities.push(vulnerability);
            }
        }
    } catch (error) {
        logger.error(`Error parsing CycloneDX VDR data: ${error}`);
        throw new Error(`Failed to parse CycloneDX VDR data: ${error}`);
    }

    return vulnerabilities;
}

/**
 * Extracts vulnerability information from a single CycloneDX vulnerability
 */
function extractVulnerabilityFromCycloneDx(
    vuln: CdxVulnerability, 
    componentMap: Map<string, string>
): VulnerabilityDto | null {
    try {
        const vulnId = vuln.id;
        if (!vulnId) {
            logger.warn('Vulnerability missing ID, skipping');
            return null;
        }

        // Extract PURL from affected components
        const purl = extractPurlFromVulnerability(vuln, componentMap);

        // Determine severity
        const severity = determineSeverityFromCycloneDx(vuln);

        return {
            purl,
            vulnId,
            severity
        };
    } catch (error) {
        logger.error(`Error extracting vulnerability from CycloneDX: ${error}`);
        return null;
    }
}

/**
 * Extracts PURL from vulnerability affects section
 */
function extractPurlFromVulnerability(
    vuln: CdxVulnerability, 
    componentMap: Map<string, string>
): string {
    // Check if vulnerability has affects section
    if (vuln.affects && vuln.affects.length > 0) {
        for (const affect of vuln.affects) {
            const purl = affect?.ref ? componentMap.get(affect.ref) : undefined;
            if (purl) {
                return purl;
            }
        }
    }

    // If no PURL found from affects, try to construct from component reference
    if (vuln.bom_ref) {
        const purl = componentMap.get(vuln.bom_ref);
        if (purl) {
            return purl;
        }
    }

    return 'unknown';
}

/**
 * Determines vulnerability severity from CycloneDX vulnerability ratings
 */
function determineSeverityFromCycloneDx(vuln: CdxVulnerability): VulnerabilitySeverity {
    if (!vuln.ratings || vuln.ratings.length === 0) {
        return VulnerabilitySeverity.UNKNOWN;
    }

    // Look for CVSS ratings first (most reliable)
    const cvssRating = vuln.ratings.find((rating: CdxRating) => {
        const methodStr = rating.method != null ? String(rating.method) : '';
        return methodStr.toUpperCase().includes('CVSS');
    });

    if (cvssRating && cvssRating.score !== undefined) {
        return mapCvssScoreToSeverity(cvssRating.score);
    }

    // Fall back to severity string mapping
    for (const rating of vuln.ratings) {
        if (rating.severity != null) {
            const severity = mapSeverityStringToEnum(String(rating.severity));
            if (severity !== VulnerabilitySeverity.UNKNOWN) {
                return severity;
            }
        }
    }

    return VulnerabilitySeverity.UNKNOWN;
}

/**
 * Maps CVSS score to vulnerability severity
 */
function mapCvssScoreToSeverity(score: number): VulnerabilitySeverity {
    if (score >= 9.0) {
        return VulnerabilitySeverity.CRITICAL;
    } else if (score >= 7.0) {
        return VulnerabilitySeverity.HIGH;
    } else if (score >= 4.0) {
        return VulnerabilitySeverity.MEDIUM;
    } else if (score >= 0.1) {
        return VulnerabilitySeverity.LOW;
    } else {
        return VulnerabilitySeverity.UNKNOWN;
    }
}

/**
 * Maps severity string to VulnerabilitySeverity enum
 */
function mapSeverityStringToEnum(severityString: string): VulnerabilitySeverity {
    const severity = severityString.toUpperCase();
    
    switch (severity) {
        case 'CRITICAL':
            return VulnerabilitySeverity.CRITICAL;
        case 'HIGH':
            return VulnerabilitySeverity.HIGH;
        case 'MEDIUM':
        case 'MODERATE':
            return VulnerabilitySeverity.MEDIUM;
        case 'LOW':
            return VulnerabilitySeverity.LOW;
        case 'INFO':
        case 'INFORMATIONAL':
            return VulnerabilitySeverity.LOW;
        default:
            return VulnerabilitySeverity.UNKNOWN;
    }
}

/**
 * Validates that the input is a valid CycloneDX VDR report
 */
export function validateCycloneDxVdr(data: unknown): boolean {
    if (!data || typeof data !== 'object') {
        return false;
    }

    const bom = data as CdxBom;

    // Check required fields
    if (!bom.bomFormat || !bom.specVersion) {
        return false;
    }

    // Validate bomFormat
    if (bom.bomFormat !== 'CycloneDX') {
        return false;
    }

    // Validate specVersion (should be 1.0 or higher)
    const specVersion = bom.specVersion;
    if (typeof specVersion !== 'string') {
        return false;
    }

    const versionParts = specVersion.split('.').map(Number);
    if (versionParts.length < 2 || isNaN(versionParts[0]) || isNaN(versionParts[1])) {
        return false;
    }

    const majorVersion = versionParts[0];
    if (majorVersion < 1) {
        return false;
    }

    // Vulnerabilities should be an array if present
    if (bom.vulnerabilities && !Array.isArray(bom.vulnerabilities)) {
        return false;
    }

    // Components should be an array if present
    if (bom.components && !Array.isArray(bom.components)) {
        return false;
    }

    return true;
}

/**
 * Parses CycloneDX VDR from JSON string
 */
export function parseCycloneDxFromString(vdrJsonString: string): VulnerabilityDto[] {
    try {
        const raw = JSON.parse(vdrJsonString);

        if (!validateCycloneDxVdr(raw)) {
            throw new Error('Invalid CycloneDX VDR report format');
        }

        // Directly parse using our lightweight JSON types
        return parseCycloneDxToVulnerabilities(raw as CdxBom);
    } catch (error) {
        logger.error(`Error parsing CycloneDX VDR from string: ${error}`);
        throw new Error(`Failed to parse CycloneDX VDR: ${error}`);
    }
}
