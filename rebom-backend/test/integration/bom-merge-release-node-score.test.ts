import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { execFileSync } from 'child_process';

// Mock integrationRepository before importing bomService (table may not exist in test DB)
vi.mock('../../src/integrationRepository', () => ({
    findIntegrationByTypeAndOrg: vi.fn().mockResolvedValue(null),
    upsertIntegration: vi.fn(),
    createSecret: vi.fn(),
    findSecretById: vi.fn(),
    updateSecret: vi.fn(),
    deleteSecret: vi.fn(),
    deleteIntegration: vi.fn(),
}));

import { pool } from '../../src/utils';
import { clearMockOciStorage } from '../../src/services/oci';
import {
    SCE_ROOT, DELIVERABLE_ROOT, RELEASE_ROOT, RELEASE_NODE, PLACEHOLDER_REF, buildProductExport
} from '../releaseNodeFacts.fixture';

/**
 * SCORE-30 (T-10, ADR-5, section 5): the product export of releaseNodeFacts.fixture, whose uploaded
 * components are complete, scored by the real `rearm bomutils score`. Producer and supplier now
 * pass on every node; the hash fails only where ReARM holds none (the release-attached root);
 * the licence fails on every node ReARM wrote and every uploaded root without one, the remaining
 * gap. The same document with the fields this task writes taken out fails producer and hash on
 * those nodes: the change is what moves them.
 */

/** The placeholder has no purl: the engine names it name@version. */
const PLACEHOLDER = 'worker@4.0.0';

function score(bom: any): any {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'score30-'));
    try {
        const file = path.join(dir, 'bom.json');
        fs.writeFileSync(file, JSON.stringify(bom));
        return JSON.parse(execFileSync('rearm', ['bomutils', 'score', '--profile', 'cisa-2026', '--profile', 'ntia-2021',
            '--profile', 'fda', '--format', 'json', '--infile', file], { encoding: 'utf8' }));
    } finally {
        fs.rmSync(dir, { recursive: true, force: true });
    }
}

/**
 * The product export as it read before this task: no supplier and no hashes on the release node
 * and the uploaded roots, no hashes on the placeholder (its supplier is SCORE-15's).
 */
function preTaskShape(bom: any): any {
    const copy = JSON.parse(JSON.stringify(bom));
    for (const c of copy.components) {
        if ([RELEASE_NODE, SCE_ROOT, DELIVERABLE_ROOT, RELEASE_ROOT].includes(c['bom-ref'])) {
            delete c.supplier;
            delete c.hashes;
        }
        if (c['bom-ref'] === PLACEHOLDER_REF) delete c.hashes;
    }
    return copy;
}

function check(report: any, id: string): any {
    for (const profile of report.profiles) {
        const found = profile.checks.find((c: any) => c.id === id);
        if (found) return found;
    }
    throw new Error(`no check ${id} in the report`);
}

// The image pins a CLI with `bomutils score` for linux/amd64 only (SCORE-4 design 3.7).
describe.skipIf(process.arch !== 'x64')('the score of a product export whose uploaded components are complete', () => {
    const created: string[] = [];

    beforeEach(() => {
        clearMockOciStorage();
    });

    afterEach(async () => {
        if (created.length > 0) {
            await pool.query('DELETE FROM rebom.boms WHERE uuid = ANY($1::uuid[])', [created]);
            created.length = 0;
        }
    });

    it('passes producer and supplier name on every node (T-10)', async () => {
        const { product } = await buildProductExport(created, true);
        const report = score(product);

        for (const id of ['cisa-2026.component-producer', 'ntia-2021.supplier-name', 'fda.baseline.supplier-name']) {
            const c = check(report, id);
            expect(c.failing, id).toStrictEqual([]);
            expect(c.passed, id).toBe(c.total);
            // the release node, three uploaded roots, five libraries and the placeholder
            expect(c.total, id).toBe(10);
        }
    });

    it('fails the hash only on the release-attached root, which ReARM holds no hash for (T-10)', async () => {
        const { product } = await buildProductExport(created, true);
        const report = score(product);

        for (const id of ['cisa-2026.component-hash-value', 'cisa-2026.component-hash-algorithm', 'fda.baseline.component-hash']) {
            const c = check(report, id);
            expect(c.failing, id).toStrictEqual([RELEASE_ROOT]);
            expect(c.passed, id).toBe(c.total - 1);
        }
    });

    it('fails the licence exactly on the nodes ReARM wrote and the uploaded roots without one (T-10, ADR-5)', async () => {
        const { product } = await buildProductExport(created, true);
        const report = score(product);

        const c = check(report, 'cisa-2026.component-license');
        expect([...c.failing].sort()).toStrictEqual([DELIVERABLE_ROOT, RELEASE_NODE, RELEASE_ROOT, SCE_ROOT, PLACEHOLDER].sort());
        expect(c.passed).toBe(5);
    });

    it('without the facts the release node still names the organization (stored row) but carries no hash, nor does the placeholder (T-10)', async () => {
        const { product } = await buildProductExport(created, false);
        const report = score(product);

        // ADR-4 fills every stored root from its row, a merged one too: the supplier is there
        for (const id of ['cisa-2026.component-producer', 'ntia-2021.supplier-name', 'fda.baseline.supplier-name']) {
            expect(check(report, id).failing, id).toStrictEqual([]);
        }
        expect([...check(report, 'fda.baseline.component-hash').failing].sort())
            .toStrictEqual([RELEASE_NODE, RELEASE_ROOT, PLACEHOLDER].sort());
    });

    it('the pre-task shape, the fields this task writes taken out, fails producer and hash on the ReARM nodes (T-10)', async () => {
        const { product } = await buildProductExport(created, true);
        const before = preTaskShape(product);
        const report = score(before);

        for (const id of ['cisa-2026.component-producer', 'ntia-2021.supplier-name', 'fda.baseline.supplier-name']) {
            expect([...check(report, id).failing].sort(), id).toStrictEqual([DELIVERABLE_ROOT, RELEASE_NODE, RELEASE_ROOT, SCE_ROOT].sort());
        }
        expect([...check(report, 'fda.baseline.component-hash').failing].sort())
            .toStrictEqual([DELIVERABLE_ROOT, RELEASE_NODE, RELEASE_ROOT, SCE_ROOT, PLACEHOLDER].sort());
    });
});
