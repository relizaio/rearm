import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import * as fs from 'fs';
import * as path from 'path';

// T-49 needs the spawn utils.ts captures at load: patched before any import runs.
const spawnCalls = vi.hoisted(() => {
    const cp = (process as any).getBuiltinModule('node:child_process');
    const original = cp.spawn;
    const calls: unknown[][] = [];
    cp.spawn = (...args: unknown[]) => {
        calls.push(args);
        return original(...args);
    };
    return calls;
});

import { shellExecDetailed } from '../../src/utils';
import { configureTea, shutdownTea } from '../../src/services/tea/teaService';
import { runningTeaGroups } from '../../src/services/tea/teaCliRunner';
import {
    API_ROOT, cleanupDirs, configure, field, gql, Http, isAlive, NONE, processGroupOf, Q, sleep, startHttp, Stub, tempDir, TOKEN, UUID_A, UUID_B,
    workDirOf
} from './teaTestKit';

/**
 * Ending a run, removal of its directory, the scratch root (TPF-180 design 3.5; rows T-43 to T-49).
 */

const PRODUCT_DOC = '{"formatVersion":1,"kind":"TEA_PRODUCT"}\n';

describe('TEA runner', () => {
    let http: Http;
    let stub: Stub | undefined;

    beforeAll(async () => {
        http = await startHttp();
    });

    afterAll(async () => {
        await http.server.stop();
    });

    afterEach(() => {
        stub?.restore();
        stub = undefined;
        cleanupDirs();
    });

    const product = async (limits?: Record<string, number>) =>
        field(await gql(http.url, Q.product, { input: { apiRoot: API_ROOT, uuid: UUID_A, credential: NONE, ...(limits ? { limits } : {}) } }));

    async function waitFor(condition: () => boolean, ms = 5000): Promise<void> {
        for (let waited = 0; waited < ms && !condition(); waited += 20) await sleep(20);
    }

    it('T-43 at the deadline plus the margin the whole process group is killed: TIMEOUT, child gone, directory gone', async () => {
        stub = new Stub();
        stub.behave({ sleep: 60, child: true });
        const root = configure({}, { killMarginMs: 300 });
        const started = Date.now();
        const pending = product({ deadlineSeconds: 1 });
        // while it runs: the stub and its child are one process group of their own, the stub its
        // leader, read from /proc (the builder image's busybox ps has no -p)
        const childPidFile = path.join(stub.dir, 'child.pid');
        await waitFor(() => fs.existsSync(childPidFile) && fs.readFileSync(childPidFile, 'utf8').trim() !== '');
        const stubPid = stub.readPid('pid');
        const childPid = stub.readPid('child.pid');
        expect(processGroupOf(stubPid)).toBe(stubPid);
        expect(processGroupOf(childPid)).toBe(stubPid);
        expect(processGroupOf(process.pid)).not.toBe(stubPid);
        const result = await pending;
        expect(Date.now() - started).toBeLessThan(10000);
        expect(result.outcome).toBe('TIMEOUT');
        expect(result.error.reason).toBe('KILLED_AT_DEADLINE');
        await waitFor(() => !isAlive(stub!.readPid('child.pid')));
        expect(isAlive(stub.readPid('pid'))).toBe(false);
        expect(isAlive(stub.readPid('child.pid'))).toBe(false);
        expect(fs.readdirSync(workDirOf(root))).toEqual([]);
    });

    it('T-44 the per-call directory is removed after an OK read, every failure, a start failure, a timeout, an overflow, an internal error', async () => {
        stub = new Stub();
        const root = configure({}, { killMarginMs: 200 });
        const work = workDirOf(root);
        const cases: Array<[string, () => void, Record<string, number>?]> = [
            ['OK', () => stub!.behave({ stdout: PRODUCT_DOC })],
            ...[1, 2, 3, 4, 5, 6, 8, 9, 10, 11].map((code) => [`exit ${code}`, () => stub!.behave({ exit: code, stderr: `{"formatVersion":1,"kind":"TEA_ERROR","exitCode":${code},"reason":"X"}\n` })] as [string, () => void]),
            ['timeout', () => stub!.behave({ sleep: 30 }), { deadlineSeconds: 1 }],
            ['overflow', () => stub!.behave({ stdout: `{"formatVersion":1,"kind":"TEA_PRODUCT","pad":"${'x'.repeat(70000)}"}\n` }), { maxOutputBytes: 65536 }]
        ];
        for (const [label, arrange, limits] of cases) {
            arrange();
            await product(limits);
            expect(fs.readdirSync(work), label).toEqual([]);
        }
        // an internal error after the directory was made: reading the download directory fails
        stub.downloadDir({ '000001.bin': 'abc' });
        const readdir = vi.spyOn(fs.promises, 'readdir').mockRejectedValueOnce(new Error('disk gone'));
        stub.behave({ stdout: '{"formatVersion":1,"kind":"TEA_DOWNLOAD_MANIFEST","files":[{"number":1,"status":"VERIFIED","fileName":"000001.bin","sizeBytes":3,"artifactType":"BOM"}]}\n' });
        const internal = field(await gql(http.url, Q.fetch, { input: { apiRoot: API_ROOT, releaseKind: 'COMPONENT_RELEASE', releaseUuid: UUID_B, collectionVersion: 1, documentClasses: ['SBOM'], credential: NONE } }));
        expect(internal.outcome).toBe('FAILED');
        expect(internal.error.reason).toBe('INTERNAL');
        readdir.mockRestore();
        expect(fs.readdirSync(work)).toEqual([]);
        // a start failure: the probe passed, then the CLI is gone from the path
        fs.rmSync(path.join(stub.dir, 'rearm'));
        const savedPath = process.env.PATH;
        process.env.PATH = stub.dir;
        const missing = await product().finally(() => {
            process.env.PATH = savedPath;
        });
        expect(missing.outcome).toBe('NOT_AVAILABLE');
        expect(missing.error.reason).toBe('CLI_MISSING');
        expect(fs.readdirSync(work)).toEqual([]);
    });

    it('T-45 the scratch directories are 0700; --dir is inside the per-call directory and does not exist yet', async () => {
        stub = new Stub();
        stub.behave({ sleep: 2, stdout: '{"formatVersion":1,"kind":"TEA_DOWNLOAD_MANIFEST","files":[]}\n' });
        const root = configure();
        const work = workDirOf(root);
        const call = gql(http.url, Q.fetch, { input: { apiRoot: API_ROOT, releaseKind: 'COMPONENT_RELEASE', releaseUuid: UUID_B, collectionVersion: 1, documentClasses: ['SBOM'], credential: NONE } });
        await waitFor(() => stub!.runs().length > 0);
        await waitFor(() => fs.existsSync(path.join(stub!.dir, 'runs', stub!.runs()[0], 'env')));
        const dirArg = stub.argv().find((a) => a.startsWith('--dir='))!.substring('--dir='.length);
        const callDir = path.dirname(dirArg);
        expect(path.dirname(callDir)).toBe(work);
        expect(path.basename(callDir).startsWith('f-')).toBe(true);
        expect(fs.statSync(work).mode & 0o777).toBe(0o700);
        expect(fs.statSync(callDir).mode & 0o777).toBe(0o700);
        expect(fs.existsSync(dirArg)).toBe(false);
        expect(field(await call).outcome).toBe('OK');
    });

    it('T-46 a work directory that is a link or open to others is refused (TMP_ROOT) and nothing in it is removed', async () => {
        stub = new Stub();
        const base = tempDir('rebom-tea-unsafe-');
        const target = path.join(base, 'target');
        fs.mkdirSync(target);
        fs.writeFileSync(path.join(target, 'keep'), 'x');
        const linkRoot = path.join(base, 'link-root');
        fs.mkdirSync(linkRoot);
        fs.symlinkSync(target, path.join(linkRoot, 'work'));
        configureTea({ REBOM_TEA_CALLER_TOKEN: TOKEN, REBOM_TEA_TMP_ROOT: linkRoot });
        let status = field(await gql(http.url, Q.status));
        expect(status.outcome).toBe('NOT_CONFIGURED');
        expect(status.error.reason).toBe('TMP_ROOT');
        expect(fs.existsSync(path.join(target, 'keep'))).toBe(true);

        const openRoot = path.join(base, 'open-root');
        fs.mkdirSync(path.join(openRoot, 'work'), { recursive: true });
        fs.chmodSync(path.join(openRoot, 'work'), 0o755);
        fs.writeFileSync(path.join(openRoot, 'work', 'keep'), 'x');
        configureTea({ REBOM_TEA_CALLER_TOKEN: TOKEN, REBOM_TEA_TMP_ROOT: openRoot });
        status = field(await gql(http.url, Q.status));
        expect(status.outcome).toBe('NOT_CONFIGURED');
        expect(status.error.reason).toBe('TMP_ROOT');
        expect(fs.existsSync(path.join(openRoot, 'work', 'keep'))).toBe(true);
        expect(stub.runs()).toEqual([]);
    });

    it('T-47 the sweep removes what is left in work and leaves what is beside it', () => {
        const root = path.join(tempDir('rebom-tea-sweep-'), 'root');
        fs.mkdirSync(path.join(root, 'work', 'f-old', 'dl'), { recursive: true, mode: 0o700 });
        fs.chmodSync(path.join(root, 'work'), 0o700);
        fs.writeFileSync(path.join(root, 'work', 'f-old', 'dl', '000001.bin'), 'x');
        fs.writeFileSync(path.join(root, 'work', 'stray'), 'x');
        fs.writeFileSync(path.join(root, 'beside'), 'x');
        configureTea({ REBOM_TEA_CALLER_TOKEN: TOKEN, REBOM_TEA_TMP_ROOT: root });
        expect(fs.readdirSync(path.join(root, 'work'))).toEqual([]);
        expect(fs.existsSync(path.join(root, 'beside'))).toBe(true);
    });

    it('T-48 the shut-down kills every running group and removes work before it returns', async () => {
        stub = new Stub();
        stub.behave({ sleep: 60, child: true });
        const root = configure();
        const call = product();
        await waitFor(() => runningTeaGroups().length > 0 && fs.existsSync(path.join(stub!.dir, 'child.pid')));
        const pid = stub.readPid('pid');
        const child = stub.readPid('child.pid');
        shutdownTea();
        expect(fs.existsSync(workDirOf(root))).toBe(false);
        await waitFor(() => !isAlive(pid) && !isAlive(child), 2000);
        expect(isAlive(pid)).toBe(false);
        expect(isAlive(child)).toBe(false);
        await call;
    });

    it('T-49 without the new options shellExecDetailed spawns exactly as before', async () => {
        spawnCalls.length = 0;
        const result = await shellExecDetailed(process.execPath, ['-e', 'process.stdout.write("x")'], { timeoutMs: 10000, maxStdoutBytes: 100 });
        expect(result.stdout).toBe('x');
        expect(spawnCalls).toHaveLength(1);
        expect(spawnCalls[0][2]).toEqual({ stdio: ['ignore', 'pipe', 'pipe'] });
    });
});
