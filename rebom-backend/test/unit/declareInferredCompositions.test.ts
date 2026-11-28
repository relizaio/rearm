import { describe, it, expect } from 'vitest';
import {
    declareInferredCompositions,
    declareUnknownCompositions,
    DEPENDENCIES_INFERRED_PROPERTY
} from '../../src/services/bom/bomProcessingService';

/**
 * FDAW-160 3.1 item 3 (architecture test 2): the last pass of a merge states, in one compositions
 * entry with aggregate unknown, every node whose dependency list a merge inferred (the nodes that
 * carry reliza:export:dependenciesInferred with a positive integer value).
 */

const ROOT = 'pkg:generic/acme/device@1.0.0';
const FIRMWARE = 'pkg:generic/acme/firmware@2.0.0';
const BOOTLOADER = 'pkg:generic/acme/bootloader@1.0.0';
const PLACEHOLDER = 'urn:rearm:release:0b0f4a52-6c55-4d1f-9a8e-000000000001';

function inferred(value: string): any {
    return { name: DEPENDENCIES_INFERRED_PROPERTY, value };
}

function componentDoc(rootProperties: any[] | undefined): any {
    return {
        bomFormat: 'CycloneDX',
        specVersion: '1.6',
        metadata: { component: { type: 'firmware', name: 'firmware', 'bom-ref': FIRMWARE, ...(rootProperties ? { properties: rootProperties } : {}) } },
        components: [{ type: 'library', name: 'lodash', 'bom-ref': 'pkg:npm/lodash@4.17.21' }],
        dependencies: [{ ref: FIRMWARE, dependsOn: ['pkg:npm/lodash@4.17.21'] }]
    };
}

function productDoc(): any {
    return {
        bomFormat: 'CycloneDX',
        specVersion: '1.6',
        metadata: { component: { type: 'device', name: 'device', 'bom-ref': ROOT } },
        components: [
            {
                type: 'firmware', name: 'firmware', 'bom-ref': FIRMWARE, properties: [inferred('5')],
                components: [{
                    type: 'library', name: 'inner', 'bom-ref': 'pkg:npm/inner@1.0.0',
                    // depth 2
                    components: [{ type: 'firmware', name: 'bootloader', 'bom-ref': BOOTLOADER, properties: [{ name: 'keep', value: 'x' }, inferred('2')] }]
                }]
            },
            { type: 'library', name: 'plain', 'bom-ref': 'pkg:npm/plain@1.0.0' }
        ],
        dependencies: [{ ref: ROOT, dependsOn: [FIRMWARE] }]
    };
}

describe('declareInferredCompositions (FDAW-160)', () => {
    it('a root with the record gives one entry {aggregate: unknown, dependencies: [rootRef]}', () => {
        const before = componentDoc([inferred('1')]);

        const result = declareInferredCompositions(before);

        expect(result.count).toBe(1);
        expect(result.bom.compositions).toStrictEqual([{ aggregate: 'unknown', dependencies: [FIRMWARE] }]);
        expect(result.bom.metadata).toBe(before.metadata);
        expect(result.bom.dependencies).toBe(before.dependencies);
        expect(result.bom.components).toBe(before.components);
        expect(before).not.toHaveProperty('compositions');
    });

    it('a product shape (root without it, two nested nodes with it, one at depth 2) gives one entry with both refs sorted', () => {
        const result = declareInferredCompositions(productDoc());

        expect(result.count).toBe(2);
        expect(result.bom.compositions).toStrictEqual([{ aggregate: 'unknown', dependencies: [BOOTLOADER, FIRMWARE] }]);
    });

    it('a node listed twice is named once', () => {
        const bom = productDoc();
        bom.components.push({ type: 'firmware', name: 'firmware', 'bom-ref': FIRMWARE, properties: [inferred('5')] });

        expect(declareInferredCompositions(bom).bom.compositions).toStrictEqual([{ aggregate: 'unknown', dependencies: [BOOTLOADER, FIRMWARE] }]);
    });

    it('an existing placeholder entry stays first, and the new entry is appended after it', () => {
        const bom = productDoc();
        bom.components.push({ type: 'library', name: PLACEHOLDER, version: '1', 'bom-ref': PLACEHOLDER, properties: [{ name: 'reliza:sbom:missing', value: 'NO_SBOM_ARTIFACT' }] });
        const withPlaceholder = declareUnknownCompositions(bom, null).bom;

        const result = declareInferredCompositions(withPlaceholder);

        expect(result.bom.compositions).toStrictEqual([
            { aggregate: 'unknown', dependencies: [PLACEHOLDER] },
            { aggregate: 'unknown', dependencies: [BOOTLOADER, FIRMWARE] }
        ]);
    });

    it('values "0", "-1", "x", "1e3" and "", a non-string value, and a node without a bom-ref add nothing', () => {
        for (const value of ['0', '-1', 'x', '1e3', '', ' 3', '1234567890']) {
            const bom = componentDoc([inferred(value)]);
            expect(declareInferredCompositions(bom).bom, value).toBe(bom);
        }
        const numeric = componentDoc([{ name: DEPENDENCIES_INFERRED_PROPERTY, value: 3 }]);
        expect(declareInferredCompositions(numeric).bom).toBe(numeric);
        const noRef = componentDoc([inferred('3')]);
        delete noRef.metadata.component['bom-ref'];
        expect(declareInferredCompositions(noRef).bom).toBe(noRef);
    });

    it('writes no property and adds no dependency reference', () => {
        const before = productDoc();
        const snapshot = structuredClone(before);

        const result = declareInferredCompositions(before);

        expect(before).toStrictEqual(snapshot);
        const { compositions, ...rest } = result.bom;
        expect(rest).toStrictEqual(snapshot);
        expect(compositions).toHaveLength(1);
    });

    it('no record gives the input itself', () => {
        const bom = componentDoc(undefined);
        expect(declareInferredCompositions(bom)).toStrictEqual({ bom, count: 0 });
        expect(declareInferredCompositions(bom).bom).toBe(bom);
        expect(declareInferredCompositions(null).bom).toBeNull();
    });
});
