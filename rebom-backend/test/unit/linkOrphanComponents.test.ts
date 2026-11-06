import { describe, it, expect } from 'vitest';
import { linkOrphanComponents } from '../../src/services/bom/bomProcessingService';

/** FDAW-6 T6 (architecture 3.4b): a top-level component in no dependency entry becomes a direct dependency of the root. */
describe('linkOrphanComponents (FDAW-6 T6)', () => {
    const doc = (deps: any[] | undefined, extra: any = {}) => ({
        metadata: { component: { name: 'root', 'bom-ref': 'root' } },
        components: [
            { name: 'a', 'bom-ref': 'a' },
            { name: 'b', 'bom-ref': 'b', components: [{ name: 'nested', 'bom-ref': 'nested' }] },
            { name: 'c', 'bom-ref': 'c' },
            { name: 'd', 'bom-ref': 'd' }
        ],
        ...(deps ? { dependencies: deps } : {}),
        ...extra
    });

    it('appends the unnamed top-level components to the root entry in component order', () => {
        const r = linkOrphanComponents(doc([{ ref: 'root', dependsOn: ['b', 'a'] }]));
        expect(r.count).toBe(2);
        expect(r.bom.dependencies).toStrictEqual([{ ref: 'root', dependsOn: ['b', 'a', 'c', 'd'] }]);
    });

    it('does not link a nested component, or one named only in another entry', () => {
        const r = linkOrphanComponents(doc([{ ref: 'root', dependsOn: ['a'] }, { ref: 'a', dependsOn: ['c'] }, { ref: 'd', dependsOn: [] }]));
        expect(r.count).toBe(1);
        expect(r.bom.dependencies[0]).toStrictEqual({ ref: 'root', dependsOn: ['a', 'b'] });
    });

    it('creates the root entry when the document has none', () => {
        const r = linkOrphanComponents(doc(undefined));
        expect(r.count).toBe(4);
        expect(r.bom.dependencies).toStrictEqual([{ ref: 'root', dependsOn: ['a', 'b', 'c', 'd'] }]);
    });

    it('an entry whose own ref resolves to nothing names nothing', () => {
        const r = linkOrphanComponents(doc([{ ref: 'root', dependsOn: ['a', 'b', 'c'] }, { ref: 'ghost', dependsOn: ['d'] }]));
        expect(r.count).toBe(1);
    });

    it('does not link placeholders the engine already named, and returns the input when nothing is linked', () => {
        const bom = {
            metadata: { component: { name: 'root', 'bom-ref': 'root' } },
            components: [{ name: 'p', 'bom-ref': 'urn:rearm:release:0001' }, { name: 'a', 'bom-ref': 'a' }],
            dependencies: [{ ref: 'root', dependsOn: ['urn:rearm:release:0001', 'a'] }]
        };
        expect(linkOrphanComponents(bom)).toStrictEqual({ bom, count: 0 });
        expect(linkOrphanComponents(bom).bom).toBe(bom);
    });

    it('returns the input when the root has no bom-ref', () => {
        const bom: any = doc(undefined);
        delete bom.metadata.component['bom-ref'];
        expect(linkOrphanComponents(bom).bom).toBe(bom);
    });
});
