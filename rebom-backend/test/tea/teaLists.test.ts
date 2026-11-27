import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import {
    API_ROOT, callsWith, cleanupDirs, configure, field, gql, Http, isAlive, LogSpy, MARKERS, MARK_KEY, MARK_TOKEN, NONE, Q, sleep,
    spyLogs, startHttp, Stub, TOKEN
} from './teaTestKit';

/**
 * The two list operations, teaProducts and teaComponents (TPF-241 design 3.1 to 3.6; rows T-1035
 * to T-1051). The CLI is the stub; every call goes through the HTTP server, so the resolver, the
 * caller gate and the schema are in every row. No marker credential may appear in any result or
 * log line of a row (afterEach), nor any of the list markers below outside the rows that return
 * them as data.
 */

const MARK_ID_VALUE = 'idv-MARK-E';
const MARK_PAGE_TOKEN = 'ptok-MARK-F';
const MARK_ROW = 'row-MARK-G';
const MARK_NEXT = 'next-MARK-H';
const MARK_MESSAGE = 'msg-MARK-I';

interface ListOp {
    name: string;
    query: string;
    kind: string;
    key: string;
    verb: string;
}

const PRODUCTS: ListOp = { name: 'teaProducts', query: Q.products, kind: 'TEA_PRODUCT_LIST', key: 'products', verb: 'products' };
const COMPONENTS: ListOp = { name: 'teaComponents', query: Q.components, kind: 'TEA_COMPONENT_LIST', key: 'components', verb: 'components' };
const BOTH: ListOp[] = [PRODUCTS, COMPONENTS];

const ROOT_SERVER = { rootUrl: 'https://tea.example.com/tea', apiVersion: '1.0.0' };
const UUID_1 = '11111111-1111-4111-8111-111111111111';
const UUID_2 = '22222222-2222-4222-8222-222222222222';

const LIST_LIMITS = ['--connect-timeout=5s', '--deadline=60s', '--max-document-bytes=4194304', '--max-redirects=5', '--max-string-bytes=8192',
    '--max-output-bytes=8388608'];

function uuidOf(n: number): string {
    return `00000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}`;
}

function row(uuid: string, name: string, identifiers: Array<{ idType: string, idValue: string }> = []): Record<string, unknown> {
    return { uuid, name, identifiers };
}

/** A list document of the operation's kind, as the CLI prints it. */
function listDoc(op: ListOp, rows: unknown[], page: Record<string, unknown> = {}): string {
    return JSON.stringify({
        formatVersion: 1, kind: op.kind, server: ROOT_SERVER, [op.key]: rows,
        page: { nextPageToken: null, rowsReceived: rows.length, cutBy: 'NONE', ...page }
    }) + '\n';
}

function errorLine(exitCode: number, reason: string, extra: Record<string, unknown> = {}): string {
    return JSON.stringify({
        formatVersion: 1, kind: 'TEA_ERROR', exitCode, reason, message: `the CLI says ${reason}`, url: 'https://tea.example.com/tea/v1.0.0/products',
        httpStatus: null, address: null, addressClass: null, phase: null, hint: null, ignoredProxyVariables: [], ...extra
    }) + '\n';
}

function teaLines(logs: LogSpy): Record<string, unknown>[] {
    return callsWith(logs, 'TEA operation').map((call) => call[0] as Record<string, unknown>);
}

describe('TEA lists', () => {
    let http: Http;
    let stub: Stub | undefined;
    let logs: LogSpy;
    let answers: string[];

    beforeAll(async () => {
        http = await startHttp();
    });

    afterAll(async () => {
        await http.server.stop();
    });

    afterEach(() => {
        const logged = logs.text();
        logs.restore();
        for (const marker of MARKERS) {
            expect(logged).not.toContain(marker);
            for (const answer of answers) expect(answer).not.toContain(marker);
        }
        stub?.restore();
        stub = undefined;
        cleanupDirs();
    });

    function begin(env: Record<string, string | undefined> = {}, options: { killMarginMs?: number } = {}): Stub {
        stub = new Stub();
        logs = spyLogs();
        answers = [];
        configure(env, options);
        return stub;
    }

    async function call(op: ListOp, input: Record<string, unknown>, header: string | null = TOKEN): Promise<any> {
        const answer = await gql(http.url, op.query, { input }, header);
        answers.push(answer.text);
        return field(answer);
    }

    function input(extra: Record<string, unknown> = {}): Record<string, unknown> {
        return { apiRoot: API_ROOT, credential: NONE, ...extra };
    }

    async function waitFor(condition: () => boolean, ms = 5000): Promise<void> {
        for (let waited = 0; waited < ms && !condition(); waited += 20) await sleep(20);
    }

    it('T-1035 the argument vector of both operations, without and with every optional input, equals the golden list', async () => {
        const s = begin();
        for (const op of BOTH) {
            s.behave({ stdout: listDoc(op, []) });
            s.clearRuns();
            expect((await call(op, input())).outcome, op.name).toBe('OK');
            expect(s.runs(), op.name).toHaveLength(1);
            expect(s.argv(), op.name).toEqual(['tea', 'machine', op.verb, `--api-root=${API_ROOT}`, ...LIST_LIMITS, '--page-size=100', '--max-rows=1000']);

            s.clearRuns();
            const all = input({ idType: 'PURL', idValue: 'pkg:npm/left-pad@1.3.0', sortOrder: 'DESC', pageToken: 'page-2', pageSize: 7 });
            expect((await call(op, all)).outcome, op.name).toBe('OK');
            expect(s.runs(), op.name).toHaveLength(1);
            const argv = s.argv();
            expect(argv, op.name).toEqual(['tea', 'machine', op.verb, `--api-root=${API_ROOT}`, '--id-type=PURL', '--id-value=pkg:npm/left-pad@1.3.0',
                '--sort-order=desc', '--page-token=page-2', ...LIST_LIMITS, '--page-size=7', '--max-rows=1000']);
            for (const absent of ['--allow-this-host', '--usehttp', '-d']) expect(argv, op.name).not.toContain(absent);
            expect(argv.some((a) => a.startsWith('--credential-stdin')), op.name).toBe(false);
        }
        // the ascending order is passed as asc
        s.clearRuns();
        s.behave({ stdout: listDoc(PRODUCTS, []) });
        await call(PRODUCTS, input({ sortOrder: 'ASC' }));
        expect(s.argv()).toContain('--sort-order=asc');
    });

    it('T-1036 a page token or an identifier value that looks like a flag, a command or holds quotes is exactly one element', async () => {
        const s = begin();
        const values = ['--dir=/etc', '-d', '$(id)', 'a b', 'x\'y"z;w', 'a=b --max-rows=1'];
        for (const op of BOTH) {
            s.behave({ stdout: listDoc(op, []) });
            const tokenReference = ['tea', 'machine', op.verb, '--api-root=x', '--page-token=x', ...LIST_LIMITS, '--page-size=100', '--max-rows=1000'].length;
            const idReference = tokenReference + 1;
            for (const value of values) {
                s.clearRuns();
                expect((await call(op, input({ pageToken: value }))).outcome, `${op.name} ${value}`).toBe('OK');
                let argv = s.argv();
                expect(argv.filter((a) => a === `--page-token=${value}`), value).toHaveLength(1);
                expect(argv.filter((a) => a.endsWith(`=${value}`)), value).toHaveLength(1);
                expect(argv, value).toHaveLength(tokenReference);

                s.clearRuns();
                expect((await call(op, input({ idType: 'CPE', idValue: value }))).outcome, `${op.name} ${value}`).toBe('OK');
                argv = s.argv();
                expect(argv.filter((a) => a === `--id-value=${value}`), value).toHaveLength(1);
                expect(argv.filter((a) => a.endsWith(`=${value}`)), value).toHaveLength(1);
                expect(argv, value).toHaveLength(idReference);
                expect(argv.filter((a) => a.startsWith('--max-rows=')), value).toEqual(['--max-rows=1000']);
            }
        }
    });

    it('T-1037 a page with two rows and a token: the rows field for field and in order, every string unchanged', async () => {
        const s = begin();
        for (const op of BOTH) {
            const rows = [
                row(UUID_1, '  Leading and trailing  ', [
                    { idType: 'PURL', idValue: 'pkg:maven/org.example/app@1.0.0?type=jar' },
                    { idType: 'CPE', idValue: 'cpe:2.3:a:example:app:1.0.0:*:*:*:*:*:*:*' },
                    { idType: 'SOMETHING_NEW', idValue: 'x' }
                ]),
                row(UUID_2, '<b>bold</b> &amp; <script>alert(1)</script> caf\u00e9 \u0000 end', [])
            ];
            s.behave({ stdout: listDoc(op, rows, { nextPageToken: 'opaque-token-2', rowsReceived: 2 }) });
            const result = await call(op, input());
            expect(result.outcome, op.name).toBe('OK');
            expect(result.error, op.name).toBeNull();
            expect(result.page.rows, op.name).toEqual(rows);
            expect(result.page.next, op.name).toBe('TOKEN');
            expect(result.page.nextPageToken, op.name).toBe('opaque-token-2');
            expect(result.page.rowsReceived, op.name).toBe(2);
            expect(result.page.cut, op.name).toBe('NONE');
            expect(result.limits.pageSize, op.name).toBe(100);
            expect(result.limits.maxPages, op.name).toBeNull();
        }
    });

    it('T-1038 the last page and the empty page are data: OK with next NONE', async () => {
        const s = begin();
        for (const op of BOTH) {
            const rows = [row(UUID_1, 'Last', [])];
            s.behave({ stdout: listDoc(op, rows) });
            const last = await call(op, input({ pageToken: 'page-9' }));
            expect(last.outcome, op.name).toBe('OK');
            expect(last.page.rows, op.name).toEqual(rows);
            expect(last.page.next, op.name).toBe('NONE');
            expect(last.page.nextPageToken, op.name).toBeNull();

            s.behave({ stdout: listDoc(op, [], { rowsReceived: 0 }) });
            const empty = await call(op, input());
            expect(empty.outcome, op.name).toBe('OK');
            expect(empty.page.rows, op.name).toEqual([]);
            expect(empty.page.next, op.name).toBe('NONE');
            expect(empty.page.rowsReceived, op.name).toBe(0);
        }
    });

    it('T-1039 a cut answer is reported: MAX_ROWS, OTHER for a cut this version does not know; 1000 rows are kept', async () => {
        const s = begin();
        const three = [row(uuidOf(1), 'a'), row(uuidOf(2), 'b'), row(uuidOf(3), 'c')];
        s.behave({ stdout: listDoc(COMPONENTS, three, { rowsReceived: 5000, cutBy: 'MAX_ROWS' }) });
        const cut = await call(COMPONENTS, input());
        expect(cut.outcome).toBe('OK');
        expect(cut.page.rows).toHaveLength(3);
        expect(cut.page.rowsReceived).toBe(5000);
        expect(cut.page.cut).toBe('MAX_ROWS');

        s.behave({ stdout: listDoc(COMPONENTS, three, { rowsReceived: 5000, cutBy: 'SOME_LATER_CUT' }) });
        expect((await call(COMPONENTS, input())).page.cut).toBe('OTHER');

        const thousand = Array.from({ length: 1000 }, (_, i) => row(uuidOf(i + 1), `row ${i}`));
        s.behave({ stdout: listDoc(COMPONENTS, thousand) });
        const full = await call(COMPONENTS, input());
        expect(full.outcome).toBe('OK');
        expect(full.page.rows).toHaveLength(1000);
        expect(full.page.cut).toBe('NONE');
    });

    it('T-1040 a returned next token is accepted back as it is; a token rebom would refuse is TOKEN_UNUSABLE and not returned', async () => {
        const s = begin();
        const rows = [row(UUID_1, 'one')];
        const longest = MARK_NEXT + 't'.repeat(4096 - MARK_NEXT.length);
        s.behave({ stdout: listDoc(PRODUCTS, rows, { nextPageToken: longest }) });
        const first = await call(PRODUCTS, input());
        expect(first.page.next).toBe('TOKEN');
        expect(first.page.nextPageToken).toBe(longest);
        s.clearRuns();
        s.behave({ stdout: listDoc(PRODUCTS, rows) });
        const second = await call(PRODUCTS, input({ pageToken: first.page.nextPageToken }));
        expect(second.outcome).toBe('OK');
        expect(s.argv().filter((a) => a === `--page-token=${longest}`)).toHaveLength(1);

        for (const [label, token] of [
            ['4097 characters', MARK_NEXT + 't'.repeat(4097 - MARK_NEXT.length)],
            ['a non-ASCII letter', `${MARK_NEXT}-caf\u00e9`],
            ['U+FFFD', '\ufffd']
        ]) {
            s.behave({ stdout: listDoc(PRODUCTS, rows, { nextPageToken: token }) });
            const answer = await gql(http.url, PRODUCTS.query, { input: input() });
            answers.push(answer.text);
            const result = field(answer);
            expect(result.outcome, label).toBe('OK');
            expect(result.page.rows, label).toEqual(rows);
            expect(result.page.next, label).toBe('TOKEN_UNUSABLE');
            expect(result.page.nextPageToken, label).toBeNull();
            expect(answer.text, label).not.toContain(MARK_NEXT);
        }
        expect(logs.text()).not.toContain(MARK_NEXT);
    });

    it('T-1041 one refused example per new rule, and an inherited one: INVALID_REQUEST naming field and rule, no value anywhere, nothing starts', async () => {
        const s = begin();
        const TOKEN_RULE = '1 to 4096 bytes of printable ASCII';
        const ID_RULE = '1 to 2048 bytes of printable ASCII';
        const cases: Array<[Record<string, unknown>, string, string]> = [
            [{ pageSize: 0 }, 'input.pageSize', 'an integer from 1 to 100'],
            [{ pageSize: 101 }, 'input.pageSize', 'an integer from 1 to 100'],
            [{ pageToken: '' }, 'input.pageToken', TOKEN_RULE],
            [{ pageToken: MARK_PAGE_TOKEN + 'p'.repeat(4097 - MARK_PAGE_TOKEN.length) }, 'input.pageToken', TOKEN_RULE],
            [{ pageToken: `${MARK_PAGE_TOKEN}\nnext` }, 'input.pageToken', TOKEN_RULE],
            [{ pageToken: `${MARK_PAGE_TOKEN}-caf\u00e9` }, 'input.pageToken', TOKEN_RULE],
            [{ idType: 'PURL', idValue: '' }, 'input.idValue', ID_RULE],
            [{ idType: 'PURL', idValue: MARK_ID_VALUE + 'i'.repeat(2049 - MARK_ID_VALUE.length) }, 'input.idValue', ID_RULE],
            [{ idType: 'PURL', idValue: `${MARK_ID_VALUE}\tx` }, 'input.idValue', ID_RULE],
            [{ idType: 'PURL', idValue: `${MARK_ID_VALUE}-caf\u00e9` }, 'input.idValue', ID_RULE],
            [{ idType: 'TEI' }, 'input.idValue', 'given together with input.idType or not at all'],
            [{ idValue: MARK_ID_VALUE }, 'input.idType', 'given together with input.idValue or not at all'],
            [{ apiRoot: `http://${MARK_ID_VALUE}.example.com/tea` }, 'input.apiRoot', 'an https URL']
        ];
        for (const op of BOTH) {
            for (const [extra, fieldName, rule] of cases) {
                const label = `${op.name} ${fieldName} ${JSON.stringify(extra).length}`;
                const answer = await gql(http.url, op.query, { input: input(extra) });
                answers.push(answer.text);
                const result = field(answer);
                expect(result.outcome, label).toBe('INVALID_REQUEST');
                expect(result.error.reason, label).toBe('INPUT');
                expect(result.error.detail.startsWith(`${fieldName}: `), label).toBe(true);
                expect(result.error.detail, label).toContain(rule);
                expect(result.page, label).toBeNull();
                for (const marker of [MARK_PAGE_TOKEN, MARK_ID_VALUE]) expect(answer.text, label).not.toContain(marker);
            }
        }
        expect(s.runs()).toEqual([]);
        expect(s.probes()).toBe(0);
        expect(logs.text()).not.toContain(MARK_PAGE_TOKEN);
        expect(logs.text()).not.toContain(MARK_ID_VALUE);

        // the accepted bounds: one run each
        s.behave({ stdout: listDoc(PRODUCTS, []) });
        for (const extra of [{ pageSize: 1 }, { pageSize: 100 }, { pageToken: 'p'.repeat(4096) }, { idType: 'PURL', idValue: 'i'.repeat(2048) }]) {
            s.clearRuns();
            const result = await call(PRODUCTS, input(extra));
            expect(result.outcome, Object.keys(extra).join()).toBe('OK');
            expect(s.runs(), Object.keys(extra).join()).toHaveLength(1);
        }
    });

    it('T-1042 an identifier type or a sort order outside the enum is a GraphQL error; nothing runs, the value is not repeated', async () => {
        const s = begin();
        for (const op of BOTH) {
            for (const [extra, refused] of [[{ idType: 'SWID', idValue: 'x' }, 'SWID'], [{ sortOrder: 'up' }, 'up']] as const) {
                const answer = await gql(http.url, op.query, { input: input(extra) });
                answers.push(answer.text);
                expect(answer.json?.data?.[op.name] ?? null, `${op.name} ${refused}`).toBeNull();
                expect(Array.isArray(answer.json?.errors) && answer.json.errors.length > 0, `${op.name} ${refused}`).toBe(true);
                for (const error of answer.json.errors) {
                    expect(new RegExp(`\\b${refused}\\b`).test(error.message), `${op.name} ${error.message}`).toBe(false);
                }
            }
        }
        expect(s.runs()).toEqual([]);
        expect(s.probes()).toBe(0);
    });

    it('T-1043 every exit code and CLI reason of design 3.6 maps to its outcome and reason, the fields copied, page null', async () => {
        const s = begin();
        const rows: Array<[number, string, string, Record<string, unknown>]> = [
            [1, 'FAILED', 'FAILED', { httpStatus: 400 }],
            [1, 'FAILED', 'FAILED', { httpStatus: 404 }],
            [1, 'FAILED', 'SERVER_ERROR', { httpStatus: 503 }],
            [1, 'FAILED', 'INVALID_RESPONSE', {}],
            [1, 'FAILED', 'TLS_CERTIFICATE', {}],
            [2, 'INVALID_REQUEST', 'USAGE', { message: 'unknown flag: --api-root' }],
            [3, 'NOT_FOUND', 'NOT_FOUND', { httpStatus: 404 }],
            [5, 'URL_REFUSED', 'URL_REFUSED', { url: 'https://elsewhere.example.com/tea/v1.0.0/products' }],
            [5, 'URL_REFUSED', 'REDIRECT_LIMIT', {}],
            [6, 'AUTH', 'AUTH', { httpStatus: 401 }],
            [8, 'ADDRESS_REFUSED', 'ADDRESS_REFUSED', { address: '169.254.169.254', addressClass: 'LINK_LOCAL' }],
            ...['DOCUMENT_BYTES', 'STRING_BYTES', 'OUTPUT_BYTES', 'REQUEST_TIMEOUT', 'DEADLINE']
                .map((reason) => [9, 'LIMIT', reason, {}] as [number, string, string, Record<string, unknown>]),
            [10, 'CONNECT_FAILED', 'CONNECT_FAILED', { phase: 'CONNECT' }]
        ];
        let i = 0;
        for (const [code, outcome, reason, extra] of rows) {
            const op = BOTH[i++ % 2];
            const label = `${op.name} ${code} ${reason}`;
            s.behave({ exit: code, stderr: errorLine(code, reason, extra) });
            const result = await call(op, input());
            expect(result.outcome, label).toBe(outcome);
            expect(result.error.reason, label).toBe(reason);
            expect(result.error.cliExitCode, label).toBe(code);
            expect(result.error.detail, label).toBe(extra.message ?? `the CLI says ${reason}`);
            expect(result.error.url, label).toBe(extra.url ?? 'https://tea.example.com/tea/v1.0.0/products');
            for (const [key, value] of Object.entries(extra)) {
                if (key !== 'message') expect(result.error[key], `${label} ${key}`).toBe(value);
            }
            expect(result.page, label).toBeNull();
        }
        s.behave({ exit: 4, stderr: errorLine(4, 'FAILED') });
        expect(await call(PRODUCTS, input())).toMatchObject({ outcome: 'FAILED', error: { reason: 'EXIT_CODE', cliExitCode: 4 }, page: null });
        s.behave({ exit: 7, stdout: listDoc(COMPONENTS, []), stderr: errorLine(7, 'PARTIAL') });
        const partial = await call(COMPONENTS, input());
        expect(partial).toMatchObject({ outcome: 'CONTRACT_MISMATCH', error: { reason: 'UNEXPECTED_PARTIAL', cliExitCode: 7 }, page: null });
        s.behave({ exit: 1 });
        const bare = await call(PRODUCTS, input());
        expect(bare).toMatchObject({ outcome: 'FAILED', error: { reason: 'NO_ERROR_OBJECT', cliExitCode: 1, detail: null }, page: null });
    });

    it('T-1044 killed at the deadline, stdout over the cap, a signal, and output that is not one list of the expected kind', async () => {
        const s = begin({}, { killMarginMs: 300 });
        s.behave({ sleep: 30 });
        const killed = await call(PRODUCTS, input({ limits: { deadlineSeconds: 1 } }));
        expect(killed).toMatchObject({ outcome: 'TIMEOUT', error: { reason: 'KILLED_AT_DEADLINE' }, page: null });
        await waitFor(() => !isAlive(s.readPid('pid')));
        expect(isAlive(s.readPid('pid'))).toBe(false);

        const big = [row(UUID_1, 'x'.repeat(70000))];
        s.behave({ stdout: listDoc(PRODUCTS, big) });
        expect(await call(PRODUCTS, input({ limits: { maxOutputBytes: 65536 } })))
            .toMatchObject({ outcome: 'CONTRACT_MISMATCH', error: { reason: 'OUTPUT_OVERFLOW' }, page: null });

        s.behave({ signal: 'TERM' });
        expect(await call(COMPONENTS, input())).toMatchObject({ outcome: 'FAILED', error: { reason: 'SIGNAL' }, page: null });

        const cases: Array<[ListOp, string, string]> = [
            [PRODUCTS, 'hello\n', 'OUTPUT_NOT_JSON'],
            [COMPONENTS, listDoc(COMPONENTS, []).replace('"formatVersion":1', '"formatVersion":2'), 'FORMAT_VERSION'],
            [COMPONENTS, listDoc(PRODUCTS, []), 'KIND'],
            [PRODUCTS, '{"formatVersion":1,"kind":"TEA_PRODUCT","product":{"uuid":"11111111-1111-4111-8111-111111111111","name":"P","identifiers":[]}}\n', 'KIND']
        ];
        for (const [op, stdout, reason] of cases) {
            s.behave({ stdout });
            expect(await call(op, input()), `${op.name} ${reason}`).toMatchObject({ outcome: 'CONTRACT_MISMATCH', error: { reason }, page: null });
        }
    });

    it('T-1045 a list of the right kind but another shape is LIST_INVALID with nothing of it returned or logged; extra keys are dropped', async () => {
        const s = begin();
        const named = row(UUID_1, MARK_ROW);
        const page = { nextPageToken: null, rowsReceived: 1, cutBy: 'NONE' };
        const doc = (body: Record<string, unknown>) => JSON.stringify({ formatVersion: 1, kind: 'TEA_PRODUCT_LIST', server: ROOT_SERVER, ...body }) + '\n';
        const broken: Array<[string, string]> = [
            ['the row key missing', doc({ items: [named], page })],
            ['the rows not an array', doc({ products: { first: named }, page })],
            ['a row that is a string', doc({ products: [MARK_ROW], page })],
            ['a row without uuid', doc({ products: [{ name: MARK_ROW, identifiers: [] }], page })],
            ['a uuid that is not a uuid', doc({ products: [{ ...named, uuid: 'not-a-uuid' }], page })],
            ['a numeric name', doc({ products: [{ ...named, name: 42 }, named], page: { ...page, rowsReceived: 2 } })],
            ['identifiers missing', doc({ products: [{ uuid: UUID_1, name: MARK_ROW }], page })],
            ['an identifier with a numeric idValue', doc({ products: [{ ...named, identifiers: [{ idType: 'PURL', idValue: 7 }] }], page })],
            ['page missing', doc({ products: [named] })],
            ['nextPageToken a number', doc({ products: [named], page: { ...page, nextPageToken: 3 } })],
            ['nextPageToken the empty string', doc({ products: [named], page: { ...page, nextPageToken: '' } })],
            ['rowsReceived a string', doc({ products: [named], page: { ...page, rowsReceived: '1' } })],
            ['rowsReceived below the number of rows', doc({ products: [named, row(UUID_2, 'two')], page: { ...page, rowsReceived: 1 } })],
            ['cutBy missing', doc({ products: [named], page: { nextPageToken: null, rowsReceived: 1 } })],
            ['1001 rows', doc({ products: [named, ...Array.from({ length: 1000 }, (_, i) => row(uuidOf(i + 1), `r${i}`))], page: { ...page, rowsReceived: 1001 } })]
        ];
        for (const [label, stdout] of broken) {
            s.behave({ stdout });
            const answer = await gql(http.url, PRODUCTS.query, { input: input() });
            answers.push(answer.text);
            const result = field(answer);
            expect(result.outcome, label).toBe('CONTRACT_MISMATCH');
            expect(result.error.reason, label).toBe('LIST_INVALID');
            expect(result.error.cliExitCode, label).toBe(0);
            expect(result.page, label).toBeNull();
            expect(answer.text, label).not.toContain(MARK_ROW);
        }
        expect(logs.text()).not.toContain(MARK_ROW);
        const outcomes = teaLines(logs).map((line) => line.reason);
        expect(outcomes.filter((reason) => reason === 'LIST_INVALID')).toHaveLength(broken.length);

        const extra = JSON.stringify({
            formatVersion: 1, kind: 'TEA_PRODUCT_LIST', server: ROOT_SERVER, extraTop: 'x-top-extra',
            products: [{ ...row(UUID_1, 'kept', [{ idType: 'TEI', idValue: 'tei://x', extraIdentifier: 'x-id-extra' }]), extraRow: 'x-row-extra' }],
            page: { ...page, extraPage: 'x-page-extra' }
        }) + '\n';
        s.behave({ stdout: extra });
        const answer = await gql(http.url, PRODUCTS.query, { input: input() });
        answers.push(answer.text);
        const ok = field(answer);
        expect(ok.outcome).toBe('OK');
        expect(ok.page.rows).toEqual([row(UUID_1, 'kept', [{ idType: 'TEI', idValue: 'tei://x' }])]);
        for (const word of ['x-top-extra', 'x-row-extra', 'x-id-extra', 'x-page-extra', 'rootUrl']) expect(answer.text).not.toContain(word);
    });

    it('T-1046 the lists answer CALLER_REFUSED without or with a wrong header, before validation; nothing starts', async () => {
        const s = begin();
        for (const op of BOTH) {
            for (const header of [null, 'a-wrong-header-0123456789abcdef0123456789']) {
                const result = await call(op, input({ pageSize: 0 }), header);
                expect(result.outcome, `${op.name} ${header}`).toBe('CALLER_REFUSED');
                expect(result.error.reason, `${op.name} ${header}`).toBe('CALLER');
                expect(result.page, `${op.name} ${header}`).toBeNull();
            }
        }
        expect(s.runs()).toEqual([]);
        expect(s.probes()).toBe(0);
    });

    it('T-1047 the credential reaches the child only in its environment, under the names of the allow-list', async () => {
        const s = begin();
        const credentials: Array<[Record<string, unknown>, Record<string, string>]> = [
            [{ kind: 'BEARER_TOKEN', authRoot: API_ROOT, token: MARK_TOKEN }, { REARM_TEA_TOKEN: MARK_TOKEN }],
            [{ kind: 'API_KEY', authRoot: API_ROOT, keyId: `${MARK_KEY}-id`, keySecret: `${MARK_KEY}-secret` },
                { REARM_TEA_APIKEYID: `${MARK_KEY}-id`, REARM_TEA_APIKEY: `${MARK_KEY}-secret` }]
        ];
        const allowed = ['PATH', 'SSL_CERT_FILE', 'SSL_CERT_DIR', 'HOME', 'TMPDIR', 'REARM_TEA_AUTH_ROOT', 'REARM_TEA_TOKEN', 'REARM_TEA_APIKEYID', 'REARM_TEA_APIKEY'];
        for (const op of BOTH) {
            for (const [credential, expected] of credentials) {
                s.clearRuns();
                s.behave({ stdout: listDoc(op, [row(UUID_1, 'one')]) });
                expect((await call(op, input({ credential }))).outcome, op.name).toBe('OK');
                const env = s.env();
                for (const [name, value] of Object.entries(expected)) expect(env[name], `${op.name} ${name}`).toBe(value);
                expect(env.REARM_TEA_AUTH_ROOT, op.name).toBe(API_ROOT);
                // the shell may add PWD, SHLVL and _ of its own
                const names = Object.keys(env).filter((n) => !['PWD', 'SHLVL', '_', 'OLDPWD'].includes(n));
                for (const name of names) expect(allowed, `${op.name} ${name}`).toContain(name);
                for (const element of s.argv()) {
                    for (const marker of MARKERS) expect(element, op.name).not.toContain(marker);
                }
                s.behave({ exit: 6, stderr: errorLine(6, 'AUTH', { httpStatus: 401 }) });
                expect((await call(op, input({ credential }))).outcome, op.name).toBe('AUTH');
            }
        }
    });

    it('T-1048 the 4 MiB answer bound applies to the lists only; a caller lowers a limit, never raises it', async () => {
        const s = begin();
        s.behave({ stdout: listDoc(COMPONENTS, []) });
        const docBytes = async (limits?: Record<string, number>): Promise<[string | undefined, number]> => {
            s.clearRuns();
            const result = await call(COMPONENTS, input(limits ? { limits } : {}));
            return [s.argv().find((a) => a.startsWith('--max-document-bytes=')), result.limits.maxDocumentBytes];
        };
        expect(await docBytes()).toEqual(['--max-document-bytes=4194304', 4194304]);
        expect(await docBytes({ maxDocumentBytes: 1048576 })).toEqual(['--max-document-bytes=1048576', 1048576]);
        expect(await docBytes({ maxDocumentBytes: 16777216 })).toEqual(['--max-document-bytes=4194304', 4194304]);
        for (const [deadline, expected] of [[5, 5], [600, 60]]) {
            s.clearRuns();
            const result = await call(COMPONENTS, input({ limits: { deadlineSeconds: deadline } }));
            expect(s.argv(), String(deadline)).toContain(`--deadline=${expected}s`);
            expect(result.limits.deadlineSeconds, String(deadline)).toBe(expected);
        }
        // a single read beside it keeps the ceiling of the other reads
        s.clearRuns();
        s.behave({ stdout: '{"formatVersion":1,"kind":"TEA_PRODUCT","product":{}}\n' });
        const product = field(await gql(http.url, Q.product, { input: { apiRoot: API_ROOT, uuid: UUID_1, credential: NONE } }));
        expect(product.outcome).toBe('OK');
        expect(s.argv()).toContain('--max-document-bytes=16777216');

        configure({ REBOM_TEA_MAX_DOCUMENT_BYTES: '2097152' });
        s.behave({ stdout: listDoc(COMPONENTS, []) });
        expect(await docBytes()).toEqual(['--max-document-bytes=2097152', 2097152]);
    });

    it('T-1049 the lists take their slot from the reads pool: BUSY beside a held read, OK once it ended', async () => {
        const s = begin({ REBOM_TEA_MAX_CONCURRENT_READS: '1', REBOM_TEA_MAX_QUEUED: '0' });
        s.behave({ sleep: 2, stdout: '{"formatVersion":1,"kind":"TEA_PRODUCT","product":{}}\n' });
        const held = gql(http.url, Q.product, { input: { apiRoot: API_ROOT, uuid: UUID_1, credential: NONE } });
        await waitFor(() => s.starts().length === 1);
        const beside = await call(PRODUCTS, input());
        // no queue: the refusal is at once (QUEUE_FULL), as for every read
        expect(beside).toMatchObject({ outcome: 'BUSY', error: { reason: 'QUEUE_FULL' }, page: null });
        expect(s.starts()).toHaveLength(1);
        expect(field(await held).outcome).toBe('OK');
        s.behave({ stdout: listDoc(PRODUCTS, []) });
        expect((await call(PRODUCTS, input())).outcome).toBe('OK');
        expect(s.starts()).toHaveLength(2);
    });

    it('T-1050 the local-testing switch: no lifting flag and no plain http unless exactly ON', async () => {
        const httpRoot = 'http://localhost:8080/tea';
        for (const value of [undefined, 'on', 'true', '1', ' ON', 'ON ']) {
            const s = begin({ REBOM_TEA_LOCAL_TESTING: value });
            for (const op of BOTH) {
                s.clearRuns();
                s.behave({ stdout: listDoc(op, []) });
                expect((await call(op, input())).outcome, `${value} ${op.name}`).toBe('OK');
                expect(s.argv(), `${value} ${op.name}`).not.toContain('--allow-this-host');
                expect(s.argv(), `${value} ${op.name}`).not.toContain('--usehttp');
                s.clearRuns();
                const plain = await call(op, input({ apiRoot: httpRoot }));
                expect(plain).toMatchObject({ outcome: 'INVALID_REQUEST', error: { reason: 'INPUT' } });
                expect(plain.error.detail.startsWith('input.apiRoot: '), `${value} ${op.name}`).toBe(true);
                expect(s.runs(), `${value} ${op.name}`).toEqual([]);
            }
            logs.restore();
            stub!.restore();
        }
        const s = begin({ REBOM_TEA_LOCAL_TESTING: 'ON' });
        for (const op of BOTH) {
            s.behave({ stdout: listDoc(op, []) });
            s.clearRuns();
            expect((await call(op, input({ apiRoot: httpRoot }))).outcome, op.name).toBe('OK');
            expect(s.argv(), op.name).toContain('--allow-this-host');
            expect(s.argv(), op.name).toContain('--usehttp');
            s.clearRuns();
            expect((await call(op, input())).outcome, op.name).toBe('OK');
            expect(s.argv(), op.name).toContain('--allow-this-host');
            expect(s.argv(), op.name).not.toContain('--usehttp');
        }
    });

    it('T-1051 one TEA log line per call with fixed keys: the correlation id and the host, no filter value, token or row text', async () => {
        const s = begin();
        const listMarkers = [MARK_ID_VALUE, MARK_PAGE_TOKEN, MARK_ROW, MARK_NEXT, MARK_MESSAGE];
        for (const op of BOTH) {
            const before = teaLines(logs).length;
            const request = input({ correlationId: 'corr-1051', idType: 'PURL', idValue: `pkg:npm/${MARK_ID_VALUE}`, pageToken: MARK_PAGE_TOKEN });
            s.behave({ stdout: listDoc(op, [row(UUID_1, MARK_ROW, [{ idType: 'PURL', idValue: MARK_ROW }])], { nextPageToken: MARK_NEXT }) });
            expect((await call(op, request)).outcome, op.name).toBe('OK');
            s.behave({ exit: 1, stderr: errorLine(1, 'FAILED', { message: `${MARK_MESSAGE} ${'m'.repeat(5000)}` }) });
            const failed = await call(op, request);
            expect(failed.outcome, op.name).toBe('FAILED');
            expect(failed.error.detail.length, op.name).toBeLessThanOrEqual(2048);
            const lines = teaLines(logs).slice(before);
            expect(lines, op.name).toHaveLength(2);
            for (const line of lines) {
                expect(line.operation, op.name).toBe(op.name);
                expect(line.correlationId, op.name).toBe('corr-1051');
                expect(line.apiHost, op.name).toBe('tea.example.com');
                expect(typeof line.outcome, op.name).toBe('string');
            }
            expect(lines.map((line) => line.outcome), op.name).toEqual(['OK', 'FAILED']);
        }
        const logged = logs.text();
        for (const marker of listMarkers) expect(logged, marker).not.toContain(marker);
    });
});
