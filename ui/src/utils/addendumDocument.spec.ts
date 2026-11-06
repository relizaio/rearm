import { describe, it, expect } from 'vitest'
import { addendumRow, addendumHeaderRows, ADDENDUM_COLUMNS, ADDENDUM_TITLE, NOT_ASSESSED,
    LEVEL_NOT_STATED, addendumFileStem } from './addendumDocument'
import type { AddendumComponent, AddendumData } from './addendumData'

function comp (over: Partial<AddendumComponent> = {}): AddendumComponent {
    return {
        sbomComponentUuid: 'sc-1', name: 'log4j-core', group: 'org.apache', version: '2.14.1',
        purl: 'pkg:maven/org.apache/log4j-core@2.14.1', attestationState: 'ATTESTED',
        levelOfSupport: 'actively maintained', levelOfSupportEnum: 'ACTIVELY_MAINTAINED',
        endOfSupportDate: '2030-01-01',
        justification: null, assessedAt: '2026-09-01T00:00:00Z', source: null, bulk: false, endOfSupportUnknownCode: null, endOfSupportUnknownBulk: false,
        assertedByName: null, justifiedByName: null, ...over
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
        expect(flat).toContain('Device end of support (last security update)')
        expect(flat).toContain('Device end of life (last sale date)')
        // FDAW-3, B11: the old wording is gone from the rendered header
        for (const label of flat) {
            expect(String(label ?? '')).not.toMatch(/end of sale/i)
            expect(String(label ?? '')).not.toContain('(EOL)')
        }
    })

    it('says "not declared" for an absent device date rather than leaving it blank', () => {
        const rows = addendumHeaderRows(data({ deviceEos: null }))
        expect(rows.find(r => r[0] === 'Device end of support (last security update)')![1]).toBe('not declared')
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

describe('addendumFileStem', () => {
    // Shared by both renderers so the CSV and the PDF for one release sort together. Each
    // used to own a copy of this rule with its own hardcoded expectations, so changing one
    // passed every test while breaking the stated contract.
    it('slugs the version', () => {
        expect(addendumFileStem(data())).toBe('fda-support-addendum-1.4.5')
    })

    it('falls back to the uuid, then to a fixed stem, rather than throwing', () => {
        expect(addendumFileStem(data({ releaseVersion: null }))).toBe('fda-support-addendum-r-1')
        expect(addendumFileStem(data({ releaseVersion: null, releaseUuid: null as any })))
            .toBe('fda-support-addendum-release')
    })

    it('strips anything path-unsafe', () => {
        expect(addendumFileStem(data({ releaseVersion: 'feature/x y' })))
            .toBe('fda-support-addendum-feature-x-y')
    })

    it('is the stem both file names are built from', () => {
        expect(ADDENDUM_TITLE).toBe('FDA software support addendum')
    })
})

describe('the FDAW-14 columns', () => {
    const at = (name: string) => ADDENDUM_COLUMNS.indexOf(name)
    const unknown = (bulk: boolean) => ({ endOfSupportDate: null, endOfSupportUnknownCode: 'NO_COMMITTED_END_OF_SUPPORT',
        endOfSupportUnknownBulk: bulk })

    it('has ten columns ending Source and Asserted by, and every row has ten cells', () => {
        expect(ADDENDUM_COLUMNS).toHaveLength(10)
        expect(ADDENDUM_COLUMNS.slice(-2)).toEqual(['Source', 'Asserted by'])
        expect(addendumRow(comp())).toHaveLength(10)
    })

    it('reads the Source grammar of 3.7, the same five cases as the server', () => {
        const source = (over: Partial<AddendumComponent>) => addendumRow(comp(over))[at('Source')]
        expect(source({ source: 'MANUAL' })).toBe('manual')
        expect(source({ source: 'MANUAL', bulk: true })).toBe('manual (bulk)')
        expect(source({ source: 'MANUAL', bulk: true, ...unknown(true) })).toBe('manual (bulk); bulk justification')
        expect(source({ source: 'MANUAL', ...unknown(false) })).toBe('manual; justification')
        expect(source({ source: 'SUPPLIER' })).toBe('supplier')
        expect(source({ source: 'MANUAL', attestationState: 'WITHDRAWN' })).toBeNull()
    })

    it('reads the Asserted by grammar, the same five cases as the server', () => {
        const by = (over: Partial<AddendumComponent>) => addendumRow(comp({ source: 'MANUAL', ...over }))[at('Asserted by')]
        expect(by({ assertedByName: 'A', justifiedByName: 'B' })).toBe('A; justification: B')
        expect(by({ assertedByName: 'A', justifiedByName: null })).toBe('A')
        expect(by({ assertedByName: 'A' })).toBe('A')
        expect(by({ assertedByName: 'A', justifiedByName: '' })).toBe('A; justification: ')
        expect(by({ assertedByName: 'B', levelOfSupport: null })).toBe('B')
        expect(by({ assertedByName: null })).toBeNull()
        expect(by({ assertedByName: 'A', attestationState: 'WITHDRAWN' })).toBeNull()
    })

    it('prints not available with the code for an end-of-support unknown', () => {
        expect(addendumRow(comp(unknown(false)))[at('End of support')]).toBe('not available: NO_COMMITTED_END_OF_SUPPORT')
        expect(addendumRow(comp({ ...unknown(false), endOfSupportDate: '2030-01-01' }))[at('End of support')])
            .toBe('2030-01-01')
    })
})
