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
