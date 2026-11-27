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

import { shellExec } from '../../src/utils';

/**
 * shellExec takes an optional environment as its last parameter (TPF-254 design 3.1; row T-1006):
 * added to the spawn options only when given, the complete environment of the child; without it
 * the options and the inherited environment are what they were.
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

    it('T-1006 the spawn options: {} without a timeout, { timeout } with one, {} for (undefined, info), { env } with the same object', async () => {
        const env = { ONLY_THIS: '1' };
        await shellExec(NODE, ['-e', '0']);
        await shellExec(NODE, ['-e', '0'], 5000);
        await shellExec(NODE, ['-e', '0'], undefined, 'info');
        await shellExec(NODE, ['-e', '0'], undefined, 'error', env);
        await shellExec(NODE, ['-e', '0'], 5000, 'warn', env);

        expect(rec.calls).toHaveLength(5);
        expect(rec.calls[0].options).toStrictEqual({});
        expect(rec.calls[1].options).toStrictEqual({ timeout: 5000 });
        expect(rec.calls[2].options).toStrictEqual({});
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

    it('T-1006 without an env the real child inherits rebom\'s environment, the planted variable included', async () => {
        const names: string[] = JSON.parse(await shellExec(NODE, printNames));
        expect(names).toContain(PLANTED);
        expect(names).not.toContain('ONLY_THIS');
    });
});
