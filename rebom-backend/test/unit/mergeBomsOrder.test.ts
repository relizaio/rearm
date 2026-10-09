import { describe, it, expect, vi, beforeEach } from 'vitest';

// mergeBoms reads its inputs through bomCrudService and runs `rearm bomutils merge-boms`
// through utils; both are mocked so the test controls the merged document merge-boms
// returns, and everything rebom does to it afterwards runs for real.
const findBomObjectById = vi.fn();
const shellExec = vi.fn();
// SCORE-14 T-12: the passes mergeBoms runs, in the order it runs them, with their arguments.
const passes = vi.hoisted(() => ({ calls: [] as { name: string, args: any[] }[] }));
// SCORE-15 T-10: the documents mergeBomObjects hands merge-boms, in input-file order.
const mergeInputs = vi.hoisted(() => ({ objs: [] as any[] }));

vi.mock('../../src/services/bom/bomCrudService', () => ({
    findBomObjectById: (...a: any[]) => findBomObjectById.apply(null, a as any)
}));
vi.mock('../../src/utils', async (importOriginal) => {
    const actual: any = await importOriginal();
    return {
        ...actual,
        createTmpFiles: vi.fn(async (objs: any[]) => {
            mergeInputs.objs = [...objs];
            return objs.map((_: any, i: number) => `/tmp/merge-input-${i}.json`);
        }),
        deleteTmpFiles: vi.fn(async () => undefined),
        shellExec: (...a: any[]) => shellExec.apply(null, a as any)
    };
});

vi.mock('../../src/services/bom/bomProcessingService', async (importOriginal) => {
    const actual: any = await importOriginal();
    const recorded = (name: string) => (...args: any[]) => {
        passes.calls.push({ name, args });
        return actual[name](...args);
    };
    return {
        ...actual,
        carryServices: recorded('carryServices'),
        dedupeBomRefs: recorded('dedupeBomRefs'),
        extractFileFilteredBom: recorded('extractFileFilteredBom'),
        dropDanglingRefs: recorded('dropDanglingRefs'),
        declareUnknownCompositions: recorded('declareUnknownCompositions')
    };
});

import { mergeBoms } from '../../src/services/bom/bomMergeService';
import {
    BOM_REFS_DEDUPLICATED_PROPERTY,
    COMPONENTS_WITHOUT_SBOM_PROPERTY,
    FILE_COMPONENTS_EXCLUDED_PROPERTY
} from '../../src/services/bom/bomProcessingService';

/**
 * SCORE-13 (T-9, ADR-6): mergeBoms de-duplicates bom-refs on the merged document, then
 * runs the SCORE-11 file filter. A file component merge-boms copied in with a copy of
 * itself nested inside must be left out once, not twice.
 */

const ROOT = 'pkg:generic/Example/merged@1.0.0';

function input(name: string): any {
    return {
        bomFormat: 'CycloneDX',
        specVersion: '1.6',
        serialNumber: `urn:uuid:5d0b7f6e-0a5c-4b8e-9d1e-${name === 'one' ? '000000000001' : '000000000002'}`,
        version: 1,
        metadata: { component: { type: 'application', name, 'bom-ref': `pkg:npm/${name}@1.0.0` } },
        components: []
    };
}

/** What merge-boms returns for the two inputs: a file component holding a copy of itself. */
function mergeBomsOutput(): any {
    return {
        bomFormat: 'CycloneDX',
        specVersion: '1.6',
        serialNumber: 'urn:uuid:5d0b7f6e-0a5c-4b8e-9d1e-0000000000ff',
        version: 1,
        metadata: {
            timestamp: '2026-10-07T00:00:00Z',
            component: { type: 'application', name: 'merged', version: '1.0.0', purl: ROOT, 'bom-ref': ROOT },
            tools: { components: [] }
        },
        components: [
            { type: 'library', name: 'a', version: '1.0.0', purl: 'pkg:npm/a@1.0.0', 'bom-ref': 'pkg:npm/a@1.0.0' },
            {
                type: 'file', name: '/opt/tool', 'bom-ref': 'file-tool',
                components: [{ type: 'file', name: '/opt/tool', 'bom-ref': 'file-tool' }]
            },
            { type: 'library', name: 'b', version: '1.0.0', purl: 'pkg:npm/b@1.0.0', 'bom-ref': 'pkg:npm/b@1.0.0' }
        ],
        dependencies: [
            { ref: ROOT, dependsOn: ['pkg:npm/a@1.0.0', 'file-tool', 'pkg:npm/b@1.0.0'] },
            { ref: 'file-tool', dependsOn: ['pkg:npm/b@1.0.0'] }
        ]
    };
}

function options(extra: any = {}): any {
    return { name: 'merged', group: 'Example', version: '1.0.0', purl: ROOT, structure: 'FLAT', tldOnly: false, ignoreDev: false, ...extra };
}

function property(bom: any, name: string): any[] {
    return (bom.metadata.properties || []).filter((p: any) => p.name === name);
}

function serveInputsAndMergeBomsOutput() {
    findBomObjectById.mockReset();
    shellExec.mockReset();
    findBomObjectById.mockImplementation(async (id: string) => input(id));
    shellExec.mockImplementation(async (...args: any[]) => {
        passes.calls.push({ name: 'merge-boms', args });
        return JSON.stringify(mergeBomsOutput());
    });
    passes.calls.length = 0;
    mergeInputs.objs = [];
}

describe('mergeBoms: bom-ref de-dup before the file filter', () => {
    beforeEach(serveInputsAndMergeBomsOutput);

    it('leaves a file duplicated under itself out once, and counts both passes (T-9)', async () => {
        const bom = await mergeBoms(['one', 'two'], options({ excludeFileComponents: true }), 'org');

        expect(bom.components).toStrictEqual([
            { type: 'library', name: 'a', version: '1.0.0', purl: 'pkg:npm/a@1.0.0', 'bom-ref': 'pkg:npm/a@1.0.0' },
            { type: 'library', name: 'b', version: '1.0.0', purl: 'pkg:npm/b@1.0.0', 'bom-ref': 'pkg:npm/b@1.0.0' }
        ]);
        expect(bom.dependencies).toStrictEqual([{ ref: ROOT, dependsOn: ['pkg:npm/a@1.0.0', 'pkg:npm/b@1.0.0'] }]);
        expect(property(bom, FILE_COMPONENTS_EXCLUDED_PROPERTY)).toStrictEqual([{ name: FILE_COMPONENTS_EXCLUDED_PROPERTY, value: '1' }]);
        expect(property(bom, BOM_REFS_DEDUPLICATED_PROPERTY)).toStrictEqual([{ name: BOM_REFS_DEDUPLICATED_PROPERTY, value: '1' }]);
        expect(findBomObjectById.mock.calls.map(c => c[0])).toStrictEqual(['one', 'two']);
    });

    it('de-duplicates without the file switch too, and keeps the file once', async () => {
        const bom = await mergeBoms(['one', 'two'], options(), 'org');

        expect(bom.components.map((c: any) => c['bom-ref'])).toStrictEqual(['pkg:npm/a@1.0.0', 'file-tool', 'pkg:npm/b@1.0.0']);
        expect(bom.components[1]).toStrictEqual({ type: 'file', name: '/opt/tool', 'bom-ref': 'file-tool', components: [] });
        expect(property(bom, BOM_REFS_DEDUPLICATED_PROPERTY)).toStrictEqual([{ name: BOM_REFS_DEDUPLICATED_PROPERTY, value: '1' }]);
        expect(property(bom, FILE_COMPONENTS_EXCLUDED_PROPERTY)).toStrictEqual([]);
    });

    it('writes no de-dup property when merge-boms returns unique bom-refs', async () => {
        shellExec.mockImplementation(async () => {
            const out = mergeBomsOutput();
            delete out.components[1].components;
            return JSON.stringify(out);
        });

        const bom = await mergeBoms(['one', 'two'], options({ excludeFileComponents: true }), 'org');

        expect(property(bom, BOM_REFS_DEDUPLICATED_PROPERTY)).toStrictEqual([]);
        expect(property(bom, FILE_COMPONENTS_EXCLUDED_PROPERTY)).toStrictEqual([{ name: FILE_COMPONENTS_EXCLUDED_PROPERTY, value: '1' }]);
    });
});

/**
 * SCORE-14 (T-12, ADR-4): merge-boms, then carryServices, dedupeBomRefs, the file filter (only
 * with the switch) and dropDanglingRefs, each on what the one before returned. SCORE-15 (ADR-5)
 * runs declareUnknownCompositions after dropDanglingRefs, last.
 */
describe('mergeBoms: pass order', () => {
    beforeEach(serveInputsAndMergeBomsOutput);

    it('runs merge-boms, then carryServices, dedupeBomRefs, the file filter, dropDanglingRefs and declareUnknownCompositions (T-12)', async () => {
        const inputs = [input('one'), input('two')];
        findBomObjectById.mockImplementation(async (id: string) => inputs[id === 'one' ? 0 : 1]);

        const bom = await mergeBoms(['one', 'two'], options({ excludeFileComponents: true }), 'org');

        expect(passes.calls.map(c => c.name)).toStrictEqual(['merge-boms', 'carryServices', 'dedupeBomRefs', 'extractFileFilteredBom', 'dropDanglingRefs', 'declareUnknownCompositions']);
        const carry = passes.calls[1];
        expect(carry.args[1]).toHaveLength(2);
        expect(carry.args[1][0]).toBe(inputs[0]);
        expect(carry.args[1][1]).toBe(inputs[1]);
        expect(carry.args[2]).toStrictEqual({ referencedOnly: false });
        // each pass works on what the one before it returned
        expect(passes.calls[2].args[0]).toBe(carry.args[0]);
        expect(passes.calls[4].args[0]).not.toBe(passes.calls[3].args[0]);
        expect(passes.calls[4].args[0].metadata.properties).toContainEqual({ name: FILE_COMPONENTS_EXCLUDED_PROPERTY, value: '1' });
        expect(bom.dependencies).toStrictEqual([{ ref: ROOT, dependsOn: ['pkg:npm/a@1.0.0', 'pkg:npm/b@1.0.0'] }]);
    });

    it('skips the file filter without the switch and still runs dropDanglingRefs then declareUnknownCompositions (T-12)', async () => {
        await mergeBoms(['one', 'two'], options(), 'org');

        expect(passes.calls.map(c => c.name)).toStrictEqual(['merge-boms', 'carryServices', 'dedupeBomRefs', 'dropDanglingRefs', 'declareUnknownCompositions']);
        expect(passes.calls[1].args[2]).toStrictEqual({ referencedOnly: false });
    });

    it('asks carryServices for referenced services only in the top-level-only merge (T-12)', async () => {
        await mergeBoms(['one', 'two'], options({ tldOnly: true }), 'org');

        expect(passes.calls.map(c => c.name)).toStrictEqual(['merge-boms', 'carryServices', 'dedupeBomRefs', 'dropDanglingRefs', 'declareUnknownCompositions']);
        expect(passes.calls[1].args[2]).toStrictEqual({ referencedOnly: true });
    });

    it('treats an absent tldOnly as a full merge (T-12)', async () => {
        const opts = options();
        delete opts.tldOnly;
        await mergeBoms(['one', 'two'], opts, 'org');

        expect(passes.calls[1].args[2]).toStrictEqual({ referencedOnly: false });
    });
});

const HTTPD = '0b0f4a52-6c55-4d1f-9a8e-000000000001';
const FLYWAY = '0b0f4a52-6c55-4d1f-9a8e-000000000002';

function missing(releaseUuid: string, name: string): any {
    return { releaseUuid, name, version: '1.0.0', type: 'library', supplierName: 'Example', group: 'Example', purl: null, reason: 'NO_SBOM_ARTIFACT' };
}

/** What merge-boms returns when two placeholders joined the two inputs: each a root dependency. */
function mergeBomsOutputWithPlaceholders(): any {
    const out = mergeBomsOutput();
    const placeholder = (uuid: string, name: string) => ({
        type: 'library', name, version: '1.0.0', group: 'Example', supplier: { name: 'Example' },
        'bom-ref': `urn:rearm:release:${uuid}`, properties: [{ name: 'reliza:sbom:missing', value: 'NO_SBOM_ARTIFACT' }]
    });
    out.components.push(placeholder(HTTPD, 'httpd'), placeholder(FLYWAY, 'flyway'));
    out.dependencies[0].dependsOn.push(`urn:rearm:release:${HTTPD}`, `urn:rearm:release:${FLYWAY}`);
    out.dependencies.push({ ref: `urn:rearm:release:${HTTPD}`, dependsOn: [] }, { ref: `urn:rearm:release:${FLYWAY}`, dependsOn: [] });
    return out;
}

/**
 * SCORE-15 (T-10, ADR-1, ADR-5): the component releases without an SBOM reach merge-boms as
 * synthetic inputs after the stored ones, past the per-input tldOnly/ignoreDev filters, and the
 * composition pass runs last on what dropDanglingRefs returned.
 */
describe('mergeBoms: component releases without an SBOM', () => {
    beforeEach(() => {
        serveInputsAndMergeBomsOutput();
        shellExec.mockImplementation(async (...args: any[]) => {
            passes.calls.push({ name: 'merge-boms', args });
            return JSON.stringify(mergeBomsOutputWithPlaceholders());
        });
    });

    it('hands merge-boms the stored inputs, then the placeholders, in the order sent (T-10)', async () => {
        await mergeBoms(['one', 'two'], options({ missingSbomComponents: [missing(HTTPD, 'httpd'), missing(FLYWAY, 'flyway')] }), 'org');

        expect(mergeInputs.objs.map((b: any) => b.metadata.component['bom-ref'])).toStrictEqual([
            'pkg:npm/one@1.0.0',
            'pkg:npm/two@1.0.0',
            `urn:rearm:release:${HTTPD}`,
            `urn:rearm:release:${FLYWAY}`
        ]);
        expect(passes.calls[0].args[1].filter((a: string) => a.startsWith('/tmp/merge-input-'))).toStrictEqual([
            '/tmp/merge-input-0.json', '/tmp/merge-input-1.json', '/tmp/merge-input-2.json', '/tmp/merge-input-3.json'
        ]);
    });

    it('runs declareUnknownCompositions last, on what dropDanglingRefs returned, with the list sent (T-10)', async () => {
        const list = [missing(HTTPD, 'httpd'), missing(FLYWAY, 'flyway')];
        const bom = await mergeBoms(['one', 'two'], options({ excludeFileComponents: true, missingSbomComponents: list }), 'org');

        expect(passes.calls.map(c => c.name)).toStrictEqual(['merge-boms', 'carryServices', 'dedupeBomRefs', 'extractFileFilteredBom', 'dropDanglingRefs', 'declareUnknownCompositions']);
        // carryServices sees every input merge-boms saw, the placeholders included
        expect(passes.calls[1].args[1].map((b: any) => b.metadata.component['bom-ref'])).toStrictEqual([
            'pkg:npm/one@1.0.0', 'pkg:npm/two@1.0.0', `urn:rearm:release:${HTTPD}`, `urn:rearm:release:${FLYWAY}`
        ]);
        expect(passes.calls[5].args[0]).toBe(passes.calls[4].args[0]);
        expect(passes.calls[5].args[1]).toBe(list);
        expect(bom.compositions).toStrictEqual([{ aggregate: 'unknown', dependencies: [`urn:rearm:release:${HTTPD}`, `urn:rearm:release:${FLYWAY}`] }]);
        expect(bom.metadata.properties).toContainEqual({ name: COMPONENTS_WITHOUT_SBOM_PROPERTY, value: '2' });
        expect(bom.dependencies[0]).toStrictEqual({ ref: ROOT, dependsOn: ['pkg:npm/a@1.0.0', 'pkg:npm/b@1.0.0', `urn:rearm:release:${HTTPD}`, `urn:rearm:release:${FLYWAY}`] });
    });

    it('applies tldOnly and ignoreDev to the stored inputs only, never to a placeholder (T-10)', async () => {
        const dev = input('two');
        dev.components = [{ type: 'library', name: 'devtool', 'bom-ref': 'pkg:npm/devtool@1.0.0', scope: 'excluded' }];
        dev.dependencies = [{ ref: 'pkg:npm/two@1.0.0', dependsOn: ['pkg:npm/devtool@1.0.0'] }, { ref: 'pkg:npm/devtool@1.0.0', dependsOn: [] }];
        findBomObjectById.mockImplementation(async (id: string) => id === 'two' ? dev : input(id));

        await mergeBoms(['one', 'two'], options({ tldOnly: true, ignoreDev: true, missingSbomComponents: [missing(HTTPD, 'httpd')] }), 'org');

        expect(mergeInputs.objs).toHaveLength(3);
        // the stored input went through the filters: its dev-scoped component is gone
        expect(mergeInputs.objs[1].components).toStrictEqual([]);
        expect(mergeInputs.objs[2]).toStrictEqual({
            bomFormat: 'CycloneDX',
            specVersion: '1.6',
            metadata: {
                component: {
                    type: 'library', name: 'httpd', version: '1.0.0', group: 'Example', supplier: { name: 'Example' },
                    'bom-ref': `urn:rearm:release:${HTTPD}`, properties: [{ name: 'reliza:sbom:missing', value: 'NO_SBOM_ARTIFACT' }]
                }
            },
            components: [],
            dependencies: [{ ref: `urn:rearm:release:${HTTPD}`, dependsOn: [] }]
        });
    });

    it('hands merge-boms the stored inputs alone and writes no composition without the list (T-10)', async () => {
        serveInputsAndMergeBomsOutput();

        const bom = await mergeBoms(['one', 'two'], options(), 'org');

        expect(mergeInputs.objs.map((b: any) => b.metadata.component['bom-ref'])).toStrictEqual(['pkg:npm/one@1.0.0', 'pkg:npm/two@1.0.0']);
        expect(bom).not.toHaveProperty('compositions');
        expect(property(bom, COMPONENTS_WITHOUT_SBOM_PROPERTY)).toStrictEqual([]);
    });
});
