import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { cleanupDirs, configure, field, gql, Http, NONE, Q, spyLogs, startHttp, Stub, UUID_A } from './teaTestKit';
import { TeaFixtureServer, SAMPLE } from './teaFixtureServer';

/**
 * The one development setting, REBOM_TEA_LOCAL_TESTING (TPF-180 design 3.8; rows T-17 to T-21).
 */

const HTTPS_ROOT = 'https://tea.example.com/tea';
const HTTP_ROOT = 'http://127.0.0.1:8080/tea';
const TEI = `tei://tea.example.com/uuid/${UUID_A}`;

describe('TEA local testing', () => {
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

    async function product(apiRoot: string) {
        return field(await gql(http.url, Q.product, { input: { apiRoot, uuid: UUID_A, credential: NONE } }));
    }

    async function resolve(port?: number) {
        return field(await gql(http.url, Q.resolve, { input: { tei: TEI, credential: NONE, ...(port ? { localPlainHttpPort: port } : {}) } }));
    }

    /** T-17's assertions, for every value that must behave as OFF. */
    async function expectOff(value: string | undefined): Promise<void> {
        stub!.clearRuns();
        configure({ REBOM_TEA_LOCAL_TESTING: value });
        expect((await product(HTTPS_ROOT)).outcome, String(value)).toBe('OK');
        const argv = stub!.argv();
        expect(argv, String(value)).not.toContain('--allow-this-host');
        expect(argv, String(value)).not.toContain('--usehttp');
        stub!.clearRuns();
        const plain = await product(HTTP_ROOT);
        expect(plain.outcome, String(value)).toBe('INVALID_REQUEST');
        expect(plain.error.detail.startsWith('input.apiRoot:'), String(value)).toBe(true);
        const port = await resolve(8080);
        expect(port.outcome, String(value)).toBe('INVALID_REQUEST');
        expect(stub!.runs(), String(value)).toEqual([]);
    }

    it('T-17 absent: no development flag on an https root; an http root and a port are refused before any start', async () => {
        stub = new Stub();
        stub.behave({ stdout: '{"formatVersion":1,"kind":"TEA_PRODUCT"}\n' });
        await expectOff(undefined);
    });

    it('T-18 ON: --allow-this-host on every run, --usehttp for an http root and a port, --useport only with the port', async () => {
        stub = new Stub();
        configure({ REBOM_TEA_LOCAL_TESTING: 'ON' });
        stub.behave({ stdout: '{"formatVersion":1,"kind":"TEA_PRODUCT"}\n' });
        expect((await product(HTTPS_ROOT)).outcome).toBe('OK');
        let argv = stub.argv();
        expect(argv).toContain('--allow-this-host');
        expect(argv).not.toContain('--usehttp');
        expect(argv.some((a) => a.startsWith('--useport'))).toBe(false);
        stub.clearRuns();
        expect((await product(HTTP_ROOT)).outcome).toBe('OK');
        argv = stub.argv();
        expect(argv).toContain('--allow-this-host');
        expect(argv).toContain('--usehttp');
        expect(argv.some((a) => a.startsWith('--useport'))).toBe(false);
        stub.clearRuns();
        stub.behave({ stdout: '{"formatVersion":1,"kind":"TEA_RESOLUTION"}\n' });
        expect((await resolve(8080)).outcome).toBe('OK');
        argv = stub.argv();
        expect(argv).toContain('--allow-this-host');
        expect(argv).toContain('--usehttp');
        expect(argv).toContain('--useport=8080');
    });

    it('T-19 on, true, 1, " ON" and "ON " behave as OFF', async () => {
        stub = new Stub();
        stub.behave({ stdout: '{"formatVersion":1,"kind":"TEA_PRODUCT"}\n' });
        for (const value of ['on', 'true', '1', ' ON', 'ON ']) await expectOff(value);
    });

    it('T-20 real: OFF refuses a loopback root end to end, and the fixture sees no connection', async () => {
        const fixture = new TeaFixtureServer();
        await fixture.start();
        try {
            fixture.serveObjects();
            configure();
            const result = await product(`https://127.0.0.1:${fixture.port}/tea`);
            expect(result.outcome).toBe('ADDRESS_REFUSED');
            expect(result.error.addressClass).toBe('LOOPBACK');
            expect(result.error.cliExitCode).toBe(8);
            expect(fixture.connections).toBe(0);
            expect(fixture.requests).toHaveLength(0);
            // the sample uuid was never asked for
            expect(fixture.count(fixture.api(`/product/${SAMPLE.product}`))).toBe(0);
        } finally {
            await fixture.stop();
        }
    });

    it('T-21 teaStatus reports the setting; one warn line at start under ON and none under OFF', async () => {
        stub = new Stub();
        const logs = spyLogs();
        try {
            configure();
            expect(logs.text()).not.toContain('local testing is ON');
            expect(field(await gql(http.url, Q.status)).localTesting).toBe('OFF');
            configure({ REBOM_TEA_LOCAL_TESTING: 'ON' });
            const warns = logs.calls().filter((call) => call.some((arg) => typeof arg === 'string' && arg.includes('local testing is ON')));
            expect(warns).toHaveLength(1);
            expect(field(await gql(http.url, Q.status)).localTesting).toBe('ON');
        } finally {
            logs.restore();
        }
    });
});
