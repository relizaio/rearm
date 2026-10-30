import { describe, it, expect, afterAll, vi } from 'vitest';

// Mock integrationRepository before importing bomService (table may not exist in test DB)
vi.mock('../../src/integrationRepository', () => ({
    findIntegrationByTypeAndOrg: vi.fn().mockResolvedValue(null),
    upsertIntegration: vi.fn(),
    createSecret: vi.fn(),
    findSecretById: vi.fn(),
    updateSecret: vi.fn(),
    deleteSecret: vi.fn(),
    deleteIntegration: vi.fn(),
}));

import { v4 as uuidv4 } from 'uuid';
import { ApolloServer } from '@apollo/server';
import typeDefs from '../../src/schema.graphql';
import resolvers from '../../src/bomResolver';
import * as BomService from '../../src/bomService';
import * as BomRepository from '../../src/bomRepository';
import { pool } from '../../src/utils';
import { TEST_ORG_UUID, loadFixture, createTestRebomOptions, generateSerialNumber, cleanupTestBoms } from '../helpers';

/**
 * SCORE-54 (SCORE-50d) T-2 against the migrated database: the SCORE-50
 * compatibility stubs and RebomOptions.ownerComponent are gone, so a caller
 * that still uses them (a SCORE-47-era rearm-saas) gets a GraphQL validation
 * error and nothing is stored. SCORE-51 T-5 (no rebom.bom_lineage) is kept.
 */

const A = 'aaaaaaaa-0000-4000-8000-000000000001';

const createdSerialNumbers: string[] = [];

function fresh(): string {
    const sn = generateSerialNumber();
    createdSerialNumbers.push(sn);
    return sn;
}

function document(serialNumber: string, version: number, fixture = 'cyclonedx-simple.json'): any {
    const bom = loadFixture(fixture);
    bom.serialNumber = serialNumber;
    bom.version = version;
    return bom;
}

const server = new ApolloServer({ typeDefs, resolvers });

async function run(query: string, variables: any = {}): Promise<any> {
    const response = await server.executeOperation({ query, variables });
    if (response.body.kind !== 'single') throw new Error('expected a single result');
    return response.body.singleResult;
}

/** The result carries errors, one of which names every needle, and no data. */
function expectRefused(result: any, ...needles: string[]) {
    expect(result.errors?.length ?? 0).toBeGreaterThan(0);
    const messages = result.errors.map((e: any) => e.message);
    expect(messages.some((m: string) => needles.every(n => m.includes(n))), JSON.stringify(messages)).toBe(true);
    expect(result.data ?? null).toBeNull();
}

function graphQlOptions(serialNumber: string, extra: any = {}): any {
    return { name: 'test-component', group: 'com.test', version: '1.0.0', structure: 'FLAT',
        tldOnly: false, stripBom: 'false', belongsTo: '', notes: '', serialNumber, ...extra };
}

async function rowsNamed(name: string): Promise<number> {
    const res = await pool.query(
        `SELECT count(*)::int AS n FROM rebom.boms WHERE organization = $1 AND meta->>'name' = $2`,
        [TEST_ORG_UUID, name]);
    return res.rows[0].n;
}

afterAll(async () => {
    await cleanupTestBoms(pool, createdSerialNumbers);
});

describe('T-5: the migrated database', () => {
    it('has no rebom.bom_lineage', async () => {
        const res = await pool.query(`SELECT to_regclass('rebom.bom_lineage') AS t`);
        expect(res.rows[0].t).toBeNull();
    });
});

describe('T-2: addBom over GraphQL', () => {
    const MUTATION = 'mutation Add($bomInput: BomInput!) { addBom(bomInput: $bomInput) { uuid meta organization } }';

    it('(a) refuses rebomOptions.ownerComponent, naming the field and RebomOptions, and stores nothing', async () => {
        const S = fresh();

        const result = await run(MUTATION, { bomInput: {
            format: 'CYCLONEDX', bom: document(S, 1), org: TEST_ORG_UUID,
            rebomOptions: graphQlOptions(S, { ownerComponent: A })
        } });

        expectRefused(result, 'ownerComponent', 'RebomOptions');
        expect(await BomRepository.allBomsBySerialNumber(S, TEST_ORG_UUID)).toHaveLength(0);
    });

    it('(b) stores the same upload without the field', async () => {
        const S = fresh();

        const result = await run(MUTATION, { bomInput: {
            format: 'CYCLONEDX', bom: document(S, 1), org: TEST_ORG_UUID, rebomOptions: graphQlOptions(S)
        } });

        expect(result.errors).toBeUndefined();
        expect(result.data.addBom.organization).toBe(TEST_ORG_UUID);
        expect(result.data.addBom.meta.serialNumber).toBe(S);
        const rows = await BomRepository.allBomsBySerialNumber(S, TEST_ORG_UUID);
        expect(rows).toHaveLength(1);
        expect(rows[0].meta.serialNumber).toBe(S);
    });
});

describe('T-2: mergeAndStoreBoms over GraphQL', () => {
    const MUTATION = 'mutation Merge($ids: [ID]!, $rebomOptions: RebomOptions!, $org: ID) { '
        + 'mergeAndStoreBoms(ids: $ids, rebomOptions: $rebomOptions, org: $org) { uuid } }';

    it('(c) refuses rebomOptions.ownerComponent and stores no merge; the same merge without it is stored', async () => {
        const ids: string[] = [];
        for (const fixture of ['cyclonedx-simple.json', 'cyclonedx-for-merge.json']) {
            const S = fresh();
            const created = await BomService.addBom({
                bomInput: { format: 'CYCLONEDX', bom: document(S, 1, fixture), org: TEST_ORG_UUID,
                    rebomOptions: createTestRebomOptions({ serialNumber: S, name: 'app', version: '1.0.0' }) }
            } as any);
            ids.push(created.uuid);
        }
        const name = `merged-${uuidv4()}`;
        const M = fresh();

        const refused = await run(MUTATION, { ids, org: TEST_ORG_UUID,
            rebomOptions: graphQlOptions(M, { name, ownerComponent: A }) });

        expectRefused(refused, 'ownerComponent', 'RebomOptions');
        expect(await rowsNamed(name)).toBe(0);
        expect(await BomRepository.allBomsBySerialNumber(M, TEST_ORG_UUID)).toHaveLength(0);

        // Control: the query above sees a stored merge of that name.
        const stored = await run(MUTATION, { ids, org: TEST_ORG_UUID, rebomOptions: graphQlOptions(M, { name }) });
        expect(stored.errors).toBeUndefined();
        const record: any = (await BomRepository.bomById(stored.data.mergeAndStoreBoms.uuid))[0];
        if (record?.meta?.serialNumber) createdSerialNumbers.push(record.meta.serialNumber);
        expect(await rowsNamed(name)).toBe(1);
    });
});

describe('T-2: the removed stubs over GraphQL', () => {
    it('(d) the bomLineage query is refused, naming bomLineage', async () => {
        const result = await run(
            'query L($org: ID!, $sn: ID!) { bomLineage(org: $org, serialNumber: $sn) { serialNumber } }',
            { org: TEST_ORG_UUID, sn: generateSerialNumber() });

        expectRefused(result, 'bomLineage');
    });

    it('(e) the setBomLineageOwners mutation is refused, with no data', async () => {
        const result = await run(
            `mutation M($org: ID!, $entries: [BomLineageOwnerInput!]!) {
                setBomLineageOwners(org: $org, entries: $entries, mode: CLAIM_IF_UNOWNED, actor: "backfill") {
                    serialNumber outcome ownerComponent } }`,
            { org: TEST_ORG_UUID, entries: [{ serialNumber: generateSerialNumber(), ownerComponent: A }] });

        expectRefused(result, 'setBomLineageOwners');
    });
});
