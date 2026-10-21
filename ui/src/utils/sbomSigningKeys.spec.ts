import { describe, it, expect } from 'vitest'
import {
    IMPORT_HINT, REVOKE_CONFIRM, ROTATE_CONFIRM, dateCell, generateLabel, isSbomSigningUnsupported, jwksUrl, splitKeys,
    type SbomSigningKey,
} from './sbomSigningKeys'

// T-27 (SCORE-35, design 3.3 and 3.7): the pure half of the "SBOM export signing" section.
const key = (uuid: string, status: SbomSigningKey['status']): SbomSigningKey => ({
    uuid, keyId: uuid, algorithm: 'Ed25519', status, source: 'GENERATED', publicKeyJwk: null, publicKeyPem: null,
    createdDate: '2026-10-01T10:00:00Z', retiredAt: null, revokedAt: null,
})

describe('splitKeys', () => {
    it('takes the CURRENT key out and keeps the others in the order served', () => {
        const keys = [key('c', 'CURRENT'), key('r2', 'RETIRED'), key('x', 'REVOKED'), key('r1', 'RETIRED')]
        const { current, others } = splitKeys(keys)
        expect(current?.uuid).toBe('c')
        expect(others.map(k => k.uuid)).toEqual(['r2', 'x', 'r1'])
    })

    it('has no current key without a CURRENT one, and an empty list for nothing served', () => {
        expect(splitKeys([key('r', 'RETIRED'), key('x', 'REVOKED')])).toEqual({ current: null, others: [key('r', 'RETIRED'), key('x', 'REVOKED')] })
        expect(splitKeys(null)).toEqual({ current: null, others: [] })
        expect(splitKeys([])).toEqual({ current: null, others: [] })
    })
})

describe('jwksUrl', () => {
    it('is the public endpoint of the organization on the page origin', () => {
        expect(jwksUrl('https://rearm.example', '11111111-2222-3333-4444-555555555555'))
            .toBe('https://rearm.example/api/public/v1/organization/11111111-2222-3333-4444-555555555555/sbom-signing-keys')
    })
})

describe('dateCell', () => {
    it('formats an ISO instant as the en-CA local string, and nothing as empty', () => {
        expect(dateCell('2026-10-01T10:00:00Z')).toBe(new Date('2026-10-01T10:00:00Z').toLocaleString('en-CA'))
        expect(dateCell(null)).toBe('')
        expect(dateCell(undefined)).toBe('')
    })
})

describe('generateLabel', () => {
    it('reads Rotate when a current key exists', () => {
        expect(generateLabel(true)).toBe('Rotate key')
        expect(generateLabel(false)).toBe('Generate key')
    })
})

describe('the confirm and hint texts', () => {
    it('are the design texts', () => {
        expect(ROTATE_CONFIRM).toEqual({
            title: 'Rotate the SBOM signing key?',
            text: 'The current key is retired and new exports sign with the new key. Retired keys stay listed under earlier keys, so documents signed with them can still be verified with them.',
            confirmButtonText: 'Rotate',
        })
        expect(REVOKE_CONFIRM).toEqual({
            title: 'Revoke this SBOM signing key?',
            text: 'Verification of documents signed with this key will report KEY_REVOKED. If it is the current key, exports are unsigned until a new key exists.',
            confirmButtonText: 'Revoke',
        })
        expect(IMPORT_HINT).toBe('An Ed25519 private key as PKCS#8 PEM (BEGIN PRIVATE KEY). The key is encrypted at rest with this installation\'s encryption settings.')
    })

    it('name what a consumer sees: KEY_REVOKED, retired, PKCS#8', () => {
        expect(REVOKE_CONFIRM.text).toContain('KEY_REVOKED')
        expect(ROTATE_CONFIRM.text).toContain('retired')
        expect(IMPORT_HINT).toContain('PKCS#8')
    })
})

describe('isSbomSigningUnsupported', () => {
    it('is the schema-drift classifier: a validation refusal or a bare 400, not a 500', () => {
        expect(isSbomSigningUnsupported(new Error('Cannot query field "sbomSigningKeys" on type "Query".'))).toBe(true)
        expect(isSbomSigningUnsupported(Object.assign(new Error('Response not successful: Received status code 400'), { statusCode: 400 }))).toBe(true)
        expect(isSbomSigningUnsupported(Object.assign(new Error('Response not successful: Received status code 500'), { statusCode: 500 }))).toBe(false)
        expect(isSbomSigningUnsupported(new Error('Not authorized'))).toBe(false)
    })
})
