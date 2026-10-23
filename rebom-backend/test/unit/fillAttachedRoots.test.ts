import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { logger } from '../../src/logger';
import { fillAttachedRoots, listedReleaseInputs } from '../../src/services/bom/bomProcessingService';
import {
    SCE_ROOT,
    DELIVERABLE_ROOT,
    COMMIT,
    UPLOAD_DIGEST,
    ORG,
    COMPONENT_LICENSE,
    COMPONENT_LICENSE_URL,
    node
} from '../releaseNodeFacts.fixture';

/**
 * SCORE-63 T-1 (ADR-2, ADR-3): the roots of the merging release's own SCE- and deliverable-attached
 * uploads, as rearm-saas lists them in attachedRootIds, get the release's declared licence where the
 * upload wrote none. `ids` and `inputs` are built as mergeBoms holds them; the merged document is
 * what merge-boms returns under FLAT, with each input root at the top level under its own bom-ref.
 */

const ROOT = 'pkg:generic/Facts%20Org/facts-api@1.0.0?tldonly=true';
const RELEASE_UP = 'pkg:npm/facts%20org/facts-api@1.0.0?belongsto=RELEASE';
/** A deliverable identifier rearm-saas sent as the purl: no belongsto qualifier. */
const DELIVERABLE_ID = 'pkg:oci/api-image@1.0.0';
/** A component inside an upload's components[] that imitates an attached root's purl. */
const LISTING = `pkg:npm/facts%20org/api@1.0.0?belongsto=SCE&hash=${COMMIT}`;
const LIB_A = 'pkg:npm/lib-a@1.0.0';
const OPTIONS = { name: 'facts-api', version: '1.0.0', group: ORG };
const NAMED = [{ license: { name: COMPONENT_LICENSE, url: COMPONENT_LICENSE_URL } }];
const FACTS = {
    supplierName: ORG,
    hashes: [{ alg: 'SHA-256', content: 'd'.repeat(64) }],
    licenseExpression: COMPONENT_LICENSE,
    licenseUrl: COMPONENT_LICENSE_URL
};

/** An input document whose root is the upload's root, with ReARM's identity fields. */
function input(purl: string | undefined, root: any = {}, components: any[] = []): any {
    const component: any = { type: 'library', name: 'facts-api', version: '1.0.0', group: ORG, ...root };
    if (purl !== undefined) {
        component.purl = purl;
        if (!('bom-ref' in root)) component['bom-ref'] = purl;
    }
    return { bomFormat: 'CycloneDX', specVersion: '1.6', metadata: { component }, components };
}

function sceInput(root: any = {}, components: any[] = []): any {
    return input(SCE_ROOT, {
        supplier: { name: ORG },
        pedigree: { commits: [{ uid: COMMIT }] },
        properties: [{ name: 'p', value: 'v' }],
        ...root
    }, components);
}

function deliverableInput(purl: string = DELIVERABLE_ROOT, root: any = {}): any {
    return input(purl, { supplier: { name: ORG }, hashes: [{ alg: 'SHA-256', content: UPLOAD_DIGEST }], ...root });
}

/** The node merge-boms places for an input root: the root as it was, at the top level. */
function nodeOf(doc: any): any {
    return { ...doc.metadata.component };
}

/** The merged document: ReARM's root, the given nodes, then lib-a; FLATTEN-shaped references. */
function merged(nodes: any[], root: any = {}): any {
    const rootRef = root['bom-ref'] ?? ROOT;
    return {
        bomFormat: 'CycloneDX',
        specVersion: '1.6',
        metadata: {
            timestamp: '2026-10-23T00:00:00Z',
            component: { type: 'application', name: 'facts-api', group: ORG, version: '1.0.0', purl: ROOT, 'bom-ref': ROOT, ...root }
        },
        components: [...nodes, { type: 'library', name: 'lib-a', version: '1.0.0', purl: LIB_A, 'bom-ref': LIB_A }],
        dependencies: [{ ref: rootRef, dependsOn: [...nodes.map((n: any) => n['bom-ref']), LIB_A] }, { ref: LIB_A, dependsOn: [] }],
        compositions: [{ aggregate: 'incomplete', assemblies: [rootRef] }],
        vulnerabilities: [{ id: 'CVE-1', affects: [{ ref: LIB_A }] }],
        annotations: [{ subjects: [LIB_A], text: 't' }]
    };
}

function fill(bom: any, ids: string[], inputs: any[], attachedRootIds: string[] | null | undefined, facts: any = FACTS, options: any = OPTIONS) {
    return fillAttachedRoots(bom, listedReleaseInputs(ids, inputs, attachedRootIds, 'attached root'), facts, options);
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

function skipLines(): string[] {
    return infoLines().filter((l) => l.startsWith('attached root fill: listed input skipped') || l === 'attached root keeps its own licence');
}

describe('fillAttachedRoots: the fill (T-1, ADR-3)', () => {
    it('fills a listed SCE root without licenses with the declared licence, every other field kept', () => {
        const sce = sceInput();
        const bom = merged([nodeOf(sce)]);
        const before = structuredClone(sce);

        const { bom: out, count } = fill(bom, ['sce'], [sce], ['sce']);

        expect(count).toBe(1);
        const filled = node(out, SCE_ROOT);
        expect(filled.licenses).toStrictEqual(NAMED);
        const { licenses, ...rest } = filled;
        expect(rest).toStrictEqual(bom.components[0]);
        expect(filled.pedigree).toBe(bom.components[0].pedigree);
        expect(filled.supplier).toBe(bom.components[0].supplier);
        expect(filled.properties).toBe(bom.components[0].properties);
        expect(filled.hashes).toBeUndefined();
        expect(out.components[1]).toBe(bom.components[1]);
        expect(sce).toStrictEqual(before);
        expect(bom.components[0].licenses).toBeUndefined();
        expect(infoLines()).toContain('declared licence filled on an attached root');
        expect(info.mock.calls.find((c: any[]) => c[1] === 'declared licence filled on an attached root')?.[0])
            .toStrictEqual({ bomRef: SCE_ROOT, belongsTo: 'SCE' });
    });

    it('fills a listed deliverable root and keeps its hashes', () => {
        const del = deliverableInput();
        const bom = merged([nodeOf(del)]);

        const { bom: out, count } = fill(bom, ['del'], [del], ['del']);

        expect(count).toBe(1);
        const filled = node(out, DELIVERABLE_ROOT);
        expect(filled.licenses).toStrictEqual(NAMED);
        expect(filled.hashes).toBe(bom.components[0].hashes);
        expect(filled.hashes).toStrictEqual([{ alg: 'SHA-256', content: UPLOAD_DIGEST }]);
    });

    it('fills a listed root whose purl is a deliverable identifier with no belongsto qualifier', () => {
        const del = deliverableInput(DELIVERABLE_ID);
        const { bom: out, count } = fill(merged([nodeOf(del)]), ['del'], [del], ['del']);

        expect(count).toBe(1);
        expect(node(out, DELIVERABLE_ID).licenses).toStrictEqual(NAMED);
    });

    it('reads the qualifier key and value case-insensitively (belongsTo=sce)', () => {
        const purl = `pkg:npm/facts%20org/facts-api@1.0.0?belongsTo=sce&hash=${COMMIT}`;
        const sce = input(purl);
        const { bom: out, count } = fill(merged([nodeOf(sce)]), ['sce'], [sce], ['sce']);

        expect(count).toBe(1);
        expect(node(out, purl).licenses).toStrictEqual(NAMED);
    });

    it('fills a listed root whose licenses is an empty list', () => {
        const sce = sceInput({ licenses: [] });
        const { bom: out, count } = fill(merged([nodeOf(sce)]), ['sce'], [sce], ['sce']);

        expect(count).toBe(1);
        expect(node(out, SCE_ROOT).licenses).toStrictEqual(NAMED);
    });

    it('writes a single list id in the id form', () => {
        const sce = sceInput();
        const { bom: out } = fill(merged([nodeOf(sce)]), ['sce'], [sce], ['sce'], { licenseExpression: 'MIT' });

        expect(node(out, SCE_ROOT).licenses).toStrictEqual([{ license: { id: 'MIT' } }]);
    });

    it('writes a compound in the expression form', () => {
        const sce = sceInput();
        const { bom: out } = fill(merged([nodeOf(sce)]), ['sce'], [sce], ['sce'], { licenseExpression: 'MIT OR Apache-2.0' });

        expect(node(out, SCE_ROOT).licenses).toStrictEqual([{ expression: 'MIT OR Apache-2.0' }]);
    });

    it('fills two listed roots, each with its own licenses array', () => {
        const sce = sceInput();
        const del = deliverableInput();
        const inputs = [sce, del];
        const copies = structuredClone(inputs);

        const { bom: out, count } = fill(merged([nodeOf(sce), nodeOf(del)]), ['sce', 'del'], inputs, ['sce', 'del']);

        expect(count).toBe(2);
        expect(node(out, SCE_ROOT).licenses).toStrictEqual(NAMED);
        expect(node(out, DELIVERABLE_ROOT).licenses).toStrictEqual(NAMED);
        expect(node(out, SCE_ROOT).licenses).not.toBe(node(out, DELIVERABLE_ROOT).licenses);
        expect(inputs).toStrictEqual(copies);
        expect(infoLines()).toContain('attached root fill: the declared licence filled on 2 attached roots');
    });

    it('leaves metadata, dependencies, compositions, vulnerabilities and annotations as the same objects', () => {
        const sce = sceInput();
        const bom = merged([nodeOf(sce)]);

        const { bom: out } = fill(bom, ['sce'], [sce], ['sce']);

        expect(out).not.toBe(bom);
        expect(out.metadata).toBe(bom.metadata);
        expect(out.dependencies).toBe(bom.dependencies);
        expect(out.compositions).toBe(bom.compositions);
        expect(out.vulnerabilities).toBe(bom.vulnerabilities);
        expect(out.annotations).toBe(bom.annotations);
        expect(out.metadata.component.licenses).toBeUndefined();
    });
});

describe('fillAttachedRoots: what is not filled (T-1, ADR-2, ADR-4)', () => {
    /** Runs the pass and asserts the input comes back as the same object, no node changed. */
    function unchanged(bom: any, ids: string[], inputs: any[], list: any, facts: any = FACTS, options: any = OPTIONS) {
        const copy = structuredClone(bom);
        const result = fill(bom, ids, inputs, list, facts, options);
        expect(result.count).toBe(0);
        expect(result.bom).toBe(bom);
        expect(bom).toStrictEqual(copy);
        return result;
    }

    it('never fills an unlisted root that carries the release name, version and group', () => {
        const sce = sceInput();
        const del = deliverableInput();
        // The list names an id that is not among the inputs: neither root is a candidate, and no
        // line of this pass names them.
        unchanged(merged([nodeOf(sce), nodeOf(del)]), ['sce', 'del'], [sce, del], ['x']);
        expect(skipLines()).toStrictEqual([]);
        expect(info.mock.calls.some((c: any[]) => JSON.stringify(c).includes(SCE_ROOT))).toBe(false);
    });

    it('fills nothing for an unlisted root while filling the listed one', () => {
        const sce = sceInput();
        const del = deliverableInput();
        const { bom: out, count } = fill(merged([nodeOf(sce), nodeOf(del)]), ['sce', 'del'], [sce, del], ['del']);

        expect(count).toBe(1);
        expect(node(out, SCE_ROOT).licenses).toBeUndefined();
        expect(node(out, DELIVERABLE_ROOT).licenses).toStrictEqual(NAMED);
    });

    it.each([
        ['absent', undefined],
        ['null', null],
        ['empty', []]
    ])('fills nothing when attachedRootIds is %s', (_label, list) => {
        const sce = sceInput();
        unchanged(merged([nodeOf(sce)]), ['sce'], [sce], list);
    });

    it('fills nothing with no facts', () => {
        const sce = sceInput();
        unchanged(merged([nodeOf(sce)]), ['sce'], [sce], ['sce'], null);
    });

    it('fills nothing when the facts carry no licence (supplier and hashes only)', () => {
        const sce = sceInput();
        unchanged(merged([nodeOf(sce)]), ['sce'], [sce], ['sce'], { supplierName: ORG, hashes: FACTS.hashes });
        expect(skipLines()).toStrictEqual([]);
    });

    it.each([
        ['an id', [{ license: { id: 'BSD-3-Clause' } }]],
        ['an expression', [{ expression: 'BSD-3-Clause OR MIT' }]],
        ['NOASSERTION', [{ expression: 'NOASSERTION' }]],
        ['NONE', [{ expression: 'NONE' }]]
    ])('keeps a listed root that carries its own licence: %s', (_label, licenses) => {
        const sce = sceInput({ licenses });
        const { bom: out } = unchanged(merged([nodeOf(sce)]), ['sce'], [sce], ['sce']);
        expect(node(out, SCE_ROOT).licenses).toStrictEqual(licenses);
        expect(skipLines()).toStrictEqual(['attached root keeps its own licence']);
    });

    it('skips a listed RELEASE root with one line naming the fold', () => {
        const rel = input(RELEASE_UP);
        unchanged(merged([nodeOf(rel)]), ['rel'], [rel], ['rel']);
        expect(skipLines()).toStrictEqual(['attached root fill: listed input skipped: attached root listed but its root is attached to the release; the fold owns it']);
    });

    it('skips a listed root attached to another kind', () => {
        const purl = 'pkg:npm/facts%20org/facts-api@1.0.0?belongsto=AGENT_SESSION';
        const other = input(purl);
        unchanged(merged([nodeOf(other)]), ['o'], [other], ['o']);
        expect(skipLines()).toStrictEqual(['attached root fill: listed input skipped: the input root is attached to another kind']);
        expect(info.mock.calls.find((c: any[]) => typeof c[1] === 'string' && c[1].includes('another kind'))?.[0])
            .toStrictEqual({ purl, belongsTo: 'AGENT_SESSION' });
    });

    it.each([
        ['name', { name: 'other-api' }, 'name', ['other-api', 'facts-api']],
        ['version', { version: '1.0.1' }, 'version', ['1.0.1', '1.0.0']],
        ['group', { group: 'Other Org' }, 'group', ['Other Org', ORG]]
    ])('skips a listed SCE root with another %s, with the pair in one line', (_label, root, field, pair) => {
        const sce = sceInput(root);
        unchanged(merged([nodeOf(sce)]), ['sce'], [sce], ['sce']);
        expect(skipLines()).toStrictEqual(['attached root fill: listed input skipped: attached root listed but its identity fields differ']);
        const detail = info.mock.calls.find((c: any[]) => typeof c[1] === 'string' && c[1].includes('identity fields differ'))?.[0];
        expect(detail[field]).toStrictEqual(pair);
    });

    it('does not trim or case-fold the identity fields', () => {
        const sce = sceInput({ name: 'Facts-API ' });
        unchanged(merged([nodeOf(sce)]), ['sce'], [sce], ['sce']);
    });

    it('skips a listed root whose purl does not parse, one without a purl and one without metadata.component, without throwing', () => {
        const bad = input('pkg:', { 'bom-ref': 'bad-ref' });
        const noPurl = input(undefined, { 'bom-ref': 'no-purl' });
        const noRoot = { bomFormat: 'CycloneDX', specVersion: '1.6', components: [] };
        const bom = merged([nodeOf(bad), nodeOf(noPurl)]);
        unchanged(bom, ['bad', 'np', 'nr'], [bad, noPurl, noRoot], ['bad', 'np', 'nr']);
        expect(skipLines()).toStrictEqual([
            'attached root fill: listed input skipped: the input root purl does not parse',
            'attached root fill: listed input skipped: the input root has no purl',
            'attached root fill: listed input skipped: the input has no metadata.component'
        ]);
    });

    it('does not fill a listing inside a listed input that imitates an attached root purl', () => {
        const listing = { type: 'library', name: 'api', version: '1.0.0', purl: LISTING, 'bom-ref': LISTING };
        const sce = input(`pkg:npm/facts%20org/facts-api@1.0.0?belongsto=SCE&hash=${COMMIT}`, { licenses: [{ license: { id: 'MIT' } }] }, [listing]);
        const bom = merged([nodeOf(sce), { ...listing }]);
        const { bom: out } = unchanged(bom, ['sce'], [sce], ['sce']);
        expect(node(out, LISTING).licenses).toBeUndefined();
    });

    it('skips a listed root whose bom-ref no top-level node carries', () => {
        const sce = sceInput();
        unchanged(merged([]), ['sce'], [sce], ['sce']);
        expect(skipLines()).toStrictEqual(['attached root fill: listed input skipped: no top-level component carries its bom-ref']);
    });

    it('skips a listed root whose bom-ref is the merged root, metadata.component untouched', () => {
        const del = deliverableInput(DELIVERABLE_ID);
        const bom = merged([], { purl: DELIVERABLE_ID, 'bom-ref': DELIVERABLE_ID });
        const { bom: out } = unchanged(bom, ['del'], [del], ['del']);
        expect(out.metadata.component.licenses).toBeUndefined();
        expect(skipLines()).toStrictEqual(['attached root fill: listed input skipped: its root is the merged root']);
    });

    it('does not fill a listed root present only nested under another top-level node', () => {
        const sce = sceInput();
        const holder = { type: 'library', name: 'holder', version: '1.0.0', 'bom-ref': 'holder', components: [nodeOf(sce)] };
        const { bom: out } = unchanged(merged([holder]), ['sce'], [sce], ['sce']);
        expect(node(out, 'holder').components[0].licenses).toBeUndefined();
    });

    it('fills once when two listed inputs share one root bom-ref, the second skipped with a line', () => {
        const a = sceInput();
        const b = sceInput();
        const { bom: out, count } = fill(merged([nodeOf(a)]), ['a', 'b'], [a, b], ['a', 'b']);

        expect(count).toBe(1);
        expect(node(out, SCE_ROOT).licenses).toStrictEqual(NAMED);
        expect(skipLines()).toStrictEqual(['attached root fill: listed input skipped: its bom-ref was looked at already']);
    });

    it('logs no warn line beyond listedReleaseInputs', () => {
        const sce = sceInput();
        fill(merged([nodeOf(sce)]), ['sce'], [sce], ['sce', 'gone']);
        expect(warn).toHaveBeenCalledTimes(1);
        expect(warn.mock.calls[0][1]).toBe('attached root listed but not an input');
    });
});
