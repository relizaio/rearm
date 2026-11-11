import { createHash } from 'crypto';
import * as fs from 'fs';
import * as path from 'path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../src/bomRepository', () => ({ allBomsBySerialNumber: vi.fn(), bomById: vi.fn() }));
vi.mock('../../src/spdxRepository', () => ({ findSpdxBomByNamespace: vi.fn() }));
vi.mock('../../src/services/bom/bomAddService', () => ({
    addBomDetailed: vi.fn(),
    AddDisposition: { CREATED: 'CREATED', EXISTING: 'EXISTING' },
    LineagePolicyKind: { ANY: 'ANY', NEW_OR_NAMED: 'NEW_OR_NAMED' }
}));

import * as BomRepository from '../../src/bomRepository';
import * as SpdxRepository from '../../src/spdxRepository';
import { logger } from '../../src/logger';
import { addBomDetailed } from '../../src/services/bom/bomAddService';
import { TeaHeldFile } from '../../src/services/tea/teaFetchStore';
import { sbomFormatOf, storeHeldSbom, TEA_SBOM_MAX_BYTES } from '../../src/services/tea/teaSbomStore';
import { BomLineageNotPermittedError, BomValidationError, BomVersionConflictError } from '../../src/types/errors';
import { cleanupDirs, tempDir } from './teaTestKit';

/**
 * storeHeldSbom on its own, with the repositories and the add path replaced (design 3.6.3, 3.6.4):
 * the size and parse checks, the format by content, what is asked of the add path, the answers of
 * a lineage or namespace that is already held, and every way the add path can refuse.
 */

const ORG = '33333333-3333-4333-8333-333333333333';
const mAdd = vi.mocked(addBomDetailed);
const mAll = vi.mocked(BomRepository.allBomsBySerialNumber);
const mById = vi.mocked(BomRepository.bomById);
const mNamespace = vi.mocked(SpdxRepository.findSpdxBomByNamespace);

const CDX = { bomFormat: 'CycloneDX', specVersion: '1.5', serialNumber: 'urn:uuid:11111111-1111-4111-8111-111111111111', version: 1 };
const SPDX = { spdxVersion: 'SPDX-2.3', documentNamespace: 'https://example.com/ns/1', name: 'doc' };

function digestOf(doc: unknown): string {
    return createHash('sha256').update(JSON.stringify(doc)).digest('hex');
}

function held(content: unknown | Buffer): TeaHeldFile {
    const bytes = Buffer.isBuffer(content) ? content : Buffer.from(typeof content === 'string' ? content : JSON.stringify(content));
    const file = path.join(tempDir('rebom-sbom-'), '000001.bin');
    fs.writeFileSync(file, bytes);
    return { number: 1, path: file, sizeBytes: bytes.length, sha256: 'x', documentClass: 'SBOM' };
}

function request(extra: Record<string, unknown> = {}): any {
    return { fetchId: 'a'.repeat(32), number: 1, org: ORG, tags: { t: 1 }, rebomOptions: { name: 'n' }, continueLineage: null, ...extra };
}

const row = (uuid: string, meta: Record<string, unknown> = {}): any => ({ uuid, meta, organization: ORG });

beforeEach(() => {
    mAdd.mockReset();
    mAll.mockReset().mockResolvedValue([]);
    mById.mockReset().mockResolvedValue([]);
    mNamespace.mockReset().mockResolvedValue(null as any);
});

afterEach(() => {
    vi.restoreAllMocks();
    cleanupDirs();
});

describe('what is a document', () => {
    it('is CycloneDX by bomFormat or specFormat in any case, SPDX by a spdxVersion text, and nothing else', () => {
        expect(sbomFormatOf({ bomFormat: 'CycloneDX' })).toBe('CYCLONEDX');
        expect(sbomFormatOf({ bomFormat: 'CYCLONEDX' })).toBe('CYCLONEDX');
        expect(sbomFormatOf({ bomFormat: 'cyclonedx' })).toBe('CYCLONEDX');
        expect(sbomFormatOf({ specFormat: 'CycloneDX' })).toBe('CYCLONEDX');
        expect(sbomFormatOf({ spdxVersion: 'SPDX-2.3' })).toBe('SPDX');
        expect(sbomFormatOf({ spdxVersion: '' })).toBe('SPDX');
        expect(sbomFormatOf({ bomFormat: 'CycloneDX', spdxVersion: 'SPDX-2.3' })).toBe('CYCLONEDX');
        for (const doc of [{}, { bomFormat: 'SPDX' }, { bomFormat: 5 }, { specFormat: null }, { spdxVersion: 2.3 }, { spdxVersion: null }, [], 'CycloneDX', 5, null, undefined, true]) {
            expect(sbomFormatOf(doc), JSON.stringify(doc)).toBeNull();
        }
    });

    it('the largest file the upload door admits is 50 MiB', () => {
        expect(TEA_SBOM_MAX_BYTES).toBe(52428800);
    });
});

describe('before the add path', () => {
    it('refuses a file larger than the bound, takes one of exactly the bound', async () => {
        const file = held(CDX);
        mAdd.mockResolvedValue({ record: row('r1'), disposition: 'CREATED' } as any);
        expect(await storeHeldSbom(file, request(), file.sizeBytes - 1)).toMatchObject({ outcome: 'OK', stored: 'TOO_LARGE', bom: null, format: null });
        expect(mAdd).not.toHaveBeenCalled();
        expect((await storeHeldSbom(file, request(), file.sizeBytes)).stored).toBe('CREATED');
        expect((await storeHeldSbom(file, request(), file.sizeBytes + 1)).stored).toBe('CREATED');
    });

    it('answers NOT_PARSEABLE for text that is not JSON and for bytes that are not UTF-8, and drops one byte order mark', async () => {
        mAdd.mockResolvedValue({ record: row('r1'), disposition: 'CREATED' } as any);
        for (const content of ['not json', '', '{"bomFormat":', Buffer.from([0x7b, 0x22, 0xff, 0x22, 0x3a, 0x31, 0x7d]), Buffer.from([0xc3, 0x28])]) {
            const result = await storeHeldSbom(held(content), request(), 1000);
            expect(result, String(content)).toMatchObject({ stored: 'NOT_PARSEABLE', bom: null });
        }
        expect(mAdd).not.toHaveBeenCalled();
        const withMark = Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), Buffer.from(JSON.stringify(CDX))]);
        const result = await storeHeldSbom(held(withMark), request(), 1000);
        expect(result.stored).toBe('CREATED');
        expect(mAdd.mock.calls[0][0].bomInput.bom).toEqual(CDX);
    });

    it('answers NOT_AN_SBOM for JSON that is neither format, whatever the file was called', async () => {
        for (const content of [{}, [], 5, 'text', null, { bomFormat: 'SPDX' }, { name: 'a cyclonedx file' }]) {
            const result = await storeHeldSbom(held(JSON.stringify(content)), request(), 1000);
            expect(result, JSON.stringify(content)).toMatchObject({ stored: 'NOT_AN_SBOM', format: null });
        }
        expect(mAdd).not.toHaveBeenCalled();
    });

    it('every result has the OK outcome, no error, the stored answer and the fields of a store', async () => {
        const result = await storeHeldSbom(held('nope'), request(), 1000);
        expect(result).toEqual({
            teaApiVersion: 1, outcome: 'OK', error: null, stored: 'NOT_PARSEABLE', format: null, bom: null, conflict: null, storeMessage: null
        });
    });
});

describe('what is asked of the add path', () => {
    it('a CycloneDX document: the parsed document, the organisation, a copy of the options, the tags, origin UPLOAD, the NEW_OR_NAMED policy', async () => {
        const record = row('created-1');
        mAdd.mockResolvedValue({ record, disposition: 'CREATED' } as any);
        const options = { name: 'n', group: 'g' };
        const result = await storeHeldSbom(held(CDX), request({ rebomOptions: options, continueLineage: 'urn:uuid:named' }), 1000);
        expect(result).toMatchObject({ outcome: 'OK', stored: 'CREATED', format: 'CYCLONEDX', bom: record, conflict: null, storeMessage: null });
        expect(mAdd).toHaveBeenCalledTimes(1);
        const [input, origin, policy] = mAdd.mock.calls[0];
        expect(input.bomInput).toEqual({ bom: CDX, format: 'CYCLONEDX', org: ORG, tags: { t: 1 }, rebomOptions: options });
        expect(input.bomInput.rebomOptions).not.toBe(options);
        expect(origin).toBe('UPLOAD');
        expect(policy).toEqual({ kind: 'NEW_OR_NAMED', named: 'urn:uuid:named' });
        // a CycloneDX document looks nothing up before the add path
        expect(mAll).not.toHaveBeenCalled();
        expect(mNamespace).not.toHaveBeenCalled();
    });

    it('the add path\'s own answer for an identical document is EXISTING', async () => {
        mAdd.mockResolvedValue({ record: row('old'), disposition: 'EXISTING' } as any);
        expect(await storeHeldSbom(held(CDX), request(), 1000)).toMatchObject({ stored: 'EXISTING', bom: { uuid: 'old' } });
    });

    it('an SPDX document with no lineage named goes straight to the add path, with no serial number of its own', async () => {
        mAdd.mockResolvedValue({ record: row('s1'), disposition: 'CREATED' } as any);
        const result = await storeHeldSbom(held(SPDX), request(), 1000);
        expect(result).toMatchObject({ stored: 'CREATED', format: 'SPDX' });
        expect(mAdd.mock.calls[0][0].bomInput).toEqual({ bom: SPDX, format: 'SPDX', org: ORG, tags: { t: 1 }, rebomOptions: { name: 'n' } });
        expect(mAdd.mock.calls[0][2]).toEqual({ kind: 'NEW_OR_NAMED', named: null });
        expect(mAll).not.toHaveBeenCalled();
        expect(mNamespace).not.toHaveBeenCalled();
    });

    it('an SPDX document that names a lineage is asked as an update of it', async () => {
        mAdd.mockResolvedValue({ record: row('s2'), disposition: 'CREATED' } as any);
        const result = await storeHeldSbom(held(SPDX), request({ continueLineage: 'urn:uuid:lineage' }), 1000);
        expect(result.stored).toBe('CREATED');
        expect(mAdd.mock.calls[0][0].bomInput).toEqual({
            bom: SPDX, format: 'SPDX', org: ORG, tags: { t: 1 }, rebomOptions: { name: 'n' }, existingSerialNumber: 'urn:uuid:lineage'
        });
        expect(mAdd.mock.calls[0][2]).toEqual({ kind: 'NEW_OR_NAMED', named: 'urn:uuid:lineage' });
        expect(mAll).toHaveBeenCalledWith('urn:uuid:lineage', ORG);
        expect(mNamespace).toHaveBeenCalledWith('https://example.com/ns/1', ORG);
    });

    it('an SPDX document whose namespace is not text is looked up under the empty namespace', async () => {
        mAdd.mockResolvedValue({ record: row('s3'), disposition: 'CREATED' } as any);
        await storeHeldSbom(held({ spdxVersion: 'SPDX-2.3', documentNamespace: 5 }), request({ continueLineage: 'urn:uuid:l' }), 1000);
        expect(mNamespace).toHaveBeenCalledWith('', ORG);
    });
});

describe('an SPDX document under a named lineage that is already held', () => {
    const named = { continueLineage: 'urn:uuid:lineage' };

    it('is EXISTING with the row of the lineage that holds this very document, and nothing is pushed', async () => {
        mAll.mockResolvedValue([row('other', { originalFileDigest: 'zzz' }), row('same', { originalFileDigest: digestOf(SPDX) })]);
        const result = await storeHeldSbom(held(SPDX), request(named), 1000);
        expect(result).toMatchObject({ stored: 'EXISTING', format: 'SPDX', bom: { uuid: 'same' } });
        expect(mNamespace).not.toHaveBeenCalled();
        expect(mAdd).not.toHaveBeenCalled();
    });

    it('is EXISTING with the converted row when the namespace holds this very file, INVALID when it holds another or no row', async () => {
        mNamespace.mockResolvedValue({ file_sha256: digestOf(SPDX), converted_bom_uuid: 'conv-1' } as any);
        mById.mockResolvedValue([row('conv-row')]);
        expect(await storeHeldSbom(held(SPDX), request(named), 1000)).toMatchObject({ stored: 'EXISTING', bom: { uuid: 'conv-row' } });
        expect(mById).toHaveBeenCalledWith('conv-1');
        const message = 'The documentNamespace of this SPDX document already holds another document; a new version of an SPDX document needs a namespace of its own.';
        mById.mockResolvedValue([]);
        expect(await storeHeldSbom(held(SPDX), request(named), 1000)).toMatchObject({ stored: 'INVALID', storeMessage: message, bom: null });
        mById.mockResolvedValue([row('conv-row')]);
        mNamespace.mockResolvedValue({ file_sha256: 'another', converted_bom_uuid: 'conv-1' } as any);
        expect(await storeHeldSbom(held(SPDX), request(named), 1000)).toMatchObject({ stored: 'INVALID', storeMessage: message });
        mNamespace.mockResolvedValue({ file_sha256: digestOf(SPDX), converted_bom_uuid: null } as any);
        expect(await storeHeldSbom(held(SPDX), request(named), 1000)).toMatchObject({ stored: 'INVALID', storeMessage: message });
        mNamespace.mockResolvedValue({ file_sha256: 'another', converted_bom_uuid: null } as any);
        expect(await storeHeldSbom(held(SPDX), request(named), 1000)).toMatchObject({ stored: 'INVALID' });
        expect(mAdd).not.toHaveBeenCalled();
    });
});

describe('every way the add path can refuse', () => {
    it('a version conflict or a lineage not permitted is EXISTING when the lineage already holds this document', async () => {
        mAll.mockResolvedValue([row('same', { originalFileDigest: digestOf(CDX) })]);
        mAdd.mockRejectedValueOnce(new BomVersionConflictError('conflict', 'urn:uuid:s', 1, 1, 'old'));
        expect(await storeHeldSbom(held(CDX), request(), 1000)).toMatchObject({ stored: 'EXISTING', bom: { uuid: 'same' }, conflict: null });
        expect(mAll).toHaveBeenCalledWith('urn:uuid:s', ORG);
        mAdd.mockRejectedValueOnce(new BomLineageNotPermittedError('not permitted', 'urn:uuid:t', 1, 1, 'old'));
        expect(await storeHeldSbom(held(CDX), request(), 1000)).toMatchObject({ stored: 'EXISTING', bom: { uuid: 'same' } });
        expect(mAll).toHaveBeenLastCalledWith('urn:uuid:t', ORG);
    });

    it('otherwise a version conflict is VERSION_CONFLICT and a lineage not permitted is LINEAGE_EXISTS, with both versions as text', async () => {
        mAdd.mockRejectedValueOnce(new BomVersionConflictError('conflict', 'urn:uuid:s', 3, '2', 'existing-1'));
        expect(await storeHeldSbom(held(CDX), request(), 1000)).toMatchObject({
            outcome: 'OK', stored: 'VERSION_CONFLICT', format: 'CYCLONEDX', bom: null,
            conflict: { serialNumber: 'urn:uuid:s', storedVersion: '3', documentVersion: '2', existingUuid: 'existing-1' }
        });
        mAdd.mockRejectedValueOnce(new BomLineageNotPermittedError('not permitted', 'urn:uuid:t', 7, null, 'existing-2'));
        expect(await storeHeldSbom(held(CDX), request(), 1000)).toMatchObject({
            stored: 'LINEAGE_EXISTS', conflict: { serialNumber: 'urn:uuid:t', storedVersion: '7', documentVersion: null, existingUuid: 'existing-2' }
        });
        mAdd.mockRejectedValueOnce(new BomLineageNotPermittedError('not permitted', 'urn:uuid:t', null, 0, 'existing-3'));
        expect(await storeHeldSbom(held(CDX), request(), 1000)).toMatchObject({
            stored: 'LINEAGE_EXISTS', conflict: { storedVersion: null, documentVersion: '0' }
        });
        mAdd.mockRejectedValueOnce(new BomLineageNotPermittedError('not permitted', 'urn:uuid:t', '1.0', undefined as any, 'existing-4'));
        expect(await storeHeldSbom(held(CDX), request(), 1000)).toMatchObject({ conflict: { storedVersion: '1.0', documentVersion: null } });
    });

    it('a validation error is INVALID with its message made printable and cut at 1024 bytes', async () => {
        mAdd.mockRejectedValueOnce(new BomValidationError('bad \u00e9\nvalue'));
        expect(await storeHeldSbom(held(CDX), request(), 1000)).toMatchObject({ stored: 'INVALID', storeMessage: 'bad ??value', conflict: null, bom: null });
        mAdd.mockRejectedValueOnce(new BomValidationError('m'.repeat(2000)));
        const long = await storeHeldSbom(held(CDX), request(), 1000);
        expect(long.storeMessage).toBe('m'.repeat(1024));
        mAdd.mockRejectedValueOnce(new RangeError('out of range \u00e9'));
        expect(await storeHeldSbom(held(CDX), request(), 1000)).toMatchObject({ stored: 'INVALID', storeMessage: 'out of range ?' });
        mAdd.mockRejectedValueOnce(new RangeError('r'.repeat(2000)));
        expect((await storeHeldSbom(held(CDX), request(), 1000)).storeMessage).toBe('r'.repeat(1024));
    });

    it('an SPDX namespace refusal is LINEAGE_EXISTS with the stored lineage, the newest version first, when there is one', async () => {
        const refusal = () => new BomValidationError('namespace taken', { field: 'documentNamespace' });
        mAdd.mockRejectedValueOnce(refusal());
        mNamespace.mockResolvedValue({ converted_bom_uuid: 'conv-1', file_sha256: 'x' } as any);
        mById.mockResolvedValue([row('conv-row', { serialNumber: 'urn:uuid:lineage', bomVersion: 1 })]);
        mAll.mockResolvedValue([row('newest', { bomVersion: 4 }), row('older', { bomVersion: 3 })]);
        const result = await storeHeldSbom(held(SPDX), request(), 1000);
        expect(result).toMatchObject({
            stored: 'LINEAGE_EXISTS', format: 'SPDX', conflict: { serialNumber: 'urn:uuid:lineage', storedVersion: '4', documentVersion: null, existingUuid: 'newest' }
        });
        expect(mNamespace).toHaveBeenCalledWith('https://example.com/ns/1', ORG);
        expect(mAll).toHaveBeenCalledWith('urn:uuid:lineage', ORG);
        // with no row in the lineage the converted row stands for it
        mAdd.mockRejectedValueOnce(refusal());
        mAll.mockResolvedValue([]);
        expect(await storeHeldSbom(held(SPDX), request(), 1000)).toMatchObject({ conflict: { storedVersion: '1', existingUuid: 'conv-row' } });
    });

    it('an SPDX namespace refusal with nothing to name, or on a CycloneDX document, is INVALID with its message', async () => {
        const refusal = () => new BomValidationError('namespace taken', { field: 'documentNamespace' });
        for (const arrange of [
            () => mNamespace.mockResolvedValue(null as any),
            () => mNamespace.mockResolvedValue({ converted_bom_uuid: null } as any),
            () => { mNamespace.mockResolvedValue({ converted_bom_uuid: 'c' } as any); mById.mockResolvedValue([row('c', {})]); },
            () => { mNamespace.mockResolvedValue({ converted_bom_uuid: 'c' } as any); mById.mockResolvedValue([row('c', { serialNumber: '' })]); }
        ]) {
            arrange();
            mAdd.mockRejectedValueOnce(refusal());
            expect(await storeHeldSbom(held(SPDX), request(), 1000)).toMatchObject({ stored: 'INVALID', storeMessage: 'namespace taken', conflict: null });
        }
        mNamespace.mockResolvedValue({ converted_bom_uuid: 'c' } as any);
        mById.mockResolvedValue([row('c', { serialNumber: 'urn:uuid:x' })]);
        mAdd.mockRejectedValueOnce(refusal());
        expect(await storeHeldSbom(held(CDX), request(), 1000)).toMatchObject({ stored: 'INVALID', storeMessage: 'namespace taken', conflict: null });
        mAdd.mockRejectedValueOnce(new BomValidationError('other field', { field: 'org' }));
        expect(await storeHeldSbom(held(SPDX), request(), 1000)).toMatchObject({ stored: 'INVALID', storeMessage: 'other field' });
        mAdd.mockRejectedValueOnce(new BomValidationError('no details'));
        expect(await storeHeldSbom(held(SPDX), request(), 1000)).toMatchObject({ stored: 'INVALID', storeMessage: 'no details' });
    });

    it('any other error is STORE_FAILED with a reference that is in the log line, and nothing of the error in the answer', async () => {
        const errors = vi.spyOn(logger, 'error').mockImplementation(() => undefined as never);
        const failure = new Error('connection to db-secret.internal refused');
        mAdd.mockRejectedValueOnce(failure);
        const result = await storeHeldSbom(held(CDX), request(), 1000);
        expect(result).toMatchObject({ outcome: 'OK', stored: 'STORE_FAILED', format: 'CYCLONEDX', bom: null, conflict: null });
        expect(result.storeMessage).toMatch(/^Internal error \(ref [0-9a-f]{8}\)$/);
        expect(JSON.stringify(result)).not.toContain('db-secret');
        expect(errors).toHaveBeenCalledTimes(1);
        const [fields, message] = errors.mock.calls[0] as any;
        expect(message).toBe('TEA: storing a fetched SBOM failed');
        expect(fields.err).toBe(failure);
        expect(result.storeMessage).toBe(`Internal error (ref ${fields.ref})`);
        mAdd.mockRejectedValueOnce('a string');
        expect((await storeHeldSbom(held(CDX), request(), 1000)).stored).toBe('STORE_FAILED');
    });
});
