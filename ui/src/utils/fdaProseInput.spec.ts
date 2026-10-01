import { describe, it, expect } from 'vitest'
import { proseDiff, proseBaselineFrom, FDA_PROSE_FIELDS } from './fdaProseInput'

describe('proseDiff', () => {
    const empty = { fdaAssessmentNarrative: '', fdaPatchesMayCeaseStatement: '',
        fdaRiskTransferProcessRef: '', fdaRiskIncreasesNotice: '' }

    it('omits every field when nothing changed, so an unrelated toggle cannot touch prose', () => {
        const state = { ...empty, fdaAssessmentNarrative: 'DHF-1' }
        expect(proseDiff(state, state)).toEqual({})
    })

    it('sends a changed field trimmed', () => {
        expect(proseDiff({ ...empty, fdaAssessmentNarrative: '  new text  ' }, empty))
            .toEqual({ fdaAssessmentNarrative: 'new text' })
    })

    // The whole point of the baseline. '' is a deliberate CLEAR on the wire; the server
    // reads a supplied empty string as "null this out" and an omitted key as "leave alone".
    it('sends emptied-from-text as an empty string, which the server reads as a clear', () => {
        expect(proseDiff(empty, { ...empty, fdaAssessmentNarrative: 'DHF-1' }))
            .toEqual({ fdaAssessmentNarrative: '' })
    })

    it('does NOT send a clear for a field that was already empty', () => {
        expect(proseDiff(empty, empty)).toEqual({})
    })

    // A field the server has never had set arrives as null, not ''. Treating those as
    // different would send a spurious clear on every save of an untouched form.
    it('treats null-on-server and empty-string-on-server as the same baseline', () => {
        const nulls = { fdaAssessmentNarrative: null, fdaPatchesMayCeaseStatement: null,
            fdaRiskTransferProcessRef: null, fdaRiskIncreasesNotice: null }
        expect(proseDiff(empty, nulls)).toEqual({})
        expect(proseDiff(nulls, empty)).toEqual({})
    })

    it('treats whitespace-only input as no change against an empty baseline', () => {
        expect(proseDiff({ ...empty, fdaAssessmentNarrative: '   ' }, empty)).toEqual({})
    })

    it('treats replacing text with whitespace as a clear', () => {
        expect(proseDiff({ ...empty, fdaAssessmentNarrative: '   ' },
            { ...empty, fdaAssessmentNarrative: 'DHF-1' }))
            .toEqual({ fdaAssessmentNarrative: '' })
    })

    it('does not send a field whose only change is surrounding whitespace', () => {
        expect(proseDiff({ ...empty, fdaAssessmentNarrative: '  DHF-1  ' },
            { ...empty, fdaAssessmentNarrative: 'DHF-1' })).toEqual({})
    })

    it('sends ONLY the changed field when several are populated', () => {
        const baseline = { fdaAssessmentNarrative: 'A', fdaPatchesMayCeaseStatement: 'B',
            fdaRiskTransferProcessRef: 'C', fdaRiskIncreasesNotice: 'D' }
        expect(proseDiff({ ...baseline, fdaRiskTransferProcessRef: 'C2' }, baseline))
            .toEqual({ fdaRiskTransferProcessRef: 'C2' })
    })

    it('never emits a key outside the four declared fields', () => {
        const withJunk: any = { ...empty, fdaAssessmentNarrative: 'x', somethingElse: 'y' }
        expect(Object.keys(proseDiff(withJunk, empty))).toEqual(['fdaAssessmentNarrative'])
    })
})

describe('proseBaselineFrom', () => {
    // REGRESSION: the baseline used to be refreshed only by a re-read nested inside the
    // save's try block. If that read failed, a COMMITTED save was reported as "Save Failed"
    // and the baseline stayed stale -- so the operator's next clear compared '' against a
    // stale '', was omitted, and reported success while the server still held the text.
    it('reads the baseline from the mutation response, including nulls as empty strings', () => {
        expect(proseBaselineFrom({ fdaAssessmentNarrative: 'saved text' }))
            .toEqual({ fdaAssessmentNarrative: 'saved text', fdaPatchesMayCeaseStatement: '',
                fdaRiskTransferProcessRef: '', fdaRiskIncreasesNotice: '' })
    })

    it('yields an all-empty baseline for a null settings object', () => {
        const b = proseBaselineFrom(null)
        expect(Object.keys(b).sort()).toEqual([...FDA_PROSE_FIELDS].sort())
        expect(Object.values(b).every(v => v === '')).toBe(true)
    })

    // Round trip: a baseline taken from the response must make an unmodified form clean.
    it('leaves the form clean immediately after a save', () => {
        const resp = { fdaAssessmentNarrative: 'saved text' }
        const baseline = proseBaselineFrom(resp)
        expect(proseDiff(baseline, baseline)).toEqual({})
    })
})
