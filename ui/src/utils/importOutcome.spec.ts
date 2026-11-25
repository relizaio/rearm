import { describe, expect, it } from 'vitest'
import { DEMOTION_TEXT, importOutcomeLines } from './importOutcome'

describe('importOutcomeLines', () => {
    it('gives one line per demotion reason, in the order sent, then the ignored override', () => {
        expect(importOutcomeLines({
            demotions: [{ reason: 'SAME_SCOPE_CONFLICT', count: 1 }, { reason: 'SEVERITY_CHANGE', count: 4 }],
            overrideOutcome: 'IGNORED'
        })).toEqual([
            '1 staged: an existing analysis at this scope says otherwise',
            '4 staged: the statement changes the severity',
            'Issuer override not applied'
        ])
    })

    it('has a text for every reason the schema names', () => {
        expect(Object.keys(DEMOTION_TEXT).sort()).toEqual(
            ['BROADER_SCOPE_CONFLICT', 'NO_FINDING_ANALYSIS_WRITE', 'SAME_SCOPE_CONFLICT', 'SEVERITY_CHANGE', 'SEVERITY_MISSING'])
    })

    it.each(['NONE', 'UNCHANGED', 'LOWERED', 'RAISED', null, undefined])('says nothing about an override outcome %s', (overrideOutcome) => {
        expect(importOutcomeLines({ demotions: [], overrideOutcome })).toEqual([])
    })

    it('names a reason it does not know rather than dropping it, and skips zero counts', () => {
        expect(importOutcomeLines({ demotions: [{ reason: 'NEW_REASON', count: 2 }, { reason: 'SEVERITY_MISSING', count: 0 }] }))
            .toEqual(['2 staged: NEW_REASON'])
    })

    it('reads an absent summary or absent fields as nothing to say', () => {
        expect(importOutcomeLines(null)).toEqual([])
        expect(importOutcomeLines({})).toEqual([])
    })
})
