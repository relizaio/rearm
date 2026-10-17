import { describe, it, expect, vi, afterEach } from 'vitest';
import { logger } from '../../src/logger';
import {
    declareUnknownCompositions,
    COMPONENTS_WITHOUT_SBOM_PROPERTY
} from '../../src/services/bom/bomProcessingService';

/**
 * SCORE-15 (T-8, T-9, ADR-4, ADR-6): the last pass of a merge states that the dependencies of
 * the placeholder components (the ones marked reliza:sbom:missing) are unknown, in one
 * compositions entry, and counts them in metadata.properties.
 */

const ROOT = 'pkg:generic/Example/prod@1.0.0';
const HTTPD = 'urn:rearm:release:0b0f4a52-6c55-4d1f-9a8e-000000000001';
const FLYWAY = 'urn:rearm:release:0b0f4a52-6c55-4d1f-9a8e-000000000002';
const ZOT = 'urn:rearm:release:0b0f4a52-6c55-4d1f-9a8e-000000000003';
const A = 'pkg:npm/a@1.0.0';
const B = 'pkg:npm/b@1.0.0';

function placeholder(ref: string, reason = 'NO_SBOM_ARTIFACT'): any {
    return { type: 'library', name: ref, version: '1.0.0', 'bom-ref': ref, properties: [{ name: 'reliza:sbom:missing', value: reason }] };
}

function doc(extra: any = {}): any {
    return {
        bomFormat: 'CycloneDX',
        specVersion: '1.6',
        serialNumber: 'urn:uuid:3c1f0a7e-14a1-4c1e-8f00-000000000115',
        version: 1,
        metadata: {
            component: { type: 'application', name: 'prod', 'bom-ref': ROOT },
            properties: [{ name: 'existing', value: 'kept' }, { name: 'cdx:other', value: '1' }]
        },
        // Out of order on purpose: the composition lists the refs sorted.
        components: [
            placeholder(FLYWAY, 'COVERAGE_EXCLUDED'),
            { type: 'application', name: 'app', version: '1.0.0', 'bom-ref': A, components: [placeholder(HTTPD)] },
            { type: 'library', name: 'b', version: '1.0.0', 'bom-ref': B, properties: [{ name: 'cdx:x', value: 'y' }] }
        ],
        dependencies: [{ ref: ROOT, dependsOn: [FLYWAY, A, B] }, { ref: FLYWAY, dependsOn: [] }],
        ...extra
    };
}

function missing(ref: string, purl: string | null = null): any {
    return { releaseUuid: ref.substring('urn:rearm:release:'.length), name: 'x', version: '1', type: 'library', purl, reason: 'NO_SBOM_ARTIFACT' };
}

describe('declareUnknownCompositions', () => {
    afterEach(() => vi.restoreAllMocks());

    it('states one unknown aggregate over every marked component, nested ones too, sorted (T-8)', () => {
        const before = doc();
        const result = declareUnknownCompositions(before, [missing(HTTPD), missing(FLYWAY)]);

        expect(result.count).toBe(2);
        expect(result.bom.compositions).toStrictEqual([{ aggregate: 'unknown', dependencies: [HTTPD, FLYWAY] }]);
        expect(result.bom.metadata.properties).toStrictEqual([
            { name: 'existing', value: 'kept' },
            { name: 'cdx:other', value: '1' },
            { name: 'reliza:export:componentsWithoutSbom', value: '2' }
        ]);
        expect(result.bom.components).toBe(before.components);
        expect(result.bom.dependencies).toBe(before.dependencies);
        expect(result.bom.metadata.component).toBe(before.metadata.component);
        expect(before).not.toHaveProperty('compositions');
        expect(before.metadata.properties).toHaveLength(2);
    });

    it('finds the placeholders by their marker, not by the list it was given (T-8)', () => {
        // A product merge made of a nested product merge: that merge's placeholders arrive as
        // components of an input, and this merge's own list does not name them.
        const result = declareUnknownCompositions(doc(), null);

        expect(result.count).toBe(2);
        expect(result.bom.compositions).toStrictEqual([{ aggregate: 'unknown', dependencies: [HTTPD, FLYWAY] }]);
    });

    it('takes the marker by its exact name, never a near miss a producer SBOM may carry (T-19)', () => {
        // Producer SBOMs keep their own reliza: component properties through upload and merge,
        // so only the exact name marks a placeholder: no prefix, no case folding, no trimming.
        const nearMiss = (ref: string, name: string): any => (
            { type: 'library', name: ref, version: '1.0.0', 'bom-ref': ref, properties: [{ name, value: 'NO_SBOM_ARTIFACT' }] }
        );
        const bom = doc();
        bom.components = [
            nearMiss('pkg:npm/generator@1.0.0', 'reliza:sbom:generator'),
            placeholder(HTTPD),
            nearMiss('pkg:npm/missingness@1.0.0', 'reliza:sbom:missingness'),
            nearMiss('pkg:npm/upper@1.0.0', 'Reliza:SBOM:Missing'),
            nearMiss('pkg:npm/trailing@1.0.0', 'reliza:sbom:missing ')
        ];

        const result = declareUnknownCompositions(bom, null);

        expect(result.count).toBe(1);
        expect(result.bom.compositions).toStrictEqual([{ aggregate: 'unknown', dependencies: [HTTPD] }]);
        expect(result.bom.metadata.properties).toContainEqual({ name: COMPONENTS_WITHOUT_SBOM_PROPERTY, value: '1' });
    });

    it('counts a placeholder the document holds twice once (T-8)', () => {
        const bom = doc();
        bom.components[2].components = [placeholder(FLYWAY)];

        const result = declareUnknownCompositions(bom, [missing(HTTPD)]);

        expect(result.count).toBe(2);
        expect(result.bom.compositions).toStrictEqual([{ aggregate: 'unknown', dependencies: [HTTPD, FLYWAY] }]);
    });

    it('replaces a stale count instead of adding a second one (T-8)', () => {
        const bom = doc();
        bom.metadata.properties = [
            { name: 'reliza:export:componentsWithoutSbom', value: '9' },
            { name: 'existing', value: 'kept' }
        ];

        const result = declareUnknownCompositions(bom, null);

        expect(result.bom.metadata.properties).toStrictEqual([
            { name: 'existing', value: 'kept' },
            { name: 'reliza:export:componentsWithoutSbom', value: '2' }
        ]);
    });

    it('keeps existing compositions ahead of the new entry, never merged into them (T-8)', () => {
        const kept = [
            { aggregate: 'complete', assemblies: [A] },
            { aggregate: 'incomplete', dependencies: [B] }
        ];
        const result = declareUnknownCompositions(doc({ compositions: kept }), null);

        expect(result.bom.compositions).toStrictEqual([
            { aggregate: 'complete', assemblies: [A] },
            { aggregate: 'incomplete', dependencies: [B] },
            { aggregate: 'unknown', dependencies: [HTTPD, FLYWAY] }
        ]);
    });

    it('returns a document without a marked component as is, with no property (T-8)', () => {
        const bom = doc();
        bom.components = [bom.components[2]];
        const before = structuredClone(bom);

        const result = declareUnknownCompositions(bom, []);

        expect(result.bom).toBe(bom);
        expect(result.bom).toStrictEqual(before);
        expect(result.count).toBe(0);
        expect(result.bom).not.toHaveProperty('compositions');
    });

    it('does not take the merged root for a placeholder', () => {
        const bom = doc();
        bom.components = [];
        bom.metadata.component.properties = [{ name: 'reliza:sbom:missing', value: 'NO_SBOM_ARTIFACT' }];

        expect(declareUnknownCompositions(bom, null).bom).toBe(bom);
    });

    it('adds nothing for a listed placeholder the merge absorbed into a component with its purl, and logs it once (T-9)', () => {
        const info = vi.spyOn(logger, 'info').mockImplementation(() => undefined as any);

        const result = declareUnknownCompositions(doc(), [missing(HTTPD), missing(ZOT, 'pkg:npm/b@1.0.0'), missing(FLYWAY)]);

        expect(result.count).toBe(2);
        expect(result.bom.compositions).toStrictEqual([{ aggregate: 'unknown', dependencies: [HTTPD, FLYWAY] }]);
        expect(result.bom.metadata.properties).toContainEqual({ name: COMPONENTS_WITHOUT_SBOM_PROPERTY, value: '2' });
        expect(info.mock.calls).toStrictEqual([[
            'Component release without SBOM urn:rearm:release:0b0f4a52-6c55-4d1f-9a8e-000000000003 is described elsewhere in the merge: another component with its purl was kept'
        ]]);
    });

    it('logs nothing when every listed placeholder is in the document (T-9)', () => {
        const info = vi.spyOn(logger, 'info').mockImplementation(() => undefined as any);

        declareUnknownCompositions(doc(), [missing(HTTPD), missing(FLYWAY)]);

        expect(info).not.toHaveBeenCalled();
    });

    it('names the count property as the export documents it (T-8)', () => {
        expect(COMPONENTS_WITHOUT_SBOM_PROPERTY).toBe('reliza:export:componentsWithoutSbom');
    });
});
