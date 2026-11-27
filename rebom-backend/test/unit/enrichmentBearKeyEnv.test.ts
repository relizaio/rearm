import { describe, it, expect, vi, beforeEach, afterEach, afterAll } from 'vitest';
import * as fs from 'fs';
import * as os from 'os';

// utils.ts captures spawn at load, so it is patched before any import runs. A `rearm` child is a
// fake the test drives (it writes the output file and ends with a code); every other command spawns
// for real. Each spawn of `rearm` records its arguments and options.
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
        if (cmd !== 'rearm') return original(cmd, args, options);
        const child: any = new EventEmitter();
        child.stdout = new EventEmitter();
        child.stderr = new EventEmitter();
        const call = { cmd, args: [...args], options, child };
        state.calls.push(call);
        const handler = state.onSpawn;
        setImmediate(() => {
            if (handler) handler(call);
            else child.emit('exit', 99);
        });
        return child;
    };
    return state;
});
const log = vi.hoisted(() => ({
    info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn(), trace: vi.fn(), fatal: vi.fn()
}));
const runQuery = vi.hoisted(() => vi.fn());
const getBearCredentials = vi.hoisted(() => vi.fn());

vi.mock('../../src/logger', () => ({ logger: log }));
// shellExec and the temp files stay real; only the database write is replaced, so the text a failed
// run hands on for storage is read off the UPDATE's parameters.
vi.mock('../../src/utils', async (importOriginal) => {
    const actual: any = await importOriginal();
    return { ...actual, runQuery: (...a: any[]) => runQuery(...a) };
});
vi.mock('../../src/services/integrationService', async (importOriginal) => {
    const actual: any = await importOriginal();
    return { ...actual, getBearCredentials: (...a: any[]) => getBearCredentials(...a) };
});

import {
    computeEnrichedBomContent, enrichBomAsync, enrichCycloneDxBom
} from '../../src/services/bom/bomProcessingService';
import { effectiveSkipPatterns } from '../../src/services/bom/enrichmentSkipPatterns';
import { BEAR_API_KEY_NUL_MESSAGE } from '../../src/services/bom/enrichmentChildEnv';
import { EnrichmentStatus } from '../../src/types';

/**
 * The BEAR key reaches the enrichment child in its environment only (TPF-254 design 3.2 to 3.5;
 * rows T-1001 to T-1005). Markers are plain words, not keys.
 */

type Call = { cmd: string, args: string[], options: any, child: any };

const MARK = 'markerkey';
const MARK_A = 'markerkeya';
const MARK_B = 'markerkeyb';
const URI = 'http://bear.invalid';
const URI_A = 'http://bear-a.invalid';
const URI_B = 'http://bear-b.invalid';
const PROBE = 'TPF254_PROBE';
const ENRICHED = { bomFormat: 'CycloneDX', specVersion: '1.6', serialNumber: 'urn:uuid:enriched', components: [] };

const bom = () => ({
    bomFormat: 'CycloneDX', specVersion: '1.6', serialNumber: 'urn:uuid:3b1d7c4e-8a7f-4c1e-9d2a-0f6b5e4d3c21', version: 1,
    components: [{ type: 'library', name: 'left-pad', version: '1.3.0', purl: 'pkg:npm/left-pad@1.3.0' }]
});

const argAfter = (call: Call, flag: string) => call.args[call.args.indexOf(flag) + 1];

function expectedArgs(call: Call, uri: string, patterns: string[]): string[] {
    return [
        'bomutils', 'enrich', '--bearUri', uri, '-f', argAfter(call, '-f'), '-o', argAfter(call, '-o'),
        ...effectiveSkipPatterns(patterns).flatMap((p) => ['--skipPattern', p])
    ];
}

function finish(call: Call, opts: { code?: number, stdout?: string, stderr?: string, output?: unknown } = {}): void {
    if (opts.output !== undefined) fs.writeFileSync(argAfter(call, '-o'), JSON.stringify(opts.output));
    if (opts.stderr) call.child.stderr.emit('data', opts.stderr);
    if (opts.stdout) call.child.stdout.emit('data', opts.stdout);
    call.child.emit('exit', opts.code ?? 0);
}

function loggedText(): string {
    const all = Object.values(log).flatMap((fn) => fn.mock.calls);
    return JSON.stringify(all, (_k, v) => (v instanceof Error ? `${v.message} ${v.stack}` : v));
}

function expectNoKeyInArgs(call: Call, key: string): void {
    expect(call.args).not.toContain('--bearApiKey');
    for (const a of call.args) expect(String(a)).not.toContain(key);
}

describe('the BEAR key goes to the enrichment child in its environment, not its arguments', () => {
    const saved: Record<string, string | undefined> = {};

    beforeEach(() => {
        for (const name of ['REARM_BEARAPIKEY', PROBE]) {
            saved[name] = process.env[name];
            delete process.env[name];
        }
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

    it('T-1001 one spawn of rearm: exact arguments without the key, the key and the inherited environment in env, process.env never holds it', async () => {
        process.env[PROBE] = 'probevalue';
        let whileRunning: unknown = 'not read';
        fake.onSpawn = (call) => {
            whileRunning = process.env.REARM_BEARAPIKEY;
            finish(call, { output: ENRICHED, stdout: 'Using BEAR at http://bear.invalid' });
        };
        expect(process.env.REARM_BEARAPIKEY).toBeUndefined();

        const result = await enrichCycloneDxBom(bom(), URI, MARK, ['x']);

        expect(result).toStrictEqual({ success: true, enrichedBom: ENRICHED });
        expect(fake.calls).toHaveLength(1);
        const call = fake.calls[0];
        expect(call.cmd).toBe('rearm');
        expect(call.args).toStrictEqual(expectedArgs(call, URI, ['x']));
        expect(call.args).toContain('x');
        expect(argAfter(call, '-f').startsWith(os.tmpdir())).toBe(true);
        expect(argAfter(call, '-o').startsWith(os.tmpdir())).toBe(true);
        expectNoKeyInArgs(call, MARK);
        expect(Object.keys(call.options).sort()).toStrictEqual(['env', 'timeout']);
        expect(call.options.timeout).toBe(1800000);
        expect(call.options.env).not.toBe(process.env);
        expect(call.options.env.REARM_BEARAPIKEY).toBe(MARK);
        expect(call.options.env[PROBE]).toBe('probevalue');
        // Everything but the one name is rebom's own environment, inherited as before.
        const { REARM_BEARAPIKEY: _key, ...rest } = call.options.env;
        expect(rest).toStrictEqual({ ...process.env });
        expect(whileRunning).toBeUndefined();
        expect(process.env.REARM_BEARAPIKEY).toBeUndefined();
    });

    it('T-1002 an inherited REARM_BEARAPIKEY never reaches the child: empty key leaves the name out, a key replaces it, process.env keeps its own', async () => {
        process.env.REARM_BEARAPIKEY = 'outside';
        fake.onSpawn = (call) => finish(call, { output: ENRICHED });

        expect((await enrichCycloneDxBom(bom(), URI, '', [])).success).toBe(true);
        expect((await enrichCycloneDxBom(bom(), URI, MARK, [])).success).toBe(true);

        expect(fake.calls).toHaveLength(2);
        const [empty, keyed] = fake.calls;
        expect(empty.args).toStrictEqual(expectedArgs(empty, URI, []));
        expect(empty.args).not.toContain('--bearApiKey');
        expect(Object.prototype.hasOwnProperty.call(empty.options.env, 'REARM_BEARAPIKEY')).toBe(false);
        expect(JSON.stringify(empty.options.env)).not.toContain('outside');
        expect(keyed.args).toStrictEqual(expectedArgs(keyed, URI, []));
        expectNoKeyInArgs(keyed, MARK);
        expect(keyed.options.env.REARM_BEARAPIKEY).toBe(MARK);
        expect(process.env.REARM_BEARAPIKEY).toBe('outside');
    });

    it('T-1003 a failed run returns the stdout as today; no log line and no temp file holds the key', async () => {
        const files: string[] = [];
        fake.onSpawn = (call) => {
            files.push(fs.readFileSync(argAfter(call, '-f'), 'utf8'), fs.readFileSync(argAfter(call, '-o'), 'utf8'));
            finish(call, { code: 1, stderr: 'a stderr chunk', stdout: 'Error during enrichment: x' });
        };

        const result = await enrichCycloneDxBom(bom(), URI, MARK, []);

        expect(result).toStrictEqual({ success: false, error: 'Error during enrichment: x' });
        expect(fake.calls).toHaveLength(1);
        expectNoKeyInArgs(fake.calls[0], MARK);
        // shellExec's failure lines are unchanged: the stderr chunk, the exit line, the stdout.
        expect(log.error).toHaveBeenCalledWith('shell command error: a stderr chunk');
        expect(log.error).toHaveBeenCalledWith('shell process exited with code 1');
        expect(log.error).toHaveBeenCalledWith('Error during enrichment: x');
        expect(log.error).toHaveBeenCalledWith(expect.objectContaining({ error: 'Error during enrichment: x' }),
            'CycloneDX BOM enrichment failed');
        expect(files).toHaveLength(2);
        for (const text of files) expect(text).not.toContain(MARK);
        expect(loggedText()).not.toContain(MARK);
        // the temp files are gone, as before
        expect(fs.existsSync(argAfter(fake.calls[0], '-f'))).toBe(false);
        expect(fs.existsSync(argAfter(fake.calls[0], '-o'))).toBe(false);
    });

    it('T-1003 a key holding a NUL byte: no spawn, the constant message, no log line holds the key', async () => {
        fake.onSpawn = (call) => finish(call, { output: ENRICHED });

        const result = await enrichCycloneDxBom(bom(), URI, MARK + String.fromCharCode(0) + 'tail', []);

        expect(fake.calls).toHaveLength(0);
        expect(result).toStrictEqual({ success: false, error: BEAR_API_KEY_NUL_MESSAGE });
        expect(result.error).toBe('BEAR API key holds a NUL byte');
        expect(loggedText()).not.toContain(MARK);
    });

    it('T-1004 two runs at once each carry their own key next to their own URI, in different env objects', async () => {
        const pending: Call[] = [];
        fake.onSpawn = (call) => {
            pending.push(call);
            if (pending.length === 2) for (const c of pending) finish(c, { output: ENRICHED });
        };

        const results = await Promise.all([
            enrichCycloneDxBom(bom(), URI_A, MARK_A, []),
            enrichCycloneDxBom(bom(), URI_B, MARK_B, [])
        ]);

        expect(results.map((r) => r.success)).toStrictEqual([true, true]);
        expect(fake.calls).toHaveLength(2);
        const a = fake.calls.find((c) => c.args.includes(URI_A))!;
        const b = fake.calls.find((c) => c.args.includes(URI_B))!;
        expect(a).toBeDefined();
        expect(b).toBeDefined();
        expect(a.options.env.REARM_BEARAPIKEY).toBe(MARK_A);
        expect(b.options.env.REARM_BEARAPIKEY).toBe(MARK_B);
        expect(a.options.env).not.toBe(b.options.env);
        expectNoKeyInArgs(a, MARK_A);
        expectNoKeyInArgs(b, MARK_B);
    });

    it('T-1005 computeEnrichedBomContent and enrichBomAsync still enrich with the key in env only; the stored error text holds no key', async () => {
        getBearCredentials.mockResolvedValue({ bearUri: URI, bearApiKey: MARK, skipPatterns: [] });

        fake.onSpawn = (call) => finish(call, { output: ENRICHED });
        const probe = await computeEnrichedBomContent('CYCLONEDX', bom(), 'org-1');
        expect(probe).toStrictEqual({ status: EnrichmentStatus.COMPLETED, enrichedBom: JSON.stringify(ENRICHED) });

        fake.onSpawn = (call) => finish(call, { code: 1, stdout: 'Error during enrichment: BEAR API authentication failed: Invalid API key' });
        await enrichBomAsync('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee', bom(), 'org-1');

        expect(getBearCredentials).toHaveBeenCalledTimes(2);
        expect(fake.calls).toHaveLength(2);
        for (const call of fake.calls) {
            expect(call.args).toStrictEqual(expectedArgs(call, URI, []));
            expectNoKeyInArgs(call, MARK);
            expect(call.options.env.REARM_BEARAPIKEY).toBe(MARK);
        }
        const updates = runQuery.mock.calls.filter(([sql]) => String(sql).includes('UPDATE rebom.boms'));
        expect(updates).toHaveLength(1);
        const params = updates[0][1];
        expect(params[0]).toBe('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee');
        expect(params[1]).toBe(JSON.stringify(EnrichmentStatus.FAILED));
        expect(params[3]).toBe(JSON.stringify('Error during enrichment: BEAR API authentication failed: Invalid API key'));
        expect(JSON.stringify(runQuery.mock.calls)).not.toContain(MARK);
        expect(loggedText()).not.toContain(MARK);
        expect(process.env.REARM_BEARAPIKEY).toBeUndefined();
    });
});
