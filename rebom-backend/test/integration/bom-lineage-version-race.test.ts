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

import * as BomService from '../../src/bomService';
import * as BomRepository from '../../src/bomRepository';
import { pool } from '../../src/utils';
import { logger } from '../../src/logger';
import { TEST_ORG_UUID, loadFixture, createTestRebomOptions, generateSerialNumber, cleanupTestBoms } from '../helpers';
import { BomVersionConflictError } from '../../src/types/errors';

/**
 * SCORE-51 (SCORE-50a) T-3: the SCORE-46 ADR-13 lineage lock and the
 * in-transaction version re-check are kept with no owner step. Two concurrent
 * uploads of one serialNumber, 20 pairings each, against the real database;
 * invariants only, no interleaving counts asserted (created_date is the
 * transaction start, so no order is asserted on it either).
 */

const ITERATIONS = 20;
const createdSerialNumbers: string[] = [];

function fresh(): string {
    const sn = generateSerialNumber();
    createdSerialNumbers.push(sn);
    return sn;
}

function upload(serialNumber: string, version: number, variant?: string) {
    const bom = loadFixture('cyclonedx-simple.json');
    bom.serialNumber = serialNumber;
    bom.version = version;
    if (variant) bom.metadata.component.description = variant;
    return BomService.addBom({
        bomInput: { format: 'CYCLONEDX', bom, org: TEST_ORG_UUID, rebomOptions: createTestRebomOptions({ serialNumber }) }
    } as any);
}

async function versions(serialNumber: string): Promise<string[]> {
    return (await BomRepository.allBomsBySerialNumber(serialNumber, TEST_ORG_UUID))
        .map((r: any) => String(r.meta.bomVersion)).sort();
}

async function newest(serialNumber: string): Promise<number> {
    const res = await pool.query(
        `SELECT max((meta->>'bomVersion')::numeric) AS v FROM rebom.boms WHERE meta->>'serialNumber' = $1 AND organization = $2`,
        [serialNumber, TEST_ORG_UUID]);
    return Number(res.rows[0].v);
}

const reason = (r: PromiseSettledResult<any>) => (r as PromiseRejectedResult).reason;

async function runPairing(
    name: string,
    start: (sn: string) => Promise<[Promise<any>, Promise<any>]>,
    judge: (sn: string, first: PromiseSettledResult<any>, second: PromiseSettledResult<any>) => Promise<string>
) {
    const counts: Record<string, number> = {};
    const violations: string[] = [];
    for (let i = 0; i < ITERATIONS; i++) {
        const sn = fresh();
        const [first, second] = await Promise.allSettled(await start(sn));
        let outcome: string;
        try {
            outcome = await judge(sn, first, second);
        } catch (e: any) {
            outcome = 'violation';
            violations.push(`${i}: ${e.message.split('\n')[0]}`);
        }
        counts[outcome] = (counts[outcome] ?? 0) + 1;
    }
    logger.info({ pairing: name, iterations: ITERATIONS, counts }, 'SCORE-51 lineage race outcomes');
    expect(violations, `${violations.length} of ${ITERATIONS} iterations broke the invariant`).toStrictEqual([]);
}

afterAll(async () => {
    await cleanupTestBoms(pool, createdSerialNumbers);
});

describe('T-3: concurrent uploads of one serialNumber', () => {
    it('(i) the same version, different content: exactly one lands', async () => {
        await runPairing('i: same version', async sn => [upload(sn, 1, 'a'), upload(sn, 1, 'b')],
            async (sn, a, b) => {
                const fulfilled = [a, b].filter(r => r.status === 'fulfilled');
                expect(fulfilled).toHaveLength(1);
                const loser = a.status === 'fulfilled' ? b : a;
                expect(reason(loser)).toBeInstanceOf(BomVersionConflictError);
                expect(await versions(sn)).toStrictEqual(['1']);
                return a.status === 'fulfilled' ? 'a' : 'b';
            });
    }, 120000);

    it('(ii) versions 2 and 3: 3 always lands, and 2 never lands beneath it', async () => {
        await runPairing('ii: 2 and 3', async sn => {
            await upload(sn, 1);
            return [upload(sn, 2), upload(sn, 3)];
        }, async (sn, v2, v3) => {
            expect(v3.status).toBe('fulfilled');
            expect(await newest(sn)).toBe(3);
            if (v2.status === 'fulfilled') {
                expect(await versions(sn)).toStrictEqual(['1', '2', '3']);
                return 'both';
            }
            expect(reason(v2)).toBeInstanceOf(BomVersionConflictError);
            expect(await versions(sn)).toStrictEqual(['1', '3']);
            return '3 first';
        });
    }, 120000);
});
