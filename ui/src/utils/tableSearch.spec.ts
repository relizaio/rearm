import { describe, it, expect } from 'vitest'
import { looksLikeUuidPrefix, normalizeSearchTerm, textMatches, uuidMatches, UUID_PREFIX_MIN } from './tableSearch'

/** TPF-88 design 4.1, rows 1-4 (landed by TPF-85, first of the two to merge). */
const A = '0dfce17a-fc79-4245-937d-eff5213fdfcb'

describe('tableSearch: normalizeSearchTerm', () => {
    it('trims and lower-cases; empty, blank, undefined and null give the empty term', () => {
        expect(normalizeSearchTerm('  AbC ')).toBe('abc')
        expect(normalizeSearchTerm('')).toBe('')
        expect(normalizeSearchTerm('   ')).toBe('')
        expect(normalizeSearchTerm(undefined)).toBe('')
        expect(normalizeSearchTerm(null)).toBe('')
    })
})

describe('tableSearch: looksLikeUuidPrefix', () => {
    it('accepts hex digits and dashes from UUID_PREFIX_MIN characters, case-exact', () => {
        expect(UUID_PREFIX_MIN).toBe(4)
        expect(looksLikeUuidPrefix('0dfc')).toBe(true)
        expect(looksLikeUuidPrefix('0DFC')).toBe(false)
        expect(looksLikeUuidPrefix('abcd-')).toBe(true)
        expect(looksLikeUuidPrefix('0df')).toBe(false)
        expect(looksLikeUuidPrefix('0dfg')).toBe(false)
        expect(looksLikeUuidPrefix(A)).toBe(true)
    })
})

describe('tableSearch: uuidMatches', () => {
    it('matches the whole uuid or a prefix, never an inner part, a short term or an unnormalized one', () => {
        expect(uuidMatches(A, '0dfce17a-fc79-4245-937d-eff5213fdfcb')).toBe(true)
        expect(uuidMatches(A, '0dfc')).toBe(true)
        expect(uuidMatches(A, 'fc79')).toBe(false)
        expect(uuidMatches(A, '0df')).toBe(false)
        expect(uuidMatches(null, '0dfc')).toBe(false)
        expect(uuidMatches(A, '0DFC')).toBe(false)
    })
})

describe('tableSearch: textMatches', () => {
    it('is a case-insensitive substring over the texts, null and undefined read as empty', () => {
        expect(textMatches(['Nightly BUILD', null, undefined], 'build')).toBe(true)
        expect(textMatches(['abc'], 'abd')).toBe(false)
        expect(() => textMatches([null, undefined], 'a')).not.toThrow()
        expect(textMatches([null, undefined], 'a')).toBe(false)
    })
})
