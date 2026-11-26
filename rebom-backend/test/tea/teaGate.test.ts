import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import * as fs from 'fs';
import * as path from 'path';
import { teaCounters } from '../../src/services/tea/teaService';
import {
    cleanupDirs, configure, field, gql, Http, MARK_CALLER, Q, spyLogs, startHttp, Stub, tenOperations, TOKEN,
    API_ROOT, UUID_A, NONE, tempDir, callsWith
} from './teaTestKit';

/**
 * The caller gate (TPF-180 design 3.2, 3.3 decision 2; rows T-1 to T-8). HTTP rows through the
 * server production starts, the stub CLI first on PATH.
 */

describe('TEA caller gate', () => {
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

    it('T-1 without REBOM_TEA_CALLER_TOKEN every operation is NOT_CONFIGURED and nothing starts', async () => {
        stub = new Stub();
        configure({ REBOM_TEA_CALLER_TOKEN: undefined });
        for (const [name, query, variables] of tenOperations()) {
            const result = field(await gql(http.url, query, variables, 'plausible-header-value-0123456789abcdef'));
            expect(result.outcome, name).toBe('NOT_CONFIGURED');
            expect(result.error.reason, name).toBe('CALLER_TOKEN');
        }
        expect(stub.runs()).toEqual([]);
        expect(stub.probes()).toBe(0);
    });

    it('T-2 an empty token, one of 31 characters and one with a space are not configured', async () => {
        stub = new Stub();
        for (const token of ['', 'a'.repeat(31), `${'a'.repeat(20)} ${'b'.repeat(20)}`]) {
            configure({ REBOM_TEA_CALLER_TOKEN: token });
            for (const [name, query, variables] of tenOperations()) {
                const result = field(await gql(http.url, query, variables, token));
                expect(result.outcome, `${name} with ${token.length}`).toBe('NOT_CONFIGURED');
            }
        }
        expect(stub.runs()).toEqual([]);
    });

    it('T-3 with a token set and no header every operation is CALLER_REFUSED, nothing starts, no pool is used', async () => {
        stub = new Stub();
        configure();
        for (const [name, query, variables] of tenOperations()) {
            const result = field(await gql(http.url, query, variables, null));
            expect(result.outcome, name).toBe('CALLER_REFUSED');
            expect(result.error.reason, name).toBe('CALLER');
        }
        expect(stub.runs()).toEqual([]);
        expect(stub.probes()).toBe(0);
        expect(teaCounters()).toEqual({ reads: 0, downloads: 0, results: 0, queued: 0, diskBytes: 0, heldFetches: 0 });
    });

    it('T-4 a wrong header of equal length, of other length, the token plus one, empty: refused; the right one passes', async () => {
        stub = new Stub();
        stub.behave({ stdout: '{"formatVersion":1,"kind":"TEA_PRODUCT","product":{}}\n' });
        configure();
        const variables = { input: { apiRoot: API_ROOT, uuid: UUID_A, credential: NONE } };
        const wrongSameLength = TOKEN.substring(0, TOKEN.length - 1) + (TOKEN.endsWith('x') ? 'y' : 'x');
        for (const header of [wrongSameLength, 'short-wrong-token', `${TOKEN}x`, '']) {
            expect(field(await gql(http.url, Q.product, variables, header)).outcome).toBe('CALLER_REFUSED');
        }
        expect(stub.runs()).toEqual([]);
        const ok = field(await gql(http.url, Q.product, variables, TOKEN));
        expect(ok.outcome).toBe('OK');
        expect(stub.runs()).toHaveLength(1);
    });

    it('T-5 the gate comes first: a wrong header beats an invalid uuid, a missing CLI and a busy pool', async () => {
        // a PATH without rearm, and a read pool of one that is busy
        const savedPath = process.env.PATH;
        const empty = tempDir('rebom-tea-nopath-');
        stub = new Stub();
        stub.behave({ sleep: 3, stdout: '{"formatVersion":1,"kind":"TEA_PRODUCT"}\n' });
        configure({ REBOM_TEA_MAX_CONCURRENT_READS: '1', REBOM_TEA_MAX_QUEUED: '0' });
        const busy = gql(http.url, Q.product, { input: { apiRoot: API_ROOT, uuid: UUID_A, credential: NONE } });
        // wait until the busy run started
        for (let i = 0; i < 100 && stub.runs().length === 0; i++) await new Promise((r) => setTimeout(r, 50));
        process.env.PATH = empty;
        try {
            const variables = { input: { apiRoot: API_ROOT, uuid: 'not-a-uuid', credential: NONE } };
            const refused = field(await gql(http.url, Q.product, variables, 'wrong-header-0123456789abcdef0123456789'));
            expect(refused.outcome).toBe('CALLER_REFUSED');
            configure({ REBOM_TEA_CALLER_TOKEN: undefined });
            expect(field(await gql(http.url, Q.product, variables, 'wrong-header')).outcome).toBe('NOT_CONFIGURED');
        } finally {
            process.env.PATH = savedPath;
        }
        await busy;
    });

    it('T-6 existing operations answer the same with no header and with a wrong one', async () => {
        configure();
        const sbom = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'fixtures', 'cyclonedx-simple.json'), 'utf8'));
        const queries: Array<[string, Record<string, unknown>]> = [
            ['query { health { status } }', {}],
            ['query { hello }', {}],
            ['mutation($bomInput: BomInput!) { addBom(bomInput: $bomInput) { uuid } }',
                { bomInput: { format: 'CYCLONEDX', bom: { ...sbom, serialNumber: 'not-a-serial' }, org: 'not-an-org', rebomOptions: {} } }]
        ];
        const answers = async (header: string | null) => Promise.all(queries.map(async ([q, v]) => (await gql(http.url, q, v, header)).text));
        const withNone = await answers(null);
        const withWrong = await answers('wrong-header');
        configure({ REBOM_TEA_CALLER_TOKEN: undefined });
        const withoutToken = await answers(null);
        expect(withNone).toEqual(withoutToken);
        expect(withWrong).toEqual(withoutToken);
        expect(withNone[1]).toContain('world');
    });

    it('T-7 fifty refusals inside a second give exactly one warn line with a count; the presented value is nowhere', async () => {
        configure();
        const logs = spyLogs();
        try {
            const answers: string[] = [];
            await Promise.all(Array.from({ length: 50 }, async () => {
                answers.push((await gql(http.url, Q.status, {}, MARK_CALLER)).text);
            }));
            const lines = callsWith(logs, 'TEA caller refused');
            expect(lines).toHaveLength(1);
            expect(typeof (lines[0][0] as any).refusals).toBe('number');
            expect(logs.text()).not.toContain(MARK_CALLER);
            for (const answer of answers) {
                expect(answer).not.toContain(MARK_CALLER);
                expect(JSON.parse(answer).data.teaStatus.outcome).toBe('CALLER_REFUSED');
            }
        } finally {
            logs.restore();
        }
    });

    it('T-8 a mutation sent by GET is not executed', async () => {
        stub = new Stub();
        stub.behave({ stdout: '{"formatVersion":1,"kind":"TEA_PRODUCT"}\n' });
        configure();
        const query = `mutation { teaProduct(input: { apiRoot: "${API_ROOT}", uuid: "${UUID_A}", credential: { kind: NONE } }) { outcome } }`;
        const response = await fetch(`${http.url}?query=${encodeURIComponent(query)}`, {
            method: 'GET',
            headers: { 'x-rebom-caller-token': TOKEN, 'apollo-require-preflight': 'true' }
        });
        const json: any = await response.json();
        expect(json.data?.teaProduct).toBeUndefined();
        expect(json.errors?.length).toBeGreaterThan(0);
        expect(stub.runs()).toEqual([]);
    });
});
