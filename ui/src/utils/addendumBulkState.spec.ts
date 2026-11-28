import { describe, it, expect, vi } from 'vitest'

vi.mock('pdfmake/build/pdfmake', () => ({ default: { vfs: null, createPdf: vi.fn() } }))
vi.mock('pdfmake/build/vfs_fonts', () => ({ default: { vfs: {} } }))

import {
    addendumRow, addendumHeaderRows, ADDENDUM_COLUMNS, ATTESTED_BULK, SBOM_SUPPORT_PROPERTIES_LABEL,
    SBOM_SUPPORT_PROPERTIES_NOTE
} from './addendumDocument'
import { renderAddendumCsv } from './addendumCsv'
import { buildAddendumDocDefinition } from './addendumPdf'
import type { AddendumComponent, AddendumData } from './addendumData'
import { expectSwept } from './__fixtures__/fdaSweep'

/**
 * FDAW-161 test 16: the state cell of a bulk-entered live row and the header row on the SBOM's support properties,
 * with the server's FdaDocumentStrings texts (ATTESTED_BULK, SBOM_SUPPORT_PROPERTIES_LABEL and _NOTE) pinned here by
 * text, in both encodings.
 */
function comp (over: Partial<AddendumComponent> = {}): AddendumComponent {
    return {
        sbomComponentUuid: 'sc-1', name: 'log4j-core', group: 'org.apache', version: '2.14.1',
        purl: 'pkg:maven/org.apache/log4j-core@2.14.1', attestationState: 'ATTESTED',
        levelOfSupport: 'actively maintained', levelOfSupportEnum: 'ACTIVELY_MAINTAINED', endOfSupportDate: '2030-01-01',
        justification: null, assessedAt: '2026-09-01T00:00:00Z', source: 'MANUAL', bulk: false,
        endOfSupportUnknownCode: null, endOfSupportUnknownBulk: false, assertedByName: null, justifiedByName: null, ...over
    }
}

const DATA: AddendumData = {
    releaseUuid: 'r-1', releaseVersion: '1.0.0', componentName: 'Pump', componentType: 'PRODUCT',
    deviceEos: '2030-06-30', deviceEol: null, narrative: null, narrativeIsPerRelease: false, orgName: 'Acme',
    patchesMayCeaseStatement: null, riskTransferProcessRef: null, riskIncreasesNotice: null,
    totalComponents: 2, attestedComponents: 2, unassessedComponents: 0,
    components: [comp({ sbomComponentUuid: 'a', name: 'alpha', bulk: true }), comp({ sbomComponentUuid: 'b', name: 'beta' })],
    generatedAt: '2026-09-08T12:00:00Z'
} as AddendumData

const at = ADDENDUM_COLUMNS.indexOf('Attestation state')

describe('the state cell of a bulk entry', () => {
    it('the server strings, by text', () => {
        expect(ATTESTED_BULK).toBe('attested (bulk entry)')
        expect(SBOM_SUPPORT_PROPERTIES_LABEL).toBe('Support properties in the SBOM')
        expect(SBOM_SUPPORT_PROPERTIES_NOTE).toBe('In the SBOM, reliza:support:status is worked out from the recorded'
            + ' support dates alone (UNKNOWN when neither an end-of-support nor an end-of-guaranteed-support date is'
            + ' recorded); it is not the level of support. reliza:support:disclosure derived-non-attested-current-state'
            + ' says that ReARM added the SBOM\'s support properties from its records; they are not the attestation'
            + ' itself. The attested statements are the rows below, with who made each and how (Source, Attestation'
            + ' state).')
    })

    it('a live row entered in bulk, or whose unknown was, says so; a single write says attested', () => {
        expect(addendumRow(comp({ bulk: true }))[at]).toBe(ATTESTED_BULK)
        expect(addendumRow(comp({ endOfSupportDate: null, endOfSupportUnknownCode: 'NO_COMMITTED_END_OF_SUPPORT',
            endOfSupportUnknownBulk: true }))[at]).toBe(ATTESTED_BULK)
        expect(addendumRow(comp())[at]).toBe('attested')
        expect(addendumRow(comp({ bulk: true, attestationState: 'WITHDRAWN' }))[at]).toBe('withdrawn')
    })
})

describe('the header row on the SBOM support properties', () => {
    it('follows the attestation counts, before the blank row', () => {
        const rows = addendumHeaderRows(DATA)
        const i = rows.findIndex(r => r[0] === 'Components with no support attestation')
        expect(rows[i + 1]).toEqual([SBOM_SUPPORT_PROPERTIES_LABEL, SBOM_SUPPORT_PROPERTIES_NOTE])
        expect(rows[i + 2]).toEqual([])
    })

    it('both encodings carry the row and the state cell, and the CSV passes the sweep', () => {
        const csv = renderAddendumCsv(DATA)
        expectSwept(csv)
        expect(csv).toContain(SBOM_SUPPORT_PROPERTIES_LABEL)
        expect(csv).toContain(ATTESTED_BULK)
        const doc: any = buildAddendumDocDefinition(DATA)
        const tables = (doc.content as any[]).filter(c => c && c.table)
        const facts: string[][] = tables[0].table.body.map((r: any) => [String(r[0].text), String(r[1].text)])
        expect(facts).toContainEqual([SBOM_SUPPORT_PROPERTIES_LABEL, SBOM_SUPPORT_PROPERTIES_NOTE])
        const states = tables[1].table.body.slice(1).map((r: any) => String(r[at]))
        expect(states).toEqual([ATTESTED_BULK, 'attested'])
    })
})
