import { afterEach, describe, expect, it, vi } from 'vitest';
import { heldBytesOf, TEA_FILE_FLOOR_BYTES, TeaDiskBudget, TeaSlotGrant, TeaSlotPool } from '../../src/services/tea/teaSlots';

/**
 * The slot pool and the disk budget (design 3.7) on their own: the queue in order, the bound, the
 * wait, the slot that passes straight to the next caller, a release that counts once, the drain.
 */

afterEach(() => {
    vi.useRealTimers();
});

function granted(grant: TeaSlotGrant): () => void {
    if (!grant.granted) throw new Error(`refused: ${grant.refusal}`);
    return grant.release;
}

describe('the slot pool', () => {
    it('grants up to its limit at once, and counts what runs and what waits', async () => {
        const pool = new TeaSlotPool(2, 5, 1000);
        expect(pool.limit).toBe(2);
        expect(pool.maxQueued).toBe(5);
        expect(pool.waitMs).toBe(1000);
        const first = granted(await pool.acquire());
        granted(await pool.acquire());
        expect(pool.running).toBe(2);
        expect(pool.queued).toBe(0);
        const third = pool.acquire();
        const fourth = pool.acquire();
        expect(pool.running).toBe(2);
        expect(pool.queued).toBe(2);
        first();
        const release3 = granted(await third);
        expect(pool.running).toBe(2);
        expect(pool.queued).toBe(1);
        release3();
        granted(await fourth);
        expect(pool.running).toBe(2);
        expect(pool.queued).toBe(0);
    });

    it('serves the queue in the order it was formed, and frees the count only when nobody waits', async () => {
        const pool = new TeaSlotPool(1, 5, 5000);
        const first = granted(await pool.acquire());
        const order: number[] = [];
        const waiting = [1, 2, 3].map((n) => pool.acquire().then((grant) => {
            order.push(n);
            return granted(grant);
        }));
        await Promise.resolve();
        first();
        const release1 = await waiting[0];
        expect(pool.running).toBe(1);
        release1();
        const release2 = await waiting[1];
        release2();
        const release3 = await waiting[2];
        expect(order).toEqual([1, 2, 3]);
        expect(pool.running).toBe(1);
        release3();
        expect(pool.running).toBe(0);
        expect(pool.queued).toBe(0);
    });

    it('a release frees its slot once, however often it is called', async () => {
        const pool = new TeaSlotPool(1, 5, 5000);
        const first = granted(await pool.acquire());
        const second = pool.acquire();
        first();
        first();
        first();
        const release = granted(await second);
        expect(pool.running).toBe(1);
        const third = pool.acquire();
        await Promise.resolve();
        expect(pool.queued).toBe(1);
        release();
        release();
        granted(await third);
        expect(pool.running).toBe(1);
        // with nobody waiting a second call of the same release does not free a second slot
        const alone = new TeaSlotPool(2, 0, 100);
        const a = granted(await alone.acquire());
        granted(await alone.acquire());
        a();
        a();
        expect(alone.running).toBe(1);
    });

    it('refuses QUEUE_FULL at once when the queue holds its bound, and with a bound of zero never queues', async () => {
        const pool = new TeaSlotPool(1, 2, 5000);
        granted(await pool.acquire());
        const waiters = [pool.acquire(), pool.acquire()];
        expect(pool.queued).toBe(2);
        expect(await pool.acquire()).toEqual({ granted: false, refusal: 'QUEUE_FULL' });
        expect(pool.queued).toBe(2);
        pool.drain();
        await Promise.all(waiters);
        const none = new TeaSlotPool(1, 0, 5000);
        granted(await none.acquire());
        expect(await none.acquire()).toEqual({ granted: false, refusal: 'QUEUE_FULL' });
        expect(none.queued).toBe(0);
        const one = new TeaSlotPool(1, 1, 5000);
        granted(await one.acquire());
        const queued = one.acquire();
        expect(await one.acquire()).toEqual({ granted: false, refusal: 'QUEUE_FULL' });
        one.drain();
        await queued;
    });

    it('refuses SLOTS after the wait and takes only that waiter out of the queue', async () => {
        vi.useFakeTimers();
        const pool = new TeaSlotPool(1, 5, 100);
        const held = granted(await pool.acquire());
        const early = pool.acquire();
        await vi.advanceTimersByTimeAsync(50);
        const late = pool.acquire();
        const latest = pool.acquire();
        expect(pool.queued).toBe(3);
        await vi.advanceTimersByTimeAsync(49);
        expect(pool.queued).toBe(3);
        await vi.advanceTimersByTimeAsync(1);
        expect(await early).toEqual({ granted: false, refusal: 'SLOTS' });
        expect(pool.queued).toBe(2);
        // the others are still queued, in order, and the next release goes to the first of them
        held();
        const release = granted(await late);
        expect(pool.queued).toBe(1);
        expect(pool.running).toBe(1);
        release();
        granted(await latest);
        expect(vi.getTimerCount()).toBe(0);
    });

    it('a waiter that was granted or refused leaves no timer behind', async () => {
        vi.useFakeTimers();
        const pool = new TeaSlotPool(1, 5, 1000);
        const held = granted(await pool.acquire());
        const first = pool.acquire();
        const second = pool.acquire();
        expect(vi.getTimerCount()).toBe(2);
        held();
        granted(await first);
        expect(vi.getTimerCount()).toBe(1);
        pool.drain();
        expect(await second).toEqual({ granted: false, refusal: 'SLOTS' });
        expect(vi.getTimerCount()).toBe(0);
    });

    it('drain refuses every waiting call, one or many, and leaves the running ones alone', async () => {
        for (const count of [1, 2, 5]) {
            const pool = new TeaSlotPool(1, 10, 60000);
            const held = granted(await pool.acquire());
            const waiting = Array.from({ length: count }, () => pool.acquire());
            expect(pool.queued).toBe(count);
            pool.drain();
            expect(pool.queued).toBe(0);
            for (const answer of await Promise.all(waiting)) expect(answer).toEqual({ granted: false, refusal: 'SLOTS' });
            expect(pool.running).toBe(1);
            held();
            expect(pool.running).toBe(0);
        }
        const empty = new TeaSlotPool(1, 1, 10);
        expect(() => empty.drain()).not.toThrow();
    });
});

describe('the disk budget', () => {
    it('reserves while the bytes fit, to the byte, and reserves nothing otherwise', () => {
        const budget = new TeaDiskBudget(100);
        expect(budget.maxBytes).toBe(100);
        expect(budget.reserve(60)).toBe(true);
        expect(budget.used).toBe(60);
        expect(budget.reserve(41)).toBe(false);
        expect(budget.used).toBe(60);
        expect(budget.reserve(40)).toBe(true);
        expect(budget.used).toBe(100);
        expect(budget.reserve(1)).toBe(false);
        expect(budget.reserve(0)).toBe(true);
        expect(budget.used).toBe(100);
    });

    it('refuses a negative amount, accepts zero, and gives bytes back without going below zero', () => {
        const budget = new TeaDiskBudget(100);
        expect(budget.reserve(-1)).toBe(false);
        expect(budget.reserve(0)).toBe(true);
        expect(budget.used).toBe(0);
        expect(budget.reserve(30)).toBe(true);
        budget.free(10);
        expect(budget.used).toBe(20);
        budget.free(1000);
        expect(budget.used).toBe(0);
        expect(new TeaDiskBudget(0).reserve(0)).toBe(true);
        expect(new TeaDiskBudget(0).reserve(1)).toBe(false);
    });

    it('counts each held file for at least 4096 bytes', () => {
        expect(TEA_FILE_FLOOR_BYTES).toBe(4096);
        expect(heldBytesOf([])).toBe(0);
        expect(heldBytesOf([0])).toBe(4096);
        expect(heldBytesOf([1, 4095])).toBe(8192);
        expect(heldBytesOf([4096])).toBe(4096);
        expect(heldBytesOf([4097])).toBe(4097);
        expect(heldBytesOf([1, 5000, 10])).toBe(4096 + 5000 + 4096);
    });
});
