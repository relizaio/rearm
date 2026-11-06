import { describe, it, expect } from 'vitest';
import { assignMissingBomRefs } from '../../src/services/bom/bomProcessingService';

/** FDAW-6 T5 (architecture 3.4a): keyless nodes get a bom-ref before the engine sees them. */
describe('assignMissingBomRefs (FDAW-6 T5)', () => {
    const doc = (components: any[], extra: any = {}) => ({ metadata: { component: { name: 'root', 'bom-ref': 'root' } }, components, ...extra });

    it('gives a keyless node <name>@<version>, and the same ref to a second node of that name and version whatever its group', () => {
        const out = assignMissingBomRefs(doc([
            { type: 'library', group: 'vendor-a', name: 'dosetables', version: '1.0' },
            { type: 'library', group: 'vendor-b', name: 'dosetables', version: '1.0' }
        ]));
        expect(out.components.map((c: any) => c['bom-ref'])).toStrictEqual(['dosetables@1.0', 'dosetables@1.0']);
    });

    it('suffixes #2, #3 against a node of another name or version holding the ref, and against a service', () => {
        const out = assignMissingBomRefs(doc([
            { name: 'renamed', version: '9', 'bom-ref': 'other@2.0' },
            { name: 'other', version: '2.0' },
            { name: 'other', version: '2.0' }
        ]));
        // the first keyless node takes #2; the second has the same name and version as the #2 holder and reuses it
        expect(out.components.map((c: any) => c['bom-ref'])).toStrictEqual(['other@2.0', 'other@2.0#2', 'other@2.0#2']);

        const three = assignMissingBomRefs(doc([
            { name: 'renamed', version: '9', 'bom-ref': 'other@2.0' },
            { name: 'renamed', version: '8', 'bom-ref': 'other@2.0#2' },
            { name: 'other', version: '2.0' }
        ]));
        expect(three.components[2]['bom-ref']).toBe('other@2.0#3');

        const svc = assignMissingBomRefs(doc([{ name: 'api', version: '1' }], { services: [{ name: 'api', version: '1', 'bom-ref': 'api@1' }] }));
        expect(svc.components[0]['bom-ref']).toBe('api@1#2');
    });

    it('uses <name> without a version, walks nested components, leaves nodes with a bom-ref or a purl alone', () => {
        const input = doc([
            { name: 'libdosecalc', version: '3.1', 'bom-ref': 'libdosecalc@3.1' },
            { name: 'a', version: '1.0.0', purl: 'pkg:npm/a@1.0.0', 'bom-ref': 'pkg:npm/a@1.0.0', components: [{ name: 'vendored' }] },
            { name: 'noversion' }
        ]);
        const out = assignMissingBomRefs(input);
        expect(out.components[0]).toBe(input.components[0]);
        expect(out.components[1]['bom-ref']).toBe('pkg:npm/a@1.0.0');
        expect(out.components[1].components[0]['bom-ref']).toBe('vendored');
        expect(out.components[2]['bom-ref']).toBe('noversion');
        expect(input.components[2]).not.toHaveProperty('bom-ref');
    });

    it('returns the input itself when every node has a ref', () => {
        const input = doc([{ name: 'a', 'bom-ref': 'a' }]);
        expect(assignMissingBomRefs(input)).toBe(input);
    });
});
