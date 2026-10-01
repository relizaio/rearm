// The FDA support addendum as a DOCUMENT: its columns, its rows and its header block.
//
// RENDERER-AGNOSTIC ON PURPOSE. The CSV and the PDF have to be comparable line for line,
// which they cannot be if each decides for itself what a component's Level of support cell
// says -- and those decisions are regulatory rather than typographic: level and
// justification are ALTERNATIVES under L1021-1022, a WITHDRAWN attestation states nothing at
// all, an absent fact reads "not assessed" rather than leaving a blank a reviewer would
// count as an omission. One implementation and two renderers makes parity STRUCTURAL, so
// the parity spec proves what the code already guarantees instead of comparing two
// hand-maintained copies.
//
// These lived in addendumCsv.ts for one PR. A module named "csv" exporting a PDF's row
// semantics is the kind of thing a reviewer rightly stops, so they moved before the second
// renderer arrived rather than after.

import type { AddendumComponent, AddendumData } from './addendumData'
import { isLiveAttestation } from './addendumData'

/**
 * The document's own title, owned here because every renderer needs it and one of them also
 * needs to RECOGNISE it: the PDF prints it as a heading and must then drop the matching row
 * from its facts table. When that comparison was against a copy of the literal, renaming the
 * title printed it twice -- once as a heading, once as a fact row with an empty value -- and
 * the guard test kept passing, because it asserted the absence of the OLD string.
 */
export const ADDENDUM_TITLE = 'FDA software support addendum'

/**
 * How the three org-authored labeling statements are named, wherever a document names them.
 *
 * Shared because BOTH documents name them and they must agree: the addendum labels them as
 * header rows, and the statement names them when it BLOCKS on a missing one. An operator told
 * "Risk-transfer process reference is missing" has to find that same wording in the settings
 * form and in the addendum, or the message sends them looking for something else.
 */
export const PROSE_SLOT_LABELS = {
    patchesMayCease: 'Patches may cease at end of support',
    riskTransferRef: 'Risk-transfer process reference',
    riskIncreases: 'Risk increases over time'
} as const

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
        [ADDENDUM_TITLE],
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
    if (d.patchesMayCeaseStatement) rows.push([PROSE_SLOT_LABELS.patchesMayCease, d.patchesMayCeaseStatement])
    if (d.riskTransferProcessRef) rows.push([PROSE_SLOT_LABELS.riskTransferRef, d.riskTransferProcessRef])
    if (d.riskIncreasesNotice) rows.push([PROSE_SLOT_LABELS.riskIncreases, d.riskIncreasesNotice])
    if (d.patchesMayCeaseStatement || d.riskTransferProcessRef || d.riskIncreasesNotice) rows.push([])
    return rows
}

/**
 * Stable display order, applied HERE rather than in the collector.
 *
 * The walk returns cursor (uuid) order, which is effectively random to a reviewer. Sorting
 * in the renderer keeps the collector free of presentation decisions -- the PDF may well
 * want a different order (by risk, say) and must not have to undo one imposed upstream.
 */
export function displayOrder (components: AddendumComponent[]): AddendumComponent[] {
    return [...components].sort((a, b) => {
        const an = (displayName(a) || '').toLowerCase()
        const bn = (displayName(b) || '').toLowerCase()
        if (an !== bn) return an < bn ? -1 : 1
        return (a.version || '').localeCompare(b.version || '')
    })
}

/**
 * The filename stem both renderers share, so the CSV and the PDF for one release sort
 * together in a downloads folder.
 *
 * Shared rather than duplicated: each renderer had its own copy of this expression, and each
 * spec hardcoded its own expected filename, so changing the sanitising rule in one and not
 * the other would have passed every test while breaking the stated contract.
 *
 * Falls back through version, then uuid, then a fixed stem. The last step matters: both
 * copies threw a TypeError when version AND uuid were absent, turning a nameless release
 * into a failed export rather than an awkwardly named file.
 */
export function addendumFileStem (d: AddendumData): string {
    return `fda-support-addendum-${releaseSlug(d)}`
}

/**
 * The release's identity as a filename-safe slug, shared by EVERY document.
 *
 * Split out of addendumFileStem when the Device Support Statement arrived with its own
 * character-for-character copy of this rule -- the same duplication the previous PR removed
 * from between the CSV and the PDF, reappearing the moment a third document was added,
 * because the stem baked in a prefix only two of the three wanted.
 *
 * Falls back through version, then uuid, then a fixed word. The last step matters: an earlier
 * copy threw a TypeError when both were absent, turning a nameless release into a failed
 * export rather than an awkwardly named file.
 */
export function releaseSlug (d: AddendumData): string {
    const raw = d.releaseVersion || d.releaseUuid || 'release'
    return String(raw).replace(/[^A-Za-z0-9._-]+/g, '-') || 'release'
}
