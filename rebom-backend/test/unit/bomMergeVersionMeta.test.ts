import { describe, it, expect } from 'vitest';
import { BomMapper } from '../../src/services/bom/bomMapper';
import { toNestedMeta, fromNestedMeta } from '../../src/types/bom-meta';
import { createTestRebomOptions } from '../helpers';

/**
 * SCORE-13 (T-11): the caller's merge rule version is stored with a merged BOM verbatim and
 * read back out the same; a merge stored without one reads back without one.
 */

function record(meta: any): any {
    return {
        uuid: '7a3d6c1e-2b4f-4e8a-9c5d-1f2e3d4c5b6a',
        created_date: new Date('2026-10-07T00:00:00Z'),
        last_updated_date: new Date('2026-10-07T00:00:00Z'),
        meta,
        bom: {},
        tags: {},
        organization: 'org',
        public: false,
        duplicate: false
    };
}

describe('mergeVersion in the stored meta', () => {
    it('reads a stored mergeVersion back out on the meta DTO', () => {
        const meta = createTestRebomOptions({ mergeVersion: 2, excludeFileComponents: true });

        const dto = BomMapper.toMetaDto(record(meta));

        expect(dto.mergeVersion).toBe(2);
        expect(dto.excludeFileComponents).toBe(true);
    });

    it('reads no mergeVersion when the merge was stored without one', () => {
        const dto = BomMapper.toMetaDto(record(createTestRebomOptions({})));

        expect(dto.mergeVersion).toBeUndefined();
    });

    it('round-trips mergeVersion through the nested meta, and absent stays absent', () => {
        const withVersion = createTestRebomOptions({ mergeVersion: 2 });
        const nested = toNestedMeta(withVersion);
        expect(nested.merge?.mergeVersion).toBe(2);
        expect(fromNestedMeta(nested).mergeVersion).toBe(2);

        const without = toNestedMeta(createTestRebomOptions({}));
        expect(without.merge?.mergeVersion).toBeUndefined();
        expect(fromNestedMeta(without).mergeVersion).toBeUndefined();
    });
});
