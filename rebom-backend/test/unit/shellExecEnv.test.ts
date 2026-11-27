import { describe, it, expect, vi, beforeEach, afterEach, afterAll } from 'vitest';

// utils.ts captures spawn at load, so it is patched before any import runs: every spawn is recorded
// and then runs for real.
const rec = vi.hoisted(() => {
    const cp = (process as any).getBuiltinModule('node:child_process');
    const original = cp.spawn;
    const calls: { cmd: string, args: string[], options: any }[] = [];
    cp.spawn = (cmd: string, args: string[], options: any) => {
        calls.push({ cmd, args, options });
        return original(cmd, args, options);
    };
    return { cp, original, calls };
});

import { shellExec, shellExecDetailed } from '../../src/utils';
import { buildChildEnv, ChildTool } from '../../src/childEnv';

/**
 * shellExec takes an optional environment as its last parameter (TPF-254 design 3.1; row T-1006),
 * the complete environment of the child. Without it the child gets the base list of childEnv.ts and
 * never rebom's environment (TPF-256 design 3.2; row T-1198); shellExecDetailed the same.
 */
describe('shellExec with an optional environment', () => {
    const NODE = process.execPath;
    const PLANTED = 'TPF254_PLANTED';
    const printNames = ['-e', 'process.stdout.write(JSON.stringify(Object.keys(process.env)))'];
    let saved: string | undefined;

    beforeEach(() => {
        rec.calls.length = 0;
        saved = process.env[PLANTED];
        process.env[PLANTED] = 'planted';
    });

    afterEach(() => {
        if (saved === undefined) delete process.env[PLANTED];
        else process.env[PLANTED] = saved;
    });

    afterAll(() => {
        rec.cp.spawn = rec.original;
    });

    it('T-1006 T-1198 the spawn options: { env } of the base list without a timeout, { timeout, env } with one, { env } for (undefined, info), a given env as the same object', async () => {
        const env = { ONLY_THIS: '1' };
        await shellExec(NODE, ['-e', '0']);
        await shellExec(NODE, ['-e', '0'], 5000);
        await shellExec(NODE, ['-e', '0'], undefined, 'info');
        await shellExec(NODE, ['-e', '0'], undefined, 'error', env);
        await shellExec(NODE, ['-e', '0'], 5000, 'warn', env);

        expect(rec.calls).toHaveLength(5);
        const base = buildChildEnv(ChildTool.BASE);
        expect(rec.calls[0].options).toStrictEqual({ env: base });
        expect(rec.calls[1].options).toStrictEqual({ timeout: 5000, env: base });
        expect(rec.calls[2].options).toStrictEqual({ env: base });
        expect(rec.calls[3].options).toStrictEqual({ env });
        expect(rec.calls[3].options.env).toBe(env);
        expect(rec.calls[4].options).toStrictEqual({ timeout: 5000, env });
        expect(rec.calls[4].options.env).toBe(env);
    });

    it('T-1006 a real child given an env sees exactly that, not a variable planted in process.env', async () => {
        const names: string[] = JSON.parse(await shellExec(NODE, printNames, undefined, 'error', { ONLY_THIS: '1' }));
        expect(names).toContain('ONLY_THIS');
        expect(names).not.toContain(PLANTED);
        expect(names).not.toContain('PATH');
    });

    it('T-1006 T-1198 without an env the real child gets the base list: neither the planted variable nor POSTGRES_PASSWORD', async () => {
        const savedPassword = process.env.POSTGRES_PASSWORD;
        process.env.POSTGRES_PASSWORD = 'tpf256-password';
        try {
            const names: string[] = JSON.parse(await shellExec(NODE, printNames));
            expect(names).not.toContain(PLANTED);
            expect(names).not.toContain('POSTGRES_PASSWORD');
            expect(names).not.toContain('ONLY_THIS');
            expect([...names].sort()).toStrictEqual(Object.keys(buildChildEnv(ChildTool.BASE)).sort());
        } finally {
            if (savedPassword === undefined) delete process.env.POSTGRES_PASSWORD;
            else process.env.POSTGRES_PASSWORD = savedPassword;
        }
    });

    it('T-1198 shellExecDetailed without an env: { stdio, env } of the base list, with detached when the group is killed', async () => {
        const opts = { timeoutMs: 10000, maxStdoutBytes: 10000 };
        const plain = await shellExecDetailed(NODE, printNames, opts);
        const grouped = await shellExecDetailed(NODE, printNames, { ...opts, killProcessGroup: true });
        expect(rec.calls).toHaveLength(2);
        const base = buildChildEnv(ChildTool.BASE);
        expect(rec.calls[0].options).toStrictEqual({ stdio: ['ignore', 'pipe', 'pipe'], env: base });
        expect(rec.calls[1].options).toStrictEqual({ stdio: ['ignore', 'pipe', 'pipe'], env: base, detached: true });
        for (const result of [plain, grouped]) {
            const names: string[] = JSON.parse(result.stdout);
            expect(names).not.toContain(PLANTED);
            expect([...names].sort()).toStrictEqual(Object.keys(base).sort());
        }
    });
});
