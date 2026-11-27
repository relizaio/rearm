import { describe, it, expect, vi, beforeEach, afterAll } from 'vitest';

// utils.ts captures spawn at load, so it is patched before any import runs. A `rearm` child is a
// stand-in the test drives; every other command (the node binary of T-1245) spawns for real. The
// stand-in emits exit and then close, each with the code and the signal, so the rows hold with
// either wiring of the helper's listener.
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
        child.killed = false;
        child.signalCode = null;
        const call = { cmd, args: [...args], options, child };
        state.calls.push(call);
        const handler = state.onSpawn;
        setImmediate(() => {
            if (handler) handler(call);
            else {
                child.emit('exit', 99, null);
                child.emit('close', 99, null);
            }
        });
        return child;
    };
    return state;
});
const log = vi.hoisted(() => ({
    info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn(), trace: vi.fn(), fatal: vi.fn()
}));
vi.mock('../../src/logger', () => ({ logger: log }));

import { shellExec } from '../../src/utils';
import { ChildFailureKind, ChildProcessError } from '../../src/childFailure';

/**
 * shellExec rejects a failed child with a bounded ChildProcessError and logs nothing of its stdout
 * (TPF-261 design 3.4; rows T-1244 and T-1245). USERW, PASSW, BODYW, ARGW and FILLERW are plain
 * marker words.
 */

type Call = { cmd: string, args: string[], options: any, child: any };
const NODE = process.execPath;
const MARKERS = ['USERW', 'PASSW', 'BODYW', 'ARGW', 'FILLERW'];

function end(call: Call, stdout: string, code: number | null, signal: string | null = null, killed = false): void {
    if (stdout) call.child.stdout.emit('data', stdout);
    call.child.killed = killed;
    call.child.signalCode = signal;
    call.child.emit('exit', code, signal);
    call.child.emit('close', code, signal);
}

/** Every argument of every logger call at every level, serialised (errors with their own fields, message and stack). */
function loggedText(): string {
    const all = Object.values(log).flatMap((fn) => fn.mock.calls);
    return JSON.stringify(all, (_k, v) => (v instanceof Error ? { ...v, message: v.message, stack: v.stack } : v));
}

describe('T-1244 shellExec rejects with a bounded ChildProcessError and logs no stdout', () => {
    beforeEach(() => {
        fake.calls.length = 0;
        fake.onSpawn = null;
        vi.clearAllMocks();
    });

    afterAll(() => {
        fake.cp.spawn = fake.original;
    });

    it('a failed enrichment with a URI line, megabytes of filler and a 502 body', async () => {
        const filler = 'filler FILLERW line\n'.repeat(150000);
        const stdout = 'Using BEAR at http://USERW:PASSW@host\n' + filler
            + 'Error during enrichment: failed to enrich batch 1-1: BEAR API returned status 502: {"message":"BODYW"} BODYW\n';
        expect(stdout.length).toBeGreaterThan(3000000);
        fake.onSpawn = (call) => end(call, stdout, 1);

        let caught: unknown = null;
        try {
            await shellExec('rearm', ['--bearUri', 'http://USERW:PASSW@host', 'ARGW'], 1800000);
        } catch (error) {
            caught = error;
        }

        expect(caught).toBeInstanceOf(ChildProcessError);
        expect(caught).toBeInstanceOf(Error);
        const error = caught as ChildProcessError;
        expect(error.kind).toBe(ChildFailureKind.EXIT_CODE);
        expect(error.exitCode).toBe(1);
        expect(error.message).toBe('rearm exited with code 1: Error during enrichment: failed to enrich batch 1-1: BEAR API returned status 502');
        const serialised = JSON.stringify({ ...error, message: error.message, stack: error.stack })
            + JSON.stringify(Object.getOwnPropertyNames(error).map((k) => (error as any)[k]));
        for (const m of MARKERS) {
            expect(error.message).not.toContain(m);
            expect(serialised).not.toContain(m);
            expect(loggedText()).not.toContain(m);
        }
        expect(Object.keys(error).filter((k) => k !== 'name').sort()).toStrictEqual(['exitCode', 'kind', 'signal', 'timeoutMs']);
        // The helper's exit line stays (design 3.4); it is the only line the helper logs here.
        expect(log.error).toHaveBeenCalledWith('shell process exited with code 1');
        expect(log.error).toHaveBeenCalledTimes(1);
        expect(log.warn).not.toHaveBeenCalled();
        expect(log.info).not.toHaveBeenCalled();
        expect(log.debug).not.toHaveBeenCalled();
    });
});

describe('T-1245 a real child: the timeout is decided from the child, not from a text', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    const WAIT = 'setInterval(() => {}, 1000)';

    it('(a) a child killed by the timeout is TIMEOUT, with the line it printed', async () => {
        const failure = shellExec(NODE, ['-e', `process.stdout.write("started\\n"); ${WAIT}`], 1500);
        await expect(failure).rejects.toBeInstanceOf(ChildProcessError);
        await expect(failure).rejects.toMatchObject({
            kind: ChildFailureKind.TIMEOUT, timeoutMs: 1500, exitCode: null, signal: 'SIGTERM',
            message: `${NODE} timed out after 1500 ms: started`
        });
    });

    it('(b) a child that traps the timeout signal and exits 7 is still TIMEOUT', async () => {
        const failure = shellExec(NODE, ['-e', `process.on("SIGTERM", () => process.exit(7)); process.stdout.write("started\\n"); ${WAIT}`], 1500);
        await expect(failure).rejects.toMatchObject({
            kind: ChildFailureKind.TIMEOUT, timeoutMs: 1500, exitCode: 7,
            message: `${NODE} timed out after 1500 ms: started`
        });
    });

    it('(c) a child that kills itself is SIGNAL, not TIMEOUT', async () => {
        const failure = shellExec(NODE, ['-e', 'process.kill(process.pid, "SIGKILL")']);
        await expect(failure).rejects.toMatchObject({
            kind: ChildFailureKind.SIGNAL, signal: 'SIGKILL', exitCode: null, timeoutMs: null,
            message: `${NODE} was killed by signal SIGKILL`
        });
    });

    it('(d) a child that prints i/o timeout and exits 3 is EXIT_CODE', async () => {
        const failure = shellExec(NODE, ['-e', 'process.stdout.write("Error: dial tcp 10.0.0.1:443: i/o timeout\\n"); process.exit(3)'], 60000);
        await expect(failure).rejects.toMatchObject({
            kind: ChildFailureKind.EXIT_CODE, exitCode: 3, signal: null, timeoutMs: null,
            message: `${NODE} exited with code 3: Error: dial tcp 10.0.0.1:443: i/o timeout`
        });
    });
});
