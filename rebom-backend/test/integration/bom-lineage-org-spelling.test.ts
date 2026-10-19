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

// Every document pushed to OCI, so a case can tell a refusal before any push
// from one after the pushes.
const pushed = vi.hoisted(() => [] as { serialNumber?: string, version?: number }[]);
vi.mock('../../src/services/oci', async (importOriginal) => {
    const actual: any = await importOriginal();
    return {
        ...actual,
        pushToOci: async (...a: any[]) => {
            pushed.push({ serialNumber: a[1]?.serialNumber, version: a[1]?.version });
            return actual.pushToOci(...a);
        }
    };
});

import { v4 as uuidv4 } from 'uuid';
import * as BomService from '../../src/bomService';
import * as BomRepository from '../../src/bomRepository';
import * as BomLineageRepository from '../../src/bomLineageRepository';
import { SpdxService } from '../../src/services/spdx';
import { pool } from '../../src/utils';
import { logger } from '../../src/logger';
import { canonicalUuid } from '../../src/types';
import { TEST_ORG_UUID, loadFixture, createTestRebomOptions, generateSerialNumber, cleanupTestBoms } from '../helpers';
import { BomValidationError, BomVersionConflictError } from '../../src/types/errors';

/**
 * SCORE-46, review item T-3, kept by SCORE-51 (SCORE-50a) with no lineage owner:
 * rebom.boms stores every spelling PostgreSQL's uuid type parses as one
 * organization, so addBom canonicalises the organization before anything reads
 * it, and the lineage lock and the in-transaction version re-check key on the
 * uuid value. Against the migrated database (localhost:5438 by default).
 */

const A = 'aaaaaaaa-0000-4000-8000-000000000001';

const ORG_BRACES = `{${TEST_ORG_UUID}}`;
const ORG_NO_HYPHENS = TEST_ORG_UUID.replace(/-/g, '');
const ORG_UPPER_BRACES = `{${TEST_ORG_UUID.toUpperCase()}}`;
const ORG_UPPER = TEST_ORG_UUID.toUpperCase();
const ORG_GROUPS_OF_FOUR = ORG_NO_HYPHENS.match(/.{4}/g)!.join('-');

// The spellings the canonical form does not already match by lowercasing.
const SPLITTING_SPELLINGS: [string, string][] = [
    ['braces', ORG_BRACES],
    ['no hyphens', ORG_NO_HYPHENS],
    ['upper-case braces', ORG_UPPER_BRACES],
    ['a hyphen after every four digits', ORG_GROUPS_OF_FOUR]
];
const ALL_SPELLINGS: [string, string][] = [['upper case', ORG_UPPER], ...SPLITTING_SPELLINGS];

const createdSerialNumbers: string[] = [];

function fresh(): string {
    const sn = generateSerialNumber();
    createdSerialNumbers.push(sn);
    return sn;
}

function bare(sn: string): string {
    return sn.replace('urn:uuid:', '');
}

function document(serialNumber: string, version: number, variant?: string): any {
    const bom = loadFixture('cyclonedx-simple.json');
    bom.serialNumber = serialNumber;
    bom.version = version;
    if (variant) bom.metadata.component.description = variant;
    return bom;
}

function upload(serialNumber: string, version: number, owner?: string, org = TEST_ORG_UUID, variant?: string) {
    const rebomOptions = createTestRebomOptions({ serialNumber });
    if (owner !== undefined) rebomOptions.ownerComponent = owner;
    return BomService.addBom({
        bomInput: { format: 'CYCLONEDX', bom: document(serialNumber, version, variant), org, rebomOptions }
    } as any);
}

async function versions(serialNumber: string): Promise<string[]> {
    return (await BomRepository.allBomsBySerialNumber(serialNumber, TEST_ORG_UUID))
        .map((r: any) => String(r.meta.bomVersion)).sort();
}

async function served(serialNumber: string): Promise<number> {
    return ((await BomService.findBomObjectById(bare(serialNumber), TEST_ORG_UUID)) as any).version;
}

const pushesOf = (serialNumber: string) => pushed.filter(p => p.serialNumber === serialNumber).length;

afterAll(async () => {
    await cleanupTestBoms(pool, createdSerialNumbers);
});

describe('canonicalUuid', () => {
    it('org spelling a: answers what PostgreSQL uuid::text answers, and null where the uuid type refuses', async () => {
        const values = [
            TEST_ORG_UUID, ...ALL_SPELLINGS.map(([, v]) => v),
            `{${ORG_NO_HYPHENS}}`, '', ' ', 'not-a-uuid', `${TEST_ORG_UUID} `, ` ${TEST_ORG_UUID}`,
            `{${TEST_ORG_UUID}`, `${TEST_ORG_UUID}}`, `-${ORG_NO_HYPHENS}`, `${ORG_NO_HYPHENS}-`,
            `${ORG_NO_HYPHENS.slice(0, 3)}-${ORG_NO_HYPHENS.slice(3)}`, ORG_NO_HYPHENS.slice(1),
            `${ORG_NO_HYPHENS}0`, TEST_ORG_UUID.replace(/-/g, '--'), `urn:uuid:${TEST_ORG_UUID}`,
            TEST_ORG_UUID.replace('5', 'g')
        ];
        for (const value of values) {
            let expected: string | null;
            try {
                expected = (await pool.query('SELECT $1::uuid::text AS v', [value])).rows[0].v;
            } catch {
                expected = null;
            }
            expect(canonicalUuid(value), JSON.stringify(value)).toBe(expected);
        }
        expect(canonicalUuid(undefined)).toBeNull();
        expect(canonicalUuid(null)).toBeNull();
    });
});

describe('addBom canonicalises the organization', () => {
    it.each(SPLITTING_SPELLINGS)('org spelling c: with no race, an upload in %s beneath a newer row is refused before any push',
        async (_name, org) => {
            const S = fresh();
            await upload(S, 1);
            await upload(S, 3);
            const before = pushesOf(S);

            await expect(upload(S, 2, A, org)).rejects.toBeInstanceOf(BomVersionConflictError);
            expect(pushesOf(S)).toBe(before);
            expect(await versions(S)).toStrictEqual(['1', '3']);
        });

    it.each(SPLITTING_SPELLINGS)('org spelling d: the same bytes uploaded again with the organization in %s answer the stored row',
        async (_name, org) => {
            const S = fresh();
            const first: any = await upload(S, 1, A);
            const before = pushesOf(S);

            const again: any = await upload(S, 1, A, org);
            expect(again.uuid).toBe(first.uuid);
            expect(pushesOf(S)).toBe(before);
            expect(await versions(S)).toStrictEqual(['1']);
        });

    it('org spelling e: an organization that is not a uuid is refused before any read or push', async () => {
        const S = fresh();
        for (const org of ['not-a-uuid', '', ` ${TEST_ORG_UUID}`, `{${TEST_ORG_UUID}`, undefined, null]) {
            const rebomOptions = { ...createTestRebomOptions({ serialNumber: S }), ownerComponent: A };
            const refusal = await BomService.addBom({
                bomInput: { format: 'CYCLONEDX', bom: document(S, 1), org, rebomOptions }
            } as any).catch(e => e);
            expect(refusal, JSON.stringify(org)).toBeInstanceOf(BomValidationError);
            expect(refusal.message).toBe('org must be a uuid');
            expect(refusal.details).toStrictEqual({ field: 'org', value: org });
        }
        expect(pushesOf(S)).toBe(0);
        expect(await versions(S)).toStrictEqual([]);
    });
});

describe('the SPDX path', () => {
    const convertSpy = vi.spyOn(SpdxService, 'convertSpdxToCycloneDx');
    const namespace = () => `https://spdx.example.test/score-46/${uuidv4()}`;
    const spdx = (ns: string) => ({
        SPDXID: 'SPDXRef-DOCUMENT', spdxVersion: 'SPDX-2.3', dataLicense: 'CC0-1.0', name: 'doc',
        documentNamespace: ns, packages: []
    });
    const uploadSpdx = (ns: string, org: string, owner?: string, existing?: string) => BomService.addBom({
        bomInput: { bom: spdx(ns), format: 'SPDX', org, existingSerialNumber: existing, rebomOptions: { ownerComponent: owner } }
    } as any);
    const spdxRows = async (ns: string) => (await pool.query(
        `SELECT organization FROM rebom.spdx_boms WHERE spdx_metadata->>'documentNamespace' = $1`, [ns])).rows;

    it('org spelling f: a first SPDX upload with the organization in braces stores under the organization', async () => {
        const S = fresh();
        const N = namespace();
        const converted = document(S, 1);
        convertSpy.mockResolvedValueOnce({ success: true, convertedBom: converted } as any);

        const created: any = await uploadSpdx(N, ORG_BRACES, A);

        expect(created.organization).toBe(TEST_ORG_UUID);
        expect((await spdxRows(N)).map(r => r.organization)).toStrictEqual([TEST_ORG_UUID]);
    });

    it('org spelling g: an SPDX update with no hyphens beneath a newer row is refused before any push or SPDX row', async () => {
        const S = fresh();
        const N = namespace();
        await upload(S, 1);
        await upload(S, 2);
        const before = pushed.length;

        await expect(uploadSpdx(N, ORG_NO_HYPHENS, A, bare(S))).rejects.toBeInstanceOf(BomVersionConflictError);
        expect(pushed.length).toBe(before);
        expect(await spdxRows(N)).toHaveLength(0);
    });
});

// The repository functions on their own: any spelling of the organization
// reads the rows stored under it and waits on the one lineage lock.
describe('the lineage lock and the version re-check', () => {
    it.each(ALL_SPELLINGS)('org spelling h: latestStoredVersion with the organization in %s reads the stored row', async (_name, org) => {
        const S = fresh();
        const stored: any = await upload(S, 1);
        const client = await pool.connect();
        try {
            expect(await BomLineageRepository.latestStoredVersion(client, org, S))
                .toStrictEqual({ uuid: stored.uuid, bom_version: '1' });
        } finally {
            client.release();
        }
    });

    it.each(ALL_SPELLINGS)('org spelling i: lockLineage with the organization in %s waits on the canonical lock', async (_name, org) => {
        const S = fresh();
        const holder = await pool.connect();
        const waiter = await pool.connect();
        try {
            await holder.query('BEGIN');
            await BomLineageRepository.lockLineage(holder, TEST_ORG_UUID, S);
            await waiter.query('BEGIN');
            await waiter.query(`SET LOCAL lock_timeout = '300ms'`);
            // Another serialNumber of the same organization does not wait.
            await BomLineageRepository.lockLineage(waiter, org, fresh());
            await expect(BomLineageRepository.lockLineage(waiter, org, S)).rejects.toMatchObject({ code: '55P03' });
        } finally {
            await waiter.query('ROLLBACK');
            await holder.query('ROLLBACK');
            waiter.release();
            holder.release();
        }
    });
});

// SCORE-51 T-3 (ii) with one side spelling the organization otherwise: every
// spelling takes the one lock, so version 2 never lands beneath version 3.
describe('parallel, one side spelling the organization otherwise', () => {
    const ITERATIONS = 20;

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
        logger.info({ pairing: name, iterations: ITERATIONS, counts }, 'SCORE-51 org spelling race outcomes');
        expect(violations, `${violations.length} of ${ITERATIONS} iterations broke the invariant`).toStrictEqual([]);
    }

    const reason = (r: PromiseSettledResult<any>) => (r as PromiseRejectedResult).reason;

    // [label, the organization of version 2, the organization of version 3]
    const SIDES: [string, string, string][] = [
        ['version 3 in upper case', TEST_ORG_UUID, ORG_UPPER],
        ['version 2 in braces', ORG_BRACES, TEST_ORG_UUID],
        ['version 3 in braces', TEST_ORG_UUID, ORG_BRACES],
        ['version 2 with no hyphens', ORG_NO_HYPHENS, TEST_ORG_UUID],
        ['version 3 with no hyphens', TEST_ORG_UUID, ORG_NO_HYPHENS]
    ];

    it.each(SIDES)('org spelling j (T-3 ii, %s): versions 2 and 3 at once never leave 2 above 3',
        async (label, org2, org3) => {
            await runPairing(`j: ${label}`, async sn => {
                await upload(sn, 1);
                return [upload(sn, 2, undefined, org2), upload(sn, 3, undefined, org3)];
            }, async (sn, v2, v3) => {
                expect(v3.status).toBe('fulfilled');
                expect(await served(sn)).toBe(3);
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
