import { describe, it, expect, vi, beforeEach } from 'vitest';

// mergeBoms reads its inputs through bomCrudService and runs `rearm bomutils merge-boms`
// through utils; both are mocked so the test controls the merged document merge-boms
// returns, and everything rebom does to it afterwards runs for real.
const findBomObjectById = vi.fn();
const shellExec = vi.fn();
// SCORE-14 T-12: the passes mergeBoms runs, in the order it runs them, with their arguments.
// SCORE-40 T-4: each recorded pass also keeps what it returned.
const passes = vi.hoisted(() => ({ calls: [] as { name: string, args: any[], result?: any }[] }));
// SCORE-57 T-4: the calls of listedReleaseInputs, with what each returned.
const listed = vi.hoisted(() => ({ calls: [] as { args: any[], result?: any }[] }));
// SCORE-15 T-10: the documents mergeBomObjects hands merge-boms, in input-file order.
const mergeInputs = vi.hoisted(() => ({ objs: [] as any[] }));
// SCORE-44 T-4: the settle, the validations, the CLI-path record and the de-dup, in call order.
const steps = vi.hoisted(() => ({ names: [] as string[] }));

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

vi.mock('../../src/validateBom', async (importOriginal) => {
    const actual: any = await importOriginal();
    return {
        default: (...args: any[]) => {
            steps.names.push('validateBom');
            return actual.default(...args);
        }
    };
});

vi.mock('../../src/services/bom/bomProcessingService', async (importOriginal) => {
    const actual: any = await importOriginal();
    const stepped = (name: string) => (...args: any[]) => {
        steps.names.push(name);
        return actual[name](...args);
    };
    const recorded = (name: string) => (...args: any[]) => {
        const call: { name: string, args: any[], result?: any } = { name, args };
        steps.names.push(name);
        passes.calls.push(call);
        call.result = actual[name](...args);
        return call.result;
    };
    return {
        ...actual,
        // SCORE-66 T-2: the re-key before merge-boms and the count stamp right after it.
        rekeyUnlistedInputs: recorded('rekeyUnlistedInputs'),
        recordRekeyedNodes: recorded('recordRekeyedNodes'),
        carryServices: recorded('carryServices'),
        dedupeBomRefs: recorded('dedupeBomRefs'),
        foldReleaseRoots: recorded('foldReleaseRoots'),
        // SCORE-57 T-4: kept apart from the pass list; what it returned is what the fold is handed.
        listedReleaseInputs: (...args: any[]) => {
            const call: { args: any[], result?: any } = { args };
            listed.calls.push(call);
            call.result = actual.listedReleaseInputs(...args);
            return call.result;
        },
        fillReleaseNode: recorded('fillReleaseNode'),
        // SCORE-63 T-2: the attached root fill, right after the release node fill.
        fillAttachedRoots: recorded('fillAttachedRoots'),
        // SCORE-60 T-9: the release node stamp, right after the fill.
        markReleaseNode: recorded('markReleaseNode'),
        // SCORE-64 T-2: the unfolded root link, after the stamp and the file filter, before the dangling check.
        linkUnfoldedRoots: recorded('linkUnfoldedRoots'),
        extractFileFilteredBom: recorded('extractFileFilteredBom'),
        dropDanglingRefs: recorded('dropDanglingRefs'),
        declareUnknownCompositions: recorded('declareUnknownCompositions'),
        settleLicenseFolds: stepped('settleLicenseFolds'),
        recordListedLicenseDrops: stepped('recordListedLicenseDrops')
    };
});

import { mergeBoms } from '../../src/services/bom/bomMergeService';
import { logger } from '../../src/logger';
import { BomStructure } from '../../src/types';
import {
    BOM_REFS_DEDUPLICATED_PROPERTY,
    COMPONENTS_WITHOUT_SBOM_PROPERTY,
    DROPPED_LICENSE_PROPERTY,
    FILE_COMPONENTS_EXCLUDED_PROPERTY,
    DANGLING_REFS_DROPPED_PROPERTY,
    RELEASE_ROOTS_FOLDED_PROPERTY,
    FOLDED_ROOT_PROPERTY,
    RELEASE_NODE_PROPERTY,
    NODES_REKEYED_PROPERTY
} from '../../src/services/bom/bomProcessingService';

/** SCORE-60: rebom's stamp on every merged root (ADR-1). */
const STAMP = { name: RELEASE_NODE_PROPERTY, value: 'true' };

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
    listed.calls.length = 0;
    steps.names.length = 0;
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

        expect(passes.calls.map(c => c.name)).toStrictEqual(['rekeyUnlistedInputs', 'merge-boms', 'recordRekeyedNodes', 'carryServices', 'dedupeBomRefs', 'foldReleaseRoots', 'fillReleaseNode', 'fillAttachedRoots', 'markReleaseNode', 'extractFileFilteredBom', 'linkUnfoldedRoots', 'dropDanglingRefs', 'declareUnknownCompositions']);
        const carry = passes.calls[3];
        expect(carry.args[1]).toHaveLength(2);
        expect(carry.args[1][0]).toBe(inputs[0]);
        expect(carry.args[1][1]).toBe(inputs[1]);
        expect(carry.args[2]).toStrictEqual({ referencedOnly: false });
        // each pass works on what the one before it returned
        expect(passes.calls[4].args[0]).toBe(carry.args[0]);
        expect(passes.calls[9].args[0]).toBe(passes.calls[8].result.bom);
        expect(passes.calls[10].args[0]).not.toBe(passes.calls[9].args[0]);
        expect(passes.calls[10].args[0].metadata.properties).toContainEqual({ name: FILE_COMPONENTS_EXCLUDED_PROPERTY, value: '1' });
        // SCORE-64 T-2: the unfolded root link is handed the file filter's result, the dangling check what it returned
        expect(passes.calls[10].name).toBe('linkUnfoldedRoots');
        expect(passes.calls[11].args[0]).toBe(passes.calls[10].result.bom);
        expect(bom.dependencies).toStrictEqual([{ ref: ROOT, dependsOn: ['pkg:npm/a@1.0.0', 'pkg:npm/b@1.0.0'] }]);
    });

    it('skips the file filter without the switch and still runs dropDanglingRefs then declareUnknownCompositions (T-12)', async () => {
        await mergeBoms(['one', 'two'], options(), 'org');

        expect(passes.calls.map(c => c.name)).toStrictEqual(['rekeyUnlistedInputs', 'merge-boms', 'recordRekeyedNodes', 'carryServices', 'dedupeBomRefs', 'foldReleaseRoots', 'fillReleaseNode', 'fillAttachedRoots', 'markReleaseNode', 'linkUnfoldedRoots', 'dropDanglingRefs', 'declareUnknownCompositions']);
        expect(passes.calls[3].args[2]).toStrictEqual({ referencedOnly: false });
    });

    it('asks carryServices for referenced services only in the top-level-only merge (T-12)', async () => {
        await mergeBoms(['one', 'two'], options({ tldOnly: true }), 'org');

        expect(passes.calls.map(c => c.name)).toStrictEqual(['rekeyUnlistedInputs', 'merge-boms', 'recordRekeyedNodes', 'carryServices', 'dedupeBomRefs', 'foldReleaseRoots', 'fillReleaseNode', 'fillAttachedRoots', 'markReleaseNode', 'linkUnfoldedRoots', 'dropDanglingRefs', 'declareUnknownCompositions']);
        expect(passes.calls[3].args[2]).toStrictEqual({ referencedOnly: true });
    });

    it('treats an absent tldOnly as a full merge (T-12)', async () => {
        const opts = options();
        delete opts.tldOnly;
        await mergeBoms(['one', 'two'], opts, 'org');

        expect(passes.calls[3].args[2]).toStrictEqual({ referencedOnly: false });
    });

    it('fills the licence with the release node fill, on what dedupeBomRefs returned (SCORE-40 T-4)', async () => {
        const releaseNode = { supplierName: 'Acme', licenseExpression: 'LicenseRef-Acme-Commercial', licenseUrl: 'https://acme.example/licence' };

        const bom = await mergeBoms(['one', 'two'], options({ releaseNode }), 'org');

        expect(passes.calls.map(c => c.name)).toStrictEqual(['rekeyUnlistedInputs', 'merge-boms', 'recordRekeyedNodes', 'carryServices', 'dedupeBomRefs', 'foldReleaseRoots', 'fillReleaseNode', 'fillAttachedRoots', 'markReleaseNode', 'linkUnfoldedRoots', 'dropDanglingRefs', 'declareUnknownCompositions']);
        expect(passes.calls[6].name).toBe('fillReleaseNode');
        expect(passes.calls[6].args[1]).toBe(releaseNode);
        expect(passes.calls[6].args[0]).toBe(passes.calls[5].result.bom);
        expect(passes.calls[6].args[0].metadata.component).not.toHaveProperty('licenses');
        expect(passes.calls[6].result.filled).toStrictEqual({ supplier: true, hashes: false, licenses: true });
        // the attached root fill works on the filled document, the stamp on what it returned, the pass
        // after it on the stamped one (SCORE-60 T-9, SCORE-63 T-2)
        expect(passes.calls[7].name).toBe('fillAttachedRoots');
        expect(passes.calls[7].args[0]).toBe(passes.calls[6].result.bom);
        expect(passes.calls[8].name).toBe('markReleaseNode');
        expect(passes.calls[8].args[0]).toBe(passes.calls[7].result.bom);
        expect(passes.calls[9].args[0]).toBe(passes.calls[8].result.bom);
        // SCORE-64 T-2: [9] is the unfolded root link, handed the stamp's result; the dangling check runs on what it returned
        expect(passes.calls[9].name).toBe('linkUnfoldedRoots');
        expect(passes.calls[10].args[0]).toBe(passes.calls[9].result.bom);
        expect(bom.metadata.component).toStrictEqual({
            type: 'application', name: 'merged', version: '1.0.0', purl: ROOT, 'bom-ref': ROOT,
            supplier: { name: 'Acme' },
            licenses: [{ license: { name: 'LicenseRef-Acme-Commercial', url: 'https://acme.example/licence' } }],
            properties: [STAMP]
        });
    });
});

/**
 * SCORE-40 (T-11, ADR-6): merge-boms folds a duplicate's license entries onto a survivor that holds
 * an expression, a mixed array CycloneDX 1.6 refuses. mergeBomObjects settles it before its first
 * validation, survivor first, inside the merge-boms step: no pass is added or moved.
 */
describe('mergeBoms: a licence fold merge-boms left mixed', () => {
    beforeEach(serveInputsAndMergeBomsOutput);

    it('keeps the survivor expression alone, resolves, and runs the same passes in the same order (T-11)', async () => {
        shellExec.mockImplementation(async (...args: any[]) => {
            passes.calls.push({ name: 'merge-boms', args });
            const out = mergeBomsOutput();
            delete out.components[1].components;
            out.components.unshift({ type: 'application', name: 'one', version: '1.0.0', purl: 'pkg:npm/one@1.0.0', 'bom-ref': 'pkg:npm/one@1.0.0',
                licenses: [{ expression: 'MIT OR Apache-2.0' }, { license: { id: 'MIT' } }] });
            out.dependencies[0].dependsOn.unshift('pkg:npm/one@1.0.0');
            return JSON.stringify(out);
        });

        const bom = await mergeBoms(['one', 'two'], options(), 'org');

        expect(bom.components.map((c: any) => c['bom-ref'])).toStrictEqual(['pkg:npm/one@1.0.0', 'pkg:npm/a@1.0.0', 'file-tool', 'pkg:npm/b@1.0.0']);
        expect(bom.components[0].licenses).toStrictEqual([{ expression: 'MIT OR Apache-2.0' }]);
        expect(passes.calls.map(c => c.name)).toStrictEqual(['rekeyUnlistedInputs', 'merge-boms', 'recordRekeyedNodes', 'carryServices', 'dedupeBomRefs', 'foldReleaseRoots', 'fillReleaseNode', 'fillAttachedRoots', 'markReleaseNode', 'linkUnfoldedRoots', 'dropDanglingRefs', 'declareUnknownCompositions']);
        // the de-dup already sees the settled list
        expect(passes.calls[4].args[0].components[0].licenses).toStrictEqual([{ expression: 'MIT OR Apache-2.0' }]);
    });
});

const LISTED_DROP = 'merge-boms will drop the listed expression: the survivor licenses stand';

/**
 * SCORE-40 (T-11, ADR-8): under FLAT merge-boms drops a listed expression beside the survivor's
 * licences without a word. mergeBomObjects names it from the inputs before it runs merge-boms;
 * under HIERARCHICAL merge-boms folds nothing and the de-dup logs its own drops.
 */
describe('mergeBoms: the expression merge-boms drops is logged before it runs', () => {
    const ONE = 'pkg:npm/one@1.0.0';

    beforeEach(() => {
        serveInputsAndMergeBomsOutput();
        findBomObjectById.mockImplementation(async (id: string) => {
            const doc = input(id);
            doc.metadata.component.purl = doc.metadata.component['bom-ref'];
            if (id === 'one') doc.metadata.component.licenses = [{ license: { id: 'MIT' } }];
            if (id === 'two') doc.components = [{ type: 'library', name: 'one', version: '1.0.0', purl: ONE, 'bom-ref': ONE, licenses: [{ expression: 'BSD-3-Clause OR MIT' }] }];
            return doc;
        });
    });

    it('logs one line with the bom-ref, the kept ids and the dropped expression, before merge-boms runs (T-11)', async () => {
        const info = vi.spyOn(logger, 'info');
        try {
            await expect(mergeBoms(['one', 'two'], options(), 'org')).resolves.toBeDefined();

            const drops = info.mock.calls.map((call, i) => ({ call, order: info.mock.invocationCallOrder[i] }))
                .filter(({ call }) => call[1] === LISTED_DROP);
            expect(drops.map(d => d.call)).toStrictEqual([
                [{ bomRef: ONE, kept: [{ license: { id: 'MIT' } }], dropped: [{ expression: 'BSD-3-Clause OR MIT' }] }, LISTED_DROP]
            ]);
            expect(shellExec).toHaveBeenCalledTimes(1);
            expect(drops[0].order).toBeLessThan(shellExec.mock.invocationCallOrder[0]);
            // merge-boms stderr is logged at info (SCORE-68 T-6, ADR-4)
            expect(shellExec.mock.calls[0][3]).toBe('info');
        } finally {
            info.mockRestore();
        }
    });

    // merge-boms runs FLAT for every structure but HIERARCHICAL (merge_boms.go compares with "HIERARCHICAL"
    // alone); rebom passes FLAT when the option is unset. [case, structure, the --structure merge-boms gets]
    const NOT_HIERARCHICAL: [string, string | undefined, string][] = [
        ['unset', undefined, BomStructure.FLAT],
        ['hierarchical in lower case', 'hierarchical', 'hierarchical']
    ];
    for (const [label, structure, flag] of NOT_HIERARCHICAL) {
        it(`logs the same line, before merge-boms runs, when the structure is ${label} (T-11, round 4)`, async () => {
            const info = vi.spyOn(logger, 'info');
            try {
                await expect(mergeBoms(['one', 'two'], options({ structure }), 'org')).resolves.toBeDefined();

                const drops = info.mock.calls.map((call, i) => ({ call, order: info.mock.invocationCallOrder[i] }))
                    .filter(({ call }) => call[1] === LISTED_DROP);
                expect(drops.map(d => d.call)).toStrictEqual([
                    [{ bomRef: ONE, kept: [{ license: { id: 'MIT' } }], dropped: [{ expression: 'BSD-3-Clause OR MIT' }] }, LISTED_DROP]
                ]);
                expect(shellExec).toHaveBeenCalledTimes(1);
                const command: string[] = shellExec.mock.calls[0][1];
                const at = command.indexOf('--structure');
                expect(command.slice(at, at + 2)).toStrictEqual(['--structure', flag]);
                expect(drops[0].order).toBeLessThan(shellExec.mock.invocationCallOrder[0]);
            } finally {
                info.mockRestore();
            }
        });
    }

    it('logs no such line under HIERARCHICAL (T-11)', async () => {
        const info = vi.spyOn(logger, 'info');
        try {
            await expect(mergeBoms(['one', 'two'], options({ structure: BomStructure.HIERARCHICAL }), 'org')).resolves.toBeDefined();

            expect(shellExec).toHaveBeenCalledTimes(1);
            expect(info.mock.calls.filter(call => call[1] === LISTED_DROP)).toStrictEqual([]);
        } finally {
            info.mockRestore();
        }
    });
});

/**
 * SCORE-44 (T-4, ADR-2 site 3): the expression merge-boms drops under FLAT, as logListedLicenseDrops
 * predicted it from the inputs, is recorded as reliza:license:dropped on the top-level component of
 * its key in the CLI's output, after processBomObj and before the last validation of
 * mergeBomObjects, so before the de-dup of mergeBoms; only when that node does not hold it.
 */
describe('mergeBoms: the expression merge-boms dropped is recorded on its node', () => {
    const ONE = 'pkg:npm/one@1.0.0';
    const X2_TEXT = 'BSD-3-Clause OR MIT';
    const RECORD = [{ name: DROPPED_LICENSE_PROPERTY, value: X2_TEXT }];
    const NOT_RECORDED = 'merge-boms left no top-level component of the key: the dropped licence expression is not recorded';

    const survivor = (licenses: any[] = [{ license: { id: 'MIT' } }]) =>
        ({ type: 'application', name: 'one', version: '1.0.0', purl: ONE, 'bom-ref': ONE, licenses });

    /** merge-boms returns `edit` applied to its output, with the survivor of ONE added top-level unless `edit` says otherwise. */
    function cliReturns(edit: (out: any) => void = () => undefined) {
        shellExec.mockImplementation(async (...args: any[]) => {
            passes.calls.push({ name: 'merge-boms', args });
            const out = mergeBomsOutput();
            delete out.components[1].components;
            out.components.push(survivor());
            out.dependencies[0].dependsOn.push(ONE);
            edit(out);
            return JSON.stringify(out);
        });
    }

    /** The node of ONE among the top-level components of the document mergeBomObjects returned. */
    function merged(): any {
        return passes.calls.find(c => c.name === 'carryServices')!.args[0];
    }

    function topLevelOne(bom: any): any {
        return bom.components.find((c: any) => c['bom-ref'] === ONE);
    }

    beforeEach(() => {
        serveInputsAndMergeBomsOutput();
        findBomObjectById.mockImplementation(async (id: string) => {
            const doc = input(id);
            doc.metadata.component.purl = doc.metadata.component['bom-ref'];
            if (id === 'one') doc.metadata.component.licenses = [{ license: { id: 'MIT' } }];
            if (id === 'two') doc.components = [{ type: 'library', name: 'one', version: '1.0.0', purl: ONE, 'bom-ref': ONE, licenses: [{ expression: X2_TEXT }] }];
            return doc;
        });
        cliReturns();
    });

    it('records it on the survivor after processBomObj, before the last validation and the de-dup (T-4)', async () => {
        const bom = await mergeBoms(['one', 'two'], options(), 'org');

        expect(topLevelOne(merged()).properties).toStrictEqual(RECORD);
        expect(topLevelOne(merged()).licenses).toStrictEqual([{ license: { id: 'MIT' } }]);
        expect(topLevelOne(bom).properties).toStrictEqual(RECORD);
        const at = steps.names.indexOf('recordListedLicenseDrops');
        expect(steps.names.filter(n => n === 'recordListedLicenseDrops')).toHaveLength(1);
        expect(steps.names.lastIndexOf('settleLicenseFolds')).toBeLessThan(at);
        expect(steps.names[at + 1]).toBe('validateBom');
        expect(steps.names.indexOf('dedupeBomRefs')).toBeGreaterThan(at + 1);
    });

    it('records it the same when the structure is unset (T-4)', async () => {
        await mergeBoms(['one', 'two'], options({ structure: undefined }), 'org');

        expect(topLevelOne(merged()).properties).toStrictEqual(RECORD);
    });

    it('records nothing under HIERARCHICAL (T-4)', async () => {
        await mergeBoms(['one', 'two'], options({ structure: BomStructure.HIERARCHICAL }), 'org');

        expect(topLevelOne(merged())).not.toHaveProperty('properties');
    });

    it('records nothing when the node merge-boms returns holds the expression after all (T-4, the re-check)', async () => {
        cliReturns(out => { out.components[out.components.length - 1] = survivor([{ expression: X2_TEXT }]); });

        await mergeBoms(['one', 'two'], options(), 'org');

        expect(topLevelOne(merged())).not.toHaveProperty('properties');
    });

    it('records nothing, without an error, and logs one info line when merge-boms left no node of the key (T-4)', async () => {
        cliReturns(out => {
            out.components.pop();
            out.dependencies[0].dependsOn.pop();
        });
        const info = vi.spyOn(logger, 'info');
        try {
            const bom = await mergeBoms(['one', 'two'], options(), 'org');

            expect(JSON.stringify(bom)).not.toContain(DROPPED_LICENSE_PROPERTY);
            expect(info.mock.calls.filter(call => call[1] === NOT_RECORDED))
                .toStrictEqual([[{ key: ONE, expressions: [X2_TEXT] }, NOT_RECORDED]]);
        } finally {
            info.mockRestore();
        }
    });

    it('records on the top-level node, not on a nested copy of its purl that comes first in the walk (T-4, F-2)', async () => {
        const nested = { type: 'library', name: 'one', version: '1.0.0', purl: ONE, 'bom-ref': 'nested-one', licenses: [{ license: { id: 'MIT' } }] };
        cliReturns(out => { out.components[0].components = [nested]; });

        const bom = await mergeBoms(['one', 'two'], options(), 'org');

        expect(merged().components[0]['bom-ref']).toBe('pkg:npm/a@1.0.0');
        expect(merged().components[0].components[0]).not.toHaveProperty('properties');
        expect(topLevelOne(merged()).properties).toStrictEqual(RECORD);
        expect(topLevelOne(bom).properties).toStrictEqual(RECORD);
    });

    it('records on the top-level node, not on a merged root that carries its purl (T-4, F-2)', async () => {
        cliReturns(out => { out.metadata.component.purl = ONE; });

        await mergeBoms(['one', 'two'], options(), 'org');

        expect(merged().metadata.component.purl).toBe(ONE);
        expect(merged().metadata.component).not.toHaveProperty('properties');
        expect(topLevelOne(merged()).properties).toStrictEqual(RECORD);
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
        expect(passes.calls[1].args[1].filter((a: string) => a.startsWith('/tmp/merge-input-'))).toStrictEqual([
            '/tmp/merge-input-0.json', '/tmp/merge-input-1.json', '/tmp/merge-input-2.json', '/tmp/merge-input-3.json'
        ]);
    });

    it('runs declareUnknownCompositions last, on what dropDanglingRefs returned, with the list sent (T-10)', async () => {
        const list = [missing(HTTPD, 'httpd'), missing(FLYWAY, 'flyway')];
        const bom = await mergeBoms(['one', 'two'], options({ excludeFileComponents: true, missingSbomComponents: list }), 'org');

        expect(passes.calls.map(c => c.name)).toStrictEqual(['rekeyUnlistedInputs', 'merge-boms', 'recordRekeyedNodes', 'carryServices', 'dedupeBomRefs', 'foldReleaseRoots', 'fillReleaseNode', 'fillAttachedRoots', 'markReleaseNode', 'extractFileFilteredBom', 'linkUnfoldedRoots', 'dropDanglingRefs', 'declareUnknownCompositions']);
        // carryServices sees every input merge-boms saw, the placeholders included
        expect(passes.calls[3].args[1].map((b: any) => b.metadata.component['bom-ref'])).toStrictEqual([
            'pkg:npm/one@1.0.0', 'pkg:npm/two@1.0.0', `urn:rearm:release:${HTTPD}`, `urn:rearm:release:${FLYWAY}`
        ]);
        expect(passes.calls[12].args[0]).toBe(passes.calls[11].args[0]);
        expect(passes.calls[12].args[1]).toBe(list);
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

    it('hands merge-boms placeholders carrying the licence ReARM sent, and none where it sent none (SCORE-40 T-4)', async () => {
        const licensed = { ...missing(HTTPD, 'httpd'), licenseExpression: 'AGPL-3.0-only' };

        await mergeBoms(['one', 'two'], options({ missingSbomComponents: [licensed, missing(FLYWAY, 'flyway')] }), 'org');

        expect(mergeInputs.objs.map((b: any) => b.metadata.component['bom-ref'])).toStrictEqual([
            'pkg:npm/one@1.0.0', 'pkg:npm/two@1.0.0', `urn:rearm:release:${HTTPD}`, `urn:rearm:release:${FLYWAY}`
        ]);
        expect(mergeInputs.objs[2].metadata.component.licenses).toStrictEqual([{ license: { id: 'AGPL-3.0-only' } }]);
        expect(Object.keys(mergeInputs.objs[2].metadata.component)).toStrictEqual(
            ['type', 'name', 'version', 'group', 'supplier', 'licenses', 'bom-ref', 'properties']);
        expect(mergeInputs.objs[3].metadata.component).not.toHaveProperty('licenses');
    });

    it('hands merge-boms the stored inputs alone and writes no composition without the list (T-10)', async () => {
        serveInputsAndMergeBomsOutput();

        const bom = await mergeBoms(['one', 'two'], options(), 'org');

        expect(mergeInputs.objs.map((b: any) => b.metadata.component['bom-ref'])).toStrictEqual(['pkg:npm/one@1.0.0', 'pkg:npm/two@1.0.0']);
        expect(bom).not.toHaveProperty('compositions');
        expect(property(bom, COMPONENTS_WITHOUT_SBOM_PROPERTY)).toStrictEqual([]);
    });
});

/**
 * SCORE-30 (T-6, ADR-6, ADR-7): the release node is filled right after the bom-ref de-dup, with
 * the facts the merge options carry, on what the de-dup returned; the file filter, the dangling
 * check and the composition pass then run on the filled document.
 */
describe('mergeBoms: the release node fill', () => {
    beforeEach(serveInputsAndMergeBomsOutput);

    const SHA256 = '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08';
    const SHA512 = 'ee26b0dd4af7e749aa1a8ee3c10ae9923f618980772e473f8819a5d4940e0db27ac185f8a0e1d5f84f88bc887fd67b143732c304cc5fa9ad8e6f57f50028a8ff';
    const facts = () => ({ supplierName: 'Example', hashes: [{ alg: 'SHA-256', content: SHA256 }, { alg: 'SHA-512', content: SHA512 }] });

    it('runs after dedupeBomRefs and before the file filter, on what the de-dup returned, with the facts sent (T-6)', async () => {
        const releaseNode = facts();
        const bom = await mergeBoms(['one', 'two'], options({ excludeFileComponents: true, releaseNode }), 'org');

        expect(passes.calls.map(c => c.name)).toStrictEqual(['rekeyUnlistedInputs', 'merge-boms', 'recordRekeyedNodes', 'carryServices', 'dedupeBomRefs', 'foldReleaseRoots', 'fillReleaseNode', 'fillAttachedRoots', 'markReleaseNode', 'extractFileFilteredBom', 'linkUnfoldedRoots', 'dropDanglingRefs', 'declareUnknownCompositions']);
        const fill = passes.calls[6];
        expect(fill.args[1]).toBe(releaseNode);
        // its input is the de-dup's output: the de-dup property is already on it
        expect(fill.args[0].metadata.properties).toContainEqual({ name: BOM_REFS_DEDUPLICATED_PROPERTY, value: '1' });
        expect(fill.args[0].metadata.component).not.toHaveProperty('supplier');
        // the file filter works on the filled document
        expect(passes.calls[9].args[0].metadata.component.supplier).toStrictEqual({ name: 'Example' });
        expect(bom.metadata.component).toStrictEqual({
            type: 'application', name: 'merged', version: '1.0.0', purl: ROOT, 'bom-ref': ROOT,
            supplier: { name: 'Example' },
            hashes: [{ alg: 'SHA-256', content: SHA256 }, { alg: 'SHA-512', content: SHA512 }],
            properties: [STAMP]
        });
    });

    it('is called with undefined facts when the options carry none, and fills nothing (T-6)', async () => {
        const bom = await mergeBoms(['one', 'two'], options(), 'org');

        const fill = passes.calls.find(c => c.name === 'fillReleaseNode')!;
        expect(fill.args[1]).toBeUndefined();
        expect(passes.calls[7].args[0]).toBe(fill.args[0]);
        expect(passes.calls[8].args[0]).toBe(fill.args[0]);
        expect(passes.calls[9].args[0]).toBe(passes.calls[8].result.bom);
        // SCORE-64 T-2: [9] is the unfolded root link, handed the stamp's result; the dangling check runs on what it returned
        expect(passes.calls[9].name).toBe('linkUnfoldedRoots');
        expect(passes.calls[10].args[0]).toBe(passes.calls[9].result.bom);
        expect(bom.metadata.component).toStrictEqual({ type: 'application', name: 'merged', version: '1.0.0', purl: ROOT, 'bom-ref': ROOT, properties: [STAMP] });
    });

    it('keeps the supplier and hashes of an uploaded copy of the root merge-boms placed beside it (T-7, ADR-6)', async () => {
        const own = [{ alg: 'SHA-256', content: '60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752' }];
        shellExec.mockImplementation(async (...args: any[]) => {
            passes.calls.push({ name: 'merge-boms', args });
            const out = mergeBomsOutput();
            delete out.components[1].components;
            out.components.push({ type: 'application', name: 'merged', version: '1.0.0', purl: ROOT, 'bom-ref': ROOT,
                supplier: { name: 'Reliza Hub' }, hashes: own });
            return JSON.stringify(out);
        });

        const bom = await mergeBoms(['one', 'two'], options({ releaseNode: facts() }), 'org');

        expect(bom.components.map((c: any) => c['bom-ref'])).toStrictEqual(['pkg:npm/a@1.0.0', 'file-tool', 'pkg:npm/b@1.0.0']);
        expect(bom.metadata.component.supplier).toStrictEqual({ name: 'Reliza Hub' });
        expect(bom.metadata.component.hashes).toStrictEqual(own);
    });

    it('fills the product root the placeholders join, and the placeholders carry their own hashes (T-6)', async () => {
        shellExec.mockImplementation(async (...args: any[]) => {
            passes.calls.push({ name: 'merge-boms', args });
            return JSON.stringify(mergeBomsOutputWithPlaceholders());
        });
        const withHashes = { ...missing(HTTPD, 'httpd'), hashes: [{ alg: 'SHA-256', content: SHA256 }] };

        const bom = await mergeBoms(['one', 'two'], options({ releaseNode: { supplierName: 'Example' }, missingSbomComponents: [withHashes, missing(FLYWAY, 'flyway')] }), 'org');

        expect(passes.calls.map(c => c.name)).toStrictEqual(['rekeyUnlistedInputs', 'merge-boms', 'recordRekeyedNodes', 'carryServices', 'dedupeBomRefs', 'foldReleaseRoots', 'fillReleaseNode', 'fillAttachedRoots', 'markReleaseNode', 'linkUnfoldedRoots', 'dropDanglingRefs', 'declareUnknownCompositions']);
        expect(bom.metadata.component.supplier).toStrictEqual({ name: 'Example' });
        expect(bom.metadata.component).not.toHaveProperty('hashes');
        expect(mergeInputs.objs[2].metadata.component.hashes).toStrictEqual([{ alg: 'SHA-256', content: SHA256 }]);
        expect(mergeInputs.objs[3].metadata.component).not.toHaveProperty('hashes');
    });
});

/**
 * SCORE-57 (T-4, ADR-1, ADR-2 (c)): the uploaded roots of the release's own RELEASE-attached
 * artifacts fold into the merged root after dedupeBomRefs and before fillReleaseNode, and the fold
 * is handed exactly the documents listedReleaseInputs returns for the ids and the list sent.
 */
describe('mergeBoms: the release root fold', () => {
    beforeEach(serveInputsAndMergeBomsOutput);

    const UP = 'pkg:npm/example/merged@1.0.0?belongsto=RELEASE';
    const DIGEST = '60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752';
    const facts = () => ({ supplierName: 'Example', hashes: [{ alg: 'SHA-256', content: DIGEST }], licenseExpression: 'Apache-2.0' });

    /** An upload attached to the release: its root carries ReARM's identity fields and the qualifier. */
    function releaseInput(serial: string, root: any = {}): any {
        return {
            bomFormat: 'CycloneDX', specVersion: '1.6', version: 1,
            serialNumber: `urn:uuid:5d0b7f6e-0a5c-4b8e-9d1e-${serial}`,
            metadata: { component: { type: 'library', name: 'merged', version: '1.0.0', group: 'Example', purl: UP, 'bom-ref': UP, ...root } },
            components: []
        };
    }

    /** merge-boms's output with the release input's root at the top level (FLAT), as `node`. */
    function serveWithUploadedRoot(node: any, edit: (out: any) => void = () => undefined) {
        shellExec.mockImplementation(async (...args: any[]) => {
            passes.calls.push({ name: 'merge-boms', args });
            const out = mergeBomsOutput();
            delete out.components[1].components;
            out.components.splice(1, 0, node);
            edit(out);
            return JSON.stringify(out);
        });
    }

    function serveInputs(docs: Record<string, any>) {
        findBomObjectById.mockImplementation(async (id: string) => docs[id]);
    }

    it('runs after dedupeBomRefs and before fillReleaseNode, handed only the documents the list names', async () => {
        const docs = { one: input('one'), rel: releaseInput('000000000003'), two: input('two') };
        serveInputs(docs);
        serveWithUploadedRoot({ type: 'library', name: 'merged', version: '1.0.0', group: 'Example', purl: UP, 'bom-ref': UP });

        const bom = await mergeBoms(['one', 'rel', 'two'], options({ releaseRootIds: ['rel'] }), 'org');

        expect(passes.calls.map(c => c.name)).toStrictEqual(['rekeyUnlistedInputs', 'merge-boms', 'recordRekeyedNodes', 'carryServices', 'dedupeBomRefs', 'foldReleaseRoots', 'fillReleaseNode', 'fillAttachedRoots', 'markReleaseNode', 'linkUnfoldedRoots', 'dropDanglingRefs', 'declareUnknownCompositions']);
        // SCORE-63 T-2: listedReleaseInputs is called twice per merge, the fold's call first
        expect(listed.calls).toHaveLength(2);
        expect(listed.calls[0].args[0]).toStrictEqual(['one', 'rel', 'two']);
        expect(listed.calls[0].args[2]).toStrictEqual(['rel']);
        const foldCall = passes.calls[5];
        expect(foldCall.args[0]).toBe(passes.calls[4].result.bom);
        expect(foldCall.args[1]).toBe(listed.calls[0].result);
        expect(foldCall.args[1]).toHaveLength(1);
        expect(foldCall.args[1][0]).toBe(docs.rel);
        expect(foldCall.args[2].releaseRootIds).toStrictEqual(['rel']);
        expect(passes.calls[6].args[0]).toBe(foldCall.result.bom);
        expect(bom.components.map((c: any) => c['bom-ref'])).toStrictEqual(['pkg:npm/a@1.0.0', 'file-tool', 'pkg:npm/b@1.0.0']);
        expect(property(bom, RELEASE_ROOTS_FOLDED_PROPERTY)).toStrictEqual([{ name: RELEASE_ROOTS_FOLDED_PROPERTY, value: '1' }]);
        expect(property(bom, DANGLING_REFS_DROPPED_PROPERTY)).toStrictEqual([]);
    });

    it('hands the fold no document when the request carries no list, and the uploaded root stays', async () => {
        serveInputs({ one: input('one'), rel: releaseInput('000000000003') });
        serveWithUploadedRoot({ type: 'library', name: 'merged', version: '1.0.0', group: 'Example', purl: UP, 'bom-ref': UP });

        const bom = await mergeBoms(['one', 'rel'], options(), 'org');

        expect(passes.calls[5].name).toBe('foldReleaseRoots');
        expect(passes.calls[5].args[1]).toStrictEqual([]);
        expect(passes.calls[5].result.bom).toBe(passes.calls[5].args[0]);
        expect(bom.components.map((c: any) => c['bom-ref'])).toContain(UP);
        expect(property(bom, RELEASE_ROOTS_FOLDED_PROPERTY)).toStrictEqual([]);
    });

    it('leaves an unlisted root with the release name, version and group where merge-boms put it', async () => {
        serveInputs({ one: input('one'), other: releaseInput('000000000004') });
        serveWithUploadedRoot({ type: 'library', name: 'merged', version: '1.0.0', group: 'Example', purl: UP, 'bom-ref': UP, supplier: { name: 'Other' } });

        const bom = await mergeBoms(['one', 'other'], options({ releaseRootIds: ['one'], releaseNode: facts() }), 'org');

        expect(bom.components.map((c: any) => c['bom-ref'])).toContain(UP);
        expect(bom.metadata.component.supplier).toStrictEqual({ name: 'Example' });
        // no fold record: the root carries rebom's stamp alone (SCORE-60 ADR-1)
        expect(bom.metadata.component.properties).toStrictEqual([STAMP]);
    });

    it('lets the fill add only what the folded upload did not say: the upload supplier and licence, the declared digest', async () => {
        serveInputs({ rel: releaseInput('000000000003'), one: input('one') });
        serveWithUploadedRoot({ type: 'library', name: 'merged', version: '1.0.0', group: 'Example', purl: UP, 'bom-ref': UP,
            supplier: { name: 'S' }, licenses: [{ license: { id: 'MIT' } }] });

        const bom = await mergeBoms(['rel', 'one'], options({ releaseRootIds: ['rel'], releaseNode: facts() }), 'org');

        expect(bom.metadata.component.supplier).toStrictEqual({ name: 'S' });
        expect(bom.metadata.component.licenses).toStrictEqual([{ license: { id: 'MIT' } }]);
        expect(bom.metadata.component.hashes).toStrictEqual([{ alg: 'SHA-256', content: DIGEST }]);
        expect(bom.metadata.component.properties).toStrictEqual([{ name: FOLDED_ROOT_PROPERTY, value: UP }, STAMP]);
    });

    it('folds the SCORE-13 repeated-root shape once: the de-dup first, then the fold', async () => {
        serveInputs({ rel: releaseInput('000000000003'), one: input('one') });
        const child = { type: 'library', name: 'c', version: '1.0.0', purl: 'pkg:npm/c@1.0.0', 'bom-ref': 'pkg:npm/c@1.0.0' };
        serveWithUploadedRoot({ type: 'library', name: 'merged', version: '1.0.0', group: 'Example', purl: UP, 'bom-ref': UP,
            components: [child, { type: 'library', name: 'merged', purl: UP, 'bom-ref': UP, description: 'the description' }] });

        const bom = await mergeBoms(['rel', 'one'], options({ releaseRootIds: ['rel'] }), 'org');

        expect(bom.metadata.component.description).toBe('the description');
        expect(bom.metadata.component.components).toStrictEqual([child]);
        expect(JSON.stringify(bom.components)).not.toContain(UP);
        expect(JSON.stringify(bom.dependencies)).not.toContain(UP);
        expect(property(bom, BOM_REFS_DEDUPLICATED_PROPERTY)).toStrictEqual([{ name: BOM_REFS_DEDUPLICATED_PROPERTY, value: '1' }]);
        expect(property(bom, RELEASE_ROOTS_FOLDED_PROPERTY)).toStrictEqual([{ name: RELEASE_ROOTS_FOLDED_PROPERTY, value: '1' }]);
    });

    it('filters a file child of the folded upload once, after the fold', async () => {
        serveInputs({ rel: releaseInput('000000000003'), one: input('one') });
        serveWithUploadedRoot({ type: 'library', name: 'merged', version: '1.0.0', group: 'Example', purl: UP, 'bom-ref': UP,
            components: [{ type: 'file', name: '/etc/conf', 'bom-ref': 'file-conf' }] });

        const bom = await mergeBoms(['rel', 'one'], options({ releaseRootIds: ['rel'], excludeFileComponents: true }), 'org');

        expect(passes.calls.map(c => c.name)).toStrictEqual(['rekeyUnlistedInputs', 'merge-boms', 'recordRekeyedNodes', 'carryServices', 'dedupeBomRefs', 'foldReleaseRoots', 'fillReleaseNode', 'fillAttachedRoots', 'markReleaseNode', 'extractFileFilteredBom', 'linkUnfoldedRoots', 'dropDanglingRefs', 'declareUnknownCompositions']);
        expect(passes.calls[9].args[0].metadata.component.components).toStrictEqual([{ type: 'file', name: '/etc/conf', 'bom-ref': 'file-conf' }]);
        expect(bom.metadata.component.components ?? []).toStrictEqual([]);
        expect(property(bom, FILE_COMPONENTS_EXCLUDED_PROPERTY)).toStrictEqual([{ name: FILE_COMPONENTS_EXCLUDED_PROPERTY, value: '2' }]);
        expect(property(bom, DANGLING_REFS_DROPPED_PROPERTY)).toStrictEqual([]);
    });

    it('leaves the SCORE-30 T-7 identifier case to the de-dup and writes no fold record, even when listed', async () => {
        const own = [{ alg: 'SHA-256', content: DIGEST }];
        const ident = { ...releaseInput('000000000003'), metadata: { component: { type: 'application', name: 'merged', version: '1.0.0', group: 'Example', purl: ROOT, 'bom-ref': ROOT } } };
        serveInputs({ rel: ident, one: input('one') });
        serveWithUploadedRoot({ type: 'application', name: 'merged', version: '1.0.0', purl: ROOT, 'bom-ref': ROOT, supplier: { name: 'Reliza Hub' }, hashes: own });

        const bom = await mergeBoms(['rel', 'one'], options({ releaseRootIds: ['rel'], releaseNode: facts() }), 'org');

        expect(bom.metadata.component.supplier).toStrictEqual({ name: 'Reliza Hub' });
        expect(bom.metadata.component.hashes).toStrictEqual(own);
        // no fold record: the root carries rebom's stamp alone (SCORE-60 ADR-1)
        expect(bom.metadata.component.properties).toStrictEqual([STAMP]);
        expect(property(bom, RELEASE_ROOTS_FOLDED_PROPERTY)).toStrictEqual([]);
        expect(property(bom, BOM_REFS_DEDUPLICATED_PROPERTY)).toStrictEqual([{ name: BOM_REFS_DEDUPLICATED_PROPERTY, value: '1' }]);
    });
});

/**
 * SCORE-60 (T-9, ADR-1): the stamp goes on the merged root only. A stamp a nested node of the merged
 * document already carries (a component row's root the CLI copied into a product) is left as it is:
 * rebom's merge inputs are read without uploaded stamps (ADR-9), and rearm-saas's row rule (ADR-4
 * rule 2: a node the organization lists as a dependency is never its own) stays as the defence.
 */
/**
 * SCORE-63 (T-2, ADR-1): the roots of the release's own SCE- and deliverable-attached uploads, as
 * rearm-saas listed them in attachedRootIds, get the declared licence right after the release node
 * fill, before the stamp, handed exactly the documents listedReleaseInputs returns for that list.
 */
describe('mergeBoms: the attached root fill', () => {
    beforeEach(serveInputsAndMergeBomsOutput);

    const SCE = 'pkg:npm/example/merged@1.0.0?belongsto=SCE&hash=0123456789abcdef0123456789abcdef01234567';

    /** An upload attached to the release's source code entry: ReARM's identity fields and the qualifier. */
    function sceInput(root: any = {}): any {
        return {
            bomFormat: 'CycloneDX', specVersion: '1.6', version: 1,
            serialNumber: 'urn:uuid:5d0b7f6e-0a5c-4b8e-9d1e-000000006301',
            metadata: { component: { type: 'library', name: 'merged', version: '1.0.0', group: 'Example', purl: SCE, 'bom-ref': SCE, ...root } },
            components: []
        };
    }

    /** merge-boms's output with the SCE input's root at the top level (FLAT). */
    function serveWithSceRoot() {
        shellExec.mockImplementation(async (...args: any[]) => {
            passes.calls.push({ name: 'merge-boms', args });
            const out = mergeBomsOutput();
            delete out.components[1].components;
            out.components.splice(1, 0, { type: 'library', name: 'merged', version: '1.0.0', group: 'Example', purl: SCE, 'bom-ref': SCE });
            return JSON.stringify(out);
        });
    }

    it('runs after fillReleaseNode and before markReleaseNode, handed exactly the documents the list names', async () => {
        const docs: Record<string, any> = { sce: sceInput(), one: input('one'), two: input('two') };
        findBomObjectById.mockImplementation(async (id: string) => docs[id]);
        serveWithSceRoot();
        const releaseNode = { licenseExpression: 'Apache-2.0' };
        const rebomOptions = options({ attachedRootIds: ['sce'], releaseNode });

        const bom = await mergeBoms(['sce', 'one', 'two'], rebomOptions, 'org');

        expect(passes.calls.map(c => c.name)).toStrictEqual(['rekeyUnlistedInputs', 'merge-boms', 'recordRekeyedNodes', 'carryServices', 'dedupeBomRefs', 'foldReleaseRoots', 'fillReleaseNode', 'fillAttachedRoots', 'markReleaseNode', 'linkUnfoldedRoots', 'dropDanglingRefs', 'declareUnknownCompositions']);
        expect(listed.calls).toHaveLength(2);
        expect(listed.calls[0].args[2]).toBeUndefined();
        expect(listed.calls[1].args[0]).toStrictEqual(['sce', 'one', 'two']);
        expect(listed.calls[1].args[1]).toBe(passes.calls[0].result.inputs);
        expect(listed.calls[1].args[2]).toStrictEqual(['sce']);
        expect(listed.calls[1].args[3]).toBe('attached root');
        const fillCall = passes.calls[7];
        expect(fillCall.name).toBe('fillAttachedRoots');
        expect(fillCall.args[0]).toBe(passes.calls[6].result.bom);
        expect(fillCall.args[1]).toBe(listed.calls[1].result);
        expect(fillCall.args[1]).toStrictEqual([docs.sce]);
        expect(fillCall.args[1][0]).toBe(docs.sce);
        expect(fillCall.args[2]).toBe(releaseNode);
        expect(fillCall.args[3]).toBe(rebomOptions);
        expect(fillCall.result.count).toBe(1);
        expect(passes.calls[8].args[0]).toBe(fillCall.result.bom);
        expect(bom.metadata.component.licenses).toStrictEqual([{ license: { id: 'Apache-2.0' } }]);
        expect(bom.components.find((c: any) => c['bom-ref'] === SCE).licenses).toStrictEqual([{ license: { id: 'Apache-2.0' } }]);
        expect(bom.components.find((c: any) => c['bom-ref'] === 'pkg:npm/a@1.0.0')).not.toHaveProperty('licenses');
    });

    it('hands the fill no document when the request carries no list, and an unlisted same-name root stays unlicensed', async () => {
        const docs: Record<string, any> = { sce: sceInput(), one: input('one') };
        findBomObjectById.mockImplementation(async (id: string) => docs[id]);
        serveWithSceRoot();

        const bom = await mergeBoms(['sce', 'one'], options({ releaseNode: { licenseExpression: 'Apache-2.0' } }), 'org');

        expect(listed.calls).toHaveLength(2);
        expect(listed.calls[1].args[2]).toBeUndefined();
        expect(passes.calls[7].name).toBe('fillAttachedRoots');
        expect(passes.calls[7].args[1]).toStrictEqual([]);
        expect(passes.calls[7].result.bom).toBe(passes.calls[7].args[0]);
        expect(bom.metadata.component.licenses).toStrictEqual([{ license: { id: 'Apache-2.0' } }]);
        expect(bom.components.find((c: any) => c['bom-ref'] === SCE)).not.toHaveProperty('licenses');
    });

    it('hands the fill the attached list, never releaseRootIds', async () => {
        const docs: Record<string, any> = { sce: sceInput(), one: input('one') };
        findBomObjectById.mockImplementation(async (id: string) => docs[id]);
        serveWithSceRoot();

        await mergeBoms(['sce', 'one'], options({ releaseRootIds: ['one'], attachedRootIds: ['sce'] }), 'org');

        expect(listed.calls[0].args[2]).toStrictEqual(['one']);
        expect(listed.calls[1].args[2]).toStrictEqual(['sce']);
        expect(listed.calls[1].result).toStrictEqual([docs.sce]);
    });
});

describe('mergeBoms: the release node stamp', () => {
    beforeEach(serveInputsAndMergeBomsOutput);

    it('stamps the merged root once, last, and leaves a nested node that carries the stamp untouched', async () => {
        const nested = { type: 'application', name: 'part', version: '2.0.0', purl: 'pkg:generic/Example/part@2.0.0', 'bom-ref': 'pkg:generic/Example/part@2.0.0',
            properties: [{ name: 'reliza:export:foldedRoot', value: 'pkg:npm/part@2.0.0' }, STAMP] };
        shellExec.mockImplementation(async (...args: any[]) => {
            passes.calls.push({ name: 'merge-boms', args });
            const out = mergeBomsOutput();
            out.components.push(nested);
            out.dependencies[0].dependsOn.push(nested['bom-ref']);
            return JSON.stringify(out);
        });

        const bom = await mergeBoms(['one', 'two'], options(), 'org');

        expect(bom.metadata.component.properties).toStrictEqual([STAMP]);
        expect(bom.components.find((c: any) => c['bom-ref'] === nested['bom-ref']).properties).toStrictEqual(nested.properties);
        expect(bom.components.filter((c: any) => (c.properties || []).some((p: any) => p.name === RELEASE_NODE_PROPERTY))).toHaveLength(1);
    });

    it('adds no stamp and does not throw when the merged document has no root', async () => {
        shellExec.mockImplementation(async (...args: any[]) => {
            passes.calls.push({ name: 'merge-boms', args });
            const out = mergeBomsOutput();
            delete out.metadata.component;
            out.dependencies = [{ ref: 'file-tool', dependsOn: ['pkg:npm/b@1.0.0'] }];
            return JSON.stringify(out);
        });

        const bom = await mergeBoms(['one', 'two'], options(), 'org');

        const stamp = passes.calls.find(c => c.name === 'markReleaseNode')!;
        expect(stamp.result.marked).toBe(false);
        expect(stamp.result.bom).toBe(stamp.args[0]);
        expect(bom.metadata.component).toBeUndefined();
    });
});

/**
 * SCORE-66 (T-2, ADR-1): the unlisted inputs are re-keyed before merge-boms, so the CLI and every
 * pass after it see the re-keyed documents; the count is stamped on the CLI's output right after it.
 */
describe('mergeBoms: the re-key of unlisted inputs before merge-boms', () => {
    beforeEach(serveInputsAndMergeBomsOutput);

    const SERIAL = 'urn:uuid:5d0b7f6e-0a5c-4b8e-9d1e-000000006601';
    const Q = `${SERIAL}#${ROOT}`;
    const P = `${ROOT}?rearm_input=${encodeURIComponent(SERIAL)}`;
    const OWN = [{ alg: 'SHA-256', content: 'fcde2b2edba56bf408601fb721fe9b5c338d10ee429ea04fae5511b68fbf8fb9' }];
    const DECLARED = [{ alg: 'SHA-256', content: '2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae' }];
    const facts = () => ({ supplierName: 'Example', hashes: DECLARED, licenseExpression: 'Apache-2.0' });

    /** A component release's row named and versioned like the merged release: its root is the merged root key. */
    function sameNameRow(): any {
        return {
            bomFormat: 'CycloneDX', specVersion: '1.6', version: 1, serialNumber: SERIAL,
            metadata: { component: { type: 'application', name: 'merged', version: '1.0.0', purl: ROOT, 'bom-ref': ROOT,
                supplier: { name: 'S-comp' }, licenses: [{ license: { id: 'MIT' } }], hashes: OWN } },
            components: [],
            dependencies: [{ ref: ROOT, dependsOn: [] }]
        };
    }

    /** merge-boms's output for [row, one] as the real CLI shapes it once the row arrives re-keyed. */
    function serveRekeyedOutput() {
        shellExec.mockImplementation(async (...args: any[]) => {
            passes.calls.push({ name: 'merge-boms', args });
            const out = mergeBomsOutput();
            out.components = [
                { type: 'application', name: 'merged', version: '1.0.0', purl: P, 'bom-ref': Q, supplier: { name: 'S-comp' }, licenses: [{ license: { id: 'MIT' } }], hashes: OWN },
                { type: 'application', name: 'one', 'bom-ref': 'pkg:npm/one@1.0.0' }
            ];
            out.dependencies = [{ ref: ROOT, dependsOn: [Q, 'pkg:npm/one@1.0.0'] }, { ref: Q, dependsOn: [] }, { ref: 'pkg:npm/one@1.0.0', dependsOn: [] }];
            return JSON.stringify(out);
        });
    }

    it('runs the re-key first, then the CLI path, then the count stamp, before carryServices', async () => {
        const docs: Record<string, any> = { row: sameNameRow(), one: input('one') };
        findBomObjectById.mockImplementation(async (id: string) => docs[id]);
        serveRekeyedOutput();

        await mergeBoms(['row', 'one'], options({ releaseRootIds: ['one'], releaseNode: facts() }), 'org');

        const at = (name: string) => steps.names.indexOf(name);
        expect(at('rekeyUnlistedInputs')).toBe(0);
        for (const step of ['settleLicenseFolds', 'validateBom', 'recordListedLicenseDrops']) {
            expect(at(step)).toBeGreaterThan(at('rekeyUnlistedInputs'));
            expect(steps.names.lastIndexOf(step)).toBeLessThan(at('recordRekeyedNodes'));
        }
        expect(at('recordRekeyedNodes')).toBeLessThan(at('carryServices'));
        expect(passes.calls[0].result.count).toBe(1);
        expect(passes.calls[2].args[1]).toBe(1);
        expect(passes.calls[3].args[0]).toBe(passes.calls[2].result);
    });

    it('hands merge-boms the re-keyed documents: the unlisted root written apart, the listed one as it was', async () => {
        const docs: Record<string, any> = { row: sameNameRow(), one: input('one') };
        findBomObjectById.mockImplementation(async (id: string) => docs[id]);
        serveRekeyedOutput();

        await mergeBoms(['row', 'one'], options({ releaseRootIds: ['one'] }), 'org');

        expect(mergeInputs.objs).toHaveLength(2);
        expect(mergeInputs.objs[0]).not.toBe(docs.row);
        expect(mergeInputs.objs[0].metadata.component['bom-ref']).toBe(Q);
        expect(mergeInputs.objs[0].metadata.component.purl).toBe(P);
        expect(mergeInputs.objs[0].dependencies).toStrictEqual([{ ref: Q, dependsOn: [] }]);
        expect(mergeInputs.objs[1]).toBe(docs.one);
        expect(docs.row.metadata.component['bom-ref']).toBe(ROOT);
        // the fold and carryServices are handed the same aligned list merge-boms saw
        expect(listed.calls[0].args[1]).toBe(passes.calls[0].result.inputs);
        expect(listed.calls[0].result).toStrictEqual([docs.one]);
        expect(passes.calls[3].args[1]).toBe(passes.calls[0].result.inputs);
    });

    it('hands merge-boms the original documents in a FLATTEN merge', async () => {
        const docs: Record<string, any> = { row: sameNameRow(), one: input('one') };
        findBomObjectById.mockImplementation(async (id: string) => docs[id]);

        const bom = await mergeBoms(['row', 'one'], options({ rootComponentMergeMode: 'FLATTEN_UNDER_NEW_ROOT' }), 'org');

        expect(mergeInputs.objs[0]).toBe(docs.row);
        expect(mergeInputs.objs[1]).toBe(docs.one);
        expect(passes.calls[0].result.count).toBe(0);
        expect(property(bom, NODES_REKEYED_PROPERTY)).toStrictEqual([]);
    });

    it('treats the attached ids as listed for the re-key: a child node under the SCE root key is written apart (SCORE-63 T-2, ADR-9)', async () => {
        const SCE_REF = 'pkg:npm/sce@1.0.0';
        const SCE_PURL = 'pkg:npm/sce@1.0.0?belongsto=SCE&hash=0123456789abcdef0123456789abcdef01234567';
        const CHILD = 'pkg:npm/child@1.0.0';
        const FORGED = { type: 'library', name: 'sce', version: '1.0.0', 'bom-ref': SCE_REF, purl: SCE_PURL, licenses: [{ license: { id: 'MIT' } }] };
        const Q_SCE = SERIAL + '#' + SCE_REF;
        const P_SCE = SCE_PURL + '&rearm_input=' + encodeURIComponent(SERIAL);
        /** A child row with a root of its own, listing a node under the product SCE root's key. */
        const childRow = (): any => ({
            bomFormat: 'CycloneDX', specVersion: '1.6', version: 1, serialNumber: SERIAL,
            metadata: { component: { type: 'application', name: 'child', version: '1.0.0', purl: CHILD, 'bom-ref': CHILD } },
            components: [FORGED],
            dependencies: [{ ref: CHILD, dependsOn: [SCE_REF] }, { ref: SCE_REF, dependsOn: [] }]
        });
        const sce = input('sce');
        sce.metadata.component.purl = SCE_PURL;
        const docs: Record<string, any> = { sce, row: childRow(), rel: input('rel') };
        findBomObjectById.mockImplementation(async (id: string) => docs[id]);
        serveRekeyedOutput();

        await mergeBoms(['sce', 'row', 'rel'], options({ releaseRootIds: ['rel'], attachedRootIds: ['sce'], releaseNode: facts() }), 'org');

        expect(passes.calls[0].result.count).toBe(1);
        expect(mergeInputs.objs).toHaveLength(3);
        expect(mergeInputs.objs[0]).toBe(docs.sce);
        expect(mergeInputs.objs[2]).toBe(docs.rel);
        expect(mergeInputs.objs[1]).not.toBe(docs.row);
        expect(mergeInputs.objs[1].metadata.component).toStrictEqual(docs.row.metadata.component);
        expect(mergeInputs.objs[1].components[0]['bom-ref']).toBe(Q_SCE);
        expect(mergeInputs.objs[1].components[0].purl).toBe(P_SCE);
        expect(mergeInputs.objs[1].components[0].licenses).toStrictEqual(FORGED.licenses);
        expect(mergeInputs.objs[1].dependencies).toStrictEqual([{ ref: CHILD, dependsOn: [Q_SCE] }, { ref: Q_SCE, dependsOn: [] }]);
        expect(docs.row.components[0]['bom-ref']).toBe(SCE_REF);
        // the fold's list is not widened; the fill's list is the attached one
        expect(listed.calls[0].result).toStrictEqual([docs.rel]);
        expect(listed.calls[1].result).toStrictEqual([docs.sce]);
    });

    it('keeps the product root to the declared facts and the row node apart, with the count (the (iv) shape)', async () => {
        const docs: Record<string, any> = { row: sameNameRow(), one: input('one') };
        findBomObjectById.mockImplementation(async (id: string) => docs[id]);
        serveRekeyedOutput();

        const bom = await mergeBoms(['row', 'one'], options({ releaseRootIds: ['one'], releaseNode: facts() }), 'org');

        expect(property(bom, BOM_REFS_DEDUPLICATED_PROPERTY)).toStrictEqual([]);
        expect(property(bom, RELEASE_ROOTS_FOLDED_PROPERTY)).toStrictEqual([]);
        expect(property(bom, NODES_REKEYED_PROPERTY)).toStrictEqual([{ name: NODES_REKEYED_PROPERTY, value: '1' }]);
        expect(bom.metadata.component).toStrictEqual({
            type: 'application', name: 'merged', version: '1.0.0', purl: ROOT, 'bom-ref': ROOT,
            supplier: { name: 'Example' }, hashes: DECLARED, licenses: [{ license: { id: 'Apache-2.0' } }],
            properties: [STAMP]
        });
        const row = bom.components.find((c: any) => c['bom-ref'] === Q);
        expect(row.purl).toBe(P);
        expect(row.supplier).toStrictEqual({ name: 'S-comp' });
        expect(row.hashes).toStrictEqual(OWN);
        expect(bom.dependencies[0]).toStrictEqual({ ref: ROOT, dependsOn: [Q, 'pkg:npm/one@1.0.0'] });
        expect(bom.dependencies.filter((d: any) => d.ref === ROOT)).toHaveLength(1);
    });
});

/**
 * SCORE-64 (T-2, ADR-2, ADR-3): every input root that survived the merge as a top-level node is a
 * dependency of the merged root. The pass runs after the stamp and the file filter, right before the
 * dangling check, handed the aligned list merge-boms saw; it reads no list of ids.
 */
describe('mergeBoms: the unfolded root link', () => {
    beforeEach(serveInputsAndMergeBomsOutput);

    const SCE = 'pkg:npm/example/merged@1.0.0?belongsto=SCE&hash=0123456789abcdef0123456789abcdef01234567';

    function sceInput(): any {
        return {
            bomFormat: 'CycloneDX', specVersion: '1.6', version: 1,
            serialNumber: 'urn:uuid:5d0b7f6e-0a5c-4b8e-9d1e-000000006401',
            metadata: { component: { type: 'library', name: 'merged', version: '1.0.0', group: 'Example', purl: SCE, 'bom-ref': SCE } },
            components: []
        };
    }

    /** merge-boms's FLATTEN output with the SCE input's root at the top level, the root entry not naming it. */
    function serveWithSceRoot() {
        shellExec.mockImplementation(async (...args: any[]) => {
            passes.calls.push({ name: 'merge-boms', args });
            const out = mergeBomsOutput();
            delete out.components[1].components;
            out.components.splice(1, 0, { type: 'library', name: 'merged', version: '1.0.0', group: 'Example', purl: SCE, 'bom-ref': SCE });
            return JSON.stringify(out);
        });
    }

    /** merge-boms's PRESERVE output: every input root a top-level node the root entry names. */
    function servePreserveOutput() {
        shellExec.mockImplementation(async (...args: any[]) => {
            passes.calls.push({ name: 'merge-boms', args });
            const out = mergeBomsOutput();
            out.components = [
                { type: 'library', name: 'merged', version: '1.0.0', group: 'Example', purl: SCE, 'bom-ref': SCE },
                { type: 'application', name: 'one', 'bom-ref': 'pkg:npm/one@1.0.0' }
            ];
            out.dependencies = [{ ref: ROOT, dependsOn: [SCE, 'pkg:npm/one@1.0.0'] }, { ref: SCE, dependsOn: [] }, { ref: 'pkg:npm/one@1.0.0', dependsOn: [] }];
            return JSON.stringify(out);
        });
    }

    const docs = (): Record<string, any> => ({ sce: sceInput(), one: input('one'), two: input('two') });
    const releaseNode = { licenseExpression: 'Apache-2.0' };

    it('runs after markReleaseNode, right before dropDanglingRefs, on the aligned inputs, and links the SCE root', async () => {
        const d = docs();
        findBomObjectById.mockImplementation(async (id: string) => d[id]);
        serveWithSceRoot();

        const bom = await mergeBoms(['sce', 'one', 'two'], options({ rootComponentMergeMode: 'FLATTEN_UNDER_NEW_ROOT', attachedRootIds: ['sce'], releaseNode }), 'org');

        const names = passes.calls.map(c => c.name);
        expect(names).toStrictEqual(['rekeyUnlistedInputs', 'merge-boms', 'recordRekeyedNodes', 'carryServices', 'dedupeBomRefs', 'foldReleaseRoots', 'fillReleaseNode', 'fillAttachedRoots', 'markReleaseNode', 'linkUnfoldedRoots', 'dropDanglingRefs', 'declareUnknownCompositions']);
        const link = passes.calls[9];
        expect(link.name).toBe('linkUnfoldedRoots');
        expect(link.args[0]).toBe(passes.calls[8].result.bom);
        expect(link.args[1]).toBe(passes.calls[0].result.inputs);
        expect(link.result.count).toBe(1);
        expect(link.result.bom.dependencies[0].dependsOn.at(-1)).toBe(SCE);
        const dangling = passes.calls[10];
        expect(dangling.args[0]).toBe(link.result.bom);
        expect(dangling.result.bom).toBe(dangling.args[0]);
        expect(dangling.result.count).toBe(0);
        expect(property(bom, DANGLING_REFS_DROPPED_PROPERTY)).toStrictEqual([]);
        expect(bom.dependencies[0]).toStrictEqual({ ref: ROOT, dependsOn: ['pkg:npm/a@1.0.0', 'file-tool', 'pkg:npm/b@1.0.0', SCE] });
        expect(bom.metadata.component.properties.at(-1)).toStrictEqual(STAMP);
    });

    it('runs after the file filter when the switch is on', async () => {
        const d = docs();
        findBomObjectById.mockImplementation(async (id: string) => d[id]);
        serveWithSceRoot();

        const bom = await mergeBoms(['sce', 'one', 'two'], options({ rootComponentMergeMode: 'FLATTEN_UNDER_NEW_ROOT', attachedRootIds: ['sce'], excludeFileComponents: true }), 'org');

        const names = passes.calls.map(c => c.name);
        expect(names.indexOf('linkUnfoldedRoots')).toBe(names.indexOf('extractFileFilteredBom') + 1);
        expect(names.indexOf('dropDanglingRefs')).toBe(names.indexOf('linkUnfoldedRoots') + 1);
        const link = passes.calls[names.indexOf('linkUnfoldedRoots')];
        expect(link.args[0]).toBe(passes.calls[names.indexOf('extractFileFilteredBom')].result.bom);
        expect(link.result.count).toBe(1);
        expect(bom.dependencies[0]).toStrictEqual({ ref: ROOT, dependsOn: ['pkg:npm/a@1.0.0', 'pkg:npm/b@1.0.0', SCE] });
        expect(property(bom, DANGLING_REFS_DROPPED_PROPERTY)).toStrictEqual([]);
    });

    it('links the root all the same without attachedRootIds: the pass reads no list', async () => {
        const d = docs();
        findBomObjectById.mockImplementation(async (id: string) => d[id]);
        serveWithSceRoot();

        const bom = await mergeBoms(['sce', 'one', 'two'], options({ rootComponentMergeMode: 'FLATTEN_UNDER_NEW_ROOT', releaseNode }), 'org');

        const link = passes.calls.find(c => c.name === 'linkUnfoldedRoots')!;
        expect(link.args).toHaveLength(2);
        expect(link.args[1]).toBe(passes.calls[0].result.inputs);
        expect(link.args[1]).toHaveLength(3);
        expect(link.result.count).toBe(1);
        expect(bom.dependencies[0]).toStrictEqual({ ref: ROOT, dependsOn: ['pkg:npm/a@1.0.0', 'file-tool', 'pkg:npm/b@1.0.0', SCE] });
    });

    it('does nothing on a PRESERVE output, where every root is named already', async () => {
        const d: Record<string, any> = { sce: sceInput(), one: input('one') };
        findBomObjectById.mockImplementation(async (id: string) => d[id]);
        servePreserveOutput();

        const bom = await mergeBoms(['sce', 'one'], options({ rootComponentMergeMode: 'PRESERVE_UNDER_NEW_ROOT', attachedRootIds: ['sce'] }), 'org');

        const link = passes.calls.find(c => c.name === 'linkUnfoldedRoots')!;
        expect(link.result.count).toBe(0);
        expect(link.result.bom).toBe(link.args[0]);
        expect(bom.dependencies[0]).toStrictEqual({ ref: ROOT, dependsOn: [SCE, 'pkg:npm/one@1.0.0'] });
    });
});
