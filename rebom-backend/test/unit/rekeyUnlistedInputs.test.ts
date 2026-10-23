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
