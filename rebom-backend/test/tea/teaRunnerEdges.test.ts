import * as fs from 'fs';
import * as path from 'path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { logger } from '../../src/logger';
import {
    killAllTeaRuns, makeCallDir, removeCallDir, runningTeaGroups, runTeaCli, setTeaWorkDir, TEA_STDERR_KEPT_BYTES, teaWorkDir, withCallDir
} from '../../src/services/tea/teaCliRunner';
import { cleanupDirs, isAlive, sleep, Stub, tempDir } from './teaTestKit';

/**
 * The runner of the CLI and the per-call directory (design 3.5): what is kept of standard error,
 * the directory that is made, removed on every path or handed on, and the registry of the
 * running process groups the shut-down kills.
 */

let stub: Stub | undefined;

afterEach(() => {
    stub?.restore();
    stub = undefined;
    setTeaWorkDir(null);
    vi.restoreAllMocks();
    cleanupDirs();
});

function workDir(): string {
    const dir = tempDir('rebom-work-');
    setTeaWorkDir(dir);
    return dir;
}

describe('the run', () => {
    it('keeps 262144 bytes of standard error and reports the rest as an overflow, exactly at the bound', async () => {
        stub = new Stub();
        expect(TEA_STDERR_KEPT_BYTES).toBe(262144);
        stub.behave({ stderr: 'e'.repeat(262144) });
        const exact = await runTeaCli(['x'], { env: { PATH: process.env.PATH }, timeoutMs: 20000, maxStdoutBytes: 1000 });
        expect(exact.stderr.length).toBe(262144);
        expect(exact.stderrOverflow).toBe(false);
        stub.behave({ stderr: 'e'.repeat(262145) });
        const over = await runTeaCli(['x'], { env: { PATH: process.env.PATH }, timeoutMs: 20000, maxStdoutBytes: 1000 });
        expect(over.stderr.length).toBe(262144);
        expect(over.stderrOverflow).toBe(true);
    });

    it('registers the group of a run while it lasts and forgets it when it ends', async () => {
        stub = new Stub();
        stub.behave({ sleep: 1 });
        expect(runningTeaGroups()).toEqual([]);
        const running = runTeaCli(['x'], { env: { PATH: process.env.PATH }, timeoutMs: 20000, maxStdoutBytes: 1000 });
        await sleep(300);
        const groups = runningTeaGroups();
        expect(groups).toHaveLength(1);
        expect(isAlive(groups[0])).toBe(true);
        const result = await running;
        expect(result.pid).toBe(groups[0]);
        expect(runningTeaGroups()).toEqual([]);
    });

    it('leads its own process group, so the whole group can be killed', async () => {
        stub = new Stub();
        stub.behave({ sleep: 1 });
        const running = runTeaCli(['x'], { env: { PATH: process.env.PATH }, timeoutMs: 20000, maxStdoutBytes: 1000 });
        await sleep(300);
        const [pid] = runningTeaGroups();
        expect(() => process.kill(-pid, 0)).not.toThrow();
        await running;
    });

    it('a run that never started leaves nothing registered', async () => {
        const saved = process.env.PATH;
        process.env.PATH = tempDir('rebom-empty-path-');
        try {
            const result = await runTeaCli(['x'], { env: { PATH: process.env.PATH }, timeoutMs: 1000, maxStdoutBytes: 10 });
            expect(result.spawnError?.code).toBe('ENOENT');
            expect(runningTeaGroups()).toEqual([]);
        } finally {
            process.env.PATH = saved;
        }
    });

    it('killAllTeaRuns kills every running group at once and empties the registry before the runs end', async () => {
        stub = new Stub();
        stub.behave({ sleep: 30, child: true });
        const one = runTeaCli(['x'], { env: { PATH: process.env.PATH }, timeoutMs: 60000, maxStdoutBytes: 1000 });
        const two = runTeaCli(['y'], { env: { PATH: process.env.PATH }, timeoutMs: 60000, maxStdoutBytes: 1000 });
        await sleep(600);
        const groups = runningTeaGroups();
        expect(groups).toHaveLength(2);
        const child = stub.readPid('child.pid');
        expect(isAlive(child)).toBe(true);
        killAllTeaRuns();
        expect(runningTeaGroups()).toEqual([]);
        const results = await Promise.all([one, two]);
        for (const result of results) expect(result).toMatchObject({ code: null, signal: 'SIGKILL', timedOut: false });
        for (const pid of groups) expect(isAlive(pid)).toBe(false);
        for (let i = 0; i < 40 && isAlive(child); i++) await sleep(50);
        expect(isAlive(child)).toBe(false);
        expect(() => killAllTeaRuns()).not.toThrow();
    });
});

describe('the per-call directory', () => {
    it('is made under the work directory with the prefix f-, private to the user', async () => {
        const root = workDir();
        expect(teaWorkDir()).toBe(root);
        const dir = await makeCallDir();
        expect(path.dirname(dir)).toBe(root);
        expect(path.basename(dir)).toMatch(/^f-[A-Za-z0-9]{6}$/);
        expect(fs.statSync(dir).isDirectory()).toBe(true);
        expect(fs.statSync(dir).mode & 0o777).toBe(0o700);
        expect(await makeCallDir()).not.toBe(dir);
    });

    it('is not made while no work directory is configured', async () => {
        setTeaWorkDir(null);
        expect(teaWorkDir()).toBeNull();
        await expect(makeCallDir()).rejects.toThrow('TEA scratch directory is not configured');
        await expect(withCallDir(async () => ({ value: 1, keep: false }))).rejects.toThrow('TEA scratch directory is not configured');
    });

    it('removing one that is gone is no error and no log; removing one that cannot be removed is logged, not thrown', async () => {
        const errors = vi.spyOn(logger, 'error').mockImplementation(() => undefined as never);
        const root = workDir();
        await removeCallDir(path.join(root, 'f-gone'));
        expect(errors).not.toHaveBeenCalled();
        const full = await makeCallDir();
        fs.writeFileSync(path.join(full, 'x'), 'x');
        await removeCallDir(full);
        expect(fs.existsSync(full)).toBe(false);
        vi.spyOn(fs.promises, 'rm').mockRejectedValue(new Error('EBUSY'));
        await expect(removeCallDir(path.join(root, 'f-stuck'))).resolves.toBeUndefined();
        expect(errors).toHaveBeenCalledTimes(1);
        expect(errors.mock.calls[0][1]).toBe('TEA: failed to remove a per-call directory');
    });

    it('is removed after the function, whatever it returns, and also when it throws', async () => {
        workDir();
        let seen = '';
        const value = await withCallDir(async (dir) => {
            seen = dir;
            fs.writeFileSync(path.join(dir, 'x'), 'x');
            return { value: 'result', keep: false };
        });
        expect(value).toBe('result');
        expect(fs.existsSync(seen)).toBe(false);
        let thrown = '';
        await expect(withCallDir(async (dir) => {
            thrown = dir;
            throw new Error('inside');
        })).rejects.toThrow('inside');
        expect(fs.existsSync(thrown)).toBe(false);
    });

    it('is handed on, and left, when the function says keep', async () => {
        workDir();
        let seen = '';
        const value = await withCallDir(async (dir) => {
            seen = dir;
            return { value: 7, keep: true };
        });
        expect(value).toBe(7);
        expect(fs.existsSync(seen)).toBe(true);
    });
});
