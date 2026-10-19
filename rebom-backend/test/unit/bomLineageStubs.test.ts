import { describe, it, expect, vi, beforeEach } from 'vitest';

// SCORE-51 (SCORE-50a) T-4: bomLineage and setBomLineageOwners are deprecated
// compatibility stubs for the rearm-saas that still calls them. They validate
// as before and answer an unowned lineage, with no query and no transaction.
const runQuery = vi.fn(async (..._a: any[]) => ({ rows: [] as any[] }));
const withTransaction = vi.fn(async (fn: any) => fn({ query: runQuery }));
const poolQuery = vi.fn(async (..._a: any[]) => ({ rows: [] as any[] }));
const poolConnect = vi.fn(async () => { throw new Error('no connection expected'); });

vi.mock('../../src/db', async (importOriginal) => ({
    ...(await importOriginal<any>()),
    runQuery: (...a: any[]) => runQuery.apply(null, a as any),
    withTransaction: (...a: any[]) => withTransaction.apply(null, a as any),
    pool: { query: (...a: any[]) => poolQuery.apply(null, a as any), connect: () => poolConnect() }
}));
vi.mock('../../src/utils', async (importOriginal) => ({
    ...(await importOriginal<any>()),
    runQuery: (...a: any[]) => runQuery.apply(null, a as any),
    withTransaction: (...a: any[]) => withTransaction.apply(null, a as any),
    pool: { query: (...a: any[]) => poolQuery.apply(null, a as any), connect: () => poolConnect() }
}));
vi.mock('../../src/logger', () => ({
    logger: { info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn() }
}));

import { ApolloServer } from '@apollo/server';
import typeDefs from '../../src/schema.graphql';
import resolvers from '../../src/bomResolver';
import { bomLineage, setBomLineageOwners, MAX_LINEAGE_OWNER_ENTRIES } from '../../src/services/bom/bomLineageService';
import { BomLineageOwnerMode } from '../../src/types';
import { BomValidationError } from '../../src/types/errors';

const ORG = '00000000-0000-0000-0000-000000000000';
const S = 'urn:uuid:11111111-2222-3333-4444-555555555555';
const S_BARE = '11111111-2222-3333-4444-555555555555';
const A = 'aaaaaaaa-0000-4000-8000-000000000001';
const B = 'bbbbbbbb-0000-4000-8000-000000000002';

function expectNoQuery() {
    expect(runQuery).not.toHaveBeenCalled();
    expect(withTransaction).not.toHaveBeenCalled();
    expect(poolQuery).not.toHaveBeenCalled();
    expect(poolConnect).not.toHaveBeenCalled();
}

beforeEach(() => vi.clearAllMocks());

describe('bomLineage', () => {
    it.each([['prefixed', S], ['bare', S_BARE]])('answers null for a %s serialNumber, with no query', async (_label, sn) => {
        expect(await bomLineage(ORG, sn)).toBeNull();
        expectNoQuery();
    });

    it('still refuses an organization that is not a uuid', async () => {
        await expect(bomLineage('not-a-uuid', S)).rejects.toBeInstanceOf(BomValidationError);
        expectNoQuery();
    });

    it('still refuses a blank serialNumber', async () => {
        await expect(bomLineage(ORG, '  ')).rejects.toBeInstanceOf(BomValidationError);
        expectNoQuery();
    });
});

describe('setBomLineageOwners', () => {
    it.each([BomLineageOwnerMode.CLAIM_IF_UNOWNED, BomLineageOwnerMode.REASSIGN])(
        '%s: NO_SUCH_LINEAGE per entry, in input order, serialNumber as given, with no query', async (mode) => {
            const entries = [
                { serialNumber: S_BARE, ownerComponent: A },
                { serialNumber: S, ownerComponent: B },
                { serialNumber: 'urn:uuid:22222222-3333-4444-5555-666666666666', ownerComponent: A.toUpperCase() }
            ];

            const results = await setBomLineageOwners(ORG, entries, mode, 'user-1');

            expect(results).toStrictEqual(entries.map(e => ({ serialNumber: e.serialNumber, outcome: 'NO_SUCH_LINEAGE' })));
            expectNoQuery();
        });

    it('answers an empty list for no entries', async () => {
        expect(await setBomLineageOwners(ORG, [], BomLineageOwnerMode.REASSIGN)).toStrictEqual([]);
        expectNoQuery();
    });

    const valid = { serialNumber: S, ownerComponent: A };
    it.each([
        ['an organization that is not a uuid', () => setBomLineageOwners('nope', [valid], BomLineageOwnerMode.REASSIGN)],
        ['more than 500 entries', () => setBomLineageOwners(ORG,
            Array.from({ length: MAX_LINEAGE_OWNER_ENTRIES + 1 }, () => valid), BomLineageOwnerMode.REASSIGN)],
        ['an unknown mode', () => setBomLineageOwners(ORG, [valid], 'OVERWRITE' as any)],
        ['an ownerComponent that is not a uuid, last', () => setBomLineageOwners(ORG,
            [valid, { serialNumber: S_BARE, ownerComponent: 'not-a-uuid' }], BomLineageOwnerMode.CLAIM_IF_UNOWNED)],
        ['a blank serialNumber, last', () => setBomLineageOwners(ORG,
            [valid, { serialNumber: ' ', ownerComponent: B }], BomLineageOwnerMode.CLAIM_IF_UNOWNED)]
    ])('refuses %s with BomValidationError and answers nothing', async (_label, call) => {
        await expect(call()).rejects.toBeInstanceOf(BomValidationError);
        expectNoQuery();
    });
});

describe('through the GraphQL resolver', () => {
    const server = new ApolloServer({ typeDefs, resolvers });

    async function run(query: string, variables: any) {
        const response = await server.executeOperation({ query, variables });
        if (response.body.kind !== 'single') throw new Error('expected a single result');
        return response.body.singleResult;
    }

    it('bomLineage answers null', async () => {
        const result = await run(
            'query L($org: ID!, $sn: ID!) { bomLineage(org: $org, serialNumber: $sn) { serialNumber ownerComponent claimedVia } }',
            { org: ORG, sn: S_BARE });

        expect(result.errors).toBeUndefined();
        // GraphQL answers null-prototype objects: compared by value.
        expect(result.data).toEqual({ bomLineage: null });
        expectNoQuery();
    });

    it('setBomLineageOwners answers NO_SUCH_LINEAGE with no ownerComponent', async () => {
        const result = await run(
            `mutation M($org: ID!, $entries: [BomLineageOwnerInput!]!) {
                setBomLineageOwners(org: $org, entries: $entries, mode: CLAIM_IF_UNOWNED, actor: "backfill") {
                    serialNumber outcome ownerComponent } }`,
            { org: ORG, entries: [{ serialNumber: S_BARE, ownerComponent: A }, { serialNumber: S, ownerComponent: B }] });

        expect(result.errors).toBeUndefined();
        expect(result.data).toEqual({ setBomLineageOwners: [
            { serialNumber: S_BARE, outcome: 'NO_SUCH_LINEAGE', ownerComponent: null },
            { serialNumber: S, outcome: 'NO_SUCH_LINEAGE', ownerComponent: null }
        ] });
        expectNoQuery();
    });
});
