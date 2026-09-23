import { describe, it, expect } from 'vitest'
import { formatSupportWindow } from './supportWindowDisplay'

describe('formatSupportWindow', () => {
    it('renders both dates', () => {
        expect(formatSupportWindow('eos=2030-06-30, eol=2033-01-01'))
            .toBe('EOS 2030-06-30, EOL 2033-01-01')
    })

    // ReleaseData.supportWindowValueString concatenates possibly-null values, so an
    // undeclared date arrives as the literal text "null". Rendering that raw would put
    // "eos=null" in front of an operator.
    it('renders a null half as "not declared", not as the string null', () => {
        expect(formatSupportWindow('eos=null, eol=2033-01-01'))
            .toBe('EOS not declared, EOL 2033-01-01')
        expect(formatSupportWindow('eos=2030-06-30, eol=null'))
            .toBe('EOS 2030-06-30, EOL not declared')
    })

    it('renders a fully-cleared window', () => {
        expect(formatSupportWindow('eos=null, eol=null'))
            .toBe('EOS not declared, EOL not declared')
    })

    it('treats an absent value as not declared', () => {
        expect(formatSupportWindow(null)).toBe('not declared')
        expect(formatSupportWindow(undefined)).toBe('not declared')
        expect(formatSupportWindow('')).toBe('not declared')
    })

    // Deliberate passthrough: coupled to the server format by comment alone, so a format
    // change should show something odd rather than swallow the row into a blank cell.
    it('passes an unrecognised shape through unchanged', () => {
        expect(formatSupportWindow('something else entirely')).toBe('something else entirely')
    })
})
