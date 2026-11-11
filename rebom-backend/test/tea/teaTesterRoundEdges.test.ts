import * as fs from 'fs';
import * as path from 'path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../src/services/tea/teaCliRunner', async (importOriginal) => {
    const actual = await importOriginal<typeof import('../../src/services/tea/teaCliRunner')>();
    return { ...actual, runTeaCli: vi.fn(actual.runTeaCli) };
});

import { logger } from '../../src/logger';
import { buildTeaChildEnv } from '../../src/services/tea/teaChildEnv';
import { ensureTeaCli, resetTeaProbe } from '../../src/services/tea/teaCliProbe';
import { runTeaCli, setTeaWorkDir } from '../../src/services/tea/teaCliRunner';
import { checkTeaManifest, teaFileName } from '../../src/services/tea/teaDocument';
import { TeaLocalTesting } from '../../src/services/tea/teaConfig';
import { TeaVerb, validateObject } from '../../src/services/tea/teaRequest';
import {
    configureTea, shutdownTea, teaComponent, teaCounters, teaFetchCollection, teaFetchedFileChunk, teaPools, teaRelease, teaResolve
} from '../../src/services/tea/teaService';
import { API_ROOT, cleanupDirs, configure, isAlive, NONE, sleep, Stub, tempDir, UUID_A, UUID_B } from './teaTestKit';

/**
 * The first run of the mutant tester on TPF-189 (T-1 to T-10): the rows of a manifest that are
 * links or directories of the right size, the owner of the work directory, the trust store
 * variables, a uuid in upper case, the environment of the probe, a stdout overflow of a child with
 * a child of its own, the limits of the other read verbs, the two bounds of an artifact, the
 * runner timeout, the results counter, the file handles of the chunk reader, and input hygiene.
 */

const mRun = vi.mocked(runTeaCli);
const OFF = TeaLocalTesting.OFF;
let stub: Stub;

beforeEach(() => {
    stub = new Stub();
    mRun.mockClear();
});

afterEach(() => {
    shutdownTea();
    setTeaWorkDir(null);
    stub.restore();
    vi.restoreAllMocks();
    cleanupDirs();
});

function row(number: number, size: number): Record<string, unknown> {
    return { number, status: 'VERIFIED', fileName: teaFileName(number), sizeBytes: size, artifactType: 'BOM', reason: null };
}

function dlDir(): string {
    const dir = path.join(tempDir('rebom-t1-'), 'dl');
    fs.mkdirSync(dir);
    return dir;
}

describe('T-1: what a verified row may point at', () => {
    it('a symbolic link whose own size equals the size of the row is refused', async () => {
        const dir = dlDir();
        const target = '/nowhere/x1';
        fs.symlinkSync(target, path.join(dir, teaFileName(1)));
        expect(fs.lstatSync(path.join(dir, teaFileName(1))).size).toBe(target.length);
        expect(await checkTeaManifest({ files: [row(1, target.length)] }, dir, 1000)).toBeNull();
        const real = path.join(tempDir('rebom-t1-real-'), 'real');
        fs.writeFileSync(real, 'x'.repeat(target.length));
        const dir2 = dlDir();
        fs.symlinkSync(real, path.join(dir2, teaFileName(1)));
        expect(await checkTeaManifest({ files: [row(1, target.length)] }, dir2, 1000)).toBeNull();
    });

    it('a directory whose own size equals the size of the row is refused', async () => {
        const dir = dlDir();
        fs.mkdirSync(path.join(dir, teaFileName(1)));
        const size = fs.lstatSync(path.join(dir, teaFileName(1))).size;
        expect(size).toBeGreaterThan(0);
        expect(await checkTeaManifest({ files: [row(1, size)] }, dir, size + 1000)).toBeNull();
    });

    it('a download directory that is a link to a directory is refused, with the right file in it', async () => {
        const real = dlDir();
        fs.writeFileSync(path.join(real, teaFileName(1)), 'abc');
        expect(await checkTeaManifest({ files: [row(1, 3)] }, real, 1000)).toHaveLength(1);
        const link = path.join(tempDir('rebom-t1-link-'), 'dl');
        fs.symlinkSync(real, link);
        expect(await checkTeaManifest({ files: [row(1, 3)] }, link, 1000)).toBeNull();
        expect(await checkTeaManifest({ files: [{ number: 1, status: 'NOT_SELECTED' }] }, link, 1000)).toBeNull();
    });
});

describe('T-2: the owner of the work directory', () => {
    it('a work directory owned by another user is refused, nothing in it is removed, and the operations are not configured', async () => {
        const errors = vi.spyOn(logger, 'error').mockImplementation(() => undefined as never);
        const root = path.join(tempDir('rebom-t2-'), 'root');
        fs.mkdirSync(path.join(root, 'work'), { recursive: true, mode: 0o700 });
        fs.chmodSync(path.join(root, 'work'), 0o700);
        fs.writeFileSync(path.join(root, 'work', 'keep'), 'x');
        const own = process.getuid!();
        vi.spyOn(process, 'getuid').mockReturnValue(own + 1);
        configureTea({ REBOM_TEA_TMP_ROOT: root } as any);
        expect(errors.mock.calls.at(-1)).toEqual([{ variable: 'REBOM_TEA_TMP_ROOT' }, 'TEA: the scratch directory is unsafe; the TEA operations are not configured']);
        expect(fs.existsSync(path.join(root, 'work', 'keep'))).toBe(true);
        vi.mocked(process.getuid!).mockReturnValue(own);
        configureTea({ REBOM_TEA_TMP_ROOT: root } as any);
        expect(fs.existsSync(path.join(root, 'work', 'keep'))).toBe(false);
    });
});

describe('T-3: the environment of a run', () => {
    it('is the path, the two trust store variables, and the call directory as home and temporary directory, nothing else', () => {
        const env = buildTeaChildEnv(null, '/tmp/call', { PATH: '/bin', SSL_CERT_FILE: '/ca.pem', SSL_CERT_DIR: '/cas', SECRET: 's', POSTGRES_PASSWORD: 'p', HOME: '/root' });
        expect(env).toEqual({ PATH: '/bin', SSL_CERT_FILE: '/ca.pem', SSL_CERT_DIR: '/cas', HOME: '/tmp/call', TMPDIR: '/tmp/call' });
    });

    it('leaves out a variable that is not set instead of setting it to undefined', () => {
        const env = buildTeaChildEnv(null, '/tmp/call', { PATH: '/bin' });
        expect(Object.keys(env).sort()).toEqual(['HOME', 'PATH', 'TMPDIR']);
    });
});

describe('T-4: a uuid in upper case', () => {
    it('is accepted and passed on in lower case, whatever letters it holds', () => {
        const upper = 'ABCDEF12-3456-4ABC-8DEF-0123456789AB';
        const ok = validateObject({ apiRoot: API_ROOT, uuid: upper, credential: NONE }, TeaVerb.PRODUCT, OFF);
        expect(ok.ok && ok.value.uuid).toBe('abcdef12-3456-4abc-8def-0123456789ab');
        const mixed = validateObject({ apiRoot: API_ROOT, uuid: 'aBcDeF12-3456-4aBc-8dEf-0123456789aB', credential: NONE }, TeaVerb.COMPONENT, OFF);
        expect(mixed.ok && mixed.value.uuid).toBe('abcdef12-3456-4abc-8def-0123456789ab');
    });
});

describe('T-5: the environment of the probe', () => {
    it('is the one of a run, with none of rebom\'s own secrets', async () => {
        process.env.POSTGRES_PASSWORD = 'pg-secret-for-the-probe';
        try {
            configure();
            mRun.mockClear();
            resetTeaProbe();
            expect(await ensureTeaCli()).toBeNull();
            const env = mRun.mock.calls[0][1].env;
            expect(Object.keys(env).sort()).toEqual(['HOME', 'PATH', 'TMPDIR'].concat(Object.keys(env).filter((k) => k.startsWith('SSL_'))).sort());
            expect(JSON.stringify(env)).not.toContain('pg-secret-for-the-probe');
            expect(env.POSTGRES_PASSWORD).toBeUndefined();
        } finally {
            delete process.env.POSTGRES_PASSWORD;
        }
    });
});

describe('T-6: a stdout overflow of a child that has a child', () => {
    it('kills the whole group at once: the run ends long before its deadline and nothing of it stays alive', async () => {
        stub.behave({ sleep: 1, child: true, stdout: 'x'.repeat(200) });
        const started = Date.now();
        const result = await runTeaCli(['x'], { env: { PATH: process.env.PATH }, timeoutMs: 30000, maxStdoutBytes: 50 });
        const elapsed = Date.now() - started;
        expect(result).toMatchObject({ stdoutOverflow: true, timedOut: false });
        expect(elapsed).toBeLessThan(8000);
        const child = stub.readPid('child.pid');
        for (let i = 0; i < 40 && isAlive(child); i++) await sleep(50);
        expect(isAlive(child)).toBe(false);
    }, 40000);
});

describe('T-7: the other read verbs', () => {
    it('run as reads: the deadline, no download limits, the deadline in the arguments', async () => {
        stub.behave({ stdout: '{"formatVersion":1,"kind":"TEA_PRODUCT"}\n' });
        configure();
        const calls: Array<[string, () => Promise<any>]> = [
            ['resolve', () => teaResolve({ tei: `tei://tea.example.com/uuid/${UUID_A}`, credential: NONE })],
            ['component', () => teaComponent({ apiRoot: API_ROOT, uuid: UUID_A, credential: NONE })],
            ['release', () => teaRelease({ apiRoot: API_ROOT, releaseKind: 'PRODUCT_RELEASE', releaseUuid: UUID_B, credential: NONE })]
        ];
        for (const [name, call] of calls) {
            mRun.mockClear();
            const result = await call();
            expect(result.limits, name).toMatchObject({ deadlineSeconds: 60, maxArtifactBytes: null, maxTotalBytes: null, maxFiles: null, holdSeconds: null });
            const argv = mRun.mock.calls.find((c) => c[0].length > 2)![0];
            expect(argv, name).toContain('--deadline=60s');
            expect(argv.some((a) => a.startsWith('--max-files') || a.startsWith('--dir=')), name).toBe(false);
        }
    });
});

describe('T-8: the two bounds of an artifact', () => {
    it('a file above the artifact bound is refused although it is within the total bound', async () => {
        const content = 'x'.repeat(20);
        stub.downloadDir({ [teaFileName(1)]: content });
        stub.behave({ exit: 0, stdout: JSON.stringify({ formatVersion: 1, kind: 'TEA_DOWNLOAD_MANIFEST', files: [row(1, 20)] }) + '\n' });
        configure();
        const result = await teaFetchCollection({
            apiRoot: API_ROOT, releaseKind: 'COMPONENT_RELEASE', releaseUuid: UUID_B, collectionVersion: 1, documentClasses: ['SBOM'], credential: NONE,
            fetchLimits: { maxArtifactBytes: 10, maxTotalBytes: 1000 }
        });
        expect(result).toMatchObject({ outcome: 'CONTRACT_MISMATCH', error: { reason: 'MANIFEST_INVALID' }, fetchId: null });
        expect(teaCounters()).toMatchObject({ diskBytes: 0, heldFetches: 0 });
    });
});

describe('T-9: the runner timeout, the results counter and the file handles', () => {
    it('the runner ends a run at the timeout it was given, not at twice that', async () => {
        stub.behave({ sleep: 30 });
        const started = Date.now();
        const result = await runTeaCli(['x'], { env: { PATH: process.env.PATH }, timeoutMs: 1000, maxStdoutBytes: 1000 });
        expect(result.timedOut).toBe(true);
        expect(Date.now() - started).toBeLessThan(1800);
    }, 20000);

    it('the service gives the runner the deadline of the request plus the margin, exactly', async () => {
        stub.behave({ stdout: '{"formatVersion":1,"kind":"TEA_PRODUCT"}\n' });
        configure({}, { killMarginMs: 250 });
        mRun.mockClear();
        await teaComponent({ apiRoot: API_ROOT, uuid: UUID_A, credential: NONE, limits: { deadlineSeconds: 7 } });
        expect(mRun.mock.calls.find((c) => c[0].length > 2)![1].timeoutMs).toBe(7250);
    });

    it('a held results slot is counted as a result and as nothing else', async () => {
        configure();
        const slot = await teaPools()!.results.acquire();
        expect(teaCounters()).toMatchObject({ reads: 0, downloads: 0, results: 1 });
        if (slot.granted) slot.release();
        const read = await teaPools()!.reads.acquire();
        expect(teaCounters()).toMatchObject({ reads: 1, downloads: 0, results: 0 });
        if (read.granted) read.release();
        const download = await teaPools()!.downloads.acquire();
        expect(teaCounters()).toMatchObject({ reads: 0, downloads: 1, results: 0 });
        if (download.granted) download.release();
    });

    it.skipIf(!fs.existsSync('/proc/self/fd'))('reading a held file in many chunks leaves no file handle open', async () => {
        const content = '0123456789'.repeat(20);
        stub.downloadDir({ [teaFileName(1)]: content });
        stub.behave({ exit: 0, stdout: JSON.stringify({ formatVersion: 1, kind: 'TEA_DOWNLOAD_MANIFEST', files: [row(1, content.length)] }) + '\n' });
        configure();
        const fetched = await teaFetchCollection({
            apiRoot: API_ROOT, releaseKind: 'COMPONENT_RELEASE', releaseUuid: UUID_B, collectionVersion: 1, documentClasses: ['SBOM'], credential: NONE
        });
        expect(fetched.outcome).toBe('OK');
        const before = fs.readdirSync('/proc/self/fd').length;
        for (let i = 0; i < 100; i++) {
            const chunk = await teaFetchedFileChunk({ fetchId: fetched.fetchId, number: 1, offset: i, length: 5 });
            expect(chunk.outcome).toBe('OK');
        }
        expect(fs.readdirSync('/proc/self/fd').length - before).toBeLessThan(20);
    });
});

describe('T-10: input hygiene', () => {
    it('a DEL character in an address is refused, like any other control character', () => {
        for (const apiRoot of ['https://tea.example.com/t\x7fea', 'https://tea.example.com/t\x01ea', 'https://tea.example.com/t\x1fea']) {
            expect(validateObject({ apiRoot, uuid: UUID_A, credential: NONE }, TeaVerb.PRODUCT, OFF).ok, JSON.stringify(apiRoot)).toBe(false);
        }
        expect(validateObject({ apiRoot: 'https://tea.example.com/t~ea', uuid: UUID_A, credential: NONE }, TeaVerb.PRODUCT, OFF).ok).toBe(true);
    });

    it('a field the kind does not allow is refused when it is the empty string as well', () => {
        for (const name of ['authRoot', 'token', 'keyId', 'keySecret']) {
            const result = validateObject({ apiRoot: API_ROOT, uuid: UUID_A, credential: { kind: 'NONE', [name]: '' } }, TeaVerb.PRODUCT, OFF);
            expect(result, name).toMatchObject({ ok: false, field: `input.credential.${name}` });
        }
    });
});
