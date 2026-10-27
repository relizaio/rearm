import { describe, it, expect } from 'vitest';
import { mergeRootKeyRecords, isProductRow } from '../../src/services/bom/bomProcessingService';
import { RootComponentMergeMode } from '../../src/types';
import { REBOM_MERGE_MOD, UPLOAD_MOD } from '../../src/services/bom/releaseNodeStamp';

/**
 * SCORE-69 T-3 (ADR-1, ADR-3): the two records a merge row stores. nestedProductSerials: the recorded
 * serial (row meta, never the document) of each unlisted input that is a product row; listedRootKeys:
 * the root purl and bom-ref of each listed input. Sorted, de-duplicated, pure.
 */

const PRESERVE = RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT;
const FLATTEN = RootComponentMergeMode.FLATTEN_UNDER_NEW_ROOT;
const SERIAL_A = 'urn:uuid:66000000-0000-4000-8000-00000000000a';
const SERIAL_B = 'urn:uuid:66000000-0000-4000-8000-00000000000b';
const SERIAL_L = 'urn:uuid:66000000-0000-4000-8000-00000000000c';
const X_P = 'pkg:generic/Facts%20Org/inner@2.0.0';
const LISTED_ROOT = 'pkg:oci/facts-product@3.0.0?belongsto=RELEASE';
const LISTED_REF = 'listed-root-ref';

function doc(serial: string | undefined, root?: any): any {
    const bom: any = { bomFormat: 'CycloneDX', specVersion: '1.6', version: 1, components: [] };
    if (root !== undefined) bom.metadata = { component: root };
    if (serial !== undefined) bom.serialNumber = serial;
    return bom;
}

function productRow(serial: string | undefined, root: any = { type: 'application', name: 'inner', purl: X_P, 'bom-ref': X_P }): any {
    return doc(serial, root);
}

function listedDoc(root: any = { type: 'application', name: 'facts-product', purl: LISTED_ROOT, 'bom-ref': LISTED_REF }): any {
    return doc(SERIAL_L, root);
}

const productMeta = (extra: any = {}) => ({ mod: REBOM_MERGE_MOD, rootComponentMergeMode: PRESERVE, nestedProductSerials: [], listedRootKeys: [], ...extra });
const productMetaOf = (serial: string, extra: any = {}) => productMeta({ serialNumber: serial, ...extra });
const componentMeta = (extra: any = {}) => ({ mod: REBOM_MERGE_MOD, rootComponentMergeMode: FLATTEN, ...extra });
const uploadMeta = (extra: any = {}) => ({ mod: UPLOAD_MOD, rootComponentMergeMode: PRESERVE, ...extra });
const listed = { releaseRootIds: ['listed'] };

/** Calls the helper and checks it changed none of its arguments. */
function records(ids: string[], inputs: any[], options: any, rowMeta?: any[]) {
    const before = structuredClone(inputs);
    const metaBefore = structuredClone(rowMeta);
    const out = rowMeta === undefined ? mergeRootKeyRecords(ids, inputs, options) : mergeRootKeyRecords(ids, inputs, options, rowMeta);
    expect(inputs).toStrictEqual(before);
    expect(rowMeta).toStrictEqual(metaBefore);
    return out;
}

describe('mergeRootKeyRecords: nestedProductSerials (SCORE-69 T-3)', () => {
    it('records the recorded serial of an unlisted product row, whatever its root carries', () => {
        const roots = [
            { type: 'application', name: 'inner', purl: X_P, 'bom-ref': X_P },
            { type: 'application', name: 'inner', purl: X_P },
            { type: 'application', name: 'inner', 'bom-ref': 'inner-ref' },
            { type: 'application', name: 'inner' }
        ];
        for (const root of roots) {
            expect(records(['listed', 'p'], [listedDoc(), productRow(SERIAL_B, root)], listed, [undefined, productMetaOf(SERIAL_B)]).nestedProductSerials).toStrictEqual([SERIAL_B]);
        }
        expect(records(['listed', 'p'], [listedDoc(), doc(SERIAL_B)], listed, [undefined, productMetaOf(SERIAL_B)]).nestedProductSerials).toStrictEqual([SERIAL_B]);
    });

    it('records the row serial, never the document serial', () => {
        expect(records(['listed', 'p'], [listedDoc(), productRow(SERIAL_A)], listed, [undefined, productMetaOf(SERIAL_B)]).nestedProductSerials).toStrictEqual([SERIAL_B]);
    });

    it('records nothing for a product row without a recorded serial', () => {
        expect(records(['p'], [productRow(SERIAL_B)], listed, [productMeta()]).nestedProductSerials).toStrictEqual([]);
        expect(records(['p'], [productRow(SERIAL_B)], listed, [productMeta({ serialNumber: '' })]).nestedProductSerials).toStrictEqual([]);
    });

    it('lets the kind decide: a component row, an upload, a missing meta record nothing', () => {
        const inputs = [listedDoc(), productRow(SERIAL_B)];
        expect(records(['listed', 'p'], inputs, listed, [undefined, componentMeta({ serialNumber: SERIAL_B })]).nestedProductSerials).toStrictEqual([]);
        expect(records(['listed', 'p'], inputs, listed, [undefined, uploadMeta({ serialNumber: SERIAL_B })]).nestedProductSerials).toStrictEqual([]);
        expect(records(['listed', 'p'], inputs, listed, [undefined, undefined]).nestedProductSerials).toStrictEqual([]);
        expect(records(['listed', 'p'], inputs, listed, [undefined]).nestedProductSerials).toStrictEqual([]);
        expect(records(['listed', 'p'], inputs, listed, [undefined, { mod: REBOM_MERGE_MOD, serialNumber: SERIAL_B }]).nestedProductSerials).toStrictEqual([SERIAL_B]);
        expect(records(['listed', 'p'], inputs, listed).nestedProductSerials).toStrictEqual([]);
    });

    it('never records a placeholder, with or without a meta entry at its index', () => {
        const inputs = [productRow(SERIAL_B), doc(undefined, { type: 'library', name: 'worker', 'bom-ref': 'ph' })];
        expect(records(['p'], inputs, {}, [productMetaOf(SERIAL_B)]).nestedProductSerials).toStrictEqual([SERIAL_B]);
        expect(records(['p'], inputs, {}, [productMetaOf(SERIAL_B), productMetaOf(SERIAL_A)]).nestedProductSerials).toStrictEqual([SERIAL_B]);
    });

    it('sorts and de-duplicates', () => {
        const inputs = [productRow(SERIAL_L), productRow(SERIAL_A), productRow(SERIAL_B)];
        expect(records(['q', 'p', 'r'], inputs, {}, [productMetaOf(SERIAL_L), productMetaOf(SERIAL_A), productMetaOf(SERIAL_A)]).nestedProductSerials).toStrictEqual([SERIAL_A, SERIAL_L]);
        expect(records(['q', 'p'], [productRow(SERIAL_A), productRow(SERIAL_B)], {}, [productMetaOf(SERIAL_A), productMetaOf(SERIAL_B)]).nestedProductSerials).toStrictEqual([SERIAL_A, SERIAL_B]);
    });
});

describe('mergeRootKeyRecords: listedRootKeys (SCORE-69 T-3)', () => {
    it('records the root keys of the inputs either list names', () => {
        const inputs = [listedDoc(), listedDoc({ type: 'application', name: 'sce', purl: 'pkg:npm/sce@1.0.0', 'bom-ref': 'sce-ref' }), productRow(SERIAL_B)];
        const ids = ['listed', 'attached', 'p'];
        expect(records(ids, inputs, { releaseRootIds: ['listed'] }).listedRootKeys).toStrictEqual([LISTED_REF, LISTED_ROOT].sort());
        expect(records(ids, inputs, { attachedRootIds: ['attached'] }).listedRootKeys).toStrictEqual(['pkg:npm/sce@1.0.0', 'sce-ref']);
        expect(records(ids, inputs, { releaseRootIds: ['listed'], attachedRootIds: ['attached'] }).listedRootKeys).toStrictEqual([LISTED_REF, LISTED_ROOT, 'pkg:npm/sce@1.0.0', 'sce-ref'].sort());
        expect(records(ids, inputs, {}).listedRootKeys).toStrictEqual([]);
    });

    it('records the purl or the bom-ref a listed root has, and nothing for a root with neither', () => {
        expect(records(['listed'], [listedDoc({ type: 'application', name: 'x', purl: LISTED_ROOT })], listed).listedRootKeys).toStrictEqual([LISTED_ROOT]);
        expect(records(['listed'], [listedDoc({ type: 'application', name: 'x', 'bom-ref': LISTED_REF })], listed).listedRootKeys).toStrictEqual([LISTED_REF]);
        expect(records(['listed'], [listedDoc({ type: 'application', name: 'x' })], listed).listedRootKeys).toStrictEqual([]);
    });

    it('takes a listed product row as listed: its keys recorded, its serial not', () => {
        const out = records(['listed'], [productRow(SERIAL_L)], listed, [productMetaOf(SERIAL_L)]);
        expect(out.listedRootKeys).toStrictEqual([X_P]);
        expect(out.nestedProductSerials).toStrictEqual([]);
    });

    it('ignores a listed id that is not an input, and copes with short or empty inputs', () => {
        expect(records(['p'], [productRow(SERIAL_B)], { releaseRootIds: ['gone'] }, [productMetaOf(SERIAL_B)])).toStrictEqual({ nestedProductSerials: [SERIAL_B], listedRootKeys: [] });
        expect(records(['listed', 'p'], [listedDoc()], listed, [undefined, productMetaOf(SERIAL_B)])).toStrictEqual({ nestedProductSerials: [SERIAL_B], listedRootKeys: [LISTED_REF, LISTED_ROOT].sort() });
        expect(records([], [], {}, [])).toStrictEqual({ nestedProductSerials: [], listedRootKeys: [] });
    });
});

describe('isProductRow (SCORE-69 ADR-1)', () => {
    it('is a rebom merge row under any mode but FLATTEN', () => {
        expect(isProductRow(productMeta() as any)).toBe(true);
        expect(isProductRow({ mod: REBOM_MERGE_MOD } as any)).toBe(true);
        expect(isProductRow(componentMeta() as any)).toBe(false);
        expect(isProductRow(uploadMeta() as any)).toBe(false);
        expect(isProductRow({ rootComponentMergeMode: PRESERVE } as any)).toBe(false);
        expect(isProductRow(undefined)).toBe(false);
    });
});
