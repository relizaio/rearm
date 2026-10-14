import { describe, it, expect } from 'vitest'
import {
    PROP_SIGNATURE_KEY_ID, PROP_SIGNED, PROP_UNSIGNED_REASON,
    exportSigningLine, hasRootSignature, scoreSignatureLine, signatureStatus,
    type SignatureReportInput, type SignedStatus,
} from './sbomSigning'
import { signingFixture } from './__fixtures__/sbomSigning/fixtures'

// T-27 (SCORE-35, design 3.5 and 3.6): what a merged CycloneDX JSON export says about its own
// signature, the Export dialog's line from it, and the score report's signature line.
const KEY_ID = '00000000-0000-4000-8000-000000000001'

describe('signatureStatus', () => {
    it('reads a signed export with its key id', () => {
        expect(signatureStatus(signingFixture('signed-ed25519.cdx.json'))).toEqual({ kind: 'SIGNED', keyId: KEY_ID })
    })

    it('reads an unsigned export with its reason, NO_KEY or SIGNING_ERROR', () => {
        expect(signatureStatus(signingFixture('unsigned.cdx.json'))).toEqual({ kind: 'UNSIGNED', reason: 'NO_KEY' })
        expect(signatureStatus(signingFixture('signing-error.cdx.json'))).toEqual({ kind: 'UNSIGNED', reason: 'SIGNING_ERROR' })
    })

    it('reads the stamp and the root signature independently (malformed: a signature without value)', () => {
        const doc = signingFixture('malformed.cdx.json')
        expect(hasRootSignature(doc)).toBe(false)
        expect(signatureStatus(doc)).toEqual({ kind: 'SIGNED', keyId: KEY_ID })
        expect(hasRootSignature(signingFixture('signed-ed25519.cdx.json'))).toBe(true)
        expect(hasRootSignature(signingFixture('keyid-only.cdx.json'))).toBe(true)
        expect(hasRootSignature(signingFixture('unsigned.cdx.json'))).toBe(false)
    })

    it('says UNSTAMPED for a document with other reliza:export properties but no signed stamp', () => {
        expect(signatureStatus(signingFixture('unstamped.cdx.json'))).toEqual({ kind: 'UNSTAMPED' })
    })

    it('takes the first occurrence of a property name', () => {
        expect(signatureStatus(signingFixture('duplicate-stamp.cdx.json'))).toEqual({ kind: 'SIGNED', keyId: KEY_ID })
    })

    it('says UNREADABLE for text that is not a JSON object, and never throws', () => {
        expect(signatureStatus(signingFixture('not-json.txt'))).toEqual({ kind: 'UNREADABLE' })
        expect(signatureStatus('[]')).toEqual({ kind: 'UNREADABLE' })
        expect(signatureStatus('null')).toEqual({ kind: 'UNREADABLE' })
        expect(signatureStatus('')).toEqual({ kind: 'UNREADABLE' })
        expect(hasRootSignature('not json')).toBe(false)
    })

    it('gives the same answer for a parsed object as for its text', () => {
        for (const name of ['signed-ed25519.cdx.json', 'unsigned.cdx.json', 'signing-error.cdx.json', 'unstamped.cdx.json', 'duplicate-stamp.cdx.json', 'malformed.cdx.json']) {
            const text = signingFixture(name)
            expect(signatureStatus(JSON.parse(text))).toEqual(signatureStatus(text))
            expect(hasRootSignature(JSON.parse(text))).toBe(hasRootSignature(text))
        }
    })

    it('compares the value as the string "true": a boolean true is not a signed stamp', () => {
        const doc = { metadata: { properties: [{ name: 'reliza:export:signed', value: true }] } }
        expect(signatureStatus(doc)).toEqual({ kind: 'UNSIGNED', reason: '' })
    })

    it('reads signed=false without a reason as UNSIGNED with an empty reason', () => {
        const doc = { metadata: { properties: [{ name: 'reliza:export:signed', value: 'false' }] } }
        expect(signatureStatus(doc)).toEqual({ kind: 'UNSIGNED', reason: '' })
    })
})

// T-1 (SCORE-35 run 1): the stamp is matched by its exact property name, first occurrence wins
// (design 3.5), and only the exact string "true" says signed. A forged or near-name property from
// an uploaded SBOM survives the server's egress sweep (it strips by exact name only), so a name
// or value that is normalised before matching would turn a NO_KEY export into a signed line.
describe('signatureStatus: exact names and values only', () => {
    const NO_KEY_LINE = 'Not signed: no organization SBOM signing key is configured (Organization settings, SBOM export signing)'
    const FORGED_KID = '00000000-0000-4000-8000-0000000000ff'
    const prop = (name: string, value: unknown) => ({ name, value })
    const doc = (...properties: object[]) => ({ metadata: { properties } })
    const line = (d: string | object) => exportSigningLine(signatureStatus(d), 'JSON')
    // Leading or trailing blanks (space, tab, newline, no-break space), other case, a prefix or a suffix.
    const nearNames = (name: string): string[] => {
        const near = [
            ` ${name}`, `${name} `, `\t${name}`, `${name}\n`, `${name}\u00a0`,
            name.toUpperCase(), name.charAt(0).toUpperCase() + name.slice(1), name.slice(0, -1) + name.slice(-1).toUpperCase(),
            `x-${name}`, `${name}X`, `${name}:`, name.replace(/:/g, '.'),
        ]
        // every variant really differs from the name, or a case would test nothing
        expect(near.filter(n => n === name)).toEqual([])
        return near
    }

    it('reads the real forged NO_KEY export as NO_KEY: its near-name true stamps count for nothing', () => {
        const text = signingFixture('forged-nokey.cdx.json')
        expect(signatureStatus(text)).toEqual({ kind: 'UNSIGNED', reason: 'NO_KEY' })
        expect(line(text)).toBe(NO_KEY_LINE)
        expect(line(JSON.parse(text))).toBe(NO_KEY_LINE)
    })

    it('a near-name signed=true ahead of the real NO_KEY stamp is not a stamp', () => {
        for (const near of nearNames(PROP_SIGNED)) {
            const d = doc(prop(near, 'true'), prop(PROP_SIGNATURE_KEY_ID, FORGED_KID),
                prop(PROP_SIGNED, 'false'), prop(PROP_UNSIGNED_REASON, 'NO_KEY'))
            expect(signatureStatus(d), JSON.stringify(near)).toEqual({ kind: 'UNSIGNED', reason: 'NO_KEY' })
            expect(line(d), JSON.stringify(near)).toBe(NO_KEY_LINE)
            expect(line(JSON.stringify(d)), JSON.stringify(near)).toBe(NO_KEY_LINE)
        }
    })

    it('a near-name signed=true alone leaves the document unstamped: no line', () => {
        for (const near of nearNames(PROP_SIGNED)) {
            const d = doc(prop(near, 'true'), prop(PROP_SIGNATURE_KEY_ID, FORGED_KID))
            expect(signatureStatus(d), JSON.stringify(near)).toEqual({ kind: 'UNSTAMPED' })
            expect(line(d), JSON.stringify(near)).toBeNull()
        }
    })

    it('property keys are name and value exactly: Name/Value entries are not a stamp', () => {
        const d = doc({ Name: PROP_SIGNED, Value: 'true' }, { NAME: PROP_SIGNED, VALUE: 'true' },
            prop(PROP_SIGNED, 'false'), prop(PROP_UNSIGNED_REASON, 'NO_KEY'))
        expect(signatureStatus(d)).toEqual({ kind: 'UNSIGNED', reason: 'NO_KEY' })
        expect(line(d)).toBe(NO_KEY_LINE)
    })

    it('a near-name unsignedReason is not the reason: the exact one is, or none', () => {
        for (const near of nearNames(PROP_UNSIGNED_REASON)) {
            const ahead = doc(prop(PROP_SIGNED, 'false'), prop(near, 'SIGNING_ERROR'), prop(PROP_UNSIGNED_REASON, 'NO_KEY'))
            expect(signatureStatus(ahead), JSON.stringify(near)).toEqual({ kind: 'UNSIGNED', reason: 'NO_KEY' })
            expect(line(ahead), JSON.stringify(near)).toBe(NO_KEY_LINE)
            const alone = doc(prop(PROP_SIGNED, 'false'), prop(near, 'NO_KEY'))
            expect(signatureStatus(alone), JSON.stringify(near)).toEqual({ kind: 'UNSIGNED', reason: '' })
            expect(line(alone), JSON.stringify(near)).toBe('Not signed')
        }
    })

    it('a near-name signatureKeyId is not the key id: the exact one is, or <unknown>', () => {
        for (const near of nearNames(PROP_SIGNATURE_KEY_ID)) {
            const ahead = doc(prop(PROP_SIGNED, 'true'), prop(near, FORGED_KID), prop(PROP_SIGNATURE_KEY_ID, KEY_ID))
            expect(signatureStatus(ahead), JSON.stringify(near)).toEqual({ kind: 'SIGNED', keyId: KEY_ID })
            expect(line(ahead), JSON.stringify(near)).toBe(`Signed with organization key ${KEY_ID}`)
            const alone = doc(prop(PROP_SIGNED, 'true'), prop(near, FORGED_KID))
            expect(signatureStatus(alone), JSON.stringify(near)).toEqual({ kind: 'SIGNED', keyId: '' })
            expect(line(alone), JSON.stringify(near)).toBe('Signed with organization key <unknown>')
        }
    })

    it('only the exact value "true" says signed: case and blank variants read not signed', () => {
        for (const value of ['TRUE', 'True', ' true', 'true ', '\ttrue', 'true\n', 'true\u00a0', 'yes', '1', 'truex']) {
            const d = doc(prop(PROP_SIGNED, value), prop(PROP_SIGNATURE_KEY_ID, FORGED_KID))
            expect(signatureStatus(d), JSON.stringify(value)).toEqual({ kind: 'UNSIGNED', reason: '' })
            expect(line(d), JSON.stringify(value)).toBe('Not signed')
        }
        for (const value of [true, 1, ['true'], { value: 'true' }, null]) {
            const d = doc(prop(PROP_SIGNED, value), prop(PROP_SIGNATURE_KEY_ID, FORGED_KID))
            expect(signatureStatus(d), JSON.stringify(value)).toEqual({ kind: 'UNSIGNED', reason: '' })
        }
    })

    it('the reason is printed as written: a blank or case variant of NO_KEY is not the NO_KEY line', () => {
        expect(line(doc(prop(PROP_SIGNED, 'false'), prop(PROP_UNSIGNED_REASON, ' NO_KEY')))).toBe('Not signed:  NO_KEY')
        expect(line(doc(prop(PROP_SIGNED, 'false'), prop(PROP_UNSIGNED_REASON, 'no_key')))).toBe('Not signed: no_key')
    })

    it('first occurrence wins in both orders, for the stamp, the key id and the reason', () => {
        // unsigned first, signed later: a later true never overrides the first stamp
        const falseFirst = doc(prop(PROP_SIGNED, 'false'), prop(PROP_UNSIGNED_REASON, 'NO_KEY'),
            prop(PROP_SIGNED, 'true'), prop(PROP_SIGNATURE_KEY_ID, FORGED_KID))
        expect(signatureStatus(falseFirst)).toEqual({ kind: 'UNSIGNED', reason: 'NO_KEY' })
        expect(line(falseFirst)).toBe(NO_KEY_LINE)
        expect(signatureStatus(doc(prop(PROP_SIGNED, 'true'), prop(PROP_SIGNATURE_KEY_ID, KEY_ID), prop(PROP_SIGNATURE_KEY_ID, FORGED_KID))))
            .toEqual({ kind: 'SIGNED', keyId: KEY_ID })
        expect(signatureStatus(doc(prop(PROP_SIGNED, 'false'), prop(PROP_UNSIGNED_REASON, 'NO_KEY'), prop(PROP_UNSIGNED_REASON, 'SIGNING_ERROR'))))
            .toEqual({ kind: 'UNSIGNED', reason: 'NO_KEY' })
    })

    it('a property without a string name is skipped, not taken as the first occurrence', () => {
        const d = doc(null, 'reliza:export:signed', { value: 'true' }, { name: null, value: 'true' }, { name: ['reliza:export:signed'], value: 'true' },
            prop(PROP_SIGNED, 'false'), prop(PROP_UNSIGNED_REASON, 'NO_KEY'))
        expect(signatureStatus(d)).toEqual({ kind: 'UNSIGNED', reason: 'NO_KEY' })
        expect(line(d)).toBe(NO_KEY_LINE)
    })
})

describe('exportSigningLine', () => {
    const NOTE = 'Signing applies to CycloneDX JSON exports only; this file carries no signature.'
    const ALL: SignedStatus[] = [
        { kind: 'SIGNED', keyId: KEY_ID }, { kind: 'UNSIGNED', reason: 'NO_KEY' }, { kind: 'UNSIGNED', reason: 'SIGNING_ERROR' },
        { kind: 'UNSIGNED', reason: 'OTHER' }, { kind: 'UNSIGNED', reason: '' }, { kind: 'UNSTAMPED' }, { kind: 'UNREADABLE' },
    ]

    it('signed: names the full key id', () => {
        expect(exportSigningLine({ kind: 'SIGNED', keyId: KEY_ID }, 'JSON')).toBe(`Signed with organization key ${KEY_ID}`)
        // A signed stamp without its key id reads as rearm exportreleasebom prints it.
        expect(exportSigningLine({ kind: 'SIGNED', keyId: '' }, 'JSON')).toBe('Signed with organization key <unknown>')
    })

    it('not signed for want of a key: points at the settings section', () => {
        expect(exportSigningLine({ kind: 'UNSIGNED', reason: 'NO_KEY' }, 'JSON'))
            .toBe('Not signed: no organization SBOM signing key is configured (Organization settings, SBOM export signing)')
    })

    it('not signed because signing failed', () => {
        expect(exportSigningLine({ kind: 'UNSIGNED', reason: 'SIGNING_ERROR' }, 'JSON'))
            .toBe('Not signed: signing failed on the server (SIGNING_ERROR)')
    })

    it('not signed for another reason: the reason as written, or nothing after an empty one', () => {
        expect(exportSigningLine({ kind: 'UNSIGNED', reason: 'NOT_CANONICALIZABLE' }, 'JSON')).toBe('Not signed: NOT_CANONICALIZABLE')
        expect(exportSigningLine({ kind: 'UNSIGNED', reason: '' }, 'JSON')).toBe('Not signed')
    })

    it('no line for an unstamped or unreadable JSON document (an older server)', () => {
        expect(exportSigningLine({ kind: 'UNSTAMPED' }, 'JSON')).toBeNull()
        expect(exportSigningLine({ kind: 'UNREADABLE' }, 'JSON')).toBeNull()
    })

    it('CSV and EXCEL: the JSON-only note, whatever the status', () => {
        for (const status of ALL) {
            expect(exportSigningLine(status, 'CSV')).toBe(NOTE)
            expect(exportSigningLine(status, 'EXCEL')).toBe(NOTE)
        }
    })

    it('reads each fixture into its line', () => {
        const line = (name: string) => exportSigningLine(signatureStatus(signingFixture(name)), 'JSON')
        expect(line('signed-ed25519.cdx.json')).toBe(`Signed with organization key ${KEY_ID}`)
        expect(line('unsigned.cdx.json')).toMatch(/^Not signed: no organization SBOM signing key/)
        expect(line('signing-error.cdx.json')).toBe('Not signed: signing failed on the server (SIGNING_ERROR)')
        expect(line('unstamped.cdx.json')).toBeNull()
        expect(line('not-json.txt')).toBeNull()
    })
})

describe('scoreSignatureLine', () => {
    const sig = (over: Partial<SignatureReportInput>): SignatureReportInput => ({
        present: true, wellFormed: true, algorithm: 'Ed25519', keyId: KEY_ID, verification: 'NOT_REQUESTED', detail: '', ...over,
    })

    it('no line without input.signature', () => {
        expect(scoreSignatureLine(undefined)).toBeNull()
    })

    it('none', () => {
        expect(scoreSignatureLine(sig({ present: false, wellFormed: false, algorithm: '', keyId: '', verification: 'FAILED', detail: 'no signature' })))
            .toBe('Signature: none')
    })

    it('present but malformed, with and without detail', () => {
        expect(scoreSignatureLine(sig({ wellFormed: false, verification: 'FAILED', detail: 'value missing' })))
            .toBe('Signature: present but malformed: value missing')
        expect(scoreSignatureLine(sig({ wellFormed: false, verification: 'FAILED' }))).toBe('Signature: present but malformed')
    })

    it('NOT_REQUESTED, with and without a key id', () => {
        expect(scoreSignatureLine(sig({}))).toBe(`Signature: Ed25519, key ${KEY_ID}, present, not verified (no key given)`)
        expect(scoreSignatureLine(sig({ keyId: '' }))).toBe('Signature: Ed25519, present, not verified (no key given)')
    })

    it('VERIFIED, with and without a key id', () => {
        expect(scoreSignatureLine(sig({ verification: 'VERIFIED', detail: 'verified with key x' }))).toBe(`Signature: Ed25519, key ${KEY_ID}, verified`)
        expect(scoreSignatureLine(sig({ verification: 'VERIFIED', keyId: '' }))).toBe('Signature: Ed25519, verified')
    })

    it('every other verification value, with and without detail, an unknown one printed as it is', () => {
        for (const v of ['FAILED', 'KEY_MISMATCH', 'KEY_REVOKED', 'UNSUPPORTED_ALGORITHM', 'UNSUPPORTED_FORM', 'NO_KEY', 'SOMETHING_NEW']) {
            expect(scoreSignatureLine(sig({ verification: v, detail: 'why' }))).toBe(`Signature: Ed25519, key ${KEY_ID}, ${v}: why`)
            expect(scoreSignatureLine(sig({ verification: v }))).toBe(`Signature: Ed25519, key ${KEY_ID}, ${v}`)
            expect(scoreSignatureLine(sig({ verification: v, keyId: '' }))).toBe(`Signature: Ed25519, ${v}`)
        }
    })
})
