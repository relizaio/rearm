import { CycloneDxService } from '../src/services/cyclonedx';
import { logger } from '../src/logger';
import * as path from 'path';
import * as fs from 'fs';
import { VulnerabilityDto } from '../src/types';

/**
 * Test script for CycloneDX VDR/BOV parser
 */
async function testCycloneDxParser() {
    try {
        console.log('🧪 Testing CycloneDX VDR/BOV Parser...\n');

        // Test file path
        const testFilePath = path.join(__dirname, 'sample-cyclonedx-vdr.json');
        
        console.log('📁 Testing file parsing...');
        const fileContent = await fs.promises.readFile(testFilePath, 'utf-8');
        const vulnerabilities: VulnerabilityDto[] = CycloneDxService.parseCycloneDxContent(fileContent);
        
        console.log(`✅ Successfully parsed ${vulnerabilities.length} vulnerabilities\n`);

        // Display parsed vulnerabilities
        console.log('🔍 Parsed Vulnerabilities:');
        console.log('=' .repeat(50));
        vulnerabilities.forEach((vuln: VulnerabilityDto, index: number) => {
            console.log(`${index + 1}. Vulnerability ID: ${vuln.vulnId}`);
            console.log(`   PURL: ${vuln.purl}`);
            console.log(`   Severity: ${vuln.severity}`);
            console.log('');
        });

        // Test statistics
        console.log('📊 Vulnerability Statistics:');
        console.log('=' .repeat(30));
        const stats = CycloneDxService.getVulnerabilityStatistics(vulnerabilities);
        console.log(`Total vulnerabilities: ${stats.total}`);
        console.log(`Unique PURLs: ${stats.uniquePurls}`);
        console.log('\nBy Severity:');
        Object.entries(stats.bySeverity).forEach(([severity, count]: [string, number]) => {
            console.log(`  ${severity}: ${count}`);
        });
        console.log('\nBy Vulnerability ID:');
        Object.entries(stats.byVulnId).forEach(([vulnId, count]: [string, number]) => {
            console.log(`  ${vulnId}: ${count}`);
        });

        // Test filtering
        console.log('\n🔧 Testing Filters:');
        console.log('=' .repeat(20));
        
        const criticalVulns = CycloneDxService.filterVulnerabilitiesBySeverity(vulnerabilities, ['CRITICAL']);
        console.log(`Critical vulnerabilities: ${criticalVulns.length}`);
        
        const highVulns = CycloneDxService.filterVulnerabilitiesBySeverity(vulnerabilities, ['HIGH']);
        console.log(`High vulnerabilities: ${highVulns.length}`);
        
        const lodashVulns = CycloneDxService.filterVulnerabilitiesByPurl(vulnerabilities, ['lodash']);
        console.log(`Lodash vulnerabilities: ${lodashVulns.length}`);

        // Test unique collections
        console.log('\n📋 Unique Collections:');
        console.log('=' .repeat(25));
        const uniqueVulnIds = CycloneDxService.getUniqueVulnIds(vulnerabilities);
        console.log(`Unique Vulnerability IDs: ${uniqueVulnIds.join(', ')}`);
        
        const uniquePurls = CycloneDxService.getUniquePurls(vulnerabilities);
        console.log(`Unique PURLs: ${uniquePurls.length}`);
        uniquePurls.forEach(purl => console.log(`  - ${purl}`));

        console.log('\n✅ All CycloneDX VDR/BOV parser tests completed successfully!');

    } catch (error) {
        console.error('❌ Test failed:', error);
        logger.error(`CycloneDX parser test failed: ${error}`);
    }
}

// Run the test if this file is executed directly
if (require.main === module) {
    testCycloneDxParser();
}

export { testCycloneDxParser };
