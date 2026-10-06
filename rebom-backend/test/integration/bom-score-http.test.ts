import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import * as fs from 'fs';
import * as path from 'path';
import { ApolloServer } from '@apollo/server';
import { createGraphqlServer, startGraphqlHttp } from '../../src/graphqlServer';

/**
 * scoreBomProbe over real HTTP, through the server production starts (src/graphqlServer.ts).
 * Pins where child C meets rebom: the 52428800-byte limit is on the whole request body,
 * and an over-size body is answered with HTTP 413, not a GraphQL error. SCORE-4 design 3.5, 3.8, 4.4.
 */

const FIXTURES = path.join(__dirname, '..', 'fixtures', 'score');
const BODY_LIMIT = 52428800;
const QUERY = 'query($bom: String!, $profiles: [String!]!) { scoreBomProbe(bom: $bom, profiles: $profiles) }';
const LARGE_BODY_TIMEOUT_MS = 60000;

/** The JSON request body, as the exact string sent. */
function body(bom: string, profiles: string[]): string {
    return JSON.stringify({ query: QUERY, variables: { bom, profiles } });
}

/** A body of exactly `bytes` bytes, the `bom` variable padded with the letter a (no escaping). */
function bodyOfLength(bytes: number, profiles: string[]): string {
    const overhead = Buffer.byteLength(body('', profiles));
    const sized = body('a'.repeat(bytes - overhead), profiles);
    expect(Buffer.byteLength(sized)).toBe(bytes);
    return sized;
}

describe('scoreBomProbe over HTTP', () => {
    let server: ApolloServer;
    let url: string;

    async function post(requestBody: string): Promise<Response> {
        return fetch(url, {
            method: 'POST',
            headers: { 'content-type': 'application/json' },
            body: requestBody
        });
    }

    async function expectUnknownProfile(response: Response): Promise<void> {
        expect(response.status).toBe(200);
        expect(response.headers.get('content-type')).toContain('application/json');
        const json = await response.json() as any;
        expect(json.errors).toHaveLength(1);
        expect(json.errors[0].extensions.code).toBe('BOM_SCORE_ERROR');
        expect(json.errors[0].extensions.details.reason).toBe('UNKNOWN_PROFILE');
    }

    beforeAll(async () => {
        server = createGraphqlServer();
        // port 0: any free port
        ({ url } = await startGraphqlHttp(server, 0));
    });

    afterAll(async () => {
        await server?.stop();
    });

    it('HT-1 answers a scoring error as a GraphQL error with HTTP 200', async () => {
        await expectUnknownProfile(await post(body('{}', ['nope'])));
    });

    it('HT-2 accepts a request body of exactly 52428800 bytes', async () => {
        await expectUnknownProfile(await post(bodyOfLength(BODY_LIMIT, ['nope'])));
    }, LARGE_BODY_TIMEOUT_MS);

    it('HT-3 refuses a request body of 52428801 bytes with HTTP 413, not JSON', async () => {
        const response = await post(bodyOfLength(BODY_LIMIT + 1, ['nope']));
        expect(response.status).toBe(413);
        expect(response.headers.get('content-type') ?? '').not.toContain('application/json');
        await response.arrayBuffer();
    }, LARGE_BODY_TIMEOUT_MS);

    it('HT-4 limits the body, not the document: 30 MiB of quotes is refused with HTTP 413', async () => {
        const bom = '"'.repeat(31457280);
        const response = await post(body(bom, ['cisa-2026']));
        expect(response.status).toBe(413);
        await response.arrayBuffer();
    }, LARGE_BODY_TIMEOUT_MS);

    // Same gate as I-0 of bom-score.test.ts: the CLI with bomutils score is pinned for
    // linux/amd64 only until the repin (SCORE-4 design 3.7). Only this case needs the CLI.
    it.skipIf(process.arch !== 'x64')('HT-5 returns the report verbatim over HTTP', async () => {
        const bom = fs.readFileSync(path.join(FIXTURES, 'full.cdx.json'), 'utf8');
        const profiles = ['cisa-2026', 'ntia-2021', 'fda'];

        const response = await post(body(bom, profiles));
        expect(response.status).toBe(200);
        const json = await response.json() as any;
        expect(json.errors).toBeUndefined();
        const overHttp: string = json.data.scoreBomProbe;

        const inProcess = await server.executeOperation({ query: QUERY, variables: { bom, profiles } });
        if (inProcess.body.kind !== 'single') throw new Error('expected a single result');
        expect(inProcess.body.singleResult.errors).toBeUndefined();
        const direct = (inProcess.body.singleResult.data as any).scoreBomProbe as string;

        expect(overHttp === direct).toBe(true);
        expect(JSON.parse(overHttp).reportVersion).toBe(1);
    }, LARGE_BODY_TIMEOUT_MS);
});
