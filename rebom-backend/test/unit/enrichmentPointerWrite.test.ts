import { describe, it, expect, vi, beforeEach } from 'vitest';

const runQuery = vi.fn();
vi.mock('../../src/utils', () => ({
    runQuery: (...a: any[]) => runQuery.apply(null, a as any),
    pool: {},
    createTempFile: vi.fn(), deleteTempFile: vi.fn(), shellExec: vi.fn()
}));
vi.mock('../../src/logger', () => ({
    logger: { info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn() }
}));

import { updateEnrichmentStatusWithBom } from '../../src/services/bom/bomProcessingService';
import { EnrichmentStatus } from '../../src/types';

/**
 * The write that moves the row onto a freshly pushed artifact used to swallow
 * its own failures. When it failed, the artifact existed, nothing pointed at
 * it, the run's history entry stayed RUNNING for ever, and the caller went on
 * to log the enrichment as completed successfully -- a false success and a
 * stale record out of one failed statement. It has to fail loudly so the caller
 * can mark the run and close its entry.
 */
describe('the pointer write reports its own failure', () => {
    beforeEach(() => vi.clearAllMocks());

    it('rejects when the update fails instead of returning quietly', async () => {
        runQuery.mockRejectedValueOnce(new Error('deadlock detected'));
        await expect(updateEnrichmentStatusWithBom(
            'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
            EnrichmentStatus.COMPLETED,
            { fileSHA256Digest: 'digest', originalSize: 10, ociRepositoryName: 'rebom-artifacts-2026-09' },
            'rebom-artifacts-2026-09',
            'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee-e1',
            1
        )).rejects.toThrow('deadlock detected');
    });

    it('resolves when the update succeeds', async () => {
        runQuery.mockResolvedValueOnce({ rows: [], rowCount: 1 });
        await expect(updateEnrichmentStatusWithBom(
            'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
            EnrichmentStatus.COMPLETED,
            { fileSHA256Digest: 'digest', originalSize: 10, ociRepositoryName: 'rebom-artifacts-2026-09' },
            'rebom-artifacts-2026-09',
            'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee-e1',
            1
        )).resolves.toBeUndefined();
    });
});
