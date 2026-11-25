import { describe, expect, it } from 'vitest'
import { MAX_TEI_LENGTH, teiOf, teiProblem } from './upstreamTei'

// T-560 (TPF-185 design 3.10): the TEI rule in the browser is the backend's rule, with the
// backend's sentence.
const SENTENCE = 'The TEI must be 1 to 2048 printable ASCII characters without spaces, starting with tei://.'
const VALID = 'tei://example.com/product/1.0'

function ofLength (n: number): string {
    return 'tei://' + 'a'.repeat(n - 'tei://'.length)
}

describe('teiProblem', () => {
    it('acceptsATei', () => {
        expect(teiProblem(VALID)).toBeNull()
    })

    it('acceptsATeiWithASpaceOnEachSideAndSendsItTrimmed', () => {
        expect(teiProblem(' ' + VALID + ' ')).toBeNull()
        expect(teiOf(' ' + VALID + ' ')).toBe(VALID)
    })

    it('acceptsATeiOf2048Characters', () => {
        expect(ofLength(2048)).toHaveLength(MAX_TEI_LENGTH)
        expect(teiProblem(ofLength(2048))).toBeNull()
    })

    it.each([
        ['an empty string', ''],
        ['spaces only', '   '],
        ['a urn', 'urn:tei:uuid:example.com:1'],
        ['an https URL', 'https://example.com/x'],
        ['a space inside', 'tei://example.com/product 1.0'],
        ['a tab inside', 'tei://example.com/product\t1.0'],
        ['a non-ASCII letter', 'tei://example.com/produ\u00e7t/1.0'],
        ['2049 characters', ofLength(2049)],
        ['the prefix in upper case', 'TEI://example.com/product/1.0'],
        ['a line break inside', 'tei://example.com/\nproduct'],
        ['null', null],
        ['undefined', undefined],
    ])('refuses %s with the sentence of the rule', (_what, text) => {
        expect(teiProblem(text as any)).toBe(SENTENCE)
    })
})
