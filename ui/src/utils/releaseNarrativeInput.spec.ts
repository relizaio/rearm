import { describe, it, expect } from 'vitest'
import { releaseNarrativeVariables, releaseNarrativeDiffers } from './releaseNarrativeInput'
import { FDA_PROSE_MAX_LENGTH } from './fdaProseInput'

const U = '11111111-1111-1111-1111-111111111111'
const O = '22222222-2222-2222-2222-222222222222'

describe('releaseNarrativeVariables', () => {
    // The durable guard, same as the device window's: updateRelease treats a null list as
    // "no change", so a payload that grew an artifacts key could DETACH release contents.
    it('sends ONLY uuid, org and the field -- never a dead key', () => {
        const vars = releaseNarrativeVariables(U, O, 'device-specific', null)
        expect(Object.keys(vars!).sort()).toEqual(['fdaAssessmentNarrative', 'org', 'uuid'])
    })

    it('returns null when nothing changed, so no mutation is fired', () => {
        expect(releaseNarrativeVariables(U, O, 'same', 'same')).toBeNull()
        expect(releaseNarrativeVariables(U, O, null, null)).toBeNull()
    })

    it('sends a changed value trimmed', () => {
        expect(releaseNarrativeVariables(U, O, '  new text  ', null))
            .toEqual({ uuid: U, org: O, fdaAssessmentNarrative: 'new text' })
    })

    // '' is the wire form of a deliberate clear; the server reads it as "return to
    // inheriting the org default".
    it('sends an empty string when text is emptied', () => {
        expect(releaseNarrativeVariables(U, O, '', 'previous'))
            .toEqual({ uuid: U, org: O, fdaAssessmentNarrative: '' })
    })

    it('treats replacing text with whitespace as a clear', () => {
        expect(releaseNarrativeVariables(U, O, '   ', 'previous'))
            .toEqual({ uuid: U, org: O, fdaAssessmentNarrative: '' })
    })

    it('treats whitespace typed into an empty field as no change', () => {
        expect(releaseNarrativeVariables(U, O, '   ', null)).toBeNull()
    })

    it('does not send a field whose only change is surrounding whitespace', () => {
        expect(releaseNarrativeVariables(U, O, '  same  ', 'same')).toBeNull()
    })

    // null on the server and '' in an untouched form are the same state; treating them as
    // different would send a spurious clear on every save of a form nobody edited.
    it('treats a null baseline and an empty form as equal', () => {
        expect(releaseNarrativeVariables(U, O, '', null)).toBeNull()
    })
})

describe('releaseNarrativeDiffers', () => {
    it('is false for equal values and whitespace-only differences', () => {
        expect(releaseNarrativeDiffers('same', 'same')).toBe(false)
        expect(releaseNarrativeDiffers('  same  ', 'same')).toBe(false)
        expect(releaseNarrativeDiffers(null, '')).toBe(false)
    })

    it('is true for a real edit and for a clear', () => {
        expect(releaseNarrativeDiffers('new', 'old')).toBe(true)
        expect(releaseNarrativeDiffers('', 'old')).toBe(true)
        expect(releaseNarrativeDiffers('new', null)).toBe(true)
    })
})

describe('the shared bound', () => {
    // Re-exported rather than redeclared: the release override and the org default must not
    // be able to accept different lengths, and the server enforces one number for both.
    it('is the same constant the org prose form uses', () => {
        expect(FDA_PROSE_MAX_LENGTH).toBe(8000)
    })
})
