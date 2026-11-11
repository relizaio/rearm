import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import * as fs from 'fs';
import {
    API_ROOT, cleanupDirs, configure, field, gql, Http, isAlive, LogSpy, MARKERS, MARK_KEY, MARK_TOKEN, Q, spyLogs, startHttp, Stub,
    UUID_A, UUID_B, workDirOf
} from './teaTestKit';

/**
 * Every exit code and every failure of rebom's own (TPF-180 design 3.3, 3.4; rows T-28 to T-38,
 * and T-24 for each of them: the calls carry marker credentials, which must appear in no result and
 * no log line).
 */

const BEARER = { kind: 'BEARER_TOKEN', authRoot: API_ROOT, token: MARK_TOKEN };
const PRODUCT_VARS = { input: { apiRoot: API_ROOT, uuid: UUID_A, credential: BEARER } };
const RELEASE_VARS = { input: { apiRoot: API_ROOT, releaseKind: 'PRODUCT_RELEASE', releaseUuid: UUID_A, credential: BEARER } };
const FETCH_VARS = { input: { apiRoot: API_ROOT, releaseKind: 'COMPONENT_RELEASE', releaseUuid: UUID_B, collectionVersion: 1, documentClasses: ['SBOM', 'VEX', 'OTHER'], credential: { kind: 'API_KEY', authRoot: API_ROOT, keyId: MARK_KEY, keySecret: MARK_KEY } } };
const PRODUCT_DOC = '{"formatVersion":1,"kind":"TEA_PRODUCT","server":{"rootUrl":"https://tea.example.com/tea","apiVersion":"1.0.0"},"product":{"uuid":"33333333-3333-4333-8333-333333333333","name":"P","identifiers":[]}}';

function errorLine(exitCode: number, reason: string, extra: Record<string, unknown> = {}): string {
    return JSON.stringify({
        formatVersion: 1, kind: 'TEA_ERROR', exitCode, reason, message: `the CLI says ${reason}`, url: 'https://tea.example.com/tea/v1.0.0/product/x',
        httpStatus: null, address: null, addressClass: null, phase: null, hint: null, ignoredProxyVariables: [], ...extra
    }) + '\n';
}

function manifestLine(files: unknown[]): string {
    return JSON.stringify({ formatVersion: 1, kind: 'TEA_DOWNLOAD_MANIFEST', files, stoppedBy: 'END' }) + '\n';
}

describe('TEA outcomes', () => {
    let http: Http;
    let stub: Stub;
    let logs: LogSpy;
    let answers: string[];

    beforeAll(async () => {
        http = await startHttp();
    });

    afterAll(async () => {
        await http.server.stop();
    });

    afterEach(() => {
        // T-24: no marker credential in any result or log line of the row
        const logged = logs.text();
        logs.restore();
        for (const marker of MARKERS) {
            expect(logged).not.toContain(marker);
            for (const answer of answers) expect(answer).not.toContain(marker);
        }
        expect(Object.keys(process.env).filter((name) => name.startsWith('REARM_TEA_'))).toEqual([]);
        stub?.restore();
        cleanupDirs();
    });

    function begin(): string {
        stub = new Stub();
        logs = spyLogs();
        answers = [];
        return configure();
    }

    async function call(query: string, variables: Record<string, unknown>): Promise<any> {
        const answer = await gql(http.url, query, variables);
        answers.push(answer.text);
        return field(answer);
    }

    it('T-28 exit codes 0 to 10, each with its error object: the outcome and reason of the table, the fields copied', async () => {
        begin();
        const rows: Array<[number, string, string, Record<string, unknown>]> = [
            [1, 'FAILED', 'SERVER_ERROR', { httpStatus: 503 }],
            [2, 'INVALID_REQUEST', 'USAGE', {}],
            [3, 'NOT_FOUND', 'NOT_FOUND', { httpStatus: 404 }],
            [4, 'FAILED', 'EXIT_CODE', {}],
            [5, 'URL_REFUSED', 'URL_REFUSED', {}],
            [6, 'AUTH', 'AUTH', { httpStatus: 401 }],
            [8, 'ADDRESS_REFUSED', 'ADDRESS_REFUSED', { address: '10.0.0.1', addressClass: 'OWN_ADDRESS' }],
            [9, 'LIMIT', 'DEADLINE', {}],
            [10, 'CONNECT_FAILED', 'CONNECT_FAILED', { phase: 'CONNECT', hint: 'the network policy of the deployment may block this address' }]
        ];
        stub.behave({ stdout: PRODUCT_DOC + '\n' });
        const ok = await call(Q.product, PRODUCT_VARS);
        expect(ok.outcome).toBe('OK');
        expect(ok.error).toBeNull();
        expect(ok.document).toBe(PRODUCT_DOC);
        for (const [code, outcome, reason, extra] of rows) {
            stub.behave({ exit: code, stderr: errorLine(code, code === 4 ? 'FAILED' : reason, extra) });
            const result = await call(Q.product, PRODUCT_VARS);
            expect(result.outcome, String(code)).toBe(outcome);
            expect(result.error.reason, String(code)).toBe(reason);
            expect(result.error.cliExitCode, String(code)).toBe(code);
            expect(result.error.detail, String(code)).toBe(`the CLI says ${code === 4 ? 'FAILED' : reason}`);
            expect(result.error.url, String(code)).toBe('https://tea.example.com/tea/v1.0.0/product/x');
            expect(result.document, String(code)).toBeNull();
            for (const [key, value] of Object.entries(extra)) expect(result.error[key], `${code} ${key}`).toBe(value);
        }
        // 7 on download: a valid manifest and its files
        stub.downloadDir({ '000001.bin': 'abc' });
        stub.behave({ exit: 7, stdout: manifestLine([
            { number: 1, status: 'VERIFIED', fileName: '000001.bin', sizeBytes: 3, artifactType: 'BOM', reason: null },
            { number: 2, status: 'FAILED', fileName: null, sizeBytes: null, artifactType: 'BOM', reason: 'CHECKSUM_MISMATCH' }
        ]) });
        const partial = await call(Q.fetch, FETCH_VARS);
        expect(partial.outcome).toBe('PARTIAL');
        expect(partial.error.cliExitCode).toBe(7);
        expect(partial.files.map((f: any) => f.status)).toEqual(['VERIFIED', 'FAILED']);
        expect(partial.fetchId).toMatch(/^[0-9a-f]{32}$/);
    });

    it('T-29 exit codes 11, 127 and 255 are FAILED EXIT_CODE with the code', async () => {
        begin();
        for (const code of [11, 127, 255]) {
            stub.behave({ exit: code, stderr: errorLine(code, 'SOMETHING') });
            const result = await call(Q.product, PRODUCT_VARS);
            expect(result.outcome).toBe('FAILED');
            expect(result.error.reason).toBe('EXIT_CODE');
            expect(result.error.cliExitCode).toBe(code);
        }
    });

    it('T-30 exit 6 with no, bad, mismatched, two-line or huge standard error: AUTH; the real exit code decides; the text is nowhere', async () => {
        begin();
        const cases: Array<[string, string, string]> = [
            ['no standard error', '', 'NO_ERROR_OBJECT'],
            ['not JSON', `garbage ${MARK_TOKEN}\n`, 'NO_ERROR_OBJECT'],
            ['exitCode 3', errorLine(3, 'NOT_FOUND', { message: `x ${MARK_TOKEN}` }), 'NO_ERROR_OBJECT'],
            ['two lines', `trace line ${MARK_TOKEN}\n${errorLine(6, 'AUTH')}`, 'AUTH'],
            ['300000 bytes', `${MARK_TOKEN}${'x'.repeat(300000)}\n${errorLine(6, 'AUTH')}`, 'NO_ERROR_OBJECT']
        ];
        for (const [label, stderr, reason] of cases) {
            stub.behave({ exit: 6, stderr });
            const result = await call(Q.product, PRODUCT_VARS);
            expect(result.outcome, label).toBe('AUTH');
            expect(result.error.reason, label).toBe(reason);
            expect(result.error.cliExitCode, label).toBe(6);
        }
    });

    it('T-31 exit 0 with output that is not one document of the expected kind is CONTRACT_MISMATCH', async () => {
        begin();
        const cases: Array<[string, string, string]> = [
            ['nothing', '', 'OUTPUT_NOT_JSON'],
            ['two lines', `${PRODUCT_DOC}\n${PRODUCT_DOC}\n`, 'OUTPUT_NOT_JSON'],
            ['text', 'hello\n', 'OUTPUT_NOT_JSON'],
            ['an array', '[1,2]\n', 'OUTPUT_NOT_JSON'],
            ['formatVersion 2', PRODUCT_DOC.replace('"formatVersion":1', '"formatVersion":2') + '\n', 'FORMAT_VERSION'],
            ['formatVersion "1"', PRODUCT_DOC.replace('"formatVersion":1', '"formatVersion":"1"') + '\n', 'FORMAT_VERSION'],
            ['another kind', PRODUCT_DOC.replace('TEA_PRODUCT', 'TEA_COMPONENT') + '\n', 'KIND']
        ];
        for (const [label, stdout, reason] of cases) {
            stub.behave({ stdout });
            const result = await call(Q.product, PRODUCT_VARS);
            expect(result.outcome, label).toBe('CONTRACT_MISMATCH');
            expect(result.error.reason, label).toBe(reason);
            expect(result.document, label).toBeNull();
        }
    });

    it('T-32 standard output one byte over the bound kills the run; exactly at the bound is OK', async () => {
        begin();
        const max = 65536;
        const doc = (bytes: number) => {
            const head = '{"formatVersion":1,"kind":"TEA_PRODUCT","pad":"';
            return head + 'x'.repeat(bytes - head.length - 2) + '"}';
        };
        const vars = { input: { ...PRODUCT_VARS.input, limits: { maxOutputBytes: max } } };
        stub.behave({ stdout: doc(max) + '\n' });
        const at = await call(Q.product, vars);
        expect(at.outcome).toBe('OK');
        expect(at.document.length).toBe(max);
        stub.behave({ stdout: doc(max + 1) + '\n' });
        const over = await call(Q.product, vars);
        expect(over.outcome).toBe('CONTRACT_MISMATCH');
        expect(over.error.reason).toBe('OUTPUT_OVERFLOW');
        expect(isAlive(stub.readPid('pid'))).toBe(false);
    });

    it('T-33 exit 7 on release is UNEXPECTED_PARTIAL', async () => {
        begin();
        stub.behave({ exit: 7, stdout: '{"formatVersion":1,"kind":"TEA_RELEASE"}\n' });
        const result = await call(Q.release, RELEASE_VARS);
        expect(result.outcome).toBe('CONTRACT_MISMATCH');
        expect(result.error.reason).toBe('UNEXPECTED_PARTIAL');
    });

    it('T-34 detail is printable ASCII of at most 2048 bytes; the message is the fixed sentence', async () => {
        begin();
        const messages: string[] = [];
        for (const text of [`first\u0001\u001b[31m\u00e9\u2028${'m'.repeat(10000)}`, 'a second, different CLI message']) {
            stub.behave({ exit: 1, stderr: errorLine(1, 'FAILED', { message: text }) });
            const result = await call(Q.product, PRODUCT_VARS);
            expect(result.error.detail.length).toBeLessThanOrEqual(2048);
            expect(/^[\x20-\x7e]*$/.test(result.error.detail)).toBe(true);
            messages.push(result.error.message);
        }
        expect(messages[0]).toBe(messages[1]);
    });

    it('T-35 exit 10: CONNECT_FAILED with the hint and the names of rebom\'s proxy variables, never their values', async () => {
        begin();
        const saved = { HTTPS_PROXY: process.env.HTTPS_PROXY, no_proxy: process.env.no_proxy };
        process.env.HTTPS_PROXY = 'http://proxy-value.example.com:3128';
        process.env.no_proxy = 'internal-value.example.com';
        try {
            stub.behave({ exit: 10, stderr: errorLine(10, 'CONNECT_FAILED', { hint: 'the network policy of the deployment may block this address', phase: 'CONNECT' }) });
            const answer = await gql(http.url, Q.product, PRODUCT_VARS);
            answers.push(answer.text);
            const result = field(answer);
            expect(result.outcome).toBe('CONNECT_FAILED');
            expect(result.error.hint).toBe('the network policy of the deployment may block this address');
            expect(result.error.ignoredProxyVariables).toEqual(expect.arrayContaining(['HTTPS_PROXY', 'no_proxy']));
            expect(answer.text).not.toContain('proxy-value');
            expect(answer.text).not.toContain('internal-value');
        } finally {
            for (const [k, v] of Object.entries(saved)) {
                if (v === undefined) delete process.env[k];
                else process.env[k] = v;
            }
        }
    });

    it('T-36 a run that ends by a signal rebom did not send is FAILED SIGNAL', async () => {
        begin();
        stub.behave({ signal: 'TERM' });
        const result = await call(Q.product, PRODUCT_VARS);
        expect(result.outcome).toBe('FAILED');
        expect(result.error.reason).toBe('SIGNAL');
    });

    it('T-37 an exception nobody expected is FAILED INTERNAL with a reference that is in one log line', async () => {
        const root = begin();
        // the per-call directory cannot be made: the service function throws
        fs.rmSync(workDirOf(root), { recursive: true, force: true });
        const result = await call(Q.product, PRODUCT_VARS);
        expect(result.outcome).toBe('FAILED');
        expect(result.error.reason).toBe('INTERNAL');
        const ref = /^Internal error \(ref ([0-9a-f]{8})\)$/.exec(result.error.message);
        expect(ref).not.toBeNull();
        const lines = logs.calls().filter((c) => (c[0] as any)?.ref === ref![1]);
        expect(lines).toHaveLength(1);
    });

    it('T-38 the document is returned as the CLI wrote it, byte for byte', async () => {
        begin();
        const line = '{"kind":"TEA_PRODUCT","zeta":[1,2.50,{"\\u00e9":"\\"q\\" \\\\ \\/"}],"formatVersion":1,"unknownKey":null,"product":{"name":"\\u2028x"}}';
        stub.behave({ stdout: `${line}\n` });
        const result = await call(Q.product, PRODUCT_VARS);
        expect(result.outcome).toBe('OK');
        expect(result.document).toBe(line);
    });
});
