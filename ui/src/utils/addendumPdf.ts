// The FDA support addendum, rendered as PDF.
//
// The second renderer over addendumDocument.ts. It shares the columns, the rows, the header
// block and the ordering with the CSV, so the two documents are comparable line for line --
// a reviewer holding both must not have to wonder which one is right about a component.
//
// DELIBERATELY NOT AN EXTENSION OF pdfExport.ts. That module renders findings: it filters by
// severity and analysis state, sorts by a vulnerability type order, and colours cells by
// CVSS band. None of that has meaning here, and threading a second document through its
// options object would couple a regulatory export to a vulnerability report's shape.

import pdfMake from 'pdfmake/build/pdfmake'
import pdfFonts from 'pdfmake/build/vfs_fonts'
import type { AddendumData } from './addendumData'
import { fontCoverageRefusal } from './pdfFontCoverage'
import { documentFooterIdentity, documentInstantText } from './fdaLabels'
import { ADDENDUM_COLUMNS, ADDENDUM_TITLE, addendumRow, addendumHeaderRows, displayOrder,
    addendumFileStem } from './addendumDocument'

pdfMake.vfs = pdfFonts.vfs

/**
 * Column widths, in pdfmake units, one per ADDENDUM_COLUMNS entry: Component, Version, PURL,
 * Level of support, End of support, Justification, Attestation state, Last assessed, Source,
 * Asserted by.
 *
 * EVERY WIDTH IS FIXED, so the table fits the page whatever the data. An earlier revision gave
 * PURL and Justification '*'; pdfmake gives every star column at least the largest unbreakable
 * run among them, so one long PURL pushed the table past the page edge: Source was cut at the
 * margin and Asserted by was not on the page at all (FDAW-65, FDAW-68). With fixed widths, text
 * wraps and a run longer than its column is split inside it, so long values grow the row, never
 * the table.
 *
 * The budget: landscape A4 is 841.89 wide, minus the 28 + 28 margins = 785.89 of body. The
 * widths sum to 710 and ADDENDUM_TABLE_LAYOUT's padding adds 9 x 8 = 72: 782. Last assessed
 * gets 84 so a YYYY-MM-DDTHH:MM:SSZ instant fits one line at 8 pt. Change a width and the
 * fit cell of addendumPdf.spec.ts recomputes the sum from this definition.
 */
const COLUMN_WIDTHS = [70, 50, 96, 58, 74, 94, 52, 84, 66, 66]

/**
 * The data table's layout: the lines of pdfmake's 'lightHorizontalLines', with this module's
 * own padding of 4 (that built-in pads 8 on each inner side, which alone is 144 units of the
 * page). Restated rather than imported, so a pdfmake upgrade that changes its built-in layout
 * changes nothing here, and an object rather than the string so the spec can evaluate the
 * padding the renderer actually uses.
 *
 * ROWS BREAK ACROSS PAGES. Narrow columns make tall rows, and a row can be taller than a page
 * (an 8,000-character justification). pdfmake's default continues such a row on the next page
 * under the repeated header, which keeps every word on paper. dontBreakRows or unbreakable does
 * the opposite for a row taller than a page: pdfmake drops the row's text and the rows after
 * it. So no table, row or cell in this document names either, at any value. A PURL tail alone
 * at the top of a page is the accepted cost.
 */
export const ADDENDUM_TABLE_LAYOUT = {
    hLineWidth: (i: number, node: any) => i === 0 ? 0 : (i === node.table.headerRows ? 2 : 1),
    vLineWidth: () => 0,
    hLineColor: (i: number) => i === 1 ? 'black' : '#aaa',
    paddingLeft: (i: number) => i === 0 ? 0 : 4,
    paddingRight: (i: number, node: any) => i === node.table.widths.length - 1 ? 0 : 4
}

/**
 * Coerce a value for a table cell.
 *
 * Empty for null/undefined -- and that is NOT the same as a blank meaning "nothing recorded".
 * Whether a cell says "not assessed" or stands empty was decided upstream in addendumRow;
 * this only turns an absent value into an empty string rather than the text "null".
 */
function cell (value: unknown): string {
    if (null === value || undefined === value) return ''
    return String(value)
}

/**
 * The characters in THIS document that the PDF font cannot draw, or null if there are none.
 *
 * Thin wrapper over the shared coverage check: it knows which strings this document prints,
 * and nothing about fonts.
 */
export function findUnrenderableText (d: AddendumData): string | null {
    const texts: Array<string | null | undefined> = []
    for (const row of addendumHeaderRows(d)) row.forEach(v => texts.push(cell(v)))
    for (const c of d.components) addendumRow(c).forEach(v => texts.push(cell(v)))
    return fontCoverageRefusal(texts, 'support addendum')
}

/**
 * The document definition: PLAIN JSON, no rendering.
 *
 * Returned rather than rendered so the spec can assert on the structure directly. Asserting
 * against a rendered PDF would mean parsing a binary to find out whether a cell said "not
 * assessed", which tests the parser as much as the document.
 *
 * LONG TEXT WRAPS, NEVER TRUNCATES. pdfmake wraps by default inside a table cell, and
 * nothing here sets noWrap or an ellipsis. That is the single most important property of
 * this document: a justification cut short at a page boundary is a regulatory statement the
 * manufacturer did not make, and it would look deliberate.
 */
export function buildAddendumDocDefinition (d: AddendumData): Record<string, unknown> {
    const headerRows = addendumHeaderRows(d)
    const body = [
        ADDENDUM_COLUMNS.map(h => ({ text: h, style: 'tableHeader' })),
        ...displayOrder(d.components).map(c => addendumRow(c).map(v => cell(v)))
    ]

    return {
        pageSize: 'A4',
        pageOrientation: 'landscape',
        pageMargins: [28, 34, 28, 46],
        content: [
            { text: ADDENDUM_TITLE, style: 'title' },
            // The header block, rendered as label/value pairs from the SAME source the CSV
            // header uses. Rows the CSV emits as spacers come through with no label, so they
            // are dropped rather than rendered as empty lines.
            {
                style: 'facts',
                table: {
                    widths: [190, '*'],
                    body: headerRows
                        .filter(r => r.length && null !== r[0] && undefined !== r[0] && '' !== String(r[0]))
                        .filter(r => String(r[0]) !== ADDENDUM_TITLE)
                        .map(r => [{ text: cell(r[0]), bold: true }, { text: cell(r[1]) }])
                },
                layout: 'noBorders',
                margin: [0, 0, 0, 12]
            },
            {
                table: {
                    headerRows: 1,
                    widths: COLUMN_WIDTHS,
                    body: body.length > 1 ? body : [...body, [{
                        text: 'This release contains no SBOM components.',
                        colSpan: ADDENDUM_COLUMNS.length, alignment: 'center', italics: true
                    }, ...Array(ADDENDUM_COLUMNS.length - 1).fill({})]]
                },
                layout: ADDENDUM_TABLE_LAYOUT
            }
        ],
        /**
         * Release identity on every page, because a page separated from the document must
         * still say which release it describes -- these get printed and passed around.
         */
        footer: (currentPage: number, pageCount: number) => ({
            style: 'footer',
            margin: [28, 8, 28, 0],
            columns: [
                { text: documentFooterIdentity(d.componentName, d.releaseVersion), alignment: 'left' },
                { text: 'Generated ' + documentInstantText(d.generatedAt), alignment: 'center' },
                { text: `Page ${currentPage} of ${pageCount}`, alignment: 'right' }
            ]
        }),
        styles: {
            title: { fontSize: 15, bold: true, margin: [0, 0, 0, 8] },
            facts: { fontSize: 9 },
            tableHeader: { bold: true, fontSize: 8, color: '#333333' },
            footer: { fontSize: 7, color: '#666666' }
        },
        defaultStyle: { fontSize: 8 }
    }
}

/** Same stem as the CSV, so the pair sorts together in a downloads folder. */
export function addendumPdfFileName (d: AddendumData): string {
    return `${addendumFileStem(d)}.pdf`
}

/**
 * Render to a Blob rather than calling pdfmake's own .download().
 *
 * The addendum's CSV path already builds a Blob and drives an anchor; using the same idiom
 * keeps one download path to reason about, and it is the one that revokes its object URL.
 */
export async function renderAddendumPdfBlob (d: AddendumData): Promise<Blob> {
    // getBlob() is PROMISE-BASED in pdfmake 0.3 (`async getBlob()`, typed
    // `getBlob(): Promise<Blob>`). An earlier revision passed it a callback, as 0.2 took --
    // the callback was simply never invoked, so the promise never settled and the export
    // spinner span forever with no error anywhere. Awaiting it is the whole fix.
    return pdfMake.createPdf(buildAddendumDocDefinition(d) as any).getBlob()
}
