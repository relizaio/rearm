import { describe, it, expect } from 'vitest'
import { deviceWindowVariables } from './deviceSupportWindowInput'

const U = '11111111-1111-1111-1111-111111111111'
const O = '22222222-2222-2222-2222-222222222222'
const none = { eos: null, eol: null }

describe('deviceWindowVariables', () => {
    // THE load-bearing assertion. updateRelease treats a null list as "no change", so a
    // payload that grew an artifacts/commits key -- even an empty one -- could detach a
    // release's contents. Assert the exact key set, not just the presence of the dates.
    it('sends ONLY uuid, org and the changed dates -- never a dead field', () => {
        const vars = deviceWindowVariables(U, O, { eos: '2030-06-30', eol: null }, none)
        expect(Object.keys(vars).sort()).toEqual(['eos', 'org', 'uuid'])
    })

    // org is ID! on ReleaseInput: a { uuid, eos } payload is rejected at GraphQL
    // validation before the resolver is ever reached.
    it('always carries org, which the schema requires even on the narrowest partial', () => {
        expect(deviceWindowVariables(U, O, none, none)).toEqual({ uuid: U, org: O })
    })

    it('sends nothing but the identifiers when neither date changed', () => {
        const same = { eos: '2030-06-30', eol: '2033-01-01' }
        expect(deviceWindowVariables(U, O, same, same)).toEqual({ uuid: U, org: O })
    })

    it('sends both dates when both are newly set', () => {
        expect(deviceWindowVariables(U, O, { eos: '2030-06-30', eol: '2033-01-01' }, none))
            .toEqual({ uuid: U, org: O, eos: '2030-06-30', eol: '2033-01-01' })
    })

    // null means "keep" on this path, so clearing needs an explicit flag and nothing else
    // can express it.
    it('clears EOS alone with clearEos, leaving EOL untouched', () => {
        expect(deviceWindowVariables(U, O, { eos: null, eol: '2033-01-01' },
            { eos: '2030-06-30', eol: '2033-01-01' }))
            .toEqual({ uuid: U, org: O, clearEos: true })
    })

    // The mirror of the above. This direction was an inference rather than an observation
    // until the live probe exercised it; the asymmetry is exactly where a bug would hide.
    it('clears EOL alone with clearEol, leaving EOS untouched', () => {
        expect(deviceWindowVariables(U, O, { eos: '2030-06-30', eol: null },
            { eos: '2030-06-30', eol: '2033-01-01' }))
            .toEqual({ uuid: U, org: O, clearEol: true })
    })

    it('clears both at once', () => {
        expect(deviceWindowVariables(U, O, none, { eos: '2030-06-30', eol: '2033-01-01' }))
            .toEqual({ uuid: U, org: O, clearEos: true, clearEol: true })
    })

    it('never emits a clear flag for a date that was not set to begin with', () => {
        const vars = deviceWindowVariables(U, O, none, { eos: null, eol: '2033-01-01' })
        expect(vars).toEqual({ uuid: U, org: O, clearEol: true })
        expect(vars).not.toHaveProperty('clearEos')
    })

    it('sends a replacement date rather than a clear when overwriting', () => {
        expect(deviceWindowVariables(U, O, { eos: '2031-01-01', eol: null },
            { eos: '2030-06-30', eol: null }))
            .toEqual({ uuid: U, org: O, eos: '2031-01-01' })
    })

    // A coherence violation is the SERVER's call: sending it is correct, so the operator
    // gets the enforced message rather than a second one invented here.
    it('sends an incoherent pair rather than pre-validating it away', () => {
        expect(deviceWindowVariables(U, O, { eos: '2035-01-01', eol: '2030-01-01' }, none))
            .toEqual({ uuid: U, org: O, eos: '2035-01-01', eol: '2030-01-01' })
    })
})
