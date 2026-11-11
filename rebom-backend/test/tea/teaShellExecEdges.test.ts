import { spawn } from 'child_process';
import { describe, expect, it } from 'vitest';
import { killProcessGroup, shellExecDetailed } from '../../src/utils';
import { isAlive, sleep } from './teaTestKit';

/**
 * shellExecDetailed and killProcessGroup, the helpers under the TEA runner: the bound on each stream
 * to the byte, the kill at the deadline and at the overflow, the process group, the environment.
 */

const NODE = process.execPath;

function script(code: string): [string, string[]] {
    return [NODE, ['-e', code]];
}

async function eventually(check: () => boolean, ms = 3000): Promise<boolean> {
    const until = Date.now() + ms;
    while (Date.now() < until) {
        if (check()) return true;
        await sleep(25);
    }
    return check();
}

describe('the bound on standard error', () => {
    const run = (code: string, maxStderrBytes: number) => shellExecDetailed(...script(code), { timeoutMs: 10000, maxStdoutBytes: 1000, maxStderrBytes });

    it('keeps what fits to the byte: exactly the bound is no overflow, one more is', async () => {
        const exact = await run("process.stderr.write('a'.repeat(10))", 10);
        expect(exact.stderr).toBe('a'.repeat(10));
        expect(exact.stderrOverflow).toBe(false);
        const over = await run("process.stderr.write('a'.repeat(11))", 10);
        expect(over.stderr).toBe('a'.repeat(10));
        expect(over.stderrOverflow).toBe(true);
        const under = await run("process.stderr.write('a'.repeat(9))", 10);
        expect(under.stderr).toBe('a'.repeat(9));
        expect(under.stderrOverflow).toBe(false);
    });

    it('counts the chunks together: two that make the bound are no overflow, two that pass it are', async () => {
        const twoWrites = (n: number) => `process.stderr.write('b'.repeat(${n})); setTimeout(() => process.stderr.write('c'.repeat(${n})), 150)`;
        const fits = await run(twoWrites(5), 10);
        expect(fits.stderr).toBe('bbbbbccccc');
        expect(fits.stderrOverflow).toBe(false);
        const passes = await run(twoWrites(6), 10);
        expect(passes.stderr).toBe('bbbbbbcccc');
        expect(passes.stderrOverflow).toBe(true);
    });

    it('lets the process run on after an overflow of standard error, and reports its exit', async () => {
        const result = await run("process.stderr.write('x'.repeat(100)); process.stdout.write('done'); process.exitCode = 3", 10);
        expect(result.code).toBe(3);
        expect(result.stdout).toBe('done');
        expect(result.timedOut).toBe(false);
        expect(result.stdoutOverflow).toBe(false);
    });

    it('keeps 64 KiB of standard error by default', async () => {
        const result = await shellExecDetailed(...script("process.stderr.write('z'.repeat(70000))"), { timeoutMs: 10000, maxStdoutBytes: 10 });
        expect(result.stderr.length).toBe(65536);
        expect(result.stderrOverflow).toBe(true);
    });
});

describe('the deadline and the overflow of standard output', () => {
    it('kills a process at the deadline, reports it, and settles only once it has closed', async () => {
        const started = Date.now();
        const result = await shellExecDetailed(...script("setTimeout(() => {}, 30000)"), { timeoutMs: 300, maxStdoutBytes: 100 });
        expect(Date.now() - started).toBeLessThan(10000);
        expect(result.timedOut).toBe(true);
        expect(result.code).toBeNull();
        expect(result.signal).toBe('SIGKILL');
        expect(result.pid).toBeGreaterThan(0);
        expect(isAlive(result.pid!)).toBe(false);
    });

    it('kills the whole process group at the deadline when asked to, children included', async () => {
        const result = await shellExecDetailed('sh', ['-c', 'sleep 30 & echo $!; sleep 30'], { timeoutMs: 400, maxStdoutBytes: 100, killProcessGroup: true });
        expect(result.timedOut).toBe(true);
        const child = Number(result.stdout.trim());
        expect(child).toBeGreaterThan(0);
        expect(await eventually(() => !isAlive(child))).toBe(true);
    });

    it('without the process group a child of the process survives the kill of the process itself', async () => {
        const result = await shellExecDetailed('sh', ['-c', 'sleep 30 >/dev/null 2>&1 & echo $!; wait'], { timeoutMs: 400, maxStdoutBytes: 100 });
        expect(result.timedOut).toBe(true);
        expect(result.signal).toBe('SIGKILL');
        const child = Number(result.stdout.trim());
        expect(isAlive(child)).toBe(true);
        process.kill(child, 'SIGKILL');
    });

    it('kills a process whose standard output passes the bound, and reports the overflow with nothing past it', async () => {
        const started = Date.now();
        const result = await shellExecDetailed(...script("process.stdout.write('o'.repeat(20)); setTimeout(() => {}, 30000)"), { timeoutMs: 20000, maxStdoutBytes: 10 });
        expect(Date.now() - started).toBeLessThan(10000);
        expect(result.stdoutOverflow).toBe(true);
        expect(result.timedOut).toBe(false);
        expect(result.signal).toBe('SIGKILL');
        expect(result.stdout).toBe('');
        expect(isAlive(result.pid!)).toBe(false);
    });

    it('kills the whole group at the overflow too, and keeps what was complete before it', async () => {
        const result = await shellExecDetailed('sh', ['-c', 'echo 12345; sleep 0.3; sleep 30 & echo $!; echo 0123456789abcdef; sleep 30'],
            { timeoutMs: 20000, maxStdoutBytes: 12, killProcessGroup: true });
        expect(result.stdoutOverflow).toBe(true);
        expect(result.stdout.startsWith('12345\n')).toBe(true);
        const child = Number(result.stdout.split('\n')[1]);
        if (child > 0) expect(await eventually(() => !isAlive(child))).toBe(true);
    });

    it('accepts standard output of exactly the bound', async () => {
        const exact = await shellExecDetailed(...script("process.stdout.write('o'.repeat(10))"), { timeoutMs: 10000, maxStdoutBytes: 10 });
        expect(exact.stdoutOverflow).toBe(false);
        expect(exact.stdout).toBe('o'.repeat(10));
        expect(exact.code).toBe(0);
    });
});

describe('starting, the environment and the process group', () => {
    it('reports a process that never started, with no pid and no streams', async () => {
        const result = await shellExecDetailed('/no/such/command-rebom', [], { timeoutMs: 1000, maxStdoutBytes: 10 });
        expect(result.spawnError?.code).toBe('ENOENT');
        expect(result).toMatchObject({ code: null, signal: null, stdout: '', stderr: '', timedOut: false, stdoutOverflow: false, stderrOverflow: false });
        expect(result.pid).toBeUndefined();
    });

    it('tells the pid once, right after the start, and not for a process that never started', async () => {
        const seen: number[] = [];
        const result = await shellExecDetailed(...script('process.exitCode = 0'), { timeoutMs: 10000, maxStdoutBytes: 10, onSpawn: (pid) => seen.push(pid) });
        expect(seen).toEqual([result.pid]);
        const none: number[] = [];
        await shellExecDetailed('/no/such/command-rebom', [], { timeoutMs: 1000, maxStdoutBytes: 10, onSpawn: (pid) => none.push(pid) });
        expect(none).toEqual([]);
    });

    it('gives the child exactly the environment it was given, or the parent\'s when none is given', async () => {
        const printEnv = "process.stdout.write(JSON.stringify(Object.keys(process.env).sort()))";
        const only = await shellExecDetailed(NODE, ['-e', printEnv], { timeoutMs: 10000, maxStdoutBytes: 10000, env: { ONLY_THIS: '1' } });
        expect(JSON.parse(only.stdout)).toEqual(['ONLY_THIS']);
        process.env.REBOM_SHELL_EXEC_PROBE = 'x';
        try {
            const inherited = await shellExecDetailed(NODE, ['-e', printEnv], { timeoutMs: 10000, maxStdoutBytes: 10000 });
            expect(JSON.parse(inherited.stdout)).toContain('REBOM_SHELL_EXEC_PROBE');
        } finally {
            delete process.env.REBOM_SHELL_EXEC_PROBE;
        }
    });

    it('starts the child as the leader of its own group only when asked to', async () => {
        const groupOf = "process.stdout.write(require('child_process').execSync('ps -o pgid= -p ' + process.pid).toString().trim() + ' ' + process.pid)";
        const own = await shellExecDetailed(NODE, ['-e', groupOf], { timeoutMs: 10000, maxStdoutBytes: 100, killProcessGroup: true });
        const [ownGroup, ownPid] = own.stdout.split(' ').map(Number);
        expect(ownGroup).toBe(ownPid);
        const shared = await shellExecDetailed(NODE, ['-e', groupOf], { timeoutMs: 10000, maxStdoutBytes: 100 });
        const [sharedGroup, sharedPid] = shared.stdout.split(' ').map(Number);
        expect(sharedGroup).not.toBe(sharedPid);
    });

    it('reports the exit code and the signal of a process that ends by itself', async () => {
        const ended = await shellExecDetailed(...script('process.exitCode = 7'), { timeoutMs: 10000, maxStdoutBytes: 10 });
        expect(ended).toMatchObject({ code: 7, signal: null, timedOut: false });
        const signalled = await shellExecDetailed('sh', ['-c', 'kill -s TERM $$'], { timeoutMs: 10000, maxStdoutBytes: 10 });
        expect(signalled).toMatchObject({ code: null, signal: 'SIGTERM', timedOut: false });
    });
});

describe('killProcessGroup', () => {
    it('kills the group a detached process leads, and the process alone when it leads none', async () => {
        const leader = spawn('sleep', ['30'], { detached: true, stdio: 'ignore' });
        const plain = spawn('sleep', ['30'], { stdio: 'ignore' });
        try {
            expect(isAlive(leader.pid!)).toBe(true);
            killProcessGroup(leader.pid!);
            killProcessGroup(plain.pid!);
            expect(await eventually(() => leader.exitCode !== null || leader.signalCode !== null)).toBe(true);
            expect(await eventually(() => plain.exitCode !== null || plain.signalCode !== null)).toBe(true);
            expect(leader.signalCode).toBe('SIGKILL');
            expect(plain.signalCode).toBe('SIGKILL');
        } finally {
            leader.kill('SIGKILL');
            plain.kill('SIGKILL');
        }
    });

    it('ignores a group that is already gone', () => {
        expect(() => killProcessGroup(2_000_000_000)).not.toThrow();
    });
});
