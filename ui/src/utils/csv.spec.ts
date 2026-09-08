import { describe, it, expect } from 'vitest'
import { csvCell, csvRow, csvDocument } from './csv'

describe('csvCell', () => {
    it('leaves a plain value bare', () => {
        expect(csvCell('log4j-core')).toBe('log4j-core')
        expect(csvCell(42)).toBe('42')
    })

    // The distinction the document depends on: an empty EOS cell means no date was
    // attested. The word "null" in a regulatory table reads as a system fault.
    it('renders null and undefined as empty, never as the words', () => {
        expect(csvCell(null)).toBe('')
        expect(csvCell(undefined)).toBe('')
    })

    it('quotes a value containing a comma', () => {
        expect(csvCell('Reliza, Incorporated')).toBe('"Reliza, Incorporated"')
    })

    // RFC 4180 quote-doubling. Getting this wrong terminates the field early and shifts
    // every remaining column on the row.
    it('doubles embedded quotes and wraps the cell', () => {
        expect(csvCell('he said "maintained"')).toBe('"he said ""maintained"""')
    })

    it('quotes a value containing a newline, keeping the newline intact', () => {
        expect(csvCell('line one\nline two')).toBe('"line one\nline two"')
        expect(csvCell('crlf\r\ninside')).toBe('"crlf\r\ninside"')
    })

    it('quotes a value with leading or trailing whitespace so it survives a round trip', () => {
        expect(csvCell('  padded  ')).toBe('"  padded  "')
    })

    // Formula injection. A justification is free text an operator types, and several
    // spreadsheets evaluate a leading =/+/-/@ on open -- so the exported evidence would not
    // say what the record says.
    it.each([
        ['=HYPERLINK("http://evil","ok")'],
        ['+1234'],
        ['-1+2'],
        ['@SUM(A1:A9)']
    ])('neutralises %s with a leading apostrophe', (payload) => {
        const out = csvCell(payload)
        expect(out.startsWith("'") || out.startsWith('"\'')).toBe(true)
        expect(out).toContain("'" + payload[0])
    })

    it('neutralises a formula that also needs quoting, in that order', () => {
        expect(csvCell('=A1,B1')).toBe('"\'=A1,B1"')
    })

    it('does not touch a minus sign that is not leading', () => {
        expect(csvCell('utf-8')).toBe('utf-8')
    })

    it('neutralises leading tab and carriage return, which some tools also evaluate', () => {
        expect(csvCell('\tvalue')).toBe('"\'\tvalue"')
    })
})

describe('csvRow', () => {
    it('joins cells with commas', () => {
        expect(csvRow(['a', 'b', 'c'])).toBe('a,b,c')
    })

    it('keeps empty cells positional rather than collapsing them', () => {
        expect(csvRow(['a', null, 'c'])).toBe('a,,c')
        expect(csvRow([null, null])).toBe(',')
    })

    it('escapes each cell independently', () => {
        expect(csvRow(['plain', 'has,comma', 'has"quote'])).toBe('plain,"has,comma","has""quote"')
    })
})

describe('csvDocument', () => {
    it('separates records with CRLF and ends with one', () => {
        expect(csvDocument([['a'], ['b']])).toBe('\ufeffa\r\nb\r\n')
    })

    // Without the BOM, Excel decodes as the system codepage and any non-ASCII component
    // name or accented justification opens as mojibake.
    it('starts with a UTF-8 BOM so a double-click open decodes correctly', () => {
        expect(csvDocument([['a']]).charCodeAt(0)).toBe(0xfeff)
    })

    // The end-to-end property that matters: a cell with the three hostile characters at once
    // must survive a naive RFC-4180 parse back to its original value.
    it('round-trips a cell containing a quote, a comma and a newline', () => {
        const nasty = 'said "yes", then\nleft'
        const doc = csvDocument([['before', nasty, 'after']])
        const body = doc.slice(1, -2)
        const parsed = parseRfc4180Row(body)
        expect(parsed).toEqual(['before', nasty, 'after'])
    })
})

/** A deliberately naive parser: if the writer only satisfies a lenient reader, it is wrong. */
function parseRfc4180Row (row: string): string[] {
    const out: string[] = []
    let cur = ''
    let inQuotes = false
    for (let i = 0; i < row.length; i++) {
        const c = row[i]
        if (inQuotes) {
            if (c === '"') {
                if (row[i + 1] === '"') { cur += '"'; i++ } else { inQuotes = false }
            } else cur += c
        } else if (c === '"') inQuotes = true
        else if (c === ',') { out.push(cur); cur = '' }
        else cur += c
    }
    out.push(cur)
    return out
}
