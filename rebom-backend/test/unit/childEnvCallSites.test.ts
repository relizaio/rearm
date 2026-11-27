import { afterAll, afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as fs from 'fs';

// utils.ts and childProcess.ts capture spawn at load, so it is patched before any import runs. A
// `rearm` or `cyclonedx-cli` child is a stand-in the test drives; each spawn records its command,
// arguments and options.
const fake = vi.hoisted(() => {
    const cp = (process as any).getBuiltinModule('node:child_process');
    const { EventEmitter } = (process as any).getBuiltinModule('node:events');
    const original = cp.spawn;
    const state = {
        cp,
        original,
        calls: [] as { cmd: string, args: string[], options: any, child: any }[],
        onSpawn: null as null | ((call: { cmd: string, args: string[], options: any, child: any }) => void)
    };
    cp.spawn = (cmd: string, args: string[], options: any) => {
        if (cmd !== 'rearm' && cmd !== 'cyclonedx-cli') return original(cmd, args, options);
        const child: any = new EventEmitter();
        child.stdout = new EventEmitter();
        child.stderr = new EventEmitter();
        const call = { cmd, args: [...args], options, child };
        state.calls.push(call);
        const handler = state.onSpawn;
        setImmediate(() => {
            if (handler) handler(call);
            else { child.emit('exit', 1); child.emit('close', 1); }
        });
        return child;
    };
    return state;
});
const log = vi.hoisted(() => ({
    info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn(), trace: vi.fn(), fatal: vi.fn()
}));
const store = vi.hoisted(() => ({ boms: {} as Record<string, any> }));

vi.mock('../../src/logger', () => ({ logger: log }));
// The diff reads its inputs through the bomService barrel: the stored documents come from the
// test and the merge before the diff is replaced, so only the cyclonedx-cli child is started.
vi.mock('../../src/bomService', async (importOriginal) => {
    const actual: any = await importOriginal();
    return {
        ...actual,
        findBomObjectById: async (id: string) => store.boms[id],
        augmentBomWithComponentContext: (bom: any) => bom,
        mergeBomObjects: async (boms: any[]) => boms[0]
    };
});

import { buildChildEnv, ChildTool } from '../../src/childEnv';
import { mergeBomObjects } from '../../src/services/bom/bomMergeService';
import { bomDiffExec } from '../../src/services/bom/bomDiffService';
import { SpdxService } from '../../src/services/spdx/spdxService';

/**
 * Every call site of child (a) gives its child the environment of its tool (TPF-256 design 3.6 and
 * 3.7; row T-1199 for merge, SPDX conversion, validation and diff; the score is T-1233 in
 * bomScoreService.test.ts and the enrichment T-1001 in enrichmentBearKeyEnv.test.ts). A canary
 * and POSTGRES_PASSWORD are planted in process.env.
 */

type Call = { cmd: string, args: string[], options: any, child: any };

const CANARY = 'CHILD_ENV_CANARY';

const cdx = (serial: string, name: string) => ({
    bomFormat: 'CycloneDX', specVersion: '1.6', serialNumber: serial, version: 1,
    metadata: { component: { type: 'application', name, version: '1.0.0', 'bom-ref': name } },
    components: [{ type: 'library', name: 'left-pad', version: '1.3.0', purl: 'pkg:npm/left-pad@1.3.0', 'bom-ref': 'pkg:npm/left-pad@1.3.0' }]
});

const spdx = () => ({
    spdxVersion: 'SPDX-2.3', dataLicense: 'CC0-1.0', SPDXID: 'SPDXRef-DOCUMENT', name: 'doc',
    documentNamespace: 'https://example.invalid/doc', creationInfo: { created: '2026-11-27T00:00:00Z', creators: ['Tool: test'] },
    packages: []
});

const argAfter = (call: Call, flag: string) => call.args[call.args.indexOf(flag) + 1];

function end(call: Call, code: number, stdout?: string): void {
    if (stdout) call.child.stdout.emit('data', stdout);
    call.child.emit('exit', code);
    call.child.emit('close', code);
}

function expectNothingOfRebom(env: NodeJS.ProcessEnv): void {
    expect(Object.prototype.hasOwnProperty.call(env, CANARY)).toBe(false);
    expect(Object.prototype.hasOwnProperty.call(env, 'POSTGRES_PASSWORD')).toBe(false);
}

describe('T-1199 the environment at each call site', () => {
    const saved: Record<string, string | undefined> = {};

    beforeEach(() => {
        for (const name of [CANARY, 'POSTGRES_PASSWORD']) saved[name] = process.env[name];
        process.env[CANARY] = 'canary-value';
        process.env.POSTGRES_PASSWORD = 'pg-password-value';
        fake.calls.length = 0;
        fake.onSpawn = null;
        vi.clearAllMocks();
    });

    afterEach(() => {
        for (const [name, value] of Object.entries(saved)) {
            if (value === undefined) delete process.env[name];
            else process.env[name] = value;
        }
        fake.onSpawn = null;
    });

    afterAll(() => {
        fake.cp.spawn = fake.original;
    });

    it('merge: rearm bomutils merge-boms, the argument array as before, no timeout, stderr at info, the REARM_BOM environment', async () => {
        const options: any = { name: 'merged', group: 'Example', version: '1.0.0', purl: 'pkg:generic/example/merged@1.0.0', structure: 'FLAT', tldOnly: false, ignoreDev: false };
        fake.onSpawn = (call) => {
            call.child.stderr.emit('data', 'merge-boms: a stderr line');
            end(call, 1);
        };
        await expect(mergeBomObjects([cdx('urn:uuid:1a2b3c4d-0000-4000-8000-000000000001', 'one'), cdx('urn:uuid:1a2b3c4d-0000-4000-8000-000000000002', 'two')], options))
            .rejects.toBeInstanceOf(Error);

        expect(fake.calls).toHaveLength(1);
        const call = fake.calls[0];
        expect(call.cmd).toBe('rearm');
        const inputs = call.args.filter((_a, i) => call.args[i - 1] === '--input-files');
        expect(inputs).toHaveLength(2);
        expect(call.args).toStrictEqual([
            'bomutils', 'merge-boms', '--structure', 'FLAT', '--group', 'Example', '--name', 'merged', '--version', '1.0.0',
            '--input-files', inputs[0], '--input-files', inputs[1], '--purl', 'pkg:generic/example/merged@1.0.0'
        ]);
        expect(Object.keys(call.options)).toStrictEqual(['env']);
        expect(call.options.env).toStrictEqual(buildChildEnv(ChildTool.REARM_BOM));
        expectNothingOfRebom(call.options.env);
        // the fourth argument is still info
        expect(log.info.mock.calls.some((c) => c[0] === 'shell command stderr: merge-boms: a stderr line')).toBe(true);
        expect(log.error.mock.calls.some((c) => String(c[0]).startsWith('shell command error: merge-boms'))).toBe(false);
    });

    it('SPDX conversion and its validation: rearm with a 60000 ms timeout and REARM_BOM, cyclonedx-cli with CYCLONEDX_CLI', async () => {
        fake.onSpawn = (call) => {
            if (call.cmd === 'rearm') {
                fs.writeFileSync(argAfter(call, '--outfile'), JSON.stringify(cdx('urn:uuid:9c0e3a52-1d7b-4f60-8e21-6a5b4c3d2e10', 'conv')));
                end(call, 0, 'converted');
            } else {
                end(call, 0);
            }
        };
        const result = await SpdxService.convertSpdxToCycloneDx(spdx());
        expect(result.success).toBe(true);

        expect(fake.calls.map((c) => c.cmd)).toStrictEqual(['rearm', 'cyclonedx-cli']);
        const [convert, validate] = fake.calls;
        expect(convert.args).toStrictEqual(['bomutils', 'convert-spdx', '--infile', argAfter(convert, '--infile'), '--outfile', argAfter(convert, '--outfile')]);
        expect(Object.keys(convert.options).sort()).toStrictEqual(['env', 'timeout']);
        expect(convert.options.timeout).toBe(60000);
        expect(convert.options.env).toStrictEqual(buildChildEnv(ChildTool.REARM_BOM));
        expectNothingOfRebom(convert.options.env);

        expect(validate.args).toStrictEqual(['validate', '--input-format', 'json', '--input-file', argAfter(convert, '--outfile')]);
        expect(Object.keys(validate.options)).toStrictEqual(['env']);
        expect(validate.options.env).toStrictEqual(buildChildEnv(ChildTool.CYCLONEDX_CLI));
        expectNothingOfRebom(validate.options.env);
    });

    it('diff: cyclonedx-cli diff, the argument array as before, no timeout, the CYCLONEDX_CLI environment', async () => {
        store.boms = { from: cdx('urn:uuid:1a2b3c4d-0000-4000-8000-0000000000a1', 'from'), to: cdx('urn:uuid:1a2b3c4d-0000-4000-8000-0000000000a2', 'to') };
        fake.onSpawn = (call) => end(call, 0, JSON.stringify({ componentVersions: {} }));

        const result = await bomDiffExec(['from'], ['to'], 'org');
        expect(result).toStrictEqual({ componentVersions: {} });

        expect(fake.calls).toHaveLength(1);
        const call = fake.calls[0];
        expect(call.cmd).toBe('cyclonedx-cli');
        const files = call.args.slice(-2);
        expect(call.args).toStrictEqual([
            'diff', '--from-format', 'json', '--to-format', 'json', '--output-format', 'json', '--component-versions', files[0], files[1]
        ]);
        expect(Object.keys(call.options)).toStrictEqual(['env']);
        expect(call.options.env).toStrictEqual(buildChildEnv(ChildTool.CYCLONEDX_CLI));
        expectNothingOfRebom(call.options.env);
    });
});
