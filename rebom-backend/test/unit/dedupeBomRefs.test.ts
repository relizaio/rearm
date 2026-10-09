import { describe, it, expect } from 'vitest';
import {
    dedupeBomRefs,
    BOM_REFS_DEDUPLICATED_PROPERTY
} from '../../src/services/bom/bomProcessingService';

/**
 * SCORE-13: a merged BOM folds the components that share one bom-ref into the first
 * occurrence, keeps every field and child of the copies, and repairs the reference lists.
 */

const X = 'pkg:maven/io.example/app@1.0.0?type=jar';
const SUPPLIER = { name: 'Example Supplier' };
const LODASH = 'pkg:npm/lodash@4.17.21';

function doc(components: any[], extra: any = {}): any {
    return {
        bomFormat: 'CycloneDX',
        specVersion: '1.6',
        serialNumber: 'urn:uuid:0b6c3f2e-6f4a-4d43-9a3c-7f1c2d9e8a10',
        version: 1,
        metadata: {
            component: { type: 'application', name: 'root', 'bom-ref': 'ROOT' },
            properties: [{ name: 'existing', value: 'kept' }]
        },
        components,
        ...extra
    };
}

function lib(ref: string, extra: any = {}): any {
    return { type: 'library', name: ref.toLowerCase(), version: '1', 'bom-ref': ref, ...extra };
}

function marker(bom: any): any[] {
    return (bom.metadata.properties || []).filter((p: any) => p.name === BOM_REFS_DEDUPLICATED_PROPERTY);
}

function allRefs(bom: any): string[] {
    const walk = (list: any[] = []): string[] => list.flatMap((c: any) => [c['bom-ref'], ...walk(c.components)]);
    return [bom.metadata.component?.['bom-ref'], ...walk(bom.metadata.component?.components), ...walk(bom.components)]
        .filter((r: any) => r !== undefined);
}

describe('dedupeBomRefs', () => {
    it('folds a component nested inside itself into the top-level copy, keeping every field (T-2)', () => {
        const input = doc([
            lib('A'),
            {
                type: 'library', name: 'app', version: '1.0.0', purl: X, 'bom-ref': X,
                supplier: SUPPLIER,
                licenses: [{ license: { id: 'Apache-2.0' } }],
                properties: [{ name: 'origin', value: 'image' }],
                components: [
                    {
                        type: 'library', name: 'app', version: '1.0.0', purl: X, 'bom-ref': X,
                        licenses: [{ license: { id: 'MIT' } }],
                        properties: [{ name: 'origin', value: 'source' }],
                        components: [lib('C1'), lib('C2')]
                    }
                ]
            },
            lib('B')
        ]);
        const before = structuredClone(input);

        const { bom, count } = dedupeBomRefs(input);

        expect(count).toBe(1);
        expect(bom.components).toStrictEqual([
            lib('A'),
            {
                type: 'library', name: 'app', version: '1.0.0', purl: X, 'bom-ref': X,
                supplier: SUPPLIER,
                licenses: [{ license: { id: 'Apache-2.0' } }, { license: { id: 'MIT' } }],
                properties: [{ name: 'origin', value: 'image' }, { name: 'origin', value: 'source' }],
                components: [lib('C1'), lib('C2')]
            },
            lib('B')
        ]);
        // The name written out on purpose: what ReARM exports and the tester greps for.
        expect(bom.metadata.properties).toStrictEqual([
            { name: 'existing', value: 'kept' },
            { name: 'reliza:export:bomRefsDeduplicated', value: '1' }
        ]);
        expect(allRefs(bom)).toStrictEqual(['ROOT', 'A', X, 'C1', 'C2', 'B']);
        expect(input).toStrictEqual(before);
    });

    it('keeps the first copy in walk order across inputs at different depths (T-3)', () => {
        // FLAT merge of input 1 (top-level Y) and input 2 (Y nested under Z).
        const input = doc([
            lib('Y', { description: 'from input one' }),
            lib('W'),
            lib('Z', { components: [lib('Z1'), lib('Y', { description: 'from input two' }), lib('Z2')] })
        ]);

        const { bom, count } = dedupeBomRefs(input);

        expect(count).toBe(1);
        expect(bom.components).toStrictEqual([
            lib('Y', { description: 'from input one' }),
            lib('W'),
            lib('Z', { components: [lib('Z1'), lib('Z2')] })
        ]);
        expect(marker(bom)).toStrictEqual([{ name: BOM_REFS_DEDUPLICATED_PROPERTY, value: '1' }]);
    });

    it('keys on the bom-ref, not the purl: one purl under different bom-refs stays, one bom-ref across purls folds (ADR-2)', () => {
        // merge-boms collapses equal purls only at the top level: nested components and the
        // subtrees HIERARCHICAL hangs under each root keep their own bom-refs.
        const input = doc([
            lib('lodash-a', { purl: LODASH }),
            lib('X', {
                purl: 'pkg:npm/x@1',
                components: [
                    lib('lodash-b', { purl: LODASH }),
                    lib('X', { purl: 'pkg:npm/x-copy@1', description: 'copy' }),
                    lib('Y')
                ]
            }),
            lib('lodash-c', { purl: LODASH })
        ]);

        const { bom, count } = dedupeBomRefs(input);

        expect(count).toBe(1);
        expect(bom.components).toStrictEqual([
            lib('lodash-a', { purl: LODASH }),
            lib('X', {
                purl: 'pkg:npm/x@1',
                components: [lib('lodash-b', { purl: LODASH }), lib('Y')],
                description: 'copy'
            }),
            lib('lodash-c', { purl: LODASH })
        ]);
        expect(allRefs(bom)).toStrictEqual(['ROOT', 'lodash-a', 'X', 'lodash-b', 'Y', 'lodash-c']);
    });

    it('keeps bom-refs that differ only in case (ADR-2)', () => {
        const upper = 'pkg:npm/Foo@1';
        const lower = 'pkg:npm/foo@1';
        const input = doc([
            lib(upper, { name: 'Foo' }),
            lib('B'),
            lib('A', { components: [lib(lower, { name: 'foo' }), lib('B'), lib('C')] })
        ]);

        const { bom, count } = dedupeBomRefs(input);

        expect(count).toBe(1);
        expect(bom.components).toStrictEqual([
            lib(upper, { name: 'Foo' }),
            lib('B'),
            lib('A', { components: [lib(lower, { name: 'foo' }), lib('C')] })
        ]);
        expect(allRefs(bom)).toStrictEqual(['ROOT', upper, 'B', 'A', lower, 'C']);
    });

    it('collapses a component equal to metadata.component into the root (T-4)', () => {
        const input = doc([
            lib('A'),
            {
                type: 'library', name: 'other-name', 'bom-ref': 'ROOT', version: '9',
                description: 'copy', supplier: SUPPLIER,
                components: [lib('R1'), lib('R2')]
            },
            lib('B')
        ]);
        input.metadata.component.components = [lib('K1'), lib('K2')];

        const { bom, count } = dedupeBomRefs(input);

        expect(count).toBe(1);
        expect(bom.metadata.component).toStrictEqual({
            type: 'application', name: 'root', 'bom-ref': 'ROOT',
            components: [lib('K1'), lib('K2'), lib('R1'), lib('R2')],
            version: '9', description: 'copy', supplier: SUPPLIER
        });
        expect(bom.components).toStrictEqual([lib('A'), lib('B')]);
    });

    it('repairs the reference lists in order and leaves untouched lists the same object (T-5)', () => {
        const dependencies = [
            { ref: 'ROOT', dependsOn: ['A', X] },
            { ref: X, dependsOn: ['A', 'B'], provides: ['P1', 'P2'] },
            { ref: 'A', dependsOn: ['B', 'C'] },
            { ref: X, dependsOn: ['B', 'C'], provides: ['P2', 'P3'] }
        ];
        const untouchedComposition = { aggregate: 'complete', assemblies: ['A', 'B'] };
        const compositions = [
            { aggregate: 'incomplete', assemblies: [X, X, 'A', 'B'], dependencies: ['B', X, 'B', 'C'] },
            untouchedComposition
        ];
        const untouchedVuln = { id: 'CVE-2', affects: [{ ref: 'A' }, { ref: 'B' }] };
        const vulnerabilities = [
            {
                id: 'CVE-1',
                affects: [
                    { ref: X, versions: [{ version: '1.0.0', status: 'affected' }, { range: 'vers:maven/<2', status: 'affected' }] },
                    { ref: 'A' },
                    { ref: X, versions: [{ version: '1.0.0', status: 'affected' }, { version: '1.0.1', status: 'unaffected' }] }
                ]
            },
            untouchedVuln
        ];
        const untouchedAnnotation = { subjects: ['A', 'B'], text: 'two' };
        const annotations = [{ subjects: [X, 'A', X, 'B'], text: 'one' }, untouchedAnnotation];
        const input = doc([lib(X), lib('A'), lib('B', { components: [lib(X)] }), lib('C')],
            { dependencies, compositions, vulnerabilities, annotations });

        const { bom, count } = dedupeBomRefs(input);

        expect(count).toBe(1);
        expect(bom.dependencies).toStrictEqual([
            { ref: 'ROOT', dependsOn: ['A', X] },
            { ref: X, dependsOn: ['A', 'B', 'C'], provides: ['P1', 'P2', 'P3'] },
            { ref: 'A', dependsOn: ['B', 'C'] }
        ]);
        expect(bom.dependencies[0]).toBe(dependencies[0]);
        expect(bom.dependencies[2]).toBe(dependencies[2]);
        expect(bom.compositions).toStrictEqual([
            { aggregate: 'incomplete', assemblies: [X, 'A', 'B'], dependencies: ['B', X, 'C'] },
            untouchedComposition
        ]);
        expect(bom.compositions[1]).toBe(untouchedComposition);
        expect(bom.vulnerabilities).toStrictEqual([
            {
                id: 'CVE-1',
                affects: [
                    {
                        ref: X,
                        versions: [
                            { version: '1.0.0', status: 'affected' },
                            { range: 'vers:maven/<2', status: 'affected' },
                            { version: '1.0.1', status: 'unaffected' }
                        ]
                    },
                    { ref: 'A' }
                ]
            },
            untouchedVuln
        ]);
        expect(bom.vulnerabilities[1]).toBe(untouchedVuln);
        expect(bom.annotations).toStrictEqual([{ subjects: [X, 'A', 'B'], text: 'one' }, untouchedAnnotation]);
        expect(bom.annotations[1]).toBe(untouchedAnnotation);
    });

    it('leaves every reference list the same object when it has nothing to repair (T-5)', () => {
        const dependencies = [{ ref: X, dependsOn: ['A', 'B'] }, { ref: 'A', dependsOn: ['B'] }];
        const compositions = [{ aggregate: 'complete', assemblies: [X, 'A'] }, { aggregate: 'unknown', assemblies: ['B'] }];
        const vulnerabilities = [{ id: 'CVE-1', affects: [{ ref: X }, { ref: 'A' }] }, { id: 'CVE-2' }];
        const annotations = [{ subjects: [X, 'A'], text: 'one' }, { subjects: ['B'], text: 'two' }];
        const input = doc([lib(X), lib('A', { components: [lib(X)] }), lib('B')],
            { dependencies, compositions, vulnerabilities, annotations });

        const { bom, count } = dedupeBomRefs(input);

        expect(count).toBe(1);
        expect(bom.dependencies).toBe(dependencies);
        expect(bom.compositions).toBe(compositions);
        expect(bom.vulnerabilities).toBe(vulnerabilities);
        expect(bom.annotations).toBe(annotations);
    });

    it('returns the document unchanged, with no property, when no bom-ref repeats (T-6)', () => {
        const input = doc([
            lib('A', { components: [lib('A1'), lib('A2')] }),
            { type: 'library', name: 'no-ref-one' },
            { type: 'library', name: 'no-ref-one' },
            lib('B')
        ], { dependencies: [{ ref: 'A', dependsOn: ['B'] }, { ref: 'B', dependsOn: [] }] });
        const before = structuredClone(input);

        const { bom, count } = dedupeBomRefs(input);

        expect(count).toBe(0);
        expect(bom).toBe(input);
        expect(bom).toStrictEqual(before);
        expect(marker(bom)).toStrictEqual([]);
    });

    it('fills empty survivor fields, never overwrites set ones, and unions lists survivor first (T-7)', () => {
        const survivor = {
            type: 'library', name: 'app', 'bom-ref': X,
            version: '1.0.0',
            description: '',
            group: 'io.example',
            supplier: SUPPLIER,
            authors: [],
            hashes: [{ alg: 'SHA-256', content: 'aaa' }, { alg: 'MD5', content: 'bbb' }],
            externalReferences: [{ type: 'vcs', url: 'https://example.com/a' }, { type: 'website', url: 'https://example.com' }],
            properties: [{ name: 'p', value: '1' }, { name: 'q', value: '1' }],
            tags: ['t1', 't2'],
            licenses: [{ expression: 'Apache-2.0 OR MIT' }, { license: { name: 'Custom' } }]
        };
        const duplicate = {
            type: 'framework', name: 'other', 'bom-ref': X,
            version: '2.0.0',
            description: 'from the copy',
            group: 'io.other',
            supplier: { name: 'Other Supplier' },
            publisher: 'Example Publisher',
            authors: [{ name: 'Author One' }, { name: 'Author Two' }],
            cpe: 'cpe:2.3:a:example:app:1.0.0:*:*:*:*:*:*:*',
            scope: 'required',
            hashes: [{ alg: 'SHA-256', content: 'zzz' }, { alg: 'SHA-1', content: 'ccc' }, { alg: 'SHA-512', content: 'ddd' }],
            externalReferences: [{ type: 'vcs', url: 'https://example.com/a' }, { type: 'vcs', url: 'https://example.com/b' }, { type: 'issue-tracker', url: 'https://example.com/i' }],
            properties: [{ name: 'p', value: '2' }, { name: 'q', value: '1' }, { name: 'r', value: '1' }],
            tags: ['t2', 't3', 't4'],
            licenses: [{ license: { id: 'MIT' } }, { expression: 'Apache-2.0 OR MIT' }, { license: { name: 'Custom' } }, { license: { id: 'BSD-3-Clause' } }]
        };
        const input = doc([survivor, lib('A', { components: [duplicate] }), lib('B')]);

        const { bom } = dedupeBomRefs(input);

        expect(bom.components[0]).toStrictEqual({
            type: 'library', name: 'app', 'bom-ref': X,
            version: '1.0.0',
            description: 'from the copy',
            group: 'io.example',
            supplier: SUPPLIER,
            authors: [{ name: 'Author One' }, { name: 'Author Two' }],
            hashes: [{ alg: 'SHA-256', content: 'aaa' }, { alg: 'MD5', content: 'bbb' }, { alg: 'SHA-1', content: 'ccc' }, { alg: 'SHA-512', content: 'ddd' }],
            externalReferences: [
                { type: 'vcs', url: 'https://example.com/a' },
                { type: 'website', url: 'https://example.com' },
                { type: 'vcs', url: 'https://example.com/b' },
                { type: 'issue-tracker', url: 'https://example.com/i' }
            ],
            properties: [{ name: 'p', value: '1' }, { name: 'q', value: '1' }, { name: 'p', value: '2' }, { name: 'r', value: '1' }],
            tags: ['t1', 't2', 't3', 't4'],
            licenses: [
                { expression: 'Apache-2.0 OR MIT' },
                { license: { name: 'Custom' } },
                { license: { id: 'MIT' } },
                { license: { id: 'BSD-3-Clause' } }
            ],
            publisher: 'Example Publisher',
            cpe: 'cpe:2.3:a:example:app:1.0.0:*:*:*:*:*:*:*',
            scope: 'required'
        });
        expect(bom.components.slice(1)).toStrictEqual([lib('A', { components: [] }), lib('B')]);
    });

    it('unions externalReferences by type and url together, so one url under two types stays twice (ADR-4)', () => {
        // The common case: a website and a vcs reference both pointing at the repository.
        const repo = 'https://github.com/example/app';
        const input = doc([
            lib(X, { externalReferences: [{ type: 'website', url: repo }, { type: 'vcs', url: 'https://example.com/a' }] }),
            lib('A', {
                components: [
                    lib(X, {
                        externalReferences: [
                            { type: 'vcs', url: repo },
                            { type: 'website', url: repo },
                            { type: 'website', url: 'https://example.com/a' }
                        ]
                    }),
                    lib('A1')
                ]
            }),
            lib('B')
        ]);

        const { bom, count } = dedupeBomRefs(input);

        expect(count).toBe(1);
        expect(bom.components).toStrictEqual([
            lib(X, {
                externalReferences: [
                    { type: 'website', url: repo },
                    { type: 'vcs', url: 'https://example.com/a' },
                    { type: 'vcs', url: repo },
                    { type: 'website', url: 'https://example.com/a' }
                ]
            }),
            lib('A', { components: [lib('A1')] }),
            lib('B')
        ]);
    });

    it('finds a duplicate nested inside a duplicate (T-8)', () => {
        const input = doc([
            lib('A'),
            lib(X, { components: [lib(X, { components: [lib(X, { components: [lib('C1'), lib('C2')] })] })] }),
            lib('B')
        ]);

        const { bom, count } = dedupeBomRefs(input);

        expect(count).toBe(2);
        expect(bom.components).toStrictEqual([lib('A'), lib(X, { components: [lib('C1'), lib('C2')] }), lib('B')]);
        expect(marker(bom)).toStrictEqual([{ name: BOM_REFS_DEDUPLICATED_PROPERTY, value: '2' }]);
    });

    it('hoists the children of a root merge-boms nested under itself after its own children (T-10)', () => {
        // HIERARCHICAL: merge-boms hangs each input's top-level components under a copy of
        // that input's root, and here the input's top level already held its root.
        const input = doc([
            lib('R', {
                components: [
                    lib('R', { components: [lib('R1'), lib('R2')] }),
                    lib('A'),
                    lib('B')
                ]
            }),
            lib('S', { components: [lib('S1'), lib('S2')] })
        ]);

        const { bom, count } = dedupeBomRefs(input);

        expect(count).toBe(1);
        expect(bom.components).toStrictEqual([
            lib('R', { components: [lib('A'), lib('B'), lib('R1'), lib('R2')] }),
            lib('S', { components: [lib('S1'), lib('S2')] })
        ]);
        expect(allRefs(bom)).toStrictEqual(['ROOT', 'R', 'A', 'B', 'R1', 'R2', 'S', 'S1', 'S2']);
    });

    it('replaces a count already in the document rather than adding a second one', () => {
        const input = doc([lib('A'), lib('B', { components: [lib('A')] })]);
        input.metadata.properties.push({ name: BOM_REFS_DEDUPLICATED_PROPERTY, value: '7' });

        const { bom } = dedupeBomRefs(input);

        expect(bom.metadata.properties).toStrictEqual([
            { name: 'existing', value: 'kept' },
            { name: BOM_REFS_DEDUPLICATED_PROPERTY, value: '1' }
        ]);
    });
});
