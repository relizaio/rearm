// RFC 4180 CSV rendering.
//
// Hand-rolled rather than pulled in: the whole surface is one escaper and one joiner, and
// the cells here carry operator-authored prose -- up to 8,000 characters of justification
// with whatever punctuation the author used. A quoting bug in that cell does not produce a
// visibly broken file, it produces a file that opens with the columns shifted, which on a
// regulatory document is worse than a crash.

/**
 * Cells that begin with one of these are prefixed with a single quote before quoting.
 *
 * FORMULA INJECTION, and it is not theoretical for this document: a justification field is
 * free text an operator types, and several spreadsheet applications evaluate a cell starting
 * with these characters on open. `=HYPERLINK(...)` in a justification would render as a link
 * rather than as the words the assessor wrote -- so the exported evidence would not say what
 * the record says.
 *
 * The prefix is visible in the cell, which is the correct trade: a leading apostrophe on the
 * rare cell that starts with an operator is obvious and harmless, while silent evaluation is
 * neither.
 */
const FORMULA_LEADERS = ['=', '+', '-', '@', '\t', '\r']

/** Characters that force quoting. A cell containing any of them cannot be written bare. */
const MUST_QUOTE = /[",\r\n]/

/**
 * One cell.
 *
 * Null and undefined render as EMPTY, never as the strings "null"/"undefined". That
 * distinction is the whole point in this document: an empty EOS cell means "no date was
 * attested", and the word "null" printed in a regulatory table reads as a system fault.
 */
export function csvCell (value: unknown): string {
    if (null === value || undefined === value) return ''
    const raw = String(value)
    // Decided from the RAW value, before the apostrophe goes on. Prefixing first would hide
    // a leading tab behind a non-whitespace character, so the cell would be written bare and
    // a parser free to strip that tab -- losing a character the operator typed.
    const needsQuote = MUST_QUOTE.test(raw) || raw !== raw.trim()
    const s = (raw.length > 0 && FORMULA_LEADERS.includes(raw[0])) ? "'" + raw : raw
    // Quote-doubling, per RFC 4180: a literal " inside a quoted field is written "".
    if (needsQuote) return '"' + s.replace(/"/g, '""') + '"'
    return s
}

/** One record. */
export function csvRow (cells: unknown[]): string {
    return cells.map(csvCell).join(',')
}

/**
 * A whole document.
 *
 * CRLF line endings, which RFC 4180 specifies and which Excel on Windows needs to avoid
 * running the file together into one row.
 *
 * A UTF-8 BOM is prepended. Without it Excel decodes the file as the system codepage, so a
 * component named with anything outside ASCII -- or a justification written in any language
 * that needs accents -- opens as mojibake. The BOM is invisible in every tool that matters
 * and is what makes the file open correctly by double-click, which is how it will be opened.
 */
export function csvDocument (rows: unknown[][]): string {
    // Escaped, never a literal U+FEFF in the source: the CI invisible-character check
    // hard-fails the build (exit 123) BEFORE maven runs, and a BOM is exactly the kind of
    // character it exists to catch. It is also unreviewable as a literal -- it renders as
    // nothing in a diff.
    return '\ufeff' + rows.map(csvRow).join('\r\n') + '\r\n'
}
