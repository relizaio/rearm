import { describe, it, expect } from 'vitest';
import { PackageURL } from 'packageurl-js';
import * as BomProcessingService from '../../src/services/bom/bomProcessingService';

/**
 * SCORE-29: the purl ReARM writes for a release's own node encodes every segment once
 * (establishPurl hands raw group and name to PackageURL, whose toString() is the one encoder),
 * and a root an upload stored before the fix (a space read %2520) is repaired when read.
 */

const SPECIAL_VALUES = ['SBOM Score Walkthrough', '100%', 'a/b', 'me@host', 'what?', 'c#', 'caf\u00e9', '\u4e2d\u6587'];

function options(overrides: any = {}): any {
    return { group: 'Reliza', name: 'rearm', version: '1.0.0', ...overrides };
}

function assertSingleEncoded(purl: string, group: string, name: string) {
    const parsed = PackageURL.fromString(purl);
    expect(parsed.namespace).toBe(group);
    expect(parsed.name).toBe(name);
    const percents = [group, name].filter(v => v === '100%').length;
    expect(purl.split('%25').length - 1).toBe(percents);
}

describe('establishPurl encodes group and name exactly once', () => {
    describe.each(SPECIAL_VALUES)('value %s', (value) => {
        it('as the group', () => {
            const purl = BomProcessingService.establishPurl(undefined, options({ group: value }));
            assertSingleEncoded(purl, value, 'rearm');
        });

        it('as the name', () => {
            const purl = BomProcessingService.establishPurl(undefined, options({ name: value }));
            assertSingleEncoded(purl, 'Reliza', value);
        });
    });

    it('writes a space as %20', () => {
        const purl = BomProcessingService.establishPurl(undefined,
            options({ group: 'SBOM Score Walkthrough', name: 'ReARM Pro Backend', version: '26.10.38', tldOnly: true }));
        expect(purl).toBe('pkg:generic/SBOM%20Score%20Walkthrough/ReARM%20Pro%20Backend@26.10.38?tldonly=true');
    });

    it('keeps a slash in the group as a namespace separator and encodes it in the name', () => {
        const purl = BomProcessingService.establishPurl(undefined, options({ group: 'a/b', name: 'a/b' }));
        expect(purl).toBe('pkg:generic/a/b/a%2Fb@1.0.0');
    });

    it('encodes a percent sign once', () => {
        const purl = BomProcessingService.establishPurl(undefined, options({ group: '100%', name: '100%' }));
        expect(purl).toBe('pkg:generic/100%25/100%25@1.0.0');
    });

    it('encodes the qualifiers once next to a single-encoded name', () => {
        const purl = BomProcessingService.establishPurl(undefined, options({
            name: 'sbom score api',
            hash: 'sha256:0123abcd',
            belongsTo: 'RELEASE',
            tldOnly: true,
            structure: 'HIERARCHICAL'
        }));
        expect(purl).toBe('pkg:generic/Reliza/sbom%20score%20api@1.0.0?belongsto=RELEASE&hash=sha256%3A0123abcd&structure=hierarchical&tldonly=true');
        const parsed = PackageURL.fromString(purl);
        expect(parsed.name).toBe('sbom score api');
        expect({ ...parsed.qualifiers }).toStrictEqual({
            belongsto: 'RELEASE',
            hash: 'sha256:0123abcd',
            structure: 'hierarchical',
            tldonly: 'true'
        });
        expect(purl).not.toContain('%25');
    });

    it('returns a release PURL identifier verbatim', () => {
        const given = 'pkg:npm/%40scope/my%20pkg@1.0.0';
        expect(BomProcessingService.establishPurl(undefined, options({ purl: given }))).toBe(given);
    });
});

describe('augmentBomWithComponentContext writes a single-encoded root', () => {
    it('sets purl and bom-ref to the same single-encoded string and moves the root dependency entry', () => {
        const bom = {
            bomFormat: 'CycloneDX',
            specVersion: '1.6',
            metadata: { component: { type: 'application', name: 'whatever', 'bom-ref': 'root-ref' } },
            components: [
                { type: 'library', name: 'a', 'bom-ref': 'pkg:npm/a@1', purl: 'pkg:npm/a@1' },
                { type: 'library', name: 'b', 'bom-ref': 'pkg:npm/b@1', purl: 'pkg:npm/b@1' }
            ],
            dependencies: [
                { ref: 'root-ref', dependsOn: ['pkg:npm/a@1', 'pkg:npm/b@1'] },
                { ref: 'pkg:npm/a@1', dependsOn: ['pkg:npm/b@1'] },
                { ref: 'pkg:npm/b@1', dependsOn: [] }
            ]
        };
        const out = BomProcessingService.augmentBomWithComponentContext(bom,
            options({ group: 'SBOM Score Walkthrough', name: 'sbom score api', belongsTo: 'RELEASE' }) as any);
        const expected = 'pkg:generic/SBOM%20Score%20Walkthrough/sbom%20score%20api@1.0.0?belongsto=RELEASE';
        expect(out.metadata.component.purl).toBe(expected);
        expect(out.metadata.component['bom-ref']).toBe(expected);
        expect(out.dependencies.map((d: any) => d.ref)).toStrictEqual([expected, 'pkg:npm/a@1', 'pkg:npm/b@1']);
        expect(out.metadata.component.name).toBe('sbom score api');
        expect(out.metadata.component.group).toBe('SBOM Score Walkthrough');
    });

    it('keeps the namespace of a BOM that brings a namespaced purl of its own', () => {
        const bom = {
            bomFormat: 'CycloneDX',
            specVersion: '1.6',
            metadata: { component: { type: 'application', name: 'my app', purl: 'pkg:npm/%40acme/my%20app@0.1.0', 'bom-ref': 'pkg:npm/%40acme/my%20app@0.1.0' } },
            components: [],
            dependencies: [
                { ref: 'pkg:npm/%40acme/my%20app@0.1.0', dependsOn: [] },
                { ref: 'other', dependsOn: [] }
            ]
        };
        const out = BomProcessingService.augmentBomWithComponentContext(bom, options({ group: 'SBOM Score Walkthrough', name: 'sbom score api' }) as any);
        expect(out.metadata.component.purl).toBe('pkg:npm/%40acme/my%20app@1.0.0');
        expect(out.dependencies.map((d: any) => d.ref)).toStrictEqual(['pkg:npm/%40acme/my%20app@1.0.0', 'other']);
    });
});

describe('repairDoubleEncodedRoot', () => {
    const LEGACY = 'pkg:generic/SBOM%2520Score%2520Walkthrough/sbom-score%2520api@1.0.0?belongsto=RELEASE';
    const FIXED = 'pkg:generic/SBOM%20Score%20Walkthrough/sbom-score%20api@1.0.0?belongsto=RELEASE';

    function stored(purl: string, overrides: any = {}): any {
        return {
            bomFormat: 'CycloneDX',
            specVersion: '1.6',
            metadata: {
                component: { type: 'application', group: 'SBOM Score Walkthrough', name: 'sbom-score api', version: '1.0.0', purl, 'bom-ref': purl, ...overrides }
            },
            components: [
                { type: 'library', name: 'a', 'bom-ref': 'pkg:npm/a@1', purl: 'pkg:npm/a@1' },
                { type: 'library', name: 'b', 'bom-ref': 'pkg:npm/b@1', purl: 'pkg:npm/b@1' }
            ],
            dependencies: [
                { ref: purl, dependsOn: ['pkg:npm/a@1', 'pkg:npm/b@1'] },
                { ref: 'pkg:npm/a@1', dependsOn: [purl, 'pkg:npm/b@1'] },
                { ref: 'pkg:npm/b@1' }
            ]
        };
    }

    it('rewrites purl, bom-ref, the dependency ref and every dependsOn entry to the single-encoded form', () => {
        const out = BomProcessingService.repairDoubleEncodedRoot(stored(LEGACY));
        expect(out.metadata.component.purl).toBe(FIXED);
        expect(out.metadata.component['bom-ref']).toBe(FIXED);
        expect(out.dependencies).toStrictEqual([
            { ref: FIXED, dependsOn: ['pkg:npm/a@1', 'pkg:npm/b@1'] },
            { ref: 'pkg:npm/a@1', dependsOn: [FIXED, 'pkg:npm/b@1'] },
            { ref: 'pkg:npm/b@1' }
        ]);
        expect({ ...PackageURL.fromString(out.metadata.component.purl).qualifiers }).toStrictEqual({ belongsto: 'RELEASE' });
        expect(out.components).toStrictEqual(stored(LEGACY).components);
    });

    it('does not change the document it was given', () => {
        const input = stored(LEGACY);
        BomProcessingService.repairDoubleEncodedRoot(input);
        expect(input).toStrictEqual(stored(LEGACY));
    });

    it('leaves a single-encoded root untouched', () => {
        const input = stored(FIXED);
        expect(BomProcessingService.repairDoubleEncodedRoot(input)).toStrictEqual(stored(FIXED));
    });

    it('repairs a raw name that itself reads like an escape by one level only', () => {
        const legacy = 'pkg:generic/Reliza/a%252520b@1.0.0';
        const out = BomProcessingService.repairDoubleEncodedRoot(stored(legacy, { group: 'Reliza', name: 'a%20b' }));
        expect(out.metadata.component.purl).toBe('pkg:generic/Reliza/a%2520b@1.0.0');
        expect(PackageURL.fromString(out.metadata.component.purl).name).toBe('a%20b');
    });

    it('judges each segment on its own', () => {
        const legacy = 'pkg:generic/Reliza/sbom-score%2520api@1.0.0';
        const out = BomProcessingService.repairDoubleEncodedRoot(stored(legacy, { group: 'Reliza' }));
        expect(out.metadata.component.purl).toBe('pkg:generic/Reliza/sbom-score%20api@1.0.0');
    });

    it('keeps a namespace that came from the producer purl', () => {
        const legacy = 'pkg:npm/%40acme/sbom-score%2520api@1.0.0';
        const out = BomProcessingService.repairDoubleEncodedRoot(stored(legacy));
        expect(out.metadata.component.purl).toBe('pkg:npm/%40acme/sbom-score%20api@1.0.0');
    });

    it('returns the input as it came when it cannot read the root', () => {
        const unparsable = stored('not a purl');
        expect(BomProcessingService.repairDoubleEncodedRoot(unparsable)).toBe(unparsable);
        const noGroupOrName = stored(LEGACY, { group: undefined, name: undefined });
        expect(BomProcessingService.repairDoubleEncodedRoot(noGroupOrName)).toBe(noGroupOrName);
        const noRoot = { bomFormat: 'CycloneDX', components: [], dependencies: [{ ref: LEGACY }, { ref: 'x' }] };
        expect(BomProcessingService.repairDoubleEncodedRoot(noRoot)).toBe(noRoot);
        const malformedEscape = stored('pkg:generic/Reliza/100%25@1.0.0', { group: 'Reliza', name: 'x' });
        expect(BomProcessingService.repairDoubleEncodedRoot(malformedEscape)).toBe(malformedEscape);
        expect(BomProcessingService.repairDoubleEncodedRoot(null)).toBe(null);
    });

    it('is idempotent', () => {
        const once = BomProcessingService.repairDoubleEncodedRoot(stored(LEGACY));
        const twice = BomProcessingService.repairDoubleEncodedRoot(once);
        expect(twice).toStrictEqual(once);
    });
});
