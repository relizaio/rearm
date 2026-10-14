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
    SCE_ROOT, DELIVERABLE_ROOT, RELEASE_ROOT, RELEASE_NODE, PLACEHOLDER_REF, EXTERNAL_PLACEHOLDER_REF, buildProductExport
} from '../releaseNodeFacts.fixture';

/**
 * SCORE-30 (T-10, ADR-5, section 5): the product export of releaseNodeFacts.fixture, whose uploaded
 * components are complete, scored by the real `rearm bomutils score`. Producer and supplier pass
 * on every node but the EXTERNAL component's placeholder and the root of the row stored without a
 * supplier (both by design, ADR-2, ADR-12); the hash passes where ReARM holds a digest and fails
 * on the source code entry root (its commit is a pedigree fact, ADR-4), the release-attached root
 * and the unhashed placeholder; the licence fails on every node ReARM wrote and every uploaded root
 * without one, the remaining gap. The same document with the fields this task writes taken out
 * fails producer on every ReARM node: the change is what moves them.
 */

/** The placeholders have no purl: the engine names them name@version. */
const PLACEHOLDER = 'worker@4.0.0';
const EXTERNAL_PLACEHOLDER = 'vendor-db@15.0.0';

const PRODUCER_CHECKS = ['cisa-2026.component-producer', 'ntia-2021.supplier-name', 'fda.baseline.supplier-name'];
const HASH_CHECKS = ['cisa-2026.component-hash-value', 'fda.baseline.component-hash'];

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
 * The product export as it read before this task: no supplier, hashes or pedigree on the release
 * node and the uploaded roots, no hashes on the placeholders (the INTERNAL placeholder's supplier
 * is SCORE-15's).
 */
function preTaskShape(bom: any): any {
    const copy = JSON.parse(JSON.stringify(bom));
    for (const c of copy.components) {
        if ([RELEASE_NODE, SCE_ROOT, DELIVERABLE_ROOT, RELEASE_ROOT].includes(c['bom-ref'])) {
            delete c.supplier;
            delete c.hashes;
            delete c.pedigree;
        }
        if ([PLACEHOLDER_REF, EXTERNAL_PLACEHOLDER_REF].includes(c['bom-ref'])) delete c.hashes;
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

function failing(report: any, id: string): string[] {
    return [...check(report, id).failing].sort();
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

    it('fails producer and supplier name exactly on the EXTERNAL placeholder and the legacy-shape root (T-10)', async () => {
        const { product } = await buildProductExport(created, true);
        const report = score(product);

        for (const id of PRODUCER_CHECKS) {
            const c = check(report, id);
            expect(failing(report, id), id).toStrictEqual([EXTERNAL_PLACEHOLDER, RELEASE_ROOT].sort());
            // the release node, three uploaded roots, five libraries and two placeholders
            expect(c.total, id).toBe(11);
            expect(c.passed, id).toBe(9);
        }
    });

    it('fails the hash on the source code entry root, the release-attached root and the unhashed placeholder (T-10)', async () => {
        const { product } = await buildProductExport(created, true);
        const report = score(product);

        for (const id of HASH_CHECKS) {
            const c = check(report, id);
            expect(failing(report, id), id).toStrictEqual([EXTERNAL_PLACEHOLDER, RELEASE_ROOT, SCE_ROOT].sort());
            expect(c.passed, id).toBe(c.total - 3);
        }
    });

    it('fails the licence exactly on the nodes ReARM wrote and the uploaded roots without one (T-10, ADR-5)', async () => {
        const { product } = await buildProductExport(created, true);
        const report = score(product);

        expect(failing(report, 'cisa-2026.component-license'))
            .toStrictEqual([DELIVERABLE_ROOT, RELEASE_NODE, RELEASE_ROOT, SCE_ROOT, PLACEHOLDER, EXTERNAL_PLACEHOLDER].sort());
        expect(check(report, 'cisa-2026.component-license').passed).toBe(5);
    });

    it('without the facts the release node names no supplier and carries no hash, nor does the placeholder (T-10)', async () => {
        const { product } = await buildProductExport(created, false);
        const report = score(product);

        for (const id of PRODUCER_CHECKS) {
            expect(failing(report, id), id).toStrictEqual([EXTERNAL_PLACEHOLDER, RELEASE_NODE, RELEASE_ROOT].sort());
        }
        expect(failing(report, 'fda.baseline.component-hash'))
            .toStrictEqual([EXTERNAL_PLACEHOLDER, PLACEHOLDER, RELEASE_NODE, RELEASE_ROOT, SCE_ROOT].sort());
    });

    it('the pre-task shape, the fields this task writes taken out, fails producer on every ReARM node (T-10)', async () => {
        const { product } = await buildProductExport(created, true);
        const report = score(preTaskShape(product));

        for (const id of PRODUCER_CHECKS) {
            expect(failing(report, id), id)
                .toStrictEqual([DELIVERABLE_ROOT, EXTERNAL_PLACEHOLDER, RELEASE_NODE, RELEASE_ROOT, SCE_ROOT].sort());
        }
        expect(failing(report, 'fda.baseline.component-hash'))
            .toStrictEqual([DELIVERABLE_ROOT, EXTERNAL_PLACEHOLDER, PLACEHOLDER, RELEASE_NODE, RELEASE_ROOT, SCE_ROOT].sort());
    });
});
