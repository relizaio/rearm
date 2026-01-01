import { SarifService } from '../src/services/sarif';
import { parseSarifFromString } from '../src/services/sarif/sarifParser';
import * as fs from 'fs';
import * as path from 'path';

/**
 * Test script for SARIF parser functionality
 */
async function testSarifParser() {
    console.log('Testing SARIF Parser...\n');

    try {
        // Test with sample SARIF file
        const sampleFilePath = path.join(__dirname, 'sample-sarif.json');
        console.log(`Reading sample SARIF file: ${sampleFilePath}`);
        
        const fileContent = await fs.promises.readFile(sampleFilePath, 'utf-8');
        const weaknesses = SarifService.parseSarifContent(fileContent);
        
        console.log(`\nParsed ${weaknesses.length} weaknesses:`);
        console.log('=====================================');
        
        weaknesses.forEach((weakness, index) => {
            console.log(`${index + 1}. Weakness:`);
            console.log(`   CWE ID: ${weakness.cweId}`);
            console.log(`   Rule ID: ${weakness.ruleId}`);
            console.log(`   Location: ${weakness.location}`);
            console.log(`   Fingerprint: ${weakness.fingerprint}`);
            console.log(`   Severity: ${weakness.severity}`);
            console.log('');
        });


        // Test filtering
        const highSeverityWeaknesses = SarifService.filterWeaknessesBySeverity(weaknesses, ['HIGH']);
        console.log(`High severity weaknesses: ${highSeverityWeaknesses.length}`);

        const cwe89Weaknesses = SarifService.filterWeaknessesByCwe(weaknesses, ['CWE-89']);
        console.log(`CWE-89 weaknesses: ${cwe89Weaknesses.length}`);

        console.log('\n✅ SARIF parser test completed successfully!');

    } catch (error) {
        console.error('❌ Error testing SARIF parser:', error);
        process.exit(1);
    }
}

// Run the test if this file is executed directly
if (require.main === module) {
    testSarifParser();
}

export { testSarifParser };
