// The FDA support addendum, encoded as CSV.
//
// One of three renderers over the SAME collected data (addendumData.ts), and one of two over
// the same document definition (addendumDocument.ts). This file decides only how to ENCODE
// the rows -- what the rows say is not its call to make, or the PDF beside it would drift.

import { csvDocument } from './csv'
import type { AddendumData } from './addendumData'
import { ADDENDUM_COLUMNS, addendumRow, addendumHeaderRows, displayOrder,
    addendumFileStem } from './addendumDocument'

/**
 * Header rows padded to the table's width.
 *
 * Excel and LibreOffice tolerate ragged rows, but a strict reader does not -- pandas fails
 * outright with "Expected 1 fields in line 8, saw 2". A regulatory export that opens by
 * double-click but not in the tool a reviewer scripts against is half a document, and the
 * padding is invisible in every viewer.
 */
function padded (row: unknown[]): unknown[] {
    return row.length >= ADDENDUM_COLUMNS.length
        ? row
        : [...row, ...Array(ADDENDUM_COLUMNS.length - row.length).fill(null)]
}

/** The whole document. */
export function renderAddendumCsv (d: AddendumData): string {
    return csvDocument([
        ...addendumHeaderRows(d).map(padded),
        ADDENDUM_COLUMNS,
        ...displayOrder(d.components).map(addendumRow)
    ])
}

/** Stable, sortable, and identifies the release without needing the file to be opened. */
export function addendumFileName (d: AddendumData): string {
    return `${addendumFileStem(d)}.csv`
}
