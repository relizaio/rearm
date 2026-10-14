import { describe, it, expect } from 'vitest'
import {
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
