import { describe, it, expect } from 'vitest';
import * as fs from 'fs';
import * as path from 'path';

// SCORE-51 (SCORE-50a) T-5: rebom has no bom_lineage table and no owner check.
// The migration versions are contiguous from 1 with no pinned maximum (V9 is
// free again), and nothing under src/ names the table, the owner error or its
// code. SCORE-54 (SCORE-50d) T-3: nor the removed compatibility stubs, their
// types or the ownerComponent input.
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

    it('names none of the SCORE-50 compatibility stubs, their types or ownerComponent (SCORE-54 T-3)', () => {
        const files = filesUnder(SRC);
        expect(files.length).toBeGreaterThan(0);
        const patterns = [
            /\bownerComponent\b/,
            /\bsetBomLineageOwners\b/,
            /\bbomLineage\b/,
            /\bBomLineage(Dto|Owner\w*)\b/,
            /NO_SUCH_LINEAGE/,
            /MAX_LINEAGE_OWNER_ENTRIES/
        ];
        for (const file of files) {
            const content = fs.readFileSync(file, 'utf8');
            for (const pattern of patterns) {
                expect(pattern.test(content), `${path.relative(ROOT, file)} matches ${pattern}`).toBe(false);
            }
        }
    });

    it('has no services/bom/bomLineageService.ts (SCORE-54 T-3)', () => {
        expect(fs.existsSync(path.join(SRC, 'services', 'bom', 'bomLineageService.ts'))).toBe(false);
    });

    it('still has bomLineageRepository.ts, the ADR-13 lock (SCORE-54 3.3)', () => {
        expect(fs.existsSync(path.join(SRC, 'bomLineageRepository.ts'))).toBe(true);
    });
});
