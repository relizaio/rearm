import { VulnerabilityDto } from '../../types';
import { parseCycloneDxFromString, parseCycloneDxToVulnerabilities, validateCycloneDxVdr } from './cycloneDxParser';
import { logger } from '../../logger';
import * as fs from 'fs';

/**
 * Service for handling CycloneDX VDR/BOV file operations
 */
export class CycloneDxService {

    /**
     * Parse CycloneDX VDR content from string
     */
    static parseCycloneDxContent(content: string): VulnerabilityDto[] {
        return parseCycloneDxFromString(content);
    }

    /**
     * Parse CycloneDX VDR content from object
     */
    static parseCycloneDxObject(vdrData: any): VulnerabilityDto[] {
        if (!validateCycloneDxVdr(vdrData)) {
            throw new Error('Invalid CycloneDX VDR report format');
        }
        return parseCycloneDxToVulnerabilities(vdrData);
    }

    /**
     * Filter vulnerabilities by severity
     */
    static filterVulnerabilitiesBySeverity(vulnerabilities: VulnerabilityDto[], severities: string[]): VulnerabilityDto[] {
        if (!severities || severities.length === 0) {
            return vulnerabilities;
        }
        
        const severitySet = new Set(severities.map(s => s.toUpperCase()));
        return vulnerabilities.filter(vulnerability => severitySet.has(vulnerability.severity));
    }

    /**
     * Filter vulnerabilities by vulnerability ID
     */
    static filterVulnerabilitiesByVulnId(vulnerabilities: VulnerabilityDto[], vulnIds: string[]): VulnerabilityDto[] {
        if (!vulnIds || vulnIds.length === 0) {
            return vulnerabilities;
        }
        
        const vulnIdSet = new Set(vulnIds.map(id => id.toUpperCase()));
        return vulnerabilities.filter(vulnerability => vulnIdSet.has(vulnerability.vulnId.toUpperCase()));
    }

    /**
     * Filter vulnerabilities by PURL pattern
     */
    static filterVulnerabilitiesByPurl(vulnerabilities: VulnerabilityDto[], purlPatterns: string[]): VulnerabilityDto[] {
        if (!purlPatterns || purlPatterns.length === 0) {
            return vulnerabilities;
        }
        
        return vulnerabilities.filter(vulnerability => {
            return purlPatterns.some(pattern => {
                // Simple pattern matching - can be enhanced with regex if needed
                return vulnerability.purl.toLowerCase().includes(pattern.toLowerCase());
            });
        });
    }

    /**
     * Get unique vulnerability IDs from vulnerabilities
     */
    static getUniqueVulnIds(vulnerabilities: VulnerabilityDto[]): string[] {
        const vulnIds = new Set<string>();
        vulnerabilities.forEach(vulnerability => vulnIds.add(vulnerability.vulnId));
        return Array.from(vulnIds).sort();
    }

    /**
     * Get unique PURLs from vulnerabilities
     */
    static getUniquePurls(vulnerabilities: VulnerabilityDto[]): string[] {
        const purls = new Set<string>();
        vulnerabilities.forEach(vulnerability => purls.add(vulnerability.purl));
        return Array.from(purls).sort();
    }

    /**
     * Get vulnerability statistics
     */
    static getVulnerabilityStatistics(vulnerabilities: VulnerabilityDto[]): {
        total: number;
        bySeverity: Record<string, number>;
        byVulnId: Record<string, number>;
        uniquePurls: number;
    } {
        const stats = {
            total: vulnerabilities.length,
            bySeverity: {} as Record<string, number>,
            byVulnId: {} as Record<string, number>,
            uniquePurls: 0
        };

        const purls = new Set<string>();

        vulnerabilities.forEach(vulnerability => {
            // Count by severity
            stats.bySeverity[vulnerability.severity] = (stats.bySeverity[vulnerability.severity] || 0) + 1;
            
            // Count by vulnerability ID
            stats.byVulnId[vulnerability.vulnId] = (stats.byVulnId[vulnerability.vulnId] || 0) + 1;
            
            // Track unique PURLs
            purls.add(vulnerability.purl);
        });

        stats.uniquePurls = purls.size;

        return stats;
    }
}
