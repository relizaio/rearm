import { createHash } from 'crypto';
import * as fs from 'fs';
import * as path from 'path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../src/services/tea/teaCliRunner', async (importOriginal) => {
    const actual = await importOriginal<typeof import('../../src/services/tea/teaCliRunner')>();
    return { ...actual, runTeaCli: vi.fn(actual.runTeaCli) };
});
vi.mock('../../src/services/tea/teaSbomStore', async (importOriginal) => {
    const actual = await importOriginal<typeof import('../../src/services/tea/teaSbomStore')>();
    return { ...actual, storeHeldSbom: vi.fn() };
});

import { logger } from '../../src/logger';
import { runTeaCli } from '../../src/services/tea/teaCliRunner';
import { storeHeldSbom } from '../../src/services/tea/teaSbomStore';
import {
    configureTea, shutdownTea, TEA_KILL_MARGIN_MS, teaComponent, teaCounters, teaFetchCollection, teaFetchedFileChunk, teaPools, teaProduct,
    teaReleaseFetch, teaReleases, teaRelease, teaResolve, teaStatus, teaStoreFetchedSbom
} from '../../src/services/tea/teaService';
import { API_ROOT, cleanupDirs, configure, FETCH_ID, NONE, sleep, Stub, tempDir, TOKEN, UUID_A, UUID_B, workDirOf } from './teaTestKit';

/**
 * The operations of teaService called directly, with the stand-in CLI (design 3.3 to 3.9): the
 * results of an unconfigured rebom, the counters, what each operation asks of the runner and logs,
 * the reservation of the disk budget, and the held fetch with its bytes.
 */

const mRun = vi.mocked(runTeaCli);
const mStore = vi.mocked(storeHeldSbom);
const PRODUCT_DOC = '{"formatVersion":1,"kind":"TEA_PRODUCT","product":{"name":"caf\u00e9"}}\n';

let stub: Stub;
/** The scratch root of the last configuration a row made. */
let lastRoot = '';

beforeEach(() => {
    stub = new Stub();
    mStore.mockReset();
    mRun.mockClear();
});

afterEach(() => {
    shutdownTea();
    stub.restore();
    vi.restoreAllMocks();
    cleanupDirs();
});

const product = (extra: Record<string, unknown> = {}) => teaProduct({ apiRoot: API_ROOT, uuid: UUID_A, credential: NONE, ...extra });

/** The TEA operation lines of the log, as their fields. */
function lines(info: { mock: { calls: unknown[][] } }): Array<Record<string, any>> {
    return info.mock.calls.filter((call) => call[1] === 'TEA operation').map((call) => call[0] as Record<string, any>);
}

function spyInfo() {
    return vi.spyOn(logger, 'info').mockImplementation(() => undefined as never);
}

function sha256(content: string): string {
    return createHash('sha256').update(content).digest('hex');
}

/** The runs of the CLI that are not the probe. */
function realRuns() {
    return mRun.mock.calls.filter((call) => call[0].length > 2);
}

describe('rebom that is not configured', () => {
    const NULLS = { limits: null, document: null };

    it('every operation answers NOT_CONFIGURED with the reason and the variable named, and the null fields of its result type', async () => {
        const warn = vi.spyOn(logger, 'warn').mockImplementation(() => undefined as never);
        const errors = vi.spyOn(logger, 'error').mockImplementation(() => undefined as never);
        configureTea({ REBOM_TEA_MAX_FILES: '0', REBOM_TEA_CALLER_TOKEN: TOKEN } as any);
        expect(errors).toHaveBeenCalledTimes(1);
        expect(errors.mock.calls[0]).toEqual([{ variable: 'REBOM_TEA_MAX_FILES' }, 'TEA: invalid setting; the TEA operations are not configured']);
        expect(warn).not.toHaveBeenCalled();
        const expected = { teaApiVersion: 1, outcome: 'NOT_CONFIGURED', error: expect.objectContaining({ reason: 'SETTING', detail: 'REBOM_TEA_MAX_FILES', message: 'The TEA operations are not configured on this rebom.' }) };
        for (const call of [teaResolve, teaProduct, teaComponent, teaReleases, teaRelease]) {
            expect(await call({}), call.name).toEqual({ ...expected, ...NULLS });
        }
        expect(await teaFetchCollection({})).toEqual({ ...expected, limits: null, manifest: null, files: null, fetchId: null, expiresAt: null });
        expect(await teaFetchedFileChunk({})).toEqual({ ...expected, dataBase64: null, offset: null, nextOffset: null, fileSizeBytes: null });
        expect(await teaStoreFetchedSbom({})).toEqual({ ...expected, stored: null, format: null, bom: null, conflict: null, storeMessage: null });
        expect(await teaReleaseFetch(FETCH_ID)).toEqual(expected);
        expect(await teaStatus()).toEqual({ ...expected, localTesting: 'OFF', ceilings: null });
        expect(teaCounters()).toEqual({ reads: 0, downloads: 0, results: 0, queued: 0, diskBytes: 0, heldFetches: 0 });
        expect(teaPools()).toBeNull();
        expect(() => shutdownTea()).not.toThrow();
        expect(mRun).not.toHaveBeenCalled();
    });

    it('teaStatus says whether local testing is on even when the settings are wrong', async () => {
        vi.spyOn(logger, 'warn').mockImplementation(() => undefined as never);
        vi.spyOn(logger, 'error').mockImplementation(() => undefined as never);
        configureTea({ REBOM_TEA_MAX_FILES: 'x', REBOM_TEA_LOCAL_TESTING: 'ON' } as any);
        expect(await teaStatus()).toMatchObject({ outcome: 'NOT_CONFIGURED', localTesting: 'ON', ceilings: null });
    });

    it('a scratch root that is unsafe is TMP_ROOT, logged with the variable, and nothing in it is removed', async () => {
        const errors = vi.spyOn(logger, 'error').mockImplementation(() => undefined as never);
        const root = path.join(tempDir('rebom-unsafe-'), 'root');
        fs.mkdirSync(path.join(root, 'work'), { recursive: true });
        fs.writeFileSync(path.join(root, 'work', 'keep'), 'x');
        fs.chmodSync(path.join(root, 'work'), 0o755);
        configureTea({ REBOM_TEA_TMP_ROOT: root } as any);
        expect(await teaProduct({})).toMatchObject({ outcome: 'NOT_CONFIGURED', error: { reason: 'TMP_ROOT', detail: 'REBOM_TEA_TMP_ROOT' } });
        expect(errors.mock.calls.at(-1)).toEqual([{ variable: 'REBOM_TEA_TMP_ROOT' }, 'TEA: the scratch directory is unsafe; the TEA operations are not configured']);
        expect(fs.existsSync(path.join(root, 'work', 'keep'))).toBe(true);
    });

    it('a scratch work entry that is a regular file, a link or open to the group is unsafe, and is told so', async () => {
        for (const make of [
            (work: string) => fs.writeFileSync(work, 'x', { mode: 0o600 }),
            (work: string) => fs.symlinkSync(tempDir('rebom-target-'), work),
            (work: string) => { fs.mkdirSync(work); fs.chmodSync(work, 0o770); },
            (work: string) => { fs.mkdirSync(work); fs.chmodSync(work, 0o707); }
        ]) {
            const errors = vi.spyOn(logger, 'error').mockImplementation(() => undefined as never);
            const root = path.join(tempDir('rebom-unsafe-'), 'root');
            fs.mkdirSync(root, { recursive: true });
            make(path.join(root, 'work'));
            configureTea({ REBOM_TEA_TMP_ROOT: root } as any);
            expect(await teaProduct({})).toMatchObject({ error: { reason: 'TMP_ROOT' } });
            expect(errors.mock.calls.at(-1)![1]).toBe('TEA: the scratch directory is unsafe; the TEA operations are not configured');
            errors.mockRestore();
        }
    });

    it('a root that cannot be made is TMP_ROOT too, logged as one that could not be prepared', async () => {
        const errors = vi.spyOn(logger, 'error').mockImplementation(() => undefined as never);
        const file = path.join(tempDir('rebom-notdir-'), 'file');
        fs.writeFileSync(file, 'x');
        configureTea({ REBOM_TEA_TMP_ROOT: path.join(file, 'root') } as any);
        expect(await teaProduct({})).toMatchObject({ error: { reason: 'TMP_ROOT' } });
        expect(errors.mock.calls.map((call) => call[1])).toContain('TEA: the scratch directory could not be prepared');
    });

    it('prepares the work directory private to the user, and empties what a previous start left in it', () => {
        const root = path.join(tempDir('rebom-prep-'), 'root');
        fs.mkdirSync(path.join(root, 'work', 'f-old', 'dl'), { recursive: true, mode: 0o700 });
        fs.writeFileSync(path.join(root, 'work', 'f-old', 'dl', '000001.bin'), 'x');
        fs.writeFileSync(path.join(root, 'work', 'stray'), 'x');
        fs.chmodSync(path.join(root, 'work'), 0o700);
        configureTea({ REBOM_TEA_TMP_ROOT: root } as any);
        expect(fs.readdirSync(path.join(root, 'work'))).toEqual([]);
        expect(fs.statSync(path.join(root, 'work')).mode & 0o777).toBe(0o700);
        const fresh = path.join(tempDir('rebom-prep-'), 'a', 'b');
        configureTea({ REBOM_TEA_TMP_ROOT: fresh } as any);
        expect(fs.statSync(path.join(fresh, 'work')).mode & 0o777).toBe(0o700);
        expect(fs.statSync(fresh).mode & 0o777).toBe(0o700);
    });

    it('local testing ON is announced once at configuration with a warning, OFF is not', () => {
        const warn = vi.spyOn(logger, 'warn').mockImplementation(() => undefined as never);
        configure({ REBOM_TEA_LOCAL_TESTING: 'ON' });
        expect(warn).toHaveBeenCalledTimes(1);
        expect(warn.mock.calls[0][0]).toBe('TEA: local testing is ON: the TEA client may connect to this host and use plain http. Never in production.');
        warn.mockClear();
        configure({ REBOM_TEA_LOCAL_TESTING: 'on' });
        configure();
        expect(warn).not.toHaveBeenCalled();
    });
});

describe('the configuration of a rebom that is ready', () => {
    it('teaStatus is OK with the ceilings, the download deadline among them, and says whether local testing is on', async () => {
        configure({ REBOM_TEA_READ_DEADLINE_SECONDS: '11', REBOM_TEA_DOWNLOAD_DEADLINE_SECONDS: '22', REBOM_TEA_MAX_FILES: '33' });
        const status = await teaStatus();
        expect(status).toMatchObject({ teaApiVersion: 1, outcome: 'OK', error: null, localTesting: 'OFF' });
        expect(status.ceilings).toMatchObject({ deadlineSeconds: 22, maxFiles: 33, pageSize: 100, maxPages: 10 });
        configure({ REBOM_TEA_LOCAL_TESTING: 'ON' });
        expect((await teaStatus()).localTesting).toBe('ON');
        stub.write('probe.exit', '0');
        stub.write('probe.stderr', '');
        configure();
        expect(await teaStatus()).toMatchObject({ outcome: 'NOT_AVAILABLE', error: { reason: 'CLI_TOO_OLD' }, localTesting: 'OFF', ceilings: expect.objectContaining({ maxFiles: 200 }) });
    });

    it('counts what runs and what waits in the three pools, and the disk bytes and the held fetches', async () => {
        configure({ REBOM_TEA_MAX_CONCURRENT_READS: '1', REBOM_TEA_MAX_CONCURRENT_DOWNLOADS: '1', REBOM_TEA_MAX_CONCURRENT_RESULTS: '1', REBOM_TEA_MAX_QUEUED: '5' });
        expect(teaCounters()).toEqual({ reads: 0, downloads: 0, results: 0, queued: 0, diskBytes: 0, heldFetches: 0 });
        const pools = teaPools()!;
        const slots = [await pools.reads.acquire(), await pools.downloads.acquire(), await pools.results.acquire()];
        expect(teaCounters()).toMatchObject({ reads: 1, downloads: 1, results: 1, queued: 0 });
        const waiting = [pools.reads.acquire(), pools.downloads.acquire(), pools.downloads.acquire(), pools.results.acquire(), pools.results.acquire(), pools.results.acquire()];
        expect(teaCounters().queued).toBe(6);
        expect(pools.reads.queued).toBe(1);
        expect(pools.downloads.queued).toBe(2);
        expect(pools.results.queued).toBe(3);
        configure({ REBOM_TEA_MAX_CONCURRENT_READS: '1' });
        // a new configuration refuses every call that was waiting
        for (const answer of await Promise.all(waiting)) expect(answer).toEqual({ granted: false, refusal: 'SLOTS' });
        void slots;
        expect(teaCounters().queued).toBe(0);
    });

    it('the pools have the limits, the queue and the wait of the settings', () => {
        configure({
            REBOM_TEA_MAX_CONCURRENT_READS: '7', REBOM_TEA_MAX_CONCURRENT_DOWNLOADS: '5', REBOM_TEA_MAX_CONCURRENT_RESULTS: '3',
            REBOM_TEA_MAX_QUEUED: '9', REBOM_TEA_QUEUE_WAIT_SECONDS: '12'
        });
        const pools = teaPools()!;
        expect([pools.reads.limit, pools.downloads.limit, pools.results.limit]).toEqual([7, 5, 3]);
        for (const pool of [pools.reads, pools.downloads, pools.results]) {
            expect(pool.maxQueued).toBe(9);
            expect(Math.abs(pool.waitMs - 12000)).toBeLessThanOrEqual(1);
        }
        expect(TEA_KILL_MARGIN_MS).toBe(15000);
    });

    it('the kill margin, the probe retry time and the SBOM ceiling of the options are used, a zero included', async () => {
        stub.behave({ stdout: PRODUCT_DOC });
        configure({}, { killMarginMs: 0, probeRetryMs: 0, sbomMaxBytes: 0 });
        mRun.mockClear();
        await product({ limits: { deadlineSeconds: 9 } });
        expect(realRuns()[0][1].timeoutMs).toBe(9000);
        // a probe retry time of zero: a bad verdict is tried again on the next call
        stub.write('probe.exit', '0');
        stub.write('probe.stderr', '');
        stub.clearRuns();
        configure({}, { probeRetryMs: 0 });
        expect((await product()).outcome).toBe('NOT_AVAILABLE');
        expect((await product()).outcome).toBe('NOT_AVAILABLE');
        expect(stub.probes()).toBe(2);
        stub.remove('probe.exit');
        stub.remove('probe.stderr');
        expect((await product()).outcome).toBe('OK');
        // the default margin and the default ceiling
        configure();
        mRun.mockClear();
        await product({ limits: { deadlineSeconds: 9 } });
        expect(realRuns()[0][1].timeoutMs).toBe(9000 + 15000);
    });
});

describe('a read', () => {
    it('asks the runner for the deadline plus the kill margin and one byte more than the output bound, and answers the document as written', async () => {
        stub.behave({ stdout: PRODUCT_DOC });
        configure({}, { killMarginMs: 2500 });
        mRun.mockClear();
        const result = await product({ limits: { deadlineSeconds: 7, maxOutputBytes: 70000, connectTimeoutSeconds: 3, maxDocumentBytes: 4096 } });
        expect(result.outcome, JSON.stringify(result.error)).toBe('OK');
        expect(result.document).toBe(PRODUCT_DOC.trimEnd());
        expect(result.limits).toMatchObject({ deadlineSeconds: 7, maxOutputBytes: 70000, connectTimeoutSeconds: 3, maxDocumentBytes: 4096, maxPages: null, maxFiles: null });
        const [argv, options] = realRuns()[0];
        expect(options.timeoutMs).toBe(7 * 1000 + 2500);
        expect(options.maxStdoutBytes).toBe(70001);
        expect(argv.slice(0, 3)).toEqual(['tea', 'machine', 'product']);
        expect(argv).toContain('--deadline=7s');
        expect(argv).toContain('--max-output-bytes=70000');
        expect(Object.keys(options.env).sort()).toContain('PATH');
    });

    it('answers no document for an outcome that is not OK, with the limits that ran', async () => {
        stub.behave({ exit: 3, stderr: '{"formatVersion":1,"kind":"TEA_ERROR","exitCode":3,"reason":"NOT_FOUND","message":"no"}\n' });
        configure();
        const result = await product();
        expect(result).toMatchObject({ outcome: 'NOT_FOUND', document: null, limits: expect.objectContaining({ deadlineSeconds: 60 }), error: { reason: 'NOT_FOUND', cliExitCode: 3 } });
    });

    it('logs one line with the operation, the outcome, the host, the exit code, the output bytes and the times', async () => {
        const info = spyInfo();
        stub.behave({ stdout: PRODUCT_DOC });
        configure();
        await product({ correlationId: 'corr-1' });
        const logged = lines(info);
        expect(logged).toHaveLength(1);
        const line = logged[0];
        expect(line).toMatchObject({ operation: 'teaProduct', outcome: 'OK', correlationId: 'corr-1', apiHost: 'tea.example.com', exitCode: 0, stdoutBytes: Buffer.byteLength(PRODUCT_DOC) });
        expect(line.stdoutBytes).toBeGreaterThan(PRODUCT_DOC.length - 1);
        expect(line.queueWaitMs).toBeGreaterThanOrEqual(0);
        expect(line.queueWaitMs).toBeLessThanOrEqual(line.durationMs);
        expect(line.durationMs).toBeGreaterThanOrEqual(0);
        expect(line.durationMs).toBeLessThan(60000);
        expect(line.reason).toBeUndefined();
    });

    it('logs a refused input with its reason and the time, and no host', async () => {
        const info = spyInfo();
        configure();
        const result = await teaProduct({ apiRoot: 'x', uuid: UUID_A, credential: NONE });
        expect(result.outcome).toBe('INVALID_REQUEST');
        const [line] = lines(info);
        expect(line).toMatchObject({ operation: 'teaProduct', outcome: 'INVALID_REQUEST', reason: 'INPUT' });
        expect(line.apiHost).toBeUndefined();
        expect(line.durationMs).toBeGreaterThanOrEqual(0);
        expect(line.durationMs).toBeLessThan(60000);
        expect(mRun).not.toHaveBeenCalledWith(expect.arrayContaining(['product']), expect.anything());
    });

    it('logs a CLI that is not available, with the host, the reason and the time, and runs nothing', async () => {
        const info = spyInfo();
        stub.write('probe.exit', '0');
        stub.write('probe.stderr', '');
        configure();
        mRun.mockClear();
        const result = await product({ correlationId: 'c9' });
        expect(result).toMatchObject({ outcome: 'NOT_AVAILABLE', document: null, limits: null });
        const [line] = lines(info);
        expect(line).toMatchObject({ operation: 'teaProduct', outcome: 'NOT_AVAILABLE', reason: 'CLI_TOO_OLD', correlationId: 'c9', apiHost: 'tea.example.com' });
        expect(line.durationMs).toBeLessThan(60000);
        expect(realRuns()).toHaveLength(0);
    });

    it('answers BUSY with the limits it would have run under, logs the wait, and releases nothing it did not hold', async () => {
        const info = spyInfo();
        stub.behave({ stdout: PRODUCT_DOC });
        configure({ REBOM_TEA_MAX_CONCURRENT_READS: '1', REBOM_TEA_MAX_QUEUED: '0' });
        const held = await teaPools()!.reads.acquire();
        const result = await product();
        expect(result).toMatchObject({ outcome: 'BUSY', document: null, error: { reason: 'QUEUE_FULL' }, limits: expect.objectContaining({ deadlineSeconds: 60 }) });
        const [line] = lines(info);
        expect(line).toMatchObject({ operation: 'teaProduct', outcome: 'BUSY', reason: 'QUEUE_FULL' });
        expect(line.queueWaitMs).toBeGreaterThanOrEqual(0);
        expect(line.queueWaitMs).toBeLessThan(60000);
        expect(teaCounters().reads).toBe(1);
        if (!held.granted) throw new Error('not granted');
        held.release();
        expect(teaCounters().reads).toBe(0);
        expect((await product()).outcome).toBe('OK');
        expect(teaCounters().reads).toBe(0);
    });

    it('each read verb runs its own command with the limits of its kind', async () => {
        stub.behave({ stdout: PRODUCT_DOC });
        configure();
        mRun.mockClear();
        await teaResolve({ tei: 'tei://tea.example.com/uuid/' + UUID_A, credential: NONE });
        await teaComponent({ apiRoot: API_ROOT, uuid: UUID_A, credential: NONE });
        await teaReleases({ apiRoot: API_ROOT, parentKind: 'PRODUCT', parentUuid: UUID_A, credential: NONE, pageSize: 7, maxPages: 3 });
        await teaRelease({ apiRoot: API_ROOT, releaseKind: 'PRODUCT_RELEASE', releaseUuid: UUID_B, credential: NONE });
        expect(realRuns().map((call) => call[0][2])).toEqual(['resolve', 'component', 'releases', 'release']);
        expect(realRuns()[2][0]).toContain('--page-size=7');
        expect(realRuns()[2][0]).toContain('--max-pages=3');
    });
});

// -------------------------------------------------------------------------------------------------
// the fetch

const row = (number: number, status: string, extra: Record<string, unknown> = {}) => ({ number, status, artifactType: 'BOM', ...extra });

function manifest(files: unknown[]): string {
    return JSON.stringify({ formatVersion: 1, kind: 'TEA_DOWNLOAD_MANIFEST', files }) + '\n';
}

const fetchInput = (extra: Record<string, unknown> = {}) => ({
    apiRoot: API_ROOT, releaseKind: 'COMPONENT_RELEASE', releaseUuid: UUID_B, collectionVersion: 1, documentClasses: ['SBOM', 'VEX', 'OTHER'], credential: NONE, ...extra
});

const verified = (number: number, size: number, extra: Record<string, unknown> = {}) =>
    row(number, 'VERIFIED', { fileName: `${String(number).padStart(6, '0')}.bin`, sizeBytes: size, ...extra });

describe('the fetch', () => {
    const FIXED = Date.parse('2026-03-04T05:06:07.000Z');

    it('holds the verified files: their digests, the budget cut down to what is held, an expiry from the clock, and the rows by status in the log', async () => {
        const info = spyInfo();
        stub.downloadDir({ '000001.bin': 'abc', '000003.bin': 'defgh' });
        stub.behave({ exit: 7, stdout: manifest([
            verified(1, 3), row(2, 'FAILED', { reason: 'CHECKSUM_MISMATCH' }), verified(3, 5, { artifactType: 'VULNERABILITIES' }),
            row(4, 'NOT_ATTEMPTED', { reason: 'TOTAL_BYTES' }), row(5, 'NOT_ATTEMPTED', { reason: 'TOTAL_BYTES' }), row(6, 'NOT_SELECTED')
        ]) });
        configure({ REBOM_TEA_HOLD_SECONDS: '100' }, { now: () => FIXED });
        const result = await teaFetchCollection(fetchInput({ fetchLimits: { holdSeconds: 50 } }));
        expect(result.outcome, JSON.stringify(result.error)).toBe('PARTIAL');
        expect(result.error).toMatchObject({ reason: 'PARTIAL', cliExitCode: 7 });
        expect(result.files).toEqual([
            { number: 1, status: 'VERIFIED', reason: null, documentClass: 'SBOM', sizeBytes: 3, sha256: sha256('abc') },
            { number: 2, status: 'FAILED', reason: 'CHECKSUM_MISMATCH', documentClass: 'SBOM', sizeBytes: null, sha256: null },
            { number: 3, status: 'VERIFIED', reason: null, documentClass: 'VEX', sizeBytes: 5, sha256: sha256('defgh') },
            { number: 4, status: 'NOT_ATTEMPTED', reason: 'TOTAL_BYTES', documentClass: 'SBOM', sizeBytes: null, sha256: null },
            { number: 5, status: 'NOT_ATTEMPTED', reason: 'TOTAL_BYTES', documentClass: 'SBOM', sizeBytes: null, sha256: null },
            { number: 6, status: 'NOT_SELECTED', reason: null, documentClass: 'SBOM', sizeBytes: null, sha256: null }
        ]);
        expect(result.fetchId).toMatch(/^[0-9a-f]{32}$/);
        expect(result.expiresAt).toBe(new Date(FIXED + 50_000).toISOString());
        expect(result.manifest).toContain('"kind":"TEA_DOWNLOAD_MANIFEST"');
        expect(result.limits).toMatchObject({ holdSeconds: 50, maxFiles: 200, maxArtifactBytes: 52428800 });
        // two files held count 4096 each, and nothing more is reserved
        expect(teaCounters()).toMatchObject({ diskBytes: 8192, heldFetches: 1, downloads: 0 });
        const [line] = lines(info);
        expect(line).toMatchObject({
            operation: 'teaFetchCollection', outcome: 'PARTIAL', reason: 'PARTIAL', apiHost: 'tea.example.com', exitCode: 7, heldBytes: 8192,
            rowsByStatus: { VERIFIED: 2, FAILED: 1, NOT_ATTEMPTED: 2, NOT_SELECTED: 1 }
        });
        expect(line.durationMs).toBeLessThan(60000);
        await teaReleaseFetch(result.fetchId);
        expect(teaCounters()).toMatchObject({ diskBytes: 0, heldFetches: 0 });
    });

    it('reserves the total bytes plus 4096 per file while the CLI runs, and gives all of it back when nothing is held', async () => {
        stub.behave({ exit: 0, stdout: manifest([row(1, 'NOT_ATTEMPTED', { reason: 'TOTAL_BYTES' })]), sleep: 1 });
        const info = spyInfo();
        const root = configure();
        const running = teaFetchCollection(fetchInput({ fetchLimits: { maxTotalBytes: 1000, maxFiles: 3 } }));
        await sleep(500);
        expect(teaCounters()).toMatchObject({ diskBytes: 1000 + 3 * 4096, downloads: 1 });
        const result = await running;
        expect(result.outcome).toBe('OK');
        expect(result.fetchId).toBeNull();
        expect(result.expiresAt).toBeNull();
        expect(result.files).toHaveLength(1);
        expect(teaCounters()).toMatchObject({ diskBytes: 0, heldFetches: 0, downloads: 0 });
        expect(fs.readdirSync(workDirOf(root))).toEqual([]);
        expect(lines(info)[0]).toMatchObject({ heldBytes: 0, rowsByStatus: { NOT_ATTEMPTED: 1 } });
    });

    it('is BUSY DISK before anything starts when the reservation does not fit, and fits to the byte', async () => {
        stub.behave({ exit: 0, stdout: manifest([]) });
        configure({ REBOM_TEA_MAX_DISK_BYTES: '1048576' });
        mRun.mockClear();
        // 1048576 - 4096 + 4096 = 1048576 fits exactly, one byte more does not
        const fits = await teaFetchCollection(fetchInput({ fetchLimits: { maxTotalBytes: 1048576 - 4096, maxFiles: 1 } }));
        expect(fits.outcome).toBe('OK');
        const over = await teaFetchCollection(fetchInput({ fetchLimits: { maxTotalBytes: 1048576 - 4095, maxFiles: 1 } }));
        expect(over).toMatchObject({ outcome: 'BUSY', error: { reason: 'DISK' }, limits: expect.objectContaining({ maxFiles: 1 }) });
        expect(realRuns()).toHaveLength(1);
        expect(teaCounters()).toMatchObject({ diskBytes: 0, downloads: 0 });
    });

    it('is BUSY with the slot\'s reason when no download slot is free, and holds nothing', async () => {
        const info = spyInfo();
        configure({ REBOM_TEA_MAX_CONCURRENT_DOWNLOADS: '1', REBOM_TEA_MAX_QUEUED: '0' });
        const held = await teaPools()!.downloads.acquire();
        const result = await teaFetchCollection(fetchInput());
        expect(result).toMatchObject({ outcome: 'BUSY', error: { reason: 'QUEUE_FULL' }, files: null, fetchId: null, limits: expect.objectContaining({ maxFiles: 200 }) });
        expect(lines(info)[0]).toMatchObject({ operation: 'teaFetchCollection', outcome: 'BUSY', reason: 'QUEUE_FULL' });
        expect(teaCounters().diskBytes).toBe(0);
        if (held.granted) held.release();
    });

    it('a manifest that does not match the directory is a contract mismatch carrying the exit code, and the directory is removed', async () => {
        stub.behave({ exit: 0, stdout: manifest([verified(1, 3)]) });
        const root = configure();
        const result = await teaFetchCollection(fetchInput());
        expect(result).toMatchObject({
            outcome: 'CONTRACT_MISMATCH', error: { reason: 'MANIFEST_INVALID', cliExitCode: 0 }, files: null, fetchId: null, manifest: null,
            limits: expect.objectContaining({ maxFiles: 200 })
        });
        expect(teaCounters()).toMatchObject({ diskBytes: 0, heldFetches: 0 });
        expect(fs.readdirSync(workDirOf(root))).toEqual([]);
    });

    it('answers what the CLI said when it did not produce a manifest, with its exit code in the log, and removes the directory', async () => {
        const info = spyInfo();
        stub.behave({ exit: 9, stderr: '{"formatVersion":1,"kind":"TEA_ERROR","exitCode":9,"reason":"DEADLINE","message":"late"}\n' });
        const root = configure();
        const result = await teaFetchCollection(fetchInput());
        expect(result).toMatchObject({ outcome: 'LIMIT', error: { reason: 'DEADLINE', cliExitCode: 9 }, files: null, fetchId: null, manifest: null, limits: expect.objectContaining({ deadlineSeconds: 600 }) });
        expect(lines(info)[0]).toMatchObject({ outcome: 'LIMIT', reason: 'DEADLINE', exitCode: 9 });
        expect(fs.readdirSync(workDirOf(root))).toEqual([]);
        expect(teaCounters().diskBytes).toBe(0);
    });

    it('runs the download with the deadline plus the margin, one byte more than the output bound, and a directory of its own', async () => {
        stub.behave({ exit: 0, stdout: manifest([]) });
        configure({}, { killMarginMs: 500 });
        mRun.mockClear();
        const result = await teaFetchCollection(fetchInput({ limits: { deadlineSeconds: 30, maxOutputBytes: 100000 } }));
        expect(result.outcome).toBe('OK');
        const [argv, options] = realRuns()[0];
        expect(options.timeoutMs).toBe(30_500);
        expect(options.maxStdoutBytes).toBe(100001);
        const dir = argv.find((a: string) => a.startsWith('--dir='))!.slice(6);
        expect(path.basename(dir)).toBe('dl');
        expect(path.basename(path.dirname(dir))).toMatch(/^f-/);
    });

    it('a file that was verified is the one under its number, whatever the manifest called it', async () => {
        stub.downloadDir({ '000001.bin': 'abc' });
        stub.behave({ exit: 0, stdout: manifest([verified(1, 3, { artifactType: 'LICENSE' })]) });
        configure();
        const result = await teaFetchCollection(fetchInput());
        expect(result.files![0]).toMatchObject({ documentClass: 'OTHER', sha256: sha256('abc') });
    });
});

describe('the bytes of a held file', () => {
    async function held(content: string, options: Record<string, string> = {}): Promise<string> {
        stub.downloadDir({ '000001.bin': content });
        stub.behave({ exit: 0, stdout: manifest([verified(1, Buffer.byteLength(content))]) });
        lastRoot = configure(options);
        const result = await teaFetchCollection(fetchInput());
        expect(result.outcome, JSON.stringify(result.error)).toBe('OK');
        return result.fetchId!;
    }

    it('answers the bytes from the offset, the next offset while there is more, and none at the end', async () => {
        const info = spyInfo();
        const fetchId = await held('0123456789');
        const whole = await teaFetchedFileChunk({ fetchId, number: 1, offset: 0, length: 10 });
        expect(whole).toMatchObject({ outcome: 'OK', error: null, dataBase64: Buffer.from('0123456789').toString('base64'), offset: 0, nextOffset: null, fileSizeBytes: 10 });
        const first = await teaFetchedFileChunk({ fetchId, number: 1, offset: 0, length: 1 });
        expect(first).toMatchObject({ dataBase64: Buffer.from('0').toString('base64'), offset: 0, nextOffset: 1, fileSizeBytes: 10 });
        const middle = await teaFetchedFileChunk({ fetchId, number: 1, offset: 3, length: 4 });
        expect(middle).toMatchObject({ dataBase64: Buffer.from('3456').toString('base64'), offset: 3, nextOffset: 7 });
        const tail = await teaFetchedFileChunk({ fetchId, number: 1, offset: 7, length: 100 });
        expect(tail).toMatchObject({ dataBase64: Buffer.from('789').toString('base64'), offset: 7, nextOffset: null });
        const exact = await teaFetchedFileChunk({ fetchId, number: 1, offset: 6, length: 4 });
        expect(exact.nextOffset).toBeNull();
        const atEnd = await teaFetchedFileChunk({ fetchId, number: 1, offset: 10, length: 5 });
        expect(atEnd).toMatchObject({ outcome: 'OK', dataBase64: '', offset: 10, nextOffset: null, fileSizeBytes: 10 });
        const logged = lines(info).filter((line) => line.operation === 'teaFetchedFileChunk');
        expect(logged).toHaveLength(6);
        expect(logged[0]).toMatchObject({ outcome: 'OK' });
        expect(logged[0].durationMs).toBeLessThan(60000);
    });

    it('refuses an offset past the end, an unknown file number and an unknown fetch, and holds the slot only while it works', async () => {
        const fetchId = await held('0123456789');
        expect(await teaFetchedFileChunk({ fetchId, number: 1, offset: 11, length: 1 })).toMatchObject({
            outcome: 'INVALID_REQUEST', error: { reason: 'INPUT', detail: 'offset: at most the size of the file' }, dataBase64: null
        });
        expect(await teaFetchedFileChunk({ fetchId, number: 2, offset: 0, length: 1 })).toMatchObject({ outcome: 'INVALID_REQUEST', error: { reason: 'FILE_NOT_HELD' } });
        expect(await teaFetchedFileChunk({ fetchId: '0'.repeat(32), number: 1, offset: 0, length: 1 })).toMatchObject({ outcome: 'FETCH_NOT_HELD', error: { reason: 'FETCH' } });
        expect(await teaFetchedFileChunk({ fetchId: 'bad', number: 1, offset: 0, length: 1 })).toMatchObject({ outcome: 'INVALID_REQUEST', error: { detail: 'fetchId: 32 lower-case hexadecimal characters' } });
        expect(teaCounters().results).toBe(0);
    });

    it('answers at most the largest chunk of the setting', async () => {
        const content = 'x'.repeat(70000);
        const fetchId = await held(content, { REBOM_TEA_MAX_CHUNK_BYTES: '65536' });
        const chunk = await teaFetchedFileChunk({ fetchId, number: 1, offset: 0, length: 100000 });
        expect(Buffer.from(chunk.dataBase64!, 'base64')).toHaveLength(65536);
        expect(chunk.nextOffset).toBe(65536);
        const rest = await teaFetchedFileChunk({ fetchId, number: 1, offset: 65536, length: 100000 });
        expect(Buffer.from(rest.dataBase64!, 'base64')).toHaveLength(70000 - 65536);
        expect(rest.nextOffset).toBeNull();
    });

    it('stops at the end of a file that has become shorter than it was, instead of waiting for bytes that are not there', async () => {
        const fetchId = await held('0123456789');
        const work = workDirOf(lastRoot);
        const file = path.join(work, fs.readdirSync(work)[0], 'dl', '000001.bin');
        fs.truncateSync(file, 4);
        const chunk = await teaFetchedFileChunk({ fetchId, number: 1, offset: 0, length: 10 });
        expect(chunk.outcome).toBe('OK');
        expect(Buffer.from(chunk.dataBase64!, 'base64').subarray(0, 4).toString()).toBe('0123');
    });

    it('is BUSY when no result slot is free', async () => {
        const fetchId = await held('0123456789', { REBOM_TEA_MAX_CONCURRENT_RESULTS: '1', REBOM_TEA_MAX_QUEUED: '0' });
        const slot = await teaPools()!.results.acquire();
        expect(await teaFetchedFileChunk({ fetchId, number: 1, offset: 0, length: 1 })).toMatchObject({ outcome: 'BUSY', error: { reason: 'QUEUE_FULL' } });
        expect(await teaStoreFetchedSbom({ fetchId, number: 1, org: UUID_A, rebomOptions: {} })).toMatchObject({ outcome: 'BUSY', stored: null });
        if (slot.granted) slot.release();
        expect((await teaFetchedFileChunk({ fetchId, number: 1, offset: 0, length: 1 })).outcome).toBe('OK');
    });
});

describe('the store and the release of a fetch', () => {
    async function held(): Promise<string> {
        stub.downloadDir({ '000001.bin': '{"bomFormat":"CycloneDX"}' });
        stub.behave({ exit: 0, stdout: manifest([verified(1, 25)]) });
        lastRoot = configure({}, { sbomMaxBytes: 777 });
        return (await teaFetchCollection(fetchInput())).fetchId!;
    }

    it('hands the held file, the request and the SBOM ceiling to the store, and logs the organisation, the answer and the row', async () => {
        const info = spyInfo();
        const fetchId = await held();
        mStore.mockResolvedValue({
            teaApiVersion: 1, outcome: 'OK', error: null, stored: 'CREATED', format: 'CYCLONEDX', bom: { uuid: 'bom-uuid-1' } as any, conflict: null, storeMessage: null
        });
        const result = await teaStoreFetchedSbom({ fetchId, number: 1, org: UUID_A.toUpperCase(), tags: { a: 1 }, rebomOptions: { name: 'n' }, continueLineage: 'urn:uuid:x' });
        expect(result).toMatchObject({ outcome: 'OK', stored: 'CREATED', bom: { uuid: 'bom-uuid-1' } });
        const [file, request, max] = mStore.mock.calls[0];
        expect(file).toMatchObject({ number: 1, sizeBytes: 25, sha256: expect.stringMatching(/^[0-9a-f]{64}$/), documentClass: 'SBOM' });
        expect(request).toMatchObject({ fetchId, number: 1, org: UUID_A, tags: { a: 1 }, rebomOptions: { name: 'n' }, continueLineage: 'urn:uuid:x' });
        expect(max).toBe(777);
        const line = lines(info).find((l) => l.operation === 'teaStoreFetchedSbom')!;
        expect(line).toMatchObject({ outcome: 'OK', org: UUID_A, stored: 'CREATED', bomUuid: 'bom-uuid-1' });
        expect(line.durationMs).toBeLessThan(60000);
        // without a row the log names none
        mStore.mockResolvedValue({ teaApiVersion: 1, outcome: 'OK', error: null, stored: 'NOT_AN_SBOM', format: null, bom: null, conflict: null, storeMessage: null });
        info.mockClear();
        await teaStoreFetchedSbom({ fetchId, number: 1, org: UUID_A, rebomOptions: {} });
        const second = lines(info).find((l) => l.operation === 'teaStoreFetchedSbom')!;
        expect(second.bomUuid).toBeUndefined();
        expect(second.stored).toBe('NOT_AN_SBOM');
    });

    it('answers a file that is not held, a fetch that is not held and a refused input without calling the store', async () => {
        const fetchId = await held();
        expect(await teaStoreFetchedSbom({ fetchId, number: 2, org: UUID_A, rebomOptions: {} })).toMatchObject({
            outcome: 'INVALID_REQUEST', error: { reason: 'FILE_NOT_HELD' }, stored: null, format: null, bom: null, conflict: null, storeMessage: null
        });
        expect(await teaStoreFetchedSbom({ fetchId: '1'.repeat(32), number: 1, org: UUID_A, rebomOptions: {} })).toMatchObject({ outcome: 'FETCH_NOT_HELD', error: { reason: 'FETCH' }, stored: null });
        expect(await teaStoreFetchedSbom({ fetchId, number: 1, org: 'x', rebomOptions: {} })).toMatchObject({ outcome: 'INVALID_REQUEST', error: { reason: 'INPUT', detail: 'input.org: a uuid (8-4-4-4-12 hexadecimal)' } });
        expect(mStore).not.toHaveBeenCalled();
        expect(teaCounters().results).toBe(0);
    });

    it('releases a fetch at once, logs it, and says OK for one that is unknown; a refused id is INVALID_REQUEST naming fetchId', async () => {
        const info = spyInfo();
        const fetchId = await held();
        expect(teaCounters().heldFetches).toBe(1);
        expect(await teaReleaseFetch(fetchId)).toEqual({ teaApiVersion: 1, outcome: 'OK', error: null });
        expect(teaCounters()).toMatchObject({ heldFetches: 0, diskBytes: 0 });
        expect(await teaReleaseFetch(fetchId)).toMatchObject({ outcome: 'OK' });
        expect(lines(info).filter((l) => l.operation === 'teaReleaseFetch')).toEqual([{ operation: 'teaReleaseFetch', outcome: 'OK' }, { operation: 'teaReleaseFetch', outcome: 'OK' }]);
        expect(await teaReleaseFetch('nope')).toMatchObject({ outcome: 'INVALID_REQUEST', error: { reason: 'INPUT', detail: 'fetchId: 32 lower-case hexadecimal characters' } });
        expect(lines(info).filter((l) => l.operation === 'teaReleaseFetch')).toHaveLength(2);
    });

    it('reconfiguring forgets the held fetches and removes their files, shutting down removes the work directory', async () => {
        const fetchId = await held();
        const work = path.join(lastRoot, 'work');
        expect(fs.readdirSync(work)).toHaveLength(1);
        configure();
        expect(fs.readdirSync(path.join(workDirOf(lastRoot))).length).toBeLessThanOrEqual(1);
        expect(teaCounters().heldFetches).toBe(0);
        expect(await teaFetchedFileChunk({ fetchId, number: 1, offset: 0, length: 1 })).toMatchObject({ outcome: 'FETCH_NOT_HELD' });
        const root = configure();
        stub.downloadDir({ '000001.bin': 'abc' });
        stub.behave({ exit: 0, stdout: manifest([verified(1, 3)]) });
        await teaFetchCollection(fetchInput());
        expect(fs.existsSync(path.join(root, 'work'))).toBe(true);
        shutdownTea();
        expect(fs.existsSync(path.join(root, 'work'))).toBe(false);
        expect(teaCounters().heldFetches).toBe(0);
    });
});
