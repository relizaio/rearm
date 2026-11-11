import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { cleanupDirs, configure, field, gql, Http, Q, sleep, startHttp, Stub, tempDir, API_ROOT, UUID_A, NONE } from './teaTestKit';

/**
 * Knowing that the CLI on the path has the machine mode (TPF-180 design 3.9; rows T-39 to T-42).
 */

describe('TEA CLI probe', () => {
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

    const product = async () => field(await gql(http.url, Q.product, { input: { apiRoot: API_ROOT, uuid: UUID_A, credential: NONE } }));

    it('T-39 twenty first calls at once share one probe; all proceed; no further probe later', async () => {
        stub = new Stub();
        stub.behave({ stdout: '{"formatVersion":1,"kind":"TEA_PRODUCT"}\n' });
        configure({ REBOM_TEA_MAX_CONCURRENT_READS: '20' });
        const results = await Promise.all(Array.from({ length: 20 }, () => product()));
        for (const result of results) expect(result.outcome).toBe('OK');
        expect(stub.probes()).toBe(1);
        expect((await product()).outcome).toBe('OK');
        expect(field(await gql(http.url, Q.status)).outcome).toBe('OK');
        expect(stub.probes()).toBe(1);
    });

    it('T-40 no rearm, a CLI that prints help and exits 0, an error object of format version 2: NOT_AVAILABLE with the reason', async () => {
        const savedPath = process.env.PATH;
        process.env.PATH = tempDir('rebom-tea-empty-path-');
        try {
            configure();
            const missing = await product();
            expect(missing.outcome).toBe('NOT_AVAILABLE');
            expect(missing.error.reason).toBe('CLI_MISSING');
        } finally {
            process.env.PATH = savedPath;
        }
        stub = new Stub();
        stub.write('probe.exit', '0');
        stub.write('probe.stdout', 'Usage:\n  rearm tea [command]\n');
        stub.write('probe.stderr', '');
        configure();
        const old = await product();
        expect(old.outcome).toBe('NOT_AVAILABLE');
        expect(old.error.reason).toBe('CLI_TOO_OLD');
        stub.remove('probe.exit');
        stub.remove('probe.stdout');
        stub.write('probe.stderr', '{"formatVersion":2,"kind":"TEA_ERROR","exitCode":2,"reason":"USAGE"}\n');
        configure();
        const format = await product();
        expect(format.outcome).toBe('NOT_AVAILABLE');
        expect(format.error.reason).toBe('CLI_FORMAT');
        expect(stub.runs()).toEqual([]);
    });

    it('T-41 a bad probe is kept until the retry time, then tried again', async () => {
        stub = new Stub();
        stub.behave({ stdout: '{"formatVersion":1,"kind":"TEA_PRODUCT"}\n' });
        stub.write('probe.exit', '0');
        stub.write('probe.stderr', '');
        configure({}, { probeRetryMs: 1500 });
        expect((await product()).outcome).toBe('NOT_AVAILABLE');
        stub.remove('probe.exit');
        stub.remove('probe.stderr');
        expect((await product()).outcome).toBe('NOT_AVAILABLE');
        expect(stub.probes()).toBe(1);
        await sleep(1600);
        expect((await product()).outcome).toBe('OK');
        expect(stub.probes()).toBe(2);
    });

    it('T-42 real: the CLI on the path passes the probe and teaStatus is OK', async () => {
        configure();
        const status = field(await gql(http.url, Q.status));
        expect(status.outcome).toBe('OK');
        expect(status.error).toBeNull();
    });
});
