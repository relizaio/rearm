import { describe, it, expect, vi, beforeEach } from 'vitest';

// FDAW-6 (architecture 3, the pipeline drawing): where the default merge's new passes run. The inputs
// and `rearm bomutils merge-boms` are mocked, every pass runs for real and is recorded in call order.
const findBomObjectById = vi.fn();
const shellExec = vi.fn();
const passes = vi.hoisted(() => ({ calls: [] as { name: string, args: any[], result?: any }[] }));
const mergeInputs = vi.hoisted(() => ({ objs: [] as any[] }));

vi.mock('../../src/services/bom/bomCrudService', () => ({
    findBomObjectById: (...a: any[]) => findBomObjectById.apply(null, a as any),
    findBomObjectWithMetaById: async (id: string, org: string) => ({ bom: await findBomObjectById(id, org), meta: undefined })
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
        const call: { name: string, args: any[], result?: any } = { name, args };
        passes.calls.push(call);
        call.result = actual[name](...args);
        return call.result;
    };
    const names = ['extractTldFromBom', 'dropExcludedAndDevComponents', 'stripPurlCredentials', 'assignMissingBomRefs', 'rekeyUnlistedInputs',
        'carryServices', 'dedupeBomRefs', 'stripPersonalDataAndLocalPaths', 'foldReleaseRoots', 'fillReleaseNode', 'fillAttachedRoots',
        'markReleaseNode', 'extractFileFilteredBom', 'linkUnfoldedRoots', 'linkOrphanComponents', 'dropDanglingRefs',
        'declareUnknownCompositions', 'recordMergeCounts'];
    return { ...actual, ...Object.fromEntries(names.map(n => [n, recorded(n)])) };
});

import { mergeBoms } from '../../src/services/bom/bomMergeService';
import {
    DEV_COMPONENTS_EXCLUDED_PROPERTY,
    EXCLUDED_SCOPE_DROPPED_PROPERTY,
    PERSONAL_DATA_STRIPPED_PROPERTY,
    LOCAL_PATHS_STRIPPED_PROPERTY,
    CREDENTIALS_STRIPPED_PROPERTY,
    ORPHAN_COMPONENTS_LINKED_PROPERTY,
    OPTIONAL_SCOPE_DROPPED_PROPERTY,
    MERGE_COUNT_PROPERTIES
} from '../../src/services/bom/bomProcessingService';

const ROOT = 'pkg:generic/Example/merged@1.0.0';
const DEV = { name: 'cdx:npm:package:development', value: 'true' };
const CRED_PURL = 'pkg:generic/x@1.0?download_url=https%3A%2F%2Fu%3Ap%40h.example%2Fx.tgz';
const CLEAN_PURL = 'pkg:generic/x@1.0?download_url=https%3A%2F%2Fh.example%2Fx.tgz';

function input(): any {
    return {
        bomFormat: 'CycloneDX', specVersion: '1.6', version: 1,
        serialNumber: 'urn:uuid:5d0b7f6e-0a5c-4b8e-9d1e-000000000001',
        metadata: { component: { type: 'application', name: 'one', 'bom-ref': 'pkg:npm/one@1.0.0' } },
        components: [
            { type: 'library', name: 'lib', version: '1.0.0', purl: 'pkg:npm/lib@1.0.0', 'bom-ref': 'pkg:npm/lib@1.0.0' },
            { type: 'library', name: 'tool', version: '1.0.0', purl: 'pkg:npm/tool@1.0.0', 'bom-ref': 'pkg:npm/tool@1.0.0', properties: [DEV] },
            { type: 'library', name: 'types', version: '1.0.0', scope: 'excluded', purl: 'pkg:npm/types@1.0.0', 'bom-ref': 'pkg:npm/types@1.0.0' },
            { type: 'library', name: 'opt', version: '1.0.0', scope: 'optional', purl: 'pkg:npm/opt@1.0.0', 'bom-ref': 'pkg:npm/opt@1.0.0' },
            { type: 'library', name: 'x', version: '1.0', purl: CRED_PURL, 'bom-ref': CRED_PURL },
            { type: 'library', name: 'vendored', version: '2.0' }
        ],
        dependencies: [{ ref: 'pkg:npm/one@1.0.0', dependsOn: ['pkg:npm/lib@1.0.0', 'pkg:npm/tool@1.0.0', CRED_PURL] }]
    };
}

/** merge-boms, faked: the inputs' components under the merged root, and the root entry only. */
function fakeMergeBoms(..._args: any[]) {
    passes.calls.push({ name: 'merge-boms', args: [] });
    const components = mergeInputs.objs.flatMap((b: any) => b.components || []);
    return JSON.stringify({
        bomFormat: 'CycloneDX', specVersion: '1.6', version: 1, serialNumber: 'urn:uuid:5d0b7f6e-0a5c-4b8e-9d1e-0000000000ff',
        metadata: { timestamp: '2026-11-05T00:00:00Z', component: { type: 'application', name: 'merged', version: '1.0.0', purl: ROOT, 'bom-ref': ROOT }, tools: { components: [] } },
        components,
        dependencies: [{ ref: ROOT, dependsOn: ['pkg:npm/lib@1.0.0'] }]
    });
}

function property(bom: any, name: string): string | undefined {
    return (bom.metadata.properties || []).find((p: any) => p.name === name)?.value;
}

describe('mergeBoms: the FDAW-6 passes in the pipeline', () => {
    beforeEach(() => {
        passes.calls.length = 0;
        mergeInputs.objs = [];
        findBomObjectById.mockReset();
        shellExec.mockReset();
        findBomObjectById.mockImplementation(async () => input());
        shellExec.mockImplementation(async (...args: any[]) => fakeMergeBoms(...args));
    });

    it('runs the per-input passes before the engine and the merged-document passes in the designed order', async () => {
        const bom = await mergeBoms(['one'], { name: 'merged', group: 'Example', version: '1.0.0', purl: ROOT, structure: 'FLAT', tldOnly: true, ignoreDev: false } as any, 'org');

        expect(passes.calls.map(c => c.name)).toStrictEqual([
            'extractTldFromBom', 'dropExcludedAndDevComponents', 'stripPurlCredentials', 'assignMissingBomRefs',
            'rekeyUnlistedInputs', 'merge-boms', 'carryServices', 'dedupeBomRefs', 'stripPersonalDataAndLocalPaths',
            'foldReleaseRoots', 'fillReleaseNode', 'fillAttachedRoots', 'markReleaseNode', 'linkUnfoldedRoots',
            'linkOrphanComponents', 'dropDanglingRefs', 'declareUnknownCompositions', 'recordMergeCounts'
        ]);
        const at = (name: string) => passes.calls.find(c => c.name === name)!;
        // each per-input pass is handed what the one before it returned
        expect(at('dropExcludedAndDevComponents').args[0]).toBe(at('extractTldFromBom').result);
        expect(at('dropExcludedAndDevComponents').args[1]).toStrictEqual({ dropOptional: false, dropExcluded: true });
        expect(at('stripPurlCredentials').args[0]).toBe(at('dropExcludedAndDevComponents').result.bom);
        expect(at('assignMissingBomRefs').args[0]).toBe(at('stripPurlCredentials').result.bom);
        expect(at('rekeyUnlistedInputs').args[1][0]).toBe(at('assignMissingBomRefs').result);
        expect(at('stripPersonalDataAndLocalPaths').args[0]).toBe(at('dedupeBomRefs').result.bom);
        expect(at('foldReleaseRoots').args[0]).toBe(at('stripPersonalDataAndLocalPaths').result.bom);
        expect(at('linkOrphanComponents').args[0]).toBe(at('linkUnfoldedRoots').result.bom);
        expect(at('dropDanglingRefs').args[0]).toBe(at('linkOrphanComponents').result.bom);
        expect(at('recordMergeCounts').args[0]).toBe(at('declareUnknownCompositions').result.bom);
        expect(bom).toBe(at('recordMergeCounts').result);

        // the engine never saw the dev, the excluded node or the credential; the keyless node has its ref
        expect(mergeInputs.objs[0].components.map((c: any) => c['bom-ref'])).toStrictEqual(['pkg:npm/lib@1.0.0', CLEAN_PURL]);
        expect(property(bom, DEV_COMPONENTS_EXCLUDED_PROPERTY)).toBe('1');
        expect(property(bom, EXCLUDED_SCOPE_DROPPED_PROPERTY)).toBe('0');
        expect(property(bom, CREDENTIALS_STRIPPED_PROPERTY)).toBe('1');
        expect(property(bom, ORPHAN_COMPONENTS_LINKED_PROPERTY)).toBe('1');
        const six = MERGE_COUNT_PROPERTIES.filter(n => n !== OPTIONAL_SCOPE_DROPPED_PROPERTY);
        for (const name of six) expect(property(bom, name)).toMatch(/^\d+$/);
        expect(property(bom, OPTIONAL_SCOPE_DROPPED_PROPERTY)).toBeUndefined();
        expect(at('stripPersonalDataAndLocalPaths').args[1]).toStrictEqual({ personalData: true, localPaths: true });
        expect(Object.keys(at('recordMergeCounts').args[1]).sort()).toStrictEqual([...six].sort());
    });

    it('drops optional scope only under ignoreDev, and assigns the keyless ref when the whole input is merged', async () => {
        const bom = await mergeBoms(['one'], { name: 'merged', group: 'Example', version: '1.0.0', purl: ROOT, structure: 'FLAT', tldOnly: false, ignoreDev: true } as any, 'org');
        expect(passes.calls.find(c => c.name === 'dropExcludedAndDevComponents')!.args[1]).toStrictEqual({ dropOptional: true, dropExcluded: true });
        expect(mergeInputs.objs[0].components.map((c: any) => c['bom-ref'])).toStrictEqual(['pkg:npm/lib@1.0.0', CLEAN_PURL, 'vendored@2.0']);
        expect(property(bom, EXCLUDED_SCOPE_DROPPED_PROPERTY)).toBe('1');
        // the credentialed purl and the keyless node are in no entry of the fake engine's output
        expect(property(bom, ORPHAN_COMPONENTS_LINKED_PROPERTY)).toBe('2');
        expect(property(bom, OPTIONAL_SCOPE_DROPPED_PROPERTY)).toBe('1');
    });

    it('FDAW-19 row 4: every knob on: the passes take the options, the strip still runs once, the records are the four that ran', async () => {
        const second = { ...input(), components: [{ type: 'library', name: 'other', version: '1.0.0', purl: 'pkg:npm/other@1.0.0', 'bom-ref': 'pkg:npm/other@1.0.0' }], dependencies: [] };
        findBomObjectById.mockImplementation(async (id: string) => (id === 'two' ? second : input()));
        const bom = await mergeBoms(['one', 'two'], {
            name: 'merged', group: 'Example', version: '1.0.0', purl: ROOT, structure: 'FLAT', tldOnly: false,
            ignoreDev: true, keepExcludedScope: true, keepPersonalData: true, keepLocalPaths: true
        } as any, 'org');

        const all = (name: string) => passes.calls.filter(c => c.name === name);
        expect(all('dropExcludedAndDevComponents').map(c => c.args[1])).toStrictEqual([
            { dropOptional: true, dropExcluded: false }, { dropOptional: true, dropExcluded: false }
        ]);
        expect(all('stripPersonalDataAndLocalPaths').map(c => c.args[1])).toStrictEqual([{ personalData: false, localPaths: false }]);
        expect(all('stripPurlCredentials')).toHaveLength(2);
        const record = all('recordMergeCounts')[0];
        expect(Object.keys(record.args[1]).sort()).toStrictEqual([
            DEV_COMPONENTS_EXCLUDED_PROPERTY, CREDENTIALS_STRIPPED_PROPERTY, ORPHAN_COMPONENTS_LINKED_PROPERTY, OPTIONAL_SCOPE_DROPPED_PROPERTY
        ].sort());
        expect(record.args[3]).toBe(all('rekeyUnlistedInputs')[0].args[3]);

        // the excluded node reached the engine, the optional one did not
        expect(mergeInputs.objs[0].components.map((c: any) => c.name)).toContain('types');
        expect(mergeInputs.objs[0].components.map((c: any) => c.name)).not.toContain('opt');
        expect(property(bom, OPTIONAL_SCOPE_DROPPED_PROPERTY)).toBe('1');
        expect(property(bom, CREDENTIALS_STRIPPED_PROPERTY)).toBe('1');
        for (const name of [EXCLUDED_SCOPE_DROPPED_PROPERTY, PERSONAL_DATA_STRIPPED_PROPERTY, LOCAL_PATHS_STRIPPED_PROPERTY]) {
            expect(property(bom, name)).toBeUndefined();
        }
    });

    it('FDAW-19 3.2: null and false run every pass, only true skips one', async () => {
        for (const off of [null, false]) {
            passes.calls.length = 0;
            const bom = await mergeBoms(['one'], {
                name: 'merged', group: 'Example', version: '1.0.0', purl: ROOT, structure: 'FLAT', tldOnly: false, ignoreDev: false,
                keepExcludedScope: off, keepPersonalData: off, keepLocalPaths: off
            } as any, 'org');
            expect(passes.calls.find(c => c.name === 'dropExcludedAndDevComponents')!.args[1]).toStrictEqual({ dropOptional: false, dropExcluded: true });
            expect(passes.calls.find(c => c.name === 'stripPersonalDataAndLocalPaths')!.args[1]).toStrictEqual({ personalData: true, localPaths: true });
            for (const name of [EXCLUDED_SCOPE_DROPPED_PROPERTY, PERSONAL_DATA_STRIPPED_PROPERTY, LOCAL_PATHS_STRIPPED_PROPERTY]) {
                expect(property(bom, name)).toMatch(/^\d+$/);
            }
        }
    });
});
