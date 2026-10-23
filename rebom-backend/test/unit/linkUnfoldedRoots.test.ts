import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { logger } from '../../src/logger';
import { linkUnfoldedRoots } from '../../src/services/bom/bomProcessingService';
import { SCE_ROOT, DELIVERABLE_ROOT } from '../releaseNodeFacts.fixture';

/**
 * SCORE-64 T-1 (ADR-2): every input root that survived the merge as a top-level node becomes a
 * dependency of the merged root, in input order, once. The merged document is what merge-boms
 * returns under FLATTEN_UNDER_NEW_ROOT: the merged root's entry unions the libraries, no entry
 * names an input root.
 */

const R = 'pkg:generic/Facts%20Org/facts-api@1.0.0';
const LIB_A = 'pkg:npm/lib-a@1.0.0';
const LIB_B = 'pkg:npm/lib-b@1.0.0';
const ORPHAN = 'pkg:npm/orphan@1.0.0';
const A = SCE_ROOT;
const B = DELIVERABLE_ROOT;

function node(ref: string, extra: any = {}): any {
    return { type: 'library', name: ref, 'bom-ref': ref, ...extra };
}

function input(ref: string | undefined, extra: any = {}): any {
    const component: any = { type: 'library', name: 'root', ...extra };
    if (ref !== undefined) component['bom-ref'] = ref;
    return { bomFormat: 'CycloneDX', specVersion: '1.6', metadata: { component }, components: [] };
}

function flattenDoc(): any {
    return {
        bomFormat: 'CycloneDX',
        specVersion: '1.6',
        metadata: { component: { type: 'application', name: 'facts-api', 'bom-ref': R } },
        components: [node(LIB_A), node(LIB_B), node(A), node(B)],
        dependencies: [
            { ref: R, dependsOn: [LIB_A, LIB_B] },
            { ref: LIB_A, dependsOn: [] },
            { ref: LIB_B, dependsOn: [] }
        ]
    };
}

const clone = (o: any) => JSON.parse(JSON.stringify(o));

describe('linkUnfoldedRoots (SCORE-64 T-1)', () => {
    let info: ReturnType<typeof vi.spyOn>;
    let debug: ReturnType<typeof vi.spyOn>;

    beforeEach(() => {
        info = vi.spyOn(logger, 'info').mockImplementation(() => undefined as any);
        debug = vi.spyOn(logger, 'debug').mockImplementation(() => undefined as any);
    });

    afterEach(() => {
        vi.restoreAllMocks();
    });

    it('(a) appends the unfolded roots to the merged root entry, in input order, sharing everything else', () => {
        const bom = flattenDoc();
        const inputs = [input(A), input(B)];
        const before = clone(bom);
        const inputsBefore = clone(inputs);

        const result = linkUnfoldedRoots(bom, inputs);

        expect(result.count).toBe(2);
        expect(result.bom).not.toBe(bom);
        expect(result.bom.dependencies[0]).toStrictEqual({ ref: R, dependsOn: [LIB_A, LIB_B, A, B] });
        expect(result.bom.dependencies[0]).not.toBe(bom.dependencies[0]);
        expect(result.bom.dependencies[1]).toBe(bom.dependencies[1]);
        expect(result.bom.dependencies[2]).toBe(bom.dependencies[2]);
        expect(result.bom.dependencies).toHaveLength(3);
        expect(result.bom.components).toBe(bom.components);
        expect(result.bom.metadata).toBe(bom.metadata);
        expect(bom).toStrictEqual(before);
        expect(inputs).toStrictEqual(inputsBefore);
    });

    it('(a) logs one info line per edge with its bom-ref and the count line once', () => {
        linkUnfoldedRoots(flattenDoc(), [input(A), input(B)]);

        expect(info).toHaveBeenCalledTimes(3);
        expect(info).toHaveBeenNthCalledWith(1, { bomRef: A }, 'unfolded input root linked under the merged root');
        expect(info).toHaveBeenNthCalledWith(2, { bomRef: B }, 'unfolded input root linked under the merged root');
        expect(info).toHaveBeenNthCalledWith(3, 'unfolded root link: 2 input roots linked under the merged root');
    });

    it('(b) returns a PRESERVE-shaped document as it is: every root is named', () => {
        const bom = flattenDoc();
        bom.dependencies = [{ ref: R, dependsOn: [A, B] }, { ref: A, dependsOn: [LIB_A] }, { ref: B, dependsOn: [LIB_B] }];

        const result = linkUnfoldedRoots(bom, [input(A), input(B)]);

        expect(result.bom).toBe(bom);
        expect(result.count).toBe(0);
        expect(info).not.toHaveBeenCalled();
    });

    it('(c) does not link a root named only in another entry\'s dependsOn', () => {
        const bom = flattenDoc();
        bom.dependencies[1] = { ref: LIB_A, dependsOn: [A] };

        const result = linkUnfoldedRoots(bom, [input(A), input(B)]);

        expect(result.count).toBe(1);
        expect(result.bom.dependencies[0].dependsOn).toStrictEqual([LIB_A, LIB_B, B]);
    });

    it('(c) does not link a root named only as the ref of its own entry', () => {
        const bom = flattenDoc();
        bom.dependencies.push({ ref: B, dependsOn: [] });

        const result = linkUnfoldedRoots(bom, [input(A), input(B)]);

        expect(result.count).toBe(1);
        expect(result.bom.dependencies[0].dependsOn).toStrictEqual([LIB_A, LIB_B, A]);
    });

    it('(c) links a root named only by an entry whose ref resolves to nothing (dropDanglingRefs removes that entry)', () => {
        const bom = flattenDoc();
        bom.dependencies.push({ ref: 'pkg:npm/not-in-components@1.0.0', dependsOn: [A] });

        const result = linkUnfoldedRoots(bom, [input(A), input(B)]);

        expect(result.count).toBe(2);
        expect(result.bom.dependencies[0].dependsOn).toStrictEqual([LIB_A, LIB_B, A, B]);
    });

    it('(c) counts an entry of a service as naming the root', () => {
        const bom = flattenDoc();
        bom.services = [{ name: 'svc', 'bom-ref': 'svc-1' }];
        bom.dependencies.push({ ref: 'svc-1', dependsOn: [A] });

        const result = linkUnfoldedRoots(bom, [input(A), input(B)]);

        expect(result.count).toBe(1);
        expect(result.bom.dependencies[0].dependsOn).toStrictEqual([LIB_A, LIB_B, B]);
    });

    it('(d) skips an input whose root bom-ref no top-level component carries (folded); count counts the others', () => {
        const bom = flattenDoc();
        bom.components = [node(LIB_A), node(LIB_B), node(B)];

        const result = linkUnfoldedRoots(bom, [input(A), input(B)]);

        expect(result.count).toBe(1);
        expect(result.bom.dependencies[0].dependsOn).toStrictEqual([LIB_A, LIB_B, B]);
        expect(debug).toHaveBeenCalledWith({ bomRef: A }, 'unfolded root link: input skipped: no top-level component carries its bom-ref');
    });

    it('(e) skips an input root whose bom-ref is the merged root: no self-loop', () => {
        const bom = flattenDoc();
        bom.components.push(node(R));

        const result = linkUnfoldedRoots(bom, [input(R), input(A)]);

        expect(result.count).toBe(1);
        expect(result.bom.dependencies[0].dependsOn).toStrictEqual([LIB_A, LIB_B, A]);
        expect(result.bom.dependencies[0].dependsOn).not.toContain(R);
    });

    it('(f) links two inputs with one root bom-ref once', () => {
        const result = linkUnfoldedRoots(flattenDoc(), [input(A), input(A), input(B)]);

        expect(result.count).toBe(2);
        expect(result.bom.dependencies[0].dependsOn).toStrictEqual([LIB_A, LIB_B, A, B]);
    });

    it('(g) creates the merged root entry at index 0 when there is none', () => {
        const bom = flattenDoc();
        bom.dependencies = bom.dependencies.slice(1);

        const result = linkUnfoldedRoots(bom, [input(A), input(B)]);

        expect(result.bom.dependencies).toStrictEqual([
            { ref: R, dependsOn: [A, B] },
            { ref: LIB_A, dependsOn: [] },
            { ref: LIB_B, dependsOn: [] }
        ]);
        expect(result.bom.dependencies[1]).toBe(bom.dependencies[0]);
    });

    it('(g) appends to the first entry of the merged root when an entry without dependsOn holds it', () => {
        const bom = flattenDoc();
        bom.dependencies[0] = { ref: R };

        const result = linkUnfoldedRoots(bom, [input(A)]);

        expect(result.bom.dependencies[0]).toStrictEqual({ ref: R, dependsOn: [A] });
    });

    it('(h) creates dependencies when they are absent', () => {
        const bom = flattenDoc();
        delete bom.dependencies;

        const result = linkUnfoldedRoots(bom, [input(A), input(B)]);

        expect(result.count).toBe(2);
        expect(result.bom.dependencies).toStrictEqual([{ ref: R, dependsOn: [A, B] }]);
        expect(bom).not.toHaveProperty('dependencies');
    });

    it('(i) skips an input with no metadata.component, and a root with no or an empty bom-ref', () => {
        const noRoot = { bomFormat: 'CycloneDX', specVersion: '1.6', components: [] };

        const result = linkUnfoldedRoots(flattenDoc(), [noRoot, input(undefined), input(''), null, input(B)]);

        expect(result.count).toBe(1);
        expect(result.bom.dependencies[0].dependsOn).toStrictEqual([LIB_A, LIB_B, B]);
    });

    it('(j) does not link a node carrying the root bom-ref only nested under another component', () => {
        const bom = flattenDoc();
        bom.components = [node(LIB_A, { components: [node(A)] }), node(LIB_B), node(B)];

        const result = linkUnfoldedRoots(bom, [input(A), input(B)]);

        expect(result.count).toBe(1);
        expect(result.bom.dependencies[0].dependsOn).toStrictEqual([LIB_A, LIB_B, B]);
    });

    it('(k) matches the bom-ref exactly: case or a trailing space is another ref', () => {
        const bom = flattenDoc();
        bom.components = [node(LIB_A), node(LIB_B), node(A.toUpperCase()), node(B + ' ')];

        const result = linkUnfoldedRoots(bom, [input(A), input(B)]);

        expect(result.bom).toBe(bom);
        expect(result.count).toBe(0);
    });

    it('(l) returns the input itself without a merged root, without components, or without inputs', () => {
        const noRoot = flattenDoc();
        delete noRoot.metadata.component;
        const noComponents = flattenDoc();
        delete noComponents.components;
        const emptyComponents = flattenDoc();
        emptyComponents.components = [];
        const doc = flattenDoc();

        for (const [bom, inputs] of [[noRoot, [input(A)]], [noComponents, [input(A)]], [emptyComponents, [input(A)]], [doc, []], [doc, undefined]] as [any, any][]) {
            const result = linkUnfoldedRoots(bom, inputs);
            expect(result.bom).toBe(bom);
            expect(result.count).toBe(0);
        }
        expect(linkUnfoldedRoots(null, [input(A)])).toStrictEqual({ bom: null, count: 0 });
        expect(info).not.toHaveBeenCalled();
    });

    it('(m) leaves an orphan top-level library that is no input\'s root an orphan', () => {
        const bom = flattenDoc();
        bom.components.push(node(ORPHAN));

        const result = linkUnfoldedRoots(bom, [input(A), input(B)]);

        const named = result.bom.dependencies.flatMap((d: any) => [d.ref, ...(d.dependsOn || [])]);
        expect(named).not.toContain(ORPHAN);
        expect(result.bom.dependencies[0].dependsOn).toStrictEqual([LIB_A, LIB_B, A, B]);
    });

    it('(n) is idempotent, and two runs on equal inputs give byte-equal output', () => {
        const first = linkUnfoldedRoots(flattenDoc(), [input(A), input(B)]);
        info.mockClear();

        const second = linkUnfoldedRoots(first.bom, [input(A), input(B)]);

        expect(second.bom).toBe(first.bom);
        expect(second.count).toBe(0);
        expect(info).not.toHaveBeenCalled();
        const again = linkUnfoldedRoots(flattenDoc(), [input(A), input(B)]);
        expect(JSON.stringify(again.bom)).toBe(JSON.stringify(first.bom));
    });
});
