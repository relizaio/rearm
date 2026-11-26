import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { teaCounters, teaPools } from '../../src/services/tea/teaService';
import { API_ROOT, cleanupDirs, configure, field, gql, Http, NONE, Q, sleep, startHttp, Stub, UUID_A, UUID_B } from './teaTestKit';

/**
 * Slots, queue and disk budget (TPF-180 design 3.7; rows T-50 to T-54).
 */

const UUIDS = [
    '00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000002',
    '00000000-0000-4000-8000-000000000003', '00000000-0000-4000-8000-000000000004'
];

describe('TEA slots and disk budget', () => {
    let http: Http;
    let stub: Stub;

    beforeAll(async () => {
        http = await startHttp();
    });

    afterAll(async () => {
        await http.server.stop();
    });

    afterEach(() => {
        stub?.restore();
        cleanupDirs();
    });

    const product = async (uuid = UUID_A) => field(await gql(http.url, Q.product, { input: { apiRoot: API_ROOT, uuid, credential: NONE } }));
    const fetch = async (fetchLimits?: Record<string, number>) => field(await gql(http.url, Q.fetch, { input: {
        apiRoot: API_ROOT, releaseKind: 'COMPONENT_RELEASE', releaseUuid: UUID_B, collectionVersion: 1, documentClasses: ['SBOM'], credential: NONE,
        ...(fetchLimits ? { fetchLimits } : {})
    } }));

    async function waitFor(condition: () => boolean, ms = 5000): Promise<void> {
        for (let waited = 0; waited < ms && !condition(); waited += 20) await sleep(20);
    }

    it('T-50 with two read slots, a third and a fourth call start in order as slots free', async () => {
        stub = new Stub();
        stub.behave({ sleep: 1, stdout: '{"formatVersion":1,"kind":"TEA_PRODUCT"}\n' });
        configure({ REBOM_TEA_MAX_CONCURRENT_READS: '2' });
        await product(); // the probe, out of the way
        stub.clearRuns();
        const first = [product(UUIDS[0]), product(UUIDS[1])];
        await waitFor(() => stub.starts().length === 2);
        const third = product(UUIDS[2]);
        await sleep(100);
        const fourth = product(UUIDS[3]);
        await sleep(300);
        expect(stub.starts()).toHaveLength(2);
        expect(teaCounters().queued).toBe(2);
        const results = await Promise.all([...first, third, fourth]);
        for (const result of results) expect(result.outcome).toBe('OK');
        const order = stub.starts().map((pid) => stub.argv(pid).find((a) => a.startsWith('--uuid='))!.substring('--uuid='.length));
        expect(order.slice(2)).toEqual([UUIDS[2], UUIDS[3]]);
    });

    it('T-51 BUSY SLOTS after the queue wait; QUEUE_FULL at once when the queue is full; BUSY at once with no queue', async () => {
        stub = new Stub();
        stub.behave({ sleep: 5, stdout: '{"formatVersion":1,"kind":"TEA_PRODUCT"}\n' });
        configure({ REBOM_TEA_MAX_CONCURRENT_READS: '1', REBOM_TEA_QUEUE_WAIT_SECONDS: '2' });
        const running = product();
        await waitFor(() => stub.starts().length === 1);
        let started = Date.now();
        const waited = await product();
        expect(waited.outcome).toBe('BUSY');
        expect(waited.error.reason).toBe('SLOTS');
        expect(Date.now() - started).toBeGreaterThanOrEqual(1900);
        await running;

        configure({ REBOM_TEA_MAX_CONCURRENT_READS: '1', REBOM_TEA_QUEUE_WAIT_SECONDS: '10', REBOM_TEA_MAX_QUEUED: '1' });
        stub.clearRuns();
        const holding = product();
        await waitFor(() => stub.starts().length === 1);
        const queued = product();
        await sleep(100);
        started = Date.now();
        const full = await product();
        expect(full.outcome).toBe('BUSY');
        expect(full.error.reason).toBe('QUEUE_FULL');
        expect(Date.now() - started).toBeLessThan(1000);

        configure({ REBOM_TEA_MAX_CONCURRENT_READS: '1', REBOM_TEA_MAX_QUEUED: '0' });
        stub.clearRuns();
        const alone = product();
        await waitFor(() => stub.starts().length === 1);
        started = Date.now();
        const none = await product();
        expect(none.outcome).toBe('BUSY');
        expect(Date.now() - started).toBeLessThan(1000);
        await Promise.all([holding, queued, alone]);
    });

    it('T-52 a full download pool and a full result pool leave reads free', async () => {
        stub = new Stub();
        stub.behave({ stdout: '{"formatVersion":1,"kind":"TEA_PRODUCT"}\n' });
        configure({ REBOM_TEA_MAX_CONCURRENT_DOWNLOADS: '1', REBOM_TEA_MAX_CONCURRENT_RESULTS: '1', REBOM_TEA_MAX_QUEUED: '0' });
        const pools = teaPools()!;
        const download = await pools.downloads.acquire();
        expect(download.granted).toBe(true);
        expect((await fetch()).outcome).toBe('BUSY');
        expect((await product()).outcome).toBe('OK');
        const result = await pools.results.acquire();
        expect(result.granted).toBe(true);
        expect((await product()).outcome).toBe('OK');
        if (download.granted) download.release();
        if (result.granted) result.release();
    });

    it('T-53 ten failing calls in a row with one slot and no queue: none is BUSY', async () => {
        stub = new Stub();
        configure({ REBOM_TEA_MAX_CONCURRENT_READS: '1', REBOM_TEA_MAX_QUEUED: '0' }, { killMarginMs: 100 });
        const failures: Array<Record<string, unknown>> = [
            { exit: 1 }, { exit: 3 }, { exit: 6 }, { stdout: 'not json\n' }, { exit: 9 }, { signal: 'TERM' }, { exit: 2 },
            { stdout: '{"formatVersion":2,"kind":"TEA_PRODUCT"}\n' }, { exit: 42 }, { exit: 7, stdout: '{}\n' }
        ];
        for (const failure of failures) {
            stub.behave(failure as any);
            const result = await product();
            expect(result.outcome, JSON.stringify(failure)).not.toBe('BUSY');
            expect(result.outcome, JSON.stringify(failure)).not.toBe('OK');
        }
        expect(teaCounters().reads).toBe(0);
    });

    it('T-54 the disk budget: BUSY DISK before any start; a held 10 KiB fetch leaves room for a second; release gives back all; 0 bytes count 4096', async () => {
        stub = new Stub();
        const manifest = (size: number) => `{"formatVersion":1,"kind":"TEA_DOWNLOAD_MANIFEST","files":[{"number":1,"status":"VERIFIED","fileName":"000001.bin","sizeBytes":${size},"artifactType":"BOM"}]}\n`;
        configure({ REBOM_TEA_MAX_DISK_BYTES: '1048576' });
        stub.behave({ stdout: manifest(0) });
        const refused = await fetch();
        expect(refused.outcome).toBe('BUSY');
        expect(refused.error.reason).toBe('DISK');
        expect(stub.runs()).toEqual([]);

        configure({ REBOM_TEA_MAX_DISK_BYTES: '2097152' });
        const oneFetch = { maxTotalBytes: 1048576, maxFiles: 1 };
        stub.downloadDir({ '000001.bin': Buffer.alloc(10240, 1) });
        stub.behave({ stdout: manifest(10240) });
        const first = await fetch(oneFetch);
        expect(first.outcome).toBe('OK');
        expect(teaCounters().diskBytes).toBe(10240);
        const second = await fetch(oneFetch);
        expect(second.outcome).toBe('OK');
        expect(teaCounters().diskBytes).toBe(20480);
        for (const id of [first.fetchId, second.fetchId]) {
            expect(field(await gql(http.url, Q.releaseFetch, { fetchId: id })).outcome).toBe('OK');
        }
        expect(teaCounters().diskBytes).toBe(0);

        stub.downloadDir({ '000001.bin': '' });
        stub.behave({ stdout: manifest(0) });
        const empty = await fetch(oneFetch);
        expect(empty.outcome).toBe('OK');
        expect(teaCounters().diskBytes).toBe(4096);
    });
});
