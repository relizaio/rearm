import { describe, it, expect } from 'vitest'
import { PACKAGE_EXPORT_REQUIRED, retentionRefusal } from './packageRetention'

/**
 * FDAW-12 4.1 row 8 (REQ-314): the retention refusal is found by its code in any of the three
 * shapes a failed call takes, and only by its code.
 */
const SENTENCE = 'Product GlucoView cannot be archived: 1 package version has no recorded zip download that lists all '
    + 'its acts. Download the zip of each version on its release page (Regulatory tab), then archive again.'
const entry = (code: string | undefined, message: unknown) => ({ message, extensions: { code, count: 1 } })

describe('retentionRefusal', () => {
    it('reads Apollo 4 errors, Apollo 3 graphQLErrors and a network error result', () => {
        expect(retentionRefusal({ errors: [entry(PACKAGE_EXPORT_REQUIRED, SENTENCE)] })).toBe(SENTENCE)
        expect(retentionRefusal({ graphQLErrors: [entry(PACKAGE_EXPORT_REQUIRED, SENTENCE)] })).toBe(SENTENCE)
        expect(retentionRefusal({ networkError: { result: { errors: [entry(PACKAGE_EXPORT_REQUIRED, SENTENCE)] } } }))
            .toBe(SENTENCE)
    })

    it('takes the first entry with the exact code and a non-empty message', () => {
        expect(retentionRefusal({ errors: [entry('OTHER', 'x'), entry(PACKAGE_EXPORT_REQUIRED, SENTENCE)] }))
            .toBe(SENTENCE)
    })

    it('is null for an empty message, another code, the code in lower case, or the sentence without the code', () => {
        expect(retentionRefusal({ errors: [entry(PACKAGE_EXPORT_REQUIRED, '')] })).toBeNull()
        expect(retentionRefusal({ errors: [entry(PACKAGE_EXPORT_REQUIRED, 42)] })).toBeNull()
        expect(retentionRefusal({ errors: [entry('BAD_REQUEST', SENTENCE)] })).toBeNull()
        expect(retentionRefusal({ errors: [entry(PACKAGE_EXPORT_REQUIRED.toLowerCase(), SENTENCE)] })).toBeNull()
        expect(retentionRefusal({ errors: [entry(undefined, SENTENCE)] })).toBeNull()
        expect(retentionRefusal({ message: SENTENCE })).toBeNull()
        expect(retentionRefusal(null)).toBeNull()
        expect(retentionRefusal(undefined)).toBeNull()
    })

    it('returns markup unchanged, as a string', () => {
        const markup = '<img src=x onerror=alert(1)>'
        const r = retentionRefusal({ errors: [entry(PACKAGE_EXPORT_REQUIRED, markup)] })
        expect(typeof r).toBe('string')
        expect(r).toBe(markup)
    })
})
