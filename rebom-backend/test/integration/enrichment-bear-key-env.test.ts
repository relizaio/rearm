import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import * as http from 'node:http';
import { AddressInfo } from 'node:net';

import { enrichCycloneDxBom } from '../../src/services/bom/bomProcessingService';

/**
 * The pinned rearm CLI reads the BEAR key from REARM_BEARAPIKEY (TPF-254 design 3.6; row T-1007).
 * The real `rearm` from PATH, started by the real enrichCycloneDxBom, against a BEAR stand-in on
 * 127.0.0.1 in this process: the key must arrive as the X-API-Key header. This runs in the image's
 * builder stage, so an image whose CLI stops reading the variable does not build. No network: the
 * CLI's default BEAR client dials an IP literal directly.
 */
describe('the pinned rearm CLI sends the key it finds in REARM_BEARAPIKEY', () => {
    const MARK = 'markerkey';
    const requests: { method?: string, url?: string, headers: http.IncomingHttpHeaders }[] = [];
    let server: http.Server;
    let uri: string;
    let saved: string | undefined;

    const bom = () => ({
        bomFormat: 'CycloneDX', specVersion: '1.6', serialNumber: 'urn:uuid:6f1c2d3e-4b5a-4978-8a1b-2c3d4e5f6a7b', version: 1,
        metadata: { component: { type: 'application', name: 'key-env-check', version: '1.0.0', 'bom-ref': 'root' } },
        components: [{ type: 'library', name: 'left-pad', version: '1.3.0', purl: 'pkg:npm/left-pad@1.3.0', 'bom-ref': 'pkg:npm/left-pad@1.3.0' }]
    });

    beforeAll(async () => {
        server = http.createServer((req, res) => {
            req.resume();
            req.on('end', () => {
                requests.push({ method: req.method, url: req.url, headers: req.headers });
                res.writeHead(200, { 'Content-Type': 'application/json' });
                res.end('{"data":{"enrichBatch":[]}}');
            });
        });
        await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', () => resolve()));
        uri = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
    });

    afterAll(async () => {
        await new Promise<void>((resolve) => server.close(() => resolve()));
    });

    afterEach(() => {
        requests.length = 0;
        if (saved === undefined) delete process.env.REARM_BEARAPIKEY;
        else process.env.REARM_BEARAPIKEY = saved;
    });

    it('T-1007 a key: one POST /graphql carrying X-API-Key with the key, and the BOM comes back', async () => {
        saved = process.env.REARM_BEARAPIKEY;
        delete process.env.REARM_BEARAPIKEY;

        const result = await enrichCycloneDxBom(bom(), uri, MARK, []);

        expect(result.error).toBeUndefined();
        expect(result.success).toBe(true);
        expect(result.enrichedBom?.components?.[0]?.purl).toBe('pkg:npm/left-pad@1.3.0');
        expect(requests).toHaveLength(1);
        expect(requests[0].method).toBe('POST');
        expect(requests[0].url).toBe('/graphql');
        expect(requests[0].headers['x-api-key']).toBe(MARK);
        expect(process.env.REARM_BEARAPIKEY).toBeUndefined();
    });

    it('T-1007 an empty key with REARM_BEARAPIKEY planted in rebom\'s environment: one request, no X-API-Key header', async () => {
        saved = process.env.REARM_BEARAPIKEY;
        process.env.REARM_BEARAPIKEY = 'outside';

        const result = await enrichCycloneDxBom(bom(), uri, '', []);

        expect(result.error).toBeUndefined();
        expect(result.success).toBe(true);
        expect(requests).toHaveLength(1);
        expect(requests[0].url).toBe('/graphql');
        expect(requests[0].headers['x-api-key']).toBeUndefined();
        expect(process.env.REARM_BEARAPIKEY).toBe('outside');
    });
});
