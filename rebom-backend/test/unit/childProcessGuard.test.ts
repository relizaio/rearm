import { afterAll, beforeEach, describe, expect, it, vi } from 'vitest';

// childProcess.ts captures spawn at load, so it is patched before any import runs: every spawn is
// recorded and a marker object is returned instead of a process.
const rec = vi.hoisted(() => {
    const cp = (process as any).getBuiltinModule('node:child_process');
    const original = cp.spawn;
    const returned = { marker: 'spawned' };
    const calls: { cmd: string, args: string[], options: any }[] = [];
    cp.spawn = (cmd: string, args: string[], options: any) => {
        calls.push({ cmd, args, options });
        return returned;
    };
    return { cp, original, calls, returned };
});

import { CHILD_ENV_GUARD_MESSAGE, spawnChild } from '../../src/childProcess';
import { holdsOwnName } from '../../src/childEnv';

/**
 * The one wrapper around spawn and its guard (TPF-256 design 3.2; row T-1196).
 */
describe('T-1196 spawnChild and its guard', () => {
    beforeEach(() => {
        rec.calls.length = 0;
    });

    afterAll(() => {
        rec.cp.spawn = rec.original;
    });

    it('a clean environment: spawn called once with the same command, arguments and options, its result returned', () => {
        const args = ['a', 'b'];
        const options = { stdio: ['ignore', 'pipe', 'pipe'] as ['ignore', 'pipe', 'pipe'], env: { PATH: '/bin', TZ: 'UTC' }, detached: true };
        const child = spawnChild('tool', args, options);
        expect(child).toBe(rec.returned);
        expect(rec.calls).toHaveLength(1);
        expect(rec.calls[0].cmd).toBe('tool');
        expect(rec.calls[0].args).toBe(args);
        expect(rec.calls[0].options).toBe(options);
        expect(rec.calls[0].options).toStrictEqual({ stdio: ['ignore', 'pipe', 'pipe'], env: { PATH: '/bin', TZ: 'UTC' }, detached: true });
    });

    const refused: [string, any][] = [
        ['POSTGRES_HOST', { env: { PATH: '/bin', POSTGRES_HOST: 'db-host-value' } }],
        ['encryption_salt', { env: { PATH: '/bin', encryption_salt: 'salt-value' } }],
        ['REBOM_X', { env: { PATH: '/bin', REBOM_X: 'rebom-value' } }],
        ['no env', { stdio: 'pipe' }],
        ['env null', { env: null }],
        ['no options', undefined]
    ];

    for (const [label, options] of refused) {
        it(`refused before anything starts: ${label}`, () => {
            let thrown: unknown;
            try {
                spawnChild('tool', ['a'], options);
            } catch (e) {
                thrown = e;
            }
            expect(thrown).toBeInstanceOf(Error);
            expect((thrown as Error).message).toBe(CHILD_ENV_GUARD_MESSAGE);
            expect(rec.calls).toHaveLength(0);
            const text = String((thrown as Error).message) + String((thrown as Error).stack);
            for (const fragment of ['POSTGRES_HOST', 'db-host-value', 'encryption_salt', 'salt-value', 'REBOM_X', 'rebom-value']) {
                expect(text).not.toContain(fragment);
            }
        });
    }

    it('the message is the constant sentence', () => {
        expect(CHILD_ENV_GUARD_MESSAGE).toBe('child process refused: its environment is missing or holds a setting of rebom');
    });

    it('holdsOwnName: the three prefixes on the upper-cased name, a prefix and not a substring', () => {
        expect(holdsOwnName({})).toBe(false);
        expect(holdsOwnName({ PATH: '/bin', LANG: 'C', REARM_BEARAPIKEY: 'k', MY_POSTGRES_X: 'x', XREBOM_Y: 'y' })).toBe(false);
        for (const name of ['POSTGRES_PASSWORD', 'postgres_host', 'ENCRYPTION_PASSWORD', 'Encryption_Old_Salt', 'REBOM_TEA_CALLER_TOKEN', 'rebom_x']) {
            expect(holdsOwnName({ PATH: '/bin', [name]: 'v' }), name).toBe(true);
        }
        expect(holdsOwnName({ POSTGRES_PASSWORD: undefined })).toBe(true);
    });
});
