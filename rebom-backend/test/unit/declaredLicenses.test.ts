import { describe, it, expect } from 'vitest';
import { declaredLicenses } from '../../src/services/bom/bomProcessingService';

/**
 * SCORE-40 (T-1, ADR-2): the CycloneDX 1.6 `licenses` written for the licence ReARM declares.
 * One SPDX list id is the id form, a single LicenseRef- with a url the named form, anything else
 * the expression form with the url dropped; a blank expression writes nothing.
 */

const LICENCE_URL = 'https://acme.example/licence';

describe('declaredLicenses', () => {
    it('writes one SPDX list id in the id form, in the list casing (T-1)', () => {
        expect(declaredLicenses('Apache-2.0', undefined)).toStrictEqual([{ license: { id: 'Apache-2.0' } }]);
        expect(declaredLicenses('apache-2.0', undefined)).toStrictEqual([{ license: { id: 'Apache-2.0' } }]);
        expect(declaredLicenses('GPL-2.0-or-later', undefined)).toStrictEqual([{ license: { id: 'GPL-2.0-or-later' } }]);
    });

    it('writes a compound, a WITH and a parenthesised expression in the expression form (T-1)', () => {
        for (const value of ['MIT OR Apache-2.0', 'GPL-2.0-only WITH Classpath-exception-2.0', '(MIT AND BSD-3-Clause) OR GPL-2.0-only']) {
            expect(declaredLicenses(value, undefined), value).toStrictEqual([{ expression: value }]);
        }
    });

    it('writes a single LicenseRef with a url in the named form (T-1)', () => {
        expect(declaredLicenses('LicenseRef-Acme-Commercial', LICENCE_URL))
            .toStrictEqual([{ license: { name: 'LicenseRef-Acme-Commercial', url: 'https://acme.example/licence' } }]);
    });

    it('writes a single LicenseRef without a url, or with a blank one, in the expression form (T-1)', () => {
        for (const url of [undefined, null, '', '   ']) {
            expect(declaredLicenses('LicenseRef-Acme-Commercial', url), String(url))
                .toStrictEqual([{ expression: 'LicenseRef-Acme-Commercial' }]);
        }
    });

    it('drops the url beside anything but a single LicenseRef (T-1)', () => {
        for (const value of ['LicenseRef-A OR MIT', 'LicenseRef-A+', '(LicenseRef-A)', 'LicenseRef-A WITH X']) {
            expect(declaredLicenses(value, LICENCE_URL), value).toStrictEqual([{ expression: value }]);
        }
        expect(declaredLicenses('MIT OR Apache-2.0', LICENCE_URL)).toStrictEqual([{ expression: 'MIT OR Apache-2.0' }]);
        expect(declaredLicenses('Apache-2.0', LICENCE_URL)).toStrictEqual([{ license: { id: 'Apache-2.0' } }]);
    });

    it('trims the expression and the url (T-1)', () => {
        expect(declaredLicenses('  LicenseRef-Acme.1-2  ', ' https://x ')).toStrictEqual([{ license: { name: 'LicenseRef-Acme.1-2', url: 'https://x' } }]);
        expect(declaredLicenses('  MIT OR Apache-2.0 ', undefined)).toStrictEqual([{ expression: 'MIT OR Apache-2.0' }]);
        expect(declaredLicenses(' Apache-2.0 ', undefined)).toStrictEqual([{ license: { id: 'Apache-2.0' } }]);
    });

    it('writes nothing for a blank or absent expression, a url alone included (T-1)', () => {
        expect(declaredLicenses('', LICENCE_URL)).toBeUndefined();
        expect(declaredLicenses('   ', LICENCE_URL)).toBeUndefined();
        expect(declaredLicenses(null, LICENCE_URL)).toBeUndefined();
        expect(declaredLicenses(undefined, undefined)).toBeUndefined();
        expect(declaredLicenses(42 as any, LICENCE_URL)).toBeUndefined();
    });

    it('returns a fresh array on every call (T-1)', () => {
        for (const [value, url] of [['Apache-2.0', undefined], ['MIT OR Apache-2.0', undefined], ['LicenseRef-Acme-Commercial', LICENCE_URL]]) {
            const first = declaredLicenses(value, url)!;
            const second = declaredLicenses(value, url)!;
            expect(second).toStrictEqual(first);
            expect(second).not.toBe(first);
            expect(second[0]).not.toBe(first[0]);
        }
    });
});
