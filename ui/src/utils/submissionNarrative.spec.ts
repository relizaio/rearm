// The assessment-narrative prompt before a submission's Create or Resubmit: which kinds ask for it,
// what it opens on, when it can confirm, and the updateRelease write each choice needs (PATCH: ''
// clears back to the organization default, null sends nothing).
import { describe, expect, it } from 'vitest'
import { FDA_PROSE_MAX_LENGTH } from './fdaProseInput'
import {
    NO_ORG_NARRATIVE, UPDATE_RELEASE_NARRATIVE_GQL, kindNeedsNarrative, narrativeChoiceError, narrativeChoiceVariables,
    narrativeInitialChoice, narrativeInitialText,
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
    it('the override when there is one, else the organization default when there is one, else writing one', () => {
        expect(narrativeInitialChoice('Mine', 'Org')).toBe('RELEASE')
        expect(narrativeInitialChoice(null, 'Org')).toBe('ORGANIZATION')
        expect(narrativeInitialChoice('   ', 'Org')).toBe('ORGANIZATION')
        expect(narrativeInitialChoice(null, '')).toBe('RELEASE')
        expect(narrativeInitialChoice(null, '  ')).toBe('RELEASE')
    })

    it('the text starts from the override, else from the organization default', () => {
        expect(narrativeInitialText('Mine', 'Org')).toBe('Mine')
        expect(narrativeInitialText(' ', 'Org')).toBe('Org')
        expect(narrativeInitialText(null, null)).toBe('')
    })
})

describe('when the prompt can confirm', () => {
    it('the organization default needs one to exist', () => {
        expect(narrativeChoiceError('ORGANIZATION', '', 'Org')).toBeNull()
        expect(narrativeChoiceError('ORGANIZATION', 'x', ' ')).toBe(NO_ORG_NARRATIVE)
        expect(NO_ORG_NARRATIVE).toBe('No organization default is set')
    })

    it('a written narrative is not blank and stays within the shared bound', () => {
        expect(narrativeChoiceError('RELEASE', 'Text', null)).toBeNull()
        expect(narrativeChoiceError('RELEASE', '   ', 'Org')).not.toBeNull()
        expect(narrativeChoiceError('RELEASE', 'x'.repeat(FDA_PROSE_MAX_LENGTH), null)).toBeNull()
        expect(narrativeChoiceError('RELEASE', 'x'.repeat(FDA_PROSE_MAX_LENGTH + 1), null)).not.toBeNull()
        expect(narrativeChoiceError(null, 'Text', 'Org')).not.toBeNull()
    })
})

describe('the write each choice needs', () => {
    it('the organization default clears an override with an empty string, and sends nothing without one', () => {
        expect(narrativeChoiceVariables('r1', 'o1', 'ORGANIZATION', 'ignored', 'Mine'))
            .toEqual({ uuid: 'r1', org: 'o1', fdaAssessmentNarrative: '' })
        expect(narrativeChoiceVariables('r1', 'o1', 'ORGANIZATION', 'ignored', null)).toBeNull()
        expect(narrativeChoiceVariables('r1', 'o1', 'ORGANIZATION', 'ignored', '  ')).toBeNull()
    })

    it('a written narrative is sent trimmed when it differs, and omitted when it does not', () => {
        expect(narrativeChoiceVariables('r1', 'o1', 'RELEASE', '  New  ', 'Mine'))
            .toEqual({ uuid: 'r1', org: 'o1', fdaAssessmentNarrative: 'New' })
        expect(narrativeChoiceVariables('r1', 'o1', 'RELEASE', 'Org', null))
            .toEqual({ uuid: 'r1', org: 'o1', fdaAssessmentNarrative: 'Org' })
        expect(narrativeChoiceVariables('r1', 'o1', 'RELEASE', ' Mine ', 'Mine')).toBeNull()
    })

    it('goes through the one narrow updateRelease mutation', () => {
        const def: any = UPDATE_RELEASE_NARRATIVE_GQL.definitions[0]
        expect(def.name.value).toBe('updateReleaseNarrative')
        expect(def.selectionSet.selections[0].name.value).toBe('updateRelease')
        expect(def.selectionSet.selections[0].selectionSet.selections.map((f: any) => f.name.value))
            .toEqual(['uuid', 'fdaAssessmentNarrative'])
    })
})
