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

// T-9: a lineage claim landing between the pre-check and the transaction. The
// hook runs after the first push of the named serialNumber, which is after the
// pre-check and before the claim-plus-insert transaction.
const race = vi.hoisted(() => ({
    serialNumber: '' as string,
    hook: null as null | (() => Promise<void>)
}));
vi.mock('../../src/services/oci', async (importOriginal) => {
    const actual: any = await importOriginal();
    return {
        ...actual,
        pushToOci: async (...a: any[]) => {
            const result = await actual.pushToOci(...a);
            const pushedBom = a[1];
            if (race.hook && pushedBom?.serialNumber === race.serialNumber) {
                const hook = race.hook;
                race.hook = null;
                await hook();
            }
            return result;
        }
    };
});

import * as BomService from '../../src/bomService';
import * as BomRepository from '../../src/bomRepository';
import { pool } from '../../src/utils';
import { logger } from '../../src/logger';
import { TEST_ORG_UUID, loadFixture, createTestRebomOptions, generateSerialNumber, cleanupTestBoms } from '../helpers';
import { BomLineageOwnerError, BomValidationError, BomVersionConflictError } from '../../src/types/errors';
import { BomLineageOwnerMode } from '../../src/types';

/**
 * SCORE-46: serialNumber lineage ownership against the migrated database
 * (localhost:5438 by default).
 */

const A = 'aaaaaaaa-0000-4000-8000-000000000001';
const B = 'bbbbbbbb-0000-4000-8000-000000000002';
const OTHER_ORG = 'cccccccc-0000-4000-8000-000000000003';

const createdSerialNumbers: string[] = [];

function fresh(): string {
    const sn = generateSerialNumber();
    createdSerialNumbers.push(sn);
    return sn;
}

function bare(sn: string): string {
    return sn.replace('urn:uuid:', '');
}

// A distinct document per version, so no upload dedupes onto another; a
// variant makes two documents of one version distinct too.
function document(serialNumber: string, version: number, variant?: string): any {
    const bom = loadFixture('cyclonedx-simple.json');
    bom.serialNumber = serialNumber;
    bom.version = version;
    if (variant) bom.metadata.component.description = variant;
    return bom;
}

function upload(serialNumber: string, version: number, owner?: string, variant?: string) {
    const rebomOptions = createTestRebomOptions({ serialNumber });
    if (owner !== undefined) rebomOptions.ownerComponent = owner;
    return BomService.addBom({
        bomInput: { format: 'CYCLONEDX', bom: document(serialNumber, version, variant), org: TEST_ORG_UUID, rebomOptions }
    } as any);
}

async function lineageRow(serialNumber: string, org = TEST_ORG_UUID): Promise<any> {
    const res = await pool.query(
        'SELECT * FROM rebom.bom_lineage WHERE organization = $1 AND serial_number = $2', [org, serialNumber]);
    return res.rows[0];
}

async function bomRowCount(serialNumber: string): Promise<number> {
    return (await BomRepository.allBomsBySerialNumber(serialNumber, TEST_ORG_UUID)).length;
}

afterAll(async () => {
    await cleanupTestBoms(pool, createdSerialNumbers);
});

describe('lineage owner on upload', () => {
    const S = fresh();
    const S2 = fresh();

    it('T-8 a: a first upload with an owner claims the lineage', async () => {
        const created: any = await upload(S, 1, A);

        const row = await lineageRow(S);
        expect(row.owner_component).toBe(A);
        expect(row.claimed_via).toBe('UPLOAD');
        expect(row.claimed_by).toBeNull();
        expect(row.claimed_at).toBeInstanceOf(Date);
        expect(created.meta.ownerComponent).toBe(A);
    });

    it('T-8 b: a null-owner first upload leaves the lineage unowned until an owner-bearing upload claims it', async () => {
        await upload(S2, 1);
        expect(await lineageRow(S2)).toBeUndefined();

        await upload(S2, 2, A);
        const row = await lineageRow(S2);
        expect(row.owner_component).toBe(A);
        expect(row.claimed_via).toBe('UPLOAD');
    });

    it('T-8 c: another component is refused and nothing is stored', async () => {
        await expect(upload(S2, 3, B)).rejects.toBeInstanceOf(BomLineageOwnerError);
        expect(await bomRowCount(S2)).toBe(2);
        expect((await lineageRow(S2)).owner_component).toBe(A);
    });

    it('T-8 d: a null caller is refused on the owned lineage', async () => {
        await expect(upload(S2, 3)).rejects.toBeInstanceOf(BomLineageOwnerError);
        expect(await bomRowCount(S2)).toBe(2);
    });

    it('T-8 e: the owner continues its lineage', async () => {
        await upload(S2, 3, A);
        expect(await bomRowCount(S2)).toBe(3);
        const row = await lineageRow(S2);
        expect(row.owner_component).toBe(A);
        expect(row.claimed_via).toBe('UPLOAD');
    });

    it('T-8 f: the serialNumber resolves to the owner\'s newest version', async () => {
        const served: any = await BomService.findBomObjectById(bare(S2), TEST_ORG_UUID);
        expect(served.version).toBe(3);
    });
});

describe('race', () => {
    it('T-9: a claim by another component after the pre-check refuses the upload in the transaction', async () => {
        const S3 = fresh();
        const warn = vi.spyOn(logger, 'warn');
        race.serialNumber = S3;
        race.hook = async () => {
            await pool.query(
                `INSERT INTO rebom.bom_lineage (organization, serial_number, owner_component, claimed_at, claimed_via)
                 VALUES ($1, $2, $3, now(), 'UPLOAD')`, [TEST_ORG_UUID, S3, B]);
        };
        try {
            await expect(upload(S3, 1, A)).rejects.toBeInstanceOf(BomLineageOwnerError);
            expect(await bomRowCount(S3)).toBe(0);
            expect((await lineageRow(S3)).owner_component).toBe(B);
            expect(warn).toHaveBeenCalledWith(expect.objectContaining({ stage: 'transaction', uploadingComponent: A }),
                'BOM lineage owner mismatch: upload refused');
        } finally {
            race.hook = null;
            warn.mockRestore();
        }
    });

    it('T-18: the claim rolls back with the transaction when the version re-check refuses', async () => {
        const S = fresh();
        const info = vi.spyOn(logger, 'info');
        race.serialNumber = S;
        // A null-owner upload of the same document stores v1 after the outer
        // upload's pre-checks and before its transaction.
        race.hook = async () => { await upload(S, 1); };
        try {
            await expect(upload(S, 1, A)).rejects.toBeInstanceOf(BomVersionConflictError);
            expect(await lineageRow(S)).toBeUndefined();
            const rows = await BomRepository.allBomsBySerialNumber(S, TEST_ORG_UUID);
            expect(rows.map((r: any) => String(r.meta.bomVersion))).toStrictEqual(['1']);
            expect(rows[0].meta.ownerComponent).toBeUndefined();
            expect(info).toHaveBeenCalledWith(expect.objectContaining({ serialNumber: S, ownerComponent: A, via: 'UPLOAD' }),
                'BOM lineage claimed');
        } finally {
            race.hook = null;
            info.mockRestore();
        }
    });

    it('T-18 b: the claim rolls back with the transaction when the row insert itself fails', async () => {
        const S = fresh();
        const rebomOptions = createTestRebomOptions({ serialNumber: S });
        rebomOptions.ownerComponent = A;
        // tags is a jsonb column: a bare string is not valid JSON, so the
        // INSERT INTO rebom.boms fails after the claim statement has run.
        await expect(BomService.addBom({
            bomInput: { format: 'CYCLONEDX', bom: document(S, 1), org: TEST_ORG_UUID, rebomOptions, tags: 'not json' }
        } as any)).rejects.toThrow();
        expect(await lineageRow(S)).toBeUndefined();
        expect(await bomRowCount(S)).toBe(0);
    });

    it('T-9: the same claim by the uploading component lets the upload through', async () => {
        const S3b = fresh();
        race.serialNumber = S3b;
        race.hook = async () => {
            await pool.query(
                `INSERT INTO rebom.bom_lineage (organization, serial_number, owner_component, claimed_at, claimed_via)
                 VALUES ($1, $2, $3, now(), 'UPLOAD')`, [TEST_ORG_UUID, S3b, A]);
        };
        try {
            await upload(S3b, 1, A);
            expect(await bomRowCount(S3b)).toBe(1);
            expect((await lineageRow(S3b)).owner_component).toBe(A);
        } finally {
            race.hook = null;
        }
    });
});

// T-17: two uploads of one serialNumber started together, no hook. Every
// iteration must end as one of the two serial orders, with the loser told; the
// outcome counts are logged, not asserted, and every violation is collected so
// a broken serialisation reports how many iterations it let through.
describe('parallel', () => {
    const ITERATIONS = 20;

    async function versions(sn: string): Promise<string[]> {
        return (await BomRepository.allBomsBySerialNumber(sn, TEST_ORG_UUID)).map((r: any) => String(r.meta.bomVersion)).sort();
    }

    async function served(sn: string): Promise<number> {
        return ((await BomService.findBomObjectById(bare(sn), TEST_ORG_UUID)) as any).version;
    }

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
        logger.info({ pairing: name, iterations: ITERATIONS, counts }, 'SCORE-46 T-17 race outcomes');
        expect(violations, `${violations.length} of ${ITERATIONS} iterations broke the invariant`).toStrictEqual([]);
    }

    const fulfilledCount = (...r: PromiseSettledResult<any>[]) => r.filter(x => x.status === 'fulfilled').length;
    const reason = (r: PromiseSettledResult<any>) => (r as PromiseRejectedResult).reason;

    it('T-17 a: a lazy claim against a null upload of a higher version ends in one serial order', async () => {
        await runPairing('a: A v2 claim vs null v3 on a stored null v1', async sn => {
            await upload(sn, 1);
            return [upload(sn, 2, A), upload(sn, 3)];
        }, async (sn, owner, nullCaller) => {
            expect(fulfilledCount(owner, nullCaller)).toBe(1);
            if (owner.status === 'fulfilled') {
                expect(reason(nullCaller)).toBeInstanceOf(BomLineageOwnerError);
                expect((await lineageRow(sn)).owner_component).toBe(A);
                expect(await versions(sn)).toStrictEqual(['1', '2']);
                expect(await served(sn)).toBe(2);
                return 'owner first';
            }
            expect(reason(owner)).toBeInstanceOf(BomVersionConflictError);
            expect(await lineageRow(sn)).toBeUndefined();
            expect(await versions(sn)).toStrictEqual(['1', '3']);
            expect(await served(sn)).toBe(3);
            return 'null first';
        });
    }, 120000);

    it('T-17 b: a first claim of a fresh serialNumber against a null upload ends in one serial order', async () => {
        await runPairing('b: B v1 claim vs null v2 on a fresh serialNumber', async sn => [upload(sn, 1, B), upload(sn, 2)],
            async (sn, owner, nullCaller) => {
                expect(fulfilledCount(owner, nullCaller)).toBe(1);
                if (owner.status === 'fulfilled') {
                    expect(reason(nullCaller)).toBeInstanceOf(BomLineageOwnerError);
                    expect((await lineageRow(sn)).owner_component).toBe(B);
                    expect(await versions(sn)).toStrictEqual(['1']);
                    return 'owner first';
                }
                expect(reason(owner)).toBeInstanceOf(BomVersionConflictError);
                expect(await lineageRow(sn)).toBeUndefined();
                expect(await versions(sn)).toStrictEqual(['2']);
                return 'null first';
            });
    }, 120000);

    it('T-17 c: two components racing for a fresh serialNumber end with exactly one owner', async () => {
        await runPairing('c: A v1 vs B v1 on a fresh serialNumber', async sn => [upload(sn, 1, A, 'a'), upload(sn, 1, B, 'b')],
            async (sn, a, b) => {
                expect(fulfilledCount(a, b)).toBe(1);
                const [winner, loser] = a.status === 'fulfilled' ? [A, b] : [B, a];
                // The loser is told by the lineage refusal; a winner committing
                // between the loser's pre-check and its version check gives the
                // version refusal instead (pre-transaction stage).
                const lost = reason(loser);
                expect(lost instanceof BomLineageOwnerError || lost instanceof BomVersionConflictError).toBe(true);
                expect((await lineageRow(sn)).owner_component).toBe(winner);
                const rows = await BomRepository.allBomsBySerialNumber(sn, TEST_ORG_UUID);
                expect(rows).toHaveLength(1);
                expect(rows[0].meta.ownerComponent).toBe(winner);
                return winner === A ? 'A first' : 'B first';
            });
    }, 120000);
});

describe('setBomLineageOwners and bomLineage', () => {
    const S4 = fresh();
    const S5 = fresh();
    const S6 = fresh();

    it('T-10: CLAIM_IF_UNOWNED claims, keeps, refuses without naming the owner, and needs stored rows', async () => {
        await upload(S4, 1);
        await upload(S5, 1, A);

        const results = await BomService.setBomLineageOwners(TEST_ORG_UUID,
            [{ serialNumber: S4, ownerComponent: B }, { serialNumber: S5, ownerComponent: A },
             { serialNumber: S5, ownerComponent: B }, { serialNumber: S6, ownerComponent: A }],
            BomLineageOwnerMode.CLAIM_IF_UNOWNED, 'backfill');

        expect(results[0]).toStrictEqual({ serialNumber: S4, outcome: 'SET', ownerComponent: B });
        expect(results[1]).toStrictEqual({ serialNumber: S5, outcome: 'UNCHANGED_SAME_OWNER', ownerComponent: A });
        expect(results[2]).toStrictEqual({ serialNumber: S5, outcome: 'REFUSED_OWNED_BY_OTHER' });
        expect(results[3]).toStrictEqual({ serialNumber: S6, outcome: 'NO_SUCH_LINEAGE' });

        const s4 = await lineageRow(S4);
        expect(s4.claimed_via).toBe('BACKFILL');
        expect(s4.claimed_by).toBe('backfill');
        const s5 = await lineageRow(S5);
        expect(s5.claimed_via).toBe('UPLOAD');
        expect(s5.claimed_by).toBeNull();
        expect(await lineageRow(S6)).toBeUndefined();
    });

    it('T-10: REASSIGN sets the owner unconditionally and records who did it', async () => {
        const results = await BomService.setBomLineageOwners(TEST_ORG_UUID,
            [{ serialNumber: S4, ownerComponent: A }, { serialNumber: S6, ownerComponent: A }],
            BomLineageOwnerMode.REASSIGN, 'user-1');

        expect(results).toStrictEqual([
            { serialNumber: S4, outcome: 'SET', ownerComponent: A },
            { serialNumber: S6, outcome: 'NO_SUCH_LINEAGE' }
        ]);
        const s4 = await lineageRow(S4);
        expect(s4.owner_component).toBe(A);
        expect(s4.claimed_via).toBe('REASSIGN');
        expect(s4.claimed_by).toBe('user-1');
    });

    it('T-10: bomLineage reads the row by either form of the serialNumber, per organization', async () => {
        const byPrefixed = await BomService.bomLineage(TEST_ORG_UUID, S4);
        const byBare = await BomService.bomLineage(TEST_ORG_UUID, bare(S4));
        expect(byBare).toStrictEqual(byPrefixed);
        expect(byPrefixed!.serialNumber).toBe(S4);
        expect(byPrefixed!.ownerComponent).toBe(A);
        expect(byPrefixed!.claimedVia).toBe('REASSIGN');
        expect(byPrefixed!.claimedBy).toBe('user-1');
        expect(byPrefixed!.claimedAt).toBeInstanceOf(Date);
        expect(await BomService.bomLineage(TEST_ORG_UUID, S6)).toBeNull();
        expect(await BomService.bomLineage(OTHER_ORG, S4)).toBeNull();
    });

    it('T-10: REASSIGN to the same owner is UNCHANGED_SAME_OWNER and still on record', async () => {
        const results = await BomService.setBomLineageOwners(TEST_ORG_UUID,
            [{ serialNumber: S4, ownerComponent: A }], BomLineageOwnerMode.REASSIGN, 'user-2');
        expect(results).toStrictEqual([{ serialNumber: S4, outcome: 'UNCHANGED_SAME_OWNER', ownerComponent: A }]);
        const s4 = await lineageRow(S4);
        expect(s4.claimed_by).toBe('user-2');
        expect(s4.claimed_via).toBe('REASSIGN');
    });

    it('T-10: the batch is validated whole before any write', async () => {
        const before = await lineageRow(S4);
        const many = Array.from({ length: 501 }, () => ({ serialNumber: S4, ownerComponent: B }));
        await expect(BomService.setBomLineageOwners(TEST_ORG_UUID, many, BomLineageOwnerMode.REASSIGN, 'x'))
            .rejects.toThrow('setBomLineageOwners accepts at most 500 entries');
        await expect(BomService.setBomLineageOwners(TEST_ORG_UUID, many, BomLineageOwnerMode.REASSIGN, 'x'))
            .rejects.toBeInstanceOf(BomValidationError);
        await expect(BomService.setBomLineageOwners(TEST_ORG_UUID,
            [{ serialNumber: S4, ownerComponent: B }, { serialNumber: S5, ownerComponent: 'x' }],
            BomLineageOwnerMode.REASSIGN, 'x')).rejects.toBeInstanceOf(BomValidationError);
        await expect(BomService.setBomLineageOwners(TEST_ORG_UUID,
            [{ serialNumber: S4, ownerComponent: B }, { serialNumber: '   ', ownerComponent: B }],
            BomLineageOwnerMode.REASSIGN, 'x')).rejects.toBeInstanceOf(BomValidationError);
        expect(await lineageRow(S4)).toStrictEqual(before);
    });

    it('T-10: an entry given without the prefix hits the canonical row and is echoed as given', async () => {
        const results = await BomService.setBomLineageOwners(TEST_ORG_UUID,
            [{ serialNumber: bare(S4), ownerComponent: A }], BomLineageOwnerMode.CLAIM_IF_UNOWNED, 'backfill');
        expect(results).toStrictEqual([{ serialNumber: bare(S4), outcome: 'UNCHANGED_SAME_OWNER', ownerComponent: A }]);
        const rows = await pool.query(
            'SELECT serial_number FROM rebom.bom_lineage WHERE organization = $1 AND serial_number IN ($2, $3)',
            [TEST_ORG_UUID, S4, bare(S4)]);
        expect(rows.rows.map((r: any) => r.serial_number)).toStrictEqual([S4]);
    });
});

describe('migration', () => {
    it('T-13: rebom.bom_lineage has the seven columns of ADR-2', async () => {
        const res = await pool.query(
            `SELECT column_name, data_type, is_nullable FROM information_schema.columns
             WHERE table_schema = 'rebom' AND table_name = 'bom_lineage' ORDER BY ordinal_position`);
        expect(res.rows).toStrictEqual([
            { column_name: 'organization', data_type: 'uuid', is_nullable: 'NO' },
            { column_name: 'serial_number', data_type: 'text', is_nullable: 'NO' },
            { column_name: 'owner_component', data_type: 'uuid', is_nullable: 'YES' },
            { column_name: 'claimed_at', data_type: 'timestamp with time zone', is_nullable: 'YES' },
            { column_name: 'claimed_via', data_type: 'text', is_nullable: 'YES' },
            { column_name: 'claimed_by', data_type: 'text', is_nullable: 'YES' },
            { column_name: 'created_date', data_type: 'timestamp with time zone', is_nullable: 'NO' }
        ]);
    });

    it('T-13: its only index is the primary key on (organization, serial_number)', async () => {
        const res = await pool.query(
            `SELECT indexdef FROM pg_indexes WHERE schemaname = 'rebom' AND tablename = 'bom_lineage'`);
        expect(res.rows).toHaveLength(1);
        expect(res.rows[0].indexdef).toMatch(/UNIQUE INDEX bom_lineage_pkey ON rebom\.bom_lineage USING btree \(organization, serial_number\)/);
    });
});
