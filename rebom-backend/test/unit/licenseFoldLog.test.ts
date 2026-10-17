import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { logger } from '../../src/logger';
import {
    settleLicenseFolds,
    dedupeBomRefs,
    logListedLicenseDrops
} from '../../src/services/bom/bomProcessingService';

/**
 * SCORE-40 T-13 (ADR-6, ADR-8, ADR-9): every licence a fold drops is named in rebom's log at info,
 * with the bom-ref, the list kept and the entries dropped: the settle of a mixed array, the de-dup
 * union beside an expression, and the drop rearm-cli merge-boms performs under FLAT, predicted
 * from the inputs before the CLI runs. A fold that loses no entry by key (an expression beside the
 * same expression) logs nothing.
 */

const SETTLED = 'license fold settled: the survivor licenses stand';
const UNION_KEPT = 'bom-ref de-dup: an expression cannot be unioned, the survivor licenses stand';
const LISTED_DROP = 'merge-boms will drop the listed expression: the survivor licenses stand';

const X = 'pkg:maven/io.example/app@1.0.0?type=jar';
const ONE = 'pkg:npm/one@1.0.0';

const L = () => [{ license: { id: 'MIT' } }];
const L2 = () => [{ license: { id: 'Apache-2.0' } }, { license: { name: 'Custom' } }];
const XP = () => [{ expression: 'MIT OR Apache-2.0' }];
const X2 = () => [{ expression: 'BSD-3-Clause OR MIT' }];
const N = () => [{ expression: 'NOASSERTION' }];

let info: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
    info = vi.spyOn(logger, 'info').mockImplementation(() => undefined as any);
});

afterEach(() => {
    info.mockRestore();
});

/** The calls the info spy saw with `message` as their message argument. */
function lines(message: string): any[][] {
    return info.mock.calls.filter((call: any[]) => call[1] === message);
}

function doc(root: any, components: any[] = []): any {
    return {
        bomFormat: 'CycloneDX', specVersion: '1.6', version: 1,
        metadata: { component: root },
        components
    };
}

function lib(ref: string, extra: any = {}): any {
    return { type: 'library', name: ref.toLowerCase(), version: '1', 'bom-ref': ref, ...extra };
}

/** An input whose root is pkg:npm/<name>@1.0.0, purl and bom-ref, with `licenses` when given. */
function rooted(name: string, licenses?: any[], components: any[] = []): any {
    const ref = `pkg:npm/${name}@1.0.0`;
    return doc({ type: 'application', name, version: '1.0.0', purl: ref, 'bom-ref': ref, ...(licenses ? { licenses } : {}) }, components);
}

/** An input whose root is pkg:npm/two@1.0.0 and which lists `listed` at the top level. */
function listing(listed: any): any {
    return rooted('two', undefined, [listed]);
}

/** A listing of pkg:npm/one@1.0.0 by purl and bom-ref, with `licenses`. */
function listedOne(licenses: any[], extra: any = {}): any {
    return { type: 'library', name: 'one', version: '1.0.0', purl: ONE, 'bom-ref': ONE, licenses, ...extra };
}

describe('settleLicenseFolds logs each node it settles (T-13)', () => {
    it('one node line with the bom-ref, the kept and the dropped entries, then the count line', () => {
        const bom = doc({ type: 'application', name: 'root', 'bom-ref': 'ROOT' },
            [{ type: 'library', name: 'one', version: '1.0.0', purl: ONE, 'bom-ref': ONE, licenses: [{ expression: 'MIT OR Apache-2.0' }, { license: { id: 'MIT' } }] }]);

        expect(settleLicenseFolds(bom)).toBe(1);

        expect(info.mock.calls).toStrictEqual([
            [{ bomRef: ONE, kept: [{ expression: 'MIT OR Apache-2.0' }], dropped: [{ license: { id: 'MIT' } }] }, SETTLED],
            ['license fold settle: 1 licenses arrays a fold left mixed settled']
        ]);
    });

    it('logs nothing on a document with nothing to settle', () => {
        const bom = doc({ type: 'application', name: 'root', 'bom-ref': 'ROOT', licenses: XP() },
            [lib('A', { licenses: L2() }), lib('B', { licenses: X2() })]);

        expect(settleLicenseFolds(bom)).toBe(0);

        expect(info).not.toHaveBeenCalled();
    });
});

/** Folds a survivor carrying `survivor` licenses with a nested copy carrying `duplicate`. */
function foldLicenses(survivor: any[], duplicate: any[]): any {
    const bom = doc({ type: 'application', name: 'root', 'bom-ref': 'ROOT' },
        [lib(X, { licenses: survivor }), lib('A', { components: [lib(X, { licenses: duplicate })] })]);
    const result = dedupeBomRefs(bom);
    expect(result.count).toBe(1);
    return result.bom;
}

describe('the de-dup licence union logs the duplicate it drops (T-13)', () => {
    it('survivor ids with a duplicate expression: one line with the bom-ref, the kept ids and the dropped expression', () => {
        foldLicenses(L(), X2());

        expect(lines(UNION_KEPT)).toStrictEqual([[{ bomRef: X, kept: L(), dropped: X2() }, UNION_KEPT]]);
    });

    it('survivor ids with a duplicate expression of the design (L, X): one line', () => {
        foldLicenses(L(), XP());

        expect(lines(UNION_KEPT)).toStrictEqual([[{ bomRef: X, kept: L(), dropped: XP() }, UNION_KEPT]]);
    });

    it('survivor expression with a different duplicate expression: one line with the kept and the dropped expression (the control, ADR-9)', () => {
        const survivor = XP();

        const bom = foldLicenses(survivor, X2());

        expect(bom.components[0].licenses).toBe(survivor);
        expect(lines(UNION_KEPT)).toStrictEqual([[{ bomRef: X, kept: XP(), dropped: X2() }, UNION_KEPT]]);
    });

    it('survivor expression with a duplicate holding that expression and an id: the id is lost, one line (ADR-9: an entry the kept list lacks)', () => {
        const duplicate = [...XP(), { license: { id: 'MIT' } }];

        foldLicenses(XP(), duplicate);

        expect(lines(UNION_KEPT)).toStrictEqual([[{ bomRef: X, kept: XP(), dropped: duplicate }, UNION_KEPT]]);
    });

    it('names both lists as the nodes hold them, not as normalised for the fold', () => {
        // a single-id expression is read as the id for the fold, and logged as written
        foldLicenses([{ expression: 'MIT' }], X2());
        foldLicenses(XP(), [{ expression: 'MIT' }]);

        expect(lines(UNION_KEPT)).toStrictEqual([
            [{ bomRef: X, kept: [{ expression: 'MIT' }], dropped: X2() }, UNION_KEPT],
            [{ bomRef: X, kept: XP(), dropped: [{ expression: 'MIT' }] }, UNION_KEPT]
        ]);
    });

    const IDENTICAL: [string, () => any[]][] = [['MIT OR Apache-2.0', XP], ['NOASSERTION', N]];
    for (const [label, list] of IDENTICAL) {
        it(`survivor ${label} with the same duplicate expression: the survivor list stands, the same array, and no licence line (ADR-9)`, () => {
            const survivor = list();

            const bom = foldLicenses(survivor, list());

            expect(bom.components[0].licenses).toBe(survivor);
            expect(survivor).toStrictEqual(list());
            expect(info.mock.calls.filter((call: any[]) => JSON.stringify(call).includes('licen'))).toStrictEqual([]);
        });
    }

    it('two license lists unioned: no licence line', () => {
        const bom = foldLicenses(L(), L2());

        expect(bom.components[0].licenses).toStrictEqual([...L(), ...L2()]);
        expect(lines(UNION_KEPT)).toStrictEqual([]);
        expect(info.mock.calls.filter((call: any[]) => JSON.stringify(call).includes('licen'))).toStrictEqual([]);
    });
});

describe('logListedLicenseDrops names the expression merge-boms will drop under FLAT (T-13, ADR-8)', () => {
    /** Runs it on `inputs`, checks it left them as they were, and returns the number of drops (SCORE-44: one per line). */
    function run(inputs: any[]): number {
        const copies = structuredClone(inputs);
        const count = logListedLicenseDrops(inputs).length;
        expect(inputs).toStrictEqual(copies);
        return count;
    }

    it('a listing with an expression beside a root with ids: one line with the bom-ref, the kept ids and the dropped expression', () => {
        expect(run([rooted('one', L()), listing(listedOne(X2()))])).toBe(1);

        expect(info.mock.calls).toStrictEqual([[{ bomRef: ONE, kept: L(), dropped: X2() }, LISTED_DROP]]);
    });

    it('a listing whose purl is the root\'s under another bom-ref is folded and logged, with the root\'s bom-ref', () => {
        expect(run([rooted('one', L()), listing(listedOne(X2(), { 'bom-ref': 'listed-one' }))])).toBe(1);

        expect(info.mock.calls).toStrictEqual([[{ bomRef: ONE, kept: L(), dropped: X2() }, LISTED_DROP]]);
    });

    it('keys by bom-ref when neither side has a purl', () => {
        const root = doc({ type: 'application', name: 'one', 'bom-ref': 'ref-one', licenses: L() });
        const other = rooted('two', undefined, [{ type: 'library', name: 'one', 'bom-ref': 'ref-one', licenses: X2() }]);

        expect(run([root, other])).toBe(1);

        expect(info.mock.calls).toStrictEqual([[{ bomRef: 'ref-one', kept: L(), dropped: X2() }, LISTED_DROP]]);
    });

    it('a top-level component of the first input survives a later listing of its key', () => {
        const first = rooted('app', undefined, [listedOne(L())]);

        expect(run([first, listing(listedOne(X2()))])).toBe(1);

        expect(info.mock.calls).toStrictEqual([[{ bomRef: ONE, kept: L(), dropped: X2() }, LISTED_DROP]]);
    });

    it('a root with an empty licenses array: merge-boms unions into it and drops the expression, logged (departure D-1)', () => {
        expect(run([rooted('one', []), listing(listedOne(X2()))])).toBe(1);

        expect(info.mock.calls).toStrictEqual([[{ bomRef: ONE, kept: [], dropped: X2() }, LISTED_DROP]]);
    });

    it('two roots of one key: the later root is the one merge-boms keeps and folds into (departure D-2)', () => {
        // the later root has no licenses: merge-boms copies the listing's whole, nothing dropped
        expect(run([rooted('one', L()), rooted('one'), listing(listedOne(X2()))])).toBe(0);
        // the later root has ids: the listing's expression is dropped beside them
        expect(run([rooted('one'), rooted('one', L()), listing(listedOne(X2()))])).toBe(1);

        expect(info.mock.calls).toStrictEqual([[{ bomRef: ONE, kept: L(), dropped: X2() }, LISTED_DROP]]);
    });

    it('a root that took a first listing whole keeps it beside a second listing\'s expression, as merge-boms holds it (departure D-2)', () => {
        expect(run([rooted('one'), listing(listedOne(L2())), rooted('three', undefined, [listedOne(X2())])])).toBe(1);

        expect(info.mock.calls).toStrictEqual([[{ bomRef: ONE, kept: L2(), dropped: X2() }, LISTED_DROP]]);
    });

    it('a root with ids, a listing with more ids, then a listing with an expression: the line keeps the union merge-boms holds by then (round 4, A08)', () => {
        expect(run([rooted('one', L()), listing(listedOne(L2())), rooted('three', undefined, [listedOne(X2())])])).toBe(1);

        expect(info.mock.calls).toStrictEqual([[{
            bomRef: ONE,
            kept: [{ license: { id: 'MIT' } }, { license: { id: 'Apache-2.0' } }, { license: { name: 'Custom' } }],
            dropped: X2()
        }, LISTED_DROP]]);
    });

    it('a listing that repeats an id: merge-boms appends it once, and the line keeps it once', () => {
        const twice = [{ license: { id: 'Apache-2.0' } }, { license: { id: 'Apache-2.0' } }];

        expect(run([rooted('one', L()), listing(listedOne(twice)), rooted('three', undefined, [listedOne(X2())])])).toBe(1);

        expect(info.mock.calls).toStrictEqual([[{ bomRef: ONE, kept: [{ license: { id: 'MIT' } }, { license: { id: 'Apache-2.0' } }], dropped: X2() }, LISTED_DROP]]);
    });

    it('a root without licenses takes a first listing\'s empty array whole, and the next listing\'s expression is dropped beside it', () => {
        expect(run([rooted('one'), listing(listedOne([])), rooted('three', undefined, [listedOne(X2())])])).toBe(1);

        expect(info.mock.calls).toStrictEqual([[{ bomRef: ONE, kept: [], dropped: X2() }, LISTED_DROP]]);
    });

    it('a root with an expression and a listing with a different expression: one line (the control for ADR-9)', () => {
        expect(run([rooted('one', XP()), listing(listedOne(X2()))])).toBe(1);

        expect(info.mock.calls).toStrictEqual([[{ bomRef: ONE, kept: XP(), dropped: X2() }, LISTED_DROP]]);
    });

    const QUIET: [string, () => any[]][] = [
        ['a listing with the root\'s own expression (ADR-9: nothing is lost)', () => [rooted('one', XP()), listing(listedOne(XP()))]],
        ['a listing with NOASSERTION beside a root with NOASSERTION (ADR-9: nothing is lost)', () => [rooted('one', N()), listing(listedOne(N()))]],
        ['a listing with the root\'s expression after a listing with ids was appended to it (ADR-9: the tracked list holds it; the settle logs the ids)',
            () => [rooted('one', XP()), listing(listedOne(L2())), rooted('three', undefined, [listedOne(XP())])]],
        ['a listing with ids (a union, nothing dropped)', () => [rooted('one', L()), listing(listedOne(L2()))]],
        ['a root without licenses (the listing is copied whole)', () => [rooted('one'), listing(listedOne(X2()))]],
        ['a root with an expression and a listing with ids (the settle\'s case)', () => [rooted('one', XP()), listing(listedOne(L2()))]],
        ['a listing nested one level down (not the merge-boms fold)', () => [rooted('one', L()),
            rooted('two', undefined, [{ type: 'library', name: 'mid', version: '1.0.0', purl: 'pkg:npm/mid@1.0.0', 'bom-ref': 'pkg:npm/mid@1.0.0', components: [listedOne(X2())] }])]],
        ['a listing whose purl differs from the root\'s (different keys)', () => [rooted('one', L()),
            listing({ type: 'library', name: 'one', version: '2.0.0', purl: 'pkg:npm/one@2.0.0', 'bom-ref': ONE, licenses: X2() })]],
        ['components with neither purl nor bom-ref (merge-boms drops them, no fold)', () => [
            rooted('app', undefined, [{ type: 'library', name: 'nokey', version: '1', licenses: L() }]),
            rooted('three', undefined, [{ type: 'library', name: 'nokey', version: '1', licenses: X2() }])]],
        ['a listing without a licenses array', () => [rooted('one', L()), listing({ type: 'library', name: 'one', version: '1.0.0', purl: ONE, 'bom-ref': ONE })]]
    ];
    for (const [label, inputs] of QUIET) {
        it(`returns 0 and logs nothing for ${label}`, () => {
            expect(run(inputs())).toBe(0);

            expect(info).not.toHaveBeenCalled();
        });
    }
});
