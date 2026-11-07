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

/** FDAW-19 row 6: the three keep options are stored on the merge row only when sent, and served. */
describe('the keep options in the stored meta', () => {
    const KEEP = ['keepExcludedScope', 'keepPersonalData', 'keepLocalPaths'];

    it('round-trips the three through the nested meta, false included', () => {
        const meta = createTestRebomOptions({ keepExcludedScope: true, keepPersonalData: false, keepLocalPaths: true });

        const nested = toNestedMeta(meta);
        expect(nested.merge).toMatchObject({ keepExcludedScope: true, keepPersonalData: false, keepLocalPaths: true });
        expect(fromNestedMeta(nested)).toMatchObject({ keepExcludedScope: true, keepPersonalData: false, keepLocalPaths: true });
    });

    it('writes one sent alone and none of the others', () => {
        const nested = toNestedMeta(createTestRebomOptions({ keepPersonalData: true }));
        expect(nested.merge?.keepPersonalData).toBe(true);
        expect(nested.merge).not.toHaveProperty('keepExcludedScope');
        expect(nested.merge).not.toHaveProperty('keepLocalPaths');
    });

    it('a meta built without the three has none of the keys, and reads back without them', () => {
        const nested = toNestedMeta(createTestRebomOptions({ mergeVersion: 19 }));
        const back = fromNestedMeta(nested);
        for (const key of KEEP) {
            expect(nested.merge).not.toHaveProperty(key);
            expect(back).not.toHaveProperty(key);
        }
    });

    it('the meta DTO serves the three, and undefined for a row without them', () => {
        const dto = BomMapper.toMetaDto(record(createTestRebomOptions({ keepExcludedScope: true, keepPersonalData: false, keepLocalPaths: true })));
        expect([dto.keepExcludedScope, dto.keepPersonalData, dto.keepLocalPaths]).toStrictEqual([true, false, true]);

        const without = BomMapper.toMetaDto(record(createTestRebomOptions({})));
        expect([without.keepExcludedScope, without.keepPersonalData, without.keepLocalPaths]).toStrictEqual([undefined, undefined, undefined]);
    });
});

/** SCORE-63 T-4: the generation-time lists are not stored. */
describe('the release root lists in the stored meta', () => {
    it('stores neither attachedRootIds nor releaseRootIds', () => {
        // createTestRebomOptions names its fields; the two lists are set on the request as sent.
        const meta = { ...createTestRebomOptions({ mergeVersion: 14 }), releaseRootIds: ['r1'], attachedRootIds: ['a1', 'a2'] };

        const nested = toNestedMeta(meta);
        const text = JSON.stringify(nested);

        expect(text).not.toContain('attachedRootIds');
        expect(text).not.toContain('releaseRootIds');
        expect(fromNestedMeta(nested)).not.toHaveProperty('attachedRootIds');
        expect(fromNestedMeta(nested)).not.toHaveProperty('releaseRootIds');
    });
});

/** SCORE-69 T-4: the merge row's two store-side records round-trip through the nested meta. */
describe('the merge row records in the nested meta', () => {
    it('round-trips nestedProductSerials and listedRootKeys under merge', () => {
        const meta = { ...createTestRebomOptions({ mergeVersion: 17 }), nestedProductSerials: ['urn:uuid:s1'], listedRootKeys: ['pkg:npm/a@1.0.0', 'ref-a'] };

        const nested = toNestedMeta(meta);
        expect(nested.merge?.nestedProductSerials).toStrictEqual(['urn:uuid:s1']);
        expect(nested.merge?.listedRootKeys).toStrictEqual(['pkg:npm/a@1.0.0', 'ref-a']);
        const back = fromNestedMeta(nested);
        expect(back.nestedProductSerials).toStrictEqual(['urn:uuid:s1']);
        expect(back.listedRootKeys).toStrictEqual(['pkg:npm/a@1.0.0', 'ref-a']);
    });

    it('keeps them absent when the row has none', () => {
        const nested = toNestedMeta(createTestRebomOptions({ mergeVersion: 17 }));
        expect(nested.merge).not.toHaveProperty('nestedProductSerials');
        expect(nested.merge).not.toHaveProperty('listedRootKeys');
        const back = fromNestedMeta(nested);
        expect(back).not.toHaveProperty('nestedProductSerials');
        expect(back).not.toHaveProperty('listedRootKeys');
    });
});
