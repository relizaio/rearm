import { describe, it, expect } from 'vitest';
import { fillReleaseNode, dedupeBomRefs } from '../../src/services/bom/bomProcessingService';

/**
 * SCORE-30 (T-4, T-7, ADR-1, ADR-6, ADR-7): the merged root, the node that describes the release,
 * gets the organization as its supplier and the digests of the release's deliverable from what
 * ReARM sent, each only where it has none of its own.
 */

const ROOT = 'pkg:generic/Acme/api@1.0.0?tldonly=true';
const SHA256 = '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08';
const SHA512 = 'ee26b0dd4af7e749aa1a8ee3c10ae9923f618980772e473f8819a5d4940e0db27ac185f8a0e1d5f84f88bc887fd67b143732c304cc5fa9ad8e6f57f50028a8ff';
const OTHER = '60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752';

/** The root merge-boms builds: type, name, group, version, purl and bom-ref, nothing else. */
function merged(root: any = {}): any {
    return {
        bomFormat: 'CycloneDX',
        specVersion: '1.6',
        metadata: {
            timestamp: '2026-10-13T00:00:00Z',
            component: { type: 'application', name: 'api', group: 'Acme', version: '1.0.0', purl: ROOT, 'bom-ref': ROOT, ...root }
        },
        components: [{ type: 'library', name: 'a', version: '1.0.0', purl: 'pkg:npm/a@1.0.0', 'bom-ref': 'pkg:npm/a@1.0.0' }],
        dependencies: [{ ref: ROOT, dependsOn: ['pkg:npm/a@1.0.0'] }]
    };
}

const FACTS = {
    supplierName: 'Acme',
    hashes: [{ alg: 'SHA-256', content: SHA256 }, { alg: 'SHA-512', content: SHA512 }]
};

describe('fillReleaseNode', () => {
    it('writes the supplier and every hash, in order, on a root merge-boms built (T-4)', () => {
        const input = merged();
        const { bom, filled } = fillReleaseNode(input, FACTS);

        expect(bom.metadata.component).toStrictEqual({
            type: 'application', name: 'api', group: 'Acme', version: '1.0.0', purl: ROOT, 'bom-ref': ROOT,
            supplier: { name: 'Acme' },
            hashes: [{ alg: 'SHA-256', content: SHA256 }, { alg: 'SHA-512', content: SHA512 }]
        });
        expect(filled).toStrictEqual({ supplier: true, hashes: true, licenses: false });
        expect(bom.components).toBe(input.components);
        expect(bom.dependencies).toBe(input.dependencies);
        expect(bom.metadata.timestamp).toBe('2026-10-13T00:00:00Z');
    });

    it('writes the supplier alone, and no hashes key, when the facts carry no hashes (T-4)', () => {
        for (const hashes of [undefined, null, []]) {
            const { bom, filled } = fillReleaseNode(merged(), { supplierName: 'Acme', hashes });

            expect(bom.metadata.component.supplier).toStrictEqual({ name: 'Acme' });
            expect(bom.metadata.component).not.toHaveProperty('hashes');
            expect(filled).toStrictEqual({ supplier: true, hashes: false, licenses: false });
        }
    });

    it('writes the hashes alone when the facts name no supplier (T-4)', () => {
        for (const supplierName of [null, undefined, '', '  ']) {
            const { bom, filled } = fillReleaseNode(merged(), { supplierName, hashes: FACTS.hashes });

            expect(bom.metadata.component).not.toHaveProperty('supplier');
            expect(bom.metadata.component.hashes).toStrictEqual([{ alg: 'SHA-256', content: SHA256 }, { alg: 'SHA-512', content: SHA512 }]);
            expect(filled).toStrictEqual({ supplier: false, hashes: true, licenses: false });
        }
    });

    it('keeps a supplier the root already names and says so (T-4, ADR-6)', () => {
        const { bom, filled } = fillReleaseNode(merged({ supplier: { name: 'Reliza Hub' } }), FACTS);

        expect(bom.metadata.component.supplier).toStrictEqual({ name: 'Reliza Hub' });
        expect(bom.metadata.component.hashes).toStrictEqual([{ alg: 'SHA-256', content: SHA256 }, { alg: 'SHA-512', content: SHA512 }]);
        expect(filled).toStrictEqual({ supplier: false, hashes: true, licenses: false });
    });

    it('keeps hashes the root already carries and says so (T-4, ADR-6)', () => {
        const own = [{ alg: 'SHA-256', content: OTHER }, { alg: 'SHA-1', content: 'a94a8fe5ccb19ba61c4c0873d391e987982fbbd3' }];
        const { bom, filled } = fillReleaseNode(merged({ hashes: own }), FACTS);

        expect(bom.metadata.component.hashes).toStrictEqual(own);
        expect(bom.metadata.component.supplier).toStrictEqual({ name: 'Acme' });
        expect(filled).toStrictEqual({ supplier: true, hashes: false, licenses: false });
    });

    it('returns the input itself when the root has both already (T-4)', () => {
        const input = merged({ supplier: { name: 'Reliza Hub' }, hashes: [{ alg: 'SHA-256', content: OTHER }] });

        const { bom, filled } = fillReleaseNode(input, FACTS);

        expect(bom).toBe(input);
        expect(filled).toStrictEqual({ supplier: false, hashes: false, licenses: false });
    });

    it('counts an empty supplier, a blank name and empty hashes as absent (T-4)', () => {
        expect(fillReleaseNode(merged({ supplier: {} }), FACTS).bom.metadata.component.supplier).toStrictEqual({ name: 'Acme' });
        expect(fillReleaseNode(merged({ supplier: { name: '' } }), FACTS).bom.metadata.component.supplier).toStrictEqual({ name: 'Acme' });
        expect(fillReleaseNode(merged({ hashes: [] }), FACTS).bom.metadata.component.hashes)
            .toStrictEqual([{ alg: 'SHA-256', content: SHA256 }, { alg: 'SHA-512', content: SHA512 }]);
    });

    it('drops entries with a blank alg or content, keeping the rest in order (T-4)', () => {
        const { bom } = fillReleaseNode(merged(), {
            supplierName: 'Acme',
            hashes: [
                { alg: '', content: SHA256 },
                { alg: 'SHA-256', content: SHA256 },
                { alg: 'SHA-1', content: ' ' },
                { alg: 'SHA-512', content: SHA512 }
            ]
        });

        expect(bom.metadata.component.hashes).toStrictEqual([{ alg: 'SHA-256', content: SHA256 }, { alg: 'SHA-512', content: SHA512 }]);
    });

    it('drops entries CycloneDX refuses (an unknown algorithm, a value that is not a digest) (T-4)', () => {
        const { bom, filled } = fillReleaseNode(merged(), {
            supplierName: 'Acme',
            hashes: [
                { alg: 'sha256', content: SHA256 },
                { alg: 'SHA-256', content: 'sha256:' + SHA256 },
                { alg: 'SHA-256', content: 'not-a-digest' },
                { alg: 'SHA-512', content: SHA512 },
                { alg: 'SHA-256', content: SHA256 }
            ]
        });

        expect(bom.metadata.component.hashes).toStrictEqual([{ alg: 'SHA-512', content: SHA512 }, { alg: 'SHA-256', content: SHA256 }]);
        expect(filled.hashes).toBe(true);
    });

    it('returns the input unchanged without facts (T-4)', () => {
        const input = merged();
        for (const facts of [null, undefined, {}]) {
            const { bom, filled } = fillReleaseNode(input, facts as any);
            expect(bom).toBe(input);
            expect(filled).toStrictEqual({ supplier: false, hashes: false, licenses: false });
        }
        expect(input.metadata.component).not.toHaveProperty('supplier');
    });

    it('returns a document without metadata.component unchanged (T-4)', () => {
        const input: any = { bomFormat: 'CycloneDX', specVersion: '1.6', metadata: {}, components: [] };
        const { bom, filled } = fillReleaseNode(input, FACTS);
        expect(bom).toBe(input);
        expect(filled).toStrictEqual({ supplier: false, hashes: false, licenses: false });
        expect(fillReleaseNode(null, FACTS).bom).toBeNull();
    });

    it('does not change the input and copies the hashes it writes (T-4)', () => {
        const input = merged();
        const before = JSON.parse(JSON.stringify(input));
        const facts = { supplierName: 'Acme', hashes: [{ alg: 'SHA-256', content: SHA256 }, { alg: 'SHA-512', content: SHA512 }] };

        const { bom } = fillReleaseNode(input, facts);
        facts.hashes[0].content = OTHER;

        expect(input).toStrictEqual(before);
        expect(bom.metadata.component.hashes[0]).toStrictEqual({ alg: 'SHA-256', content: SHA256 });
    });

    it('writes no licenses without a licence fact, and no property (T-4, ADR-5; SCORE-40 T-2)', () => {
        const { bom } = fillReleaseNode(merged(), FACTS);

        expect(bom.metadata.component).not.toHaveProperty('licenses');
        expect(bom.metadata.component).not.toHaveProperty('properties');
        expect(bom.metadata).not.toHaveProperty('properties');
    });
});

const LICENCE_URL = 'https://acme.example/licence';

/**
 * SCORE-40 (T-2, ADR-2, ADR-3): the root carries the licence ReARM declares for the release where
 * it carries none, in the form declaredLicenses gives; a licence it carries, NOASSERTION included,
 * stands.
 */
describe('fillReleaseNode: licenses', () => {
    it('writes a single SPDX id in the id form beside the supplier and hashes (T-2)', () => {
        const { bom, filled } = fillReleaseNode(merged(), { ...FACTS, licenseExpression: 'AGPL-3.0-only' });

        expect(bom.metadata.component).toStrictEqual({
            type: 'application', name: 'api', group: 'Acme', version: '1.0.0', purl: ROOT, 'bom-ref': ROOT,
            supplier: { name: 'Acme' },
            hashes: [{ alg: 'SHA-256', content: SHA256 }, { alg: 'SHA-512', content: SHA512 }],
            licenses: [{ license: { id: 'AGPL-3.0-only' } }]
        });
        expect(filled).toStrictEqual({ supplier: true, hashes: true, licenses: true });
    });

    it('writes a single LicenseRef with its url in the named form, alone (T-2)', () => {
        const { bom, filled } = fillReleaseNode(merged(), { licenseExpression: 'LicenseRef-Acme-Commercial', licenseUrl: LICENCE_URL });

        expect(bom.metadata.component.licenses)
            .toStrictEqual([{ license: { name: 'LicenseRef-Acme-Commercial', url: 'https://acme.example/licence' } }]);
        expect(bom.metadata.component).not.toHaveProperty('supplier');
        expect(bom.metadata.component).not.toHaveProperty('hashes');
        expect(filled).toStrictEqual({ supplier: false, hashes: false, licenses: true });
    });

    it('writes a compound expression in the expression form, the url dropped (T-2)', () => {
        const { bom, filled } = fillReleaseNode(merged(), { licenseExpression: 'MIT OR Apache-2.0', licenseUrl: LICENCE_URL });

        expect(bom.metadata.component.licenses).toStrictEqual([{ expression: 'MIT OR Apache-2.0' }]);
        expect(filled).toStrictEqual({ supplier: false, hashes: false, licenses: true });
    });

    it('keeps a licence the root carries and says so (T-2, ADR-3)', () => {
        const { bom, filled } = fillReleaseNode(merged({ licenses: [{ expression: 'MIT' }] }), { ...FACTS, licenseExpression: 'AGPL-3.0-only' });

        expect(bom.metadata.component.licenses).toStrictEqual([{ expression: 'MIT' }]);
        expect(bom.metadata.component.supplier).toStrictEqual({ name: 'Acme' });
        expect(filled).toStrictEqual({ supplier: true, hashes: true, licenses: false });
    });

    it('keeps a NOASSERTION or NONE the root carries (T-2, ADR-3)', () => {
        for (const asserted of ['NOASSERTION', 'NONE']) {
            const { bom, filled } = fillReleaseNode(merged({ licenses: [{ expression: asserted }] }), { licenseExpression: 'AGPL-3.0-only' });

            expect(bom.metadata.component.licenses, asserted).toStrictEqual([{ expression: asserted }]);
            expect(filled.licenses, asserted).toBe(false);
        }
    });

    it('fills a root whose licenses is empty or null (T-2)', () => {
        for (const licenses of [[], null]) {
            const { bom, filled } = fillReleaseNode(merged({ licenses }), { licenseExpression: 'AGPL-3.0-only' });

            expect(bom.metadata.component.licenses, String(licenses)).toStrictEqual([{ license: { id: 'AGPL-3.0-only' } }]);
            expect(filled, String(licenses)).toStrictEqual({ supplier: false, hashes: false, licenses: true });
        }
    });

    it('writes nothing from a url alone, or a blank expression (T-2)', () => {
        const input = merged();
        for (const facts of [{ licenseUrl: LICENCE_URL }, { licenseExpression: '  ', licenseUrl: LICENCE_URL }, { licenseExpression: null }]) {
            const { bom, filled } = fillReleaseNode(input, facts);

            expect(bom).toBe(input);
            expect(filled).toStrictEqual({ supplier: false, hashes: false, licenses: false });
        }
        expect(input.metadata.component).not.toHaveProperty('licenses');
    });

    it('returns the input itself when the root has a supplier, hashes and a licence already (T-2)', () => {
        const input = merged({
            supplier: { name: 'Reliza Hub' },
            hashes: [{ alg: 'SHA-256', content: OTHER }],
            licenses: [{ license: { id: 'MIT' } }]
        });

        const { bom, filled } = fillReleaseNode(input, { ...FACTS, licenseExpression: 'LicenseRef-Acme-Commercial', licenseUrl: LICENCE_URL });

        expect(bom).toBe(input);
        expect(filled).toStrictEqual({ supplier: false, hashes: false, licenses: false });
    });

    it('does not change the input, and writes a fresh array (T-2)', () => {
        const input = merged();
        const before = JSON.parse(JSON.stringify(input));
        const facts = { licenseExpression: 'LicenseRef-Acme-Commercial', licenseUrl: LICENCE_URL };

        const first = fillReleaseNode(input, facts).bom;
        const second = fillReleaseNode(input, facts).bom;

        expect(input).toStrictEqual(before);
        expect(second.metadata.component.licenses).toStrictEqual(first.metadata.component.licenses);
        expect(second.metadata.component.licenses).not.toBe(first.metadata.component.licenses);
        expect(facts).toStrictEqual({ licenseExpression: 'LicenseRef-Acme-Commercial', licenseUrl: LICENCE_URL });
    });
});

/**
 * SCORE-30 (T-7, ADR-6): end to end on the seam. A merged document holding an uploaded copy of
 * the root, with its own supplier and hashes: the de-dup collapses the copy into the root, and the
 * fill that runs after it finds them there and leaves them.
 */
describe('dedupeBomRefs then fillReleaseNode', () => {
    it('keeps the uploaded copy supplier and hashes on the root, not the facts (T-7)', () => {
        const uploaded = {
            type: 'application', name: 'api', group: 'Acme', version: '1.0.0', purl: ROOT, 'bom-ref': ROOT,
            supplier: { name: 'Reliza Hub' },
            hashes: [{ alg: 'SHA-256', content: OTHER }, { alg: 'SHA-1', content: 'a94a8fe5ccb19ba61c4c0873d391e987982fbbd3' }]
        };
        const doc = merged();
        doc.components = [uploaded, ...doc.components];

        const deduped = dedupeBomRefs(doc).bom;
        const { bom, filled } = fillReleaseNode(deduped, FACTS);

        expect(bom.components.map((c: any) => c['bom-ref'])).toStrictEqual(['pkg:npm/a@1.0.0']);
        expect(bom.metadata.component.supplier).toStrictEqual({ name: 'Reliza Hub' });
        expect(bom.metadata.component.hashes)
            .toStrictEqual([{ alg: 'SHA-256', content: OTHER }, { alg: 'SHA-1', content: 'a94a8fe5ccb19ba61c4c0873d391e987982fbbd3' }]);
        expect(filled).toStrictEqual({ supplier: false, hashes: false, licenses: false });
    });

    it('fills from the facts when the collapsed copy carried neither (T-7)', () => {
        const doc = merged();
        doc.components = [{ type: 'application', name: 'api', group: 'Acme', version: '1.0.0', purl: ROOT, 'bom-ref': ROOT }, ...doc.components];

        const { bom } = fillReleaseNode(dedupeBomRefs(doc).bom, FACTS);

        expect(bom.metadata.component.supplier).toStrictEqual({ name: 'Acme' });
        expect(bom.metadata.component.hashes).toStrictEqual([{ alg: 'SHA-256', content: SHA256 }, { alg: 'SHA-512', content: SHA512 }]);
    });

    it('keeps the licence of an uploaded copy of the root, and fills the supplier and hashes it lacks (SCORE-40 T-2)', () => {
        const doc = merged();
        doc.components = [{
            type: 'application', name: 'api', group: 'Acme', version: '1.0.0', purl: ROOT, 'bom-ref': ROOT,
            licenses: [{ license: { id: 'MIT' } }]
        }, ...doc.components];

        const { bom, filled } = fillReleaseNode(dedupeBomRefs(doc).bom, { ...FACTS, licenseExpression: 'AGPL-3.0-only' });

        expect(bom.components.map((c: any) => c['bom-ref'])).toStrictEqual(['pkg:npm/a@1.0.0']);
        expect(bom.metadata.component.licenses).toStrictEqual([{ license: { id: 'MIT' } }]);
        expect(bom.metadata.component.supplier).toStrictEqual({ name: 'Acme' });
        expect(bom.metadata.component.hashes).toStrictEqual([{ alg: 'SHA-256', content: SHA256 }, { alg: 'SHA-512', content: SHA512 }]);
        expect(filled).toStrictEqual({ supplier: true, hashes: true, licenses: false });
    });

    it('fills the licence from the facts when the collapsed copy carried none (SCORE-40 T-2)', () => {
        const doc = merged();
        doc.components = [{ type: 'application', name: 'api', group: 'Acme', version: '1.0.0', purl: ROOT, 'bom-ref': ROOT }, ...doc.components];

        const { bom, filled } = fillReleaseNode(dedupeBomRefs(doc).bom, { licenseExpression: 'LicenseRef-Acme-Commercial', licenseUrl: LICENCE_URL });

        expect(bom.metadata.component.licenses)
            .toStrictEqual([{ license: { name: 'LicenseRef-Acme-Commercial', url: 'https://acme.example/licence' } }]);
        expect(filled).toStrictEqual({ supplier: false, hashes: false, licenses: true });
    });
});
