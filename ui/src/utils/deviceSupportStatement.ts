// The Device Support Statement: FDA labeling section VI.A, customer-facing.
//
// The third renderer over addendumData, and the one written for a DIFFERENT READER. FDA
// L1591-1592 says the audience "might include patients or caregivers with limited technical
// knowledge", so this is the one artifact here that must read as plain language rather than
// as a component inventory. That single constraint decides most of what follows: no purls, no
// inventory table, no unassessed count, no risk verdict.
//
// The unassessed count is excluded deliberately, not by omission. "412 components not
// assessed" conveys no risk information to a hospital biomed reader and edges toward
// misleading under 502(a)(1). It belongs in the addendum, which a reviewer reads.

import pdfMake from 'pdfmake/build/pdfmake'
import pdfFonts from 'pdfmake/build/vfs_fonts'
import type { AddendumComponent, AddendumData } from './addendumData'
import { isLiveAttestation } from './addendumData'
import { fontCoverageRefusal } from './pdfFontCoverage'

pdfMake.vfs = pdfFonts.vfs

export const STATEMENT_TITLE = 'Device software support statement'

/** How the document names a date nobody has declared. Never blank, never omitted. */
export const NOT_DECLARED = 'not declared'

/**
 * The three org-level prose slots this document cannot be generated without.
 *
 * REQUIRED, and the reason is specific to this document. Section 3's "a gap beats a
 * fabrication" is right for a submission, where a reviewer expects gaps -- but on a
 * patient-facing statement a silent gap is itself the misleading thing under 502(a)(1). So an
 * empty slot BLOCKS generation with the slot named, rather than producing a document with an
 * empty section that reads as though the manufacturer had nothing to say.
 *
 * `label` is what the UI names when it blocks; `field` is where the operator fixes it.
 */
export const REQUIRED_PROSE_SLOTS: Array<{ key: keyof AddendumData, label: string }> = [
    { key: 'patchesMayCeaseStatement', label: 'Patches may cease at end of support' },
    { key: 'riskTransferProcessRef', label: 'Risk-transfer process reference' },
    { key: 'riskIncreasesNotice', label: 'Risk increases over time' }
]

/** Where an operator goes to author them. Named in the block message so it is actionable. */
export const PROSE_SETTINGS_LOCATION = 'Organization Settings -> FDA submission and labeling text'

/** The slots that are missing, by label, in the order the document would print them. */
export function missingProseSlots (d: AddendumData): string[] {
    return REQUIRED_PROSE_SLOTS
        .filter(s => !String(d[s.key] ?? '').trim())
        .map(s => s.label)
}

/**
 * Why this statement cannot be generated, or null when it can.
 *
 * One function for every refusal so the caller cannot check some and forget others, and so
 * the order is deliberate: SCOPE first (a component release is not a device and no amount of
 * authored prose makes it one), then the required slots, then font coverage.
 */
export function statementBlockReason (d: AddendumData): string | null {
    if (d.componentType !== 'PRODUCT') {
        return 'The device support statement describes a DEVICE, so it is generated from a'
            + ' product release. This release is a component release; open the product release'
            + ' that ships it and generate the statement there.'
    }
    const missing = missingProseSlots(d)
    if (missing.length) {
        return `This statement cannot be generated until the following organization text is`
            + ` authored: ${missing.join('; ')}.`
            + ` A patient-facing document with an empty section is worse than no document,`
            + ` so nothing is produced. Add the text under ${PROSE_SETTINGS_LOCATION}.`
    }
    return fontCoverageRefusal(statementStrings(d), 'device support statement')
}

/**
 * Components whose ATTESTED end of support falls BEFORE the device's.
 *
 * The only component information this document carries, and only as recorded facts -- a name
 * and a date. Nothing derived: no risk verdict, no ranking, no count of everything else.
 *
 * A component with no LIVE attestation is not listed. That is not the same as saying it
 * outlives the device -- it means nobody has recorded a date, and asserting anything about it
 * to a patient would be a fabrication. The addendum is where "what we do not know" is stated.
 *
 * When the device EOS is not declared there is nothing for a component date to precede, so
 * the list is empty rather than "every dated component" -- which would silently change what
 * the section means.
 */
export function componentsEndingBeforeDevice (d: AddendumData): Array<{ name: string, date: string }> {
    if (!d.deviceEos) return []
    return d.components
        .filter(c => isLiveAttestation(c) && c.endOfSupportDate && c.endOfSupportDate < (d.deviceEos as string))
        .map(c => ({ name: displayName(c), date: c.endOfSupportDate as string }))
        .sort((a, b) => (a.date === b.date ? a.name.localeCompare(b.name) : a.date.localeCompare(b.date)))
}

/** Plain name for a plain-language document: no group prefix, no purl. */
function displayName (c: AddendumComponent): string {
    return c.name || c.group || '(unnamed component)'
}

/** Every string the document prints, for the font-coverage check. */
function statementStrings (d: AddendumData): Array<string | null | undefined> {
    return [
        d.componentName, d.releaseVersion, d.orgName,
        d.patchesMayCeaseStatement, d.riskTransferProcessRef, d.riskIncreasesNotice,
        ...componentsEndingBeforeDevice(d).flatMap(c => [c.name, c.date])
    ]
}

/**
 * What this document covers, and -- equally important -- what it does not.
 *
 * FDA labeling VI.A lists FOUR asks. This document answers ONE of them, and says so on its
 * face so nobody reads shipping it as satisfying VI.A. The other three are the manufacturer's
 * own commitments and process; under section 3 there is no product-shipped default text for
 * any of them, which is exactly why they are org-authored prose rather than generated.
 */
const COVERAGE_STATEMENT = 'This document provides end-of-support and end-of-life information'
    + ' for this device and its software, which is one of the four items FDA labeling guidance'
    + ' section VI.A suggests may be included in labeling.'
const NOT_COVERED = [
    'That the manufacturer may no longer be able to provide security patches or software'
        + ' updates after end of support.',
    'The manufacturer\'s pre-established process for transferring cybersecurity risk if this'
        + ' device remains in service past end of support.',
    'That cybersecurity risk to users can be expected to increase over time.'
]

/**
 * The document definition: plain JSON, no rendering, so the spec asserts structure directly.
 *
 * CALLERS MUST CHECK statementBlockReason FIRST. This function assumes it may generate; it
 * does not re-check, because a builder that silently produced a partial document when its
 * preconditions failed is precisely what the block exists to prevent.
 */
export function buildDeviceSupportStatementDefinition (d: AddendumData): Record<string, unknown> {
    const early = componentsEndingBeforeDevice(d)
    const content: unknown[] = [
        { text: STATEMENT_TITLE, style: 'title' },
        { text: `${d.componentName || 'This device'}${d.releaseVersion ? `, version ${d.releaseVersion}` : ''}`, style: 'subtitle' },
        { text: d.orgName || '', style: 'subtitle', margin: [0, 0, 0, 14] },

        { text: 'What this document covers', style: 'h2' },
        { text: COVERAGE_STATEMENT, style: 'body' },
        { text: 'It does not cover the following, which the manufacturer addresses separately:', style: 'body' },
        { ul: NOT_COVERED, style: 'body', margin: [0, 0, 0, 14] },

        { text: 'Support dates for this device', style: 'h2' },
        {
            style: 'facts',
            table: {
                widths: [230, '*'],
                // TWO DISTINCT FACTS, never merged and never routed through a
                // support-horizon fallback. An EOS-else-EOL rule is right for a risk
                // comparison and wrong here: this document is read as two separate
                // commitments, and collapsing them would answer a question nobody asked.
                body: [
                    [{ text: 'Software support ends (end of support)', bold: true },
                        { text: d.deviceEos || NOT_DECLARED }],
                    [{ text: 'Device end of life / end of sale', bold: true },
                        { text: d.deviceEol || NOT_DECLARED }]
                ]
            },
            layout: 'noBorders',
            margin: [0, 0, 0, 14]
        }
    ]

    if (early.length) {
        content.push({ text: 'Software components whose support ends sooner', style: 'h2' })
        content.push({
            text: 'The manufacturer has recorded an earlier end-of-support date for the'
                + ' following software in this device:',
            style: 'body'
        })
        content.push({
            style: 'facts',
            table: {
                widths: [230, '*'],
                body: early.map(c => [{ text: c.name }, { text: `support ends ${c.date}` }])
            },
            layout: 'noBorders',
            margin: [0, 0, 0, 14]
        })
    }

    content.push({ text: 'After end of support', style: 'h2' })
    content.push({ text: d.patchesMayCeaseStatement as string, style: 'body' })
    content.push({ text: 'If this device remains in service past end of support', style: 'h2' })
    content.push({ text: d.riskTransferProcessRef as string, style: 'body' })
    content.push({ text: 'Cybersecurity risk over time', style: 'h2' })
    content.push({ text: d.riskIncreasesNotice as string, style: 'body' })

    return {
        pageSize: 'A4',
        pageMargins: [56, 56, 56, 52],
        content,
        footer: (currentPage: number, pageCount: number) => ({
            style: 'footer',
            margin: [56, 10, 56, 0],
            columns: [
                { text: `${d.componentName || '(unnamed device)'} ${d.releaseVersion || ''} (${d.releaseUuid})`, alignment: 'left' },
                { text: `Generated ${d.generatedAt}`, alignment: 'center' },
                { text: `Page ${currentPage} of ${pageCount}`, alignment: 'right' }
            ]
        }),
        styles: {
            title: { fontSize: 18, bold: true, margin: [0, 0, 0, 4] },
            subtitle: { fontSize: 11, color: '#444444' },
            h2: { fontSize: 12, bold: true, margin: [0, 10, 0, 4] },
            body: { fontSize: 10, margin: [0, 0, 0, 6], lineHeight: 1.25 },
            facts: { fontSize: 10 },
            footer: { fontSize: 7, color: '#666666' }
        },
        defaultStyle: { fontSize: 10 }
    }
}

export function deviceSupportStatementFileName (d: AddendumData): string {
    const raw = d.releaseVersion || d.releaseUuid || 'release'
    const slug = String(raw).replace(/[^A-Za-z0-9._-]+/g, '-')
    return `device-support-statement-${slug || 'release'}.pdf`
}

export async function renderDeviceSupportStatementBlob (d: AddendumData): Promise<Blob> {
    return pdfMake.createPdf(buildDeviceSupportStatementDefinition(d) as any).getBlob()
}
