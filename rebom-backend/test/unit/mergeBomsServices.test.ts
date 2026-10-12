import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import * as fs from 'fs';
import * as path from 'path';

// mergeBoms reads its inputs through bomCrudService and runs `rearm bomutils merge-boms`
// through utils; both are mocked, so each test controls the merged document merge-boms
// returns, and every pass rebom runs on it afterwards runs for real.
const findBomObjectById = vi.fn();
const shellExec = vi.fn();

vi.mock('../../src/services/bom/bomCrudService', () => ({
    findBomObjectById: (...a: any[]) => findBomObjectById.apply(null, a as any)
}));
vi.mock('../../src/utils', async (importOriginal) => {
    const actual: any = await importOriginal();
    return {
        ...actual,
        createTmpFiles: vi.fn(async (objs: any[]) => objs.map((_: any, i: number) => `/tmp/merge-input-${i}.json`)),
        deleteTmpFiles: vi.fn(async () => undefined),
        shellExec: (...a: any[]) => shellExec.apply(null, a as any)
    };
});

import { mergeBoms } from '../../src/services/bom/bomMergeService';
import {
    carryServices,
    dropDanglingRefs,
    BOM_REFS_DEDUPLICATED_PROPERTY,
    DANGLING_REFS_DROPPED_PROPERTY,
    FILE_COMPONENTS_EXCLUDED_PROPERTY
} from '../../src/services/bom/bomProcessingService';
import { logger } from '../../src/logger';

/**
 * SCORE-14 (T-13 and the SCORE-9 T-4 regression): the services carry, the SCORE-13 de-dup,
 * the SCORE-11 file filter and the dangling-ref drop together, on the shapes they meet in a
 * release export.
 */

const FIXTURES = path.join(__dirname, '..', 'fixtures', 'merge');
const ROOT = 'pkg:generic/Example/prod@1.0.0';
const NGINX = 'urn:service:sysvinit:pkg:apk-alpine-nginx-1.31.3-r1:nginx';
const NGINX_DEBUG = 'urn:service:sysvinit:pkg:apk-alpine-nginx-1.31.3-r1:nginx-debug';

function fixture(name: string): any {
    return JSON.parse(fs.readFileSync(path.join(FIXTURES, name), 'utf8'));
}

function options(extra: any = {}): any {
    return { name: 'prod', group: 'Example', version: '1.0.0', purl: ROOT, structure: 'FLAT', tldOnly: false, ignoreDev: false, ...extra };
}

function property(bom: any, name: string): any[] {
    return (bom.metadata.properties || []).filter((p: any) => p.name === name);
}

/** Every bom-ref a dependency reference may resolve to: components and services, any depth. */
function resolvable(bom: any): Set<string> {
    const refs = new Set<string>();
    const walk = (list: any[] = [], child: string) => list.forEach((c: any) => {
        if (c['bom-ref']) refs.add(c['bom-ref']);
        walk(c[child], child);
    });
    walk([bom.metadata.component], 'components');
    walk(bom.components, 'components');
    walk(bom.services, 'services');
    return refs;
}

function unresolved(bom: any): string[] {
    const refs = resolvable(bom);
    return (bom.dependencies || []).flatMap((d: any) => [d.ref, ...(d.dependsOn || []), ...(d.provides || [])])
        .filter((r: string) => !refs.has(r));
}

function serviceDependencyCount(bom: any): number {
    return (bom.dependencies || []).filter((d: any) => d.ref.startsWith('urn:service:')).length;
}

function serve(inputs: Record<string, any>, mergeBomsOutput: any) {
    findBomObjectById.mockImplementation(async (id: string) => structuredClone(inputs[id]));
    shellExec.mockImplementation(async () => JSON.stringify(mergeBomsOutput));
}

describe('mergeBoms: services carried from the inputs (SCORE-9 T-4)', () => {
    beforeEach(() => {
        findBomObjectById.mockReset();
        shellExec.mockReset();
    });

    it('carries the nginx sysvinit services the dependency entries name, so every ref resolves', async () => {
        // The merge-boms output is what rearm-cli 2026-10-sbom-score.3 (the image rebom pins)
        // printed for the two inputs: both service entries, no services[].
        const raw = fixture('nginx-services.merge-boms.cdx.json');
        expect(serviceDependencyCount(raw)).toBe(2);
        expect(raw).not.toHaveProperty('services');
        expect(unresolved(raw)).toStrictEqual([NGINX, NGINX_DEBUG]);
        const nginx = fixture('nginx-services.input.cdx.json');
        serve({ nginx, app: fixture('app.input.cdx.json') }, raw);

        const bom = await mergeBoms(['nginx', 'app'], options(), 'org');

        expect(serviceDependencyCount(bom)).toBe(2);
        expect(bom.services).toStrictEqual(nginx.services);
        expect(bom.services.map((s: any) => s['bom-ref'])).toStrictEqual([NGINX, NGINX_DEBUG]);
        expect(unresolved(bom)).toStrictEqual([]);
        expect(bom.dependencies).toStrictEqual(raw.dependencies);
        expect(property(bom, DANGLING_REFS_DROPPED_PROPERTY)).toStrictEqual([]);
    });

    it('keeps the top-level-only merge free of services and of unresolved refs', async () => {
        const raw = fixture('nginx-services.tld.merge-boms.cdx.json');
        serve({ nginx: fixture('nginx-services.input.cdx.json'), app: fixture('app.input.cdx.json') }, raw);

        const bom = await mergeBoms(['nginx', 'app'], options({ tldOnly: true }), 'org');

        expect(bom).not.toHaveProperty('services');
        expect(serviceDependencyCount(bom)).toBe(0);
        expect(unresolved(bom)).toStrictEqual([]);
        expect(property(bom, DANGLING_REFS_DROPPED_PROPERTY)).toStrictEqual([]);
    });

    it('carries a service whose dependencies were spliced past a filtered file, and drops nothing (T-13)', async () => {
        const A = 'pkg:npm/a@1.0.0';
        const B = 'pkg:npm/b@1.0.0';
        const s1 = { 'bom-ref': 's1', name: 'daemon' };
        const input = {
            bomFormat: 'CycloneDX', specVersion: '1.6', version: 1,
            metadata: { component: { type: 'application', name: 'one', 'bom-ref': 'pkg:npm/one@1.0.0' } },
            components: [], services: [s1]
        };
        serve({ one: input }, {
            bomFormat: 'CycloneDX', specVersion: '1.6', version: 1,
            serialNumber: 'urn:uuid:3c1f0a7e-14a1-4c1e-8f00-000000000016',
            metadata: { component: { type: 'application', name: 'prod', 'bom-ref': ROOT } },
            components: [
                { type: 'library', name: 'a', version: '1.0.0', purl: A, 'bom-ref': A },
                { type: 'file', name: '/usr/sbin/daemon', 'bom-ref': 'file-f' },
                { type: 'library', name: 'b', version: '1.0.0', purl: B, 'bom-ref': B }
            ],
            dependencies: [
                { ref: ROOT, dependsOn: [A, 'file-f'] },
                { ref: 's1', dependsOn: ['file-f'] },
                { ref: 'file-f', dependsOn: [B] }
            ]
        });

        const bom = await mergeBoms(['one'], options({ excludeFileComponents: true }), 'org');

        expect(bom.services).toStrictEqual([s1]);
        expect(bom.components.map((c: any) => c['bom-ref'])).toStrictEqual([A, B]);
        expect(bom.dependencies).toStrictEqual([
            { ref: ROOT, dependsOn: [A, B] },
            { ref: 's1', dependsOn: [B] }
        ]);
        expect(property(bom, FILE_COMPONENTS_EXCLUDED_PROPERTY)).toStrictEqual([{ name: FILE_COMPONENTS_EXCLUDED_PROPERTY, value: '1' }]);
        expect(property(bom, DANGLING_REFS_DROPPED_PROPERTY)).toStrictEqual([]);
        expect(unresolved(bom)).toStrictEqual([]);
    });

    it('carries services next to a folded duplicate bom-ref without dropping a ref (T-13)', async () => {
        const X = 'pkg:maven/io.example/app@1.0.0?type=jar';
        const s1 = { 'bom-ref': 's1', name: 'web' };
        const input = {
            bomFormat: 'CycloneDX', specVersion: '1.6', version: 1,
            metadata: { component: { type: 'application', name: 'one', 'bom-ref': 'pkg:npm/one@1.0.0' } },
            components: [], services: [s1]
        };
        serve({ one: input }, {
            bomFormat: 'CycloneDX', specVersion: '1.6', version: 1,
            serialNumber: 'urn:uuid:3c1f0a7e-14a1-4c1e-8f00-000000000017',
            metadata: { component: { type: 'application', name: 'prod', 'bom-ref': ROOT } },
            components: [
                { type: 'application', name: 'app', version: '1.0.0', 'bom-ref': X,
                    components: [{ type: 'library', name: 'app', version: '1.0.0', 'bom-ref': X, supplier: { name: 'Example' } }] }
            ],
            dependencies: [
                { ref: ROOT, dependsOn: [X] },
                { ref: 's1', dependsOn: [X] }
            ]
        });

        const bom = await mergeBoms(['one'], options(), 'org');

        expect(bom.services).toStrictEqual([s1]);
        expect(property(bom, BOM_REFS_DEDUPLICATED_PROPERTY)).toStrictEqual([{ name: BOM_REFS_DEDUPLICATED_PROPERTY, value: '1' }]);
        expect(property(bom, DANGLING_REFS_DROPPED_PROPERTY)).toStrictEqual([]);
        expect(bom.components).toHaveLength(1);
        expect(bom.components[0].components).toStrictEqual([]);
        expect(unresolved(bom)).toStrictEqual([]);
    });

    it('drops a ref no input can resolve, last, and says so', async () => {
        const raw = fixture('nginx-services.merge-boms.cdx.json');
        raw.dependencies.push({ ref: 'urn:service:sysvinit:ghost', dependsOn: [] });
        serve({ nginx: fixture('nginx-services.input.cdx.json'), app: fixture('app.input.cdx.json') }, raw);

        const bom = await mergeBoms(['nginx', 'app'], options(), 'org');

        expect(bom.services).toHaveLength(2);
        expect(unresolved(bom)).toStrictEqual([]);
        expect(serviceDependencyCount(bom)).toBe(2);
        expect(property(bom, DANGLING_REFS_DROPPED_PROPERTY)).toStrictEqual([{ name: DANGLING_REFS_DROPPED_PROPERTY, value: '1' }]);
    });
});

describe('carryServices then dropDanglingRefs', () => {
    const A = 'pkg:npm/a@1.0.0';
    const B = 'pkg:npm/b@1.0.0';

    function merged(dependencies: any[]): any {
        return {
            bomFormat: 'CycloneDX', specVersion: '1.6', version: 1,
            metadata: { component: { type: 'application', name: 'prod', 'bom-ref': ROOT } },
            components: [
                { type: 'library', name: 'a', version: '1.0.0', 'bom-ref': A },
                { type: 'library', name: 'b', version: '1.0.0', 'bom-ref': B }
            ],
            dependencies
        };
    }

    function input(services?: any[]): any {
        const bom: any = { bomFormat: 'CycloneDX', specVersion: '1.6', metadata: {}, components: [] };
        if (services) bom.services = services;
        return bom;
    }

    afterEach(() => vi.restoreAllMocks());

    it('never lets a service without a bom-ref make a dangling ref resolve (T-5)', () => {
        vi.spyOn(logger, 'warn').mockImplementation(() => undefined as any);
        const bom = merged([{ ref: ROOT, dependsOn: [A, 'cron'] }, { ref: 'syslog', dependsOn: [A] }]);
        const carried = carryServices(bom, [input([{ name: 'cron' }, { name: 'syslog' }])], { referencedOnly: false }).bom;
        const result = dropDanglingRefs(carried);

        expect(carried.services).toStrictEqual([{ name: 'cron' }, { name: 'syslog' }]);
        expect(result.count).toBe(2);
        expect(result.bom.dependencies).toStrictEqual([{ ref: ROOT, dependsOn: [A] }]);
    });

    it('passes a document without services through both passes unchanged, with no marker (T-8)', () => {
        const bom = merged([{ ref: ROOT, dependsOn: [A, B] }]);
        const before = structuredClone(bom);
        const carried = carryServices(bom, [input(), input()], { referencedOnly: false });
        const checked = dropDanglingRefs(carried.bom);

        expect(checked.bom).toBe(bom);
        expect(checked.bom).toStrictEqual(before);
        expect(carried.count).toBe(0);
        expect(checked.count).toBe(0);
        expect(checked.bom).not.toHaveProperty('services');
        expect(checked.bom.metadata).not.toHaveProperty('properties');
    });
});
