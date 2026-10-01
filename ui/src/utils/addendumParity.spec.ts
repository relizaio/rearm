import { describe, it, expect, vi } from 'vitest'

vi.mock('pdfmake/build/pdfmake', () => ({ default: { vfs: null, createPdf: vi.fn() } }))
vi.mock('pdfmake/build/vfs_fonts', () => ({ default: { vfs: {} } }))

import { renderAddendumCsv } from './addendumCsv'
import { buildAddendumDocDefinition } from './addendumPdf'
import { ADDENDUM_COLUMNS, ADDENDUM_TITLE } from './addendumDocument'
import type { AddendumComponent, AddendumData } from './addendumData'

/**
 * CSV / PDF parity.
 *
 * A reviewer holding both documents for one release must never have to work out which is
 * right. Parity is structural -- both renderers call addendumRow and displayOrder from
 * addendumDocument.ts -- so these assertions prove the structure is actually wired that way
 * rather than comparing two hand-maintained copies. If someone reimplements a rule in one
 * renderer, this fails.
 */
function comp (over: Partial<AddendumComponent> = {}): AddendumComponent {
    return {
        sbomComponentUuid: 'sc-x', name: 'log4j-core', group: 'org.apache', version: '2.14.1',
        purl: 'pkg:maven/org.apache/log4j-core@2.14.1', attestationState: 'ATTESTED',
        levelOfSupport: 'actively maintained', levelOfSupportEnum: 'ACTIVELY_MAINTAINED',
        endOfSupportDate: '2030-01-01', justification: null,
        assessedAt: '2026-09-01T00:00:00Z', ...over
    }
}

/** Deliberately awkward: every branch of addendumRow, plus CSV-hostile characters. */
const COMPONENTS: AddendumComponent[] = [
    comp({ sbomComponentUuid: 'a', name: 'zeta', group: null }),
    comp({ sbomComponentUuid: 'b', name: 'alpha', group: 'org.example',
        levelOfSupport: null, justification: 'no upstream date published, and we asked' }),
    comp({ sbomComponentUuid: 'c', name: 'withdrawn-one', attestationState: 'WITHDRAWN',
        justification: 'retracted reasoning' }),
    comp({ sbomComponentUuid: 'd', name: 'unassessed', attestationState: null,
        levelOfSupport: null, endOfSupportDate: null, assessedAt: null }),
    comp({ sbomComponentUuid: 'e', name: 'quote"comma,newline',
        justification: 'said "yes", then\nleft', levelOfSupport: null }),
    comp({ sbomComponentUuid: 'f', name: 'formula', levelOfSupport: null,
        justification: '=HYPERLINK("http://x","ok")' })
]

const DATA: AddendumData = {
    releaseUuid: 'r-parity', releaseVersion: '9000.1.0', componentName: 'Infusion Pump 9000',
    componentType: 'PRODUCT',
    deviceEos: '2030-06-30', deviceEol: null,
    narrative: 'Assessed per DHF-PROC-9001, "rev D", including a comma.',
    narrativeIsPerRelease: true, orgName: 'Acme, Inc.',
    patchesMayCeaseStatement: 'Patches may cease.', riskTransferProcessRef: 'DHF-4471',
    riskIncreasesNotice: 'Risk increases.',
    totalComponents: COMPONENTS.length, attestedComponents: 2,
    unassessedComponents: COMPONENTS.length - 2,
    components: COMPONENTS, generatedAt: '2026-09-08T12:00:00Z'
}

/** Parse the CSV back into rows, so the comparison is against what a READER sees. */
function parseCsv (csv: string): string[][] {
    const rows: string[][] = []
    let row: string[] = [], cur = '', q = false
    const body = csv.charCodeAt(0) === 0xfeff ? csv.slice(1) : csv
    for (let i = 0; i < body.length; i++) {
        const c = body[i]
        if (q) {
            if (c === '"') { if (body[i + 1] === '"') { cur += '"'; i++ } else q = false }
            else cur += c
        } else if (c === '"') q = true
        else if (c === ',') { row.push(cur); cur = '' }
        else if (c === '\r' && body[i + 1] === '\n') { row.push(cur); cur = ''; rows.push(row); row = []; i++ }
        else cur += c
    }
    if (cur.length || row.length) { row.push(cur); rows.push(row) }
    return rows
}

const csvRows = parseCsv(renderAddendumCsv(DATA))
const csvHeaderIdx = csvRows.findIndex(r => r[0] === ADDENDUM_COLUMNS[0] && r[1] === ADDENDUM_COLUMNS[1])
const csvDataRows = csvRows.slice(csvHeaderIdx + 1).filter(r => r.some(c => c.length))

/**
 * Undo the ONE permitted divergence.
 *
 * csvCell prefixes an apostrophe when a cell begins with =, +, -, @, tab or CR, because
 * several spreadsheets evaluate such a cell on open -- a justification rendering as a
 * hyperlink means the exported evidence does not say what the record says. A PDF has no
 * evaluation risk, so it carries the operator's text verbatim. That is an ENCODING
 * difference, and this strips it so the comparison below is about what the cell SAYS.
 */
const FORMULA_LEADERS = ['=', '+', '-', '@', '\t', '\r']
function deneutralise (cell: string): string {
    return cell.length > 1 && cell[0] === "'" && FORMULA_LEADERS.includes(cell[1])
        ? cell.slice(1)
        : cell
}

const doc: any = buildAddendumDocDefinition(DATA)
const pdfTable = (doc.content as any[]).filter(c => c && c.table)[1].table
const pdfDataRows: string[][] = pdfTable.body.slice(1)

describe('CSV and PDF are the same document in two encodings', () => {
    it('agree on the column set and its order', () => {
        expect(csvRows[csvHeaderIdx]).toEqual(ADDENDUM_COLUMNS)
        expect(pdfTable.body[0].map((c: any) => c.text)).toEqual(ADDENDUM_COLUMNS)
    })

    it('emit the same number of data rows', () => {
        expect(csvDataRows).toHaveLength(COMPONENTS.length)
        expect(pdfDataRows).toHaveLength(COMPONENTS.length)
    })

    it('emit the rows in the same order', () => {
        expect(pdfDataRows.map(r => r[0])).toEqual(csvDataRows.map(r => r[0]))
    })

    // The assertion that actually matters: every cell, both documents, same value. A rule
    // reimplemented in one renderer shows up here as a mismatched cell.
    it('agree on every cell value', () => {
        expect(pdfDataRows).toEqual(csvDataRows.map(r => r.map(deneutralise)))
    })

    it('agree on the header block facts, in both directions', () => {
        const factsTable = (doc.content as any[]).filter(c => c && c.table)[0].table
        const pdfFacts = new Map<string, string>(
            factsTable.body.map((r: any) => [String(r[0].text), String(r[1].text)]))
        const csvFacts = csvRows.slice(0, csvHeaderIdx)
            .filter(r => r[0] && r[0] !== ADDENDUM_TITLE && r.some(c => c.length))
        for (const row of csvFacts) {
            expect(pdfFacts.get(row[0]), `header fact "${row[0]}"`).toBe(row[1])
        }
        // BOTH directions: a fact the PDF invents, or one it drops, is a divergence too.
        expect([...pdfFacts.keys()].sort()).toEqual(csvFacts.map(r => r[0]).sort())
        // The PDF renders a header row as exactly two cells, so a future three-element row
        // would be silently truncated. Fail here instead.
        expect(csvFacts.every(r => r.filter(c => c.length).length <= 2)).toBe(true)
    })

    // Deliberate, and previously unasserted: the CSV emits no data rows for an empty release
    // while the PDF emits an explanatory note row, so the counts differ BY DESIGN.
    it('handle a release with no components in their own documented ways', () => {
        const empty = { ...DATA, components: [], totalComponents: 0, attestedComponents: 0,
            unassessedComponents: 0 }
        const csv = parseCsv(renderAddendumCsv(empty))
        const hdr = csv.findIndex(r => r[0] === ADDENDUM_COLUMNS[0])
        expect(csv.slice(hdr + 1).filter(r => r.some(c => c.length))).toHaveLength(0)
        const t = (buildAddendumDocDefinition(empty).content as any[]).filter(c => c && c.table)[1].table
        expect(t.body).toHaveLength(2)
        expect(JSON.stringify(t.body[1])).toContain('no SBOM components')
    })

    // Encoding differences are allowed and expected -- the CSV quotes, the PDF does not --
    // but they must not change what the cell SAYS.
    it('agree on a cell containing a quote, a comma and a newline', () => {
        const csvRow = csvDataRows.find(r => r[0].includes('quote'))!
        const pdfRow = pdfDataRows.find(r => r[0].includes('quote'))!
        expect(pdfRow).toEqual(csvRow)
        expect(csvRow.join(' ')).toContain('said "yes", then\nleft')
    })

    /**
     * KNOWN SECOND DIVERGENCE, recorded so it is a decision rather than a surprise: a TAB
     * inside operator prose is preserved by the CSV (quoted) and collapsed to a single space
     * by the PDF, because that is what a PDF text run does with whitespace. The fixture below
     * deliberately contains no tabs, so the "only differing cell" claim holds for it; a tab
     * would add a second, and this note is where a future reader finds out why.
     */
    // The CSV neutralises a leading = against spreadsheet evaluation; the PDF has no such
    // risk. Asserted so the difference is a decision on record rather than a surprise, and
    // so the CELL TEXT is otherwise identical.
    it('differ only in formula neutralisation, which is CSV-specific', () => {
        const raw = renderAddendumCsv(DATA)
        expect(raw).toContain("'=HYPERLINK")
        const pdfRow = pdfDataRows.find(r => r[0].endsWith(':formula'))!
        const csvRow = csvDataRows.find(r => r[0].endsWith(':formula'))!
        expect(pdfRow[5]).toBe('=HYPERLINK("http://x","ok")')
        expect(csvRow[5]).toBe("'=HYPERLINK(\"http://x\",\"ok\")")
        // ...and that is the ONLY cell in the whole document where they differ. Located by
        // searching rather than by a fixed index: displayOrder sorts alphabetically, so the
        // formula row is not where it was declared.
        const formulaIdx = csvDataRows.findIndex(r => r[0].endsWith(':formula'))
        // Guarded: an unguarded index turned a row-count divergence into a TypeError rather
        // than a readable assertion failure, which is the harder bug to diagnose.
        expect(pdfDataRows).toHaveLength(csvDataRows.length)
        const differing = csvDataRows.flatMap((r, i) =>
            r.map((c, j) => (c === (pdfDataRows[i] || [])[j] ? null : `${i}:${j}`)).filter(Boolean))
        expect(differing).toEqual([`${formulaIdx}:5`])
    })
})
