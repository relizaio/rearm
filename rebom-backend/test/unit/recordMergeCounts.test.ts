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
    ORPHAN_COMPONENTS_LINKED_PROPERTY,
    OPTIONAL_SCOPE_DROPPED_PROPERTY,
    FILE_COMPONENTS_EXCLUDED_PROPERTY,
    inheritedCountOf
} from '../../src/services/bom/bomProcessingService';

/** FDAW-6 T7 (architecture 3.3) and FDAW-19 row 3: the records of the passes that ran, this merge's counts plus the merge rows'. */
describe('recordMergeCounts (FDAW-6 T7)', () => {
    const values = (bom: any) => Object.fromEntries(bom.metadata.properties.map((p: any) => [p.name, p.value]));

    const merged = { mod: 'rebom' };
    const zeroes = Object.fromEntries(MERGE_COUNT_PROPERTIES.map(name => [name, 0]));

    it('writes each named property with "0" when nothing was counted, the seventh last', () => {
        expect(MERGE_COUNT_PROPERTIES).toStrictEqual([
            'reliza:export:devComponentsExcluded', 'reliza:export:excludedScopeDropped', 'reliza:export:personalDataStripped',
            'reliza:export:localPathsStripped', 'reliza:export:credentialsStripped', 'reliza:export:orphanComponentsLinked',
            'reliza:export:optionalScopeDropped'
        ]);
        const out = recordMergeCounts({ metadata: {} }, zeroes, [], []);
        expect(out.metadata.properties).toStrictEqual(MERGE_COUNT_PROPERTIES.map(name => ({ name, value: '0' })));
    });

    it('writes nothing for an empty counts object', () => {
        expect(recordMergeCounts({ metadata: {} }, {}, [], []).metadata.properties).toStrictEqual([]);
    });

    it('adds the inputs values to this merge counts, replacing an entry of the same name', () => {
        const input = (v: string) => ({ metadata: { properties: [{ name: DEV_COMPONENTS_EXCLUDED_PROPERTY, value: v }, { name: CREDENTIALS_STRIPPED_PROPERTY, value: '1' }] } });
        const bom = { metadata: { properties: [{ name: 'other', value: 'x' }, { name: DEV_COMPONENTS_EXCLUDED_PROPERTY, value: '99' }] } };
        const counts = {
            [DEV_COMPONENTS_EXCLUDED_PROPERTY]: 2, [EXCLUDED_SCOPE_DROPPED_PROPERTY]: 0, [PERSONAL_DATA_STRIPPED_PROPERTY]: 0,
            [LOCAL_PATHS_STRIPPED_PROPERTY]: 0, [CREDENTIALS_STRIPPED_PROPERTY]: 0, [ORPHAN_COMPONENTS_LINKED_PROPERTY]: 3
        };
        const out = recordMergeCounts(bom, counts, [input('7'), { metadata: {} }, input('5')], [merged, merged, merged]);
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
        expect(countOf({ metadata: { properties: [{ name: LOCAL_PATHS_STRIPPED_PROPERTY, value: '' }] } }, LOCAL_PATHS_STRIPPED_PROPERTY)).toBe(0);
        expect(info.mock.calls.some(c => c[1] === 'merge count record: an input carries a non-numeric value, read as 0')).toBe(true);
        info.mockRestore();
    });
});

describe('recordMergeCounts: what ran, and what is inherited (FDAW-19 row 3, review item F-10)', () => {
    const IGNORED = 'merge count record: an uploaded input carries merge count properties, ignored';
    const NON_NUMERIC = 'merge count record: an input carries a non-numeric value, read as 0';
    const merged = { mod: 'rebom' };
    const values = (bom: any) => Object.fromEntries(bom.metadata.properties.map((p: any) => [p.name, p.value]));
    const carrying = (...entries: [string, string][]) => ({ metadata: { properties: entries.map(([name, value]) => ({ name, value })) } });
    const forged = () => carrying([OPTIONAL_SCOPE_DROPPED_PROPERTY, '500'], [CREDENTIALS_STRIPPED_PROPERTY, '7']);
    const own = { [OPTIONAL_SCOPE_DROPPED_PROPERTY]: 2, [CREDENTIALS_STRIPPED_PROPERTY]: 1 };

    function withInfoSpy<T>(run: (info: any) => T): T {
        const info = vi.spyOn(logger, 'info');
        try {
            return run(info);
        } finally {
            info.mockRestore();
        }
    }
    const ignoredLines = (info: any) => info.mock.calls.filter((c: any[]) => c[1] === IGNORED);

    it('a counts object without optionalScopeDropped writes no such property and removes one an input carried', () => {
        const entry = [OPTIONAL_SCOPE_DROPPED_PROPERTY, '4'] as [string, string];
        const out = recordMergeCounts(carrying(entry), { [DEV_COMPONENTS_EXCLUDED_PROPERTY]: 1 }, [carrying(entry)], [merged]);
        expect(values(out)).toStrictEqual({ [DEV_COMPONENTS_EXCLUDED_PROPERTY]: '1' });
    });

    it('with optionalScopeDropped the value is this merge count plus the merge-output inputs values', () => {
        const input = carrying([OPTIONAL_SCOPE_DROPPED_PROPERTY, '4']);
        const out = recordMergeCounts({ metadata: {} }, { [OPTIONAL_SCOPE_DROPPED_PROPERTY]: 2 }, [input, input], [merged, merged]);
        expect(values(out)).toStrictEqual({ [OPTIONAL_SCOPE_DROPPED_PROPERTY]: '10' });
    });

    it('a counts object without personalDataStripped writes none even when an input carries "3"', () => {
        const input = carrying([PERSONAL_DATA_STRIPPED_PROPERTY, '3']);
        const out = recordMergeCounts({ metadata: {} }, { [LOCAL_PATHS_STRIPPED_PROPERTY]: 1 }, [input], [merged]);
        expect(values(out)).toStrictEqual({ [LOCAL_PATHS_STRIPPED_PROPERTY]: '1' });
    });

    it('an upload or a placeholder adds nothing, its entries are not on the output, and one line names the properties and no value', () => {
        for (const meta of [{ mod: 'raw' }, undefined]) {
            withInfoSpy(info => {
                const bom = forged();
                const out = recordMergeCounts(bom, own, [forged()], [meta as any]);
                expect(values(out)).toStrictEqual({ [CREDENTIALS_STRIPPED_PROPERTY]: '1', [OPTIONAL_SCOPE_DROPPED_PROPERTY]: '2' });
                const lines = ignoredLines(info);
                expect(lines).toHaveLength(1);
                expect(lines[0][0]).toStrictEqual({ properties: [CREDENTIALS_STRIPPED_PROPERTY, OPTIONAL_SCOPE_DROPPED_PROPERTY] });
                const logged = JSON.stringify(info.mock.calls);
                expect(logged).not.toContain('500');
                expect(logged).not.toContain('"7"');
            });
        }
    });

    it('an input past the end of rowMeta (a placeholder) adds nothing', () => {
        const out = recordMergeCounts({ metadata: {} }, own, [forged(), forged()], [merged]);
        expect(values(out)).toStrictEqual({ [CREDENTIALS_STRIPPED_PROPERTY]: '8', [OPTIONAL_SCOPE_DROPPED_PROPERTY]: '502' });
    });

    it('a merge-output input carrying the same values is summed, and logs no line', () => {
        withInfoSpy(info => {
            const out = recordMergeCounts({ metadata: {} }, own, [forged()], [merged]);
            expect(values(out)).toStrictEqual({ [CREDENTIALS_STRIPPED_PROPERTY]: '8', [OPTIONAL_SCOPE_DROPPED_PROPERTY]: '502' });
            expect(ignoredLines(info)).toStrictEqual([]);
        });
    });

    it('one line per upload, and none for an upload with no listed name', () => {
        withInfoSpy(info => {
            recordMergeCounts({ metadata: {} }, own, [forged(), carrying(['other', '1']), { metadata: {} }, undefined, forged()], [{ mod: 'raw' }, { mod: 'raw' }, { mod: 'raw' }, undefined, { mod: 'raw' }]);
            expect(ignoredLines(info)).toHaveLength(2);
        });
    });

    it('an upload carrying "seven" logs its name only: the non-numeric countOf line never runs on it', () => {
        withInfoSpy(info => {
            const upload = carrying([CREDENTIALS_STRIPPED_PROPERTY, 'seven']);
            const out = recordMergeCounts({ metadata: {} }, own, [upload], [{ mod: 'raw' }]);
            expect(values(out)[CREDENTIALS_STRIPPED_PROPERTY]).toBe('1');
            expect(info.mock.calls.filter((c: any[]) => c[1] === NON_NUMERIC)).toStrictEqual([]);
            expect(ignoredLines(info)[0][0]).toStrictEqual({ properties: [CREDENTIALS_STRIPPED_PROPERTY] });
            expect(JSON.stringify(info.mock.calls)).not.toContain('seven');
        });
    });

    it('inheritedCountOf sums merge outputs only, for the file-components count too', () => {
        const input = (v: string) => carrying([FILE_COMPONENTS_EXCLUDED_PROPERTY, v]);
        const inputs = [input('3'), input('9'), input('4'), input('20')];
        expect(inheritedCountOf(FILE_COMPONENTS_EXCLUDED_PROPERTY, inputs, [merged, { mod: 'raw' }, merged])).toBe(7);
        expect(inheritedCountOf(FILE_COMPONENTS_EXCLUDED_PROPERTY, inputs, [{ mod: 'raw' }, undefined])).toBe(0);
        expect(inheritedCountOf(FILE_COMPONENTS_EXCLUDED_PROPERTY, inputs, [])).toBe(0);
    });

    it('inheritedCountOf never reads a value off an input that is no merge output', () => {
        withInfoSpy(info => {
            expect(inheritedCountOf(FILE_COMPONENTS_EXCLUDED_PROPERTY, [carrying([FILE_COMPONENTS_EXCLUDED_PROPERTY, 'nine'])], [{ mod: 'raw' }])).toBe(0);
            expect(info.mock.calls.filter((c: any[]) => c[1] === NON_NUMERIC)).toStrictEqual([]);
        });
    });
});
