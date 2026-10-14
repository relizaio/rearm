import { describe, it, expect } from 'vitest';
import { hashFromReference } from '../../src/services/bom/bomProcessingService';

/**
 * SCORE-30 (T-1, ADR-4): the hash an attachment reference stands for. A git commit (SHA-1, or
 * SHA-256 on a sha256 repository) and a deliverable digest, bare or prefixed, give one CycloneDX
 * hash; anything else gives none.
 */

const SHA1 = 'a94a8fe5ccb19ba61c4c0873d391e987982fbbd3';
const SHA256 = '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08';
const SHA512 = 'ee26b0dd4af7e749aa1a8ee3c10ae9923f618980772e473f8819a5d4940e0db27ac185f8a0e1d5f84f88bc887fd67b143732c304cc5fa9ad8e6f57f50028a8ff';

describe('hashFromReference', () => {
    it('reads 40, 64 and 128 hex characters as SHA-1, SHA-256 and SHA-512 (T-1)', () => {
        expect(hashFromReference(SHA1)).toStrictEqual({ alg: 'SHA-1', content: SHA1 });
        expect(hashFromReference(SHA256)).toStrictEqual({ alg: 'SHA-256', content: SHA256 });
        expect(hashFromReference(SHA512)).toStrictEqual({ alg: 'SHA-512', content: SHA512 });
    });

    it('lower-cases the content (T-1)', () => {
        expect(hashFromReference(SHA1.toUpperCase())).toStrictEqual({ alg: 'SHA-1', content: SHA1 });
        expect(hashFromReference('sha256:' + SHA256.toUpperCase())).toStrictEqual({ alg: 'SHA-256', content: SHA256 });
    });

    it('takes the algorithm from a prefix in any case and drops the prefix (T-1)', () => {
        expect(hashFromReference('sha256:' + SHA256)).toStrictEqual({ alg: 'SHA-256', content: SHA256 });
        expect(hashFromReference('SHA256:' + SHA256)).toStrictEqual({ alg: 'SHA-256', content: SHA256 });
        expect(hashFromReference('sha1:' + SHA1)).toStrictEqual({ alg: 'SHA-1', content: SHA1 });
        expect(hashFromReference('sha512:' + SHA512)).toStrictEqual({ alg: 'SHA-512', content: SHA512 });
    });

    it('trims surrounding whitespace (T-1)', () => {
        expect(hashFromReference('  ' + SHA1 + '\n')).toStrictEqual({ alg: 'SHA-1', content: SHA1 });
        expect(hashFromReference(' sha256:' + SHA256 + ' ')).toStrictEqual({ alg: 'SHA-256', content: SHA256 });
    });

    it('gives none when the prefix and the length disagree (T-1)', () => {
        expect(hashFromReference('sha256:' + SHA1)).toBeUndefined();
        expect(hashFromReference('sha1:' + SHA256)).toBeUndefined();
        expect(hashFromReference('sha512:' + SHA256)).toBeUndefined();
    });

    it('gives none for an svn revision, a short sha, a tag or another algorithm (T-1)', () => {
        expect(hashFromReference('r1234')).toBeUndefined();
        expect(hashFromReference('1234')).toBeUndefined();
        expect(hashFromReference('a94a8fe')).toBeUndefined();
        expect(hashFromReference('v1.2.3')).toBeUndefined();
        expect(hashFromReference('md5:' + SHA1.substring(0, 32))).toBeUndefined();
        expect(hashFromReference(SHA1.substring(0, 32))).toBeUndefined();
        // the right length, not hex
        expect(hashFromReference('g'.repeat(40))).toBeUndefined();
    });

    it('gives none for empty, whitespace, null and undefined (T-1)', () => {
        expect(hashFromReference('')).toBeUndefined();
        expect(hashFromReference('   ')).toBeUndefined();
        expect(hashFromReference('sha256:')).toBeUndefined();
        expect(hashFromReference(null)).toBeUndefined();
        expect(hashFromReference(undefined)).toBeUndefined();
    });
});
