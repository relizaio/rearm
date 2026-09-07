import { describe, it, expect } from 'vitest'
import { addendumRow, addendumHeaderRows, renderAddendumCsv, addendumFileName,
    ADDENDUM_COLUMNS, NOT_ASSESSED, LEVEL_NOT_STATED } from './addendumCsv'
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
        releaseUuid: 'r-1', releaseVersion: '1.4.5', componentName: 'Pump',
        deviceEos: '2030-06-30', deviceEol: '2033-01-01',
        narrative: 'org words', narrativeIsPerRelease: false, orgName: 'Acme',
        patchesMayCeaseStatement: null, riskTransferProcessRef: null, riskIncreasesNotice: null,
        totalComponents: 1, attestedComponents: 1, unassessedComponents: 0,
        components: [comp()], generatedAt: '2026-09-07T12:00:00Z', ...over
    }
}

describe('addendumRow', () => {
    it('states the FDA phrase verbatim for an attested component', () => {
        expect(addendumRow(comp())[3]).toBe('actively maintained')
    })

    it('qualifies the name with its group', () => {
        expect(addendumRow(comp())[0]).toBe('org.apache:log4j-core')
        expect(addendumRow(comp({ group: null }))[0]).toBe('log4j-core')
    })

    // L1021-1022: a level and a justification are ALTERNATIVES. Emitting both suggests the
    // justification qualifies the level, when it exists because there is no level to give.
    it('emits the level and no justification when a level is attested', () => {
        const row = addendumRow(comp({ justification: 'checked upstream' }))
        expect(row[3]).toBe('actively maintained')
        expect(row[5]).toBeNull()
    })

    it('emits the justification when there is no level', () => {
        const row = addendumRow(comp({ levelOfSupport: null, justification: 'no upstream date published' }))
        expect(row[5]).toBe('no upstream date published')
    })

    // REGRESSION: a level is OPTIONAL on an attestation, and a justification-only
    // attestation is exactly the L1021-1022 case. Printing "not assessed" for it made the
    // table contradict its own header -- "10 with a support attestation" above ten rows
    // each reading not assessed.
    it('distinguishes an attested component with no level from an unassessed one', () => {
        expect(addendumRow(comp({ levelOfSupport: null }))[3]).toBe(LEVEL_NOT_STATED)
        expect(addendumRow(comp({ attestationState: null, levelOfSupport: null }))[3]).toBe(NOT_ASSESSED)
        expect(LEVEL_NOT_STATED).not.toBe(NOT_ASSESSED)
    })

    // A retracted attestation is not injected into exports. Emitting its justification would
    // republish a claim the manufacturer formally withdrew.
    it('states nothing at all for a WITHDRAWN component, justification included', () => {
        const row = addendumRow(comp({ attestationState: 'WITHDRAWN', levelOfSupport: null,
            justification: 'retracted reasoning' }))
        expect(row[5]).toBeNull()
    })

    // Blank would read as an omission -- something the exporter forgot. The honest claim is
    // that nothing is recorded, and the row has to say which it is.
    it('says "not assessed" rather than leaving the cell blank', () => {
        const row = addendumRow(comp({ attestationState: null, levelOfSupport: null }))
        expect(row[3]).toBe(NOT_ASSESSED)
        expect(row[6]).toBe(NOT_ASSESSED)
    })

    // A WITHDRAWN attestation is not injected into exports, so treating it as live here
    // would make the addendum disagree with the BOM it accompanies.
    it('treats WITHDRAWN as not assessed and suppresses its stale dates', () => {
        const row = addendumRow(comp({ attestationState: 'WITHDRAWN' }))
        expect(row[3]).toBe(NOT_ASSESSED)
        expect(row[4]).toBeNull()
        expect(row[6]).toBe('WITHDRAWN')
        expect(row[7]).toBeNull()
    })

    it('has exactly one cell per declared column', () => {
        expect(addendumRow(comp())).toHaveLength(ADDENDUM_COLUMNS.length)
        expect(addendumRow(comp({ attestationState: null }))).toHaveLength(ADDENDUM_COLUMNS.length)
    })
})

describe('addendumHeaderRows', () => {
    // Two questions, two facts: when patching stops, and when selling stops. One merged
    // "support window" cell forces the reader to guess which a lone date is.
    it('states device EOS and EOL as two separate labelled facts', () => {
        const flat = addendumHeaderRows(data()).map(r => r[0])
        expect(flat).toContain('Device end of support (EOS)')
        expect(flat).toContain('Device end of sale / end of life (EOL)')
    })

    it('says "not declared" for an absent device date rather than leaving it blank', () => {
        const rows = addendumHeaderRows(data({ deviceEos: null }))
        expect(rows.find(r => r[0] === 'Device end of support (EOS)')![1]).toBe('not declared')
    })

    it('carries the assessed and unassessed counts', () => {
        const rows = addendumHeaderRows(data({ totalComponents: 10, attestedComponents: 4, unassessedComponents: 6 }))
        expect(rows.find(r => r[0] === 'Components with no support attestation')![1]).toBe(6)
        expect(rows.find(r => r[0] === 'Components with a support attestation')![1]).toBe(4)
    })

    it('states the narrative scope beside the text', () => {
        const org = addendumHeaderRows(data())
        expect(org.find(r => r[0] === 'Justification scope')![1]).toBe('organization default')
        const rel = addendumHeaderRows(data({ narrativeIsPerRelease: true }))
        expect(rel.find(r => r[0] === 'Justification scope')![1]).toBe('this release')
    })

    // A blank "Assessment justification" heading reads as "we had nothing to say" rather
    // than "nobody has written this yet".
    it('omits the justification block entirely when neither level has one', () => {
        const rows = addendumHeaderRows(data({ narrative: null }))
        expect(rows.map(r => r[0])).not.toContain('Assessment justification')
        expect(rows.map(r => r[0])).not.toContain('Justification scope')
    })

    it('includes each labeling statement only when authored', () => {
        expect(addendumHeaderRows(data()).map(r => r[0]))
            .not.toContain('Patches may cease at end of support')
        expect(addendumHeaderRows(data({ patchesMayCeaseStatement: 'patches stop' })).map(r => r[0]))
            .toContain('Patches may cease at end of support')
    })
})

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
