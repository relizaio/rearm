import { describe, it, expect } from 'vitest';
import { fillRootFromRecordMeta } from '../../src/services/bom/bomProcessingService';

/**
 * SCORE-30 (T-2, ADR-4, ADR-6): an uploaded root gets its supplier from the organization stored
 * with the row and its hash from the attachment reference, each only where it has none.
 */

const COMMIT = 'a94a8fe5ccb19ba61c4c0873d391e987982fbbd3';
const DIGEST = '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08';
const OTHER_DIGEST = '60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752';

function upload(root: any = {}): any {
    return {
        bomFormat: 'CycloneDX',
        specVersion: '1.6',
        metadata: {
            timestamp: '2026-10-13T00:00:00Z',
            component: { type: 'application', name: 'api', version: '1.0.0', group: 'Acme', purl: 'pkg:generic/Acme/api@1.0.0', 'bom-ref': 'pkg:generic/Acme/api@1.0.0', ...root }
        },
        components: [{ type: 'library', name: 'a', 'bom-ref': 'pkg:npm/a@1.0.0' }]
    };
}

describe('fillRootFromRecordMeta', () => {
    it('gives a source code entry root the organization and the SHA-1 of its commit (T-2)', () => {
        const bom = fillRootFromRecordMeta(upload(), { group: 'Acme', belongsTo: 'SCE', hash: COMMIT });

        expect(bom.metadata.component).toStrictEqual({
            type: 'application', name: 'api', version: '1.0.0', group: 'Acme', purl: 'pkg:generic/Acme/api@1.0.0',
            'bom-ref': 'pkg:generic/Acme/api@1.0.0',
            supplier: { name: 'Acme' },
            hashes: [{ alg: 'SHA-1', content: COMMIT }]
        });
        expect(bom.components).toStrictEqual([{ type: 'library', name: 'a', 'bom-ref': 'pkg:npm/a@1.0.0' }]);
        expect(bom.metadata.timestamp).toBe('2026-10-13T00:00:00Z');
    });

    it('gives a deliverable root the SHA-256 of its sha256-prefixed digest (T-2)', () => {
        const bom = fillRootFromRecordMeta(upload(), { group: 'Acme', belongsTo: 'DELIVERABLE', hash: 'sha256:' + DIGEST });

        expect(bom.metadata.component.supplier).toStrictEqual({ name: 'Acme' });
        expect(bom.metadata.component.hashes).toStrictEqual([{ alg: 'SHA-256', content: DIGEST }]);
    });

    it('gives a release root without a reference the supplier only, and no hashes key (T-2)', () => {
        const bom = fillRootFromRecordMeta(upload(), { group: 'Acme', belongsTo: 'RELEASE' });

        expect(bom.metadata.component.supplier).toStrictEqual({ name: 'Acme' });
        expect(bom.metadata.component).not.toHaveProperty('hashes');
    });

    it('keeps the supplier and the hashes a root already carries (T-2, ADR-6)', () => {
        const own = upload({
            supplier: { name: 'Reliza Hub', url: ['https://example.com'] },
            hashes: [{ alg: 'SHA-256', content: OTHER_DIGEST }, { alg: 'SHA-1', content: COMMIT }]
        });

        const bom = fillRootFromRecordMeta(own, { group: 'Acme', belongsTo: 'DELIVERABLE', hash: 'sha256:' + DIGEST });

        expect(bom).toBe(own);
        expect(bom.metadata.component.supplier).toStrictEqual({ name: 'Reliza Hub', url: ['https://example.com'] });
        expect(bom.metadata.component.hashes).toStrictEqual([{ alg: 'SHA-256', content: OTHER_DIGEST }, { alg: 'SHA-1', content: COMMIT }]);
    });

    it('fills each field on its own: a present supplier stays and the hash is added (T-2, ADR-6)', () => {
        const bom = fillRootFromRecordMeta(upload({ supplier: { name: 'Reliza Hub' } }), { group: 'Acme', hash: COMMIT });

        expect(bom.metadata.component.supplier).toStrictEqual({ name: 'Reliza Hub' });
        expect(bom.metadata.component.hashes).toStrictEqual([{ alg: 'SHA-1', content: COMMIT }]);
    });

    it('counts an empty supplier, a blank supplier name and empty hashes as absent (T-2)', () => {
        expect(fillRootFromRecordMeta(upload({ supplier: {} }), { group: 'Acme' }).metadata.component.supplier)
            .toStrictEqual({ name: 'Acme' });
        expect(fillRootFromRecordMeta(upload({ supplier: { name: ' ' } }), { group: 'Acme' }).metadata.component.supplier)
            .toStrictEqual({ name: 'Acme' });
        expect(fillRootFromRecordMeta(upload({ hashes: [] }), { group: 'Acme', hash: COMMIT }).metadata.component.hashes)
            .toStrictEqual([{ alg: 'SHA-1', content: COMMIT }]);
    });

    it('keeps the other fields of a supplier without a name (T-2)', () => {
        const bom = fillRootFromRecordMeta(upload({ supplier: { url: ['https://example.com'] } }), { group: 'Acme' });

        expect(bom.metadata.component.supplier).toStrictEqual({ url: ['https://example.com'], name: 'Acme' });
    });

    it('adds no hash for a reference that is not a sha (T-2)', () => {
        const bom = fillRootFromRecordMeta(upload(), { group: 'Acme', belongsTo: 'SCE', hash: 'r1234' });

        expect(bom.metadata.component.supplier).toStrictEqual({ name: 'Acme' });
        expect(bom.metadata.component).not.toHaveProperty('hashes');
    });

    it('returns the input itself without metadata.component, without facts, or with nothing to fill (T-2)', () => {
        const rootless: any = { bomFormat: 'CycloneDX', specVersion: '1.6', metadata: {}, components: [] };
        expect(fillRootFromRecordMeta(rootless, { group: 'Acme', hash: COMMIT })).toBe(rootless);
        const noMetadata: any = { bomFormat: 'CycloneDX', specVersion: '1.6' };
        expect(fillRootFromRecordMeta(noMetadata, { group: 'Acme', hash: COMMIT })).toBe(noMetadata);
        const bom = upload();
        expect(fillRootFromRecordMeta(bom, undefined)).toBe(bom);
        expect(fillRootFromRecordMeta(bom, null)).toBe(bom);
        expect(fillRootFromRecordMeta(bom, {})).toBe(bom);
        expect(fillRootFromRecordMeta(bom, { group: '  ', belongsTo: 'RELEASE' })).toBe(bom);
        expect(bom.metadata.component).not.toHaveProperty('supplier');
    });

    it('without a group fills the hash alone (T-2)', () => {
        const bom = fillRootFromRecordMeta(upload(), { belongsTo: 'SCE', hash: COMMIT });

        expect(bom.metadata.component).not.toHaveProperty('supplier');
        expect(bom.metadata.component.hashes).toStrictEqual([{ alg: 'SHA-1', content: COMMIT }]);
    });

    it('never touches licenses (T-2, ADR-5)', () => {
        const bom = fillRootFromRecordMeta(upload({ licenses: [{ license: { id: 'MIT' } }] }), { group: 'Acme', hash: COMMIT });
        expect(bom.metadata.component.licenses).toStrictEqual([{ license: { id: 'MIT' } }]);
        expect(fillRootFromRecordMeta(upload(), { group: 'Acme', hash: COMMIT }).metadata.component).not.toHaveProperty('licenses');
    });

    it('does not change the input (T-2)', () => {
        const input = upload();
        const before = JSON.parse(JSON.stringify(input));

        const bom = fillRootFromRecordMeta(input, { group: 'Acme', hash: COMMIT });

        expect(bom).not.toBe(input);
        expect(input).toStrictEqual(before);
    });

    it('is idempotent: a second fill returns what the first gave (T-2)', () => {
        const meta = { group: 'Acme', belongsTo: 'SCE', hash: COMMIT };
        const once = fillRootFromRecordMeta(upload(), meta);

        expect(fillRootFromRecordMeta(once, meta)).toBe(once);
    });
});
