import { describe, it, expect } from 'vitest';
import { augmentBomWithComponentContext, augmentBomForStorage } from '../../src/services/bom/bomProcessingService';

/**
 * SCORE-30 (T-3, ADR-4, ADR-12): the upload's root rewrite gives the root the supplier ReARM named
 * for the upload and, by what it is attached to, the deliverable digest or the source commit as a
 * pedigree commit, beside the single-encoded purl SCORE-29 writes, where the generator wrote none.
 */

const COMMIT = 'a94a8fe5ccb19ba61c4c0873d391e987982fbbd3';
const DIGEST = '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08';
const GROUP = 'SBOM Score Walkthrough';
const PUBLISHER = 'Example Devices Inc.';

function generated(root: any = {}): any {
    return {
        bomFormat: 'CycloneDX',
        specVersion: '1.6',
        serialNumber: 'urn:uuid:5d0b7f6e-0a5c-4b8e-9d1e-000000000301',
        version: 1,
        metadata: { component: { type: 'application', name: 'app', 'bom-ref': 'app-root', ...root } },
        components: [{ type: 'library', name: 'a', version: '1.0.0', 'bom-ref': 'pkg:npm/a@1.0.0' }],
        dependencies: [{ ref: 'app-root', dependsOn: ['pkg:npm/a@1.0.0'] }]
    };
}

function options(extra: any): any {
    return { group: GROUP, name: 'sbom score api', version: '1.0.0', ...extra };
}

describe('augmentBomWithComponentContext fills the root from the attachment', () => {
    it('writes the supplier and the commit as a pedigree commit on a source code entry root, no hashes (T-3)', () => {
        const bom = augmentBomWithComponentContext(generated(), options({ supplierName: GROUP, belongsTo: 'SCE', hash: COMMIT }));

        const root = bom.metadata.component;
        expect(root.purl).toBe(`pkg:generic/SBOM%20Score%20Walkthrough/sbom%20score%20api@1.0.0?belongsto=SCE&hash=${COMMIT}`);
        expect(root['bom-ref']).toBe(root.purl);
        expect(root.supplier).toStrictEqual({ name: GROUP });
        expect(root.pedigree).toStrictEqual({ commits: [{ uid: COMMIT }] });
        expect(root).not.toHaveProperty('hashes');
        expect(bom.dependencies).toStrictEqual([{ ref: root.purl, dependsOn: ['pkg:npm/a@1.0.0'] }]);
        // the document-level supplier and authors stay as before
        expect(bom.metadata.supplier).toStrictEqual({ name: GROUP });
        expect(bom.metadata.authors).toStrictEqual([{ name: GROUP }]);
    });

    it('writes the supplier and the SHA-256 of a deliverable digest (T-3)', () => {
        const bom = augmentBomWithComponentContext(generated(), options({ supplierName: PUBLISHER, belongsTo: 'DELIVERABLE', hash: 'sha256:' + DIGEST }));

        expect(bom.metadata.component.supplier).toStrictEqual({ name: PUBLISHER });
        expect(bom.metadata.component.hashes).toStrictEqual([{ alg: 'SHA-256', content: DIGEST }]);
        expect(bom.metadata.component).not.toHaveProperty('pedigree');
    });

    it('writes the supplier alone on a release root (T-3)', () => {
        const bom = augmentBomWithComponentContext(generated(), options({ supplierName: GROUP, belongsTo: 'RELEASE' }));

        expect(bom.metadata.component.supplier).toStrictEqual({ name: GROUP });
        expect(bom.metadata.component).not.toHaveProperty('hashes');
        expect(bom.metadata.component).not.toHaveProperty('pedigree');
    });

    it('names no root supplier without supplierName, while the document supplier and authors keep the group (T-3, ADR-12)', () => {
        const bom = augmentBomWithComponentContext(generated(), options({ belongsTo: 'DELIVERABLE', hash: 'sha256:' + DIGEST }));

        expect(bom.metadata.component).not.toHaveProperty('supplier');
        expect(bom.metadata.component.hashes).toStrictEqual([{ alg: 'SHA-256', content: DIGEST }]);
        expect(bom.metadata.supplier).toStrictEqual({ name: GROUP });
        expect(bom.metadata.authors).toStrictEqual([{ name: GROUP }]);
    });

    it('keeps the supplier and hashes a generator wrote on its root (T-3, ADR-6)', () => {
        const bom = augmentBomWithComponentContext(
            generated({ supplier: { name: 'Reliza Hub' }, hashes: [{ alg: 'SHA-512', content: 'ab'.repeat(64) }] }),
            options({ supplierName: GROUP, belongsTo: 'DELIVERABLE', hash: 'sha256:' + DIGEST }));

        expect(bom.metadata.component.supplier).toStrictEqual({ name: 'Reliza Hub' });
        expect(bom.metadata.component.hashes).toStrictEqual([{ alg: 'SHA-512', content: 'ab'.repeat(64) }]);
    });

    it('the stored document carries the fields beside the ReARM tool entry (T-3)', () => {
        const bom = augmentBomForStorage(generated(), options({ supplierName: GROUP, belongsTo: 'SCE', hash: COMMIT }));

        expect(bom.metadata.component.supplier).toStrictEqual({ name: GROUP });
        expect(bom.metadata.component.pedigree).toStrictEqual({ commits: [{ uid: COMMIT }] });
        expect(bom.metadata.component).not.toHaveProperty('hashes');
    });
});
