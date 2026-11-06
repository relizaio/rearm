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
    CREDENTIALS_STRIPPED_PROPERTY,
    ORPHAN_COMPONENTS_LINKED_PROPERTY,
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
        expect(at('dropExcludedAndDevComponents').args[1]).toStrictEqual({ dropOptional: false });
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
        for (const name of MERGE_COUNT_PROPERTIES) expect(property(bom, name)).toMatch(/^\d+$/);
    });

    it('drops optional scope only under ignoreDev, and assigns the keyless ref when the whole input is merged', async () => {
        const bom = await mergeBoms(['one'], { name: 'merged', group: 'Example', version: '1.0.0', purl: ROOT, structure: 'FLAT', tldOnly: false, ignoreDev: true } as any, 'org');
        expect(passes.calls.find(c => c.name === 'dropExcludedAndDevComponents')!.args[1]).toStrictEqual({ dropOptional: true });
        expect(mergeInputs.objs[0].components.map((c: any) => c['bom-ref'])).toStrictEqual(['pkg:npm/lib@1.0.0', CLEAN_PURL, 'vendored@2.0']);
        expect(property(bom, EXCLUDED_SCOPE_DROPPED_PROPERTY)).toBe('1');
        // the credentialed purl and the keyless node are in no entry of the fake engine's output
        expect(property(bom, ORPHAN_COMPONENTS_LINKED_PROPERTY)).toBe('2');
    });
});
