import { createHash } from 'crypto';
import * as fs from 'fs';
import * as path from 'path';
import { logger } from '../../logger';
import { buildTeaArgv } from './teaArgv';
import { setTeaCallerToken } from './teaCallerGate';
import { buildTeaChildEnv } from './teaChildEnv';
import { ensureTeaCli, resetTeaProbe, TEA_PROBE_RETRY_MS } from './teaCliProbe';
import { killAllTeaRuns, makeCallDir, removeCallDir, runTeaCli, setTeaWorkDir, withCallDir } from './teaCliRunner';
import {
    ceilingsAsLimits, effectiveLimits, localTestingOf, readTeaConfig, TEA_LOCAL_TESTING_VARIABLE, TEA_TMP_ROOT_VARIABLE,
    TeaConfig, TeaEffectiveLimits, TeaLocalTesting, TeaRunKind, validCallerToken, TEA_CALLER_TOKEN_VARIABLE
} from './teaConfig';
import { checkTeaManifest, TeaDocumentClass, TeaDocumentKind, teaFilePath, TeaFileStatus } from './teaDocument';
import { TEA_FETCH_NOT_HELD, TeaFetchStore, TeaHeldFile } from './teaFetchStore';
import { hostOf, logTeaEvent } from './teaLog';
import { mapTeaRun, TEA_API_VERSION, TeaOutcome, TeaReason, teaResult, TeaResultBase } from './teaOutcome';
import { storeHeldSbom, TEA_SBOM_MAX_BYTES, TeaSbomStoreResult } from './teaSbomStore';
import { heldBytesOf, TEA_FILE_FLOOR_BYTES, TeaDiskBudget, TeaSlotPool, TeaSlotRefusal } from './teaSlots';
import {
    describeRefusal, TeaRequest, TeaValidation, TeaVerb, validateChunk, validateFetch, validateFetchId, validateObject,
    validateRelease, validateReleases, validateResolve, validateStore
} from './teaRequest';

/**
 * One function per TEA operation, composing the gate's sibling checks, validation, the probe, the
 * ceilings, the slots, the runner and the held fetches (TPF-180 design 3.3 to 3.9). The caller gate
 * itself runs in the resolver, before any of this.
 */

const WORK_DIR_NAME = 'work';
const DOWNLOAD_DIR_NAME = 'dl';
/** rebom kills the run's process group this long after the CLI's own deadline. */
export const TEA_KILL_MARGIN_MS = 15000;

export interface TeaConfigureOptions {
    killMarginMs?: number;
    probeRetryMs?: number;
    sbomMaxBytes?: number;
    now?: () => number;
}

interface Ready {
    config: TeaConfig;
    reads: TeaSlotPool;
    downloads: TeaSlotPool;
    results: TeaSlotPool;
    budget: TeaDiskBudget;
    fetches: TeaFetchStore;
    killMarginMs: number;
    sbomMaxBytes: number;
}

type TeaState =
    | { ready: true, value: Ready }
    | { ready: false, result: TeaResultBase, localTesting: TeaLocalTesting };

let state: TeaState = { ready: false, result: teaResult(TeaOutcome.NOT_CONFIGURED, TeaReason.CALLER_TOKEN), localTesting: TeaLocalTesting.OFF };

/**
 * <root>/work: made with mode 0700 when absent, then checked (a directory, not a link, owned by
 * this user, no group or other bits) and only then emptied. Null when the check fails; nothing is
 * removed then.
 */
function prepareWorkDir(root: string): string | null {
    const work = path.join(root, WORK_DIR_NAME);
    try {
        fs.mkdirSync(root, { recursive: true, mode: 0o700 });
        if (!fs.existsSync(work) && !isLink(work)) fs.mkdirSync(work, { mode: 0o700 });
        const stat = fs.lstatSync(work);
        const uid = typeof process.getuid === 'function' ? process.getuid() : stat.uid;
        if (!stat.isDirectory() || stat.isSymbolicLink() || stat.uid !== uid || (stat.mode & 0o077) !== 0) return null;
        for (const entry of fs.readdirSync(work)) {
            fs.rmSync(path.join(work, entry), { recursive: true, force: true });
        }
        return work;
    } catch (err) {
        logger.error({ err }, 'TEA: the scratch directory could not be prepared');
        return null;
    }
}

function isLink(p: string): boolean {
    try {
        return fs.lstatSync(p).isSymbolicLink();
    } catch {
        return false;
    }
}

/** Stops what the previous configuration runs: waiting calls, the expiry timer, held files. */
function teardown(): void {
    if (state.ready) {
        const ready = state.value;
        ready.reads.drain();
        ready.downloads.drain();
        ready.results.drain();
        ready.fetches.clearSync();
    }
}

/**
 * Builds the configuration, the pools, the budget and the scratch root from env and replaces the
 * previous state (src/index.ts calls it once with process.env; tests call it per row). The kill
 * margin, the probe's retry time and the SBOM ceiling are parameters for the tests.
 */
export function configureTea(env: NodeJS.ProcessEnv, options: TeaConfigureOptions = {}): void {
    teardown();
    resetTeaProbe(options.probeRetryMs ?? TEA_PROBE_RETRY_MS);
    setTeaWorkDir(null);
    const read = readTeaConfig(env);
    const localTesting = localTestingOf(env[TEA_LOCAL_TESTING_VARIABLE]);
    setTeaCallerToken(validCallerToken(env[TEA_CALLER_TOKEN_VARIABLE]));
    if (localTesting === TeaLocalTesting.ON) {
        logger.warn('TEA: local testing is ON: the TEA client may connect to this host and use plain http. Never in production.');
    }
    if (!read.ok) {
        logger.error({ variable: read.variable }, 'TEA: invalid setting; the TEA operations are not configured');
        state = { ready: false, result: teaResult(TeaOutcome.NOT_CONFIGURED, TeaReason.SETTING, read.variable), localTesting };
        return;
    }
    const config = read.config;
    const work = prepareWorkDir(config.tmpRoot);
    if (!work) {
        logger.error({ variable: TEA_TMP_ROOT_VARIABLE }, 'TEA: the scratch directory is unsafe; the TEA operations are not configured');
        state = { ready: false, result: teaResult(TeaOutcome.NOT_CONFIGURED, TeaReason.TMP_ROOT, TEA_TMP_ROOT_VARIABLE), localTesting };
        return;
    }
    setTeaWorkDir(work);
    const c = config.ceilings;
    const waitMs = c.queueWaitSeconds * 1000;
    const budget = new TeaDiskBudget(c.maxDiskBytes);
    const fetches = new TeaFetchStore(budget, options.now ?? Date.now);
    fetches.start();
    state = {
        ready: true,
        value: {
            config,
            reads: new TeaSlotPool(c.maxConcurrentReads, c.maxQueued, waitMs),
            downloads: new TeaSlotPool(c.maxConcurrentDownloads, c.maxQueued, waitMs),
            results: new TeaSlotPool(c.maxConcurrentResults, c.maxQueued, waitMs),
            budget,
            fetches,
            killMarginMs: options.killMarginMs ?? TEA_KILL_MARGIN_MS,
            sbomMaxBytes: options.sbomMaxBytes ?? TEA_SBOM_MAX_BYTES
        }
    };
}

/** The shut-down: kills every running process group and removes <root>/work, synchronously. */
export function shutdownTea(): void {
    killAllTeaRuns();
    if (state.ready) {
        state.value.fetches.clearSync();
        const work = path.join(state.value.config.tmpRoot, WORK_DIR_NAME);
        try {
            fs.rmSync(work, { recursive: true, force: true });
        } catch {
            // the sweep at the next start removes what is left
        }
    }
}

/** The pools and the budget, for tests and for teaStatus. */
export function teaCounters(): { reads: number, downloads: number, results: number, queued: number, diskBytes: number, heldFetches: number } {
    if (!state.ready) return { reads: 0, downloads: 0, results: 0, queued: 0, diskBytes: 0, heldFetches: 0 };
    const r = state.value;
    return {
        reads: r.reads.running,
        downloads: r.downloads.running,
        results: r.results.running,
        queued: r.reads.queued + r.downloads.queued + r.results.queued,
        diskBytes: r.budget.used,
        heldFetches: r.fetches.size
    };
}

/** The three pools of the current configuration (tests hold slots with it), or null. */
export function teaPools(): { reads: TeaSlotPool, downloads: TeaSlotPool, results: TeaSlotPool } | null {
    return state.ready ? { reads: state.value.reads, downloads: state.value.downloads, results: state.value.results } : null;
}

function invalid(field: string, rule: string): TeaResultBase {
    return teaResult(TeaOutcome.INVALID_REQUEST, TeaReason.INPUT, describeRefusal(field, rule));
}

function busy(refusal: TeaSlotRefusal | typeof TeaReason.DISK): TeaResultBase {
    return teaResult(TeaOutcome.BUSY, refusal === TeaSlotRefusal.QUEUE_FULL ? TeaReason.QUEUE_FULL : refusal === TeaReason.DISK ? TeaReason.DISK : TeaReason.SLOTS);
}

function fetchNotHeld(): TeaResultBase {
    return teaResult(TeaOutcome.FETCH_NOT_HELD, TeaReason.FETCH);
}

// ---------------------------------------------------------------------------------------------
// Results

export interface TeaDocumentResult extends TeaResultBase {
    limits: TeaEffectiveLimits | null;
    document: string | null;
}

export interface TeaFetchedFileDto {
    number: number;
    status: TeaFileStatus;
    reason: string | null;
    documentClass: TeaDocumentClass;
    sizeBytes: number | null;
    sha256: string | null;
}

export interface TeaFetchResult extends TeaResultBase {
    limits: TeaEffectiveLimits | null;
    manifest: string | null;
    files: TeaFetchedFileDto[] | null;
    fetchId: string | null;
    expiresAt: string | null;
}

export interface TeaFileChunk extends TeaResultBase {
    dataBase64: string | null;
    offset: number | null;
    nextOffset: number | null;
    fileSizeBytes: number | null;
}

export interface TeaStatusResult extends TeaResultBase {
    localTesting: TeaLocalTesting | null;
    ceilings: TeaEffectiveLimits | null;
}

function documentResult(base: TeaResultBase, limits: TeaEffectiveLimits | null = null, document: string | null = null): TeaDocumentResult {
    return { ...base, limits, document };
}

function fetchResult(base: TeaResultBase, extra: Partial<TeaFetchResult> = {}): TeaFetchResult {
    return { ...base, limits: null, manifest: null, files: null, fetchId: null, expiresAt: null, ...extra };
}

function chunkResult(base: TeaResultBase, extra: Partial<TeaFileChunk> = {}): TeaFileChunk {
    return { ...base, dataBase64: null, offset: null, nextOffset: null, fileSizeBytes: null, ...extra };
}

function storeResult(base: TeaResultBase): TeaSbomStoreResult {
    return { ...base, stored: null, format: null, bom: null, conflict: null, storeMessage: null };
}

// ---------------------------------------------------------------------------------------------
// teaStatus

export async function teaStatus(): Promise<TeaStatusResult> {
    if (!state.ready) return { ...state.result, localTesting: state.localTesting, ceilings: null };
    const ready = state.value;
    const probe = await ensureTeaCli();
    return {
        ...(probe ?? teaResult(TeaOutcome.OK)),
        localTesting: ready.config.localTesting,
        ceilings: ceilingsAsLimits(ready.config.ceilings)
    };
}

// ---------------------------------------------------------------------------------------------
// Reads

const READS = {
    [TeaVerb.RESOLVE]: { kind: TeaDocumentKind.RESOLUTION, runKind: TeaRunKind.READ },
    [TeaVerb.PRODUCT]: { kind: TeaDocumentKind.PRODUCT, runKind: TeaRunKind.READ },
    [TeaVerb.COMPONENT]: { kind: TeaDocumentKind.COMPONENT, runKind: TeaRunKind.READ },
    [TeaVerb.RELEASES]: { kind: TeaDocumentKind.RELEASE_LIST, runKind: TeaRunKind.RELEASE_LIST },
    [TeaVerb.RELEASE]: { kind: TeaDocumentKind.RELEASE, runKind: TeaRunKind.READ }
} as const;

type ReadVerb = keyof typeof READS;

async function runRead(operation: string, verb: ReadVerb, validate: (localTesting: TeaLocalTesting) => TeaValidation<TeaRequest>): Promise<TeaDocumentResult> {
    const started = Date.now();
    if (!state.ready) return documentResult(state.result);
    const ready = state.value;
    const validation = validate(ready.config.localTesting);
    if (!validation.ok) {
        const result = documentResult(invalid(validation.field, validation.rule));
        logTeaEvent({ operation, outcome: result.outcome, reason: result.error?.reason, durationMs: Date.now() - started });
        return result;
    }
    const request = validation.value;
    const log = { operation, correlationId: request.correlationId, apiHost: hostOf(request.apiRoot) };
    const probe = await ensureTeaCli();
    if (probe) {
        logTeaEvent({ ...log, outcome: probe.outcome, reason: probe.error?.reason, durationMs: Date.now() - started });
        return documentResult(probe);
    }
    const { kind, runKind } = READS[verb];
    const limits = effectiveLimits(runKind, ready.config.ceilings, request.limits, null,
        { pageSize: request.pageSize, maxPages: request.maxPages });
    const slot = await ready.reads.acquire();
    const queueWaitMs = Date.now() - started;
    if (!slot.granted) {
        const result = documentResult(busy(slot.refusal), limits);
        logTeaEvent({ ...log, outcome: result.outcome, reason: result.error?.reason, queueWaitMs });
        return result;
    }
    try {
        const { result, exitCode, stdoutBytes } = await withCallDir(async (dir) => {
            const argv = buildTeaArgv(request, limits, ready.config.localTesting);
            const run = await runTeaCli(argv, {
                env: buildTeaChildEnv(request.credential, dir),
                timeoutMs: limits.deadlineSeconds * 1000 + ready.killMarginMs,
                maxStdoutBytes: limits.maxOutputBytes + 1
            });
            const mapping = mapTeaRun(run, kind, process.env);
            const value = {
                result: documentResult({ teaApiVersion: TEA_API_VERSION, outcome: mapping.outcome, error: mapping.error },
                    limits, mapping.outcome === TeaOutcome.OK ? mapping.line ?? null : null),
                exitCode: run.code,
                stdoutBytes: Buffer.byteLength(run.stdout)
            };
            return { value, keep: false };
        });
        logTeaEvent({ ...log, outcome: result.outcome, reason: result.error?.reason, exitCode, stdoutBytes, queueWaitMs, durationMs: Date.now() - started });
        return result;
    } finally {
        slot.release();
    }
}

export function teaResolve(input: unknown): Promise<TeaDocumentResult> {
    return runRead('teaResolve', TeaVerb.RESOLVE, (lt) => validateResolve(input, lt));
}

export function teaProduct(input: unknown): Promise<TeaDocumentResult> {
    return runRead('teaProduct', TeaVerb.PRODUCT, (lt) => validateObject(input, TeaVerb.PRODUCT, lt));
}

export function teaComponent(input: unknown): Promise<TeaDocumentResult> {
    return runRead('teaComponent', TeaVerb.COMPONENT, (lt) => validateObject(input, TeaVerb.COMPONENT, lt));
}

export function teaReleases(input: unknown): Promise<TeaDocumentResult> {
    return runRead('teaReleases', TeaVerb.RELEASES, (lt) => validateReleases(input, lt));
}

export function teaRelease(input: unknown): Promise<TeaDocumentResult> {
    return runRead('teaRelease', TeaVerb.RELEASE, (lt) => validateRelease(input, lt));
}

// ---------------------------------------------------------------------------------------------
// The fetch

async function sha256Of(file: string): Promise<string> {
    const hash = createHash('sha256');
    for await (const chunk of fs.createReadStream(file)) hash.update(chunk as Buffer);
    return hash.digest('hex');
}

function countRows(files: TeaFetchedFileDto[]): Record<string, number> {
    const counts: Record<string, number> = {};
    for (const file of files) counts[file.status] = (counts[file.status] ?? 0) + 1;
    return counts;
}

export async function teaFetchCollection(input: unknown): Promise<TeaFetchResult> {
    const operation = 'teaFetchCollection';
    const started = Date.now();
    if (!state.ready) return fetchResult(state.result);
    const ready = state.value;
    const validation = validateFetch(input, ready.config.localTesting);
    if (!validation.ok) {
        const result = fetchResult(invalid(validation.field, validation.rule));
        logTeaEvent({ operation, outcome: result.outcome, reason: result.error?.reason, durationMs: Date.now() - started });
        return result;
    }
    const request = validation.value;
    const log = { operation, correlationId: request.correlationId, apiHost: hostOf(request.apiRoot) };
    const done = (result: TeaFetchResult, extra: { exitCode?: number | null, heldBytes?: number } = {}): TeaFetchResult => {
        logTeaEvent({ ...log, outcome: result.outcome, reason: result.error?.reason, durationMs: Date.now() - started,
            rowsByStatus: result.files ? countRows(result.files) : undefined, ...extra });
        return result;
    };
    const probe = await ensureTeaCli();
    if (probe) return done(fetchResult(probe));
    const limits = effectiveLimits(TeaRunKind.DOWNLOAD, ready.config.ceilings, request.limits, request.fetchLimits);
    const slot = await ready.downloads.acquire();
    if (!slot.granted) return done(fetchResult(busy(slot.refusal), { limits }));
    const reservation = limits.maxTotalBytes! + TEA_FILE_FLOOR_BYTES * limits.maxFiles!;
    let reserved = false;
    let callDir: string | null = null;
    let handedOver = false;
    try {
        if (!ready.budget.reserve(reservation)) return done(fetchResult(busy(TeaReason.DISK), { limits }));
        reserved = true;
        callDir = await makeCallDir();
        const dlDir = path.join(callDir, DOWNLOAD_DIR_NAME);
        const argv = buildTeaArgv(request, limits, ready.config.localTesting, dlDir);
        const run = await runTeaCli(argv, {
            env: buildTeaChildEnv(request.credential, callDir),
            timeoutMs: limits.deadlineSeconds * 1000 + ready.killMarginMs,
            maxStdoutBytes: limits.maxOutputBytes + 1
        });
        const mapping = mapTeaRun(run, TeaDocumentKind.DOWNLOAD_MANIFEST, process.env);
        const base: TeaResultBase = { teaApiVersion: TEA_API_VERSION, outcome: mapping.outcome, error: mapping.error };
        if ((mapping.outcome !== TeaOutcome.OK && mapping.outcome !== TeaOutcome.PARTIAL) || !mapping.doc) {
            return done(fetchResult(base, { limits }), { exitCode: run.code });
        }
        const rows = await checkTeaManifest(mapping.doc, dlDir, limits.maxArtifactBytes!);
        if (!rows) {
            const mismatch = teaResult(TeaOutcome.CONTRACT_MISMATCH, TeaReason.MANIFEST_INVALID);
            mismatch.error!.cliExitCode = run.code;
            return done(fetchResult(mismatch, { limits }), { exitCode: run.code });
        }
        const files: TeaFetchedFileDto[] = [];
        const held: TeaHeldFile[] = [];
        for (const row of rows) {
            let sha256: string | null = null;
            if (row.status === TeaFileStatus.VERIFIED) {
                const filePath = teaFilePath(dlDir, row.number);
                sha256 = await sha256Of(filePath);
                held.push({ number: row.number, path: filePath, sizeBytes: row.sizeBytes!, sha256, documentClass: row.documentClass });
            }
            files.push({ number: row.number, status: row.status, reason: row.reason, documentClass: row.documentClass,
                sizeBytes: row.sizeBytes, sha256 });
        }
        const extra: Partial<TeaFetchResult> = { limits, manifest: mapping.line ?? null, files };
        let heldBytes = 0;
        if (held.length > 0) {
            heldBytes = heldBytesOf(held.map((file) => file.sizeBytes));
            // the reservation shrinks to what is held (never more than was reserved: each held file
            // counts at most its size plus the floor); the store frees that at release or expiry
            ready.budget.free(reservation - heldBytes);
            reserved = false;
            const fetch = ready.fetches.register(callDir, held, limits.holdSeconds!, heldBytes);
            handedOver = true;
            extra.fetchId = fetch.fetchId;
            extra.expiresAt = new Date(fetch.expiresAt).toISOString();
        }
        return done(fetchResult(base, extra), { exitCode: run.code, heldBytes });
    } finally {
        if (reserved) ready.budget.free(reservation);
        if (callDir && !handedOver) await removeCallDir(callDir);
        slot.release();
    }
}

// ---------------------------------------------------------------------------------------------
// Bytes, store, release

export async function teaFetchedFileChunk(args: unknown): Promise<TeaFileChunk> {
    const operation = 'teaFetchedFileChunk';
    const started = Date.now();
    if (!state.ready) return chunkResult(state.result);
    const ready = state.value;
    const validation = validateChunk(args);
    if (!validation.ok) return chunkResult(invalid(validation.field, validation.rule));
    const request = validation.value;
    const slot = await ready.results.acquire();
    if (!slot.granted) return chunkResult(busy(slot.refusal));
    try {
        const used = await ready.fetches.use(request.fetchId, async (fetch) => {
            const file = fetch.files.get(request.number);
            if (!file) return chunkResult(teaResult(TeaOutcome.INVALID_REQUEST, TeaReason.FILE_NOT_HELD));
            if (request.offset > file.sizeBytes) return chunkResult(invalid('offset', 'at most the size of the file'));
            const length = Math.min(request.length, ready.config.ceilings.maxChunkBytes, file.sizeBytes - request.offset);
            const data = Buffer.alloc(length);
            if (length > 0) {
                const handle = await fs.promises.open(file.path, 'r');
                try {
                    let read = 0;
                    while (read < length) {
                        const { bytesRead } = await handle.read(data, read, length - read, request.offset + read);
                        if (bytesRead === 0) break;
                        read += bytesRead;
                    }
                } finally {
                    await handle.close();
                }
            }
            const end = request.offset + length;
            return chunkResult(teaResult(TeaOutcome.OK), {
                dataBase64: data.toString('base64'),
                offset: request.offset,
                nextOffset: end < file.sizeBytes ? end : null,
                fileSizeBytes: file.sizeBytes
            });
        });
        const result = used === TEA_FETCH_NOT_HELD ? chunkResult(fetchNotHeld()) : used;
        logTeaEvent({ operation, outcome: result.outcome, reason: result.error?.reason, durationMs: Date.now() - started });
        return result;
    } finally {
        slot.release();
    }
}

export async function teaStoreFetchedSbom(input: unknown): Promise<TeaSbomStoreResult> {
    const operation = 'teaStoreFetchedSbom';
    const started = Date.now();
    if (!state.ready) return storeResult(state.result);
    const ready = state.value;
    const validation = validateStore(input);
    if (!validation.ok) return storeResult(invalid(validation.field, validation.rule));
    const request = validation.value;
    const slot = await ready.results.acquire();
    if (!slot.granted) return storeResult(busy(slot.refusal));
    try {
        const used = await ready.fetches.use(request.fetchId, async (fetch) => {
            const file = fetch.files.get(request.number);
            if (!file) return storeResult(teaResult(TeaOutcome.INVALID_REQUEST, TeaReason.FILE_NOT_HELD));
            return storeHeldSbom(file, request, ready.sbomMaxBytes);
        });
        const result = used === TEA_FETCH_NOT_HELD ? storeResult(fetchNotHeld()) : used;
        logTeaEvent({ operation, outcome: result.outcome, reason: result.error?.reason, durationMs: Date.now() - started,
            org: request.org, stored: result.stored, bomUuid: result.bom?.uuid ?? null });
        return result;
    } finally {
        slot.release();
    }
}

export async function teaReleaseFetch(fetchId: unknown): Promise<TeaResultBase> {
    if (!state.ready) return state.result;
    const validation = validateFetchId(fetchId);
    if (!validation.ok) return invalid(validation.field, validation.rule);
    await state.value.fetches.release(validation.value);
    logTeaEvent({ operation: 'teaReleaseFetch', outcome: TeaOutcome.OK });
    return teaResult(TeaOutcome.OK);
}
