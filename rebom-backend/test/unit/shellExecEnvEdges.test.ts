import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { shellExec, shellExecDetailed } from '../../src/utils';

/**
 * The default environment of the two helpers (TPF-256 design 3.2, row T-1198): a call without an
 * environment must run and give its child the base list. These cases assert on what the promise
 * resolves to, so a helper that refuses the call (the guard of childProcess.ts, which a default
 * holding rebom's own names reaches) fails an assertion and not only a thrown error.
 */
describe('the helpers without an environment still run their child', () => {
    const NODE = process.execPath;
    const PRINT = ['-e', 'process.stdout.write(JSON.stringify(process.env))'];
    const SETTINGS = ['POSTGRES_PASSWORD', 'ENCRYPTION_PASSWORD', 'REBOM_TEA_CALLER_TOKEN', 'PATH'] as const;
    const saved: Record<string, string | undefined> = {};

    beforeEach(() => {
        for (const name of SETTINGS) saved[name] = process.env[name];
        process.env.POSTGRES_PASSWORD = 'fake-postgres-password-for-test';
        process.env.ENCRYPTION_PASSWORD = 'fake-encryption-password-for-test';
        process.env.REBOM_TEA_CALLER_TOKEN = 'fake-caller-token-for-test';
        process.env.PATH = process.env.PATH ?? '/usr/bin:/bin';
    });

    afterEach(() => {
        for (const name of SETTINGS) {
            if (saved[name] === undefined) delete process.env[name];
            else process.env[name] = saved[name];
        }
    });

    it('shellExec resolves with the output of a child that sees the base list only', async () => {
        const seen = JSON.parse(await shellExec(NODE, PRINT));
        expect(Object.keys(seen).every((name) => ['PATH', 'LANG', 'LC_ALL'].includes(name))).toBe(true);
        expect(seen.PATH).toBe(process.env.PATH);
    });

    it('shellExec with a timeout and with a stderr level resolves the same way', async () => {
        await expect(shellExec(NODE, ['-e', 'process.stdout.write("ok")'], 20000)).resolves.toBe('ok');
        await expect(shellExec(NODE, ['-e', 'process.stdout.write("ok")'], undefined, 'info')).resolves.toBe('ok');
    });

    it('shellExecDetailed resolves with exit code 0 and the output of a child that sees the base list only', async () => {
        const opts = { timeoutMs: 20000, maxStdoutBytes: 100000 };
        for (const extra of [{}, { killProcessGroup: true }]) {
            const settled = shellExecDetailed(NODE, PRINT, { ...opts, ...extra });
            await expect(settled).resolves.toMatchObject({ code: 0, signal: null, timedOut: false, stdoutOverflow: false });
            const seen = JSON.parse((await settled).stdout);
            expect(Object.keys(seen).every((name) => ['PATH', 'LANG', 'LC_ALL'].includes(name))).toBe(true);
            expect(seen.PATH).toBe(process.env.PATH);
        }
    });
});
