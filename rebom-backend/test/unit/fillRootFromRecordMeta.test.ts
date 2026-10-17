import { describe, it, expect } from 'vitest';
import { fillRootFromRecordMeta } from '../../src/services/bom/bomProcessingService';

/**
 * SCORE-30 (T-2, ADR-4, ADR-6, ADR-12): an uploaded root gets the supplier ReARM named for the
 * upload, the digest of the deliverable it is attached to, or the commit of its source code entry
 * as a pedigree commit, each only where it has none; nothing from the row's group.
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
    it('gives a source code entry root the supplier and its commit as a pedigree commit, no hashes (T-2)', () => {
        const bom = fillRootFromRecordMeta(upload(), { supplierName: 'Acme', belongsTo: 'SCE', hash: COMMIT });

        expect(bom.metadata.component).toStrictEqual({
            type: 'application', name: 'api', version: '1.0.0', group: 'Acme', purl: 'pkg:generic/Acme/api@1.0.0',
            'bom-ref': 'pkg:generic/Acme/api@1.0.0',
            supplier: { name: 'Acme' },
            pedigree: { commits: [{ uid: COMMIT }] }
        });
        expect(bom.metadata.component).not.toHaveProperty('hashes');
        expect(bom.components).toStrictEqual([{ type: 'library', name: 'a', 'bom-ref': 'pkg:npm/a@1.0.0' }]);
        expect(bom.metadata.timestamp).toBe('2026-10-13T00:00:00Z');
    });

    it('writes any source code entry reference as the commit uid, as given (T-2)', () => {
        const bom = fillRootFromRecordMeta(upload(), { belongsTo: 'SCE', hash: 'r1234' });

        expect(bom.metadata.component.pedigree).toStrictEqual({ commits: [{ uid: 'r1234' }] });
        expect(bom.metadata.component).not.toHaveProperty('hashes');
    });

    it('leaves a pedigree that lists commits untouched, and keeps other pedigree members when it fills (T-2)', () => {
        const own = upload({ pedigree: { commits: [{ uid: 'abc', url: 'https://example.com/c/abc' }], notes: 'forked' } });
        expect(fillRootFromRecordMeta(own, { belongsTo: 'SCE', hash: COMMIT })).toBe(own);

        const noted = fillRootFromRecordMeta(upload({ pedigree: { notes: 'forked', commits: [] } }), { belongsTo: 'SCE', hash: COMMIT });
        expect(noted.metadata.component.pedigree).toStrictEqual({ notes: 'forked', commits: [{ uid: COMMIT }] });
    });

    it('gives a deliverable root the SHA-256 of its sha256-prefixed digest and no pedigree (T-2)', () => {
        const bom = fillRootFromRecordMeta(upload(), { supplierName: 'Acme', belongsTo: 'DELIVERABLE', hash: 'sha256:' + DIGEST });

        expect(bom.metadata.component.supplier).toStrictEqual({ name: 'Acme' });
        expect(bom.metadata.component.hashes).toStrictEqual([{ alg: 'SHA-256', content: DIGEST }]);
        expect(bom.metadata.component).not.toHaveProperty('pedigree');
    });

    it('reads belongsTo in any case (T-2)', () => {
        const bom = fillRootFromRecordMeta(upload(), { belongsTo: 'deliverable', hash: DIGEST });

        expect(bom.metadata.component.hashes).toStrictEqual([{ alg: 'SHA-256', content: DIGEST }]);
    });

    it('adds nothing for a deliverable reference that is not a digest (T-2)', () => {
        const bom = upload();
        expect(fillRootFromRecordMeta(bom, { belongsTo: 'DELIVERABLE', hash: 'v1.2.3' })).toBe(bom);
    });

    it('fills neither hashes nor pedigree for a release root, whatever its hash says (T-2, run 1 item T-5)', () => {
        const bom = fillRootFromRecordMeta(upload(), { supplierName: 'Acme', belongsTo: 'RELEASE', hash: DIGEST });

        expect(bom.metadata.component.supplier).toStrictEqual({ name: 'Acme' });
        expect(bom.metadata.component).not.toHaveProperty('hashes');
        expect(bom.metadata.component).not.toHaveProperty('pedigree');
    });

    it('fills nothing from a hash when belongsTo is absent or another value (T-2)', () => {
        const bom = upload();
        expect(fillRootFromRecordMeta(bom, { hash: DIGEST })).toBe(bom);
        expect(fillRootFromRecordMeta(bom, { belongsTo: 'AGENT_SESSION', hash: COMMIT })).toBe(bom);
    });

    it('names no supplier from the row group: a legacy row without supplierName (T-2, ADR-12)', () => {
        const legacy: any = { group: 'Acme', belongsTo: 'DELIVERABLE', hash: 'sha256:' + DIGEST };
        const bom = fillRootFromRecordMeta(upload(), legacy);

        expect(bom.metadata.component).not.toHaveProperty('supplier');
        expect(bom.metadata.component.hashes).toStrictEqual([{ alg: 'SHA-256', content: DIGEST }]);
    });

    it('names no supplier for a blank supplierName (T-2)', () => {
        const bom = upload();
        expect(fillRootFromRecordMeta(bom, { supplierName: ' ', belongsTo: 'RELEASE' })).toBe(bom);
    });

    it('keeps the supplier and the hashes a root already carries (T-2, ADR-6)', () => {
        const own = upload({
            supplier: { name: 'Reliza Hub', url: ['https://example.com'] },
            hashes: [{ alg: 'SHA-256', content: OTHER_DIGEST }, { alg: 'SHA-1', content: COMMIT }]
        });

        const bom = fillRootFromRecordMeta(own, { supplierName: 'Acme', belongsTo: 'DELIVERABLE', hash: 'sha256:' + DIGEST });

        expect(bom).toBe(own);
        expect(bom.metadata.component.supplier).toStrictEqual({ name: 'Reliza Hub', url: ['https://example.com'] });
        expect(bom.metadata.component.hashes).toStrictEqual([{ alg: 'SHA-256', content: OTHER_DIGEST }, { alg: 'SHA-1', content: COMMIT }]);
    });

    it('fills each field on its own: a present supplier stays and the hash is added (T-2, ADR-6)', () => {
        const bom = fillRootFromRecordMeta(upload({ supplier: { name: 'Reliza Hub' } }), { supplierName: 'Acme', belongsTo: 'DELIVERABLE', hash: DIGEST });

        expect(bom.metadata.component.supplier).toStrictEqual({ name: 'Reliza Hub' });
        expect(bom.metadata.component.hashes).toStrictEqual([{ alg: 'SHA-256', content: DIGEST }]);
    });

    it('counts an empty supplier, a blank supplier name and empty hashes as absent (T-2)', () => {
        expect(fillRootFromRecordMeta(upload({ supplier: {} }), { supplierName: 'Acme' }).metadata.component.supplier)
            .toStrictEqual({ name: 'Acme' });
        expect(fillRootFromRecordMeta(upload({ supplier: { name: ' ' } }), { supplierName: 'Acme' }).metadata.component.supplier)
            .toStrictEqual({ name: 'Acme' });
        expect(fillRootFromRecordMeta(upload({ hashes: [] }), { belongsTo: 'DELIVERABLE', hash: DIGEST }).metadata.component.hashes)
            .toStrictEqual([{ alg: 'SHA-256', content: DIGEST }]);
    });

    it('keeps the other fields of a supplier without a name (T-2)', () => {
        const bom = fillRootFromRecordMeta(upload({ supplier: { url: ['https://example.com'] } }), { supplierName: 'Acme' });

        expect(bom.metadata.component.supplier).toStrictEqual({ url: ['https://example.com'], name: 'Acme' });
    });

    it('returns the input itself without metadata.component, without facts, or with nothing to fill (T-2)', () => {
        const rootless: any = { bomFormat: 'CycloneDX', specVersion: '1.6', metadata: {}, components: [] };
        expect(fillRootFromRecordMeta(rootless, { supplierName: 'Acme', belongsTo: 'SCE', hash: COMMIT })).toBe(rootless);
        const noMetadata: any = { bomFormat: 'CycloneDX', specVersion: '1.6' };
        expect(fillRootFromRecordMeta(noMetadata, { supplierName: 'Acme', belongsTo: 'SCE', hash: COMMIT })).toBe(noMetadata);
        const bom = upload();
        expect(fillRootFromRecordMeta(bom, undefined)).toBe(bom);
        expect(fillRootFromRecordMeta(bom, null)).toBe(bom);
        expect(fillRootFromRecordMeta(bom, {})).toBe(bom);
        expect(fillRootFromRecordMeta(bom, { belongsTo: 'SCE', hash: '  ' })).toBe(bom);
        expect(bom.metadata.component).not.toHaveProperty('supplier');
    });

    it('never touches licenses (T-2, ADR-5)', () => {
        const meta = { supplierName: 'Acme', belongsTo: 'DELIVERABLE', hash: DIGEST };
        const bom = fillRootFromRecordMeta(upload({ licenses: [{ license: { id: 'MIT' } }] }), meta);
        expect(bom.metadata.component.licenses).toStrictEqual([{ license: { id: 'MIT' } }]);
        expect(fillRootFromRecordMeta(upload(), meta).metadata.component).not.toHaveProperty('licenses');
    });

    it('does not change the input, and a fill returns a copy (T-2)', () => {
        const input = upload({ pedigree: { notes: 'forked' } });
        const before = JSON.parse(JSON.stringify(input));

        const bom = fillRootFromRecordMeta(input, { supplierName: 'Acme', belongsTo: 'SCE', hash: COMMIT });

        expect(bom).not.toBe(input);
        expect(input).toStrictEqual(before);
    });

    it('is idempotent: a second fill returns what the first gave (T-2)', () => {
        const meta = { supplierName: 'Acme', belongsTo: 'SCE', hash: COMMIT };
        const once = fillRootFromRecordMeta(upload(), meta);

        expect(fillRootFromRecordMeta(once, meta)).toBe(once);
    });
});
