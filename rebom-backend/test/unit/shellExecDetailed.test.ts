import { describe, it, expect } from 'vitest';
import { shellExecDetailed, shellExec } from '../../src/utils';

// Real processes: the node binary running this suite, with an inline script.
const NODE = process.execPath;
const OPTS = { timeoutMs: 10000, maxStdoutBytes: 1024 * 1024 };

function isAlive(pid: number): boolean {
    try {
        process.kill(pid, 0);
        return true;
    } catch {
        return false;
    }
}

describe('shellExecDetailed', () => {
    it('H-1 returns stdout and stderr whole, stdout not trimmed', async () => {
        const r = await shellExecDetailed(NODE, ['-e', 'process.stdout.write("out\\n\\n"); process.stderr.write("err line\\nsecond\\n")'], OPTS);
        expect(r.code).toBe(0);
        expect(r.signal).toBeNull();
        expect(r.stdout).toBe('out\n\n');
        expect(r.stderr).toBe('err line\nsecond\n');
        expect(r.timedOut).toBe(false);
        expect(r.stdoutOverflow).toBe(false);
        expect(r.spawnError).toBeUndefined();
    });

    it('H-2 resolves with a non-zero exit code instead of rejecting', async () => {
        const r = await shellExecDetailed(NODE, ['-e', 'process.stderr.write("refused\\n"); process.exit(3)'], OPTS);
        expect(r.code).toBe(3);
        expect(r.stderr).toBe('refused\n');
    });

    it('H-3 resolves with spawnError ENOENT for a command that does not exist', async () => {
        const r = await shellExecDetailed('rebom-no-such-command-4c1d', [], OPTS);
        expect(r.spawnError?.code).toBe('ENOENT');
        expect(r.code).toBeNull();
        expect(r.stdout).toBe('');
    });

    it('H-4 kills the process at the timeout and resolves after it closed', async () => {
        const started = Date.now();
        const r = await shellExecDetailed(NODE,
            ['-e', 'process.stdout.write(String(process.pid)); setTimeout(() => {}, 10000)'],
            { timeoutMs: 200, maxStdoutBytes: 1000 });
        expect(Date.now() - started).toBeLessThan(2000);
        expect(r.timedOut).toBe(true);
        expect(r.signal).toBe('SIGKILL');
        expect(r.code).toBeNull();
        const pid = Number(r.stdout);
        expect(pid).toBeGreaterThan(0);
        expect(isAlive(pid)).toBe(false);
    });

    it('H-5 sets stdoutOverflow and kills the process past maxStdoutBytes', async () => {
        const r = await shellExecDetailed(NODE,
            ['-e', 'process.stdout.write("x".repeat(1024 * 1024)); setTimeout(() => {}, 10000)'],
            { timeoutMs: 10000, maxStdoutBytes: 1000 });
        expect(r.stdoutOverflow).toBe(true);
        expect(r.timedOut).toBe(false);
        expect(Buffer.byteLength(r.stdout)).toBeLessThanOrEqual(1000);
    });

    it('H-6 keeps a multi-byte character written across two chunks intact', async () => {
        // U+20AC is e2 82 ac in UTF-8: the first byte goes alone, the rest 100 ms later
        const script = 'process.stdout.write(Buffer.from([0x61, 0xe2])); '
            + 'setTimeout(() => process.stdout.write(Buffer.from([0x82, 0xac, 0x62])), 100)';
        const r = await shellExecDetailed(NODE, ['-e', script], OPTS);
        expect(r.code).toBe(0);
        expect(r.stdout).toBe('a\u20acb');
    });
});

describe('shellExec (H-7: behaviour unchanged)', () => {
    it('resolves with stdout minus one trailing newline on exit 0', async () => {
        await expect(shellExec(NODE, ['-e', 'process.stdout.write("a\\n")'])).resolves.toBe('a');
    });

    it('rejects with stdout on a non-zero exit', async () => {
        await expect(shellExec(NODE, ['-e', 'process.stdout.write("partial"); process.exit(1)'])).rejects.toBe('partial');
    });
});
