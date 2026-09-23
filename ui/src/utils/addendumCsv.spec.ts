import { describe, it, expect } from 'vitest'
import { renderAddendumCsv, addendumFileName } from './addendumCsv'
import { ADDENDUM_COLUMNS } from './addendumDocument'
import type { AddendumComponent, AddendumData } from './addendumData'

function comp (over: Partial<AddendumComponent> = {}): AddendumComponent {
    return {
        sbomComponentUuid: 'sc-1', name: 'log4j-core', group: 'org.apache', version: '2.14.1',
        purl: 'pkg:maven/org.apache/log4j-core@2.14.1', attestationState: 'ATTESTED',
        levelOfSupport: 'actively maintained', levelOfSupportEnum: 'ACTIVELY_MAINTAINED',
        endOfSupportDate: '2030-01-01',
        justification: null, assessedAt: '2026-09-01T00:00:00Z', ...over
    }
}

function data (over: Partial<AddendumData> = {}): AddendumData {
    return {
        releaseUuid: 'r-1', releaseVersion: '1.4.5', componentName: 'Pump', componentType: 'PRODUCT',
        deviceEos: '2030-06-30', deviceEol: '2033-01-01',
        narrative: 'org words', narrativeIsPerRelease: false, orgName: 'Acme',
        patchesMayCeaseStatement: null, riskTransferProcessRef: null, riskIncreasesNotice: null,
        totalComponents: 1, attestedComponents: 1, unassessedComponents: 0,
        components: [comp()], generatedAt: '2026-09-07T12:00:00Z', ...over
    }
}

describe('renderAddendumCsv', () => {
    it('emits the header block, the column row, then one row per component', () => {
        const csv = renderAddendumCsv(data({ components: [comp(), comp({ name: 'other' })] }))
        // slice(1) drops the UTF-8 BOM, which is part of the document, not of the first cell
        const lines = csv.slice(1).split('\r\n')
        // padded to the table width, so a strict reader (pandas) can parse the whole file
        expect(lines[0]).toBe('FDA software support addendum' + ','.repeat(ADDENDUM_COLUMNS.length - 1))
        expect(lines).toContain(ADDENDUM_COLUMNS.join(','))
        const idx = lines.indexOf(ADDENDUM_COLUMNS.join(','))
        expect(lines.slice(idx + 1).filter(l => l.length).length).toBe(2)
    })

    // THE probe assertion, asserted here too so it does not depend on a browser: the number
    // of data rows must equal the total the header states.
    it('emits exactly totalComponents data rows', () => {
        const comps = Array.from({ length: 7 }, (_, i) => comp({ sbomComponentUuid: `sc-${i}` }))
        const csv = renderAddendumCsv(data({ components: comps, totalComponents: 7, attestedComponents: 7, unassessedComponents: 0 }))
        const lines = csv.split('\r\n')
        const idx = lines.indexOf(ADDENDUM_COLUMNS.join(','))
        expect(lines.slice(idx + 1).filter(l => l.length).length).toBe(7)
    })

    // The prose fields are up to 8,000 characters of operator free text. A quoting failure
    // here shifts every column on the row.
    it('escapes a narrative containing commas, quotes and newlines', () => {
        const csv = renderAddendumCsv(data({ narrative: 'We checked, then\nsaid "no date".' }))
        expect(csv).toContain('"We checked, then\nsaid ""no date""."')
    })

    it('neutralises a formula planted in a justification', () => {
        const csv = renderAddendumCsv(data({
            components: [comp({ levelOfSupport: null, justification: '=HYPERLINK("http://x","ok")' })]
        }))
        expect(csv).toContain("'=HYPERLINK")
    })

    it('renders an empty release as a header block and a column row only', () => {
        const csv = renderAddendumCsv(data({ components: [], totalComponents: 0, attestedComponents: 0, unassessedComponents: 0 }))
        const lines = csv.split('\r\n')
        const idx = lines.indexOf(ADDENDUM_COLUMNS.join(','))
        expect(idx).toBeGreaterThan(-1)
        expect(lines.slice(idx + 1).filter(l => l.length).length).toBe(0)
    })
})

describe('addendumFileName', () => {
    it('identifies the release without the file being opened', () => {
        expect(addendumFileName(data())).toBe('fda-support-addendum-1.4.5.csv')
    })

    it('falls back to the uuid and strips anything path-unsafe', () => {
        expect(addendumFileName(data({ releaseVersion: null }))).toBe('fda-support-addendum-r-1.csv')
        expect(addendumFileName(data({ releaseVersion: 'feature/x y' }))).toBe('fda-support-addendum-feature-x-y.csv')
    })
})
