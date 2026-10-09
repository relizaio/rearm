import { describe, it, expect, vi, afterEach } from 'vitest';
import { logger } from '../../src/logger';
import {
    dropDanglingRefs,
    DANGLING_REFS_DROPPED_PROPERTY
} from '../../src/services/bom/bomProcessingService';

/**
 * SCORE-14 (ADR-3): the last pass of a merge drops the dependency references that resolve
 * to no component or service, counts them, and says so in metadata.properties.
 */

const ROOT = 'pkg:generic/Example/merged@1.0.0';
const X = 'pkg:npm/x@1.0.0';
const A = 'pkg:npm/a@1.0.0';
const B = 'pkg:npm/b@1.0.0';
const C = 'pkg:npm/c@1.0.0';

function lib(ref: string, extra: any = {}): any {
    return { type: 'library', name: ref, version: '1.0.0', 'bom-ref': ref, ...extra };
}

function doc(extra: any = {}): any {
    return {
        bomFormat: 'CycloneDX',
        specVersion: '1.6',
        serialNumber: 'urn:uuid:3c1f0a7e-14a1-4c1e-8f00-000000000015',
        version: 1,
        metadata: {
            component: { type: 'application', name: 'merged', 'bom-ref': ROOT },
            properties: [{ name: 'existing', value: 'kept' }, { name: 'cdx:other', value: '1' }]
        },
        components: [lib(X), lib(A)],
        ...extra
    };
}

function marker(bom: any): any[] {
    return (bom.metadata.properties || []).filter((p: any) => p.name === DANGLING_REFS_DROPPED_PROPERTY);
}

afterEach(() => vi.restoreAllMocks());

describe('dropDanglingRefs', () => {
    it('drops an unresolved entry and an unresolved dependsOn item, counts both and keeps the other properties (T-9)', () => {
        const warn = vi.spyOn(logger, 'warn').mockImplementation(() => undefined as any);
        const bom = doc({
            dependencies: [
                { ref: X, dependsOn: [A, 'ghost'] },
                { ref: 'ghost2', dependsOn: [A] }
            ]
        });
        const result = dropDanglingRefs(bom);

        expect(result.count).toBe(2);
        expect(result.bom.dependencies).toStrictEqual([{ ref: X, dependsOn: [A] }]);
        expect(result.bom.metadata.properties).toStrictEqual([
            { name: 'existing', value: 'kept' },
            { name: 'cdx:other', value: '1' },
            { name: DANGLING_REFS_DROPPED_PROPERTY, value: '2' }
        ]);
        expect(result.bom.components).toBe(bom.components);
        expect(bom.dependencies).toStrictEqual([
            { ref: X, dependsOn: [A, 'ghost'] },
            { ref: 'ghost2', dependsOn: [A] }
        ]);
        expect(warn).toHaveBeenCalledTimes(1);
        expect(String(warn.mock.calls[0][0])).toContain('ghost');
        expect(String(warn.mock.calls[0][0])).toContain('ghost2');
    });

    it('keeps refs to services, nested services, nested components and the root; filters provides; keeps emptied entries (T-10)', () => {
        vi.spyOn(logger, 'warn').mockImplementation(() => undefined as any);
        const bom = doc({
            metadata: {
                component: { type: 'application', name: 'merged', 'bom-ref': ROOT, components: [lib('root-child')] }
            },
            components: [lib(X, { components: [lib('deep', { components: [lib('deeper')] })] }), lib(A)],
            services: [{ 'bom-ref': 'svc', name: 'svc', services: [{ 'bom-ref': 'svc-child', name: 'child' }] }],
            dependencies: [
                { ref: ROOT, dependsOn: [X, 'svc', 'root-child'] },
                { ref: 'svc', dependsOn: ['deeper', 'gone'] },
                { ref: 'svc-child', dependsOn: ['deep'] },
                { ref: X, dependsOn: ['gone-too'], provides: ['svc', 'not-there'] },
                { ref: A, dependsOn: [] },
                { ref: 'deeper', provides: ['missing'] }
            ]
        });
        const result = dropDanglingRefs(bom);

        expect(result.bom.dependencies).toStrictEqual([
            { ref: ROOT, dependsOn: [X, 'svc', 'root-child'] },
            { ref: 'svc', dependsOn: ['deeper'] },
            { ref: 'svc-child', dependsOn: ['deep'] },
            { ref: X, dependsOn: [], provides: ['svc'] },
            { ref: A, dependsOn: [] },
            { ref: 'deeper', provides: [] }
        ]);
        expect(result.count).toBe(4);
        expect(result.bom.dependencies[0]).toBe(bom.dependencies[0]);
        expect(result.bom.dependencies[4]).toBe(bom.dependencies[4]);
        expect(marker(result.bom)).toStrictEqual([{ name: DANGLING_REFS_DROPPED_PROPERTY, value: '4' }]);
    });

    it('leaves compositions and vulnerabilities alone', () => {
        vi.spyOn(logger, 'warn').mockImplementation(() => undefined as any);
        const compositions = [{ aggregate: 'complete', assemblies: ['ghost'] }];
        const vulnerabilities = [{ id: 'CVE-1', affects: [{ ref: 'ghost' }] }];
        const bom = doc({ dependencies: [{ ref: 'ghost', dependsOn: [] }], compositions, vulnerabilities });
        const result = dropDanglingRefs(bom);

        expect(result.bom.compositions).toBe(compositions);
        expect(result.bom.vulnerabilities).toBe(vulnerabilities);
        expect(result.bom.dependencies).toStrictEqual([]);
        expect(result.count).toBe(1);
    });

    it('returns the same object and writes no marker when every ref resolves (T-11)', () => {
        const bom = doc({ dependencies: [{ ref: ROOT, dependsOn: [X, A] }, { ref: X, dependsOn: [A] }] });
        const before = structuredClone(bom);
        const result = dropDanglingRefs(bom);

        expect(result.bom).toBe(bom);
        expect(result.count).toBe(0);
        expect(result.bom).toStrictEqual(before);
        expect(marker(result.bom)).toStrictEqual([]);
    });

    it('keeps a marker already present when nothing is dropped (T-11)', () => {
        const bom = doc({ dependencies: [{ ref: X, dependsOn: [A] }] });
        bom.metadata.properties.push({ name: DANGLING_REFS_DROPPED_PROPERTY, value: '7' });
        const result = dropDanglingRefs(bom);

        expect(result.bom).toBe(bom);
        expect(marker(result.bom)).toStrictEqual([{ name: DANGLING_REFS_DROPPED_PROPERTY, value: '7' }]);
    });

    it('replaces an existing marker instead of adding a second one (T-11)', () => {
        vi.spyOn(logger, 'warn').mockImplementation(() => undefined as any);
        const bom = doc({ dependencies: [{ ref: X, dependsOn: [A, 'ghost'] }] });
        bom.metadata.properties.push({ name: DANGLING_REFS_DROPPED_PROPERTY, value: '7' });
        const result = dropDanglingRefs(bom);

        expect(marker(result.bom)).toStrictEqual([{ name: DANGLING_REFS_DROPPED_PROPERTY, value: '1' }]);
        expect(result.bom.metadata.properties).toHaveLength(3);
        expect(bom.metadata.properties).toHaveLength(3);
    });

    it('logs the first 20 dropped refs and the count of the rest', () => {
        const warn = vi.spyOn(logger, 'warn').mockImplementation(() => undefined as any);
        const ghosts = Array.from({ length: 23 }, (_, i) => `ghost-${String(i).padStart(2, '0')}`);
        const result = dropDanglingRefs(doc({ dependencies: [{ ref: X, dependsOn: [A, ...ghosts] }] }));

        expect(result.count).toBe(23);
        const message = String(warn.mock.calls[0][0]);
        expect(message).toContain('ghost-19');
        expect(message).not.toContain('ghost-20');
        expect(message).toContain('and 3 more');
    });

    it('passes a document without dependencies through unchanged', () => {
        const bom = doc();
        expect(dropDanglingRefs(bom).bom).toBe(bom);
    });
});

describe('dropDanglingRefs: the marker name', () => {
    it('writes the property under the literal name reliza:export:danglingRefsDropped (ADR-3)', () => {
        vi.spyOn(logger, 'warn').mockImplementation(() => undefined as any);
        const result = dropDanglingRefs(doc({ dependencies: [{ ref: X, dependsOn: [A, 'ghost'] }, { ref: 'ghost2' }] }));

        expect(DANGLING_REFS_DROPPED_PROPERTY).toBe('reliza:export:danglingRefsDropped');
        expect(result.bom.metadata.properties).toStrictEqual([
            { name: 'existing', value: 'kept' },
            { name: 'cdx:other', value: '1' },
            { name: 'reliza:export:danglingRefsDropped', value: '2' }
        ]);
    });

    it('moves a replaced marker to the end, after the properties it found, like the de-dup marker', () => {
        vi.spyOn(logger, 'warn').mockImplementation(() => undefined as any);
        const bom = doc({ dependencies: [{ ref: X, dependsOn: [A, 'ghost'] }] });
        bom.metadata.properties = [
            { name: 'first', value: 'a' },
            { name: DANGLING_REFS_DROPPED_PROPERTY, value: '7' },
            { name: 'last', value: 'b' }
        ];
        const result = dropDanglingRefs(bom);

        expect(result.bom.metadata.properties).toStrictEqual([
            { name: 'first', value: 'a' },
            { name: 'last', value: 'b' },
            { name: DANGLING_REFS_DROPPED_PROPERTY, value: '1' }
        ]);
    });
});

describe('dropDanglingRefs: the exact bom-ref string resolves (ADR-3, no normalisation)', () => {
    it('drops and counts a dependsOn item that differs from a component bom-ref only by case', () => {
        vi.spyOn(logger, 'warn').mockImplementation(() => undefined as any);
        const bom = doc({ components: [lib(X), lib('A')], dependencies: [{ ref: X, dependsOn: ['a', 'A'] }] });
        const result = dropDanglingRefs(bom);

        expect(result.bom.dependencies).toStrictEqual([{ ref: X, dependsOn: ['A'] }]);
        expect(result.count).toBe(1);
        expect(marker(result.bom)).toStrictEqual([{ name: DANGLING_REFS_DROPPED_PROPERTY, value: '1' }]);
    });

    it('drops an entry whose ref differs from a component bom-ref only by case, and a provides item that differs from a service bom-ref only by case', () => {
        vi.spyOn(logger, 'warn').mockImplementation(() => undefined as any);
        const bom = doc({
            services: [{ 'bom-ref': 'urn:svc:nginx', name: 'nginx' }],
            dependencies: [
                { ref: X.toUpperCase(), dependsOn: [A] },
                { ref: X, provides: ['urn:svc:Nginx', 'urn:svc:nginx'] }
            ]
        });
        const result = dropDanglingRefs(bom);

        expect(result.bom.dependencies).toStrictEqual([{ ref: X, provides: ['urn:svc:nginx'] }]);
        expect(result.count).toBe(2);
    });

    it('does not trim: a ref with a trailing space resolves to nothing', () => {
        vi.spyOn(logger, 'warn').mockImplementation(() => undefined as any);
        const result = dropDanglingRefs(doc({ dependencies: [{ ref: X, dependsOn: [`${A} `, A] }] }));

        expect(result.bom.dependencies).toStrictEqual([{ ref: X, dependsOn: [A] }]);
        expect(result.count).toBe(1);
    });

    it('drops and counts an entry without a ref (D-3)', () => {
        const warn = vi.spyOn(logger, 'warn').mockImplementation(() => undefined as any);
        const result = dropDanglingRefs(doc({ dependencies: [{ dependsOn: [A] }, { ref: X, dependsOn: [A] }] }));

        expect(result.bom.dependencies).toStrictEqual([{ ref: X, dependsOn: [A] }]);
        expect(result.count).toBe(1);
        expect(warn).toHaveBeenCalledTimes(1);
    });
});

describe('dropDanglingRefs: order of what is kept', () => {
    it('keeps the surviving dependsOn and provides items in their order around the dropped ones', () => {
        vi.spyOn(logger, 'warn').mockImplementation(() => undefined as any);
        const bom = doc({
            components: [lib(X), lib(A), lib(B), lib(C)],
            services: [{ 'bom-ref': 's1', name: 's1' }, { 'bom-ref': 's2', name: 's2' }, { 'bom-ref': 's3', name: 's3' }],
            dependencies: [
                { ref: X, dependsOn: [C, 'ghost', A, B], provides: ['s3', 'ghost2', 's1', 's2'] },
                { ref: A, dependsOn: [B, 'ghost3', C] }
            ]
        });
        const result = dropDanglingRefs(bom);

        expect(result.bom.dependencies).toStrictEqual([
            { ref: X, dependsOn: [C, A, B], provides: ['s3', 's1', 's2'] },
            { ref: A, dependsOn: [B, C] }
        ]);
        expect(result.count).toBe(3);
    });

    it('keeps the surviving entries and their keys in their order around a dropped entry', () => {
        vi.spyOn(logger, 'warn').mockImplementation(() => undefined as any);
        const bom = doc({
            components: [lib(X), lib(A), lib(B)],
            dependencies: [
                { ref: B, provides: [A, 'ghost'], dependsOn: [X] },
                { ref: 'ghost2', dependsOn: [A] },
                { ref: X, dependsOn: [A] },
                { ref: A, dependsOn: ['ghost3', B] }
            ]
        });
        const result = dropDanglingRefs(bom);

        expect(result.bom.dependencies).toStrictEqual([
            { ref: B, provides: [A], dependsOn: [X] },
            { ref: X, dependsOn: [A] },
            { ref: A, dependsOn: [B] }
        ]);
        expect(Object.keys(result.bom.dependencies[0])).toStrictEqual(['ref', 'provides', 'dependsOn']);
        expect(result.bom.dependencies.map((d: any) => d.ref)).toStrictEqual([B, X, A]);
    });

    it('logs the dropped refs in document order', () => {
        const warn = vi.spyOn(logger, 'warn').mockImplementation(() => undefined as any);
        dropDanglingRefs(doc({ dependencies: [{ ref: X, dependsOn: ['g1', A], provides: ['g2'] }, { ref: 'g3' }] }));

        expect(String(warn.mock.calls[0][0])).toContain('g1, g2, g3');
    });
});
