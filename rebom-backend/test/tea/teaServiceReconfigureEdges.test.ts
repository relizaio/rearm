import * as fs from 'fs';
import * as path from 'path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../src/services/tea/teaSbomStore', async (importOriginal) => {
    const actual = await importOriginal<typeof import('../../src/services/tea/teaSbomStore')>();
    return { ...actual, storeHeldSbom: vi.fn() };
});

import { logger } from '../../src/logger';
import { teaWorkDir } from '../../src/services/tea/teaCliRunner';
import { storeHeldSbom } from '../../src/services/tea/teaSbomStore';
import {
    configureTea, shutdownTea, teaCounters, teaFetchCollection, teaFetchedFileChunk, teaPools, teaProduct, teaStoreFetchedSbom
} from '../../src/services/tea/teaService';
import { API_ROOT, cleanupDirs, configure, FETCH_ID, NONE, sleep, Stub, tempDir, UUID_A, UUID_B, workDirOf } from './teaTestKit';

/**
 * What a new configuration does to the old one (design 3.4): calls that wait are refused, held
 * fetches and their files go at once, the scratch directory handle is cleared, the expiry timer of
 * the held fetches runs for the new configuration; and the logged times of a fetch that is refused.
 */

const mStore = vi.mocked(storeHeldSbom);
const PRODUCT_DOC = '{"formatVersion":1,"kind":"TEA_PRODUCT","product":{"name":"x"}}\n';

let stub: Stub;

beforeEach(() => {
    stub = new Stub();
    mStore.mockReset();
});

afterEach(() => {
    vi.useRealTimers();
    shutdownTea();
    stub.restore();
    vi.restoreAllMocks();
    cleanupDirs();
});

const row = (number: number, status: string, extra: Record<string, unknown> = {}) => ({ number, status, artifactType: 'BOM', ...extra });
const manifest = (files: unknown[]) => JSON.stringify({ formatVersion: 1, kind: 'TEA_DOWNLOAD_MANIFEST', files }) + '\n';
const verified = (number: number, size: number) => row(number, 'VERIFIED', { fileName: `${String(number).padStart(6, '0')}.bin`, sizeBytes: size });
const fetchInput = (extra: Record<string, unknown> = {}) => ({
    apiRoot: API_ROOT, releaseKind: 'COMPONENT_RELEASE', releaseUuid: UUID_B, collectionVersion: 1, documentClasses: ['SBOM', 'VEX', 'OTHER'], credential: NONE, ...extra
});

async function heldFetch(content: string, env: Record<string, string> = {}, options: Parameters<typeof configure>[1] = {}): Promise<{ root: string, fetchId: string }> {
    stub.downloadDir({ '000001.bin': content });
    stub.behave({ exit: 0, stdout: manifest([verified(1, Buffer.byteLength(content))]) });
    const root = configure(env, options);
    const result = await teaFetchCollection(fetchInput());
    expect(result.outcome, JSON.stringify(result.error)).toBe('OK');
    return { root, fetchId: result.fetchId! };
}

const SLOW_QUEUE = { REBOM_TEA_MAX_CONCURRENT_READS: '1', REBOM_TEA_MAX_CONCURRENT_DOWNLOADS: '1', REBOM_TEA_MAX_CONCURRENT_RESULTS: '1', REBOM_TEA_MAX_QUEUED: '5', REBOM_TEA_QUEUE_WAIT_SECONDS: '300' };

/** Resolves with what the promise answers, or fails the test when it is still waiting after a second. */
function within(promise: Promise<any>, ms = 1000): Promise<any> {
    return Promise.race([promise, sleep(ms).then(() => 'STILL WAITING')]);
}

describe('a call that waits for a slot when the configuration is replaced', () => {
    it('is refused at once on each of the three pools, instead of waiting out a long queue time', async () => {
        stub.behave({ stdout: PRODUCT_DOC });
        stub.downloadDir({});
        configure(SLOW_QUEUE);
        const pools = teaPools()!;
        const readSlot = await pools.reads.acquire();
        const downloadSlot = await pools.downloads.acquire();
        const resultSlot = await pools.results.acquire();
        const read = teaProduct({ apiRoot: API_ROOT, uuid: UUID_A, credential: NONE });
        const download = teaFetchCollection(fetchInput());
        const chunk = teaFetchedFileChunk({ fetchId: FETCH_ID, number: 1, offset: 0, length: 1 });
        await sleep(300);
        expect(teaCounters().queued).toBe(3);
        configure(SLOW_QUEUE);
        for (const answer of [await within(read), await within(download), await within(chunk)]) {
            expect(answer).toMatchObject({ outcome: 'BUSY', error: { reason: 'SLOTS' } });
        }
        for (const slot of [readSlot, downloadSlot, resultSlot]) if (slot.granted) slot.release();
    });

    it('is refused when a store call waits for a result slot', async () => {
        stub.behave({ stdout: PRODUCT_DOC });
        configure(SLOW_QUEUE);
        const resultSlot = await teaPools()!.results.acquire();
        const store = teaStoreFetchedSbom({ fetchId: FETCH_ID, number: 1, org: UUID_A, rebomOptions: {} });
        await sleep(200);
        expect(teaCounters().queued).toBe(1);
        configure(SLOW_QUEUE);
        expect(await within(store)).toMatchObject({ outcome: 'BUSY', error: { reason: 'SLOTS' } });
        if (resultSlot.granted) resultSlot.release();
    });
});

describe('a held fetch when the configuration is replaced', () => {
    it('is forgotten with its files removed from the old scratch directory at once, and its bytes are not counted by the new one', async () => {
        const { root, fetchId } = await heldFetch('0123456789');
        expect(fs.readdirSync(workDirOf(root))).toHaveLength(1);
        expect(teaCounters().heldFetches).toBe(1);
        configure();
        expect(fs.readdirSync(workDirOf(root))).toEqual([]);
        expect(teaCounters()).toMatchObject({ heldFetches: 0, diskBytes: 0 });
        expect(await teaFetchedFileChunk({ fetchId, number: 1, offset: 0, length: 1 })).toMatchObject({ outcome: 'FETCH_NOT_HELD' });
    });

    it('is forgotten when the new settings are wrong too, and the scratch directory handle is cleared', async () => {
        const { root } = await heldFetch('abc');
        expect(teaWorkDir()).toBe(workDirOf(root));
        vi.spyOn(logger, 'error').mockImplementation(() => undefined as never);
        configureTea({ REBOM_TEA_MAX_FILES: '0', REBOM_TEA_CALLER_TOKEN: 'x' } as any);
        expect(fs.readdirSync(workDirOf(root))).toEqual([]);
        expect(teaWorkDir()).toBeNull();
        expect(await teaProduct({})).toMatchObject({ outcome: 'NOT_CONFIGURED' });
    });

    it('a scratch directory that is unsafe leaves no handle behind either', async () => {
        await heldFetch('abc');
        expect(teaWorkDir()).not.toBeNull();
        const errors = vi.spyOn(logger, 'error').mockImplementation(() => undefined as never);
        const root = path.join(tempDir('rebom-unsafe-'), 'root');
        fs.mkdirSync(path.join(root, 'work'), { recursive: true });
        fs.chmodSync(path.join(root, 'work'), 0o755);
        configureTea({ REBOM_TEA_TMP_ROOT: root } as any);
        expect(teaWorkDir()).toBeNull();
        expect(errors).toHaveBeenCalledTimes(1);
    });
});

describe('the scratch work entry', () => {
    it('that is a regular file is unsafe and is only that: one log line, and nothing that says it could not be prepared', () => {
        const errors = vi.spyOn(logger, 'error').mockImplementation(() => undefined as never);
        const root = path.join(tempDir('rebom-file-'), 'root');
        fs.mkdirSync(root, { recursive: true });
        fs.writeFileSync(path.join(root, 'work'), 'x', { mode: 0o600 });
        configureTea({ REBOM_TEA_TMP_ROOT: root } as any);
        expect(errors).toHaveBeenCalledTimes(1);
        expect(errors.mock.calls[0]).toEqual([{ variable: 'REBOM_TEA_TMP_ROOT' }, 'TEA: the scratch directory is unsafe; the TEA operations are not configured']);
        expect(fs.readFileSync(path.join(root, 'work'), 'utf8')).toBe('x');
    });
});

describe('the expiry of held fetches', () => {
    it('runs every ten seconds from the configuration, and removes the fetch whose hold has passed', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] });
        vi.setSystemTime(new Date('2026-05-05T10:00:00.000Z'));
        const { root, fetchId } = await heldFetch('abc', { REBOM_TEA_HOLD_SECONDS: '10' });
        expect(teaCounters().heldFetches).toBe(1);
        vi.setSystemTime(new Date('2026-05-05T10:00:11.000Z'));
        vi.advanceTimersByTime(9_999);
        await sleep(50);
        expect(teaCounters().heldFetches).toBe(1);
        vi.advanceTimersByTime(1);
        await sleep(100);
        expect(teaCounters()).toMatchObject({ heldFetches: 0, diskBytes: 0 });
        expect(fs.readdirSync(workDirOf(root))).toEqual([]);
        expect(await teaFetchedFileChunk({ fetchId, number: 1, offset: 0, length: 1 })).toMatchObject({ outcome: 'FETCH_NOT_HELD' });
    });
});

describe('the SBOM ceiling option', () => {
    it('a zero reaches the store as zero', async () => {
        const { fetchId } = await heldFetch('{"bomFormat":"CycloneDX"}', {}, { sbomMaxBytes: 0 });
        mStore.mockResolvedValue({ teaApiVersion: 1, outcome: 'OK', error: null, stored: 'TOO_LARGE', format: null, bom: null, conflict: null, storeMessage: null } as any);
        await teaStoreFetchedSbom({ fetchId, number: 1, org: UUID_A, rebomOptions: {} });
        expect(mStore.mock.calls[0][2]).toBe(0);
    });
});

describe('the bytes of a file that has become empty', () => {
    it('are none, and are not waited for', async () => {
        const { root, fetchId } = await heldFetch('0123456789');
        const work = workDirOf(root);
        fs.truncateSync(path.join(work, fs.readdirSync(work)[0], 'dl', '000001.bin'), 0);
        const chunk = await within(teaFetchedFileChunk({ fetchId, number: 1, offset: 0, length: 10 }), 3000);
        expect(chunk).toMatchObject({ outcome: 'OK' });
        expect(Buffer.from(chunk.dataBase64, 'base64').equals(Buffer.alloc(10))).toBe(true);
    });

    it('a request for no bytes at the end of the file does not open the file', async () => {
        const { root, fetchId } = await heldFetch('0123456789');
        const work = workDirOf(root);
        fs.rmSync(path.join(work, fs.readdirSync(work)[0], 'dl', '000001.bin'));
        const chunk = await teaFetchedFileChunk({ fetchId, number: 1, offset: 10, length: 5 });
        expect(chunk).toMatchObject({ outcome: 'OK', dataBase64: '', offset: 10, nextOffset: null, fileSizeBytes: 10 });
    });
});

describe('a fetch that is refused', () => {
    it('for its input logs one line with the reason and the time, and no host', async () => {
        const info = vi.spyOn(logger, 'info').mockImplementation(() => undefined as never);
        configure();
        const result = await teaFetchCollection(fetchInput({ releaseUuid: 'not-a-uuid' }));
        expect(result).toMatchObject({ outcome: 'INVALID_REQUEST', error: { reason: 'INPUT' } });
        const lines = info.mock.calls.filter((call) => call[1] === 'TEA operation').map((call) => call[0] as Record<string, any>);
        expect(lines).toHaveLength(1);
        expect(lines[0]).toMatchObject({ operation: 'teaFetchCollection', outcome: 'INVALID_REQUEST', reason: 'INPUT' });
        expect(lines[0].apiHost).toBeUndefined();
        expect(lines[0].durationMs).toBeGreaterThanOrEqual(0);
        expect(lines[0].durationMs).toBeLessThan(60000);
    });

    it('when the CLI is not available answers that and runs nothing, reserving nothing', async () => {
        stub.write('probe.exit', '0');
        stub.write('probe.stderr', '');
        configure();
        const result = await teaFetchCollection(fetchInput());
        expect(result).toMatchObject({ outcome: 'NOT_AVAILABLE', files: null, fetchId: null, limits: null });
        expect(stub.runs().filter((r: any) => !String(r).includes('probe'))).toHaveLength(0);
        expect(teaCounters()).toMatchObject({ diskBytes: 0, downloads: 0, heldFetches: 0 });
    });

    it('for the disk does not give back what another fetch has reserved', async () => {
        stub.downloadDir({});
        stub.behave({ exit: 0, stdout: manifest([]), sleep: 1 });
        configure({ REBOM_TEA_MAX_DISK_BYTES: '1048576', REBOM_TEA_MAX_CONCURRENT_DOWNLOADS: '2' });
        const first = teaFetchCollection(fetchInput({ fetchLimits: { maxTotalBytes: 500000, maxFiles: 1 } }));
        await sleep(600);
        expect(teaCounters().diskBytes).toBe(500000 + 4096);
        const refused = await teaFetchCollection(fetchInput({ fetchLimits: { maxTotalBytes: 600000, maxFiles: 1 } }));
        expect(refused).toMatchObject({ outcome: 'BUSY', error: { reason: 'DISK' } });
        expect(teaCounters().diskBytes).toBe(500000 + 4096);
        await first;
        expect(teaCounters().diskBytes).toBe(0);
    });
});
