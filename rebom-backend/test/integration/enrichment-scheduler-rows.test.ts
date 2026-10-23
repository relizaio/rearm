import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import crypto from 'node:crypto';

import { findBomsNeedingEnrichment } from '../../src/services/enrichmentScheduler';
import { fetchProcessedBomWithRetry } from '../../src/services/oci';
import { runQuery } from '../../src/utils';
import { RELEASE_NODE_PROPERTY, REBOM_MERGE_MOD, UPLOAD_MOD } from '../../src/services/bom/releaseNodeStamp';

/**
 * SCORE-60 (T-26, ADR-9 (d); functional F-7): the enrichment scheduler's candidate rows carry `meta`,
 * so the fetcher resolves each row's processed tag against its digest and keeps a merge output's own
 * release node stamps, while an upload is still served without them. Live Postgres: the query reads
 * rebom.integrations directly (V8), as the image's test database has it.
 */
describe('the enrichment scheduler reads whole rows', () => {
    const org = crypto.randomUUID();
    const integration = crypto.randomUUID();
    const merge = crypto.randomUUID();
    const upload = crypto.randomUUID();
    const REPO = 'rebom-artifacts-2026-08';
    const STAMP = { name: RELEASE_NODE_PROPERTY, value: 'true' };
    const mergeMeta = { serialNumber: `urn:uuid:${crypto.randomUUID()}`, mod: REBOM_MERGE_MOD, enrichmentStatus: 'FAILED',
        processedTag: `${merge}-e1`, processedFileDigest: 'd1' };
    const uploadMeta = { serialNumber: `urn:uuid:${crypto.randomUUID()}`, mod: UPLOAD_MOD, processedFileDigest: 'd2' };
    const stamped = () => ({ bomFormat: 'CycloneDX', metadata: { component: { name: 'root', properties: [STAMP] } }, components: [] });

    beforeEach(async () => {
        await runQuery(
            `INSERT INTO rebom.integrations (uuid, config, organization) VALUES ($1, $2, $3)`,
            [integration, { type: 'BEAR', uri: 'http://bear.test', secretUuid: crypto.randomUUID() }, org]
        );
        for (const [uuid, meta] of [[merge, mergeMeta], [upload, uploadMeta]] as const) {
            await runQuery(
                `INSERT INTO rebom.boms (uuid, meta, bom, organization, source_format) VALUES ($1, $2, $3, $4, 'CYCLONEDX')`,
                [uuid, meta, { ociRepositoryName: REPO }, org]
            );
        }
    });

    afterEach(async () => {
        await runQuery('DELETE FROM rebom.boms WHERE uuid = ANY($1)', [[merge, upload]]);
        await runQuery('DELETE FROM rebom.integrations WHERE uuid = $1', [integration]);
    });

    it('returns each candidate with its meta beside the fields it carried before', async () => {
        const rows = await findBomsNeedingEnrichment();
        const byUuid = (uuid: string) => rows.find((r) => r.uuid === uuid)!;

        expect(byUuid(merge)).toBeDefined();
        expect(byUuid(upload)).toBeDefined();
        expect(byUuid(merge).meta).toStrictEqual(mergeMeta);
        expect(byUuid(upload).meta).toStrictEqual(uploadMeta);
        expect(byUuid(merge).organization).toBe(org);
        expect(byUuid(merge).serialNumber).toBe(mergeMeta.serialNumber);
        expect(byUuid(merge).bom).toStrictEqual({ ociRepositoryName: REPO });
    });

    it('hands the fetcher rows it can decide on without a re-read', async () => {
        const rows = await findBomsNeedingEnrichment();
        const mergeRow = rows.find((r) => r.uuid === merge)!;
        const uploadRow = rows.find((r) => r.uuid === upload)!;

        const content = stamped();
        const fetch = vi.fn(async () => content);
        const reRead = vi.fn();
        expect(await fetchProcessedBomWithRetry(mergeRow, fetch, reRead)).toBe(content);
        expect(fetch).toHaveBeenCalledWith(`${merge}-e1`, REPO, 'd1');
        expect(reRead).not.toHaveBeenCalled();

        const served = await fetchProcessedBomWithRetry(uploadRow, vi.fn(async () => stamped()), reRead);
        expect(served.metadata.component.properties).toStrictEqual([]);
        expect(reRead).not.toHaveBeenCalled();
    });
});
