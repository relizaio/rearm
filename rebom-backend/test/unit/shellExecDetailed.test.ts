import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { shellExecDetailed, shellExec } from '../../src/utils';
import { logger } from '../../src/logger';

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
        // 3 s timeout: an emulated node start is about 1 s (SCORE-7 design round 3, ADR-9)
        const started = Date.now();
        const r = await shellExecDetailed(NODE,
            ['-e', 'process.stdout.write(String(process.pid)); setTimeout(() => {}, 60000)'],
            { timeoutMs: 3000, maxStdoutBytes: 1000 });
        expect(Date.now() - started).toBeLessThan(10000);
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

    it('H-8 keeps only the first 64 KiB of stderr by default, and the first maxStderrBytes when given', async () => {
        // 102400 bytes of a repeating digit pattern, so a prefix is distinguishable from any other slice
        const script = 'process.stderr.write("0123456789".repeat(10240))';
        const written = '0123456789'.repeat(10240);

        const byDefault = await shellExecDetailed(NODE, ['-e', script], OPTS);
        expect(byDefault.code).toBe(0);
        expect(Buffer.byteLength(byDefault.stderr)).toBe(65536);
        expect(byDefault.stderr).toBe(written.substring(0, 65536));

        const capped = await shellExecDetailed(NODE, ['-e', script], { ...OPTS, maxStderrBytes: 10 });
        expect(capped.code).toBe(0);
        expect(capped.stderr).toBe('0123456789');
    });

    it('H-9 keeps stdout of exactly maxStdoutBytes whole and trips the overflow one byte later', async () => {
        const exact = await shellExecDetailed(NODE, ['-e', 'process.stdout.write("x".repeat(1000))'],
            { timeoutMs: 10000, maxStdoutBytes: 1000 });
        expect(exact.stdoutOverflow).toBe(false);
        expect(exact.code).toBe(0);
        expect(exact.stdout).toBe('x'.repeat(1000));

        const over = await shellExecDetailed(NODE, ['-e', 'process.stdout.write("x".repeat(1001))'],
            { timeoutMs: 10000, maxStdoutBytes: 1000 });
        expect(over.stdoutOverflow).toBe(true);
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

// SCORE-68 T-6 (ADR-4): shellExec logs each stderr chunk at the level its caller names; the default
// keeps the historical error line. 'exit' may fire before the last stderr chunk is read, so the
// logger is awaited with vi.waitFor.
describe('shellExec logs stderr at the level given (SCORE-68 ADR-4)', () => {
    const ERR_LINE = 'process.stdout.write("out"); process.stderr.write("err line\\n")';
    let spies: Record<'info' | 'warn' | 'error', ReturnType<typeof vi.spyOn>>;

    beforeEach(() => {
        spies = {
            info: vi.spyOn(logger, 'info').mockImplementation(() => undefined as any),
            warn: vi.spyOn(logger, 'warn').mockImplementation(() => undefined as any),
            error: vi.spyOn(logger, 'error').mockImplementation(() => undefined as any)
        };
    });

    afterEach(() => {
        vi.restoreAllMocks();
    });

    /** The string messages `spy` saw. */
    function messages(spy: ReturnType<typeof vi.spyOn>): string[] {
        return spy.mock.calls.map((call: any[]) => call[0]).filter((m: any) => typeof m === 'string');
    }

    function sawStderr(spy: ReturnType<typeof vi.spyOn>, prefix: string): boolean {
        return messages(spy).some(m => m.includes(prefix) && m.includes('err line'));
    }

    it("'info': resolves with stdout, the chunk at info as shell command stderr, nothing at error", async () => {
        await expect(shellExec(NODE, ['-e', ERR_LINE], undefined, 'info')).resolves.toBe('out');

        await vi.waitFor(() => expect(sawStderr(spies.info, 'shell command stderr: ')).toBe(true));
        expect(spies.error).not.toHaveBeenCalled();
        expect(spies.warn).not.toHaveBeenCalled();
    });

    it('no level: the chunk at error as shell command error, nothing at info', async () => {
        await expect(shellExec(NODE, ['-e', ERR_LINE])).resolves.toBe('out');

        await vi.waitFor(() => expect(sawStderr(spies.error, 'shell command error: ')).toBe(true));
        expect(spies.info).not.toHaveBeenCalled();
    });

    it("'warn': the chunk at warn as shell command stderr", async () => {
        await expect(shellExec(NODE, ['-e', ERR_LINE], undefined, 'warn')).resolves.toBe('out');

        await vi.waitFor(() => expect(sawStderr(spies.warn, 'shell command stderr: ')).toBe(true));
        expect(spies.error).not.toHaveBeenCalled();
        expect(spies.info).not.toHaveBeenCalled();
    });

    it("'info' and exit 3: rejects with stdout, the chunk at info, the exit line still at error", async () => {
        await expect(shellExec(NODE, ['-e', `${ERR_LINE}; process.exit(3)`], undefined, 'info')).rejects.toBe('out');

        await vi.waitFor(() => expect(sawStderr(spies.info, 'shell command stderr: ')).toBe(true));
        expect(messages(spies.error)).toContain('shell process exited with code 3');
        expect(messages(spies.error).some(m => m.includes('err line'))).toBe(false);
    });
});
