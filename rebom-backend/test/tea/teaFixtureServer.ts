import { createHash } from 'crypto';
import * as http from 'http';
import { AddressInfo } from 'net';

/**
 * A TEA 1.0.0 server for the rows marked real (TPF-180 design section 4): plain http on
 * 127.0.0.1 with a free port, in the test process. Document shapes follow rearm-cli's
 * cmd/teaFixture_test.go. It records every connection and every request with its headers;
 * a row can make it demand a credential, delay or stall a body.
 */

export interface TeaRecordedRequest {
    method: string;
    path: string;
    query: URLSearchParams;
    authorization: string | undefined;
    headers: http.IncomingHttpHeaders;
}

export type TeaHandler = (req: http.IncomingMessage, res: http.ServerResponse, query: URLSearchParams) => void;

export const SAMPLE = {
    product: '33333333-3333-4333-8333-333333333333',
    component: '44444444-4444-4444-8444-444444444441',
    productRelease: '11111111-1111-4111-8111-111111111111',
    productRelease2: '22222222-2222-4222-8222-222222222222',
    componentRelease: '55555555-5555-4555-8555-555555555551',
    componentRelease2: '55555555-5555-4555-8555-555555555552',
    componentRelease3: '55555555-5555-4555-8555-555555555553'
};

export function sha256Hex(bytes: Buffer | string): string {
    return createHash('sha256').update(bytes).digest('hex');
}

export function sendJson(res: http.ServerResponse, status: number, body: unknown): void {
    const text = typeof body === 'string' ? body : JSON.stringify(body);
    res.writeHead(status, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(text) });
    res.end(text);
}

/** One artifact of a collection, as the fixture serves it. */
export interface TeaFixtureArtifact {
    uuid: string;
    type: string;
    name?: string;
    mediaType: string;
    body: Buffer;
    /** Checksums as listed; default: the right SHA-256 of body. */
    checksums?: Array<{ algType: string, algValue: string }>;
    /** Serve this instead of body (a wrong checksum is listed for body). */
    served?: Buffer;
    /** Stall: send half the body, then nothing. */
    stall?: boolean;
}

export class TeaFixtureServer {
    readonly requests: TeaRecordedRequest[] = [];
    connections = 0;
    /** When set, every path under /tea/ but the token endpoint needs Authorization: Bearer <value>. */
    requireBearer: string | null = null;
    /** The API key the token endpoint accepts and the token it issues. */
    apiKey: { id: string, secret: string, token: string } | null = null;
    private readonly routes = new Map<string, TeaHandler>();
    private server!: http.Server;
    port = 0;
    /** Requests currently held open by hold(). */
    private holds: Array<() => void> = [];

    /**
     * host: the name in every URL the fixture hands out. A TEI needs a name, not an address
     * (resolve rows use localhost); it listens on 127.0.0.1 either way.
     */
    constructor(readonly host: string = '127.0.0.1') {}

    get origin(): string {
        return `http://${this.host}:${this.port}`;
    }

    get root(): string {
        return `${this.origin}/tea`;
    }

    api(p: string): string {
        return `/tea/v1.0.0${p}`;
    }

    async start(): Promise<void> {
        this.server = http.createServer((req, res) => this.serve(req, res));
        this.server.on('connection', () => {
            this.connections++;
        });
        await new Promise<void>((resolve) => this.server.listen(0, '127.0.0.1', resolve));
        this.port = (this.server.address() as AddressInfo).port;
        this.route('/.well-known/tea', (_req, res) => sendJson(res, 200, { schemaVersion: 1, endpoints: [{ url: this.root, versions: ['1.0.0'] }] }));
    }

    async stop(): Promise<void> {
        for (const release of this.holds) release();
        this.holds = [];
        this.server.closeAllConnections();
        await new Promise<void>((resolve) => this.server.close(() => resolve()));
    }

    route(path: string, handler: TeaHandler): void {
        this.routes.set(path, handler);
    }

    json(path: string, body: unknown): void {
        this.route(path, (_req, res) => sendJson(res, 200, body));
    }

    /** Holds the answer of path until release() is called. */
    hold(path: string, body: unknown): () => void {
        let release!: () => void;
        const released = new Promise<void>((resolve) => {
            release = resolve;
        });
        this.holds.push(() => release());
        this.route(path, (_req, res) => {
            released.then(() => sendJson(res, 200, body));
        });
        return () => release();
    }

    reset(): void {
        this.requests.length = 0;
        this.connections = 0;
    }

    count(path: string): number {
        return this.requests.filter((r) => r.path === path).length;
    }

    private serve(req: http.IncomingMessage, res: http.ServerResponse): void {
        const url = new URL(req.url ?? '/', this.origin);
        this.requests.push({ method: req.method ?? '', path: url.pathname, query: url.searchParams, authorization: req.headers.authorization, headers: req.headers });
        const tokenPath = this.api('/token');
        if (url.pathname === tokenPath && this.apiKey) {
            const auth = req.headers.authorization ?? '';
            const decoded = auth.startsWith('Basic ') ? Buffer.from(auth.substring(6), 'base64').toString('utf8') : '';
            const at = decoded.indexOf(':');
            const id = decodeURIComponent(decoded.substring(0, at));
            const secret = decodeURIComponent(decoded.substring(at + 1));
            if (at < 0 || id !== this.apiKey.id || secret !== this.apiKey.secret) {
                sendJson(res, 401, { error: 'invalid_client' });
                return;
            }
            sendJson(res, 200, { access_token: this.apiKey.token, token_type: 'Bearer', expires_in: 3600 });
            return;
        }
        const needed = this.requireBearer ?? this.apiKey?.token ?? null;
        if (needed && url.pathname.startsWith('/tea/') && url.pathname !== tokenPath && req.headers.authorization !== `Bearer ${needed}`) {
            res.writeHead(401, { 'www-authenticate': 'Bearer realm="tea"' });
            res.end();
            return;
        }
        const handler = this.routes.get(url.pathname);
        if (!handler) {
            sendJson(res, 404, { error: 'OBJECT_UNKNOWN' });
            return;
        }
        handler(req, res, url.searchParams);
    }

    // -------------------------------------------------------------------------------------
    // Sample data

    productJson(uuid = SAMPLE.product, name = 'Example Product'): Record<string, unknown> {
        return { uuid, name, identifiers: [{ idType: 'PURL', idValue: 'pkg:generic/example/product' }] };
    }

    /** product, component and the discovery of a product release. */
    serveObjects(): void {
        this.json(this.api(`/product/${SAMPLE.product}`), this.productJson());
        this.json(this.api(`/component/${SAMPLE.component}`), { uuid: SAMPLE.component, name: 'Example Component', identifiers: [] });
        this.serveDiscovery([SAMPLE.productRelease]);
    }

    serveDiscovery(ids: string[]): void {
        this.json(this.api('/discovery'), ids.map((id) => ({ productReleaseUuid: id, servers: [{ rootUrl: this.root, versions: ['1.0.0'] }] })));
    }

    /** The releases of the sample product: three pages chained by tokens. */
    serveReleasePages(): void {
        const item = (uuid: string, version: string) => ({ uuid, product: SAMPLE.product, productName: 'P', version, createdDate: '2026-01-10T08:00:00Z', components: [] });
        this.route(this.api(`/product/${SAMPLE.product}/releases`), (_req, res, query) => {
            switch (query.get('pageToken') ?? '') {
                case '':
                    return sendJson(res, 200, { hasNext: true, nextPageToken: 'tok-2', results: [item(SAMPLE.productRelease, '1')] });
                case 'tok-2':
                    return sendJson(res, 200, { hasNext: true, nextPageToken: 'tok-3', results: [item(SAMPLE.productRelease2, '2')] });
                case 'tok-3':
                    return sendJson(res, 200, { hasNext: false, results: [item(SAMPLE.componentRelease, '3')] });
            }
            sendJson(res, 400, { error: 'INVALID_PAGE_TOKEN' });
        });
    }

    collectionJson(id: string, belongsTo: string, artifacts: TeaFixtureArtifact[], version = 1): Record<string, unknown> {
        return {
            uuid: id, version, createdDate: '2026-01-15T00:00:00Z', belongsTo, updateReason: { type: 'INITIAL_RELEASE' },
            artifacts: artifacts.map((a) => ({
                uuid: a.uuid, version: 1, name: a.name ?? a.uuid, type: a.type, createdDate: '2026-01-10T08:05:00Z',
                formats: [{ mediaType: a.mediaType, checksums: a.checksums ?? [{ algType: 'SHA-256', algValue: sha256Hex(a.body) }] }]
            }))
        };
    }

    /** A product release with its latest collection and lifecycle. */
    serveProductRelease(artifacts: TeaFixtureArtifact[] = []): void {
        const id = SAMPLE.productRelease;
        this.json(this.api(`/productRelease/${id}`), {
            uuid: id, product: SAMPLE.product, productName: 'Example Product', version: '1.2.3', createdDate: '2026-01-10T08:00:00Z',
            components: [{ uuid: SAMPLE.component, release: SAMPLE.componentRelease }],
            latestCollection: this.collectionJson(id, 'PRODUCT_RELEASE', artifacts)
        });
        this.json(this.api(`/productRelease/${id}/cle`), { events: [] });
    }

    /** A component release whose collection version 1 holds artifacts, each served at its download URL. */
    serveComponentCollection(artifacts: TeaFixtureArtifact[], id = SAMPLE.componentRelease): void {
        const collection = this.collectionJson(id, 'COMPONENT_RELEASE', artifacts);
        this.json(this.api(`/componentRelease/${id}`), {
            uuid: id, component: SAMPLE.component, componentName: 'C', version: '1.0', createdDate: '2026-01-15T00:00:00Z', latestCollection: collection
        });
        this.json(this.api(`/componentRelease/${id}/collection/1`), collection);
        for (const a of artifacts) {
            this.route(this.api(`/artifact/${a.uuid}/1/download`), (_req, res) => {
                const bytes = a.served ?? a.body;
                res.writeHead(200, { 'content-type': 'application/octet-stream', 'content-length': bytes.length });
                if (a.stall) {
                    res.write(bytes.subarray(0, Math.floor(bytes.length / 2)));
                    return;
                }
                res.end(bytes);
            });
        }
    }
}
