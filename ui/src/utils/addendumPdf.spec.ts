import { describe, it, expect, vi } from 'vitest'

// pdfmake pulls its font VFS in at import time, which is a megabyte of base64 and needs a
// browser-ish global. Stubbed because nothing here renders: the whole point of returning a
// plain doc definition is that the document can be asserted without pdfmake at all.
const createPdf = vi.fn()
vi.mock('pdfmake/build/pdfmake', () => ({ default: { vfs: null, get createPdf () { return createPdf } } }))
vi.mock('pdfmake/build/vfs_fonts', () => ({ default: { vfs: {} } }))

import { buildAddendumDocDefinition, addendumPdfFileName, renderAddendumPdfBlob,
    findUnrenderableText } from './addendumPdf'
import { ADDENDUM_COLUMNS, ADDENDUM_TITLE } from './addendumDocument'
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
        releaseUuid: 'r-1', releaseVersion: '1.4.5', componentName: 'Pump', componentType: 'PRODUCT',
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

    // Asserted against the shared CONSTANT, not a copy of the literal. When this compared a
    // hardcoded string, renaming the title made the PDF print it twice -- once as the
    // heading, once as a fact row -- and this test stayed green, because it was asserting the
    // absence of a string that no longer existed anywhere.
    it('does not repeat the title inside the facts table', () => {
        const labels = factsTable(buildAddendumDocDefinition(data())).body.map((r: any) => r[0].text)
        expect(labels).not.toContain(ADDENDUM_TITLE)
        expect((buildAddendumDocDefinition(data()).content as any[])[0].text).toBe(ADDENDUM_TITLE)
    })

    it('pads the empty-state colSpan row to the column count', () => {
        const doc = buildAddendumDocDefinition(data({ components: [], totalComponents: 0, attestedComponents: 0, unassessedComponents: 0 }))
        const row = dataTable(doc).body[1]
        expect(row).toHaveLength(ADDENDUM_COLUMNS.length)
        expect(row[0].colSpan).toBe(ADDENDUM_COLUMNS.length)
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

/**
 * The pdfmake SEAM, which the doc-definition tests deliberately cannot cover.
 *
 * Mocking pdfmake is right for asserting the document -- but it means the integration with
 * pdfmake itself has no unit coverage at all, and that is exactly where this shipped a bug:
 * getBlob() is PROMISE-BASED in 0.3, an earlier revision passed it a 0.2-style callback, the
 * callback was never invoked, and the export spinner span forever with no error. The live
 * probe was the only thing that caught it.
 *
 * These two assertions close that specific hole: the promise must actually settle, and the
 * callback form must not come back.
 */
describe('renderAddendumPdfBlob', () => {
    it('resolves the blob getBlob() returns, rather than waiting on a callback', async () => {
        const fake = new Blob(['%PDF-'], { type: 'application/pdf' })
        createPdf.mockReturnValue({ getBlob: () => Promise.resolve(fake) })
        await expect(renderAddendumPdfBlob(data())).resolves.toBe(fake)
    })

    it('calls getBlob with NO arguments -- the callback form never settles in 0.3', async () => {
        const getBlob = vi.fn(() => Promise.resolve(new Blob(['%PDF-'])))
        createPdf.mockReturnValue({ getBlob })
        await renderAddendumPdfBlob(data())
        expect(getBlob).toHaveBeenCalledTimes(1)
        expect(getBlob.mock.calls[0]).toHaveLength(0)
    })
})

/**
 * FONT COVERAGE.
 *
 * pdfmake bundles Roboto only and SILENTLY DROPS a character it cannot draw -- no error, no
 * placeholder, no warning. A component named in Japanese produced an empty Component cell in
 * a regulatory document, while the CSV for the same release showed the text. Blank is the one
 * thing this document must never be ambiguous about, so the export refuses instead.
 *
 * Non-Latin samples are written as \u escapes: this repo is plain-ASCII by rule, and the CI
 * invisible-character check runs before the build.
 */
describe('findUnrenderableText', () => {
    it('passes a document that is entirely Latin', () => {
        expect(findUnrenderableText(data())).toBeNull()
    })

    it.each([
        ['accented Latin', '\u0047\u0072\u00fcnwald caf\u00e9'],
        ['Vietnamese', 'Ti\u1ebfng Vi\u1ec7t'],
        ['Cyrillic', '\u041f\u0440\u043e\u0432\u0435\u0440\u043a\u0430'],
        ['Greek', '\u0395\u03bb\u03bb\u03b7\u03bd\u03b9\u03ba\u03ac'],
        ['em dash and curly quotes', '\u2014 \u201cq\u201d']
    ])('passes %s, which Roboto draws', (_n, text) => {
        expect(findUnrenderableText(data({ narrative: text }))).toBeNull()
    })

    it.each([
        ['CJK', '\u652f\u63f4\u7d42\u4e86'],
        ['Hangul', '\ud55c\uad6d\uc5b4'],
        ['Arabic', '\u0627\u0644\u0639\u0631\u0628\u064a\u0629'],
        ['Hebrew', '\u05e2\u05d1\u05e8\u05d9\u05ea'],
        ['Thai', '\u0e44\u0e17\u0e22'],
        ['Devanagari', '\u0939\u093f\u0928\u094d\u0926\u0940'],
        ['an emoji', '\u26a0']
    ])('refuses %s, which Roboto silently drops', (_n, text) => {
        const msg = findUnrenderableText(data({ narrative: text }))
        expect(msg).not.toBeNull()
        expect(msg).toContain('CSV')
    })

    // Every cell is scanned, not just the narrative -- the first report of this was a
    // component NAME, which lives in the table rather than the header block.
    it('scans component cells, not only the header block', () => {
        const msg = findUnrenderableText(data({
            components: [comp({ name: '\u65e5\u672c\u8a9e\u30e9\u30a4\u30d6\u30e9\u30ea' })]
        }))
        expect(msg).not.toBeNull()
    })

    it('scans justification text', () => {
        const msg = findUnrenderableText(data({
            components: [comp({ levelOfSupport: null, justification: '\u4e2d\u6587' })]
        }))
        expect(msg).not.toBeNull()
    })

    // The message has to be actionable: an operator who cannot export the PDF needs to know
    // which characters and what to do instead.
    it('names the offending characters and points at the CSV', () => {
        const msg = findUnrenderableText(data({ narrative: '\u652f' })) as string
        expect(msg).toContain('\u652f')
        expect(msg).toMatch(/CSV/)
        expect(msg).toMatch(/silently dropped|blank/)
    })
})
