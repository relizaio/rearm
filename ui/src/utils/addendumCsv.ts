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
    return [
        displayName(c),
        c.version,
        c.purl,
        live ? (c.levelOfSupport || NOT_ASSESSED) : NOT_ASSESSED,
        live ? c.endOfSupportDate : null,
        live && c.levelOfSupport ? null : c.justification,
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

/** The whole document. */
export function renderAddendumCsv (d: AddendumData): string {
    return csvDocument([
        ...addendumHeaderRows(d),
        ADDENDUM_COLUMNS,
        ...d.components.map(addendumRow)
    ])
}

/** Stable, sortable, and identifies the release without needing the file to be opened. */
export function addendumFileName (d: AddendumData): string {
    const slug = (d.releaseVersion || d.releaseUuid).replace(/[^A-Za-z0-9._-]+/g, '-')
    return `fda-support-addendum-${slug}.csv`
}
