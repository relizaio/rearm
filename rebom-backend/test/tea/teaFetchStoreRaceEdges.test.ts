import * as fs from 'fs';
import * as path from 'path';
import { afterEach, describe, expect, it } from 'vitest';
import { TeaFetchStore } from '../../src/services/tea/teaFetchStore';
import { TeaDiskBudget } from '../../src/services/tea/teaSlots';
import { cleanupDirs, sleep, tempDir } from './teaTestKit';

/**
 * A fetch that is forgotten while a call still uses it (design 3.6.1): when that call ends it
 * gives back nothing a second time.
 */

afterEach(() => {
    cleanupDirs();
});

describe('a fetch forgotten while it is in use', () => {
    it('does not give its bytes back again when the last user ends, and does not touch what was reserved since', async () => {
        const budget = new TeaDiskBudget(1_000_000);
        const store = new TeaFetchStore(budget, () => 1_000_000);
        const dir = path.join(tempDir('rebom-race-'), 'f-1');
        fs.mkdirSync(dir);
        expect(budget.reserve(8192)).toBe(true);
        const held = store.register(dir, [], 600, 8192);
        let finish!: () => void;
        const running = store.use(held.fetchId, () => new Promise<void>((resolve) => { finish = resolve; }));
        await sleep(20);
        await store.release(held.fetchId);
        expect(fs.existsSync(dir)).toBe(true);
        store.clearSync();
        expect(budget.used).toBe(0);
        expect(budget.reserve(5000)).toBe(true);
        finish();
        await running;
        expect(budget.used).toBe(5000);
        expect(store.size).toBe(0);
    });
});
