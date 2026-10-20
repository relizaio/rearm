import { describe, it, expect } from 'vitest';
import * as fs from 'fs';
import * as path from 'path';

// SCORE-51 (SCORE-50a) T-5: rebom has no bom_lineage table and no owner check.
// The migration versions are contiguous from 1 with no pinned maximum (V9 is
// free again), and nothing under src/ names the table, the owner error or its
// code.
const ROOT = path.join(__dirname, '..', '..');
const MIGRATIONS = path.join(ROOT, 'migrations');
const SRC = path.join(ROOT, 'src');

function filesUnder(dir: string): string[] {
    return fs.readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
        const full = path.join(dir, entry.name);
        return entry.isDirectory() ? filesUnder(full) : [full];
    });
}

describe('migrations', () => {
    const files = fs.readdirSync(MIGRATIONS).filter(name => name.endsWith('.sql'));

    it('are V1..VN with no gap and no duplicate', () => {
        const versions = files.map(name => {
            const match = /^V(\d+)__.+\.sql$/.exec(name);
            expect(match, `${name} is not a V<n>__*.sql migration`).not.toBeNull();
            return parseInt(match![1], 10);
        }).sort((a, b) => a - b);

        expect(versions.length).toBeGreaterThan(0);
        expect(versions).toStrictEqual(versions.map((_, i) => i + 1));
    });

    it('name and create no bom_lineage', () => {
        for (const name of files) {
            expect(name).not.toContain('bom_lineage');
            expect(fs.readFileSync(path.join(MIGRATIONS, name), 'utf8')).not.toContain('bom_lineage');
        }
    });
});

describe('src/', () => {
    it('names no bom_lineage, BomLineageOwnerError or BOM_LINEAGE_OWNER_MISMATCH', () => {
        const files = filesUnder(SRC);
        expect(files.length).toBeGreaterThan(0);
        for (const file of files) {
            const content = fs.readFileSync(file, 'utf8');
            for (const needle of ['bom_lineage', 'BomLineageOwnerError', 'BOM_LINEAGE_OWNER_MISMATCH']) {
                expect(content.includes(needle), `${path.relative(ROOT, file)} contains ${needle}`).toBe(false);
            }
        }
    });
});
