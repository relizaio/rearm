import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import * as http from 'node:http';
import { AddressInfo } from 'node:net';

import { enrichCycloneDxBom } from '../../src/services/bom/bomProcessingService';

/**
 * The failure text of the pinned rearm CLI, bounded and cleaned (TPF-261 design 3.2, 3.3; row
 * T-1251). The real `rearm` from PATH, started by the real enrichCycloneDxBom, against a BEAR
 * stand-in on 127.0.0.1 in this process that answers 500 with a body, and against a port nothing
 * listens on, both with a URI with userinfo. This runs in the image's builder stage, so a new pin
 * that words the body line differently fails the build. USERW, PASSW and BODYW are plain marker
 * words; no network.
 */
describe('the pinned rearm CLI: the failure text holds no userinfo and no response body', () => {
    let server: http.Server;
    let port: number;
    let closedPort: number;

    const bom = () => ({
        bomFormat: 'CycloneDX', specVersion: '1.6', serialNumber: 'urn:uuid:7a2b3c4d-5e6f-4a8b-9c0d-1e2f3a4b5c6d', version: 1,
        metadata: { component: { type: 'application', name: 'failure-text-check', version: '1.0.0', 'bom-ref': 'root' } },
        components: [{ type: 'library', name: 'left-pad', version: '1.3.0', purl: 'pkg:npm/left-pad@1.3.0', 'bom-ref': 'pkg:npm/left-pad@1.3.0' }]
    });

    function expectClean(text: string): void {
        for (const m of ['USERW', 'PASSW', 'BODYW', '@', '://', '"']) expect(text).not.toContain(m);
        expect(text.length).toBeLessThanOrEqual(300);
    }

    beforeAll(async () => {
        server = http.createServer((req, res) => {
            req.resume();
            req.on('end', () => {
                res.writeHead(500, { 'Content-Type': 'application/json' });
                res.end('{"errors":[{"message":"BODYW internal"}]}\nBODYW second line');
            });
        });
        await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', () => resolve()));
        port = (server.address() as AddressInfo).port;
        const probe = http.createServer();
        await new Promise<void>((resolve) => probe.listen(0, '127.0.0.1', () => resolve()));
        closedPort = (probe.address() as AddressInfo).port;
        await new Promise<void>((resolve) => probe.close(() => resolve()));
    });

    afterAll(async () => {
        await new Promise<void>((resolve) => server.close(() => resolve()));
    });

    it('T-1251 (a) a 500 with a body: the text ends at the status', async () => {
        const result = await enrichCycloneDxBom(bom(), `http://USERW:PASSW@127.0.0.1:${port}`, '', []);

        expect(result.success).toBe(false);
        expect(result.error).toBe('rearm exited with code 1: Error during enrichment: failed to enrich batch 1-1: BEAR API returned status 500');
        expectClean(result.error!);
    });

    it('T-1251 (b) a port nothing listens on: connection refused and the mark where the URL was', async () => {
        const result = await enrichCycloneDxBom(bom(), `http://USERW:PASSW@127.0.0.1:${closedPort}`, '', []);

        expect(result.success).toBe(false);
        expect(result.error).toContain('connection refused');
        expect(result.error).toContain('[removed]');
        expect(result.error!.startsWith('rearm exited with code 1: Error during enrichment:')).toBe(true);
        expectClean(result.error!);
    });
});
