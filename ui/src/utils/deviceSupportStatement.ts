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
//
// A DELIBERATE DEVIATION FROM PLAN SECTION 7f, KEPT. The plan describes this document as
// carrying the device dates and the manufacturer's labeling text and nothing else; the
// "Software components whose support ends sooner" section below is not in it. It stays
// because a reader told only the device's end-of-support date would reasonably conclude that
// every part of the device is supported until then, which is false whenever a component's
// window closes earlier -- and that is precisely the gap section 524B exists to close. What
// the plan's constraint correctly rules out is the DETAIL, so the section states the fact in
// one sentence and carries no component names, no dates and no count. An operator walkthrough
// found it listing all ten components, which was a component inventory by another name.

import pdfMake from 'pdfmake/build/pdfmake'
import pdfFonts from 'pdfmake/build/vfs_fonts'
import type { AddendumComponent, AddendumData } from './addendumData'
import { isLiveAttestation } from './addendumData'
import { releaseSlug, PROSE_SLOT_LABELS } from './addendumDocument'
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
    { key: 'patchesMayCeaseStatement', label: PROSE_SLOT_LABELS.patchesMayCease },
    { key: 'riskTransferProcessRef', label: PROSE_SLOT_LABELS.riskTransferRef },
    { key: 'riskIncreasesNotice', label: PROSE_SLOT_LABELS.riskIncreases }
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
 * Used ONLY to decide whether the ends-sooner section appears. Its names and dates are no
 * longer printed: the section states the fact in one sentence and carries no component text,
 * no dates and no count (see the header). So this is a presence test, and the values it
 * returns must not be rendered or fed to the font-coverage check without revisiting that.
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
    // COLLAPSED BY NAME, keeping the EARLIEST date. The version is deliberately not shown --
    // it means nothing to this reader -- which makes one library at three versions three rows
    // saying different things about the same name, reading as the document disagreeing with
    // itself. The earliest date is the one that matters: it is when this reader first loses
    // supported software.
    const earliest = new Map<string, string>()
    for (const c of d.components) {
        if (!isLiveAttestation(c) || !c.endOfSupportDate) continue
        if (!(c.endOfSupportDate < (d.deviceEos as string))) continue
        const name = displayName(c)
        const seen = earliest.get(name)
        if (!seen || c.endOfSupportDate < seen) earliest.set(name, c.endOfSupportDate)
    }
    return [...earliest.entries()]
        .map(([name, date]) => ({ name, date }))
        .sort((a, b) => (a.date === b.date ? a.name.localeCompare(b.name) : a.date.localeCompare(b.date)))
}

/** Plain name for a plain-language document: no group prefix, no purl. */
function displayName (c: AddendumComponent): string {
    return c.name || c.group || '(unnamed component)'
}

/** Every string the document prints, for the font-coverage check. */
function statementStrings (d: AddendumData): Array<string | null | undefined> {
    return [
        STATEMENT_TITLE, NOT_DECLARED,
        d.componentName, d.releaseVersion, d.orgName,
        // Rendered in the facts table and the footer. Server-generated today -- dates, a
        // uuid, an ISO instant -- so unreachable in practice, but the invariant this function
        // states is "every string the document prints", and the next field added to the
        // footer would otherwise escape the whitelist silently.
        d.deviceEos, d.deviceEol, d.releaseUuid, d.generatedAt,
        d.patchesMayCeaseStatement, d.riskTransferProcessRef, d.riskIncreasesNotice
        // NO component names or dates. They were listed here when the ends-sooner section
        // printed them; it now prints one fixed sentence and no component text at all. Left in
        // place, this made an unrenderable glyph in a component NAME a HARD REFUSAL of the
        // whole patient-facing statement, over a string that can no longer appear in it --
        // a check that had stopped describing the document it guards.
    ]
}

/**
 * What this document covers, and -- equally important -- what it does not.
 *
 * FDA labeling VI.A lists FOUR asks. Exactly one of them -- end-of-support and end-of-life
 * information -- is GENERATED here from the manufacturer's recorded data. The other three are
 * reproduced verbatim from text the manufacturer authored; this document neither generates
 * nor checks them.
 *
 * THAT DISTINCTION IS THE WHOLE POINT, and an earlier revision destroyed it: it said the
 * document "does not cover" the other three and then printed all three, in the same order,
 * under their own headings -- while REFUSING to generate at all unless the manufacturer had
 * authored them. Every statement it produced contradicted itself on its face, which is
 * precisely the misleading-labeling risk under 502(a)(1) this module exists to avoid.
 *
 * The risk-transfer item gets its own sentence because there the gap is substantive rather
 * than editorial: the slot holds a REFERENCE to a controlled DHF document, so the process
 * itself genuinely is not in this document and a reader must not infer otherwise.
 */
const COVERAGE_GENERATED = 'The support dates below are generated from the manufacturer\'s'
    + ' records. They answer one of the four items FDA labeling guidance section VI.A suggests'
    + ' may be included in labeling, and it is the only one of the four this document generates.'
const COVERAGE_AUTHORED = 'The three statements that follow the dates are the manufacturer\'s'
    + ' own words, reproduced here unchanged. They are not generated, completed or verified by'
    + ' the system that produced this document.'
const COVERAGE_REFERENCE = 'Where this document refers to the manufacturer\'s process for'
    + ' transferring risk, it gives a reference to that controlled document. The process itself'
    + ' is held separately and is not reproduced here.'

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
        { text: COVERAGE_GENERATED, style: 'body' },
        { text: COVERAGE_AUTHORED, style: 'body' },
        { text: COVERAGE_REFERENCE, style: 'body', margin: [0, 0, 0, 14] },

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
        // ONE SENTENCE, NO LIST AND NO COUNT.
        //
        // This section used to name every component and its date. On the walkthrough device
        // that was all ten of them, which is a component inventory by another name -- the one
        // thing this document is not supposed to carry, because its audience may include
        // patients and caregivers. A count is no better: "10 of 10" invites a reader with no
        // way to weigh it to conclude the device is in worse shape than a "3 of 40" device
        // that happens to be more exposed. The addendum is where per-component detail belongs,
        // and it is a different document for a different reader.
        //
        // The fact still has to be stated -- a reader is entitled to know the device window is
        // not the whole story -- so what remains is the statement itself, unquantified.
        content.push({
            text: 'Some software in this device has an earlier end-of-support date than the'
                + ' device itself. Ask the manufacturer for the support addendum if you need'
                + ' the details for a specific component.',
            style: 'body',
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
    return `device-support-statement-${releaseSlug(d)}.pdf`
}

export async function renderDeviceSupportStatementBlob (d: AddendumData): Promise<Blob> {
    // Enforced HERE as well as at the call site. The builder casts the prose slots to string
    // and would emit { text: null } into pdfmake -- a silent empty section, exactly what the
    // block exists to prevent. One guard in one caller is not a guarantee when the function
    // is exported and nothing type-checks the template that calls it.
    const blocked = statementBlockReason(d)
    if (blocked) throw new Error(blocked)
    return pdfMake.createPdf(buildDeviceSupportStatementDefinition(d) as any).getBlob()
}
