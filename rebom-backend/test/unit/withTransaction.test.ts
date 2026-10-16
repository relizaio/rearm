import { describe, it, expect, vi, beforeEach } from 'vitest';

// SCORE-46 T-14: withTransaction runs BEGIN, the callback, COMMIT on one pool
// client, rolls back on any failure, always releases, and hands the callback
// a client whose statements fail exactly as runQuery's do.
const statements: string[] = [];
const release = vi.fn();
let failOn: ((text: string) => any) | null = null;

const fakeClient = {
    query: vi.fn(async (text: string, _params?: any[]) => {
        statements.push(text);
        const failure = failOn ? failOn(text) : null;
        if (failure) throw failure;
        return { rows: [] };
    }),
    release
};

vi.mock('../../src/db/pool', () => ({ pool: { connect: vi.fn(async () => fakeClient) } }));
vi.mock('../../src/logger', () => ({
    logger: { info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn() }
}));

import { withTransaction, runQuery } from '../../src/db/query';

beforeEach(() => {
    statements.length = 0;
    release.mockClear();
    failOn = null;
});

describe('withTransaction', () => {
    it('T-14 a: commits and answers the callback result', async () => {
        const result = await withTransaction(async client => {
            await client.query('SELECT 1', []);
            return 42;
        });
        expect(result).toBe(42);
        expect(statements).toEqual(['BEGIN', 'SELECT 1', 'COMMIT']);
        expect(release).toHaveBeenCalledTimes(1);
    });

    it('T-14 b: rolls back and rethrows the same error when the callback throws', async () => {
        const boom = new Error('boom');
        await expect(withTransaction(async client => {
            await client.query('SELECT 1', []);
            throw boom;
        })).rejects.toBe(boom);
        expect(statements).toEqual(['BEGIN', 'SELECT 1', 'ROLLBACK']);
        expect(release).toHaveBeenCalledTimes(1);
    });

    it('T-14 c: a failing statement reaches the callback mapped as runQuery maps it', async () => {
        failOn = text => text === 'INSERT x' ? { code: '23505', detail: 'Key (x)=(1) already exists.' } : null;
        let seen: any;
        await expect(withTransaction(async client => {
            try {
                await client.query('INSERT x', []);
            } catch (e) {
                seen = e;
                throw e;
            }
        })).rejects.toThrow('Duplicate entry: Key (x)=(1) already exists.');
        expect(seen).toBeInstanceOf(Error);
        expect(seen.message).toBe('Duplicate entry: Key (x)=(1) already exists.');
        expect(statements).toEqual(['BEGIN', 'INSERT x', 'ROLLBACK']);
        expect(release).toHaveBeenCalledTimes(1);
    });

    it('T-14 d: a failing COMMIT rolls back, rejects and releases', async () => {
        failOn = text => text === 'COMMIT' ? new Error('commit failed') : null;
        await expect(withTransaction(async client => {
            await client.query('SELECT 1', []);
        })).rejects.toThrow('commit failed');
        expect(statements).toEqual(['BEGIN', 'SELECT 1', 'COMMIT', 'ROLLBACK']);
        expect(release).toHaveBeenCalledTimes(1);
    });

    it('T-14 e: runQuery still maps a unique violation the same way', async () => {
        failOn = () => ({ code: '23505', detail: 'd' });
        await expect(runQuery('SELECT 1', [])).rejects.toThrow('Duplicate entry: d');
        expect(release).toHaveBeenCalledTimes(1);
    });
});
