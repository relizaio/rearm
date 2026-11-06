import { describe, it, expect, vi } from 'vitest';
import { logger } from '../../src/logger';
import {
    recordMergeCounts,
    countOf,
    MERGE_COUNT_PROPERTIES,
    DEV_COMPONENTS_EXCLUDED_PROPERTY,
    EXCLUDED_SCOPE_DROPPED_PROPERTY,
    PERSONAL_DATA_STRIPPED_PROPERTY,
    LOCAL_PATHS_STRIPPED_PROPERTY,
    CREDENTIALS_STRIPPED_PROPERTY,
    ORPHAN_COMPONENTS_LINKED_PROPERTY
} from '../../src/services/bom/bomProcessingService';

/** FDAW-6 T7 (architecture 3.3): the six records, always written, this merge's counts plus the inputs'. */
describe('recordMergeCounts (FDAW-6 T7)', () => {
    const values = (bom: any) => Object.fromEntries(bom.metadata.properties.map((p: any) => [p.name, p.value]));

    it('writes the six properties with "0" when nothing was counted', () => {
        expect(MERGE_COUNT_PROPERTIES).toStrictEqual([
            'reliza:export:devComponentsExcluded', 'reliza:export:excludedScopeDropped', 'reliza:export:personalDataStripped',
            'reliza:export:localPathsStripped', 'reliza:export:credentialsStripped', 'reliza:export:orphanComponentsLinked'
        ]);
        const out = recordMergeCounts({ metadata: {} }, {}, []);
        expect(out.metadata.properties).toStrictEqual(MERGE_COUNT_PROPERTIES.map(name => ({ name, value: '0' })));
    });

    it('adds the inputs values to this merge counts, replacing an entry of the same name', () => {
        const input = (v: string) => ({ metadata: { properties: [{ name: DEV_COMPONENTS_EXCLUDED_PROPERTY, value: v }, { name: CREDENTIALS_STRIPPED_PROPERTY, value: '1' }] } });
        const bom = { metadata: { properties: [{ name: 'other', value: 'x' }, { name: DEV_COMPONENTS_EXCLUDED_PROPERTY, value: '99' }] } };
        const out = recordMergeCounts(bom, { [DEV_COMPONENTS_EXCLUDED_PROPERTY]: 2, [ORPHAN_COMPONENTS_LINKED_PROPERTY]: 3 }, [input('7'), { metadata: {} }, input('5')]);
        expect(values(out)).toStrictEqual({
            other: 'x',
            [DEV_COMPONENTS_EXCLUDED_PROPERTY]: '14',
            [EXCLUDED_SCOPE_DROPPED_PROPERTY]: '0',
            [PERSONAL_DATA_STRIPPED_PROPERTY]: '0',
            [LOCAL_PATHS_STRIPPED_PROPERTY]: '0',
            [CREDENTIALS_STRIPPED_PROPERTY]: '2',
            [ORPHAN_COMPONENTS_LINKED_PROPERTY]: '3'
        });
        expect(out.metadata.properties.filter((p: any) => p.name === DEV_COMPONENTS_EXCLUDED_PROPERTY)).toHaveLength(1);
        expect(bom.metadata.properties).toHaveLength(2);
    });

    it('countOf reads a number, 0 for a missing property, and 0 with a log line for a non-numeric value', () => {
        const info = vi.spyOn(logger, 'info');
        expect(countOf({ metadata: { properties: [{ name: LOCAL_PATHS_STRIPPED_PROPERTY, value: '7' }] } }, LOCAL_PATHS_STRIPPED_PROPERTY)).toBe(7);
        expect(countOf({ metadata: {} }, LOCAL_PATHS_STRIPPED_PROPERTY)).toBe(0);
        expect(countOf(undefined, LOCAL_PATHS_STRIPPED_PROPERTY)).toBe(0);
        expect(countOf({ metadata: { properties: [{ name: LOCAL_PATHS_STRIPPED_PROPERTY, value: 'seven' }] } }, LOCAL_PATHS_STRIPPED_PROPERTY)).toBe(0);
        expect(countOf({ metadata: { properties: [{ name: LOCAL_PATHS_STRIPPED_PROPERTY, value: '-3' }] } }, LOCAL_PATHS_STRIPPED_PROPERTY)).toBe(0);
        expect(info.mock.calls.some(c => c[1] === 'merge count record: an input carries a non-numeric value, read as 0')).toBe(true);
        info.mockRestore();
    });
});
