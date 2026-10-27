import { describe, it, expect } from 'vitest';
import { stripReleaseNodeStamps, isRebomMergeOutput, RELEASE_NODE_PROPERTY, REBOM_MERGE_MOD } from '../../src/services/bom/releaseNodeStamp';

/**
 * SCORE-60 (T-23, ADR-9): rebom removes `reliza:export:releaseNode` from every document it did not
 * merge, at any depth and whatever its value, so the only stamp rearm-saas ever reads is rebom's own.
 */

const STAMP = { name: RELEASE_NODE_PROPERTY, value: 'true' };
const FOLDED = { name: 'reliza:export:foldedRoot', value: 'pkg:npm/x@1.0.0' };
const SUPPORT = { name: 'reliza:support:levelOfSupport', value: 'ACTIVELY_MAINTAINED' };
const KEEP = { name: 'acme:keep', value: 'yes' };

function stampedEverywhere(): any {
    return {
        bomFormat: 'CycloneDX',
        specVersion: '1.6',
        metadata: {
            component: {
                type: 'application', name: 'root', 'bom-ref': 'pkg:npm/root@1.0.0', properties: [KEEP, STAMP],
                components: [{ type: 'library', name: 'under-root', 'bom-ref': 'pkg:npm/under-root@1.0.0', properties: [STAMP, FOLDED] }]
            },
            tools: { components: [{ type: 'application', name: 'tool', properties: [STAMP] }] },
            properties: [STAMP, { name: 'reliza:export:bomRefsDeduplicated', value: '1' }]
        },
        components: [
            { type: 'library', name: 'top', 'bom-ref': 'pkg:npm/top@1.0.0', properties: [SUPPORT, STAMP] },
            {
                type: 'library', name: 'mid', 'bom-ref': 'pkg:npm/mid@1.0.0',
                components: [{
                    type: 'library', name: 'deep-parent', 'bom-ref': 'pkg:npm/deep-parent@1.0.0',
                    components: [{ type: 'library', name: 'deep', 'bom-ref': 'pkg:npm/deep@1.0.0', properties: [STAMP, KEEP] }]
                }]
            },
            {
                type: 'library', name: 'varied', 'bom-ref': 'pkg:npm/varied@1.0.0',
                pedigree: { variants: [{ type: 'library', name: 'variant', 'bom-ref': 'pkg:npm/variant@1.0.0', properties: [STAMP] }] }
            },
            { type: 'library', name: 'untouched', 'bom-ref': 'pkg:npm/untouched@1.0.0', properties: [KEEP] }
        ],
        services: [{ name: 'svc', 'bom-ref': 'svc-1', properties: [STAMP] }]
    };
}

function namesEverywhere(node: any, out: string[] = []): string[] {
    if (Array.isArray(node)) { node.forEach((n) => namesEverywhere(n, out)); return out; }
    if (node && typeof node === 'object') {
        if (Array.isArray(node.properties)) node.properties.forEach((p: any) => out.push(p?.name));
        Object.values(node).forEach((v) => namesEverywhere(v, out));
    }
    return out;
}

describe('stripReleaseNodeStamps', () => {
    it('returns a document without the property as it is', () => {
        const bom = stampedEverywhere();
        const clean = JSON.parse(JSON.stringify(bom).split(RELEASE_NODE_PROPERTY).join('acme:other'));

        const result = stripReleaseNodeStamps(clean);

        expect(result.bom).toBe(clean);
        expect(result.count).toBe(0);
    });

    it('removes the stamp from the root, nested and top-level nodes, services, tools, variants and metadata', () => {
        const result = stripReleaseNodeStamps(stampedEverywhere());

        expect(result.count).toBe(8);
        expect(namesEverywhere(result.bom)).not.toContain(RELEASE_NODE_PROPERTY);
        expect(result.bom.metadata.component.properties).toStrictEqual([KEEP]);
        expect(result.bom.metadata.component.components[0].properties).toStrictEqual([FOLDED]);
        expect(result.bom.metadata.tools.components[0].properties).toStrictEqual([]);
        expect(result.bom.metadata.properties).toStrictEqual([{ name: 'reliza:export:bomRefsDeduplicated', value: '1' }]);
        expect(result.bom.components[0].properties).toStrictEqual([SUPPORT]);
        expect(result.bom.components[1].components[0].components[0].properties).toStrictEqual([KEEP]);
        expect(result.bom.components[2].pedigree.variants[0].properties).toStrictEqual([]);
        expect(result.bom.services[0].properties).toStrictEqual([]);
    });

    it('removes an entry by its name, whatever its value', () => {
        const bom = stampedEverywhere();
        bom.components[0].properties = [{ name: RELEASE_NODE_PROPERTY, value: 'false' }, SUPPORT, { name: RELEASE_NODE_PROPERTY, value: 'TRUE' }];

        const result = stripReleaseNodeStamps(bom);

        expect(result.bom.components[0].properties).toStrictEqual([SUPPORT]);
        expect(result.count).toBe(9);
    });

    it('keeps reliza:export:foldedRoot and reliza:support entries', () => {
        const names = namesEverywhere(stripReleaseNodeStamps(stampedEverywhere()).bom);

        expect(names).toContain(FOLDED.name);
        expect(names).toContain(SUPPORT.name);
    });

    it('mutates nothing it was handed and shares every subtree that lost nothing', () => {
        const bom = stampedEverywhere();
        const before = structuredClone(bom);

        const result = stripReleaseNodeStamps(bom);

        expect(bom).toStrictEqual(before);
        expect(result.bom).not.toBe(bom);
        expect(result.bom.components[3]).toBe(bom.components[3]);
        expect(result.bom.metadata.component).not.toBe(bom.metadata.component);
    });
});

describe('isRebomMergeOutput', () => {
    it('is true only for meta.mod exactly rebom', () => {
        expect(REBOM_MERGE_MOD).toBe('rebom');
        expect(isRebomMergeOutput({ mod: 'rebom' })).toBe(true);
        expect(isRebomMergeOutput({ mod: 'raw' })).toBe(false);
        expect(isRebomMergeOutput({ mod: 'user' })).toBe(false);
        expect(isRebomMergeOutput({ mod: 'REBOM' })).toBe(false);
        expect(isRebomMergeOutput({})).toBe(false);
        expect(isRebomMergeOutput(null)).toBe(false);
        expect(isRebomMergeOutput(undefined)).toBe(false);
    });
});
