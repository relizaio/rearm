// The FDA support addendum, as CSV.
//
// One of three renderers over the SAME collected data (addendumData.ts). This file decides
// only how to SAY the facts; it must not fetch, count or resolve anything, or the PDF and
// the Device Support Statement will disagree with it about what the release contains.

import { csvDocument } from './csv'
import type { AddendumComponent, AddendumData } from './addendumData'
import { isLiveAttestation } from './addendumData'

export const ADDENDUM_COLUMNS = [
    'Component', 'Version', 'PURL', 'Level of support', 'End of support',
    'Justification', 'Attestation state', 'Last assessed'
]

/**
 * The value stated when a component carries no live attestation.
 *
 * NOT an empty cell, and the distinction is the point of the document. Blank reads as an
 * omission -- something the exporter forgot -- whereas the honest claim is that the
 * manufacturer looked and has nothing recorded. A reviewer counting blanks cannot tell those
 * apart; this way the row says which it is.
 */
export const NOT_ASSESSED = 'not assessed'

/**
 * The value stated when a component IS attested but the assessor recorded no level.
 *
 * Distinct from NOT_ASSESSED, and the distinction is not cosmetic. A level is optional on an
 * attestation, and a justification-only attestation is precisely the L1021-1022 case this
 * document exists to carry -- the assessor looked, could not determine a level, and said
 * why. The gauge counts those rows as ATTESTED. Printing "not assessed" in the level column
 * for them produced a document whose table contradicted its own header: "10 components with
 * a support attestation" above ten rows each saying not assessed.
 */
export const LEVEL_NOT_STATED = 'not stated -- see justification'

/** Fully-qualified name: group is part of a component's identity, not decoration. */
function displayName (c: AddendumComponent): string | null {
    if (c.group && c.name) return `${c.group}:${c.name}`
    return c.name || c.group || null
}

/**
 * One component row.
 *
 * LEVEL AND JUSTIFICATION ARE ALTERNATIVES, per L1021-1022: a component with an attested
 * level states it, and one without states WHY the information cannot be given. Emitting both
 * would suggest the justification qualifies the level, when it exists precisely because
 * there is no level to give.
 *
 * A WITHDRAWN attestation is treated as not assessed -- it keeps its history but is not
 * injected into exports, so counting it here would make the addendum disagree with the BOM
 * it accompanies.
 */
export function addendumRow (c: AddendumComponent): unknown[] {
    const live = isLiveAttestation(c)
    // Two rules at once, and they are separate:
    //   LEVEL AND JUSTIFICATION ARE ALTERNATIVES (L1021-1022) -- a component with a level
    //   states it, one without states WHY, and emitting both would suggest the justification
    //   qualifies the level rather than standing in for it.
    //   A WITHDRAWN attestation states NOTHING, justification included. It is retracted and
    //   not injected into exports, and an earlier revision emitted its prose anyway --
    //   republishing a claim the manufacturer had formally withdrawn, in the one document
    //   where that matters most.
    return [
        displayName(c),
        c.version,
        c.purl,
        live ? (c.levelOfSupport || LEVEL_NOT_STATED) : NOT_ASSESSED,
        live ? c.endOfSupportDate : null,
        live && !c.levelOfSupport ? c.justification : null,
        live ? 'ATTESTED' : (c.attestationState || NOT_ASSESSED),
        live ? c.assessedAt : null
    ]
}

/**
 * The header block: what a reviewer reads before the table.
 *
 * Device EOS and EOL are stated as TWO SEPARATE FACTS. They answer different questions --
 * when patching stops, and when selling stops -- and merging them into one "support window"
 * line forces the reader to guess which a lone date is.
 *
 * The assessed / unassessed counts live HERE rather than being counted from the rows below.
 * They come from the same resolver as the on-screen gauge, so the document cannot state a
 * number the page disagrees with; addendumData refuses outright if the two ever diverge.
 */
export function addendumHeaderRows (d: AddendumData): unknown[][] {
    const rows: unknown[][] = [
        ['FDA software support addendum'],
        ['Organization', d.orgName],
        ['Device', d.componentName],
        ['Release', d.releaseVersion],
        ['Release UUID', d.releaseUuid],
        ['Generated', d.generatedAt],
        [],
        ['Device end of support (EOS)', d.deviceEos || 'not declared'],
        ['Device end of sale / end of life (EOL)', d.deviceEol || 'not declared'],
        [],
        ['Components in this release', d.totalComponents],
        ['Components with a support attestation', d.attestedComponents],
        ['Components with no support attestation', d.unassessedComponents],
        []
    ]
    if (d.narrative) {
        rows.push(['Assessment justification', d.narrative])
        rows.push(['Justification scope', d.narrativeIsPerRelease ? 'this release' : 'organization default'])
        rows.push([])
    }
    if (d.patchesMayCeaseStatement) rows.push(['Patches may cease at end of support', d.patchesMayCeaseStatement])
    if (d.riskTransferProcessRef) rows.push(['Risk-transfer process reference', d.riskTransferProcessRef])
    if (d.riskIncreasesNotice) rows.push(['Risk increases over time', d.riskIncreasesNotice])
    if (d.patchesMayCeaseStatement || d.riskTransferProcessRef || d.riskIncreasesNotice) rows.push([])
    return rows
}

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

/**
 * Stable display order, applied HERE rather than in the collector.
 *
 * The walk returns cursor (uuid) order, which is effectively random to a reviewer. Sorting
 * in the renderer keeps the collector free of presentation decisions -- the PDF may well
 * want a different order (by risk, say) and must not have to undo one imposed upstream.
 */
function displayOrder (components: AddendumComponent[]): AddendumComponent[] {
    return [...components].sort((a, b) => {
        const an = (displayName(a) || '').toLowerCase()
        const bn = (displayName(b) || '').toLowerCase()
        if (an !== bn) return an < bn ? -1 : 1
        return (a.version || '').localeCompare(b.version || '')
    })
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
    const slug = (d.releaseVersion || d.releaseUuid).replace(/[^A-Za-z0-9._-]+/g, '-')
    return `fda-support-addendum-${slug}.csv`
}
