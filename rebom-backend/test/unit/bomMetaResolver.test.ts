import { describe, it, expect, vi, beforeAll, afterAll, beforeEach } from 'vitest';

const svc = vi.hoisted(() => ({
    findAllBoms: vi.fn(), addBom: vi.fn(), mergeAndStoreBoms: vi.fn(), teaStoreFetchedSbom: vi.fn()
}));

// The service functions behind the four operations answer a stored row; BomMapper stays real.
vi.mock('../../src/bomService', async (importOriginal) => {
    const actual: any = await importOriginal();
    return {
        ...actual,
        findAllBoms: (...a: any[]) => svc.findAllBoms(...a),
        addBom: (...a: any[]) => svc.addBom(...a),
        mergeAndStoreBoms: (...a: any[]) => svc.mergeAndStoreBoms(...a)
    };
});
vi.mock('../../src/services/tea/teaService', async (importOriginal) => {
    const actual: any = await importOriginal();
    return { ...actual, teaStoreFetchedSbom: (...a: any[]) => svc.teaStoreFetchedSbom(...a) };
});

import { ApolloServer } from '@apollo/server';
import { createGraphqlServer } from '../../src/graphqlServer';
import { setTeaCallerToken, TeaRequestContext } from '../../src/services/tea/teaCallerGate';
import { teaResult, TeaOutcome } from '../../src/services/tea/teaOutcome';
import { BomMapper } from '../../src/services/bom/bomMapper';
import { BomRecord, RebomOptions } from '../../src/types';

/**
 * Every answer of type Bom serves its meta through the Bom.meta field resolver, so an old row's
 * enrichmentError is served bounded whichever function produced the row (TPF-261 design 3.9; row
 * T-1253). The four operations run through the executed schema as production builds it. USERW,
 * PASSW, BODYW and FILLERW are plain marker words.
 */

const TOKEN = 'caller-token-for-the-bom-meta-resolver-test-0123456789';
const OLD_TEXT = 'Using BEAR at http://USERW:PASSW@host\n' + 'filler FILLERW line\n'.repeat(55000)
    + 'Error during enrichment: failed to enrich batch 1-1: BEAR API returned status 502: {"message":"BODYW"} BODYW\n';
const SERVED = 'Error during enrichment: failed to enrich batch 1-1: BEAR API returned status 502';
const MARKERS = ['USERW', 'PASSW', 'BODYW', 'FILLERW'];

type Variant = 'old' | 'absent' | 'null';

function row(variant: Variant): BomRecord {
    const meta: any = {
        name: 'app', group: 'org.example', bomVersion: '3', serialNumber: 'urn:uuid:1', version: '1.0.0',
        enrichmentStatus: 'FAILED', enrichmentTimestamp: '2026-11-27T00:00:00.000Z'
    };
    if (variant === 'old') meta.enrichmentError = OLD_TEXT;
    if (variant === 'null') meta.enrichmentError = null;
    return {
        uuid: '11111111-2222-3333-4444-555555555555', created_date: new Date(0), last_updated_date: new Date(0),
        meta: meta as RebomOptions, bom: { ociDigest: 'sha256:x' }, tags: {}, organization: 'org-1', public: false, duplicate: false
    };
}

const OPERATIONS: Array<[string, string, Record<string, unknown>, (data: any) => any]> = [
    ['allBoms', 'query { allBoms { uuid meta } }', {}, (d) => d.allBoms[0]],
    ['addBom', 'mutation($bomInput: BomInput!) { addBom(bomInput: $bomInput) { uuid meta } }',
        { bomInput: { format: 'CYCLONEDX', bom: {}, org: 'org-1', rebomOptions: { name: 'app' } } }, (d) => d.addBom],
    ['mergeAndStoreBoms', 'mutation($ids: [ID]!, $opts: RebomOptions!) { mergeAndStoreBoms(ids: $ids, rebomOptions: $opts, org: "org-1") { uuid meta } }',
        { ids: ['a', 'b'], opts: { name: 'app' } }, (d) => d.mergeAndStoreBoms],
    ['teaStoreFetchedSbom', 'mutation($input: TeaSbomStoreInput!) { teaStoreFetchedSbom(input: $input) { outcome stored bom { uuid meta } } }',
        { input: { fetchId: 'f-1', number: 1, org: 'org-1', rebomOptions: { name: 'app' } } }, (d) => d.teaStoreFetchedSbom.bom]
];

function answerWith(stored: BomRecord | null): void {
    svc.findAllBoms.mockResolvedValue(stored ? [BomMapper.toDto(stored)] : []);
    svc.addBom.mockResolvedValue(stored);
    svc.mergeAndStoreBoms.mockResolvedValue(stored);
    svc.teaStoreFetchedSbom.mockResolvedValue({
        ...teaResult(TeaOutcome.OK), stored: 'EXISTING', format: 'CYCLONEDX', bom: stored, conflict: null, storeMessage: null
    });
}

describe('T-1253 every answer of type Bom serves meta.enrichmentError bounded', () => {
    let server: ApolloServer<TeaRequestContext>;

    async function run(query: string, variables: Record<string, unknown>): Promise<{ text: string, data: any }> {
        const response = await server.executeOperation({ query, variables }, { contextValue: { teaCallerToken: TOKEN } });
        const text = JSON.stringify(response);
        expect(response.body.kind).toBe('single');
        const single = (response.body as any).singleResult;
        expect(single.errors).toBeUndefined();
        return { text, data: single.data };
    }

    beforeAll(async () => {
        setTeaCallerToken(TOKEN);
        server = createGraphqlServer();
        await server.start();
    });

    afterAll(async () => {
        await server.stop();
        setTeaCallerToken(null);
    });

    beforeEach(() => {
        for (const fn of Object.values(svc)) fn.mockReset();
    });

    for (const [name, query, variables, pick] of OPERATIONS) {
        it(`${name}: the old megabyte text is served bounded, every other key as stored, the row untouched`, async () => {
            const stored = row('old');
            const storedMeta = stored.meta;
            answerWith(stored);

            const { text, data } = await run(query, variables);

            const meta = pick(data).meta;
            expect(meta.enrichmentError).toBe(SERVED);
            for (const key of Object.keys(storedMeta)) if (key !== 'enrichmentError') expect(meta[key]).toStrictEqual((storedMeta as any)[key]);
            for (const m of MARKERS) expect(text).not.toContain(m);
            expect(stored.meta).toBe(storedMeta);
            expect(stored.meta.enrichmentError).toBe(OLD_TEXT);
        });

        it(`${name}: absent stays absent and null stays null`, async () => {
            answerWith(row('absent'));
            const absent = pick((await run(query, variables)).data).meta;
            expect(Object.prototype.hasOwnProperty.call(absent, 'enrichmentError')).toBe(false);
            expect(absent.enrichmentStatus).toBe('FAILED');

            answerWith(row('null'));
            const nul = pick((await run(query, variables)).data).meta;
            expect(nul.enrichmentError).toBeNull();
        });
    }

    it('teaStoreFetchedSbom: a null bom is answered as null', async () => {
        answerWith(null);
        const { data } = await run(OPERATIONS[3][1], OPERATIONS[3][2]);
        expect(data.teaStoreFetchedSbom.outcome).toBe('OK');
        expect(data.teaStoreFetchedSbom.bom).toBeNull();
    });
});
