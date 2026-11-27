import { afterAll, beforeEach, describe, expect, it, vi } from 'vitest';

// As childProcessGuard.test.ts: spawn is patched before any import runs and a marker is returned.
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
 * Edges of the guard (TPF-256 design 3.2, row T-1196): it looks at the names of the environment it
 * is given and at nothing else, whatever the values are, and a name is rebom's only by its prefix.
 */
describe('the guard of spawnChild', () => {
    beforeEach(() => {
        rec.calls.length = 0;
    });

    afterAll(() => {
        rec.cp.spawn = rec.original;
    });

    const refusedEnvs: [string, NodeJS.ProcessEnv][] = [
        ['an empty value', { PATH: '/bin', REBOM_X: '' }],
        ['an undefined value', { PATH: '/bin', ENCRYPTION_SALT: undefined }],
        ['the bare prefix REBOM_', { REBOM_: 'x' }],
        ['the bare prefix POSTGRES_', { POSTGRES_: 'x' }],
        ['the bare prefix ENCRYPTION_', { ENCRYPTION_: 'x' }],
        ['mixed case', { PATH: '/bin', Postgres_Port: '5432' }],
        ['the name last of many', { A: '1', B: '2', C: '3', REBOM_LAST: '4' }]
    ];

    for (const [label, env] of refusedEnvs) {
        it(`refuses ${label}`, () => {
            expect(holdsOwnName(env)).toBe(true);
            expect(() => spawnChild('tool', [], { env })).toThrow(CHILD_ENV_GUARD_MESSAGE);
            expect(rec.calls).toHaveLength(0);
        });
    }

    const acceptedEnvs: [string, NodeJS.ProcessEnv][] = [
        ['a name that holds a prefix after its start', { PATH: '/bin', MY_REBOM_X: '1', NOT_POSTGRES_X: '2', X_ENCRYPTION_Y: '3' }],
        ['the prefixes without their underscore', { REBOM: '1', POSTGRES: '2', ENCRYPTION: '3', POSTGRESQL_X: '4', REBOMX: '5' }],
        ['an empty environment', {}]
    ];

    for (const [label, env] of acceptedEnvs) {
        it(`starts the child for ${label}`, () => {
            expect(holdsOwnName(env)).toBe(false);
            expect(spawnChild('tool', ['a'], { env })).toBe(rec.returned);
            expect(rec.calls).toHaveLength(1);
            expect(rec.calls[0].options.env).toBe(env);
        });
    }

    it('looks at the environment of the options and not at rebom\'s own', () => {
        const saved = process.env.POSTGRES_PASSWORD;
        process.env.POSTGRES_PASSWORD = 'fake-postgres-password-for-test';
        try {
            expect(spawnChild('tool', [], { env: { PATH: '/bin' } })).toBe(rec.returned);
            expect(rec.calls).toHaveLength(1);
            expect(() => spawnChild('tool', [], { env: { REBOM_X: '1' } })).toThrow(CHILD_ENV_GUARD_MESSAGE);
            expect(rec.calls).toHaveLength(1);
        } finally {
            if (saved === undefined) delete process.env.POSTGRES_PASSWORD;
            else process.env.POSTGRES_PASSWORD = saved;
        }
    });
});
