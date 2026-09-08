import { describe, it, expect, vi } from 'vitest'

vi.mock('pdfmake/build/pdfmake', () => ({ default: { vfs: null, createPdf: vi.fn() } }))
vi.mock('pdfmake/build/vfs_fonts', () => ({ default: { vfs: {} } }))

import { buildDeviceSupportStatementDefinition, statementBlockReason, missingProseSlots,
    componentsEndingBeforeDevice, deviceSupportStatementFileName, STATEMENT_TITLE,
    NOT_DECLARED, REQUIRED_PROSE_SLOTS } from './deviceSupportStatement'
import type { AddendumComponent, AddendumData } from './addendumData'

function comp (over: Partial<AddendumComponent> = {}): AddendumComponent {
    return {
        sbomComponentUuid: 'sc-1', name: 'log4j-core', group: 'org.apache', version: '2.14.1',
        purl: 'pkg:maven/org.apache/log4j-core@2.14.1', attestationState: 'ATTESTED',
        levelOfSupport: 'actively maintained', levelOfSupportEnum: 'ACTIVELY_MAINTAINED',
        endOfSupportDate: '2029-01-01', justification: null,
        assessedAt: '2026-09-01T00:00:00Z', ...over
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
    // draw. Written as an escape because this repo is plain-ASCII by rule.
    it('refuses text the PDF font cannot draw', () => {
        const msg = statementBlockReason(data({
            components: [comp({ name: '\u65e5\u672c\u8a9e', endOfSupportDate: '2029-01-01' })]
        })) as string
        expect(msg).not.toBeNull()
        expect(msg).toContain('cannot draw')
    })

    it('checks the font AFTER scope and slots, so the most fundamental problem is reported', () => {
        const msg = statementBlockReason(data({
            componentType: 'COMPONENT',
            components: [comp({ name: '\u65e5\u672c\u8a9e' })]
        })) as string
        expect(msg).toContain('product release')
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

    it('names the other three as addressed separately', () => {
        const s = flat(data())
        expect(s).toContain('security patches or software updates')
        expect(s).toContain('transferring cybersecurity risk')
        expect(s).toContain('increase over time')
        expect(s).toContain('addresses separately')
    })

    // Two distinct facts. An EOS-else-EOL horizon is right for a risk comparison and wrong
    // for a document read as two separate commitments.
    it('shows device EOS and EOL as two separate labelled facts', () => {
        const s = flat(data())
        expect(s).toContain('end of support')
        expect(s).toContain('end of life / end of sale')
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
            expect(t).toContain('r-1')
            expect(t).toMatch(/\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z/)
            expect(t).toContain('Page 2 of 3')
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
})
