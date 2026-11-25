// T-500 (TPF-185 design 3.2 and 3.5): the edition gate of the upstream sources feature, the uuid
// test for values put into a route, the reading of a refused request and the forbidden rule.
import { describe, expect, it } from 'vitest'
import { isForbidden, isUuid, refusalOf, upstreamSourcesAvailable } from './upstream'
import { networkSentence } from './upstreamLabels'

const UUID = '3f9a1c2e-5b6d-4e7f-8a9b-0c1d2e3f4a5b'

function apolloError (entries: any[]): any {
    const err: any = new Error(entries.map(e => e.message).join(', '))
    err.errors = entries
    return err
}

const NULL_PROPAGATION = { message: 'The field at path \'/upstreamSource\' was declared as a non null type, but the code involved in retrieving data has wrongly returned a null value.', extensions: { classification: 'NullValueInNonNullableField' } }
const TYPED = { message: 'An upstream source of the organization already has this root.', extensions: { code: 'UPSTREAM_SOURCE_REFUSED', reason: 'DUPLICATE_ROOT', classification: 'DataFetchingException' } }

describe('upstreamSourcesAvailable', () => {
    it.each(['SAAS', 'MANAGED_SERVICE', 'DEMO'])('is true for %s', (t) => {
        expect(upstreamSourcesAvailable(t)).toBe(true)
    })

    it.each(['OSS', '', undefined, null])('is false for %s', (t) => {
        expect(upstreamSourcesAvailable(t as any)).toBe(false)
    })
})

describe('isUuid', () => {
    it('acceptsALowerAndAnUpperCaseUuid', () => {
        expect(isUuid(UUID)).toBe(true)
        expect(isUuid(UUID.toUpperCase())).toBe(true)
    })

    it.each([
        ['the nil uuid', '00000000-0000-0000-0000-000000000000'],
        ['a trailing path', UUID + '/x'],
        ['a relative path', '../x'],
        ['an empty string', ''],
    ])('refuses %s', (_what, text) => {
        expect(isUuid(text)).toBe(false)
    })
})

describe('refusalOf and isForbidden', () => {
    it('takesTheTypedEntryAfterTheNullPropagationEntry', () => {
        const r = refusalOf(apolloError([NULL_PROPAGATION, TYPED]))
        expect(r).toEqual({ text: TYPED.message, code: 'UPSTREAM_SOURCE_REFUSED', reason: 'DUPLICATE_ROOT' })
        expect(isForbidden(r)).toBe(false)
    })

    it('readsAPlainNotAuthorizedAsForbidden', () => {
        const r = refusalOf(apolloError([{ message: 'Not authorized' }]))
        expect(r).toEqual({ text: 'Not authorized', code: null, reason: null })
        expect(isForbidden(r)).toBe(true)
    })

    it('givesTheNetworkSentenceWithoutGraphqlEntries', () => {
        const r = refusalOf(new Error('Failed to fetch'))
        expect(r).toEqual({ text: networkSentence, code: null, reason: null })
        expect(networkSentence).toBe('The request did not reach the server. Try again.')
        expect(isForbidden(r)).toBe(false)
    })

    it('doesNotReadNotAuthorizedWithACodeAsForbidden', () => {
        const r = refusalOf(apolloError([{ message: 'Not authorized', extensions: { code: 'UPSTREAM_SOURCE_REFUSED' } }]))
        expect(r.code).toBe('UPSTREAM_SOURCE_REFUSED')
        expect(isForbidden(r)).toBe(false)
    })

    it('doesNotReadAnotherSentenceAsForbidden', () => {
        const r = refusalOf(apolloError([{ message: 'System is sealed. Please unseal the system first.' }]))
        expect(r.text).toBe('System is sealed. Please unseal the system first.')
        expect(isForbidden(r)).toBe(false)
    })

    it('readsTheEntriesOfANetworkErrorResult', () => {
        const err: any = new Error('Response not successful')
        err.networkError = { result: { errors: [{ message: 'Not authorized' }] } }
        expect(isForbidden(refusalOf(err))).toBe(true)
    })
})
