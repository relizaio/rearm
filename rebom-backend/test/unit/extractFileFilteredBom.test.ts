import { describe, it, expect } from 'vitest';
import {
    extractFileFilteredBom,
    fileComponentsExcludedOf,
    FILE_COMPONENTS_EXCLUDED_PROPERTY
} from '../../src/services/bom/bomProcessingService';

/**
 * SCORE-11: leaving components of type `file` out of a merged BOM, with the graph,
 * compositions, vulnerabilities and annotations repaired, and the count recorded.
 */

const ROOT = 'root-ref';

function graphBom(): any {
    return {
        bomFormat: 'CycloneDX',
        specVersion: '1.6',
        serialNumber: 'urn:uuid:3e671687-395b-41f5-a30f-a58921a69b79',
        version: 1,
        metadata: {
            component: { type: 'application', name: 'root', 'bom-ref': ROOT }
        },
        components: [
            { type: 'library', name: 'a', version: '1', 'bom-ref': 'A' },
            { type: 'file', name: 'f1', 'bom-ref': 'F1' },
            { type: 'library', name: 'b', version: '1', 'bom-ref': 'B' },
            { type: 'file', name: 'f2', 'bom-ref': 'F2' },
            { type: 'library', name: 'c', version: '1', 'bom-ref': 'C' },
            {
                type: 'library', name: 'p', version: '1', 'bom-ref': 'P',
                components: [
                    { type: 'file', name: 'f3', 'bom-ref': 'F3' },
                    { type: 'library', name: 'n', version: '1', 'bom-ref': 'N' }
                ]
            },
            { type: 'data', name: 'd', 'bom-ref': 'D' },
            { type: 'operating-system', name: 'os', version: '12', 'bom-ref': 'OS' }
        ],
        dependencies: [
            { ref: ROOT, dependsOn: ['A', 'F2', 'P'] },
            { ref: 'A', dependsOn: ['F1'] },
            { ref: 'F1', dependsOn: ['B'] },
            { ref: 'F2', dependsOn: [] },
            { ref: 'B', dependsOn: [] },
            { ref: 'C', dependsOn: ['D'] },
            { ref: 'P', dependsOn: ['F3', 'N'] }
        ]
    };
}

function refsOf(components: any[]): string[] {
    const out: string[] = [];
    for (const c of components) {
        out.push(c['bom-ref']);
        if (Array.isArray(c.components)) out.push(...refsOf(c.components));
    }
    return out;
}

function depOf(bom: any, ref: string): any {
    return bom.dependencies.find((d: any) => d.ref === ref);
}

function excludedProps(bom: any): any[] {
    return (bom.metadata.properties || []).filter((p: any) => p.name === FILE_COMPONENTS_EXCLUDED_PROPERTY);
}

describe('extractFileFilteredBom', () => {
    describe('components and dependencies (T-1)', () => {
        it('drops every file component, nested ones included, and keeps the rest in order', () => {
            const { bom, excludedCount } = extractFileFilteredBom(graphBom());

            expect(excludedCount).toBe(3);
            expect(bom.components.map((c: any) => c['bom-ref'])).toEqual(['A', 'B', 'C', 'P', 'D', 'OS']);
            expect(bom.components.find((c: any) => c['bom-ref'] === 'P').components.map((c: any) => c['bom-ref'])).toEqual(['N']);
            expect(refsOf(bom.components)).not.toContain('F1');
            expect(refsOf(bom.components)).not.toContain('F3');
        });

        it('splices a dropped ref out of dependsOn, keeping code reached only through a file reachable', () => {
            const { bom } = extractFileFilteredBom(graphBom());

            expect(depOf(bom, 'A').dependsOn).toEqual(['B']);
            expect(depOf(bom, ROOT).dependsOn).toEqual(['A', 'P']);
            expect(depOf(bom, 'P').dependsOn).toEqual(['N']);
            for (const dropped of ['F1', 'F2', 'F3']) {
                expect(depOf(bom, dropped)).toBeUndefined();
            }
        });

        it('leaves untouched an entry that names no file', () => {
            const input = graphBom();
            const { bom } = extractFileFilteredBom(input);

            expect(depOf(bom, 'C')).toEqual({ ref: 'C', dependsOn: ['D'] });
            expect(depOf(bom, 'B')).toEqual({ ref: 'B', dependsOn: [] });
        });

        it('keeps an entry whose dependsOn the filter emptied', () => {
            const input = graphBom();
            input.dependencies.push({ ref: 'D', dependsOn: ['F2'] });
            const { bom } = extractFileFilteredBom(input);

            expect(depOf(bom, 'D')).toEqual({ ref: 'D', dependsOn: [] });
        });

        it('terminates on a cycle through files and splices what the cycle reaches', () => {
            const input = graphBom();
            depOf(input, 'F2').dependsOn = ['F1'];
            depOf(input, 'F1').dependsOn = ['F2', 'B'];
            const { bom } = extractFileFilteredBom(input);

            expect(depOf(bom, ROOT).dependsOn).toEqual(['A', 'B', 'P']);
            expect(depOf(bom, 'A').dependsOn).toEqual(['B']);
        });

        it('never splices an entry onto itself', () => {
            const input = graphBom();
            depOf(input, 'F1').dependsOn = ['A', 'B'];
            const { bom } = extractFileFilteredBom(input);

            expect(depOf(bom, 'A').dependsOn).toEqual(['B']);
        });

        it('filters provides without splicing', () => {
            const input = graphBom();
            depOf(input, 'A').provides = ['F1', 'B'];
            const { bom } = extractFileFilteredBom(input);

            expect(depOf(bom, 'A').provides).toEqual(['B']);
        });

        it('lifts a non-file component nested under a dropped file into its place', () => {
            const input = graphBom();
            input.components[1].components = [{ type: 'library', name: 'inner', version: '1', 'bom-ref': 'I' }];
            const { bom, excludedCount } = extractFileFilteredBom(input);

            expect(excludedCount).toBe(3);
            expect(bom.components.map((c: any) => c['bom-ref'])).toEqual(['A', 'I', 'B', 'C', 'P', 'D', 'OS']);
        });

        it('does not mutate its input', () => {
            const input = graphBom();
            const before = JSON.stringify(input);
            extractFileFilteredBom(input);

            expect(JSON.stringify(input)).toBe(before);
        });
    });

    describe('what counts as a file (ADR-3)', () => {
        it('drops type file exactly, and keeps an absent, unknown or other-case type', () => {
            const input = graphBom();
            input.components.push(
                { name: 'untyped', version: '1', 'bom-ref': 'U' },
                { type: 'File', name: 'upper', 'bom-ref': 'FU' },
                { type: 'FILE', name: 'caps', 'bom-ref': 'FC' },
                { type: 'files', name: 'plural', 'bom-ref': 'FP' },
                { type: 'not-a-cyclonedx-type', name: 'unknown', 'bom-ref': 'X' }
            );
            input.dependencies.push({ ref: 'C', dependsOn: ['U', 'FU', 'FC', 'FP', 'X'] });
            const { bom, excludedCount } = extractFileFilteredBom(input);

            expect(excludedCount).toBe(3);
            expect(bom.components.map((c: any) => c['bom-ref']))
                .toEqual(['A', 'B', 'C', 'P', 'D', 'OS', 'U', 'FU', 'FC', 'FP', 'X']);
            expect(bom.dependencies.filter((d: any) => d.ref === 'C').pop().dependsOn)
                .toEqual(['U', 'FU', 'FC', 'FP', 'X']);
        });
    });

    describe('what is kept is kept as it was (ADR-4)', () => {
        function richBom(): any {
            const bom = graphBom();
            for (const c of [...bom.components, bom.components[5].components[1]]) {
                c.purl = `pkg:generic/${c.name}@1`;
                c.licenses = [{ license: { id: 'MIT' } }];
                c.properties = [{ name: 'cdx:x', value: c.name }];
                c.hashes = [{ alg: 'SHA-256', content: 'a'.repeat(64) }];
            }
            return bom;
        }

        it('keeps every field of every kept component, nested ones included', () => {
            const input = richBom();
            const expected = JSON.parse(JSON.stringify(input.components.filter((c: any) => c.type !== 'file')));
            expected.find((c: any) => c['bom-ref'] === 'P').components = [JSON.parse(JSON.stringify(input.components[5].components[1]))];
            const { bom } = extractFileFilteredBom(input);

            expect(bom.components).toStrictEqual(expected);
        });

        it('keeps every field of a component lifted out of a dropped file, nested ones included (T-7)', () => {
            const rich = (name: string, ref: string, extra: any = {}): any => ({
                type: 'library', name, version: '1', 'bom-ref': ref,
                purl: `pkg:generic/${name}@1`,
                licenses: [{ license: { id: 'MIT' } }],
                properties: [{ name: 'cdx:x', value: name }],
                hashes: [{ alg: 'SHA-256', content: 'b'.repeat(64) }],
                ...extra
            });
            const input = richBom();
            // F1 (top level) holds a library that holds a library; F3 (under P) holds a file
            // that holds a library, so that one is lifted through two dropped files.
            input.components[1].components = [rich('inner', 'I', { components: [rich('innermost', 'II')] })];
            input.components[5].components[0].components = [
                { type: 'file', name: 'f5', 'bom-ref': 'F5', components: [rich('deep', 'DP')] }
            ];
            const liftedFromF1 = JSON.parse(JSON.stringify(input.components[1].components[0]));
            const liftedFromF5 = JSON.parse(JSON.stringify(input.components[5].components[0].components[0].components[0]));
            const { bom, excludedCount } = extractFileFilteredBom(input);

            expect(excludedCount).toBe(4);
            expect(bom.components[1]).toStrictEqual(liftedFromF1);
            expect(bom.components.find((c: any) => c['bom-ref'] === 'P').components[0]).toStrictEqual(liftedFromF5);
        });

        it('leaves a document without file components as it was, apart from the count', () => {
            const input = richBom();
            input.components = input.components.filter((c: any) => c.type !== 'file');
            input.components.find((c: any) => c['bom-ref'] === 'P').components.splice(0, 1);
            input.dependencies = [
                { ref: ROOT, dependsOn: ['A', 'P'] },
                { ref: 'A', dependsOn: ['B'], provides: ['C'] },
                { ref: 'B', dependsOn: [] },
                { ref: 'C', dependsOn: ['D'] },
                { ref: 'P', dependsOn: ['N'] }
            ];
            input.compositions = [{ aggregate: 'complete', assemblies: ['A'], dependencies: [ROOT] }];
            input.vulnerabilities = [{ id: 'V-1', affects: [{ ref: 'A' }] }];
            const before = JSON.parse(JSON.stringify(input));
            const { bom, excludedCount } = extractFileFilteredBom(input);

            expect(excludedCount).toBe(0);
            const { metadata, ...rest } = bom;
            const { metadata: metadataBefore, ...restBefore } = before;
            expect(rest).toStrictEqual(restBefore);
            expect(metadata).toStrictEqual({
                ...metadataBefore,
                properties: [{ name: 'reliza:export:fileComponentsExcluded', value: '0' }]
            });
        });

        it('de-duplicates what two files splice into one entry, first-seen order', () => {
            const input = graphBom();
            input.components.push({ type: 'file', name: 'f4', 'bom-ref': 'F4' });
            depOf(input, 'F1').dependsOn = ['B', 'C'];
            input.dependencies.push({ ref: 'F4', dependsOn: ['B'] });
            input.dependencies.push({ ref: 'X', dependsOn: ['F1', 'C', 'F4', 'B'] });
            const { bom } = extractFileFilteredBom(input);

            expect(depOf(bom, 'X').dependsOn).toEqual(['B', 'C']);
            expect(depOf(bom, 'A').dependsOn).toEqual(['B', 'C']);
        });
    });

    describe('what is kept keeps its order (ADR-4, T-8)', () => {
        const lib = (ref: string, components?: any[]): any =>
            components ? { type: 'library', name: ref, 'bom-ref': ref, components } : { type: 'library', name: ref, 'bom-ref': ref };
        const file = (ref: string, components?: any[]): any =>
            components ? { type: 'file', name: ref, 'bom-ref': ref, components } : { type: 'file', name: ref, 'bom-ref': ref };
        // The component tree as refs: a leaf is its ref, a parent is [ref, children].
        const shape = (components: any[]): any[] =>
            components.map((c: any) => Array.isArray(c.components) ? [c['bom-ref'], shape(c.components)] : c['bom-ref']);

        it('lifts several components out of one file, and out of files nested in it, in their own order', () => {
            const input = graphBom();
            input.components = [
                lib('A'),
                // one dropped file holding a run of components, a nested dropped file, and
                // a kept parent whose own children include another dropped file
                file('F1', [
                    lib('X1'),
                    lib('X2'),
                    file('G1', [lib('Y1'), lib('Y2'), lib('Y3')]),
                    lib('X3'),
                    lib('K', [lib('K1'), file('G2', [lib('Z1'), lib('Z2')]), lib('K2')]),
                    file('G3')
                ]),
                lib('B'),
                // two dropped files in a row, then an empty one
                file('F2', [lib('U1'), lib('U2')]),
                file('F3', [lib('V1'), lib('V2')]),
                file('F4', []),
                lib('C')
            ];
            const { bom, excludedCount } = extractFileFilteredBom(input);

            expect(excludedCount).toBe(7);
            expect(shape(bom.components)).toStrictEqual([
                'A',
                'X1', 'X2', 'Y1', 'Y2', 'Y3', 'X3',
                ['K', ['K1', 'Z1', 'Z2', 'K2']],
                'B',
                'U1', 'U2', 'V1', 'V2',
                'C'
            ]);
        });

        it('keeps the order of every list it repairs, and splices in place, depth first', () => {
            const input = graphBom();
            // F1 stands for F2's targets, then B; X splices F1 between N and P.
            depOf(input, 'F1').dependsOn = ['F2', 'B'];
            depOf(input, 'F2').dependsOn = ['C', 'D'];
            input.dependencies.push({ ref: 'X', dependsOn: ['N', 'F1', 'P'], provides: ['C', 'F1', 'B', 'A'] });
            input.compositions = [
                { aggregate: 'complete', assemblies: ['C', 'F1', 'A', 'B'], dependencies: ['P', 'F2', ROOT, 'A'] },
                { aggregate: 'incomplete', assemblies: ['B', 'A'] }
            ];
            input.vulnerabilities = [
                { id: 'V-2', affects: [{ ref: 'C' }, { ref: 'F3' }, { ref: 'A' }, { ref: 'B' }] },
                { id: 'V-1', affects: [{ ref: 'F1' }] },
                { id: 'V-3', affects: [{ ref: 'B' }, { ref: 'A' }] }
            ];
            const note = (text: string, subjects: string[]): any =>
                ({ subjects, annotator: { organization: { name: 'x' } }, timestamp: '2026-10-07T00:00:00Z', text });
            input.annotations = [note('two', ['C', 'F2', 'A']), note('gone', ['F1']), note('one', ['B', 'A'])];
            const { bom } = extractFileFilteredBom(input);

            expect(bom.dependencies).toStrictEqual([
                { ref: ROOT, dependsOn: ['A', 'C', 'D', 'P'] },
                { ref: 'A', dependsOn: ['C', 'D', 'B'] },
                { ref: 'B', dependsOn: [] },
                { ref: 'C', dependsOn: ['D'] },
                { ref: 'P', dependsOn: ['N'] },
                { ref: 'X', dependsOn: ['N', 'C', 'D', 'B', 'P'], provides: ['C', 'B', 'A'] }
            ]);
            expect(bom.compositions).toStrictEqual([
                { aggregate: 'complete', assemblies: ['C', 'A', 'B'], dependencies: ['P', ROOT, 'A'] },
                { aggregate: 'incomplete', assemblies: ['B', 'A'] }
            ]);
            expect(bom.vulnerabilities).toStrictEqual([
                { id: 'V-2', affects: [{ ref: 'C' }, { ref: 'A' }, { ref: 'B' }] },
                { id: 'V-3', affects: [{ ref: 'B' }, { ref: 'A' }] }
            ]);
            expect(bom.annotations).toStrictEqual([note('two', ['C', 'A']), note('one', ['B', 'A'])]);
        });
    });

    describe('compositions, vulnerabilities and annotations (T-2)', () => {
        function withRefs(): any {
            const bom = graphBom();
            bom.compositions = [
                { aggregate: 'complete', assemblies: ['A', 'F1'], dependencies: [ROOT, 'F2'] },
                { aggregate: 'incomplete', assemblies: ['F1', 'F3'] },
                { aggregate: 'unknown', dependencies: ['F2'], vulnerabilities: ['V-3'] },
                { aggregate: 'complete' }
            ];
            bom.vulnerabilities = [
                { id: 'V-1', affects: [{ ref: 'A' }, { ref: 'F1' }] },
                { id: 'V-2', affects: [{ ref: 'F2' }] },
                { id: 'V-3' }
            ];
            bom.annotations = [
                { subjects: ['B', 'F3'], annotator: { organization: { name: 'x' } }, timestamp: '2026-10-07T00:00:00Z', text: 'one' },
                { subjects: ['F1'], annotator: { organization: { name: 'x' } }, timestamp: '2026-10-07T00:00:00Z', text: 'two' }
            ];
            return bom;
        }

        it('removes dropped refs from compositions and drops an entry the filter emptied', () => {
            const { bom } = extractFileFilteredBom(withRefs());

            expect(bom.compositions).toEqual([
                { aggregate: 'complete', assemblies: ['A'], dependencies: [ROOT] },
                { aggregate: 'unknown', dependencies: [], vulnerabilities: ['V-3'] },
                { aggregate: 'complete' }
            ]);
        });

        it('removes dropped refs from affects and drops a vulnerability left with none', () => {
            const { bom } = extractFileFilteredBom(withRefs());

            expect(bom.vulnerabilities.map((v: any) => v.id)).toEqual(['V-1', 'V-3']);
            expect(bom.vulnerabilities[0].affects).toEqual([{ ref: 'A' }]);
        });

        it('removes dropped refs from annotation subjects and drops an annotation left with none', () => {
            const { bom } = extractFileFilteredBom(withRefs());

            expect(bom.annotations.map((a: any) => a.text)).toEqual(['one']);
            expect(bom.annotations[0].subjects).toEqual(['B']);
        });
    });

    describe('provenance (T-3)', () => {
        it('records the count as exactly one metadata property', () => {
            const { bom } = extractFileFilteredBom(graphBom());

            expect(excludedProps(bom)).toEqual([{ name: FILE_COMPONENTS_EXCLUDED_PROPERTY, value: '3' }]);
        });

        it('names the property reliza:export:fileComponentsExcluded, outside the namespace ReARM strips', () => {
            // Written out, not imported: ReARM's export keeps exactly this name through its
            // reliza:support:* strip and its internal-metadata strip, so a rename here would
            // silently drop the record from every export.
            const { bom } = extractFileFilteredBom(graphBom());

            expect(FILE_COMPONENTS_EXCLUDED_PROPERTY).toBe('reliza:export:fileComponentsExcluded');
            expect(bom.metadata.properties).toEqual([{ name: 'reliza:export:fileComponentsExcluded', value: '3' }]);
        });

        it('records 0 on a document with no file component', () => {
            const input = graphBom();
            input.components = input.components.filter((c: any) => c.type !== 'file');
            input.components.find((c: any) => c['bom-ref'] === 'P').components = [];
            const { bom, excludedCount } = extractFileFilteredBom(input);

            expect(excludedCount).toBe(0);
            expect(excludedProps(bom)).toEqual([{ name: FILE_COMPONENTS_EXCLUDED_PROPERTY, value: '0' }]);
        });

        it('appends to existing metadata properties and replaces a stale count', () => {
            const input = graphBom();
            input.metadata.properties = [
                { name: 'cdx:other', value: 'kept' },
                { name: FILE_COMPONENTS_EXCLUDED_PROPERTY, value: '99' }
            ];
            const { bom } = extractFileFilteredBom(input);

            expect(bom.metadata.properties).toEqual([
                { name: 'cdx:other', value: 'kept' },
                { name: FILE_COMPONENTS_EXCLUDED_PROPERTY, value: '3' }
            ]);
        });

        it('adds what the inputs of a merge already left out', () => {
            const { bom, excludedCount } = extractFileFilteredBom(graphBom(), 5);

            expect(excludedCount).toBe(8);
            expect(fileComponentsExcludedOf(bom)).toBe(8);
        });

        it('never drops metadata.component, whatever its type', () => {
            const input = graphBom();
            input.metadata.component.type = 'file';
            input.components.push({ type: 'file', name: 'root', 'bom-ref': ROOT });
            const { bom } = extractFileFilteredBom(input);

            expect(bom.metadata.component['bom-ref']).toBe(ROOT);
            expect(depOf(bom, ROOT)).toBeDefined();
            expect(bom.components.some((c: any) => c['bom-ref'] === ROOT)).toBe(true);
        });

        it('reads no count from a document that carries none', () => {
            expect(fileComponentsExcludedOf(graphBom())).toBe(0);
        });
    });
});
