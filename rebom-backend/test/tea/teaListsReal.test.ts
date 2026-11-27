import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { cleanupDirs, configure, field, gql, Http, LogSpy, MARKERS, MARK_TOKEN, NONE, Q, spyLogs, startHttp } from './teaTestKit';
import { TeaFixtureServer } from './teaFixtureServer';

/**
 * The two list operations against the fixture server with the real CLI (TPF-241 design 3.7 and
 * 3.8; rows T-1052 and T-1053). They need a `rearm` with the list verbs on PATH (the pinned
 * release in the image build) and are not skipped without one. No marker credential may appear
 * in any result or log line (afterEach).
 */

const ID_VALUE = 'pkg:npm/@scope/a b&c=d?e#f';
const LIST_PARAMS = ['idType', 'idValue', 'pageSize', 'pageToken', 'sortField', 'sortOrder'];

describe('TEA lists (real)', () => {
    let gqlHttp: Http;
    let fixture: TeaFixtureServer;
    let logs: LogSpy;
    let answers: string[];

    beforeAll(async () => {
        gqlHttp = await startHttp();
    });

    afterAll(async () => {
        await gqlHttp.server.stop();
    });

    beforeEach(async () => {
        fixture = new TeaFixtureServer('localhost');
        await fixture.start();
        fixture.serveListPages();
        logs = spyLogs();
        answers = [];
    });

    afterEach(async () => {
        const logged = logs.text();
        logs.restore();
        for (const marker of MARKERS) {
            expect(logged).not.toContain(marker);
            for (const answer of answers) expect(answer).not.toContain(marker);
        }
        await fixture.stop();
        cleanupDirs();
    });

    async function call(query: string, input: Record<string, unknown>): Promise<any> {
        const answer = await gql(gqlHttp.url, query, { input });
        answers.push(answer.text);
        return field(answer);
    }

    const bearer = () => ({ kind: 'BEARER_TOKEN', authRoot: fixture.root, token: MARK_TOKEN });

    /** The list requests the fixture saw on path, each with the list parameters it carried. */
    function listRequests(key: 'products' | 'components') {
        return fixture.requests.filter((r) => r.path === fixture.api(`/${key}`));
    }

    /** The query of one request reduced to the list parameters, each asserted to occur once. */
    function paramsOf(query: URLSearchParams): Record<string, string> {
        const out: Record<string, string> = {};
        for (const name of LIST_PARAMS) {
            const values = query.getAll(name);
            expect(values.length, name).toBeLessThanOrEqual(1);
            if (values.length === 1) out[name] = values[0];
        }
        return out;
    }

    it('T-1052 teaProducts pages the fixture by the returned token; teaComponents sends the filter, the order and the size as given', async () => {
        configure({ REBOM_TEA_LOCAL_TESTING: 'ON' });

        const first = await call(Q.products, { apiRoot: fixture.root, credential: bearer() });
        expect(first.outcome, JSON.stringify(first.error)).toBe('OK');
        expect(first.error).toBeNull();
        expect(first.page.rows).toEqual(fixture.listRows('products').slice(0, 2));
        expect(first.page.next).toBe('TOKEN');
        expect(first.page.nextPageToken).toBe('products-page-2');
        expect(first.page.rowsReceived).toBe(2);
        expect(first.page.cut).toBe('NONE');
        let seen = listRequests('products');
        expect(seen).toHaveLength(1);
        expect(paramsOf(seen[0].query)).toEqual({ pageSize: '100' });
        expect(seen[0].authorization).toBe(`Bearer ${MARK_TOKEN}`);

        fixture.reset();
        const second = await call(Q.products, { apiRoot: fixture.root, credential: bearer(), pageToken: first.page.nextPageToken });
        expect(second.outcome, JSON.stringify(second.error)).toBe('OK');
        expect(second.page.rows).toEqual(fixture.listRows('products').slice(2));
        expect(second.page.next).toBe('NONE');
        expect(second.page.nextPageToken).toBeNull();
        seen = listRequests('products');
        expect(seen).toHaveLength(1);
        expect(paramsOf(seen[0].query)).toEqual({ pageSize: '100', pageToken: 'products-page-2' });
        expect(seen[0].authorization).toBe(`Bearer ${MARK_TOKEN}`);

        fixture.reset();
        const filtered = await call(Q.components, {
            apiRoot: fixture.root, credential: bearer(), idType: 'PURL', idValue: ID_VALUE, sortOrder: 'DESC', pageSize: 2
        });
        expect(filtered.outcome, JSON.stringify(filtered.error)).toBe('OK');
        expect(filtered.page.rows).toEqual(fixture.listRows('components').slice(0, 2));
        expect(filtered.page.next).toBe('TOKEN');
        expect(filtered.limits.pageSize).toBe(2);
        seen = listRequests('components');
        expect(seen).toHaveLength(1);
        expect(paramsOf(seen[0].query)).toEqual({ pageSize: '2', idType: 'PURL', idValue: ID_VALUE, sortOrder: 'desc' });
        expect(seen[0].authorization).toBe(`Bearer ${MARK_TOKEN}`);
        expect(listRequests('products')).toHaveLength(0);
    });

    it('T-1053 without local testing both operations refuse a loopback root and the fixture sees no connection', async () => {
        configure();
        for (const query of [Q.products, Q.components]) {
            for (const host of ['localhost', '127.0.0.1']) {
                fixture.reset();
                const result = await call(query, { apiRoot: `https://${host}:${fixture.port}/tea`, credential: NONE });
                expect(result.outcome, `${host} ${JSON.stringify(result.error)}`).toBe('ADDRESS_REFUSED');
                expect(result.error.addressClass).toBe('LOOPBACK');
                expect(result.error.cliExitCode).toBe(8);
                expect(result.page).toBeNull();
                expect(fixture.connections).toBe(0);
                expect(fixture.requests).toHaveLength(0);
            }
        }
    });
});
