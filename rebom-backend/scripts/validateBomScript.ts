import fs from 'fs/promises';
import path from 'path';
import validateBom from '../src/validateBom';

async function main() {
    if (process.argv.length < 3) {
        console.error('Usage: ts-node scripts/validateBomScript.ts <path-to-json>');
        process.exit(1);
    }
    const filePath = process.argv[2];
    try {
        const content = await fs.readFile(path.resolve(filePath), 'utf-8');
        const json = JSON.parse(content);
        try {
            await validateBom(json);
            console.log('Validation succeeded.');
        } catch (err) {
            console.error('Validation failed:', err);
            process.exit(2);
        }
    } catch (err) {
        console.error('Error reading or parsing file:', err);
        process.exit(3);
    }
}

main();
