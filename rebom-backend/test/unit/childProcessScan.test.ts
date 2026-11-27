import { describe, expect, it } from 'vitest';
import * as fs from 'fs';
import * as path from 'path';

/**
 * No child process outside the builder (TPF-256 design 3.2; row T-1197): childProcess.ts is the only
 * file under src that may name the Node child process module, and no file spreads or passes
 * process.env. The exception list holds the signature verifier until it moves to the wrapper.
 */

const SRC = path.resolve(__dirname, '../../src');
const WRAPPER = 'childProcess.ts';
const MODULE_NAME = 'child' + '_process';
const SCAN_EXCEPTIONS: readonly string[] = ['services/signatureVerifier.ts'];

const PROCESS_ENV_PATTERNS: readonly RegExp[] = [/\.\.\.\s*process\.env/, /env\s*:\s*process\.env/];

interface ScanFinding { file: string; rule: 'MODULE' | 'PROCESS_ENV' }

/** Pure: the findings for (path relative to src, text) pairs. */
function scanSources(files: readonly { file: string, text: string }[], exceptions: readonly string[]): ScanFinding[] {
    const findings: ScanFinding[] = [];
    for (const { file, text } of files) {
        if (exceptions.includes(file)) continue;
        if (file !== WRAPPER && text.includes(MODULE_NAME)) findings.push({ file, rule: 'MODULE' });
        if (PROCESS_ENV_PATTERNS.some((pattern) => pattern.test(text))) findings.push({ file, rule: 'PROCESS_ENV' });
    }
    return findings;
}

function sourceFiles(dir: string): { file: string, text: string }[] {
    const out: { file: string, text: string }[] = [];
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) out.push(...sourceFiles(full));
        else if (entry.isFile() && entry.name.endsWith('.ts')) {
            out.push({ file: path.relative(SRC, full).split(path.sep).join('/'), text: fs.readFileSync(full, 'utf8') });
        }
    }
    return out;
}

describe('T-1197 the scan of src', () => {
    const files = sourceFiles(SRC);

    it('reads the whole tree', () => {
        const names = files.map((f) => f.file);
        expect(names).toContain(WRAPPER);
        expect(names).toContain('utils.ts');
        expect(names).toContain('services/signatureVerifier.ts');
        expect(names.length).toBeGreaterThan(50);
    });

    it('no finding outside the exception list, which is the signature verifier alone', () => {
        expect(SCAN_EXCEPTIONS).toStrictEqual(['services/signatureVerifier.ts']);
        expect(scanSources(files, SCAN_EXCEPTIONS)).toStrictEqual([]);
    });

    it('childProcess.ts and the exception are the only files that name the module', () => {
        const naming = files.filter((f) => f.text.includes(MODULE_NAME)).map((f) => f.file).sort();
        expect(naming).toStrictEqual([WRAPPER, ...SCAN_EXCEPTIONS].sort());
    });

    it('each offending text gives a finding', () => {
        const fed = [
            { file: 'a.ts', text: `import { spawn } from '${MODULE_NAME}';` },
            { file: 'b.ts', text: `const { spawn } = require('node:${MODULE_NAME}');` },
            { file: 'c.ts', text: 'const env = { ...process.env, X: 1 };' },
            { file: 'd.ts', text: 'run(cmd, { env : process.env });' },
            { file: 'e.ts', text: `const cp = process.getBuiltinModule('node:${MODULE_NAME}');` }
        ];
        expect(scanSources(fed, SCAN_EXCEPTIONS)).toStrictEqual([
            { file: 'a.ts', rule: 'MODULE' },
            { file: 'b.ts', rule: 'MODULE' },
            { file: 'c.ts', rule: 'PROCESS_ENV' },
            { file: 'd.ts', rule: 'PROCESS_ENV' },
            { file: 'e.ts', rule: 'MODULE' }
        ]);
    });

    it('the wrapper may name the module but not pass process.env; an excepted file is skipped', () => {
        expect(scanSources([{ file: WRAPPER, text: `require('node:${MODULE_NAME}')` }], SCAN_EXCEPTIONS)).toStrictEqual([]);
        expect(scanSources([{ file: WRAPPER, text: 'spawn(c, a, { env: process.env })' }], SCAN_EXCEPTIONS))
            .toStrictEqual([{ file: WRAPPER, rule: 'PROCESS_ENV' }]);
        expect(scanSources([{ file: 'services/signatureVerifier.ts', text: `import '${MODULE_NAME}'; ({ ...process.env })` }], SCAN_EXCEPTIONS))
            .toStrictEqual([]);
        expect(scanSources([{ file: 'x.ts', text: 'process.env.PATH; buildChildEnv(ChildTool.BASE, process.env)' }], SCAN_EXCEPTIONS))
            .toStrictEqual([]);
    });
});
