import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import crypto from 'node:crypto';

import { reserveEnrichmentRun } from '../../src/services/bom/bomProcessingService';
import { runQuery, pool } from '../../src/utils';

/**
 * Two schedulers reserving at the same moment must not mint the same tag.
 *
 * This is a live-Postgres test on purpose: the property being checked is a
 * statement-level concurrency guarantee, and no mock has it. Under READ
 * COMMITTED, an UPDATE whose target row was changed by a concurrent statement
 * is re-evaluated against the NEW version of that row -- but ONLY the target
 * row. Anything read by another scan in the same statement, such as a CTE over
 * the same table, keeps its original snapshot. The first version of this
 * reservation computed the run list in exactly such a CTE, so two concurrent
 * reservations both read the pre-update list and both minted sequence 1.
 */
describe('enrichment sequence reservation under concurrency', () => {
    const uuid = crypto.randomUUID();

    beforeEach(async () => {
        await runQuery(
            `INSERT INTO rebom.boms (uuid, meta, bom, organization, source_format)
             VALUES ($1, $2, $3, $4, 'CYCLONEDX')`,
            [uuid,
             { serialNumber: `urn:uuid:${crypto.randomUUID()}`, processedFileDigest: 'digest-0',
               processedFileSize: 10 },
             { ociRepositoryName: 'rebom-artifacts-2026-08' },
             '00000000-0000-0000-0000-000000000000']
        );
    });

    afterEach(async () => {
        await runQuery('DELETE FROM rebom.boms WHERE uuid = $1', [uuid]);
    });

    it('gives a blocked reservation the sequence after the one that committed first', async () => {
        // Deterministic, because timing-based concurrency proves nothing when it
        // happens to serialise: hold the row in an open transaction, start a
        // reservation so it blocks on the lock with its snapshot already taken,
        // append an entry from inside the holding transaction, then commit. The
        // blocked statement resumes against a row that has changed underneath
        // it, which is exactly the case the SQL has to survive.
        const holder = await pool.connect();
        let blocked: Promise<number>;
        try {
            await holder.query('BEGIN');
            await holder.query('SELECT uuid FROM rebom.boms WHERE uuid = $1 FOR UPDATE', [uuid]);

            blocked = reserveEnrichmentRun(uuid, 'manual');
            // Give it time to reach the lock rather than the assertion.
            await new Promise((r) => setTimeout(r, 300));

            await holder.query(
                `UPDATE rebom.boms
                 SET meta = jsonb_set(meta, '{enrichments}', jsonb_build_array(
                       jsonb_build_object('sequence', 0, 'tag', uuid::text, 'status', 'COMPLETED'),
                       jsonb_build_object('sequence', 1, 'status', 'RUNNING', 'source', 'scheduler')))
                 WHERE uuid = $1`, [uuid]);
            await holder.query('COMMIT');
        } finally {
            holder.release();
        }

        // 0 and 1 are taken by the transaction that committed first. A statement
        // that computed its sequence from a pre-update snapshot would say 1 and
        // overwrite that entry.
        expect(await blocked).toBe(2);

        const res = await runQuery(`SELECT meta->'enrichments' AS runs FROM rebom.boms WHERE uuid = $1`, [uuid]);
        const runs = res.rows[0].runs;
        expect(runs.map((r: any) => r.sequence)).toEqual([0, 1, 2]);
        expect(runs.filter((r: any) => r.status === 'RUNNING')).toHaveLength(2);
    });

    it('keeps every entry when several reservations land together', async () => {
        const sequences = await Promise.all(
            Array.from({ length: 6 }, () => reserveEnrichmentRun(uuid, 'scheduler'))
        );
        expect(new Set(sequences).size).toBe(6);

        const res = await runQuery(`SELECT meta->'enrichments' AS runs FROM rebom.boms WHERE uuid = $1`, [uuid]);
        const runs = res.rows[0].runs;
        expect(runs).toHaveLength(7);
        expect(runs.map((r: any) => r.sequence).sort((a: number, b: number) => a - b))
            .toEqual([0, 1, 2, 3, 4, 5, 6]);
    });

    it('synthesises entry 0 from a legacy row before appending', async () => {
        const sequence = await reserveEnrichmentRun(uuid, 'scheduler');
        expect(sequence).toBe(1);

        const res = await runQuery(`SELECT meta->'enrichments' AS runs FROM rebom.boms WHERE uuid = $1`, [uuid]);
        const [entry0, entry1] = res.rows[0].runs;
        // The upload is entry 0: the row's own processed artifact, described
        // from the fields it already carried, so the history explains every
        // artifact this row has ever pointed at.
        expect(entry0).toMatchObject({
            sequence: 0, tag: uuid, repository: 'rebom-artifacts-2026-08',
            digest: 'digest-0', status: 'COMPLETED', source: 'on-upload'
        });
        expect(entry1).toMatchObject({ sequence: 1, status: 'RUNNING', source: 'scheduler' });
    });
});
