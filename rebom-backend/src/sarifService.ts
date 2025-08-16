import { WeaknessDto } from './types';
import { parseSarifFromString, parseSarifToWeaknesses, validateSarifReport } from './sarifParser';
import { logger } from './logger';
import * as fs from 'fs';

/**
 * Service for handling SARIF file operations
 */
export class SarifService {
    
    /**
     * Parse SARIF file from file path
     */
    static async parseSarifFile(filePath: string): Promise<WeaknessDto[]> {
        try {
            const fileContent = await fs.promises.readFile(filePath, 'utf-8');
            return parseSarifFromString(fileContent);
        } catch (error) {
            logger.error(`Error reading SARIF file ${filePath}: ${error}`);
            throw new Error(`Failed to read SARIF file: ${error}`);
        }
    }

    /**
     * Parse SARIF content from string
     */
    static parseSarifContent(content: string): WeaknessDto[] {
        return parseSarifFromString(content);
    }

    /**
     * Parse SARIF content from object
     */
    static parseSarifObject(sarifData: any): WeaknessDto[] {
        if (!validateSarifReport(sarifData)) {
            throw new Error('Invalid SARIF report format');
        }
        return parseSarifToWeaknesses(sarifData);
    }

    /**
     * Filter weaknesses by severity
     */
    static filterWeaknessesBySeverity(weaknesses: WeaknessDto[], severities: string[]): WeaknessDto[] {
        if (!severities || severities.length === 0) {
            return weaknesses;
        }
        
        const severitySet = new Set(severities.map(s => s.toUpperCase()));
        return weaknesses.filter(weakness => severitySet.has(weakness.severity));
    }

    /**
     * Filter weaknesses by CWE ID
     */
    static filterWeaknessesByCwe(weaknesses: WeaknessDto[], cweIds: string[]): WeaknessDto[] {
        if (!cweIds || cweIds.length === 0) {
            return weaknesses;
        }
        
        const cweSet = new Set(cweIds.map(cwe => cwe.toUpperCase()));
        return weaknesses.filter(weakness => cweSet.has(weakness.cweId.toUpperCase()));
    }

    /**
     * Get unique CWE IDs from weaknesses
     */
    static getUniqueCweIds(weaknesses: WeaknessDto[]): string[] {
        const cweIds = new Set<string>();
        weaknesses.forEach(weakness => cweIds.add(weakness.cweId));
        return Array.from(cweIds).sort();
    }

    /**
     * Get weakness statistics
     */
    static getWeaknessStatistics(weaknesses: WeaknessDto[]): {
        total: number;
        bySeverity: Record<string, number>;
        byCwe: Record<string, number>;
        uniqueLocations: number;
    } {
        const stats = {
            total: weaknesses.length,
            bySeverity: {} as Record<string, number>,
            byCwe: {} as Record<string, number>,
            uniqueLocations: 0
        };

        const locations = new Set<string>();

        weaknesses.forEach(weakness => {
            // Count by severity
            stats.bySeverity[weakness.severity] = (stats.bySeverity[weakness.severity] || 0) + 1;
            
            // Count by CWE
            stats.byCwe[weakness.cweId] = (stats.byCwe[weakness.cweId] || 0) + 1;
            
            // Track unique locations
            locations.add(weakness.location);
        });

        stats.uniqueLocations = locations.size;

        return stats;
    }
}
