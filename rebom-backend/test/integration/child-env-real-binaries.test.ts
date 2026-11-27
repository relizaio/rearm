import { afterAll, afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// utils.ts and childProcess.ts capture spawn at load, so it is patched before any import runs: every
// spawn is recorded and then runs for real.
const rec = vi.hoisted(() => {
    const cp = (process as any).getBuiltinModule('node:child_process');
    const original = cp.spawn;
    const calls: { cmd: string, args: string[], options: any }[] = [];
    cp.spawn = (cmd: string, args: string[], options: any) => {
        calls.push({ cmd, args: [...args], options });
        return original(cmd, args, options);
    };
    return { cp, original, calls };
});

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

import * as fs from 'fs';
import * as path from 'path';
import * as BomService from '../../src/bomService';
import * as BomDiffService from '../../src/services/bom/bomDiffService';
import { mergeBomObjects } from '../../src/services/bom/bomMergeService';
import { scoreBom } from '../../src/services/bom/bomScoreService';
import { SpdxService } from '../../src/services/spdx/spdxService';
import { buildChildEnv, ChildTool } from '../../src/childEnv';
import { pool } from '../../src/utils';
import { clearMockOciStorage } from '../../src/services/oci';
import { TEST_ORG_UUID, loadFixture, createTestRebomOptions, generateSerialNumber } from '../helpers';

/**
 * The real rearm CLI and cyclonedx-cli with the short environment of their tool (TPF-256 design 3.1
 * and 3.9; rows T-1200 and T-1201). They run in the image's builder stage with the binaries on PATH
 * and do not skip when one is missing. A canary is planted in process.env; POSTGRES_PASSWORD is set
 * there by the test configuration.
 */

const CANARY = 'CHILD_ENV_CANARY';
const FIXTURES = path.join(__dirname, '..', 'fixtures');

const sorted = (names: string[]) => [...names].sort();

function expectToolEnvironment(call: { options: any }, tool: ChildTool): void {
    const env = call.options.env;
    expect(sorted(Object.keys(env))).toStrictEqual(sorted(Object.keys(buildChildEnv(tool))));
    expect(env).toStrictEqual(buildChildEnv(tool));
    expect(Object.prototype.hasOwnProperty.call(env, CANARY)).toBe(false);
    expect(Object.prototype.hasOwnProperty.call(env, 'POSTGRES_PASSWORD')).toBe(false);
}

const spdx = () => ({
    spdxVersion: 'SPDX-2.3', dataLicense: 'CC0-1.0', SPDXID: 'SPDXRef-DOCUMENT', name: 'child-env-check',
    documentNamespace: 'https://example.invalid/child-env-check',
    creationInfo: { created: '2026-11-27T00:00:00Z', creators: ['Tool: test'] },
    packages: [{
        SPDXID: 'SPDXRef-left-pad', name: 'left-pad', versionInfo: '1.3.0', downloadLocation: 'NOASSERTION',
        externalRefs: [{ referenceCategory: 'PACKAGE-MANAGER', referenceType: 'purl', referenceLocator: 'pkg:npm/left-pad@1.3.0' }]
    }],
    relationships: [{ spdxElementId: 'SPDXRef-DOCUMENT', relationshipType: 'DESCRIBES', relatedSpdxElement: 'SPDXRef-left-pad' }]
});

describe('the real tools with the environment of their row', () => {
    let savedCanary: string | undefined;
    const createdSerialNumbers: string[] = [];

    beforeEach(() => {
        rec.calls.length = 0;
        savedCanary = process.env[CANARY];
        process.env[CANARY] = 'canary-value';
        clearMockOciStorage();
    });

    afterEach(async () => {
        if (savedCanary === undefined) delete process.env[CANARY];
        else process.env[CANARY] = savedCanary;
        if (createdSerialNumbers.length > 0) {
            await pool.query(`DELETE FROM rebom.boms WHERE meta->>'serialNumber' = ANY($1)`, [createdSerialNumbers]);
            createdSerialNumbers.length = 0;
        }
    });

    afterAll(() => {
        rec.cp.spawn = rec.original;
    });

    it('T-1200 merge: rearm bomutils merge-boms gives a merged document with the REARM_BOM environment', async () => {
        expect(process.env.POSTGRES_PASSWORD).toBeDefined();
        const a = JSON.parse(fs.readFileSync(path.join(FIXTURES, 'merge', 'engine-check-a.cdx.json'), 'utf8'));
        const b = JSON.parse(fs.readFileSync(path.join(FIXTURES, 'merge', 'engine-check-b.cdx.json'), 'utf8'));
        const options: any = { name: 'child-env', group: 'Example', version: '1.0.0', purl: 'pkg:generic/example/child-env@1.0.0', structure: 'FLAT', tldOnly: false, ignoreDev: false };

        const merged = await mergeBomObjects([a, b], options);

        expect(merged.bomFormat).toBe('CycloneDX');
        expect(Array.isArray(merged.components)).toBe(true);
        expect(rec.calls.map((c) => c.cmd)).toStrictEqual(['rearm']);
        expect(rec.calls[0].args.slice(0, 2)).toStrictEqual(['bomutils', 'merge-boms']);
        expectToolEnvironment(rec.calls[0], ChildTool.REARM_BOM);
    });

    it('T-1200 SPDX conversion with its validation: a converted document that validates, REARM_BOM then CYCLONEDX_CLI', async () => {
        const result = await SpdxService.convertSpdxToCycloneDx(spdx());

        expect(result.error).toBeUndefined();
        expect(result.success).toBe(true);
        expect(result.convertedBom.bomFormat).toBe('CycloneDX');
        expect(JSON.stringify(result.convertedBom)).toContain('pkg:npm/left-pad@1.3.0');
        expect(rec.calls.map((c) => c.cmd)).toStrictEqual(['rearm', 'cyclonedx-cli']);
        expectToolEnvironment(rec.calls[0], ChildTool.REARM_BOM);
        expect(rec.calls[1].args[0]).toBe('validate');
        expectToolEnvironment(rec.calls[1], ChildTool.CYCLONEDX_CLI);
    });

    it('T-1200 score: rearm bomutils score gives a report with the REARM_BOM environment', async () => {
        const bom = fs.readFileSync(path.join(FIXTURES, 'score', 'full.cdx.json'), 'utf8');

        const report = JSON.parse(await scoreBom(bom, ['fda']));

        expect(report.reportVersion).toBe(1);
        expect(Array.isArray(report.profiles)).toBe(true);
        expect(rec.calls.map((c) => c.cmd)).toStrictEqual(['rearm']);
        expect(rec.calls[0].args.slice(0, 2)).toStrictEqual(['bomutils', 'score']);
        expectToolEnvironment(rec.calls[0], ChildTool.REARM_BOM);
    });

    it('T-1201 diff: cyclonedx-cli diff of two stored documents gives a parsed result with the CYCLONEDX_CLI environment', async () => {
        const ids: string[] = [];
        for (const [fixture, version] of [['cyclonedx-simple.json', '1.0.0'], ['cyclonedx-for-merge.json', '2.0.0']]) {
            const bom = loadFixture(fixture);
            const serialNumber = generateSerialNumber();
            createdSerialNumbers.push(serialNumber);
            bom.serialNumber = serialNumber;
            const created = await BomService.addBom({
                bomInput: {
                    format: 'CYCLONEDX' as const, bom, org: TEST_ORG_UUID,
                    rebomOptions: createTestRebomOptions({ serialNumber, name: 'app', version })
                }
            });
            ids.push(created.uuid);
        }
        rec.calls.length = 0;

        const result = await BomDiffService.bomDiffExec([ids[0]] as [string], [ids[1]] as [string], TEST_ORG_UUID);

        expect(result).toBeTypeOf('object');
        expect(result).toHaveProperty('componentVersions');
        const diffs = rec.calls.filter((c) => c.cmd === 'cyclonedx-cli');
        expect(diffs).toHaveLength(1);
        expect(diffs[0].args[0]).toBe('diff');
        expectToolEnvironment(diffs[0], ChildTool.CYCLONEDX_CLI);
        for (const merge of rec.calls.filter((c) => c.cmd === 'rearm')) expectToolEnvironment(merge, ChildTool.REARM_BOM);
    });
});
