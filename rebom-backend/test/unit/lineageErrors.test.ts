import { describe, it, expect } from 'vitest';
import { BomLineageOwnerError, toGraphQLError } from '../../src/types/errors';
import { canonicalSerialNumber } from '../../src/bomLineageRepository';

// SCORE-46 T-7: the refusal names the serialNumber and nothing else; the
// lineage key adds the urn:uuid: prefix and trims, and folds nothing.
const S = 'urn:uuid:11111111-2222-3333-4444-555555555555';
const S_BARE = '11111111-2222-3333-4444-555555555555';
const MSG = `BOM with serialNumber ${S} belongs to another component of this organization; ` +
    'a new version of it can only be uploaded to that component. Upload an SBOM with its own serialNumber instead.';

describe('BomLineageOwnerError over GraphQL', () => {
    it('T-7: maps to BOM_LINEAGE_OWNER_MISMATCH with the serialNumber as the only detail', () => {
        expect(toGraphQLError(new BomLineageOwnerError(MSG, S))).toStrictEqual({
            message: MSG,
            extensions: { code: 'BOM_LINEAGE_OWNER_MISMATCH', details: { serialNumber: S } }
        });
    });
});

describe('canonicalSerialNumber', () => {
    it('T-7: keeps the prefixed form', () => {
        expect(canonicalSerialNumber(S)).toBe(S);
    });

    it('T-7: adds the prefix to the bare form', () => {
        expect(canonicalSerialNumber(S_BARE)).toBe(S);
    });

    it('T-7: trims before prefixing', () => {
        expect(canonicalSerialNumber('  ' + S_BARE + ' ')).toBe(S);
    });

    it('T-7: does not fold case', () => {
        // An upper-case uuid under the prefix is returned unchanged: a different
        // lineage from the lower-case one, as it is a different row today.
        const lower = 'urn:uuid:abcdef12-3456-4789-8abc-def012345678';
        const upper = 'urn:uuid:ABCDEF12-3456-4789-8ABC-DEF012345678';
        expect(canonicalSerialNumber(upper)).toBe(upper);
        expect(canonicalSerialNumber(upper)).not.toBe(canonicalSerialNumber(lower));
    });

    it('T-7: blank values give the empty string', () => {
        expect(canonicalSerialNumber('')).toBe('');
        expect(canonicalSerialNumber('   ')).toBe('');
    });
});
