import { describe, it, expect, vi } from 'vitest';
import { PackageURL } from 'packageurl-js';
import {
    rekeyUnlistedInputs,
    recordRekeyedNodes,
    establishPurl,
    NODES_REKEYED_PROPERTY,
    BOM_REFS_DEDUPLICATED_PROPERTY
} from '../../src/services/bom/bomProcessingService';
import { logger } from '../../src/logger';
import { RootComponentMergeMode } from '../../src/types';
import { REBOM_MERGE_MOD, UPLOAD_MOD } from '../../src/services/bom/releaseNodeStamp';

/**
 * SCORE-66 (T-1; ADR-2 to ADR-5): in a product merge, a node of an input rearm-saas did not list that
 * carries a release key (the merged root's key, a listed root's purl or bom-ref) is written apart
 * before the CLI merge, at any depth, services included; two unlisted roots of one key are both
 * written apart; a listed input and a FLATTEN merge are never touched.
 */

const ORG = 'Facts Org';
const PRESERVE = RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT;
const FLATTEN = RootComponentMergeMode.FLATTEN_UNDER_NEW_ROOT;
const SERIAL_A = 'urn:uuid:66000000-0000-4000-8000-00000000000a';
const SERIAL_B = 'urn:uuid:66000000-0000-4000-8000-00000000000b';
const SERIAL_L = 'urn:uuid:66000000-0000-4000-8000-00000000000c';
const PLACEHOLDER_REF = 'urn:rearm:release:0b0f4a52-6c55-4d1f-9a8e-000000006601';
const LIB_A = 'pkg:npm/lib-a@1.0.0';
const LIB_B = 'pkg:npm/lib-b@1.0.0';
const LISTED_ROOT = 'pkg:oci/facts-product@3.0.0?belongsto=RELEASE';
const LISTED_REF = 'listed-root-ref';
const COMPONENT_ROOT = 'pkg:generic/Facts%20Org/facts-api@1.0.0';

function options(extra: any = {}): any {
    return { name: 'facts-product', group: ORG, version: '3.0.0', rootComponentMergeMode: PRESERVE, releaseRootIds: ['listed'], ...extra };
}

/** The merged root's key under `opts`, as mergeBomObjects sends it. */
function keyOf(opts: any): string {
    return establishPurl(undefined, opts);
}

const X = keyOf(options());

function Q(base: string, ref: string): string {
    return `${base}#${ref}`;
}

function P(purl: string, base: string): string {
    const at = purl.indexOf('#');
    const head = at < 0 ? purl : purl.slice(0, at);
    const tail = at < 0 ? '' : purl.slice(at);
    return `${head}${head.includes('?') ? '&' : '?'}rearm_input=${encodeURIComponent(base)}${tail}`;
}

function lib(purl: string, extra: any = {}): any {
    return { type: 'library', name: purl, version: '1.0.0', purl, 'bom-ref': purl, ...extra };
}

/** A stored row: `root`, its top-level `components`, a dependency entry per node. */
function doc(serial: string | undefined, root: any, components: any[] = [], extra: any = {}): any {
    const bom: any = {
        bomFormat: 'CycloneDX', specVersion: '1.6', version: 1,
        metadata: { component: root },
        components,
        dependencies: [
            { ref: root['bom-ref'], dependsOn: components.map(c => c['bom-ref']).filter(Boolean) },
            ...components.filter(c => c['bom-ref']).map(c => ({ ref: c['bom-ref'], dependsOn: [] }))
        ],
        ...extra
    };
    if (serial !== undefined) bom.serialNumber = serial;
    return bom;
}

/** The product's own listed upload (id `listed`). */
function listedDoc(root: any = { type: 'application', name: 'facts-product', version: '3.0.0', purl: LISTED_ROOT, 'bom-ref': LISTED_REF }): any {
    return doc(SERIAL_L, root, [lib(LIB_B)]);
}

/** A component release's row whose root carries `key` as bom-ref and purl. */
function rowWithRoot(serial: string | undefined, key: string, components: any[] = [lib(LIB_A)], extra: any = {}): any {
    return doc(serial, { type: 'application', name: 'facts-product', version: '3.0.0', purl: key, 'bom-ref': key, supplier: { name: 'S-comp' } }, components, extra);
}

function componentRow(serial: string, components: any[] = [lib(LIB_A)], extra: any = {}): any {
    return doc(serial, { type: 'library', name: 'facts-api', version: '1.0.0', purl: COMPONENT_ROOT, 'bom-ref': COMPONENT_ROOT }, components, extra);
}

function placeholderDoc(purl?: string): any {
    const root: any = { type: 'library', name: 'worker', version: '4.0.0', 'bom-ref': PLACEHOLDER_REF, properties: [{ name: 'reliza:sbom:missing', value: 'NO_SBOM_ARTIFACT' }] };
    if (purl) root.purl = purl;
    return { bomFormat: 'CycloneDX', specVersion: '1.6', metadata: { component: root }, components: [], dependencies: [{ ref: PLACEHOLDER_REF, dependsOn: [] }] };
}

/** Runs the pass and checks the inputs were not changed. */
function run(ids: string[], inputs: any[], opts: any = options()) {
    const before = structuredClone(inputs);
    const result = rekeyUnlistedInputs(ids, inputs, opts);
    expect(inputs).toStrictEqual(before);
    expect(result.inputs).toHaveLength(inputs.length);
    return result;
}

function expectUntouched(ids: string[], inputs: any[], opts: any = options()) {
    const result = run(ids, inputs, opts);
    expect(result.inputs).toBe(inputs);
    expect(result.count).toBe(0);
}

describe('rekeyUnlistedInputs: release keys (K)', () => {
    it('writes apart an unlisted root carrying the merged root key and re-points every reference to it', () => {
        const row = rowWithRoot(SERIAL_A, X, [lib(LIB_A)], {
            compositions: [{ aggregate: 'complete', assemblies: [X, LIB_A], dependencies: [X] }],
            vulnerabilities: [{ id: 'CVE-2026-0001', affects: [{ ref: X }, { ref: LIB_A }] }],
            annotations: [{ subjects: [X, LIB_A], annotator: { organization: { name: 'o' } }, timestamp: '2026-10-23T00:00:00Z', text: 't' }]
        });
        row.dependencies.push({ ref: LIB_B, provides: [X] });
        const listed = listedDoc();

        const result = run(['listed', 'row'], [listed, row]);

        expect(result.count).toBe(1);
        expect(result.inputs[0]).toBe(listed);
        const out = result.inputs[1];
        expect(out).not.toBe(row);
        expect(out.metadata.component).toStrictEqual({ ...row.metadata.component, 'bom-ref': Q(SERIAL_A, X), purl: P(X, SERIAL_A) });
        expect(out.dependencies).toStrictEqual([
            { ref: Q(SERIAL_A, X), dependsOn: [LIB_A] },
            { ref: LIB_A, dependsOn: [] },
            { ref: LIB_B, provides: [Q(SERIAL_A, X)] }
        ]);
        expect(out.compositions).toStrictEqual([{ aggregate: 'complete', assemblies: [Q(SERIAL_A, X), LIB_A], dependencies: [Q(SERIAL_A, X)] }]);
        expect(out.vulnerabilities[0].affects).toStrictEqual([{ ref: Q(SERIAL_A, X) }, { ref: LIB_A }]);
        expect(out.annotations[0].subjects).toStrictEqual([Q(SERIAL_A, X), LIB_A]);
        expect(out.components).toBe(row.components);
        expect({ ...PackageURL.fromString(out.metadata.component.purl).qualifiers }).toStrictEqual({ rearm_input: SERIAL_A });
    });

    const SHAPES: [string, any][] = [
        ['tldOnly', { tldOnly: true }],
        ['belongsTo RELEASE', { belongsTo: 'RELEASE' }],
        ['structure HIERARCHICAL', { structure: 'HIERARCHICAL' }]
    ];
    for (const [label, extra] of SHAPES) {
        it(`follows the options for the merged root key: ${label}`, () => {
            const opts = options(extra);
            const key = keyOf(opts);
            expect(key).not.toBe(X);
            const plain = rowWithRoot(SERIAL_B, X);
            const row = rowWithRoot(SERIAL_A, key);

            const result = run(['listed', 'row', 'plain'], [listedDoc(), row, plain], opts);

            expect(result.count).toBe(1);
            expect(result.inputs[1].metadata.component['bom-ref']).toBe(Q(SERIAL_A, key));
            expect(result.inputs[1].metadata.component.purl).toBe(P(key, SERIAL_A));
            expect(result.inputs[2]).toBe(plain);
        });
    }

    it('re-keys the bom-ref of a node two levels down that carries the key, and keeps its other purl', () => {
        const deep = { type: 'library', name: 'forged', purl: 'pkg:npm/forged@1.0.0', 'bom-ref': X };
        const row = componentRow(SERIAL_A, [lib(LIB_A, { components: [lib(LIB_B, { components: [deep] })] })]);
        row.dependencies.push({ ref: X, dependsOn: [] });

        const result = run(['listed', 'row'], [listedDoc(), row]);

        expect(result.count).toBe(1);
        const out = result.inputs[1];
        expect(out.components[0].components[0].components[0]).toStrictEqual({ ...deep, 'bom-ref': Q(SERIAL_A, X) });
        expect(out.metadata.component).toBe(row.metadata.component);
        expect(out.dependencies[out.dependencies.length - 1]).toStrictEqual({ ref: Q(SERIAL_A, X), dependsOn: [] });
    });

    it('qualifies the purl of a nested node that carries the key, and keeps its bom-ref', () => {
        const deep = { type: 'library', name: 'forged', purl: X, 'bom-ref': 'forged' };
        const row = componentRow(SERIAL_A, [lib(LIB_A, { components: [deep] })]);

        const result = run(['listed', 'row'], [listedDoc(), row]);

        expect(result.count).toBe(1);
        expect(result.inputs[1].components[0].components[0]).toStrictEqual({ ...deep, purl: P(X, SERIAL_A) });
        expect(result.inputs[1].dependencies).toBe(row.dependencies);
    });

    it('qualifies the purl of a top-level node that carries the key, and keeps its bom-ref', () => {
        const forged = { type: 'library', name: 'forged', purl: X, 'bom-ref': 'forged' };
        const row = componentRow(SERIAL_A, [lib(LIB_A), forged]);

        const result = run(['listed', 'row'], [listedDoc(), row]);

        expect(result.count).toBe(1);
        expect(result.inputs[1].components).toStrictEqual([lib(LIB_A), { ...forged, purl: P(X, SERIAL_A) }]);
    });

    it('gives the FLATTEN row copy of its own root the same qualified purl as the root, and counts both', () => {
        const copy = { type: 'application', name: 'facts-product', purl: X, 'bom-ref': 'self-copy' };
        const row = rowWithRoot(SERIAL_A, X, [copy]);

        const result = run(['listed', 'row'], [listedDoc(), row]);

        expect(result.count).toBe(2);
        const out = result.inputs[1];
        expect(out.metadata.component['bom-ref']).toBe(Q(SERIAL_A, X));
        expect(out.metadata.component.purl).toBe(P(X, SERIAL_A));
        expect(out.components).toStrictEqual([{ ...copy, purl: P(X, SERIAL_A) }]);
    });

    it('qualifies the purl of a node that carries a listed root purl', () => {
        const forged = { type: 'library', name: 'forged', purl: LISTED_ROOT, 'bom-ref': 'forged' };
        const row = componentRow(SERIAL_A, [forged]);

        const result = run(['listed', 'row'], [listedDoc(), row]);

        expect(result.count).toBe(1);
        expect(result.inputs[1].components[0]).toStrictEqual({ ...forged, purl: P(LISTED_ROOT, SERIAL_A) });
    });

    it('re-keys the bom-ref of a node that carries a listed root bom-ref, and keeps its other purl', () => {
        const forged = { type: 'library', name: 'forged', purl: 'pkg:npm/forged@1.0.0', 'bom-ref': LISTED_REF };
        const row = componentRow(SERIAL_A, [forged]);

        const result = run(['listed', 'row'], [listedDoc(), row]);

        expect(result.count).toBe(1);
        expect(result.inputs[1].components[0]).toStrictEqual({ ...forged, 'bom-ref': Q(SERIAL_A, LISTED_REF) });
        expect(result.inputs[1].dependencies[0].dependsOn).toStrictEqual([Q(SERIAL_A, LISTED_REF)]);
    });

    it('qualifies a placeholder purl that is the key with the placeholder bom-ref, and keeps the bom-ref', () => {
        const ph = placeholderDoc(X);

        const result = run(['listed'], [listedDoc(), ph]);

        expect(result.count).toBe(1);
        expect(result.inputs[1].metadata.component['bom-ref']).toBe(PLACEHOLDER_REF);
        expect(result.inputs[1].metadata.component.purl).toBe(P(X, PLACEHOLDER_REF));
        expect(result.inputs[1].dependencies).toBe(ph.dependencies);
    });

    it('takes the root bom-ref as the base of an input without a serial number', () => {
        const forged = { type: 'library', name: 'forged', purl: 'pkg:npm/forged@1.0.0', 'bom-ref': X };
        const row = componentRow(undefined as any, [forged]);
        delete row.serialNumber;

        const result = run(['listed', 'row'], [listedDoc(), row]);

        expect(result.inputs[1].components[0]['bom-ref']).toBe(Q(COMPONENT_ROOT, X));
    });

    it('gives two re-keyed nodes of one input two distinct bom-refs', () => {
        const byRef = { type: 'library', name: 'a', purl: 'pkg:npm/a@1.0.0', 'bom-ref': X };
        const byListedRef = { type: 'library', name: 'b', purl: 'pkg:npm/b@1.0.0', 'bom-ref': LISTED_REF };
        const row = componentRow(SERIAL_A, [byRef, byListedRef]);

        const result = run(['listed', 'row'], [listedDoc(), row]);

        expect(result.count).toBe(2);
        expect(result.inputs[1].components.map((c: any) => c['bom-ref'])).toStrictEqual([Q(SERIAL_A, X), Q(SERIAL_A, LISTED_REF)]);
    });

    it('puts the qualifier before the subpath', () => {
        const opts = options({ purl: 'pkg:generic/g/n@1#sub' });
        const row = componentRow(SERIAL_A, [{ type: 'library', name: 'n', purl: 'pkg:generic/g/n@1#sub', 'bom-ref': 'n' }]);

        const result = run(['listed', 'row'], [listedDoc(), row], opts);

        expect(result.inputs[1].components[0].purl).toBe(`pkg:generic/g/n@1?rearm_input=${encodeURIComponent(SERIAL_A)}#sub`);
    });

    it('replaces a rearm_input qualifier already on the purl instead of adding a second one', () => {
        const key = 'pkg:generic/g/n@1?a=b&rearm_input=old&c=d';
        const row = componentRow(SERIAL_A, [{ type: 'library', name: 'n', purl: key, 'bom-ref': 'n' }]);

        const result = run(['listed', 'row'], [listedDoc(), row], options({ purl: key }));

        expect(result.inputs[1].components[0].purl).toBe(`pkg:generic/g/n@1?a=b&rearm_input=${encodeURIComponent(SERIAL_A)}&c=d`);
    });

    it('replaces an existing rearm_input qualifier regardless of its key case, instead of doubling it', () => {
        const key = 'pkg:generic/g/n@1?a=b&REARM_INPUT=old&c=d';
        const row = componentRow(SERIAL_A, [{ type: 'library', name: 'n', purl: key, 'bom-ref': 'n' }]);

        const result = run(['listed', 'row'], [listedDoc(), row], options({ purl: key }));

        expect(result.inputs[1].components[0].purl).toBe(`pkg:generic/g/n@1?a=b&rearm_input=${encodeURIComponent(SERIAL_A)}&c=d`);
    });

    it('writes the qualifier straight after an empty query instead of an empty pair', () => {
        const key = 'pkg:generic/g/n@1?';
        const row = componentRow(SERIAL_A, [{ type: 'library', name: 'n', purl: key, 'bom-ref': 'n' }]);

        const result = run(['listed', 'row'], [listedDoc(), row], options({ purl: key }));

        expect(result.inputs[1].components[0].purl).toBe(`pkg:generic/g/n@1?rearm_input=${encodeURIComponent(SERIAL_A)}`);
    });

    it('keeps every byte of a percent-encoded purl with a hash qualifier and appends the qualifier with &', () => {
        const opts = options({ hash: 'sha256:' + 'a'.repeat(64) });
        const key = keyOf(opts);
        expect(key).toContain('%20');
        expect(key).toContain('hash=sha256%3A');
        const row = rowWithRoot(SERIAL_A, key);

        const result = run(['listed', 'row'], [listedDoc(), row], opts);

        expect(result.inputs[1].metadata.component.purl).toBe(`${key}&rearm_input=${encodeURIComponent(SERIAL_A)}`);
    });
});

describe('rekeyUnlistedInputs: the root collision (C_i)', () => {
    const SHARED = 'pkg:generic/Facts%20Org/twin@1.0.0';

    it('writes apart both unlisted roots that share a purl, whatever their order', () => {
        const a = rowWithRoot(SERIAL_A, SHARED);
        const b = rowWithRoot(SERIAL_B, SHARED);

        const forward = run(['listed', 'a', 'b'], [listedDoc(), a, b]);
        const reversed = run(['listed', 'b', 'a'], [listedDoc(), b, a]);

        expect(forward.count).toBe(2);
        expect(reversed.count).toBe(2);
        expect(forward.inputs[1].metadata.component).toStrictEqual({ ...a.metadata.component, 'bom-ref': Q(SERIAL_A, SHARED), purl: P(SHARED, SERIAL_A) });
        expect(forward.inputs[2].metadata.component).toStrictEqual({ ...b.metadata.component, 'bom-ref': Q(SERIAL_B, SHARED), purl: P(SHARED, SERIAL_B) });
        expect(reversed.inputs[1]).toStrictEqual(forward.inputs[2]);
        expect(reversed.inputs[2]).toStrictEqual(forward.inputs[1]);
    });

    it('re-keys both bom-refs of two unlisted roots that share a bom-ref, and keeps their purls', () => {
        const a = doc(SERIAL_A, { type: 'library', name: 'a', purl: 'pkg:npm/a@1.0.0', 'bom-ref': 'shared' });
        const b = doc(SERIAL_B, { type: 'library', name: 'b', purl: 'pkg:npm/b@1.0.0', 'bom-ref': 'shared' });

        const result = run(['listed', 'a', 'b'], [listedDoc(), a, b]);

        expect(result.count).toBe(2);
        expect(result.inputs[1].metadata.component).toStrictEqual({ ...a.metadata.component, 'bom-ref': Q(SERIAL_A, 'shared') });
        expect(result.inputs[2].metadata.component).toStrictEqual({ ...b.metadata.component, 'bom-ref': Q(SERIAL_B, 'shared') });
    });

    it('leaves a top-level node carrying another unlisted root purl where it is (the SCORE-40 listing shape)', () => {
        const component = componentRow(SERIAL_A);
        const listing = doc(SERIAL_B, lib('pkg:npm/web-app@2.0.0'), [lib(COMPONENT_ROOT)]);

        expectUntouched(['listed', 'component', 'listing'], [listedDoc(), component, listing]);
    });

    it('leaves a nested node carrying another unlisted root purl where it is', () => {
        const component = componentRow(SERIAL_A);
        const listing = doc(SERIAL_B, lib('pkg:npm/web-app@2.0.0'), [lib('pkg:npm/mid@1.0.0', { components: [lib(COMPONENT_ROOT)] })]);

        expectUntouched(['listed', 'component', 'listing'], [listedDoc(), component, listing]);
    });

    it('qualifies a placeholder purl that is another unlisted root purl, keeps its bom-ref, and qualifies that root too', () => {
        const component = componentRow(SERIAL_A);
        const ph = placeholderDoc(COMPONENT_ROOT);

        const result = run(['listed', 'component'], [listedDoc(), component, ph]);

        expect(result.count).toBe(2);
        expect(result.inputs[2].metadata.component['bom-ref']).toBe(PLACEHOLDER_REF);
        expect(result.inputs[2].metadata.component.purl).toBe(P(COMPONENT_ROOT, PLACEHOLDER_REF));
        expect(result.inputs[1].metadata.component.purl).toBe(P(COMPONENT_ROOT, SERIAL_A));
        expect(result.inputs[1].metadata.component['bom-ref']).toBe(Q(SERIAL_A, COMPONENT_ROOT));
    });
});

describe('rekeyUnlistedInputs: services (F-10)', () => {
    it('re-keys a service under the key and re-points the entries that name it', () => {
        const svc = { 'bom-ref': X, name: 'svc', endpoints: ['https://svc.example'] };
        const row = componentRow(SERIAL_A, [lib(LIB_A)], { services: [svc] });
        row.dependencies = [{ ref: X, dependsOn: [LIB_A] }, { ref: LIB_A, provides: [X] }];

        const result = run(['listed', 'row'], [listedDoc(), row]);

        expect(result.count).toBe(1);
        const out = result.inputs[1];
        expect(out.services).toStrictEqual([{ ...svc, 'bom-ref': Q(SERIAL_A, X) }]);
        expect(out.dependencies).toStrictEqual([{ ref: Q(SERIAL_A, X), dependsOn: [LIB_A] }, { ref: LIB_A, provides: [Q(SERIAL_A, X)] }]);
        expect(out.metadata.component).toBe(row.metadata.component);
        expect(out.components).toBe(row.components);
    });

    it('re-keys a nested service under the key and keeps the outer one', () => {
        const row = componentRow(SERIAL_A, [lib(LIB_A)], { services: [{ 'bom-ref': 'svc-outer', name: 'outer', services: [{ 'bom-ref': X, name: 'forged-svc' }] }] });

        const result = run(['listed', 'row'], [listedDoc(), row]);

        expect(result.count).toBe(1);
        expect(result.inputs[1].services).toStrictEqual([{ 'bom-ref': 'svc-outer', name: 'outer', services: [{ 'bom-ref': Q(SERIAL_A, X), name: 'forged-svc' }] }]);
    });

    it('re-keys a service under a listed root bom-ref and one under a listed root purl', () => {
        const row = componentRow(SERIAL_A, [lib(LIB_A)], { services: [{ 'bom-ref': LISTED_REF, name: 'one' }, { 'bom-ref': LISTED_ROOT, name: 'two' }] });

        const result = run(['listed', 'row'], [listedDoc(), row]);

        expect(result.count).toBe(2);
        expect(result.inputs[1].services.map((s: any) => s['bom-ref'])).toStrictEqual([Q(SERIAL_A, LISTED_REF), Q(SERIAL_A, LISTED_ROOT)]);
    });

    it('re-keys a service and a component of one input under the key to the same bom-ref, and counts both', () => {
        const row = componentRow(SERIAL_A, [{ type: 'library', name: 'forged', purl: 'pkg:npm/forged@1.0.0', 'bom-ref': X }], { services: [{ 'bom-ref': X, name: 'svc' }] });

        const result = run(['listed', 'row'], [listedDoc(), row]);

        expect(result.count).toBe(2);
        expect(result.inputs[1].components[0]['bom-ref']).toBe(Q(SERIAL_A, X));
        expect(result.inputs[1].services[0]['bom-ref']).toBe(Q(SERIAL_A, X));
    });

    it('re-keys a service under the key when the merge mode is absent', () => {
        const opts = options();
        delete opts.rootComponentMergeMode;
        const row = componentRow(SERIAL_A, [lib(LIB_A)], { services: [{ 'bom-ref': X, name: 'svc' }] });

        const result = run(['listed', 'row'], [listedDoc(), row], opts);

        expect(result.count).toBe(1);
        expect(result.inputs[1].services[0]['bom-ref']).toBe(Q(SERIAL_A, X));
    });
});

describe('rekeyUnlistedInputs: nothing to re-key', () => {
    it('leaves the listed input with the merged root key alone (the identifier case)', () => {
        const listed = rowWithRoot(SERIAL_L, X);
        expectUntouched(['listed', 'row'], [listed, componentRow(SERIAL_A)]);
    });

    it('leaves a service of the listed input under the key alone', () => {
        const listed = listedDoc();
        listed.services = [{ 'bom-ref': X, name: 'own' }];
        expectUntouched(['listed', 'row'], [listed, componentRow(SERIAL_A)]);
    });

    it('leaves a service under another unlisted root bom-ref alone (C_i is roots only)', () => {
        const other = componentRow(SERIAL_B, [lib(LIB_A)], { services: [{ 'bom-ref': 'pkg:npm/web-app@2.0.0', name: 'svc' }] });
        expectUntouched(['listed', 'web', 'other'], [listedDoc(), doc(SERIAL_A, lib('pkg:npm/web-app@2.0.0')), other]);
    });

    it('leaves a service without a bom-ref alone', () => {
        expectUntouched(['listed', 'row'], [listedDoc(), componentRow(SERIAL_A, [lib(LIB_A)], { services: [{ name: 'anonymous' }] })]);
    });

    it('leaves a service under the key alone in a FLATTEN merge', () => {
        expectUntouched(['listed', 'row'], [listedDoc(), componentRow(SERIAL_A, [lib(LIB_A)], { services: [{ 'bom-ref': X, name: 'svc' }] })], options({ rootComponentMergeMode: FLATTEN }));
    });

    it('leaves an unlisted root with the key alone in a FLATTEN merge', () => {
        expectUntouched(['listed', 'row'], [listedDoc(), rowWithRoot(SERIAL_A, X)], options({ rootComponentMergeMode: FLATTEN }));
    });

    it('leaves two unlisted roots sharing a purl alone in a FLATTEN merge', () => {
        const shared = 'pkg:generic/Facts%20Org/twin@1.0.0';
        expectUntouched(['a', 'b'], [rowWithRoot(SERIAL_A, shared), rowWithRoot(SERIAL_B, shared)], options({ rootComponentMergeMode: FLATTEN }));
    });

    it('leaves inputs alone when no node carries a reserved key', () => {
        expectUntouched(['listed', 'row', 'web'], [listedDoc(), componentRow(SERIAL_A), doc(SERIAL_B, lib('pkg:npm/web-app@2.0.0'), [lib(LIB_B)])]);
    });

    it('leaves a node with neither bom-ref nor purl alone', () => {
        expectUntouched(['listed', 'row'], [listedDoc(), componentRow(SERIAL_A, [{ type: 'library', name: 'bare' }])]);
    });

    it('carries a node written apart in a merge below through a merge above whose keys do not name it', () => {
        const apart = { type: 'application', name: 'facts-product', version: '3.0.0', purl: P(X, SERIAL_A), 'bom-ref': Q(SERIAL_A, X) };
        const inner = doc(SERIAL_B, { type: 'application', name: 'facts-product', version: '3.0.0', purl: X, 'bom-ref': X }, [apart]);
        const suite = options({ name: 'facts-suite', version: '1.0.0', releaseRootIds: [] });

        expectUntouched(['inner', 'web'], [inner, doc(SERIAL_L, lib('pkg:npm/web-app@2.0.0'))], suite);
    });

    it('returns the same array for no inputs', () => {
        expectUntouched([], []);
    });
});

describe('rekeyUnlistedInputs: attached roots are listed (SCORE-63 ADR-9)', () => {
    const A = 'pkg:npm/facts%20org/facts-product@3.0.0?belongsto=SCE&hash=a94a8fe5ccb19ba61c4c0873d391e987982fbbd3';
    const SERIAL_ATT = 'urn:uuid:66000000-0000-4000-8000-00000000006a';
    const MIT = { license: { id: 'MIT' } };
    /** The product's own SCE upload (id `att`): its root bom-ref and purl are `A`. */
    const att = () => doc(SERIAL_ATT, { type: 'application', name: 'facts-product', version: '3.0.0', purl: A, 'bom-ref': A });
    const nodeA = () => ({ type: 'library', name: 'forged', version: '3.0.0', 'bom-ref': A, purl: A, licenses: [MIT] });
    const opts = (extra: any = {}) => options({ releaseRootIds: ['listed'], attachedRootIds: ['att'], ...extra });

    it('(a) re-keys a top-level node of an unlisted row under the attached root key, the listed input untouched', () => {
        const info = vi.spyOn(logger, 'info');
        const a = att();
        const row = componentRow(SERIAL_A, [lib(LIB_A), nodeA()]);

        const result = run(['listed', 'att', 'row'], [listedDoc(), a, row], opts());

        expect(result.count).toBe(1);
        const out = result.inputs[2];
        expect(out).not.toBe(row);
        expect(out.metadata.component).toBe(row.metadata.component);
        expect(out.components[1]).toStrictEqual({ ...nodeA(), 'bom-ref': Q(SERIAL_A, A), purl: P(A, SERIAL_A) });
        expect(result.inputs[1]).toBe(a);
        const line = info.mock.calls.find((c: any[]) => c[0]?.input === SERIAL_A);
        expect(line?.[0].reason).toBe('release key');
        info.mockRestore();
    });

    it('(b) re-keys the node two levels down, the nodes above it untouched', () => {
        const deep = nodeA();
        const row = componentRow(SERIAL_A, [lib(LIB_A, { components: [lib(LIB_B, { components: [deep] })] })]);

        const result = run(['listed', 'att', 'row'], [listedDoc(), att(), row], opts());

        expect(result.count).toBe(1);
        const outer = result.inputs[2].components[0];
        expect({ ...outer, components: undefined }).toStrictEqual({ ...row.components[0], components: undefined });
        expect(outer.components[0].name).toBe(LIB_B);
        expect(outer.components[0].components[0]).toStrictEqual({ ...deep, 'bom-ref': Q(SERIAL_A, A), purl: P(A, SERIAL_A) });
    });

    it('(c) re-keys a service under the attached root key and re-points the entries naming it', () => {
        const row = componentRow(SERIAL_A, [lib(LIB_A)], {
            services: [{ 'bom-ref': A, name: 'forged-svc' }],
            dependencies: [{ ref: COMPONENT_ROOT, dependsOn: [LIB_A, A] }, { ref: LIB_A, dependsOn: [] }, { ref: A, dependsOn: [] }]
        });

        const result = run(['listed', 'att', 'row'], [listedDoc(), att(), row], opts());

        expect(result.count).toBe(1);
        expect(result.inputs[2].services).toStrictEqual([{ 'bom-ref': Q(SERIAL_A, A), name: 'forged-svc' }]);
        expect(result.inputs[2].dependencies).toStrictEqual([
            { ref: COMPONENT_ROOT, dependsOn: [LIB_A, Q(SERIAL_A, A)] }, { ref: LIB_A, dependsOn: [] }, { ref: Q(SERIAL_A, A), dependsOn: [] }
        ]);
    });

    it('(d) writes apart an unlisted row whose root carries the attached root key, never the listed one', () => {
        const a = att();
        const row = rowWithRoot(SERIAL_A, A);

        const result = run(['listed', 'att', 'row'], [listedDoc(), a, row], opts());

        expect(result.count).toBe(1);
        expect(result.inputs[1]).toBe(a);
        expect(result.inputs[2].metadata.component['bom-ref']).toBe(Q(SERIAL_A, A));
        expect(result.inputs[2].metadata.component.purl).toBe(P(A, SERIAL_A));
    });

    it('(e) reads an id in both lists once: the node is re-keyed once', () => {
        const info = vi.spyOn(logger, 'info');
        const row = componentRow(SERIAL_A, [lib(LIB_A), nodeA()]);

        const result = run(['listed', 'att', 'row'], [listedDoc(), att(), row], opts({ releaseRootIds: ['listed', 'att'] }));

        expect(result.count).toBe(1);
        expect(result.inputs[2].components[1]['bom-ref']).toBe(Q(SERIAL_A, A));
        expect(info.mock.calls.filter((c: any[]) => c[0]?.input === SERIAL_A)).toHaveLength(1);
        info.mockRestore();
    });

    it('(f) qualifies the purl of a node whose purl alone is the attached root key, and keeps its bom-ref', () => {
        const forged = { type: 'library', name: 'forged', version: '3.0.0', 'bom-ref': 'forged-ref', purl: A };
        const row = componentRow(SERIAL_A, [lib(LIB_A), forged]);

        const result = run(['listed', 'att', 'row'], [listedDoc(), att(), row], opts());

        expect(result.count).toBe(1);
        expect(result.inputs[2].components[1]).toStrictEqual({ ...forged, purl: P(A, SERIAL_A) });
    });

    it.each([
        ['absent', undefined],
        ['null', null],
        ['empty', []]
    ])('(g) re-keys nothing when attachedRootIds is %s', (_label, list) => {
        const o = opts();
        if (list === undefined) delete o.attachedRootIds; else o.attachedRootIds = list;
        expectUntouched(['listed', 'att', 'row'], [listedDoc(), att(), componentRow(SERIAL_A, [lib(LIB_A), nodeA()])], o);
    });

    it('(h) re-keys nothing in a FLATTEN merge', () => {
        expectUntouched(['listed', 'att', 'row'], [listedDoc(), att(), componentRow(SERIAL_A, [lib(LIB_A), nodeA()])], opts({ rootComponentMergeMode: FLATTEN }));
    });

    it('(i) reads the attached list when releaseRootIds is absent: both the merged root key and the attached key re-key', () => {
        const a = att();
        const row = rowWithRoot(SERIAL_A, X, [lib(LIB_A), nodeA()]);

        const result = run(['att', 'row'], [a, row], options({ releaseRootIds: undefined, attachedRootIds: ['att'] }));

        expect(result.count).toBe(2);
        const out = result.inputs[1];
        expect(out.metadata.component).toStrictEqual({ ...row.metadata.component, 'bom-ref': Q(SERIAL_A, X), purl: P(X, SERIAL_A) });
        expect(out.components[1]).toStrictEqual({ ...nodeA(), 'bom-ref': Q(SERIAL_A, A), purl: P(A, SERIAL_A) });
        expect(out.components[0]).toBe(row.components[0]);
        expect(out.dependencies).toStrictEqual([
            { ref: Q(SERIAL_A, X), dependsOn: [LIB_A, Q(SERIAL_A, A)] },
            { ref: LIB_A, dependsOn: [] },
            { ref: Q(SERIAL_A, A), dependsOn: [] }
        ]);
        expect(result.inputs[0]).toBe(a);
    });
});

describe('rekeyUnlistedInputs: fail-closed defaults', () => {
    it('treats every input as unlisted when releaseRootIds is absent', () => {
        const opts = options();
        delete opts.releaseRootIds;
        const row = rowWithRoot(SERIAL_A, X);

        const result = run(['row'], [row], opts);

        expect(result.count).toBe(1);
        expect(result.inputs[0].metadata.component['bom-ref']).toBe(Q(SERIAL_A, X));
    });

    it('treats an absent merge mode as a product merge', () => {
        const opts = options();
        delete opts.rootComponentMergeMode;
        const row = rowWithRoot(SERIAL_A, X);

        const result = run(['listed', 'row'], [listedDoc(), row], opts);

        expect(result.count).toBe(1);
        expect(result.inputs[1].metadata.component.purl).toBe(P(X, SERIAL_A));
    });
});

describe('rekeyUnlistedInputs: log lines (ADR-6)', () => {
    it('logs one line per re-keyed node with its input, kind, reason and both keys, then the count', () => {
        const info = vi.spyOn(logger, 'info');
        try {
            const shared = 'pkg:generic/Facts%20Org/twin@1.0.0';
            const row = componentRow(SERIAL_A, [lib(LIB_A)], { services: [{ 'bom-ref': X, name: 'svc' }] });
            run(['listed', 'row', 'a', 'b'], [listedDoc(), row, rowWithRoot(SERIAL_B, shared), rowWithRoot(SERIAL_L + '0', shared)]);

            const lines = info.mock.calls.filter(c => c[1] === 'node re-keyed: an unlisted input carries a reserved key').map(c => c[0]);
            expect(lines).toStrictEqual([
                { input: SERIAL_A, kind: 'service', reason: 'release key', from: { bomRef: X, purl: undefined }, to: { bomRef: Q(SERIAL_A, X), purl: undefined } },
                { input: SERIAL_B, kind: 'component', reason: 'root collision', from: { bomRef: shared, purl: shared }, to: { bomRef: Q(SERIAL_B, shared), purl: P(shared, SERIAL_B) } },
                { input: SERIAL_L + '0', kind: 'component', reason: 'root collision', from: { bomRef: shared, purl: shared }, to: { bomRef: Q(SERIAL_L + '0', shared), purl: P(shared, SERIAL_L + '0') } }
            ]);
            expect(info.mock.calls.filter(c => c[0] === 'node re-key: 3 nodes of unlisted merge inputs re-keyed before the merge')).toHaveLength(1);
        } finally {
            info.mockRestore();
        }
    });
});

// SCORE-69 T-1 (ADR-1, ADR-4, ADR-5): in a product merge, an unlisted input that is a product row
// (row meta: mod rebom, mode not FLATTEN) reserves its root's keys, its listed root keys and their
// images against every other unlisted input, except a product row whose record names its recorded
// serial while no other unlisted root carries its root's key.
const SERIAL_F = 'urn:uuid:66000000-0000-4000-8000-00000000000f';
const SERIAL_G = 'urn:uuid:66000000-0000-4000-8000-00000000000e';
const X_P = 'pkg:generic/Facts%20Org/inner@2.0.0';
const A_P = 'pkg:npm/facts%20org/inner@2.0.0?belongsto=SCE&hash=4f1c2a9e';
const OUTER = 'pkg:generic/Facts%20Org/outer@1.0.0';
const NESTED_LINE = 'nested product keys reserved';
const DENIED_LINE = 'nested product exemption denied';
const REKEYED_LINE = 'node re-keyed: an unlisted input carries a reserved key';

function productRow(serial: string | undefined, key: string = X_P, components: any[] = [lib(LIB_B)]): any {
    return doc(serial, { type: 'application', name: 'inner', version: '2.0.0', purl: key, 'bom-ref': key, supplier: { name: ORG } }, components);
}

function productMeta(extra: any = {}): any {
    return { mod: REBOM_MERGE_MOD, rootComponentMergeMode: PRESERVE, nestedProductSerials: [], listedRootKeys: [], ...extra };
}

function productMetaOf(serial: string, extra: any = {}): any {
    return productMeta({ serialNumber: serial, ...extra });
}

function componentMeta(): any {
    return { mod: REBOM_MERGE_MOD, rootComponentMergeMode: FLATTEN };
}

function uploadMeta(): any {
    return { mod: UPLOAD_MOD, rootComponentMergeMode: PRESERVE };
}

function listing(key: string, extra: any = {}): any {
    return lib(key, { name: 'inner', version: '2.0.0', licenses: [{ license: { id: 'MIT' } }], hashes: [{ alg: 'SHA-512', content: 'f'.repeat(128) }], ...extra });
}

/** Runs the pass with the row meta and checks the inputs and the meta were not changed. */
function run4(ids: string[], inputs: any[], rowMeta: any[], opts: any = options()) {
    const before = structuredClone(inputs);
    const metaBefore = structuredClone(rowMeta);
    const result = rekeyUnlistedInputs(ids, inputs, opts, rowMeta);
    expect(inputs).toStrictEqual(before);
    expect(rowMeta).toStrictEqual(metaBefore);
    expect(result.inputs).toHaveLength(inputs.length);
    return result;
}

/** `j`: a product row carrying copies of p's root, of p's listed root and of p's written-apart root. */
function carrier(): any {
    return productRow(SERIAL_L, OUTER, [lib(X_P, { name: 'inner' }), lib(A_P), lib(P(X_P, SERIAL_B), { 'bom-ref': Q(SERIAL_B, X_P) })]);
}

/** The three copies of `carrier()` as written apart under `j`'s base. */
function expectCarrierRekeyed(j: any) {
    expect(j.components.map((c: any) => c['bom-ref'])).toStrictEqual([Q(SERIAL_L, X_P), Q(SERIAL_L, A_P), Q(SERIAL_L, Q(SERIAL_B, X_P))]);
    expect(j.components.map((c: any) => c.purl)).toStrictEqual([P(X_P, SERIAL_L), P(A_P, SERIAL_L), P(X_P, SERIAL_L)]);
}

describe('rekeyUnlistedInputs: the nested product keys (M, SCORE-69)', () => {
    const lister = () => componentRow(SERIAL_A, [lib(LIB_A), listing(X_P)]);

    it('(a) writes apart a listing of a nested product root key, in either input order', () => {
        const info = vi.spyOn(logger, 'info');
        try {
            const p = productRow(SERIAL_B);
            const c = lister();
            const result = run4(['listed', 'p', 'c'], [listedDoc(), p, c], [undefined, productMeta(), componentMeta()]);

            expect(result.count).toBe(1);
            expect(result.inputs[1]).toBe(p);
            expect(result.inputs[2].components[1]).toStrictEqual({ ...listing(X_P), 'bom-ref': Q(SERIAL_A, X_P), purl: P(X_P, SERIAL_A) });
            expect(result.inputs[2].dependencies.find((d: any) => d.ref === Q(SERIAL_A, X_P))).toBeDefined();
            expect(result.inputs[2].dependencies.find((d: any) => d.ref === X_P)).toBeUndefined();
            const lines = info.mock.calls.filter(c => c[1] === REKEYED_LINE).map(c => c[0] as any);
            expect(lines.map(l => l.reason)).toStrictEqual(['nested product key']);

            const swapped = run4(['listed', 'c', 'p'], [listedDoc(), lister(), productRow(SERIAL_B)], [undefined, componentMeta(), productMeta()]);
            expect(swapped.count).toBe(1);
            expect(swapped.inputs[1].components[1]['bom-ref']).toBe(Q(SERIAL_A, X_P));
            expect(swapped.inputs[1].components[1].purl).toBe(P(X_P, SERIAL_A));
        } finally {
            info.mockRestore();
        }
    });

    it('(b) writes apart a listing two levels down and leaves the nodes above it', () => {
        const c = componentRow(SERIAL_A, [lib(LIB_A, { components: [lib('pkg:npm/mid@1.0.0', { components: [listing(X_P)] })] })]);
        const result = run4(['p', 'c'], [productRow(SERIAL_B), c], [productMeta(), componentMeta()]);

        expect(result.count).toBe(1);
        const top = result.inputs[1].components[0];
        expect(top['bom-ref']).toBe(LIB_A);
        expect(top.components[0]['bom-ref']).toBe('pkg:npm/mid@1.0.0');
        expect(top.components[0].components[0]['bom-ref']).toBe(Q(SERIAL_A, X_P));
        expect(top.components[0].components[0].purl).toBe(P(X_P, SERIAL_A));
    });

    it('(c) re-keys only the field that carries the key', () => {
        const byRef = componentRow(SERIAL_A, [lib('pkg:npm/other@1.0.0', { 'bom-ref': X_P })]);
        const refResult = run4(['p', 'c'], [productRow(SERIAL_B), byRef], [productMeta(), componentMeta()]);
        expect(refResult.count).toBe(1);
        expect(refResult.inputs[1].components[0]['bom-ref']).toBe(Q(SERIAL_A, X_P));
        expect(refResult.inputs[1].components[0].purl).toBe('pkg:npm/other@1.0.0');

        const byPurl = componentRow(SERIAL_A, [lib(X_P, { 'bom-ref': 'other-ref' })]);
        const purlResult = run4(['p', 'c'], [productRow(SERIAL_B), byPurl], [productMeta(), componentMeta()]);
        expect(purlResult.count).toBe(1);
        expect(purlResult.inputs[1].components[0]['bom-ref']).toBe('other-ref');
        expect(purlResult.inputs[1].components[0].purl).toBe(P(X_P, SERIAL_A));
    });

    it('(d) writes apart a service under the key and re-points its references, nested services too', () => {
        const c = componentRow(SERIAL_A, [lib(LIB_A)], {
            services: [{ 'bom-ref': X_P, name: 'svc' }],
            dependencies: [{ ref: COMPONENT_ROOT, dependsOn: [LIB_A] }, { ref: X_P, dependsOn: [LIB_A] }, { ref: LIB_A, provides: [X_P] }]
        });
        const result = run4(['p', 'c'], [productRow(SERIAL_B), c], [productMeta(), componentMeta()]);

        expect(result.count).toBe(1);
        expect(result.inputs[1].services[0]['bom-ref']).toBe(Q(SERIAL_A, X_P));
        expect(result.inputs[1].dependencies).toStrictEqual([
            { ref: COMPONENT_ROOT, dependsOn: [LIB_A] }, { ref: Q(SERIAL_A, X_P), dependsOn: [LIB_A] }, { ref: LIB_A, provides: [Q(SERIAL_A, X_P)] }
        ]);

        const nested = componentRow(SERIAL_A, [lib(LIB_A)], { services: [{ 'bom-ref': 'svc-top', name: 'top', services: [{ 'bom-ref': X_P, name: 'inner-svc' }] }] });
        const nestedResult = run4(['p', 'c'], [productRow(SERIAL_B), nested], [productMeta(), componentMeta()]);
        expect(nestedResult.count).toBe(1);
        expect(nestedResult.inputs[1].services[0]['bom-ref']).toBe('svc-top');
        expect(nestedResult.inputs[1].services[0].services[0]['bom-ref']).toBe(Q(SERIAL_A, X_P));
    });

    it('(e) reserves the nested product listed root keys from its row meta, and nothing without them', () => {
        const c = componentRow(SERIAL_A, [lib(LIB_A), listing(A_P)], { services: [{ 'bom-ref': A_P, name: 'svc' }] });
        const result = run4(['p', 'c'], [productRow(SERIAL_B), c], [productMeta({ listedRootKeys: [A_P] }), componentMeta()]);
        expect(result.count).toBe(2);
        expect(result.inputs[1].components[1]['bom-ref']).toBe(Q(SERIAL_A, A_P));
        expect(result.inputs[1].components[1].purl).toBe(P(A_P, SERIAL_A));
        expect(result.inputs[1].services[0]['bom-ref']).toBe(Q(SERIAL_A, A_P));

        for (const meta of [productMeta({ listedRootKeys: undefined }), productMeta({ listedRootKeys: [] })]) {
            const both = componentRow(SERIAL_A, [listing(A_P), listing(X_P)]);
            const out = run4(['p', 'c'], [productRow(SERIAL_B), both], [meta, componentMeta()]);
            expect(out.count).toBe(1);
            expect(out.inputs[1].components[0]).toBe(both.components[0]);
            expect(out.inputs[1].components[1]['bom-ref']).toBe(Q(SERIAL_A, X_P));
        }
    });

    it('(f) never touches the merge listed input', () => {
        const listed = doc(SERIAL_L, { type: 'application', name: 'facts-product', version: '3.0.0', purl: LISTED_ROOT, 'bom-ref': LISTED_REF }, [lib(LIB_B), listing(X_P)]);
        const inputs = [listed, productRow(SERIAL_B)];
        const result = run4(['listed', 'p'], inputs, [undefined, productMeta()]);
        expect(result.count).toBe(0);
        expect(result.inputs).toBe(inputs);
    });

    describe('(g) the carried-copy exemption (ADR-5)', () => {
        const pMeta = () => productMetaOf(SERIAL_B, { listedRootKeys: [A_P] });
        const jMeta = (record: string[]) => productMetaOf(SERIAL_L, { nestedProductSerials: record });

        it('first sub-case: a product row whose record names the nested product row keeps its copies', () => {
            const j = carrier();
            const result = run4(['p', 'j', 'c'], [productRow(SERIAL_B), j, lister()], [pMeta(), jMeta([SERIAL_B]), componentMeta()]);
            expect(result.inputs[1]).toBe(j);
            expect(result.inputs[2].components[1]['bom-ref']).toBe(Q(SERIAL_A, X_P));
            expect(result.count).toBe(1);
        });

        it('second sub-case: the exemption is by row, not by key', () => {
            const p = doc(SERIAL_B, { type: 'application', name: 'inner', version: '2.0.0', purl: X_P, 'bom-ref': 'inner-ref' }, [lib(LIB_B)]);
            const j = carrier();
            const result = run4(['p', 'j', 'c'], [p, j, lister()], [pMeta(), jMeta([SERIAL_B]), componentMeta()]);
            expect(result.inputs[1]).toBe(j);
            expect(result.count).toBe(1);
        });

        it('third sub-case: a record naming nothing of the nested product row earns nothing (the forged identifier shape)', () => {
            for (const record of [[], [SERIAL_F]]) {
                const p = productRow(SERIAL_B);
                const result = run4(['p', 'j', 'c'], [p, carrier(), lister()], [pMeta(), jMeta(record), componentMeta()]);
                expectCarrierRekeyed(result.inputs[1]);
                expect(result.inputs[0]).toBe(p);
                expect(result.inputs[2].components[1]['bom-ref']).toBe(Q(SERIAL_A, X_P));
                expect(result.count).toBe(4);
            }
        });

        it('fourth sub-case: a component row is never exempt', () => {
            const meta = { ...componentMeta(), serialNumber: SERIAL_L, nestedProductSerials: [SERIAL_B] };
            const result = run4(['p', 'j', 'c'], [productRow(SERIAL_B), carrier(), lister()], [pMeta(), meta, componentMeta()]);
            expectCarrierRekeyed(result.inputs[1]);
            expect(result.count).toBe(4);
        });

        it('fifth sub-case: another product row under the nested product key denies the exemption, in both orders', () => {
            for (const record of [[SERIAL_B], [SERIAL_F]]) {
                const pp = productRow(SERIAL_F, X_P, [lib(LIB_A)]);
                const result = run4(['p', 'j', 'pp'], [productRow(SERIAL_B), carrier(), pp], [pMeta(), jMeta(record), productMetaOf(SERIAL_F)]);
                expectCarrierRekeyed(result.inputs[1]);
                expect(result.inputs[0].metadata.component['bom-ref']).toBe(Q(SERIAL_B, X_P));
                expect(result.inputs[0].metadata.component.purl).toBe(P(X_P, SERIAL_B));
                expect(result.inputs[2].metadata.component['bom-ref']).toBe(Q(SERIAL_F, X_P));
                expect(result.inputs[2].metadata.component.purl).toBe(P(X_P, SERIAL_F));
                expect(result.inputs[0].components[0]['bom-ref']).toBe(LIB_B);
                expect(result.inputs[2].components[0]['bom-ref']).toBe(LIB_A);
                expect(result.count).toBe(5);
            }
            const swapped = run4(['pp', 'j', 'p'], [productRow(SERIAL_F, X_P, [lib(LIB_A)]), carrier(), productRow(SERIAL_B)], [productMetaOf(SERIAL_F), jMeta([SERIAL_B]), pMeta()]);
            expectCarrierRekeyed(swapped.inputs[1]);
            expect(swapped.count).toBe(5);
        });

        it('sixth sub-case: a placeholder under the nested product key denies the exemption', () => {
            const result = run4(['p', 'j'], [productRow(SERIAL_B), carrier(), placeholderDoc(X_P)], [pMeta(), jMeta([SERIAL_B])]);
            expectCarrierRekeyed(result.inputs[1]);
            expect(result.inputs[0].metadata.component['bom-ref']).toBe(Q(SERIAL_B, X_P));
            expect(result.inputs[0].metadata.component.purl).toBe(P(X_P, SERIAL_B));
            expect(result.inputs[2].metadata.component['bom-ref']).toBe(PLACEHOLDER_REF);
            expect(result.inputs[2].metadata.component.purl).toBe(P(X_P, PLACEHOLDER_REF));
            expect(result.count).toBe(5);
        });

        it('seventh sub-case: a stale record (the sibling rebuilt, absent, or a key recorded) earns nothing', () => {
            const readings: { ids: string[], inputs: any[], meta: any[] }[] = [];
            const pp = productRow(SERIAL_G, 'pkg:generic/Facts%20Org/other@1.0.0', [lib(LIB_A)]);
            readings.push({ ids: ['p', 'j', 'pp'], inputs: [productRow(SERIAL_B), carrier(), pp], meta: [pMeta(), jMeta([SERIAL_F]), productMetaOf(SERIAL_G)] });
            readings.push({ ids: ['p', 'j'], inputs: [productRow(SERIAL_B), carrier()], meta: [pMeta(), jMeta([SERIAL_F])] });
            readings.push({ ids: ['p', 'j'], inputs: [productRow(SERIAL_B), carrier()], meta: [pMeta(), jMeta([X_P])] });
            for (const reading of readings) {
                const result = run4(reading.ids, reading.inputs, reading.meta);
                expectCarrierRekeyed(result.inputs[1]);
                expect(result.inputs[0]).toBe(reading.inputs[0]);
                if (reading.inputs[2]) expect(result.inputs[2]).toBe(reading.inputs[2]);
                expect(result.count).toBe(3);
            }
        });

        it('eighth sub-case: the record is compared with the row recorded serial, never the served document serial', () => {
            const meta = productMetaOf(SERIAL_F, { listedRootKeys: [A_P] });
            const j = carrier();
            const exempt = run4(['p', 'j', 'c'], [productRow(SERIAL_B), j, lister()], [meta, jMeta([SERIAL_F]), componentMeta()]);
            expect(exempt.inputs[1]).toBe(j);
            expect(exempt.count).toBe(1);

            const denied = run4(['p', 'j', 'c'], [productRow(SERIAL_B), carrier(), lister()], [meta, jMeta([SERIAL_B]), componentMeta()]);
            expectCarrierRekeyed(denied.inputs[1]);
            expect(denied.count).toBe(4);
        });
    });

    it('(h) writes apart a component root and two product roots under one key, whichever comes first', () => {
        const info = vi.spyOn(logger, 'info');
        try {
            const result = run4(['p', 'c'], [productRow(SERIAL_B), rowWithRoot(SERIAL_A, X_P)], [productMeta(), componentMeta()]);
            expect(result.count).toBe(2);
            expect(result.inputs[0].metadata.component['bom-ref']).toBe(Q(SERIAL_B, X_P));
            expect(result.inputs[1].metadata.component['bom-ref']).toBe(Q(SERIAL_A, X_P));
            const reasons = Object.fromEntries(info.mock.calls.filter(c => c[1] === REKEYED_LINE).map(c => [(c[0] as any).input, (c[0] as any).reason]));
            expect(reasons).toStrictEqual({ [SERIAL_B]: 'root collision', [SERIAL_A]: 'nested product key' });
        } finally {
            info.mockRestore();
        }
        for (const ids of [['p', 'q'], ['q', 'p']]) {
            const rows: Record<string, any> = { p: productRow(SERIAL_B), q: productRow(SERIAL_F) };
            const result = run4(ids, ids.map(id => rows[id]), [productMeta(), productMeta()]);
            expect(result.count).toBe(2);
            expect(result.inputs.map((b: any) => b.metadata.component['bom-ref']).sort()).toStrictEqual([Q(SERIAL_B, X_P), Q(SERIAL_F, X_P)].sort());
        }
    });

    it('(i) a release key takes precedence over a nested product key', () => {
        const info = vi.spyOn(logger, 'info');
        try {
            const listed = listedDoc({ type: 'application', name: 'inner', version: '2.0.0', purl: X_P, 'bom-ref': X_P });
            run4(['listed', 'p', 'c'], [listed, productRow(SERIAL_B), lister()], [undefined, productMeta(), componentMeta()], options({ releaseRootIds: ['listed'] }));
            const line = info.mock.calls.filter(c => c[1] === REKEYED_LINE).map(c => c[0] as any).find(l => l.input === SERIAL_A);
            expect(line.reason).toBe('release key');
        } finally {
            info.mockRestore();
        }
    });

    it('(j) a product row is told by its row meta alone (ADR-1)', () => {
        const inputs = () => [listedDoc(), productRow(SERIAL_B), lister()];
        const upload = inputs();
        const uploadResult = run4(['listed', 'p', 'c'], upload, [undefined, uploadMeta(), componentMeta()]);
        expect(uploadResult.inputs).toBe(upload);
        expect(uploadResult.count).toBe(0);

        expect(run4(['listed', 'p', 'c'], inputs(), [undefined, { mod: REBOM_MERGE_MOD }, componentMeta()]).count).toBe(1);
        expect(run4(['listed', 'p', 'c'], inputs(), [undefined, componentMeta(), componentMeta()]).count).toBe(0);
        expect(run(['listed', 'p', 'c'], inputs()).count).toBe(0);
        expect(run4(['listed', 'c', 'p'], [listedDoc(), lister(), productRow(SERIAL_B)], [undefined, componentMeta()]).count).toBe(0);

        const withPlaceholder = [listedDoc(), lister(), placeholderDoc(X_P)];
        const placeholderResult = run4(['listed', 'c'], withPlaceholder, [undefined, componentMeta(), productMeta()]);
        expect(placeholderResult.inputs).toBe(withPlaceholder);
        expect(placeholderResult.count).toBe(0);
    });

    it('(k) a FLATTEN merge is never touched', () => {
        const inputs = [productRow(SERIAL_B), lister()];
        const result = run4(['p', 'c'], inputs, [productMeta(), componentMeta()], options({ rootComponentMergeMode: FLATTEN }));
        expect(result.inputs).toBe(inputs);
        expect(result.count).toBe(0);
    });

    it('(l) a placeholder under a nested product key is qualified, and under the root key the product root collides', () => {
        const info = vi.spyOn(logger, 'info');
        try {
            const result = run4(['p'], [productRow(SERIAL_B), placeholderDoc(X_P)], [productMeta()]);
            expect(result.count).toBe(2);
            expect(result.inputs[1].metadata.component['bom-ref']).toBe(PLACEHOLDER_REF);
            expect(result.inputs[1].metadata.component.purl).toBe(P(X_P, PLACEHOLDER_REF));
            expect(result.inputs[0].metadata.component['bom-ref']).toBe(Q(SERIAL_B, X_P));
            expect(result.inputs[0].metadata.component.purl).toBe(P(X_P, SERIAL_B));
            const reasons = Object.fromEntries(info.mock.calls.filter(c => c[1] === REKEYED_LINE).map(c => [(c[0] as any).input, (c[0] as any).reason]));
            expect(reasons).toStrictEqual({ [SERIAL_B]: 'root collision', [PLACEHOLDER_REF]: 'nested product key' });
        } finally {
            info.mockRestore();
        }
        const p = productRow(SERIAL_B);
        const attached = run4(['p'], [p, placeholderDoc(A_P)], [productMeta({ listedRootKeys: [A_P] })]);
        expect(attached.count).toBe(1);
        expect(attached.inputs[0]).toBe(p);
        expect(attached.inputs[1].metadata.component.purl).toBe(P(A_P, PLACEHOLDER_REF));
        expect(attached.inputs[1].metadata.component['bom-ref']).toBe(PLACEHOLDER_REF);
    });

    it('(m) empty keys reserve nothing, and keys are compared exactly', () => {
        const p = doc(SERIAL_B, { type: 'application', name: 'inner', version: '2.0.0', purl: '', 'bom-ref': 'inner-ref' }, [lib(LIB_B)]);
        const c = componentRow(SERIAL_A, [{ type: 'library', name: 'empty', purl: '', 'bom-ref': 'empty-ref' }, lib(`${X_P}?x=1`)]);
        const inputs = [p, c];
        const result = run4(['p', 'c'], inputs, [productMeta({ listedRootKeys: [''] }), componentMeta()]);
        expect(result.inputs).toBe(inputs);
        expect(result.count).toBe(0);

        const exact = [productRow(SERIAL_B), componentRow(SERIAL_A, [lib(`${X_P}?x=1`)])];
        expect(run4(['p', 'c'], exact, [productMeta(), componentMeta()]).inputs).toBe(exact);
    });

    it('(n) logs the reserved count per product row, the node lines, and the exemption lines at debug only', () => {
        const info = vi.spyOn(logger, 'info');
        const debug = vi.spyOn(logger, 'debug');
        const linesOf = (spy: any, msg: string) => spy.mock.calls.filter((c: any[]) => c[1] === msg).map((c: any[]) => c[0]);
        try {
            run4(['p', 'c'], [productRow(SERIAL_B), lister()], [productMeta({ listedRootKeys: [A_P] }), componentMeta()]);
            expect(linesOf(info, NESTED_LINE)).toStrictEqual([{ input: SERIAL_B, keys: 6 }]);
            expect(linesOf(info, REKEYED_LINE)).toStrictEqual([
                { input: SERIAL_A, kind: 'component', reason: 'nested product key', from: { bomRef: X_P, purl: X_P }, to: { bomRef: Q(SERIAL_A, X_P), purl: P(X_P, SERIAL_A) } }
            ]);
            expect(info.mock.calls.some(c => c[0] === 'node re-key: 1 nodes of unlisted merge inputs re-keyed before the merge')).toBe(true);

            info.mockClear();
            debug.mockClear();
            const pMeta = productMetaOf(SERIAL_B, { listedRootKeys: [A_P] });
            run4(['p', 'j'], [productRow(SERIAL_B), carrier()], [pMeta, productMetaOf(SERIAL_L, { nestedProductSerials: [SERIAL_B] })]);
            expect(linesOf(debug, 'nested product exemption: the input merged this product row')).toStrictEqual([{ input: SERIAL_L, product: SERIAL_B, recordedSerial: SERIAL_B }]);
            expect(linesOf(info, 'nested product exemption: the input merged this product row')).toStrictEqual([]);
            expect(linesOf(info, NESTED_LINE)).toHaveLength(2);

            const denied = (ids: string[], inputs: any[], meta: any[]) => {
                debug.mockClear();
                info.mockClear();
                run4(ids, inputs, meta);
                expect(linesOf(info, DENIED_LINE)).toStrictEqual([]);
                return linesOf(debug, DENIED_LINE);
            };
            const pp = () => productRow(SERIAL_F, X_P, [lib(LIB_A)]);
            expect(denied(['p', 'j', 'pp'], [productRow(SERIAL_B), carrier(), pp()], [pMeta, productMetaOf(SERIAL_L, { nestedProductSerials: [SERIAL_B] }), productMetaOf(SERIAL_F)]))
                .toStrictEqual([{ input: SERIAL_L, product: SERIAL_B, recordedSerial: SERIAL_B, key: X_P }]);
            expect(denied(['p', 'j', 'pp'], [productRow(SERIAL_B), carrier(), pp()], [pMeta, productMetaOf(SERIAL_L, { nestedProductSerials: [SERIAL_F] }), productMetaOf(SERIAL_F)]))
                .toStrictEqual([{ input: SERIAL_L, product: SERIAL_F, recordedSerial: SERIAL_F, key: X_P }]);
            expect(denied(['p', 'j'], [productRow(SERIAL_B), carrier(), placeholderDoc(X_P)], [pMeta, productMetaOf(SERIAL_L, { nestedProductSerials: [SERIAL_B] })])).toHaveLength(1);
            expect(denied(['p', 'j'], [productRow(SERIAL_B), carrier()], [pMeta, productMetaOf(SERIAL_L, { nestedProductSerials: [SERIAL_F] })])).toStrictEqual([]);
        } finally {
            info.mockRestore();
            debug.mockRestore();
        }
    });

    it('(o) reserves the images of the nested product root and listed roots under its own base alone', () => {
        const image = componentRow(SERIAL_A, [lib(LIB_A), lib(P(X_P, SERIAL_B), { 'bom-ref': Q(SERIAL_B, X_P) })]);
        const imageResult = run4(['p', 'c'], [productRow(SERIAL_B), image], [productMeta(), componentMeta()]);
        expect(imageResult.count).toBe(1);
        expect(imageResult.inputs[1].components[1]['bom-ref']).toBe(Q(SERIAL_A, Q(SERIAL_B, X_P)));
        expect(imageResult.inputs[1].components[1].purl).toBe(P(X_P, SERIAL_A));

        const listedImage = componentRow(SERIAL_A, [lib(P(A_P, SERIAL_B), { 'bom-ref': Q(SERIAL_B, A_P) })]);
        expect(run4(['p', 'c'], [productRow(SERIAL_B), listedImage], [productMeta({ listedRootKeys: [A_P] }), componentMeta()]).count).toBe(1);

        const otherBases = componentRow(SERIAL_A, [lib(P(X_P, 'other'), { 'bom-ref': Q('other', X_P) }), lib(P(X_P, SERIAL_A), { 'bom-ref': Q(SERIAL_A, X_P) })]);
        const otherResult = run4(['p', 'c'], [productRow(SERIAL_B), otherBases], [productMeta(), componentMeta()]);
        expect(otherResult.inputs[1]).toBe(otherBases);
        expect(otherResult.count).toBe(0);

        const p = productRow(SERIAL_B);
        const j = productRow(SERIAL_L, OUTER, [lib(P(X_P, SERIAL_B), { 'bom-ref': Q(SERIAL_B, X_P) })]);
        const carried = run4(['p', 'j'], [p, j], [productMetaOf(SERIAL_B), productMetaOf(SERIAL_L, { nestedProductSerials: [SERIAL_B] })]);
        expect(carried.inputs[0]).toBe(p);
        expect(carried.inputs[1]).toBe(j);
        expect(carried.count).toBe(0);

        const noSerial = componentRow(SERIAL_A, [lib(P(X_P, X_P), { 'bom-ref': Q(X_P, X_P) })]);
        expect(run4(['p', 'c'], [productRow(undefined), noSerial], [productMeta(), componentMeta()]).count).toBe(1);
    });
});

describe('recordRekeyedNodes', () => {
    const bom = () => ({ bomFormat: 'CycloneDX', metadata: { properties: [{ name: BOM_REFS_DEDUPLICATED_PROPERTY, value: '1' }] } });

    it('writes the count above 0', () => {
        const input = bom();
        const out = recordRekeyedNodes(input, 2);

        expect(out.metadata.properties).toStrictEqual([{ name: BOM_REFS_DEDUPLICATED_PROPERTY, value: '1' }, { name: NODES_REKEYED_PROPERTY, value: '2' }]);
        expect(input).toStrictEqual(bom());
    });

    it('replaces a value already there', () => {
        const input = bom();
        input.metadata.properties.push({ name: NODES_REKEYED_PROPERTY, value: '9' });

        expect(recordRekeyedNodes(input, 1).metadata.properties).toStrictEqual([{ name: BOM_REFS_DEDUPLICATED_PROPERTY, value: '1' }, { name: NODES_REKEYED_PROPERTY, value: '1' }]);
    });

    it('returns the same document at 0', () => {
        const input = bom();
        expect(recordRekeyedNodes(input, 0)).toBe(input);
    });
});
