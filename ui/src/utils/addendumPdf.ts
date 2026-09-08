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
import { ADDENDUM_COLUMNS, addendumRow, addendumHeaderRows, displayOrder } from './addendumDocument'

pdfMake.vfs = pdfFonts.vfs

/**
 * Column widths, in pdfmake units, one per ADDENDUM_COLUMNS entry.
 *
 * '*' means "share the remaining space". Justification and PURL get the stars because they
 * are the two unbounded fields -- a justification is up to 8,000 characters of operator
 * prose. Everything else is a date, a short token or a name, and giving those fixed widths
 * is what stops the two wide columns from being squeezed into a ribbon.
 */
const COLUMN_WIDTHS = [110, 55, '*', 85, 62, '*', 62, 92]

/** A cell that must never be blank in a regulatory table. */
function cell (value: unknown): string {
    if (null === value || undefined === value) return ''
    return String(value)
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
            { text: 'FDA software support addendum', style: 'title' },
            // The header block, rendered as label/value pairs from the SAME source the CSV
            // header uses. Rows the CSV emits as spacers come through with no label, so they
            // are dropped rather than rendered as empty lines.
            {
                style: 'facts',
                table: {
                    widths: [190, '*'],
                    body: headerRows
                        .filter(r => r.length && null !== r[0] && undefined !== r[0] && '' !== String(r[0]))
                        .filter(r => String(r[0]) !== 'FDA software support addendum')
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
                    }, {}, {}, {}, {}, {}, {}, {}]]
                },
                layout: 'lightHorizontalLines'
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
                { text: `${d.componentName || '(unnamed device)'} ${d.releaseVersion || ''} (${d.releaseUuid})`, alignment: 'left' },
                { text: `Generated ${d.generatedAt}`, alignment: 'center' },
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
    const slug = (d.releaseVersion || d.releaseUuid).replace(/[^A-Za-z0-9._-]+/g, '-')
    return `fda-support-addendum-${slug}.pdf`
}

/**
 * Render to a Blob rather than calling pdfmake's own .download().
 *
 * The addendum's CSV path already builds a Blob and drives an anchor; using the same idiom
 * keeps one download path to reason about, and it is the one that revokes its object URL.
 */
export function renderAddendumPdfBlob (d: AddendumData): Promise<Blob> {
    return new Promise((resolve) => {
        pdfMake.createPdf(buildAddendumDocDefinition(d) as any).getBlob((blob: Blob) => resolve(blob))
    })
}
