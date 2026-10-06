import { describe, it, expect, vi, beforeAll, afterAll } from 'vitest';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { createHash } from 'crypto';

// Wrap runQuery in a spy that still calls through: scoring must not reach it.
vi.mock('../../src/db/query', async (importOriginal) => {
    const actual = await importOriginal<typeof import('../../src/db/query')>();
    return { ...actual, runQuery: vi.fn(actual.runQuery) };
});

import { ApolloServer } from '@apollo/server';
import typeDefs from '../../src/schema.graphql';
import resolvers from '../../src/bomResolver';
import { runQuery } from '../../src/db/query';
import { pool } from '../../src/utils';

/**
 * scoreBomProbe end to end: real schema, resolver, error mapping and the real
 * `rearm bomutils score` on PATH. SCORE-4 design 4.3.
 */

const FIXTURES = path.join(__dirname, '..', 'fixtures', 'score');
const ALL_PROFILES = ['cisa-2026', 'ntia-2021', 'fda'];
const VERDICTS = ['READY', 'NOT_READY', 'UNKNOWN'];
const QUERY = 'query Score($bom: String!, $profiles: [String!]!) { scoreBomProbe(bom: $bom, profiles: $profiles) }';

function fixture(name: string): string {
    return fs.readFileSync(path.join(FIXTURES, name), 'utf8');
}

// I-0: the image pins the CLI with `bomutils score` for linux/amd64 only until the
// repin to a released zip (SCORE-4 design 3.7); arm64 keeps CLI 26.07.9, which has
// no score command. On x64 nothing is skipped: a CLI without the command fails here.
describe.skipIf(process.arch !== 'x64')('scoreBomProbe with the real rearm CLI', () => {
    const server = new ApolloServer({ typeDefs, resolvers });
    let mkdtempSpy: ReturnType<typeof vi.spyOn>;
    let poolQuerySpy: ReturnType<typeof vi.spyOn>;
    let poolConnectSpy: ReturnType<typeof vi.spyOn>;

    async function score(bom: string, profiles: string[]) {
        const response = await server.executeOperation({ query: QUERY, variables: { bom, profiles } });
        if (response.body.kind !== 'single') throw new Error('expected a single result');
        return response.body.singleResult;
    }

    async function scored(bom: string, profiles: string[]): Promise<string> {
        const result = await score(bom, profiles);
        expect(result.errors).toBeUndefined();
        return (result.data as any).scoreBomProbe as string;
    }

    async function refused(bom: string, profiles: string[]) {
        const result = await score(bom, profiles);
        expect(result.errors).toHaveLength(1);
        const error = result.errors![0];
        expect(error.extensions?.code).toBe('BOM_SCORE_ERROR');
        return { reason: (error.extensions?.details as any)?.reason, message: error.message };
    }

    beforeAll(async () => {
        await server.start();
        mkdtempSpy = vi.spyOn(fs.promises, 'mkdtemp');
        poolQuerySpy = vi.spyOn(pool, 'query');
        poolConnectSpy = vi.spyOn(pool, 'connect');
        vi.mocked(runQuery).mockClear();
    });

    afterAll(async () => {
        // I-6: nothing stored and no file left behind by any call of this suite
        expect(vi.mocked(runQuery)).not.toHaveBeenCalled();
        expect(poolQuerySpy).not.toHaveBeenCalled();
        expect(poolConnectSpy).not.toHaveBeenCalled();
        const dirs: string[] = [];
        for (const r of mkdtempSpy.mock.results) {
            if (r.type === 'return') dirs.push(await r.value);
        }
        expect(dirs.length).toBeGreaterThan(0);
        for (const dir of dirs) expect(fs.existsSync(dir)).toBe(false);
        vi.restoreAllMocks();
        await server.stop();
    });

    it('I-1 scores CycloneDX JSON against all three profiles in the order asked', async () => {
        const bom = fixture('full.cdx.json');
        const report = JSON.parse(await scored(bom, ALL_PROFILES));
        expect(report.reportVersion).toBe(1);
        expect(report.input.format).toBe('CycloneDX');
        expect(report.input.serialization).toBe('json');
        const fileSha = createHash('sha256').update(fs.readFileSync(path.join(FIXTURES, 'full.cdx.json'))).digest('hex');
        expect(report.input.sha256).toBe(fileSha);
        expect(report.profiles.map((p: any) => p.key)).toEqual(ALL_PROFILES);
        for (const p of report.profiles) expect(VERDICTS).toContain(p.verdict);
    });

    it('I-1 keeps the order the caller gave', async () => {
        const report = JSON.parse(await scored(fixture('full.cdx.json'), ['fda', 'cisa-2026']));
        expect(report.profiles.map((p: any) => p.key)).toEqual(['fda', 'cisa-2026']);
    });

    it.each([
        ['full.cdx.xml', 'CycloneDX', 'xml'],
        ['full.spdx', 'SPDX', 'tag-value'],
        ['full.spdx.yaml', 'SPDX', 'yaml']
    ])('I-2 scores %s', async (name, format, serialization) => {
        const report = JSON.parse(await scored(fixture(name), ['fda']));
        expect(report.input.format).toBe(format);
        expect(report.input.serialization).toBe(serialization);
    });

    it.each([
        ['spdx-rdf.xml', () => fixture('spdx-rdf.xml'), 'unsupported SBOM: SPDX RDF/XML is not supported'],
        ['not-a-bom.json', () => fixture('not-a-bom.json'), 'unsupported SBOM: not a CycloneDX or SPDX document'],
        ['broken.cdx.json', () => fixture('broken.cdx.json'), 'unparsable SBOM: '],
        ['the empty string', () => '', 'unsupported SBOM: empty input']
    ])('I-3 refuses %s as INPUT_REFUSED', async (_label, content, messageStart) => {
        const bom = content();
        const { reason, message } = await refused(bom, ['fda']);
        expect(reason).toBe('INPUT_REFUSED');
        expect(message.startsWith(messageStart)).toBe(true);
        expect(message).toMatch(/^(unsupported|unparsable) SBOM: /);
    });

    it('I-4 refuses profile nope as UNKNOWN_PROFILE', async () => {
        const { reason } = await refused(fixture('full.cdx.json'), ['nope']);
        expect(reason).toBe('UNKNOWN_PROFILE');
    });

    it('I-5 returns identical strings for identical calls', async () => {
        const bom = fixture('full.spdx.yaml');
        const first = await scored(bom, ALL_PROFILES);
        const second = await scored(bom, ALL_PROFILES);
        expect(second === first).toBe(true);
    });

    it('I-7 answers CLI_UNAVAILABLE when no rearm is on PATH', async () => {
        const emptyDir = await fs.promises.mkdtemp(path.join(os.tmpdir(), 'rebom-score-nopath-'));
        const savedPath = process.env.PATH;
        try {
            process.env.PATH = emptyDir;
            const { reason } = await refused(fixture('full.cdx.json'), ['fda']);
            expect(reason).toBe('CLI_UNAVAILABLE');
        } finally {
            process.env.PATH = savedPath;
            await fs.promises.rm(emptyDir, { recursive: true, force: true });
        }
    });
});
