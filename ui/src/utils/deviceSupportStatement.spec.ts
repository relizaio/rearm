import { describe, it, expect, vi } from 'vitest'

vi.mock('pdfmake/build/pdfmake', () => ({ default: { vfs: null, createPdf: vi.fn() } }))
vi.mock('pdfmake/build/vfs_fonts', () => ({ default: { vfs: {} } }))

import { buildDeviceSupportStatementDefinition, statementBlockReason, missingProseSlots,
    componentsEndingBeforeDevice, deviceSupportStatementFileName, STATEMENT_TITLE,
    NOT_DECLARED, REQUIRED_PROSE_SLOTS, renderDeviceSupportStatementBlob, statementStrings,
    PROSE_SETTINGS_LOCATION } from './deviceSupportStatement'
import { UUID_RE } from './__fixtures__/fdaSweep'
import type { AddendumComponent, AddendumData } from './addendumData'

function comp (over: Partial<AddendumComponent> = {}): AddendumComponent {
    return {
        sbomComponentUuid: 'sc-1', name: 'log4j-core', group: 'org.apache', version: '2.14.1',
        purl: 'pkg:maven/org.apache/log4j-core@2.14.1', attestationState: 'ATTESTED',
        levelOfSupport: 'actively maintained', levelOfSupportEnum: 'ACTIVELY_MAINTAINED',
        endOfSupportDate: '2029-01-01', justification: null,
        assessedAt: '2026-09-01T00:00:00Z', source: null, bulk: false, endOfSupportUnknownCode: null, endOfSupportUnknownBulk: false,
        assertedByName: null, justifiedByName: null, ...over
    }
}

function data (over: Partial<AddendumData> = {}): AddendumData {
    return {
        releaseUuid: 'r-1', releaseVersion: '9000.1.0', componentName: 'Infusion Pump 9000',
        componentType: 'PRODUCT',
        deviceEos: '2030-06-30', deviceEol: '2033-01-01',
        narrative: 'org words', narrativeIsPerRelease: false, orgName: 'Acme',
        patchesMayCeaseStatement: 'After end of support, security patches may no longer be provided.',
        riskTransferProcessRef: 'DHF-PROC-4471 rev C',
        riskIncreasesNotice: 'Cybersecurity risk can be expected to increase after end of support.',
        totalComponents: 1, attestedComponents: 1, unassessedComponents: 0,
        components: [comp()], generatedAt: '2026-09-08T12:00:00Z', ...over
    }
}

const flat = (d: AddendumData) => JSON.stringify(buildDeviceSupportStatementDefinition(d))

describe('statementBlockReason', () => {
    it('permits a product release with all three slots authored', () => {
        expect(statementBlockReason(data())).toBeNull()
    })

    // One document per PRODUCT release. A component release is not a device, and no amount
    // of authored prose makes it one -- so scope is checked before the slots.
    it('refuses a component release, and says where to go instead', () => {
        const msg = statementBlockReason(data({ componentType: 'COMPONENT' })) as string
        expect(msg).toContain('product release')
        expect(msg).toContain('component release')
    })

    it('refuses a release whose type is unknown rather than assuming it is a device', () => {
        expect(statementBlockReason(data({ componentType: null }))).not.toBeNull()
    })

    it('checks scope BEFORE the prose slots', () => {
        const msg = statementBlockReason(data({
            componentType: 'COMPONENT', patchesMayCeaseStatement: null,
            riskTransferProcessRef: null, riskIncreasesNotice: null
        })) as string
        expect(msg).toContain('product release')
    })

    // A patient-facing document with an empty section is worse than no document: section 3's
    // "a gap beats a fabrication" is right for a submission a reviewer reads, and wrong here.
    it.each(REQUIRED_PROSE_SLOTS)('blocks when $label is empty, naming it', (slot) => {
        const msg = statementBlockReason(data({ [slot.key]: null } as any)) as string
        expect(msg).not.toBeNull()
        expect(msg).toContain(slot.label)
    })

    // FDAW-78 X4: the path named is the one that exists, as the server's DeviceSupportStatementPartGenerator names it.
    it('says where the text is written, by the full Organization Settings path (FDAW-78 X4)', () => {
        expect(PROSE_SETTINGS_LOCATION).toBe('Organization Settings > Admin Settings > General > Submission and labeling text')
        const msg = statementBlockReason(data({ patchesMayCeaseStatement: null })) as string
        expect(msg.endsWith('Add the text under Organization Settings > Admin Settings > General > Submission and labeling text.'))
            .toBe(true)
        expect(msg).not.toContain('->')
    })

    it('treats a whitespace-only slot as empty', () => {
        expect(statementBlockReason(data({ riskTransferProcessRef: '   ' }))).not.toBeNull()
    })

    it('names EVERY missing slot, not just the first', () => {
        const msg = statementBlockReason(data({
            patchesMayCeaseStatement: null, riskIncreasesNotice: null
        })) as string
        expect(msg).toContain('Patches may cease at end of support')
        expect(msg).toContain('Risk increases over time')
    })

    // The block has to be actionable, not just correct.
    it('tells the operator where to author the missing text', () => {
        const msg = statementBlockReason(data({ patchesMayCeaseStatement: null })) as string
        expect(msg).toContain('Organization Settings')
    })

    // Reused from the addendum: pdfmake ships Roboto only and silently drops what it cannot
    // draw. Written as an escape because this repo is plain-ASCII by rule. Checked on a slot
    // the document ACTUALLY PRINTS.
    it('refuses text the PDF font cannot draw', () => {
        const msg = statementBlockReason(data({
            riskIncreasesNotice: 'Risk increases \u65e5\u672c\u8a9e'
        })) as string
        expect(msg).not.toBeNull()
        expect(msg).toContain('cannot draw')
    })

    /**
     * ...and NOT on a component name, which this document no longer prints.
     *
     * The name was in the font whitelist while the ends-sooner section listed components. Once
     * that section became one fixed sentence, leaving it there turned an unrenderable glyph in
     * a component name into a HARD REFUSAL of the whole patient-facing statement over text that
     * cannot appear in it. Caught in review, not by this suite -- which had the opposite
     * assertion locked in.
     */
    it('does not refuse over a component name it will never print', () => {
        expect(statementBlockReason(data({
            components: [comp({ name: '\u65e5\u672c\u8a9e', endOfSupportDate: '2029-01-01' })]
        }))).toBeNull()
    })

    it('checks the font AFTER scope and slots, so the most fundamental problem is reported', () => {
        const msg = statementBlockReason(data({
            componentType: 'COMPONENT',
            components: [comp({ name: '\u65e5\u672c\u8a9e' })]
        })) as string
        expect(msg).toContain('product release')
    })
})

describe('the displayed version line (FDAW-74 row 20 d, e)', () => {
    const DV = 'GV-2.4.1 (Build 118)'
    const SP = 'R&D <b>"5"</b> =1+1, \'x\'; \u00fc'
    it('stands as content[2] between the version subtitle and the organization', () => {
        const without = buildDeviceSupportStatementDefinition(data()).content as any[]
        const withIt = buildDeviceSupportStatementDefinition(data({ displayedVersion: DV })).content as any[]
        expect(withIt[2]).toEqual({ text: 'Shown on the device as GV-2.4.1 (Build 118)', style: 'subtitle' })
        expect(withIt[1]).toEqual(without[1])
        expect(withIt[1].text).toBe('Infusion Pump 9000, version 9000.1.0')
        expect(withIt[3]).toEqual(without[2])
        expect(without[2].text).toBe('Acme')
        expect(flat(data())).not.toContain('Shown on the device')
        const sp = buildDeviceSupportStatementDefinition(data({ displayedVersion: SP })).content as any[]
        expect(sp[2].text).toBe('Shown on the device as ' + SP)
    })

    it('is in the font check: an undrawable character refuses, a plain value does not', () => {
        const msg = statementBlockReason(data({ displayedVersion: 'GV \u65e5\u672c\u8a9e' })) as string
        expect(msg).not.toBeNull()
        expect(msg).toContain('cannot draw')
        expect(statementBlockReason(data({ displayedVersion: DV }))).toBeNull()
        expect(statementStrings(data({ displayedVersion: DV }))).toContain(DV)
    })
})

describe('missingProseSlots', () => {
    it('is empty when all three are authored', () => {
        expect(missingProseSlots(data())).toEqual([])
    })

    it('reports labels in document order', () => {
        expect(missingProseSlots(data({
            patchesMayCeaseStatement: null, riskTransferProcessRef: null, riskIncreasesNotice: null
        }))).toEqual(REQUIRED_PROSE_SLOTS.map(s => s.label))
    })
})

describe('componentsEndingBeforeDevice', () => {
    it('lists a component whose attested EOS precedes the device EOS', () => {
        expect(componentsEndingBeforeDevice(data())).toEqual([{ name: 'log4j-core', date: '2029-01-01' }])
    })

    it('excludes one whose support outlasts the device', () => {
        expect(componentsEndingBeforeDevice(data({
            components: [comp({ endOfSupportDate: '2031-01-01' })]
        }))).toEqual([])
    })

    it('excludes one ending on the SAME day -- it does not end sooner', () => {
        expect(componentsEndingBeforeDevice(data({
            components: [comp({ endOfSupportDate: '2030-06-30' })]
        }))).toEqual([])
    })

    // Not listed is NOT a claim that it outlives the device: nobody recorded a date, and
    // asserting anything about it to a patient would be a fabrication.
    it('excludes a component with no live attestation, even with a date', () => {
        expect(componentsEndingBeforeDevice(data({
            components: [comp({ attestationState: 'WITHDRAWN' }), comp({ attestationState: null })]
        }))).toEqual([])
    })

    it('excludes an attested component with no date', () => {
        expect(componentsEndingBeforeDevice(data({
            components: [comp({ endOfSupportDate: null })]
        }))).toEqual([])
    })

    // With no device EOS there is nothing for a date to precede. Listing every dated
    // component instead would silently change what the section means.
    it('lists nothing when the device EOS is not declared', () => {
        expect(componentsEndingBeforeDevice(data({ deviceEos: null }))).toEqual([])
    })

    it('orders by date, then name', () => {
        const out = componentsEndingBeforeDevice(data({
            components: [
                comp({ name: 'zeta', endOfSupportDate: '2029-05-01' }),
                comp({ name: 'alpha', endOfSupportDate: '2029-05-01' }),
                comp({ name: 'earlier', endOfSupportDate: '2027-01-01' })
            ]
        }))
        expect(out.map(c => c.name)).toEqual(['earlier', 'alpha', 'zeta'])
    })

    it('uses the plain name, with no group prefix and no purl', () => {
        const out = componentsEndingBeforeDevice(data())
        expect(out[0].name).toBe('log4j-core')
        expect(out[0].name).not.toContain('org.apache')
    })
})

describe('buildDeviceSupportStatementDefinition', () => {
    it('returns plain JSON', () => {
        const doc = buildDeviceSupportStatementDefinition(data())
        expect(Array.isArray(doc.content)).toBe(true)
    })

    // On its face, so nobody reads shipping it as satisfying VI.A.
    it('states which ONE of the four labeling asks it covers', () => {
        const s = flat(data())
        expect(s).toContain('VI.A')
        expect(s).toContain('one of the four')
    })

    it('distinguishes what it generates from what it reproduces', () => {
        const s = flat(data())
        expect(s).toContain('generated from the manufacturer')
        expect(s).toContain("manufacturer's own words, reproduced here unchanged")
        expect(s).toContain('not generated, completed or verified')
    })

    // The risk-transfer gap is SUBSTANTIVE rather than editorial: the slot holds a reference
    // to a controlled document, so the process itself genuinely is not here.
    it('says the risk-transfer process itself is not reproduced', () => {
        expect(flat(data())).toContain('is not reproduced here')
    })

    /**
     * REGRESSION, and the reason this file exists in its current shape.
     *
     * An earlier revision declared the document "does not cover" three items and then printed
     * all three, in the same order, under their own headings -- while REFUSING to generate
     * unless the manufacturer had authored them. Every statement it produced contradicted
     * itself on its face. The two specs that were supposed to guard the coverage block
     * asserted the wrong text and stayed green through it.
     */
    it('never claims not to cover something it then prints', () => {
        const d = data()
        const s = flat(d)
        expect(s).not.toContain('does not cover')
        for (const slot of [d.patchesMayCeaseStatement, d.riskTransferProcessRef, d.riskIncreasesNotice]) {
            const hits = s.split(slot as string).length - 1
            expect(hits, `"${slot}" should appear exactly once`).toBe(1)
        }
    })

    // Two distinct facts. An EOS-else-EOL horizon is right for a risk comparison and wrong
    // for a document read as two separate commitments.
    it('shows device EOS and EOL as two separate labelled facts', () => {
        const s = flat(data())
        expect(s).toContain('Device end of support (last security update)')
        expect(s).toContain('Device end of life (last sale date)')
        // FDAW-3, B11: the old wording is gone from the rendered statement
        expect(s).not.toMatch(/end of sale/i)
        expect(s).not.toContain('(EOL)')
        expect(s).toContain('2030-06-30')
        expect(s).toContain('2033-01-01')
    })

    it('says "not declared" for a missing date rather than leaving it blank', () => {
        const s = flat(data({ deviceEos: null, deviceEol: null }))
        expect(s.match(new RegExp(NOT_DECLARED, 'g'))).toHaveLength(2)
    })

    // Plain language for a patient, caregiver or biomed reader (L1591-1592).
    it('carries no purl, no version column and no component inventory', () => {
        const s = flat(data())
        expect(s).not.toContain('pkg:')
        expect(s).not.toContain('2.14.1')
        expect(s).not.toContain('PURL')
    })

    // "412 components not assessed" conveys no risk information to a hospital biomed reader
    // and edges toward misleading under 502(a)(1). That count belongs in the addendum.
    it('carries no unassessed count and no risk verdict', () => {
        const s = flat(data({ totalComponents: 412, attestedComponents: 3, unassessedComponents: 409 }))
        expect(s).not.toContain('409')
        expect(s).not.toContain('412')
        expect(s).not.toContain('unassessed')
        expect(s).not.toContain('deviceSupportRisk')
    })

    it('omits the earlier-support section entirely when nothing qualifies', () => {
        const s = flat(data({ components: [comp({ endOfSupportDate: '2031-01-01' })] }))
        expect(s).not.toContain('ends sooner')
    })

    it('prints all three prose slots verbatim', () => {
        const d = data()
        const s = flat(d)
        expect(s).toContain(d.patchesMayCeaseStatement as string)
        expect(s).toContain(d.riskTransferProcessRef as string)
        expect(s).toContain(d.riskIncreasesNotice as string)
    })

    // The slot holds a REFERENCE to a controlled document, not the process: pasting the
    // process in creates a second, unversioned copy that drifts from the DHF original.
    it('prints the risk-transfer slot as the reference it is', () => {
        expect(flat(data())).toContain('DHF-PROC-4471 rev C')
    })

    it('never truncates: no noWrap, no ellipsis', () => {
        const s = flat(data({ riskIncreasesNotice: 'x'.repeat(8000) }))
        expect(s).not.toContain('noWrap')
        expect(s).not.toContain('ellipsis')
        expect(s).toContain('x'.repeat(8000))
    })

    describe('the footer', () => {
        const footer = (d = data()) => (buildDeviceSupportStatementDefinition(d).footer as any)(2, 3)
        it('carries device identity, a UTC instant and page N of M', () => {
            const t = footer().columns.map((c: any) => c.text).join(' | ')
            expect(t).toContain('Infusion Pump 9000')
            expect(t).not.toContain('r-1')
            expect(t).toMatch(/Generated \d{4}-\d{2}-\d{2} \d{2}:\d{2} UTC/)
            expect(t).toContain('Page 2 of 3')
        })

        // FDAW-78 row 18 (REQ-69): the statement footer prints what the server's footer prints.
        it('reads <device> <version> and the document instant, no uuid, on every page (FDAW-78 row 18)', () => {
            const uuid = '3bef7cde-2edb-47cb-aa4e-94487c2cdced'
            const cols = (d: any, page: number) => (buildDeviceSupportStatementDefinition(d).footer as any)(page, 2)
                .columns.map((c: any) => c.text)
            const base = { componentName: 'Pump', releaseVersion: '1.2.0', releaseUuid: uuid,
                generatedAt: '2026-10-20T08:15:30.123Z' }
            for (const page of [1, 2]) {
                const c = cols(data(base), page)
                expect(c[0]).toBe('Pump 1.2.0')
                expect(c[1]).toBe('Generated 2026-10-20 08:15 UTC')
                expect(c[2]).toBe('Page ' + page + ' of 2')
                expect(c.join(' ')).not.toMatch(UUID_RE)
            }
            expect(cols(data({ ...base, componentName: null }), 1)[0]).toBe('(unnamed device) 1.2.0')
            expect(cols(data({ ...base, releaseVersion: null }), 1)[0]).toBe('Pump')
            // control: the expression finds the fixture's uuid
            expect(uuid).toMatch(UUID_RE)
        })

        it('statementStrings lists no release uuid and the instant as printed (FDAW-78 row 18)', () => {
            const d = data({ releaseUuid: '3bef7cde-2edb-47cb-aa4e-94487c2cdced', generatedAt: '2026-10-20T08:15:30.123Z' })
            const strings = statementStrings(d)
            expect(strings).not.toContain(d.releaseUuid)
            expect(strings).not.toContain(d.generatedAt)
            expect(strings).toContain('2026-10-20 08:15 UTC')
        })
    })

    it('titles itself', () => {
        expect((buildDeviceSupportStatementDefinition(data()).content as any[])[0].text).toBe(STATEMENT_TITLE)
    })
})

describe('deviceSupportStatementFileName', () => {
    it('names the document and the release', () => {
        expect(deviceSupportStatementFileName(data())).toBe('device-support-statement-9000.1.0.pdf')
    })

    it('falls back rather than throwing when both version and uuid are absent', () => {
        expect(deviceSupportStatementFileName(data({ releaseVersion: null, releaseUuid: null as any })))
            .toBe('device-support-statement-release.pdf')
    })

    /**
     * Two batches of the same release carry DIFFERENT dates. Two downloads called
     * `device-support-statement-1.4.2.pdf` in one folder is one of them silently replacing
     * the other -- in a folder of regulatory documents.
     */
    it('names the delivery too, when the dates are a delivery\'s', () => {
        const name = deviceSupportStatementFileName(data({
            deviceWindowSource: { level: 'SHIPMENT', siteName: 'St Elsewhere ICU',
                shipDate: '2026-04-02', batchIdentifier: 'LOT:77' }
        }))
        expect(name).toBe('device-support-statement-9000.1.0-2026-04-02-LOT-77.pdf')
        expect(name).not.toBe(deviceSupportStatementFileName(data()))
    })

    it('stays a usable file name when the batch carries punctuation', () => {
        const name = deviceSupportStatementFileName(data({
            deviceWindowSource: { level: 'SHIPMENT', siteName: null, shipDate: null,
                batchIdentifier: 'LOT:77 / SERIAL:A9' }
        }))
        expect(name).toBe('device-support-statement-9000.1.0-LOT-77-SERIAL-A9.pdf')
        expect(name).not.toMatch(/[/:\\ ]/)
    })
})

describe('what the statement must never carry', () => {
    // The addendum's assessment narrative is written for a submission reviewer, not for a
    // patient. A regression that printed it here would pass every other test in this file.
    it('omits the technical assessment narrative', () => {
        expect(flat(data({ narrative: 'a very technical justification' })))
            .not.toContain('a very technical justification')
    })
})

describe('componentsEndingBeforeDevice, duplicate names', () => {
    // The version is deliberately not shown -- it means nothing to this reader -- so one
    // library at three versions rendered three rows saying different things about the same
    // name, which reads as the document disagreeing with itself.
    it('collapses one name to its EARLIEST date', () => {
        const out = componentsEndingBeforeDevice(data({
            components: [
                comp({ name: 'log4j-core', version: '2.14.1', endOfSupportDate: '2029-01-01' }),
                comp({ name: 'log4j-core', version: '2.15.0', endOfSupportDate: '2027-06-01' }),
                comp({ name: 'log4j-core', version: '2.16.0', endOfSupportDate: '2028-03-01' })
            ]
        }))
        expect(out).toEqual([{ name: 'log4j-core', date: '2027-06-01' }])
    })

    it('collapses exact duplicates rather than repeating a row', () => {
        expect(componentsEndingBeforeDevice(data({
            components: [comp(), comp(), comp()]
        }))).toHaveLength(1)
    })

    it('keeps genuinely different names apart', () => {
        expect(componentsEndingBeforeDevice(data({
            components: [comp({ name: 'a' }), comp({ name: 'b' })]
        }))).toHaveLength(2)
    })
})

describe('renderDeviceSupportStatementBlob', () => {
    // The builder casts the prose slots to string and would emit { text: null } into pdfmake
    // -- a silent empty section, exactly what the block exists to prevent. One guard in one
    // caller is not a guarantee when the function is exported and no vue-tsc checks callers.
    it('refuses to render a document its own preconditions forbid', async () => {
        await expect(renderDeviceSupportStatementBlob(data({ patchesMayCeaseStatement: null })))
            .rejects.toThrow(/Patches may cease/)
    })

    it('refuses to render for a component release', async () => {
        await expect(renderDeviceSupportStatementBlob(data({ componentType: 'COMPONENT' })))
            .rejects.toThrow(/product release/)
    })
})


/**
 * The "ends sooner" section states the FACT and carries no detail.
 *
 * It used to name every component and its date. On the walkthrough device that was all ten,
 * which is a component inventory by another name -- the one thing this document must not be,
 * because its audience may include patients and caregivers (FDA L1591-1592). A count is no
 * better: "10 of 10" invites a reader with no way to weigh it to conclude the device is in
 * worse shape than a "3 of 40" device that is in fact more exposed.
 *
 * The section is a deliberate deviation from plan section 7f and is KEPT: a reader told only
 * the device end-of-support date would reasonably conclude every part is supported until then.
 */
describe('the ends-sooner section is a statement, not an inventory', () => {
    const flatten = (n: any): string =>
        typeof n === 'string' ? n
            : Array.isArray(n) ? n.map(flatten).join(' ')
                : n && typeof n === 'object' ? Object.values(n).map(flatten).join(' ') : ''

    it('says the fact when a component ends sooner', () => {
        const text = flatten(buildDeviceSupportStatementDefinition(data()))
        expect(text).toMatch(/Software components whose support ends sooner/)
        expect(text).toMatch(/Some software in this device has an earlier end-of-support date/)
    })

    /**
     * Bounded at the NEXT heading. An unbounded slice runs to the end of the document and
     * picks up the later sections' own text and pdfmake's style colours, which made the
     * count assertion below fail on "#444444" -- a test that looked like it had caught
     * something and had not.
     */
    const endsSoonerSection = (d: any): string => {
        const text = flatten(buildDeviceSupportStatementDefinition(d))
        const start = text.indexOf('Software components whose support ends sooner')
        if (start < 0) return ''
        // Skip the heading's OWN style marker first. flatten() emits { text, style: 'h2' } as
        // "<heading text> h2", so searching for the next "h2" from here found the heading's
        // own and returned an EMPTY section -- against which "contains no component name" and
        // "contains no count" both passed vacuously. The revert probe caught that: restoring
        // the per-component table failed only one of the three assertions.
        const rest = text.slice(start + 'Software components whose support ends sooner'.length)
            .replace(/^\s*h2\s*/, '')
        const next = rest.indexOf('h2')
        return next < 0 ? rest : rest.slice(0, next)
    }

    it('names no component and prints no date in that section', () => {
        const section = endsSoonerSection(data())
        expect(section).not.toMatch(/log4j-core/)
        expect(section).not.toMatch(/2029-01-01/)
        expect(section).not.toMatch(/support ends \d{4}-/)
    })

    it('prints no count of affected components', () => {
        // No bare integers in the sentence: a count is the thing a lay reader cannot weigh.
        // "10 of 10" reads worse than "3 of 40" to someone with no way to compare them.
        expect(endsSoonerSection(data())).not.toMatch(/\b\d+\b/)
    })

    it('omits the section entirely when nothing ends sooner', () => {
        const text = flatten(buildDeviceSupportStatementDefinition(
            data({ components: [comp({ endOfSupportDate: null })] })))
        expect(text).not.toMatch(/Software components whose support ends sooner/)
    })
})

/**
 * WHERE THE DATES CAME FROM, in one line under them (D7).
 *
 * A reader comparing two statements for the same device model needs to know why the dates
 * differ, and "a different batch declared its own" is the answer. The line names the batch by
 * things a person can match against a delivery note -- site, ship date, batch identifier --
 * never by the word "override" or a uuid.
 */
describe('the device window provenance line', () => {
    const flat = (n: any): string =>
        typeof n === 'string' ? n
            : Array.isArray(n) ? n.map(flat).join(' ')
                : n && typeof n === 'object' ? Object.values(n).map(flat).join(' ') : ''
    const render = (over: any) => flat(buildDeviceSupportStatementDefinition(data(over)))

    it('names the product when the window is the model default', () => {
        const text = render({ deviceWindowSource: { level: 'COMPONENT', productName: 'Infusion Pump 9000' } })
        expect(text).toMatch(/declared on Infusion Pump 9000 and apply to every unit of it/)
    })

    it('names the site, ship date and batch when it came from a shipment', () => {
        const text = render({ deviceWindowSource: {
            level: 'SHIPMENT', siteName: 'St Elsewhere ICU', shipDate: '2026-04-02', batchIdentifier: 'LOT-77' } })
        expect(text).toMatch(/delivered to St Elsewhere ICU/)
        expect(text).toMatch(/on 2026-04-02/)
        expect(text).toMatch(/\(batch LOT-77\)/)
        expect(text).toMatch(/may differ from other deliveries of the same device/)
    })

    it('omits the batch identifier when none was recorded', () => {
        const text = render({ deviceWindowSource: {
            level: 'SHIPMENT', siteName: 'St Elsewhere ICU', shipDate: '2026-04-02', batchIdentifier: null } })
        expect(text).toMatch(/delivered to St Elsewhere ICU/)
        expect(text).not.toMatch(/batch/)
    })

    /** "Override" is our vocabulary, not a patient's; a uuid is not something anyone can check. */
    it('never uses the word override or prints an identifier a reader cannot match', () => {
        // A REAL uuid in the fixture. With 'r-1' this assertion was green for the wrong
        // reason: the page footer prints d.releaseUuid and always has, so a document-wide
        // "no uuid" claim is false by design and only passed because the fixture had nothing
        // uuid-shaped in it. Scoped to the provenance LINE, which is the claim
        // buildDeviceSupportStatementDefinition's provenance block actually makes -- and the
        // scope d7-window-probe.mjs already uses against the real PDF.
        const text = render({
            releaseUuid: '277c971c-687d-46eb-bded-74a55149a978',
            deviceWindowSource: {
                level: 'SHIPMENT', siteName: 'St Elsewhere ICU', shipDate: '2026-04-02',
                batchIdentifier: 'LOT-77' }
        })
        // The CONTENT carries no uuid, and since FDAW-78 (O-2) neither does the FOOTER: `flat()`
        // only walks content, so the footer is read on its own. Both stated here so neither can
        // quietly change.
        expect(text).not.toMatch(/277c971c/)
        const footerText = (buildDeviceSupportStatementDefinition(
            data({ releaseUuid: '277c971c-687d-46eb-bded-74a55149a978' })).footer as any)(1, 1)
            .columns.map((c: any) => c.text).join(' | ')
        expect(footerText).not.toMatch(/277c971c/)
        const line = text.split('\n').find(l => /delivered to/.test(l)) || ''
        expect(line).not.toBe('')
        expect(line.toLowerCase()).not.toMatch(/override/)
        expect(line).not.toMatch(/[0-9a-f]{8}-[0-9a-f]{4}-/)
    })

    /** No provenance for a fact that does not exist. */
    it('prints no line at all when no window is declared', () => {
        const text = render({ deviceWindowSource: null })
        expect(text).not.toMatch(/apply to every unit of it/)
        expect(text).not.toMatch(/may differ from other deliveries/)
    })
})
