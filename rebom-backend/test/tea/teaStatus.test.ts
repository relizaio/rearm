import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { readTeaConfig } from '../../src/services/tea/teaConfig';
import { cleanupDirs, configure, field, gql, Http, Q, startHttp, Stub } from './teaTestKit';

/**
 * teaStatus (TPF-180 design 3.3; row T-78). Rows T-79 (the image build) and T-80 (the deploy
 * directory) are commands recorded in the pull request.
 */

describe('TEA status', () => {
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

    it('T-78 a good probe: OK with the ceilings of the configuration; a bad probe: NOT_AVAILABLE', async () => {
        stub = new Stub();
        const env = { REBOM_TEA_MAX_FILES: '77', REBOM_TEA_MAX_PAGES: '9', REBOM_TEA_HOLD_SECONDS: '99' };
        configure(env);
        const read = readTeaConfig(env);
        if (!read.ok) throw new Error('valid settings');
        const c = read.config.ceilings;
        const ok = field(await gql(http.url, Q.status));
        expect(ok.outcome).toBe('OK');
        expect(ok.teaApiVersion).toBe(1);
        expect(ok.ceilings).toEqual({
            deadlineSeconds: c.downloadDeadlineSeconds,
            connectTimeoutSeconds: c.connectTimeoutSeconds,
            maxDocumentBytes: c.maxDocumentBytes,
            maxOutputBytes: c.maxOutputBytes,
            maxPages: 9,
            pageSize: 100,
            maxArtifactBytes: c.maxArtifactBytes,
            maxTotalBytes: c.maxTotalBytes,
            maxFiles: 77,
            holdSeconds: 99
        });
        stub.write('probe.exit', '0');
        stub.write('probe.stderr', '');
        configure(env);
        const bad = field(await gql(http.url, Q.status));
        expect(bad.outcome).toBe('NOT_AVAILABLE');
        expect(bad.error.reason).toBe('CLI_TOO_OLD');
    });
});
