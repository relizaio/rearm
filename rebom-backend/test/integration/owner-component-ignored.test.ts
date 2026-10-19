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

import { ApolloServer } from '@apollo/server';
import typeDefs from '../../src/schema.graphql';
import resolvers from '../../src/bomResolver';
import * as BomService from '../../src/bomService';
import * as BomRepository from '../../src/bomRepository';
import { pool } from '../../src/utils';
import { TEST_ORG_UUID, loadFixture, createTestRebomOptions, generateSerialNumber, cleanupTestBoms } from '../helpers';

/**
 * SCORE-51 (SCORE-50a) T-1, T-5 and T-6 against the migrated database: rebom
 * has no bom_lineage table, and an upload's rebomOptions.ownerComponent is
 * accepted, through the service and through the GraphQL schema, and never
 * stored.
 */

const A = 'aaaaaaaa-0000-4000-8000-000000000001';

const createdSerialNumbers: string[] = [];

function fresh(): string {
    const sn = generateSerialNumber();
    createdSerialNumbers.push(sn);
    return sn;
}

function document(serialNumber: string, version: number): any {
    const bom = loadFixture('cyclonedx-simple.json');
    bom.serialNumber = serialNumber;
    bom.version = version;
    return bom;
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

describe('T-1: a CycloneDX upload with a uuid ownerComponent', () => {
    it('is accepted and stored without it, and the caller\'s options keep it', async () => {
        const S = fresh();
        const rebomOptions = { ...createTestRebomOptions({ serialNumber: S }), ownerComponent: A };

        const created: any = await BomService.addBom({
            bomInput: { format: 'CYCLONEDX', bom: document(S, 1), org: TEST_ORG_UUID, rebomOptions }
        } as any);

        expect(created.meta.serialNumber).toBe(S);
        expect(created.meta).not.toHaveProperty('ownerComponent');
        const rows = await BomRepository.allBomsBySerialNumber(S, TEST_ORG_UUID);
        expect(rows).toHaveLength(1);
        expect(rows[0].meta).not.toHaveProperty('ownerComponent');
        expect(rebomOptions.ownerComponent).toBe(A);
    });
});

describe('T-6: addBom over GraphQL', () => {
    const server = new ApolloServer({ typeDefs, resolvers });
    const MUTATION = 'mutation Add($bomInput: BomInput!) { addBom(bomInput: $bomInput) { uuid meta organization } }';

    it('validates with rebomOptions.ownerComponent and stores the upload without it', async () => {
        const S = fresh();
        const response = await server.executeOperation({
            query: MUTATION,
            variables: { bomInput: {
                format: 'CYCLONEDX', bom: document(S, 1), org: TEST_ORG_UUID,
                rebomOptions: { name: 'test-component', group: 'com.test', version: '1.0.0', structure: 'FLAT',
                    tldOnly: false, stripBom: 'false', belongsTo: '', notes: '', serialNumber: S, ownerComponent: A }
            } }
        });
        if (response.body.kind !== 'single') throw new Error('expected a single result');
        const result: any = response.body.singleResult;

        expect(result.errors).toBeUndefined();
        expect(result.data.addBom.organization).toBe(TEST_ORG_UUID);
        expect(result.data.addBom.meta.serialNumber).toBe(S);
        expect(result.data.addBom.meta).not.toHaveProperty('ownerComponent');
        expect(await BomRepository.allBomsBySerialNumber(S, TEST_ORG_UUID)).toHaveLength(1);
    });
});
