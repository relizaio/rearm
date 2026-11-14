// The assessment-narrative prompt before a submission's Create or Resubmit: which kinds ask for it,
// what its text starts from, when it can confirm (never on the organization default unchanged,
// FDAW-16 3.8), and the updateRelease write the text needs (null sends nothing).
import { describe, expect, it } from 'vitest'
import { FDA_PROSE_MAX_LENGTH } from './fdaProseInput'
import {
    NARRATIVE_IS_ORG_DEFAULT, NO_ORG_NARRATIVE, UPDATE_RELEASE_NARRATIVE_GQL, kindNeedsNarrative, narrativeChoiceError,
    narrativeChoiceVariables, narrativeInitialText,
} from './submissionNarrative'

describe('the kinds that ask for the narrative', () => {
    it('asks only where the catalog entry has a REQUIRED NARRATIVE part', () => {
        expect(kindNeedsNarrative({ parts: [{ part: 'AGGREGATE_SBOM', requirement: 'REQUIRED' },
            { part: 'NARRATIVE', requirement: 'REQUIRED' }] })).toBe(true)
        expect(kindNeedsNarrative({ parts: [{ part: 'NARRATIVE', requirement: 'OPTIONAL' }] })).toBe(false)
        expect(kindNeedsNarrative({ parts: [{ part: 'AGGREGATE_SBOM', requirement: 'REQUIRED' }] })).toBe(false)
        expect(kindNeedsNarrative({ parts: null })).toBe(false)
        expect(kindNeedsNarrative(undefined)).toBe(false)
    })
})

describe('what the prompt opens on', () => {
    it('the text starts from the override, else from the organization default', () => {
        expect(narrativeInitialText('Mine', 'Org')).toBe('Mine')
        expect(narrativeInitialText(' ', 'Org')).toBe('Org')
        expect(narrativeInitialText(null, null)).toBe('')
    })
})

describe('when the prompt can confirm', () => {
    it('a written narrative is not blank, stays within the shared bound, and is not the organization default', () => {
        expect(NO_ORG_NARRATIVE).toBe('No organization default is set')
        expect(narrativeChoiceError('Text', null)).toBeNull()
        expect(narrativeChoiceError('   ', 'Org')).not.toBeNull()
        expect(narrativeChoiceError('x'.repeat(FDA_PROSE_MAX_LENGTH), null)).toBeNull()
        expect(narrativeChoiceError('x'.repeat(FDA_PROSE_MAX_LENGTH + 1), null)).not.toBeNull()
        // FDAW-16 row 21: the default, trimmed, answers text 14; a text of its own, or no default, confirms.
        expect(narrativeChoiceError(' Text ', 'Text')).toBe("Write this release's own cover summary; the organization default is only a starting point")
        expect(NARRATIVE_IS_ORG_DEFAULT).toBe("Write this release's own cover summary; the organization default is only a starting point")
        expect(narrativeChoiceError('Mine', 'Text')).toBeNull()
        expect(narrativeChoiceError('Mine', null)).toBeNull()
        expect(narrativeChoiceError('  ', 'Text')).toBe('Write the assessment narrative for this release')
    })
})

describe('the write the text needs', () => {
    it('a written narrative is sent trimmed when it differs, and omitted when it does not', () => {
        expect(narrativeChoiceVariables('r1', 'o1', '  New  ', 'Mine'))
            .toEqual({ uuid: 'r1', org: 'o1', fdaAssessmentNarrative: 'New' })
        expect(narrativeChoiceVariables('r1', 'o1', 'Org', null))
            .toEqual({ uuid: 'r1', org: 'o1', fdaAssessmentNarrative: 'Org' })
        expect(narrativeChoiceVariables('r1', 'o1', ' Mine ', 'Mine')).toBeNull()
    })

    it('goes through the one narrow updateRelease mutation', () => {
        const def: any = UPDATE_RELEASE_NARRATIVE_GQL.definitions[0]
        expect(def.name.value).toBe('updateReleaseNarrative')
        expect(def.selectionSet.selections[0].name.value).toBe('updateRelease')
        expect(def.selectionSet.selections[0].selectionSet.selections.map((f: any) => f.name.value))
            .toEqual(['uuid', 'fdaAssessmentNarrative'])
    })
})
