import * as fs from 'fs';
import * as path from 'path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { logger } from '../../src/logger';
import { TEA_FETCH_NOT_HELD, TeaFetchStore, TeaHeldFile } from '../../src/services/tea/teaFetchStore';
import { TeaDiskBudget } from '../../src/services/tea/teaSlots';
import { cleanupDirs, tempDir } from './teaTestKit';

/**
 * The held fetches in memory (design 3.6.1): the identifier, the absolute expiry that use does not
 * extend, the removal that waits for running users, the budget given back, the sweep every ten
 * seconds, and what clearSync forgets.
 */

let clock = 1_000_000;
const now = () => clock;

beforeEach(() => {
    clock = 1_000_000;
});

afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    cleanupDirs();
});

function file(number: number): TeaHeldFile {
    return { number, path: `/x/${number}.bin`, sizeBytes: 5, sha256: 'ab'.repeat(32), documentClass: 'SBOM' };
}

/** A directory with a file in it, as a call directory is. */
function callDir(): string {
    const dir = path.join(tempDir('rebom-held-'), 'f-1');
    fs.mkdirSync(dir);
    fs.writeFileSync(path.join(dir, 'x'), 'x');
    return dir;
}

describe('registering and using a held fetch', () => {
    it('gives a random 32-character identifier, the files by number and an expiry holdSeconds from now', () => {
        const store = new TeaFetchStore(new TeaDiskBudget(1_000_000), now);
        const first = store.register('/dir/a', [file(2), file(1)], 600, 8192);
        const second = store.register('/dir/b', [], 10, 4096);
        expect(first.fetchId).toMatch(/^[0-9a-f]{32}$/);
        expect(second.fetchId).not.toBe(first.fetchId);
        expect(first.callDir).toBe('/dir/a');
        expect(first.heldBytes).toBe(8192);
        expect(first.expiresAt).toBe(1_000_000 + 600_000);
        expect(second.expiresAt).toBe(1_000_000 + 10_000);
        expect([...first.files.keys()]).toEqual([2, 1]);
        expect(store.size).toBe(2);
    });

    it('answers use with the function\'s result while the fetch is held, and counts the running users', async () => {
        const store = new TeaFetchStore(new TeaDiskBudget(1_000_000), now);
        const fetch = store.register(callDir(), [file(1)], 60, 4096);
        expect(await store.use(fetch.fetchId, async (held) => held.files.size)).toBe(1);
        expect(await store.use('0'.repeat(32), async () => 'never')).toBe(TEA_FETCH_NOT_HELD);
        await expect(store.use(fetch.fetchId, async () => { throw new Error('boom'); })).rejects.toThrow('boom');
        // the failed use released its place: a release now removes at once
        await store.release(fetch.fetchId);
        expect(store.size).toBe(0);
    });

    it('does not extend the expiry by use, and refuses at the instant of the expiry, not one millisecond before', async () => {
        const store = new TeaFetchStore(new TeaDiskBudget(1_000_000), now);
        const fetch = store.register(callDir(), [file(1)], 10, 4096);
        clock = fetch.expiresAt - 1;
        expect(await store.use(fetch.fetchId, async () => 'held')).toBe('held');
        clock = fetch.expiresAt;
        expect(await store.use(fetch.fetchId, async () => 'late')).toBe(TEA_FETCH_NOT_HELD);
        expect(store.size).toBe(0);
        expect(fs.existsSync(fetch.callDir)).toBe(false);
    });

    it('an expired fetch is removed by the use that finds it: the directory, the entry and the bytes', async () => {
        const budget = new TeaDiskBudget(1_000_000);
        expect(budget.reserve(8192)).toBe(true);
        const store = new TeaFetchStore(budget, now);
        const fetch = store.register(callDir(), [file(1)], 10, 8192);
        clock += 10_000;
        expect(await store.use(fetch.fetchId, async () => 'late')).toBe(TEA_FETCH_NOT_HELD);
        expect(budget.used).toBe(0);
        expect(fs.existsSync(fetch.callDir)).toBe(false);
    });
});

describe('releasing', () => {
    it('removes the directory, forgets the entry and gives the held bytes back, once', async () => {
        const budget = new TeaDiskBudget(1_000_000);
        expect(budget.reserve(12288)).toBe(true);
        const store = new TeaFetchStore(budget, now);
        const fetch = store.register(callDir(), [file(1)], 60, 8192);
        await store.release(fetch.fetchId);
        expect(store.size).toBe(0);
        expect(budget.used).toBe(4096);
        expect(fs.existsSync(fetch.callDir)).toBe(false);
        await store.release(fetch.fetchId);
        await store.release('f'.repeat(32));
        expect(budget.used).toBe(4096);
        expect(await store.use(fetch.fetchId, async () => 'x')).toBe(TEA_FETCH_NOT_HELD);
    });

    it('waits for the operations running on the fetch and removes it when the last one ends', async () => {
        const budget = new TeaDiskBudget(1_000_000);
        expect(budget.reserve(4096)).toBe(true);
        const store = new TeaFetchStore(budget, now);
        const fetch = store.register(callDir(), [file(1)], 60, 4096);
        let finish1!: () => void;
        let finish2!: () => void;
        const first = store.use(fetch.fetchId, () => new Promise<void>((resolve) => { finish1 = resolve; }));
        const second = store.use(fetch.fetchId, () => new Promise<void>((resolve) => { finish2 = resolve; }));
        await Promise.resolve();
        await store.release(fetch.fetchId);
        // two users are running: nothing is removed, and no new use is let in
        expect(store.size).toBe(1);
        expect(fs.existsSync(fetch.callDir)).toBe(true);
        expect(budget.used).toBe(4096);
        expect(await store.use(fetch.fetchId, async () => 'new')).toBe(TEA_FETCH_NOT_HELD);
        finish1();
        await first;
        expect(store.size).toBe(1);
        expect(fs.existsSync(fetch.callDir)).toBe(true);
        finish2();
        await second;
        expect(store.size).toBe(0);
        expect(fs.existsSync(fetch.callDir)).toBe(false);
        expect(budget.used).toBe(0);
    });

    it('a user that ends while the fetch is not released leaves it held', async () => {
        const store = new TeaFetchStore(new TeaDiskBudget(1_000_000), now);
        const fetch = store.register(callDir(), [file(1)], 60, 4096);
        await store.use(fetch.fetchId, async () => 1);
        expect(store.size).toBe(1);
        expect(fs.existsSync(fetch.callDir)).toBe(true);
    });

    it('logs and goes on when the directory cannot be removed, the bytes given back all the same', async () => {
        const errors = vi.spyOn(logger, 'error').mockImplementation(() => undefined as never);
        vi.spyOn(fs.promises, 'rm').mockRejectedValue(new Error('EBUSY'));
        const budget = new TeaDiskBudget(1_000_000);
        expect(budget.reserve(4096)).toBe(true);
        const store = new TeaFetchStore(budget, now);
        const fetch = store.register('/nowhere', [file(1)], 60, 4096);
        await store.release(fetch.fetchId);
        expect(store.size).toBe(0);
        expect(budget.used).toBe(0);
        expect(errors).toHaveBeenCalledTimes(1);
        expect(errors.mock.calls[0][1]).toBe('TEA: failed to remove a held fetch');
    });

    it('removes a directory that has files in it and one that is already gone, without logging', async () => {
        const errors = vi.spyOn(logger, 'error').mockImplementation(() => undefined as never);
        const store = new TeaFetchStore(new TeaDiskBudget(1_000_000), now);
        const gone = store.register(path.join(tempDir('rebom-gone-'), 'missing'), [], 60, 4096);
        await store.release(gone.fetchId);
        const full = store.register(callDir(), [], 60, 4096);
        await store.release(full.fetchId);
        expect(fs.existsSync(full.callDir)).toBe(false);
        expect(errors).not.toHaveBeenCalled();
    });
});

describe('the sweep', () => {
    // only the interval is faked: the files are really removed
    const settle = () => new Promise<void>((resolve) => setTimeout(resolve, 60));

    it('expire removes the expired fetches and keeps the others', async () => {
        const budget = new TeaDiskBudget(1_000_000);
        expect(budget.reserve(8192)).toBe(true);
        const store = new TeaFetchStore(budget, now);
        const early = store.register(callDir(), [], 10, 4096);
        const late = store.register(callDir(), [], 100, 4096);
        clock += 9_999;
        await store.expire();
        expect(store.size).toBe(2);
        clock += 1;
        await store.expire();
        expect(store.size).toBe(1);
        expect(fs.existsSync(early.callDir)).toBe(false);
        expect(fs.existsSync(late.callDir)).toBe(true);
        expect(budget.used).toBe(4096);
    });

    it('runs every 10 seconds once started, not before, never twice over, and stops when stopped', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        const store = new TeaFetchStore(new TeaDiskBudget(1_000_000), now);
        const fetch = store.register(callDir(), [], 1, 4096);
        clock += 5_000;
        store.start();
        store.start();
        expect(vi.getTimerCount()).toBe(1);
        vi.advanceTimersByTime(9_999);
        await settle();
        expect(store.size).toBe(1);
        vi.advanceTimersByTime(1);
        await settle();
        expect(store.size).toBe(0);
        expect(fs.existsSync(fetch.callDir)).toBe(false);
        store.stop();
        expect(vi.getTimerCount()).toBe(0);
        const again = store.register(callDir(), [], 1, 4096);
        clock += 5_000;
        vi.advanceTimersByTime(30_000);
        await settle();
        expect(store.size).toBe(1);
        expect(fs.existsSync(again.callDir)).toBe(true);
        // stopped means it can be started again
        store.start();
        expect(vi.getTimerCount()).toBe(1);
        store.stop();
        store.stop();
        expect(vi.getTimerCount()).toBe(0);
    });

    it('does not keep the process alive, and logs a sweep that failed instead of throwing', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        const store = new TeaFetchStore(new TeaDiskBudget(1_000_000), now);
        const interval = vi.spyOn(globalThis, 'setInterval');
        store.start();
        const timer = interval.mock.results[0].value as NodeJS.Timeout;
        expect(timer.hasRef()).toBe(false);
        store.stop();
        const errors = vi.spyOn(logger, 'error').mockImplementation(() => undefined as never);
        vi.spyOn(store, 'expire').mockRejectedValue(new Error('sweep'));
        store.start();
        vi.advanceTimersByTime(10_000);
        await settle();
        expect(errors).toHaveBeenCalledTimes(1);
        expect(errors.mock.calls[0][1]).toBe('TEA: expiry of held fetches failed');
        store.stop();
    });
});

describe('clearSync', () => {
    it('stops the sweep, gives every fetch\'s bytes back, removes every directory and forgets every entry', async () => {
        vi.useFakeTimers();
        const budget = new TeaDiskBudget(1_000_000);
        expect(budget.reserve(12288)).toBe(true);
        const store = new TeaFetchStore(budget, now);
        const one = store.register(callDir(), [], 60, 4096);
        const two = store.register(callDir(), [], 60, 8192);
        store.start();
        store.clearSync();
        expect(vi.getTimerCount()).toBe(0);
        expect(store.size).toBe(0);
        expect(budget.used).toBe(0);
        expect(fs.existsSync(one.callDir)).toBe(false);
        expect(fs.existsSync(two.callDir)).toBe(false);
        expect(await store.use(one.fetchId, async () => 'x')).toBe(TEA_FETCH_NOT_HELD);
    });

    it('goes on past a directory that cannot be removed, and past one that is gone', () => {
        if (process.getuid && process.getuid() === 0) return;
        const budget = new TeaDiskBudget(1_000_000);
        expect(budget.reserve(12288)).toBe(true);
        const store = new TeaFetchStore(budget, now);
        const missing = store.register(path.join(tempDir('rebom-gone-'), 'nope'), [], 60, 4096);
        // a directory that sits in one that cannot be written to cannot be removed
        const locked = path.join(tempDir('rebom-locked-'), 'parent');
        fs.mkdirSync(path.join(locked, 'child'), { recursive: true });
        const stuck = store.register(path.join(locked, 'child'), [], 60, 4096);
        const present = store.register(callDir(), [], 60, 4096);
        fs.chmodSync(locked, 0o500);
        try {
            expect(() => store.clearSync()).not.toThrow();
        } finally {
            fs.chmodSync(locked, 0o700);
        }
        expect(store.size).toBe(0);
        expect(budget.used).toBe(0);
        expect(fs.existsSync(stuck.callDir)).toBe(true);
        expect(fs.existsSync(missing.callDir)).toBe(false);
        expect(fs.existsSync(present.callDir)).toBe(false);
    });
});
