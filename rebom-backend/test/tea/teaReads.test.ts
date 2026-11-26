import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import * as http from 'http';
import { AddressInfo } from 'net';
import { cleanupDirs, configure, field, gql, Http, LogSpy, MARKERS, MARK_TOKEN, Q, spyLogs, startHttp } from './teaTestKit';
import { SAMPLE, TeaFixtureServer } from './teaFixtureServer';

/**
 * The read operations against the fixture server with the real CLI (TPF-180 design 3.3, 3.4, 3.5;
 * rows T-55 to T-57, and T-24 for T-55: the credential is a marker in no result and no log line).
 */

describe('TEA reads (real)', () => {
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
        configure({ REBOM_TEA_LOCAL_TESTING: 'ON' });
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
    const none = { kind: 'NONE' };

    it('T-55 the five reads answer OK with the fixture\'s content; paging with maxPages 2 and the page token', async () => {
        fixture.serveObjects();
        fixture.serveReleasePages();
        fixture.serveProductRelease();
        const tei = `tei://localhost/uuid/${SAMPLE.productRelease}`;

        const resolved = await call(Q.resolve, { tei, credential: none, localPlainHttpPort: fixture.port });
        expect(resolved.outcome, JSON.stringify(resolved.error)).toBe('OK');
        const resolution = JSON.parse(resolved.document);
        expect(resolution.kind).toBe('TEA_RESOLUTION');
        expect(resolution.results[0].productReleaseUuid).toBe(SAMPLE.productRelease);

        const product = await call(Q.product, { apiRoot: fixture.root, uuid: SAMPLE.product, credential: bearer() });
        expect(product.outcome, JSON.stringify(product.error)).toBe('OK');
        expect(JSON.parse(product.document).product.name).toBe('Example Product');
        expect(fixture.requests.find((r) => r.path === fixture.api(`/product/${SAMPLE.product}`))!.authorization).toBe(`Bearer ${MARK_TOKEN}`);

        const component = await call(Q.component, { apiRoot: fixture.root, uuid: SAMPLE.component, credential: none });
        expect(component.outcome).toBe('OK');
        expect(JSON.parse(component.document).kind).toBe('TEA_COMPONENT');
        expect(JSON.parse(component.document).component.name).toBe('Example Component');
        expect(fixture.requests.find((r) => r.path === fixture.api(`/component/${SAMPLE.component}`))!.authorization).toBeUndefined();

        const firstPages = await call(Q.releases, { apiRoot: fixture.root, parentKind: 'PRODUCT', parentUuid: SAMPLE.product, maxPages: 2, credential: none });
        expect(firstPages.outcome).toBe('OK');
        expect(firstPages.limits.maxPages).toBe(2);
        expect(firstPages.limits.pageSize).toBe(100);
        const list = JSON.parse(firstPages.document);
        expect(list.kind).toBe('TEA_RELEASE_LIST');
        expect(list.releases.map((r: any) => r.version)).toEqual(['1', '2']);
        expect(list.page.stoppedBy).toBe('MAX_PAGES');
        expect(list.page.nextPageToken).toBe('tok-3');
        const rest = await call(Q.releases, { apiRoot: fixture.root, parentKind: 'PRODUCT', parentUuid: SAMPLE.product, pageToken: list.page.nextPageToken, credential: none });
        expect(JSON.parse(rest.document).releases.map((r: any) => r.version)).toEqual(['3']);
        expect(JSON.parse(rest.document).page.stoppedBy).toBe('END');

        const release = await call(Q.release, { apiRoot: fixture.root, releaseKind: 'PRODUCT_RELEASE', releaseUuid: SAMPLE.productRelease, credential: none });
        expect(release.outcome, JSON.stringify(release.error)).toBe('OK');
        const doc = JSON.parse(release.document);
        expect(doc.kind).toBe('TEA_RELEASE');
        expect(doc.release.version).toBe('1.2.3');
        expect(doc.release.components).toEqual([{ componentUuid: SAMPLE.component, releaseUuid: SAMPLE.componentRelease }]);
    });

    it('T-56 unknown uuid, another token, a closed port, a large document, a stalled body: the CLI\'s outcomes', async () => {
        fixture.serveObjects();
        const unknown = await call(Q.product, { apiRoot: fixture.root, uuid: '99999999-9999-4999-8999-999999999999', credential: none });
        expect(unknown.outcome).toBe('NOT_FOUND');

        fixture.requireBearer = 'another-token';
        const auth = await call(Q.product, { apiRoot: fixture.root, uuid: SAMPLE.product, credential: bearer() });
        expect(auth.outcome).toBe('AUTH');
        expect(auth.error.httpStatus).toBe(401);
        fixture.requireBearer = null;

        const closed = http.createServer();
        await new Promise<void>((resolve) => closed.listen(0, '127.0.0.1', resolve));
        const closedPort = (closed.address() as AddressInfo).port;
        await new Promise<void>((resolve) => closed.close(() => resolve()));
        const refused = await call(Q.product, { apiRoot: `http://127.0.0.1:${closedPort}/tea`, uuid: SAMPLE.product, credential: none });
        expect(refused.outcome).toBe('CONNECT_FAILED');
        expect(refused.error.hint).toBeTruthy();

        fixture.json(fixture.api(`/product/${SAMPLE.product}`), fixture.productJson(SAMPLE.product, 'x'.repeat(4000)));
        const large = await call(Q.product, { apiRoot: fixture.root, uuid: SAMPLE.product, credential: none, limits: { maxDocumentBytes: 1024 } });
        expect(large.outcome).toBe('LIMIT');
        expect(large.error.reason).toBe('DOCUMENT_BYTES');

        fixture.route(fixture.api(`/product/${SAMPLE.product}`), (_req, res) => {
            res.writeHead(200, { 'content-type': 'application/json', 'content-length': 1000 });
            res.write('{"uuid":');
        });
        const started = Date.now();
        const stalled = await call(Q.product, { apiRoot: fixture.root, uuid: SAMPLE.product, credential: none, limits: { deadlineSeconds: 2 } });
        expect(stalled.outcome).toBe('LIMIT');
        expect(Date.now() - started).toBeLessThan(15000);
    });

    it('T-57 discovery with two results is OK and both are in the document', async () => {
        fixture.serveDiscovery([SAMPLE.productRelease, SAMPLE.productRelease2]);
        const resolved = await call(Q.resolve, { tei: `tei://localhost/uuid/${SAMPLE.productRelease}`, credential: none, localPlainHttpPort: fixture.port });
        expect(resolved.outcome).toBe('OK');
        expect(JSON.parse(resolved.document).results.map((r: any) => r.productReleaseUuid)).toEqual([SAMPLE.productRelease, SAMPLE.productRelease2]);
    });
});

