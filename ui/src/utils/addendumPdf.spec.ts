import { describe, it, expect, vi } from 'vitest'

// pdfmake pulls its font VFS in at import time, which is a megabyte of base64 and needs a
// browser-ish global. Stubbed because nothing here renders: the whole point of returning a
// plain doc definition is that the document can be asserted without pdfmake at all.
vi.mock('pdfmake/build/pdfmake', () => ({ default: { vfs: null, createPdf: vi.fn() } }))
vi.mock('pdfmake/build/vfs_fonts', () => ({ default: { vfs: {} } }))

import { buildAddendumDocDefinition, addendumPdfFileName } from './addendumPdf'
import { ADDENDUM_COLUMNS } from './addendumDocument'
import type { AddendumComponent, AddendumData } from './addendumData'

function comp (over: Partial<AddendumComponent> = {}): AddendumComponent {
    return {
        sbomComponentUuid: 'sc-1', name: 'log4j-core', group: 'org.apache', version: '2.14.1',
        purl: 'pkg:maven/org.apache/log4j-core@2.14.1', attestationState: 'ATTESTED',
        levelOfSupport: 'actively maintained', levelOfSupportEnum: 'ACTIVELY_MAINTAINED',
        endOfSupportDate: '2030-01-01', justification: null,
        assessedAt: '2026-09-01T00:00:00Z', ...over
    }
}

function data (over: Partial<AddendumData> = {}): AddendumData {
    return {
        releaseUuid: 'r-1', releaseVersion: '1.4.5', componentName: 'Pump',
        deviceEos: '2030-06-30', deviceEol: '2033-01-01',
        narrative: 'org words', narrativeIsPerRelease: false, orgName: 'Acme',
        patchesMayCeaseStatement: null, riskTransferProcessRef: null, riskIncreasesNotice: null,
        totalComponents: 1, attestedComponents: 1, unassessedComponents: 0,
        components: [comp()], generatedAt: '2026-09-08T12:00:00Z', ...over
    }
}

const tables = (doc: any) => (doc.content as any[]).filter(c => c && c.table)
const dataTable = (doc: any) => tables(doc)[1].table
const factsTable = (doc: any) => tables(doc)[0].table

describe('buildAddendumDocDefinition', () => {
    it('returns a plain object, not a rendered document', () => {
        const doc = buildAddendumDocDefinition(data())
        expect(typeof doc).toBe('object')
        expect(Array.isArray(doc.content)).toBe(true)
    })

    it('carries the column row, in the documented order', () => {
        const header = dataTable(buildAddendumDocDefinition(data())).body[0]
        expect(header.map((c: any) => c.text)).toEqual(ADDENDUM_COLUMNS)
    })

    it('gives one width per column, so no cell is unallocated', () => {
        const t = dataTable(buildAddendumDocDefinition(data()))
        expect(t.widths).toHaveLength(ADDENDUM_COLUMNS.length)
    })

    it('repeats the column row across pages', () => {
        expect(dataTable(buildAddendumDocDefinition(data())).headerRows).toBe(1)
    })

    it('emits one body row per component plus the header', () => {
        const d = data({ components: [comp(), comp({ name: 'other' }), comp({ name: 'third' })],
            totalComponents: 3, attestedComponents: 3, unassessedComponents: 0 })
        expect(dataTable(buildAddendumDocDefinition(d)).body).toHaveLength(4)
    })

    // The most important property of this document. A justification cut short at a page
    // boundary is a regulatory statement the manufacturer did not make, and it would look
    // deliberate. pdfmake wraps by default; nothing here may turn that off.
    it('never truncates: no noWrap and no ellipsis anywhere', () => {
        const long = 'x'.repeat(9000)
        const doc = buildAddendumDocDefinition(data({
            narrative: long,
            components: [comp({ levelOfSupport: null, justification: long })]
        }))
        const json = JSON.stringify(doc)
        expect(json).not.toContain('noWrap')
        expect(json).not.toContain('ellipsis')
        // and the full text is present, not a shortened copy
        expect(json).toContain(long)
    })

    it('states the device EOS and EOL as two separate labelled facts', () => {
        const labels = factsTable(buildAddendumDocDefinition(data())).body.map((r: any) => r[0].text)
        expect(labels).toContain('Device end of support (EOS)')
        expect(labels).toContain('Device end of sale / end of life (EOL)')
    })

    it('carries the assessed and unassessed counts', () => {
        const doc = buildAddendumDocDefinition(data({ totalComponents: 10, attestedComponents: 4, unassessedComponents: 6 }))
        const rows = factsTable(doc).body
        const find = (l: string) => rows.find((r: any) => r[0].text === l)?.[1].text
        expect(find('Components with no support attestation')).toBe('6')
        expect(find('Components with a support attestation')).toBe('4')
    })

    it('labels the narrative source', () => {
        const org = factsTable(buildAddendumDocDefinition(data())).body
        expect(org.find((r: any) => r[0].text === 'Justification scope')?.[1].text).toBe('organization default')
        const rel = factsTable(buildAddendumDocDefinition(data({ narrativeIsPerRelease: true }))).body
        expect(rel.find((r: any) => r[0].text === 'Justification scope')?.[1].text).toBe('this release')
    })

    // The CSV emits blank spacer rows between header groups. Rendered as label/value pairs
    // those would be empty lines in a table with no borders -- visible gaps that read as
    // missing data.
    it('drops the CSV spacer rows rather than rendering empty lines', () => {
        const rows = factsTable(buildAddendumDocDefinition(data())).body
        expect(rows.every((r: any) => String(r[0].text).trim().length > 0)).toBe(true)
    })

    it('does not repeat the title inside the facts table', () => {
        const labels = factsTable(buildAddendumDocDefinition(data())).body.map((r: any) => r[0].text)
        expect(labels).not.toContain('FDA software support addendum')
    })

    it('renders an explicit note for a release with no components', () => {
        const doc = buildAddendumDocDefinition(data({ components: [], totalComponents: 0, attestedComponents: 0, unassessedComponents: 0 }))
        expect(JSON.stringify(doc)).toContain('no SBOM components')
    })

    describe('the footer', () => {
        const footer = (d = data()) => (buildAddendumDocDefinition(d).footer as any)(2, 7)

        // A page separated from the document must still say which release it describes;
        // these get printed and passed around.
        it('carries release identity on every page', () => {
            const texts = footer().columns.map((c: any) => c.text).join(' | ')
            expect(texts).toContain('Pump')
            expect(texts).toContain('1.4.5')
            expect(texts).toContain('r-1')
        })

        it('carries the generation timestamp as a UTC RFC-3339 instant', () => {
            const t = footer().columns.map((c: any) => c.text).join(' ')
            expect(t).toContain('2026-09-08T12:00:00Z')
            expect(t).toMatch(/\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z/)
        })

        it('numbers pages as N of M', () => {
            expect(footer().columns.map((c: any) => c.text).join(' ')).toContain('Page 2 of 7')
        })

        it('survives a release with no name or version', () => {
            const t = footer(data({ componentName: null, releaseVersion: null }))
            expect(() => JSON.stringify(t)).not.toThrow()
            expect(t.columns[0].text).toContain('r-1')
        })
    })
})

describe('addendumPdfFileName', () => {
    it('shares the CSV stem so the pair sorts together', () => {
        expect(addendumPdfFileName(data())).toBe('fda-support-addendum-1.4.5.pdf')
    })

    it('falls back to the uuid and strips anything path-unsafe', () => {
        expect(addendumPdfFileName(data({ releaseVersion: null }))).toBe('fda-support-addendum-r-1.pdf')
        expect(addendumPdfFileName(data({ releaseVersion: 'feature/x y' }))).toBe('fda-support-addendum-feature-x-y.pdf')
    })
})
