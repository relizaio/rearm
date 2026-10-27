import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { logger } from '../../src/logger';
import {
    foldReleaseRoots,
    listedReleaseInputs,
    FOLDED_ROOT_PROPERTY,
    RELEASE_ROOTS_FOLDED_PROPERTY,
    DROPPED_LICENSE_PROPERTY
} from '../../src/services/bom/bomProcessingService';
import { SCE_ROOT, DELIVERABLE_ROOT } from '../releaseNodeFacts.fixture';
import { BomStructure } from '../../src/types';

/**
 * SCORE-57 (T-1, T-2, T-3, T-19; ADR-2 to ADR-5): the uploaded roots of the merging release's own
 * RELEASE-attached artifacts, as rearm-saas lists them, fold into the merged root. `ids` and
 * `inputs` are built as mergeBoms holds them; the merged document is what merge-boms returns under
 * FLAT, with each input root at the top level under its own bom-ref.
 */

const ROOT = 'pkg:generic/Facts%20Org/facts-api@1.0.0?tldonly=true';
/** A generator purl type kept at upload (packageurl-js lower-cases an npm namespace). */
const UP = 'pkg:npm/facts%20org/facts-api@1.0.0?belongsto=RELEASE';
/** No generator purl: the upload's root is built from the options. */
const UP2 = 'pkg:generic/Facts%20Org/facts-api@1.0.0?belongsto=RELEASE';
const IDENTIFIER = 'pkg:generic/Facts%20Org/facts-api@1.0.0';
const LIB_A = 'pkg:npm/lib-a@1.0.0';
const LIB_B = 'pkg:npm/lib-b@1.0.0';
const OPTIONS = { name: 'facts-api', version: '1.0.0', group: 'Facts Org' };
const SHA_A = 'a'.repeat(64);
const SHA_B = 'b'.repeat(64);

/** An input document whose root is the upload's root, with ReARM's identity fields. */
function input(purl: string | undefined, root: any = {}, components: any[] = []): any {
    const component: any = { type: 'library', name: 'facts-api', version: '1.0.0', group: 'Facts Org', ...root };
    if (purl !== undefined) {
        component.purl = purl;
        component['bom-ref'] = purl;
    }
    return { bomFormat: 'CycloneDX', specVersion: '1.6', metadata: { component }, components };
}

/** The node merge-boms places for an input root: the root as it was, at the top level. */
function nodeOf(doc: any): any {
    return { ...doc.metadata.component };
}

/** The merged document: ReARM's root (identity only), the given nodes, then lib-a; FLATTEN-shaped dependencies. */
function merged(nodes: any[], extra: any = {}): any {
    return {
        bomFormat: 'CycloneDX',
        specVersion: '1.6',
        metadata: {
            timestamp: '2026-10-22T00:00:00Z',
            component: { type: 'application', name: 'facts-api', group: 'Facts Org', version: '1.0.0', purl: ROOT, 'bom-ref': ROOT }
        },
        components: [...nodes, { type: 'library', name: 'lib-a', version: '1.0.0', purl: LIB_A, 'bom-ref': LIB_A }],
        dependencies: [{ ref: ROOT, dependsOn: [LIB_A] }, { ref: LIB_A, dependsOn: [] }],
        ...extra
    };
}

function fold(bom: any, ids: string[], inputs: any[], releaseRootIds: string[] | null | undefined, options: any = OPTIONS) {
    return foldReleaseRoots(bom, listedReleaseInputs(ids, inputs, releaseRootIds), options);
}

function refs(bom: any): string[] {
    return bom.components.map((c: any) => c['bom-ref']);
}

let info: ReturnType<typeof vi.spyOn>;
let warn: ReturnType<typeof vi.spyOn>;
beforeEach(() => {
    info = vi.spyOn(logger, 'info').mockImplementation(() => undefined as any);
    warn = vi.spyOn(logger, 'warn').mockImplementation(() => undefined as any);
});
afterEach(() => vi.restoreAllMocks());

/** The messages of the info lines logged, object-first or string-only. */
function infoLines(): string[] {
    return info.mock.calls.map((c: any[]) => (typeof c[0] === 'string' ? c[0] : c[1]));
}

describe('foldReleaseRoots: which listed roots fold (T-1, ADR-2)', () => {
    it('folds a listed root with a generator purl, the qualifier and the options identity', () => {
        const up = input(UP, { description: 'D' });
        const bom = merged([nodeOf(up)]);

        const { bom: out, count } = fold(bom, ['sce', 'rel'], [input(SCE_ROOT), up], ['rel']);

        expect(count).toBe(1);
        expect(refs(out)).toStrictEqual([LIB_A]);
        expect(out.metadata.component.description).toBe('D');
        expect(out.metadata.component.properties).toStrictEqual([{ name: FOLDED_ROOT_PROPERTY, value: UP }]);
    });

    it('folds a listed root built without a generator purl', () => {
        const up2 = input(UP2);
        const { bom: out, count } = fold(merged([nodeOf(up2)]), ['rel'], [up2], ['rel']);

        expect(count).toBe(1);
        expect(refs(out)).toStrictEqual([LIB_A]);
        expect(out.metadata.component.properties).toStrictEqual([{ name: FOLDED_ROOT_PROPERTY, value: UP2 }]);
    });

    it('reads the qualifier key and value without regard to case', () => {
        const purl = 'pkg:npm/facts%20org/facts-api@1.0.0?belongsTo=release';
        const doc = input(purl);
        const { count, bom: out } = fold(merged([nodeOf(doc)]), ['rel'], [doc], ['rel']);

        expect(count).toBe(1);
        expect(refs(out)).toStrictEqual([LIB_A]);
    });

    it('never folds an unlisted root, whatever its purl and identity, and says nothing about it (the F-1 shape)', () => {
        const listed = input(SCE_ROOT);
        const unlisted = input(UP2, { supplier: { name: 'Other' } });
        const bom = merged([nodeOf(unlisted)]);

        const result = fold(bom, ['sce', 'other'], [listed, unlisted], ['sce']);

        expect(result.bom).toBe(bom);
        expect(result.count).toBe(0);
        expect(infoLines().some(l => l.includes(UP2))).toBe(false);
        expect(info.mock.calls.some((c: any[]) => JSON.stringify(c).includes(UP2))).toBe(false);
    });

    it.each([
        ['absent', undefined],
        ['null', null],
        ['empty', []]
    ])('folds nothing when the list is %s', (_label, list) => {
        const up = input(UP);
        const bom = merged([nodeOf(up)]);

        const result = fold(bom, ['rel'], [up], list as any);

        expect(result.bom).toBe(bom);
        expect(result.count).toBe(0);
        expect(info).not.toHaveBeenCalled();
    });

    it.each([
        ['DELIVERABLE', DELIVERABLE_ROOT],
        ['SCE', SCE_ROOT]
    ])('does not fold a listed %s root, and logs one line naming the kind', (kind, purl) => {
        const doc = input(purl);
        const bom = merged([nodeOf(doc)]);

        const result = fold(bom, ['x'], [doc], ['x']);

        expect(result.bom).toBe(bom);
        expect(result.count).toBe(0);
        expect(info).toHaveBeenCalledTimes(1);
        expect(info.mock.calls[0][1]).toContain('attached to another kind');
        expect(info.mock.calls[0][0]).toMatchObject({ belongsTo: kind });
    });

    it.each([
        ['name', { name: 'facts-other' }, ['facts-other', 'facts-api']],
        ['version', { version: '1.0.1' }, ['1.0.1', '1.0.0']],
        ['group', { group: 'facts org' }, ['facts org', 'Facts Org']]
    ])('does not fold a listed RELEASE root whose %s differs (no trimming or case folding), one line naming the pair', (field, root, pair) => {
        const doc = input(UP2, root);
        const bom = merged([nodeOf(doc)]);

        const result = fold(bom, ['rel'], [doc], ['rel']);

        expect(result.bom).toBe(bom);
        expect(info).toHaveBeenCalledTimes(1);
        expect(info.mock.calls[0][1]).toContain('release root listed but its identity fields differ');
        expect(info.mock.calls[0][0][field]).toStrictEqual(pair);
    });

    it('does not trim the identity fields', () => {
        const doc = input(UP2, { name: ' facts-api' });
        const bom = merged([nodeOf(doc)]);

        expect(fold(bom, ['rel'], [doc], ['rel']).bom).toBe(bom);
    });

    it('leaves a listed root whose purl is the identifier (no qualifier) to the bom-ref de-dup, without a line', () => {
        const doc = input(IDENTIFIER);
        const bom = merged([nodeOf(doc)]);

        const result = fold(bom, ['rel'], [doc], ['rel']);

        expect(result.bom).toBe(bom);
        expect(info).not.toHaveBeenCalled();
    });

    it.each([
        ['does not parse', 'pkg:', 'does not parse'],
        ['is absent', undefined, 'has no purl']
    ])('skips a listed root whose purl %s, without a throw, with one line', (_label, purl, reason) => {
        const doc = input(purl as any);
        const bom = merged([]);

        const result = fold(bom, ['rel'], [doc], ['rel']);

        expect(result.bom).toBe(bom);
        expect(info).toHaveBeenCalledTimes(1);
        expect(infoLines()[0]).toContain(reason);
    });

    it('skips a listed input without a metadata.component, with one line', () => {
        const bom = merged([]);
        const result = fold(bom, ['rel'], [{ bomFormat: 'CycloneDX', components: [] }], ['rel']);

        expect(result.bom).toBe(bom);
        expect(infoLines()[0]).toContain('no metadata.component');
    });

    it('does not fold a belongsto=RELEASE component that is a listing inside a listed input, not its root', () => {
        const listing = { type: 'library', name: 'facts-api', version: '1.0.0', group: 'Facts Org', purl: UP2, 'bom-ref': UP2 };
        const doc = input('pkg:npm/web-app@2.0.0', { name: 'web-app', version: '2.0.0' }, [listing]);
        const bom = merged([{ ...listing }]);

        const result = fold(bom, ['rel'], [doc], ['rel']);

        expect(result.bom).toBe(bom);
        expect(refs(result.bom)).toContain(UP2);
    });

    it('does not fold a node that sits nested, not at the top level, and logs one line', () => {
        const up = input(UP);
        const bom = merged([]);
        bom.components[0].components = [nodeOf(up)];

        const result = fold(bom, ['rel'], [up], ['rel']);

        expect(result.bom).toBe(bom);
        expect(result.count).toBe(0);
        expect(infoLines()[0]).toContain('no top-level component carries its bom-ref');
    });

    it('logs one line, counts 0 and returns the same object when no top-level node carries the bom-ref', () => {
        const up = input(UP);
        const bom = merged([]);

        const result = fold(bom, ['rel'], [up], ['rel']);

        expect(result.bom).toBe(bom);
        expect(result.count).toBe(0);
        expect(info).toHaveBeenCalledTimes(1);
        expect(bom.metadata).not.toHaveProperty('properties');
    });

    it('folds once when two listed inputs share one root bom-ref (the FLAT survivor shape), and says the second was folded already', () => {
        const first = input(UP2, { description: 'first' });
        const second = input(UP2, { description: 'second' });
        const bom = merged([nodeOf(second)]);

        const { bom: out, count } = fold(bom, ['r1', 'r2'], [first, second], ['r1', 'r2']);

        expect(count).toBe(1);
        expect(out.metadata.component.properties).toStrictEqual([{ name: FOLDED_ROOT_PROPERTY, value: UP2 }]);
        expect(out.metadata.properties).toStrictEqual([{ name: RELEASE_ROOTS_FOLDED_PROPERTY, value: '1' }]);
        expect(infoLines().some(l => l.includes('its bom-ref was folded already'))).toBe(true);
        expect(warn).not.toHaveBeenCalled();
    });
});

describe('foldReleaseRoots: the fold (T-2, ADR-3, ADR-4, ADR-5)', () => {
    const CHILD = { type: 'library', name: 'child', version: '1.0.0', purl: 'pkg:npm/child@1.0.0', 'bom-ref': 'pkg:npm/child@1.0.0' };
    const P = { name: 'build:tool', value: 'x' };
    const WEBSITE = { type: 'website', url: 'https://facts.example' };

    function upNode(): any {
        return {
            type: 'library', name: 'facts-api', version: '1.0.0', group: 'Facts Org', purl: UP, 'bom-ref': UP,
            supplier: { name: 'S' }, description: 'D',
            licenses: [{ expression: 'BSD-3-Clause OR MIT' }],
            hashes: [{ alg: 'SHA-256', content: SHA_A }],
            externalReferences: [WEBSITE],
            properties: [P],
            pedigree: { commits: [{ uid: 'c' }] },
            components: [CHILD]
        };
    }

    it('gives the root every field of the upload under its own identity, hoists the children and records the fold (FLATTEN)', () => {
        const up = input(UP);
        up.metadata.component = upNode();
        const bom = merged([upNode()]);
        const before = JSON.stringify(bom);
        const inputBefore = JSON.stringify(up);

        const { bom: out, count } = fold(bom, ['rel'], [up], ['rel']);

        expect(count).toBe(1);
        expect(out.metadata.component).toStrictEqual({
            type: 'application', name: 'facts-api', group: 'Facts Org', version: '1.0.0', purl: ROOT, 'bom-ref': ROOT,
            supplier: { name: 'S' }, description: 'D',
            licenses: [{ expression: 'BSD-3-Clause OR MIT' }],
            hashes: [{ alg: 'SHA-256', content: SHA_A }],
            externalReferences: [WEBSITE],
            properties: [P, { name: FOLDED_ROOT_PROPERTY, value: UP }],
            pedigree: { commits: [{ uid: 'c' }] },
            components: [CHILD]
        });
        expect(refs(out)).toStrictEqual([LIB_A]);
        // FLATTEN dropped the input root's entry: nothing names UP, nothing to change
        expect(out.dependencies).toBe(bom.dependencies);
        expect(out.metadata.properties).toStrictEqual([{ name: RELEASE_ROOTS_FOLDED_PROPERTY, value: '1' }]);
        expect(JSON.stringify(bom)).toBe(before);
        expect(JSON.stringify(up)).toBe(inputBefore);
    });

    it('re-points PRESERVE-shaped dependencies: the upload entry folds into the root entry, root first, with no self-reference', () => {
        const up = input(UP);
        const bom = merged([nodeOf(up)], {
            dependencies: [
                { ref: ROOT, dependsOn: [UP, LIB_B] },
                { ref: UP, dependsOn: [LIB_A, LIB_B], provides: [LIB_A] },
                { ref: LIB_A, dependsOn: [UP] },
                { ref: LIB_B, dependsOn: [] }
            ]
        });

        const { bom: out } = fold(bom, ['rel'], [up], ['rel']);

        expect(out.dependencies).toStrictEqual([
            { ref: ROOT, dependsOn: [LIB_B, LIB_A], provides: [LIB_A] },
            { ref: LIB_A, dependsOn: [ROOT] },
            { ref: LIB_B, dependsOn: [] }
        ]);
        // an entry with nothing to change is the same object
        expect(out.dependencies[2]).toBe(bom.dependencies[3]);
        expect(JSON.stringify(out.dependencies)).not.toContain(UP);
    });

    it('creates the root entry where the upload entry stood when the root has none', () => {
        const up = input(UP);
        const bom = merged([nodeOf(up)], {
            dependencies: [{ ref: LIB_A, dependsOn: [] }, { ref: UP, dependsOn: [LIB_A] }]
        });

        const { bom: out } = fold(bom, ['rel'], [up], ['rel']);

        expect(out.dependencies).toStrictEqual([{ ref: LIB_A, dependsOn: [] }, { ref: ROOT, dependsOn: [LIB_A] }]);
    });

    it('re-points compositions, vulnerability affects and annotation subjects, folding repeats; lists naming nothing stay the same objects', () => {
        const up = input(UP);
        const untouchedComposition = { aggregate: 'complete', assemblies: [LIB_A] };
        const untouchedVulnerability = { id: 'CVE-2', affects: [{ ref: LIB_A }] };
        const untouchedAnnotation = { subjects: [LIB_A], text: 't2' };
        const bom = merged([nodeOf(up)], {
            compositions: [{ aggregate: 'incomplete', assemblies: [ROOT, UP, LIB_A], dependencies: [UP] }, untouchedComposition],
            vulnerabilities: [
                { id: 'CVE-1', affects: [{ ref: ROOT, versions: [{ version: '1.0.0', status: 'affected' }] }, { ref: UP, versions: [{ version: '1.0.1', status: 'affected' }] }] },
                untouchedVulnerability
            ],
            annotations: [{ subjects: [UP, ROOT], text: 't1' }, untouchedAnnotation]
        });

        const { bom: out } = fold(bom, ['rel'], [up], ['rel']);

        expect(out.compositions).toStrictEqual([
            { aggregate: 'incomplete', assemblies: [ROOT, LIB_A], dependencies: [ROOT] },
            untouchedComposition
        ]);
        expect(out.compositions[1]).toBe(untouchedComposition);
        expect(out.vulnerabilities[0].affects).toStrictEqual([
            { ref: ROOT, versions: [{ version: '1.0.0', status: 'affected' }, { version: '1.0.1', status: 'affected' }] }
        ]);
        expect(out.vulnerabilities[1]).toBe(untouchedVulnerability);
        expect(out.annotations).toStrictEqual([{ subjects: [ROOT], text: 't1' }, untouchedAnnotation]);
        expect(out.annotations[1]).toBe(untouchedAnnotation);
    });

    it('returns each reference list as the same object when none names the folded ref', () => {
        const up = input(UP);
        const compositions = [{ aggregate: 'complete', assemblies: [LIB_A] }];
        const vulnerabilities = [{ id: 'CVE-1', affects: [{ ref: LIB_A }] }];
        const annotations = [{ subjects: [LIB_A], text: 't' }];
        const bom = merged([nodeOf(up)], { compositions, vulnerabilities, annotations });

        const { bom: out } = fold(bom, ['rel'], [up], ['rel']);

        expect(out.compositions).toBe(compositions);
        expect(out.vulnerabilities).toBe(vulnerabilities);
        expect(out.annotations).toBe(annotations);
    });

    it('keeps the root supplier, expression and hashes, records the dropped expression and appends a hash of another algorithm', () => {
        const up = input(UP);
        const node = { ...nodeOf(up), supplier: { name: 'S' }, licenses: [{ expression: 'BSD-3-Clause OR MIT' }], hashes: [{ alg: 'SHA-512', content: 'c'.repeat(128) }, { alg: 'SHA-256', content: SHA_B }] };
        const bom = merged([node]);
        bom.metadata.component = { ...bom.metadata.component, supplier: { name: 'Root Co' }, licenses: [{ expression: 'Apache-2.0 OR MIT' }], hashes: [{ alg: 'SHA-256', content: SHA_A }] };

        const { bom: out } = fold(bom, ['rel'], [up], ['rel']);

        const root = out.metadata.component;
        expect(root.supplier).toStrictEqual({ name: 'Root Co' });
        expect(root.licenses).toStrictEqual([{ expression: 'Apache-2.0 OR MIT' }]);
        expect(root.hashes).toStrictEqual([{ alg: 'SHA-256', content: SHA_A }, { alg: 'SHA-512', content: 'c'.repeat(128) }]);
        expect(root.properties).toStrictEqual([
            { name: DROPPED_LICENSE_PROPERTY, value: 'BSD-3-Clause OR MIT' },
            { name: FOLDED_ROOT_PROPERTY, value: UP }
        ]);
    });

    it('logs one line per fold and one summary line', () => {
        const up = input(UP);
        fold(merged([nodeOf(up)]), ['rel'], [up], ['rel']);

        expect(info.mock.calls.some((c: any[]) => c[1] === 'release root folded into the merged root' && c[0].folded === UP && c[0].into === ROOT)).toBe(true);
        expect(infoLines().some(l => l.includes('1 uploaded roots that describe the release folded'))).toBe(true);
    });
});

describe('foldReleaseRoots: order and several roots (T-3)', () => {
    function pair() {
        const up = input(UP);
        const up2 = input(UP2);
        const upNode = { ...nodeOf(up), supplier: { name: 'S1' }, description: 'D1', licenses: [{ license: { id: 'MIT' } }], hashes: [{ alg: 'SHA-256', content: SHA_A }] };
        const up2Node = { ...nodeOf(up2), supplier: { name: 'S2' }, description: 'D2', licenses: [{ license: { id: 'Apache-2.0' } }], hashes: [{ alg: 'SHA-256', content: SHA_B }, { alg: 'SHA-512', content: 'c'.repeat(128) }] };
        return { up, up2, upNode, up2Node };
    }

    it('lets the first listed input in input order set the scalars, the second fill what is empty, and records both', () => {
        const { up, up2, upNode, up2Node } = pair();
        const bom = merged([up2Node, upNode]);

        const { bom: out, count } = fold(bom, ['a', 'b'], [up, up2], ['b', 'a']);

        expect(count).toBe(2);
        const root = out.metadata.component;
        expect(root.supplier).toStrictEqual({ name: 'S1' });
        expect(root.description).toBe('D1');
        expect(root.licenses).toStrictEqual([{ license: { id: 'MIT' } }, { license: { id: 'Apache-2.0' } }]);
        expect(root.hashes).toStrictEqual([{ alg: 'SHA-256', content: SHA_A }, { alg: 'SHA-512', content: 'c'.repeat(128) }]);
        expect(root.properties).toStrictEqual([{ name: FOLDED_ROOT_PROPERTY, value: UP }, { name: FOLDED_ROOT_PROPERTY, value: UP2 }]);
        expect(refs(out)).toStrictEqual([LIB_A]);
        expect(out.metadata.properties).toStrictEqual([{ name: RELEASE_ROOTS_FOLDED_PROPERTY, value: '2' }]);
    });

    it('takes the scalars from UP2 when it comes first in input order', () => {
        const { up, up2, upNode, up2Node } = pair();

        const { bom: out } = fold(merged([upNode, up2Node]), ['b', 'a'], [up2, up], ['a', 'b']);

        expect(out.metadata.component.supplier).toStrictEqual({ name: 'S2' });
        expect(out.metadata.component.description).toBe('D2');
        expect(out.metadata.component.properties.map((p: any) => p.value)).toStrictEqual([UP2, UP]);
    });

    /** Two listed nodes, UP2 with [c3] then UP with [c1, c2], ahead of lib-a; the root holds [own]. */
    function nested() {
        const { up, up2 } = pair();
        const c1 = { type: 'library', name: 'c1', 'bom-ref': 'c1' };
        const c2 = { type: 'library', name: 'c2', 'bom-ref': 'c2' };
        const c3 = { type: 'library', name: 'c3', 'bom-ref': 'c3' };
        const own = { type: 'library', name: 'own', 'bom-ref': 'own' };
        const bom = merged([{ ...nodeOf(up2), components: [c3] }, { ...nodeOf(up), components: [c1, c2] }]);
        bom.metadata.component = { ...bom.metadata.component, components: [own] };
        return { up, up2, c1, c2, c3, own, bom };
    }

    // SCORE-62 ADR-8, T-3c: under HIERARCHICAL a folded node's children are the upload's top-level
    // components, so they take the node's place at the top level.
    it('puts the children of a HIERARCHICAL merge at the top level, each folded node replaced in its place', () => {
        const { up, up2, c1, c2, c3, own, bom } = nested();

        const { bom: out, count } = fold(bom, ['a', 'b'], [up, up2], ['a', 'b'], { ...OPTIONS, structure: BomStructure.HIERARCHICAL });

        expect(count).toBe(2);
        expect(out.components.slice(0, 3)).toStrictEqual([c3, c1, c2]);
        expect(refs(out)).toStrictEqual(['c3', 'c1', 'c2', LIB_A]);
        expect(out.metadata.component.components).toStrictEqual([own]);
    });

    it('reads the structure without regard to case', () => {
        const { up, up2, own, bom } = nested();

        const { bom: out } = fold(bom, ['a', 'b'], [up, up2], ['a', 'b'], { ...OPTIONS, structure: 'hierarchical' });

        expect(refs(out)).toStrictEqual(['c3', 'c1', 'c2', LIB_A]);
        expect(out.metadata.component.components).toStrictEqual([own]);
    });

    it('removes a node listed under HIERARCHICAL that has no children and adds nothing in its place', () => {
        const { up } = pair();
        const bom = merged([nodeOf(up)]);

        const { bom: out, count } = fold(bom, ['a'], [up], ['a'], { ...OPTIONS, structure: BomStructure.HIERARCHICAL });

        expect(count).toBe(1);
        expect(refs(out)).toStrictEqual([LIB_A]);
        expect('components' in out.metadata.component).toBe(false);
    });

    it('hoists the children of a FLAT merge (no structure) after the root own, in input order', () => {
        const { up, up2, c1, c2, c3, own, bom } = nested();

        const { bom: out } = fold(bom, ['a', 'b'], [up, up2], ['a', 'b']);

        expect(out.metadata.component.components).toStrictEqual([own, c1, c2, c3]);
        expect(refs(out)).toStrictEqual([LIB_A]);
    });

    it('hoists the children of an explicit FLAT merge after the root own', () => {
        const { up, up2, c1, c2, c3, own, bom } = nested();

        const { bom: out } = fold(bom, ['a', 'b'], [up, up2], ['a', 'b'], { ...OPTIONS, structure: BomStructure.FLAT });

        expect(out.metadata.component.components).toStrictEqual([own, c1, c2, c3]);
        expect(refs(out)).toStrictEqual([LIB_A]);
    });

    it('replaces a releaseRootsFolded value already present rather than adding a second', () => {
        const { up } = pair();
        const bom = merged([nodeOf(up)]);
        bom.metadata.properties = [{ name: RELEASE_ROOTS_FOLDED_PROPERTY, value: '5' }, { name: 'keep', value: 'x' }];

        const { bom: out } = fold(bom, ['a'], [up], ['a']);

        expect(out.metadata.properties).toStrictEqual([{ name: 'keep', value: 'x' }, { name: RELEASE_ROOTS_FOLDED_PROPERTY, value: '1' }]);
    });
});

describe('listedReleaseInputs (T-19, ADR-2 (c))', () => {
    const A = { n: 'A' };
    const B = { n: 'B' };
    const C = { n: 'C' };
    const PLACEHOLDER = { n: 'P' };
    const ids = ['a', 'b', 'c'];
    const inputs = [A, B, C, PLACEHOLDER];

    it('returns the listed inputs in input order, not list order', () => {
        const out = listedReleaseInputs(ids, inputs, ['c', 'a']);
        expect(out).toHaveLength(2);
        expect(out[0]).toBe(A);
        expect(out[1]).toBe(C);
    });

    it('returns an input once however often it is listed', () => {
        expect(listedReleaseInputs(ids, inputs, ['a', 'a'])).toStrictEqual([A]);
    });

    it('logs one warn line for a listed id that is not an input, and returns the rest', () => {
        const out = listedReleaseInputs(ids, inputs, ['b', 'x']);
        expect(out).toStrictEqual([B]);
        expect(warn).toHaveBeenCalledTimes(1);
        expect(warn.mock.calls[0][0]).toStrictEqual({ releaseRootId: 'x' });
        expect(warn.mock.calls[0][1]).toBe('release root listed but not an input');
    });

    it.each([[[]], [null], [undefined]])('returns none, without a line, for %s', (list) => {
        expect(listedReleaseInputs(ids, inputs, list as any)).toStrictEqual([]);
        expect(warn).not.toHaveBeenCalled();
        expect(info).not.toHaveBeenCalled();
    });

    it('never returns a placeholder beyond ids', () => {
        const out = listedReleaseInputs(ids, inputs, ['a', 'b', 'c']);
        expect(out).toStrictEqual([A, B, C]);
        expect(out).not.toContain(PLACEHOLDER);
    });

    it('compares the ids as strings exactly', () => {
        expect(listedReleaseInputs(ids, inputs, ['A'])).toStrictEqual([]);
    });

    it('names the list in the warn line by its label, and returns the same documents for either label (SCORE-63 T-3)', () => {
        const plain = listedReleaseInputs(ids, inputs, ['b', 'x']);
        const labelled = listedReleaseInputs(ids, inputs, ['b', 'x'], 'attached root');
        expect(warn).toHaveBeenCalledTimes(2);
        expect(warn.mock.calls[0][1]).toBe('release root listed but not an input');
        expect(warn.mock.calls[1][1]).toBe('attached root listed but not an input');
        expect(warn.mock.calls[1][0]).toStrictEqual({ releaseRootId: 'x' });
        expect(labelled).toStrictEqual(plain);
        expect(labelled[0]).toBe(B);
    });

    it('returns a document at each position its id holds in ids', () => {
        const twice = ['a', 'b', 'a'];
        const docs = [A, B, { n: 'A again' }];
        expect(listedReleaseInputs(twice, docs, ['a'])).toStrictEqual([A, { n: 'A again' }]);
    });
});
