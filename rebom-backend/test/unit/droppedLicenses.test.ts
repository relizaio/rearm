import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { logger } from '../../src/logger';
import {
    droppedLicenseExpressions,
    recordDroppedLicenses,
    logListedLicenseDrops,
    DROPPED_LICENSE_PROPERTY
} from '../../src/services/bom/bomProcessingService';

/**
 * SCORE-44 T-3 (ADR-1, ADR-2): the predicate every fold site uses to name the listed licence
 * expressions it lost, the helper that records them on the node, and the drops
 * logListedLicenseDrops hands to the record after merge-boms.
 */

const ONE = 'pkg:npm/one@1.0.0';
const LISTED_DROP = 'merge-boms will drop the listed expression: the survivor licenses stand';

const L = () => [{ license: { id: 'MIT' } }];
const L2 = () => [{ license: { id: 'Apache-2.0' } }, { license: { name: 'Custom' } }];
const X = () => [{ expression: 'MIT OR Apache-2.0' }];
const X2 = () => [{ expression: 'BSD-3-Clause OR MIT' }];
const X3 = () => [{ expression: 'GPL-2.0-only WITH Classpath-exception-2.0' }];
const N = () => [{ expression: 'NOASSERTION' }];
const NN = () => [{ expression: 'NONE' }];

const P = (value: string) => ({ name: DROPPED_LICENSE_PROPERTY, value });

describe('droppedLicenseExpressions (SCORE-44 T-3, ADR-2)', () => {
    it('names the property reliza:license:dropped', () => {
        expect(DROPPED_LICENSE_PROPERTY).toBe('reliza:license:dropped');
    });

    it('a listed expression beside kept ids is dropped', () => {
        expect(droppedLicenseExpressions(L(), X2())).toStrictEqual(['BSD-3-Clause OR MIT']);
    });

    it('a listed expression beside another kept expression is dropped', () => {
        expect(droppedLicenseExpressions(X(), X2())).toStrictEqual(['BSD-3-Clause OR MIT']);
    });

    it('an identical expression loses nothing', () => {
        expect(droppedLicenseExpressions(X(), X())).toStrictEqual([]);
    });

    it('NOASSERTION and NONE, in any case, assert no licence', () => {
        expect(droppedLicenseExpressions(L(), N())).toStrictEqual([]);
        expect(droppedLicenseExpressions(L(), NN())).toStrictEqual([]);
        expect(droppedLicenseExpressions(L(), [{ expression: 'none' }])).toStrictEqual([]);
    });

    it('ids and names dropped beside a kept expression are not named', () => {
        expect(droppedLicenseExpressions(X(), L2())).toStrictEqual([]);
    });

    it('each distinct text once, in order', () => {
        expect(droppedLicenseExpressions(L(), [X2()[0], X3()[0], X2()[0]]))
            .toStrictEqual(['BSD-3-Clause OR MIT', 'GPL-2.0-only WITH Classpath-exception-2.0']);
    });

    it('a non-array on either side names nothing', () => {
        expect(droppedLicenseExpressions(undefined, X2())).toStrictEqual([]);
        expect(droppedLicenseExpressions(L(), undefined)).toStrictEqual([]);
        expect(droppedLicenseExpressions(L(), X2()[0])).toStrictEqual([]);
    });
});

describe('recordDroppedLicenses (SCORE-44 T-3, ADR-1)', () => {
    it('creates properties when absent and returns the count added', () => {
        const node: any = { name: 'one' };

        expect(recordDroppedLicenses(node, ['BSD-3-Clause OR MIT'])).toBe(1);

        expect(node.properties).toStrictEqual([P('BSD-3-Clause OR MIT')]);
    });

    it('appends after the existing entries, in a new array, leaving the one it found as it was', () => {
        const found = [{ name: 'cdx:other', value: 'kept' }];
        const node: any = { name: 'one', properties: found };

        expect(recordDroppedLicenses(node, ['BSD-3-Clause OR MIT', 'GPL-2.0-only WITH Classpath-exception-2.0'])).toBe(2);

        expect(node.properties).toStrictEqual([
            { name: 'cdx:other', value: 'kept' },
            P('BSD-3-Clause OR MIT'),
            P('GPL-2.0-only WITH Classpath-exception-2.0')
        ]);
        expect(node.properties).not.toBe(found);
        expect(found).toStrictEqual([{ name: 'cdx:other', value: 'kept' }]);
    });

    it('skips a value the node already records, and one repeated in the list', () => {
        const node: any = { name: 'one', properties: [P('BSD-3-Clause OR MIT')] };
        const found = node.properties;

        expect(recordDroppedLicenses(node, ['BSD-3-Clause OR MIT'])).toBe(0);
        expect(node.properties).toBe(found);

        expect(recordDroppedLicenses(node, ['MIT OR Apache-2.0', 'MIT OR Apache-2.0'])).toBe(1);
        expect(node.properties).toStrictEqual([P('BSD-3-Clause OR MIT'), P('MIT OR Apache-2.0')]);
    });

    it('is a no-op on a non-object and on an empty list', () => {
        expect(recordDroppedLicenses(null, ['BSD-3-Clause OR MIT'])).toBe(0);
        expect(recordDroppedLicenses('one', ['BSD-3-Clause OR MIT'])).toBe(0);
        const node: any = { name: 'one' };

        expect(recordDroppedLicenses(node, [])).toBe(0);

        expect(node).toStrictEqual({ name: 'one' });
    });
});

describe('logListedLicenseDrops returns the drops it logs, with the expressions lost (SCORE-44 T-3, ADR-2 site 3)', () => {
    let info: ReturnType<typeof vi.spyOn>;

    beforeEach(() => {
        info = vi.spyOn(logger, 'info').mockImplementation(() => undefined as any);
    });

    afterEach(() => {
        info.mockRestore();
    });

    function rooted(name: string, licenses?: any[], components: any[] = []): any {
        const ref = `pkg:npm/${name}@1.0.0`;
        return {
            bomFormat: 'CycloneDX', specVersion: '1.6', version: 1,
            metadata: { component: { type: 'application', name, version: '1.0.0', purl: ref, 'bom-ref': ref, ...(licenses ? { licenses } : {}) } },
            components
        };
    }

    function listedOne(licenses: any[]): any {
        return { type: 'library', name: 'one', version: '1.0.0', purl: ONE, 'bom-ref': ONE, licenses };
    }

    it('a root with ids and a listing with an expression: one drop keyed by the root purl, naming the expression', () => {
        expect(logListedLicenseDrops([rooted('one', L()), rooted('two', undefined, [listedOne(X2())])]))
            .toStrictEqual([{ key: ONE, bomRef: ONE, kept: L(), dropped: X2(), expressions: ['BSD-3-Clause OR MIT'] }]);
    });

    it('a listing with NOASSERTION: the line is still logged, the drop names no expression', () => {
        expect(logListedLicenseDrops([rooted('one', L()), rooted('two', undefined, [listedOne(N())])]))
            .toStrictEqual([{ key: ONE, bomRef: ONE, kept: L(), dropped: N(), expressions: [] }]);
        expect(info.mock.calls).toStrictEqual([[{ bomRef: ONE, kept: L(), dropped: N() }, LISTED_DROP]]);
    });

    it('a root and a listing with the same expression: no drop', () => {
        expect(logListedLicenseDrops([rooted('one', X()), rooted('two', undefined, [listedOne(X())])])).toStrictEqual([]);
    });

    it('three inputs (SCORE-40 T-13 a): one drop whose kept list is the union merge-boms holds by then', () => {
        const drops = logListedLicenseDrops([rooted('one', L()), rooted('two', undefined, [listedOne(L2())]), rooted('three', undefined, [listedOne(X2())])]);

        expect(drops).toStrictEqual([{
            key: ONE,
            bomRef: ONE,
            kept: [{ license: { id: 'MIT' } }, { license: { id: 'Apache-2.0' } }, { license: { name: 'Custom' } }],
            dropped: X2(),
            expressions: ['BSD-3-Clause OR MIT']
        }]);
    });
});
