import { describe, it, expect, vi, beforeEach, afterEach, afterAll } from 'vitest';
import * as fs from 'fs';

// utils.ts captures spawn at load, so it is patched before any import runs. The commands named in
// STAND_INS get a stand-in child (an event emitter with stdout and stderr emitters and no pid) that
// the test drives; every other command spawns for real. Each stand-in spawn is recorded.
const fake = vi.hoisted(() => {
    const cp = (process as any).getBuiltinModule('node:child_process');
    const { EventEmitter } = (process as any).getBuiltinModule('node:events');
    const original = cp.spawn;
    const STAND_INS = ['rebom-stand-in-262', 'rearm', 'cyclonedx-cli'];
    const state = {
        cp,
        original,
        calls: [] as { cmd: string, args: string[], options: any, child: any }[],
        // Set by a case to give the next stand-in no pipes (a child that could not get them).
        noPipes: false,
        onSpawn: null as null | ((call: { cmd: string, args: string[], options: any, child: any }) => void)
    };
    cp.spawn = (cmd: string, args: string[], options: any) => {
        if (!STAND_INS.includes(cmd)) return original(cmd, args, options);
        const child: any = new EventEmitter();
        if (!state.noPipes) {
            child.stdout = new EventEmitter();
            child.stderr = new EventEmitter();
        }
        const call = { cmd, args: [...args], options, child };
        state.calls.push(call);
        const handler = state.onSpawn;
        if (handler) setImmediate(() => handler(call));
        return child;
    };
    return state;
});
const log = vi.hoisted(() => ({
    info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn(), trace: vi.fn(), fatal: vi.fn()
}));
const runQuery = vi.hoisted(() => vi.fn());

vi.mock('../../src/logger', () => ({ logger: log }));
// shellExec and the temp files stay real; the database is never reached.
vi.mock('../../src/utils', async (importOriginal) => {
    const actual: any = await importOriginal();
    return { ...actual, runQuery: (...a: any[]) => runQuery(...a) };
});

import { shellExec } from '../../src/utils';
import { ChildFailureKind, ChildProcessError } from '../../src/childFailure';
import { enrichCycloneDxBom } from '../../src/services/bom/bomProcessingService';
import { mergeBomObjects } from '../../src/services/bom/bomMergeService';
import { SpdxService } from '../../src/services/spdx/spdxService';

/**
 * shellExec listens for the error event of its child and settles once (TPF-262 design 3.1 to 3.5;
 * rows T-1280 to T-1283). Markers are plain words.
 */

type Call = { cmd: string, args: string[], options: any, child: any };

const STAND_IN = 'rebom-stand-in-262';
const MISSING = 'rebom-no-such-command-4c1d';

const allLogCalls = () => Object.values(log).flatMap((fn) => fn.mock.calls);
const loggedText = () => JSON.stringify(allLogCalls(), (_k, v) => (v instanceof Error ? `${v.message} ${v.stack}` : v));
const errorLines = () => log.error.mock.calls.map((c) => c[0]).filter((a) => typeof a === 'string') as string[];

function lastCall(): Call {
    const call = fake.calls.at(-1);
    if (!call) throw new Error('no stand-in spawn recorded');
    return call;
}

/** What Node emits for a child that could not be started: error without a pid, then close with -errno. */
function notStarted(call: Call, code = 'ENOENT', errno = -2): void {
    const err: any = new Error(`spawn ${call.cmd} ${code}`);
    Object.assign(err, { code, errno, syscall: `spawn ${call.cmd}`, path: call.cmd, spawnargs: call.args });
    call.child.emit('error', err);
    call.child.emit('close', errno);
}

function settledState<T>(p: Promise<T>): { done: boolean } {
    const s = { done: false };
    p.then(() => { s.done = true; }, () => { s.done = true; });
    return s;
}

/**
 * The rejection of a child that could not be started: a ChildProcessError of kind NOT_STARTED (TPF-261
 * design 3.12, written by the second of the two pull requests to merge) whose message is exactly the
 * given text, with no code, signal or timeout of a child that never ran.
 */
function expectNotStartedError(err: any, message: string): void {
    expect(err).toBeInstanceOf(Error);
    expect(err).toBeInstanceOf(ChildProcessError);
    expect(err.kind).toBe(ChildFailureKind.NOT_STARTED);
    expect(err.message).toBe(message);
    expect(err.exitCode).toBeNull();
    expect(err.signal).toBeNull();
    expect(err.timeoutMs).toBeNull();
}

async function expectNotStarted(p: Promise<unknown>, message: string): Promise<void> {
    const err: any = await p.then(() => { throw new Error('resolved'); }, (e) => e);
    expectNotStartedError(err, message);
}

const turn = () => new Promise((r) => setImmediate(r));
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

const argAfter = (call: Call, flag: string) => call.args[call.args.indexOf(flag) + 1];
const argsAfter = (call: Call, flag: string) => call.args.filter((_a, i) => call.args[i - 1] === flag);

describe('shellExec settles when its child cannot be started, and settles once', () => {
    beforeEach(() => {
        fake.calls.length = 0;
        fake.onSpawn = null;
        fake.noPipes = false;
        vi.clearAllMocks();
    });

    afterEach(() => {
        fake.onSpawn = null;
        fake.noPipes = false;
    });

    afterAll(() => {
        fake.cp.spawn = fake.original;
    });

    describe('T-1280 a real command that does not exist', () => {
        for (const timeout of [undefined, 300]) {
            it(`rejects at once with the command and the code, nothing uncaught (timeout ${timeout ?? 'none'})`, async () => {
                const uncaught: unknown[] = [];
                const onUncaught = (e: unknown) => { uncaught.push(e); };
                process.on('uncaughtException', onUncaught);
                try {
                    const started = Date.now();
                    const err: any = await shellExec(MISSING, ['--flag', 'ARGW'], timeout).then(
                        () => { throw new Error('resolved'); },
                        (e) => e
                    );
                    expect(Date.now() - started).toBeLessThan(5000);
                    expectNotStartedError(err, `${MISSING} could not be started: ENOENT`);
                    expect(err.spawnargs).toBeUndefined();
                    expect(err.path).toBeUndefined();
                    const own = JSON.stringify(Object.getOwnPropertyNames(err).map((k) => [k, String(err[k])]));
                    for (const marker of ['ARGW', '--flag']) {
                        expect(err.message).not.toContain(marker);
                        expect(own).not.toContain(marker);
                        expect(loggedText()).not.toContain(marker);
                    }
                    const callsAtRejection = allLogCalls().length;
                    // Past the timeout: Node's timer finds no process; nothing more happens.
                    await sleep(500);
                    expect(uncaught).toEqual([]);
                    expect(allLogCalls().length).toBe(callsAtRejection);
                } finally {
                    process.off('uncaughtException', onUncaught);
                }
            });
        }
    });

    describe('T-1281 one outcome whatever order the events come in', () => {
        it('(a) error, then close -2: rejects as not started, the close logs nothing', async () => {
            const p = shellExec(STAND_IN, ['x']);
            notStarted(lastCall());
            await expectNotStarted(p, `${STAND_IN} could not be started: ENOENT`);
            expect(errorLines().some((l) => l.startsWith('shell process exited'))).toBe(false);
        });

        it('(b) error, exit 0, close 0: rejects as not started, exit and close log nothing', async () => {
            const p = shellExec(STAND_IN, ['x']);
            const { child } = lastCall();
            child.emit('error', Object.assign(new Error('spawn failed'), { code: 'ENOENT' }));
            child.emit('exit', 0);
            child.emit('close', 0);
            await expectNotStarted(p, `${STAND_IN} could not be started: ENOENT`);
            expect(errorLines().some((l) => l.startsWith('shell process exited'))).toBe(false);
        });

        it('(c) stdout, close 0, then a late error: resolves the stdout, nothing thrown or logged', async () => {
            const p = shellExec(STAND_IN, ['x']);
            const { child } = lastCall();
            child.stdout.emit('data', 'out\n');
            child.emit('close', 0);
            expect(() => child.emit('error', Object.assign(new Error('late'), { code: 'EPIPE' }))).not.toThrow();
            await expect(p).resolves.toBe('out');
            expect(allLogCalls()).toEqual([]);
        });

        it('(d) close 3, then close 0: rejects, the exit line is logged once for code 3 only', async () => {
            const p = shellExec(STAND_IN, ['x']);
            const { child } = lastCall();
            child.emit('close', 3);
            child.emit('close', 0);
            await expect(p).rejects.toBeDefined();
            const exitLines = errorLines().filter((l) => l.startsWith('shell process exited'));
            expect(exitLines).toEqual(['shell process exited with code 3']);
        });

        it('(e) a child with a pid: error EPERM is logged once and close decides', async () => {
            const p = shellExec(STAND_IN, ['x']);
            const { child } = lastCall();
            child.pid = 4242;
            child.emit('error', Object.assign(new Error('kill EPERM'), { code: 'EPERM' }));
            child.stdout.emit('data', 'out\n');
            child.emit('close', 0);
            await expect(p).resolves.toBe('out');
            expect(log.error.mock.calls).toEqual([['shell process error after start: EPERM']]);
        });

        it('(f) an error without a code: the cause reads unknown', async () => {
            const p = shellExec(STAND_IN, ['x']);
            lastCall().child.emit('error', new Error('no code'));
            await expectNotStarted(p, `${STAND_IN} could not be started: unknown`);
        });

        it('(g) a child without pipes: error EMFILE then close -24 rejects as not started, not a TypeError', async () => {
            fake.noPipes = true;
            const p = shellExec(STAND_IN, ['x']);
            const call = lastCall();
            expect(call.child.stdout).toBeUndefined();
            notStarted(call, 'EMFILE', -24);
            const err: any = await p.then(() => { throw new Error('resolved'); }, (e) => e);
            expect(err).not.toBeInstanceOf(TypeError);
            expectNotStartedError(err, `${STAND_IN} could not be started: EMFILE`);
        });
    });

    it('T-1282 the normal path ends on close and keeps what came after exit', async () => {
        const p = shellExec(STAND_IN, ['x']);
        const state = settledState(p);
        const { child } = lastCall();
        child.stdout.emit('data', 'early');
        child.emit('exit', 0);
        await turn();
        await turn();
        expect(state.done).toBe(false);
        child.stdout.emit('data', 'late\n');
        child.emit('close', 0);
        await expect(p).resolves.toBe('earlylate');
    });

    describe('T-1283 each caller ends, answers the failure and removes its temp files', () => {
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

        function expectGone(paths: string[]): void {
            expect(paths.length).toBeGreaterThan(0);
            for (const p of paths) expect(fs.existsSync(p)).toBe(false);
        }

        it('(a) enrichment returns the failure and logs it once', async () => {
            fake.onSpawn = (call) => notStarted(call);
            const started = Date.now();
            const result = await enrichCycloneDxBom(cdx('urn:uuid:3b1d7c4e-8a7f-4c1e-9d2a-0f6b5e4d3c21', 'app'), 'http://bear.invalid', 'markerkey', []);
            expect(Date.now() - started).toBeLessThan(5000);
            expect(result).toEqual({ success: false, error: 'rearm could not be started: ENOENT' });
            const failed = log.error.mock.calls.filter((c) => c[1] === 'CycloneDX BOM enrichment failed');
            expect(failed).toHaveLength(1);
            expect(fake.calls.map((c) => c.cmd)).toEqual(['rearm']);
            const call = fake.calls[0];
            expectGone([argAfter(call, '-f'), argAfter(call, '-o')]);
        });

        it('(b) SPDX conversion with rearm not started returns the failure', async () => {
            fake.onSpawn = (call) => notStarted(call);
            const started = Date.now();
            const result = await SpdxService.convertSpdxToCycloneDx(spdx());
            expect(Date.now() - started).toBeLessThan(5000);
            expect(result.success).toBe(false);
            expect(result.error).toContain('rearm could not be started: ENOENT');
            expect(fake.calls.map((c) => c.cmd)).toEqual(['rearm']);
            const call = fake.calls[0];
            expectGone([argAfter(call, '--infile'), argAfter(call, '--outfile')]);
        });

        it('(c) SPDX conversion with the validator not started returns the failure', async () => {
            fake.onSpawn = (call) => {
                if (call.cmd === 'rearm') {
                    fs.writeFileSync(argAfter(call, '--outfile'), JSON.stringify(cdx('urn:uuid:9c0e3a52-1d7b-4f60-8e21-6a5b4c3d2e10', 'conv')));
                    call.child.emit('exit', 0);
                    call.child.emit('close', 0);
                } else {
                    notStarted(call);
                }
            };
            const started = Date.now();
            const result = await SpdxService.convertSpdxToCycloneDx(spdx());
            expect(Date.now() - started).toBeLessThan(5000);
            expect(result.success).toBe(false);
            expect(result.error).toContain('cyclonedx-cli could not be started: ENOENT');
            expect(fake.calls.map((c) => c.cmd)).toEqual(['rearm', 'cyclonedx-cli']);
            const [convert, validate] = fake.calls;
            expectGone([argAfter(convert, '--infile'), argAfter(convert, '--outfile'), argAfter(validate, '--input-file')]);
        });

        it('(d) merge rejects with the failure', async () => {
            fake.onSpawn = (call) => notStarted(call);
            const options: any = { name: 'merged', group: 'Example', version: '1.0.0', purl: 'pkg:generic/example/merged@1.0.0', structure: 'FLAT', tldOnly: false, ignoreDev: false };
            const started = Date.now();
            const err: any = await mergeBomObjects(
                [cdx('urn:uuid:1a2b3c4d-0000-4000-8000-000000000001', 'one'), cdx('urn:uuid:1a2b3c4d-0000-4000-8000-000000000002', 'two')],
                options
            ).then(() => { throw new Error('resolved'); }, (e) => e);
            expect(Date.now() - started).toBeLessThan(5000);
            expectNotStartedError(err, 'rearm could not be started: ENOENT');
            expect(fake.calls.map((c) => c.cmd)).toEqual(['rearm']);
            const inputs = argsAfter(fake.calls[0], '--input-files');
            expect(inputs).toHaveLength(2);
            expectGone(inputs);
        });
    });
});
