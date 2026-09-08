import { describe, it, expect } from 'vitest'
import { narrativeLength, formatNarrativeChange } from './narrativeHistory'

describe('narrativeLength', () => {
    it('is the string length when the value was stored whole', () => {
        expect(narrativeLength('short narrative')).toBe(15)
    })

    // THE REGRESSION THIS EXISTS FOR: the event carries at most ~200 characters, so the
    // excerpt's own length is meaningless for anything longer. Reporting it would tell an
    // auditor a 9,000-character justification was 218 characters.
    it('reads the TRUE length out of the excerpt marker, not the excerpt', () => {
        const excerpt = 'w'.repeat(200) + '... (9000 characters)'
        expect(excerpt.length).not.toBe(9000)
        expect(narrativeLength(excerpt)).toBe(9000)
    })

    it('treats absent values as zero', () => {
        expect(narrativeLength(null)).toBe(0)
        expect(narrativeLength(undefined)).toBe(0)
        expect(narrativeLength('')).toBe(0)
    })

    // The marker is only meaningful at the END. Narrative prose could legitimately contain
    // a parenthetical that looks like it.
    it('ignores a marker-shaped phrase that is not the trailing marker', () => {
        expect(narrativeLength('we counted ... (42 characters) and then continued'))
            .toBe('we counted ... (42 characters) and then continued'.length)
    })
})

describe('formatNarrativeChange', () => {
    it('reports a first narrative as set, with its length', () => {
        expect(formatNarrativeChange(null, 'x'.repeat(1240)))
            .toBe('narrative set (1,240 characters)')
    })

    it('reports a replacement as changed, with both lengths', () => {
        expect(formatNarrativeChange('x'.repeat(1240), 'y'.repeat(890)))
            .toBe('narrative changed (1,240 characters -> 890 characters)')
    })

    // "cleared" and not "removed": clearing the override returns the release to INHERITING
    // the org default, so the document keeps its justification section. An auditor reading
    // "removed" would conclude the opposite.
    it('says a clear returns the release to the org default', () => {
        expect(formatNarrativeChange('x'.repeat(1240), null))
            .toBe('narrative cleared, release returns to the org default (was 1,240 characters)')
    })

    it('uses the true lengths when the event carried excerpts', () => {
        const oldEx = 'a'.repeat(200) + '... (5000 characters)'
        const newEx = 'b'.repeat(200) + '... (7500 characters)'
        expect(formatNarrativeChange(oldEx, newEx))
            .toBe('narrative changed (5,000 characters -> 7,500 characters)')
    })

    // Never the text: the event holds an excerpt that stops mid-word, and the history table
    // is a dense list where paragraphs of prose make every surrounding row unreadable.
    it('never renders the narrative text itself', () => {
        const secret = 'the manufacturer states that components were assessed by hand'
        expect(formatNarrativeChange(null, secret)).not.toContain('manufacturer')
        expect(formatNarrativeChange(secret, null)).not.toContain('manufacturer')
        expect(formatNarrativeChange(secret, secret + '!')).not.toContain('manufacturer')
    })

    it('singularises a one-character narrative', () => {
        expect(formatNarrativeChange(null, 'x')).toBe('narrative set (1 character)')
    })

    it('falls back to a neutral phrase rather than rendering a blank cell', () => {
        expect(formatNarrativeChange(null, null)).toBe('narrative unchanged')
    })
})
