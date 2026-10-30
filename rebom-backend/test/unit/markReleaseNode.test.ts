import { describe, it, expect } from 'vitest';
import { markReleaseNode, RELEASE_NODE_PROPERTY } from '../../src/services/bom/bomProcessingService';
import { RELEASE_NODE_PROPERTY as LEAF_RELEASE_NODE_PROPERTY } from '../../src/services/bom/releaseNodeStamp';

/**
 * SCORE-60 (T-8, ADR-1; SCORE-58 ADR-5): rebom stamps the root of every merge it makes as the node it
 * built for a release, `reliza:export:releaseNode` = `true`, and nothing else.
 */

const ROOT = 'pkg:generic/Acme/api@1.0.0';
const STAMP = { name: RELEASE_NODE_PROPERTY, value: 'true' };
const P = { name: 'reliza:export:foldedRoot', value: 'pkg:npm/api@1.0.0' };

function merged(root: any = {}): any {
    return {
        bomFormat: 'CycloneDX',
        specVersion: '1.6',
        metadata: {
            timestamp: '2026-10-23T00:00:00Z',
            component: { type: 'application', name: 'api', group: 'Acme', version: '1.0.0', purl: ROOT, 'bom-ref': ROOT, ...root },
            properties: [{ name: 'reliza:export:bomRefsDeduplicated', value: '1' }]
        },
        components: [
            { type: 'library', name: 'a', version: '1.0.0', purl: 'pkg:npm/a@1.0.0', 'bom-ref': 'pkg:npm/a@1.0.0' },
            { type: 'application', name: 'part', version: '2.0.0', purl: 'pkg:generic/Acme/part@2.0.0', 'bom-ref': 'pkg:generic/Acme/part@2.0.0', properties: [STAMP] }
        ],
        dependencies: [{ ref: ROOT, dependsOn: ['pkg:npm/a@1.0.0', 'pkg:generic/Acme/part@2.0.0'] }]
    };
}

describe('markReleaseNode', () => {
    it('names the property once, in the leaf module and the re-export alike', () => {
        expect(RELEASE_NODE_PROPERTY).toBe('reliza:export:releaseNode');
        expect(LEAF_RELEASE_NODE_PROPERTY).toBe(RELEASE_NODE_PROPERTY);
    });

    it('gives a root without properties the stamp alone', () => {
        const result = markReleaseNode(merged());

        expect(result.marked).toBe(true);
        expect(result.bom.metadata.component.properties).toStrictEqual([STAMP]);
    });

    it('appends the stamp after the properties the root already has', () => {
        const result = markReleaseNode(merged({ properties: [P] }));

        expect(result.bom.metadata.component.properties).toStrictEqual([P, STAMP]);
    });

    it('replaces an earlier entry of that name, whatever its value, with one stamp, last', () => {
        const result = markReleaseNode(merged({ properties: [{ name: RELEASE_NODE_PROPERTY, value: 'false' }, P] }));

        expect(result.bom.metadata.component.properties).toStrictEqual([P, STAMP]);
    });

    it('returns a document without a root as it is', () => {
        const bom = merged();
        delete bom.metadata.component;

        const result = markReleaseNode(bom);

        expect(result.bom).toBe(bom);
        expect(result.marked).toBe(false);
    });

    it('mutates neither the document nor its root, and leaves the components where they were', () => {
        const bom = merged({ properties: [P] });
        const before = structuredClone(bom);

        const result = markReleaseNode(bom);

        expect(bom).toStrictEqual(before);
        expect(result.bom).not.toBe(bom);
        expect(result.bom.metadata.component).not.toBe(bom.metadata.component);
        expect(result.bom.components).toBe(bom.components);
        expect(result.bom.metadata.properties).toBe(bom.metadata.properties);
    });

    it('leaves a nested node that carries the stamp untouched', () => {
        const bom = merged();

        const result = markReleaseNode(bom);

        expect(result.bom.components[1]).toBe(bom.components[1]);
        expect(result.bom.components[1].properties).toStrictEqual([STAMP]);
    });
});
