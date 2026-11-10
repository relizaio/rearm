import { describe, it, expect, vi } from 'vitest'
import { DESCRIBE_VENDORED_COMPONENT, RELEASE_UNIDENTIFIED_COMPONENTS, REMOVE_VENDORED_DESCRIPTION,
    VENDORED_DESCRIPTIONS_BY_NAME, VENDORED_IDENTIFIER, VENDORED_ORG_WRITE_SENTENCE, copyVendoredDescription,
    describeVendored, describeVendoredVariables, loadUnidentifiedComponents, loadVendoredSupportDetail,
    openVendoredEditor, removeVendored, validateVendoredDescribe, vendoredDescribeFormOf, vendoredPanelTitle,
    vendoredRemoveSentence, vendoredRowActions, vendoredStateTag } from './vendoredComponents'
import type { VendoredRow } from './vendoredComponents'
import { useAttestationForm } from './useAttestationForm'
import { orgWriteSentence } from './orgWrite'

const RELEASE = 'rel-1'
const KEY = 'pkg:generic/org-1/libdosecalc@2.3'

function row (over: Partial<VendoredRow> = {}): VendoredRow {
    return { key: null, name: 'libdosecalc', version: '2.3', group: null, described: false, describable: true,
        refusal: null, overlay: null, component: null, ...over }
}

const ATTESTED = { uuid: 'sc-v', attestationState: 'ATTESTED', attestedLevelOfSupport: 'ACTIVELY_MAINTAINED',
    justification: 'vendor confirms maintenance in writing', supportParty: 'THIRD_PARTY',
    endOfGuaranteedSupportDate: null, endOfSupportDate: null, endOfLifeDate: null, endOfSupportUnknown: null,
    supportMilestones: [] }

/** A client whose releaseUnidentifiedComponents answers `rows`, and whose other reads answer as given. */
function client (rows: VendoredRow[], extra: Record<string, unknown> = {}) {
    const query = vi.fn(async (opts: any) => {
        if (opts.query === RELEASE_UNIDENTIFIED_COMPONENTS) return { data: { releaseUnidentifiedComponents: rows } }
        if (opts.query === VENDORED_IDENTIFIER) return { data: { vendoredIdentifier: KEY } }
        if (opts.query === VENDORED_DESCRIPTIONS_BY_NAME) return { data: { vendoredDescriptionsByName: extra.copies ?? [] } }
        return { data: {} }
    })
    const mutate = vi.fn(async (_opts: any) => ({ data: { describeVendoredComponent: { key: KEY } } }))
    return { c: { query, mutate } as any, query, mutate }
}

const docName = (doc: any): string => doc.definitions[0].selectionSet.selections[0].name.value
const sentQueries = (query: any): string[] => query.mock.calls.map((call: any) => docName(call[0].query))

/** FDAW-15 row 15 (REQ-14): the panel. */
describe('the panel list', () => {
    it('sends one releaseUnidentifiedComponents per load and answers its rows', async () => {
        const rows = [row(), row({ name: 'libother', version: null })]
        const { c, query } = client(rows)
        expect(await loadUnidentifiedComponents(c, RELEASE)).toEqual(rows)
        expect(query).toHaveBeenCalledTimes(1)
        expect(sentQueries(query)).toEqual(['releaseUnidentifiedComponents'])
        expect((query.mock.calls[0] as any)[0].variables).toEqual({ releaseUuid: RELEASE })
    })

    it('counts every listed node in its title, a describable false one too (F-6)', () => {
        const rows = [row(), row({ name: 'x'.repeat(513), describable: false, refusal: 'name is longer than 512 characters' })]
        expect(vendoredPanelTitle(rows)).toBe('Components without an identifier \u00b7 2')
        expect(vendoredPanelTitle([])).toBe('Components without an identifier \u00b7 0')
    })
})

/** FDAW-51 3.3.5 (F-5): assessed means ATTESTED only. */
describe('the state tag', () => {
    it.each([
        ['not described, no component', row(), 'not described'],
        ['not described, with a component', row({ component: { ...ATTESTED } }), 'not described'],
        ['described, no component', row({ described: true }), 'described'],
        ['described, state null', row({ described: true, component: { ...ATTESTED, attestationState: null } }), 'described'],
        ['described, WITHDRAWN', row({ described: true, component: { ...ATTESTED, attestationState: 'WITHDRAWN' } }), 'described'],
        ['described, ATTESTED', row({ described: true, component: { ...ATTESTED } }), 'described, assessed']
    ])('%s', (_name, r, tag) => {
        expect(vendoredStateTag(r)).toBe(tag)
    })
})

/** REQ-13 for the panel, FDAW-51 3.3.2: none for a reader, disabled with the sentence without org write. */
describe('the row actions', () => {
    it('a reader is offered no action', () => {
        const a = vendoredRowActions(row({ described: true }), false, false)
        expect([a.describe.shown, a.attest.shown, a.remove.shown]).toEqual([false, false, false])
    })

    it('a product-scoped writer sees Describe, Attest and Remove disabled', () => {
        const a = vendoredRowActions(row({ described: true }), true, false)
        expect([a.describe, a.attest, a.remove]).toEqual([
            { shown: true, disabled: true }, { shown: true, disabled: true }, { shown: true, disabled: true }])
        expect(VENDORED_ORG_WRITE_SENTENCE).toBe(orgWriteSentence('Describing a vendored component'))
    })

    it('an organization writer has them enabled; Attest waits for a description, Remove shows only once described', () => {
        const fresh = vendoredRowActions(row(), true, true)
        expect(fresh.describe).toEqual({ shown: true, disabled: false })
        expect(fresh.attest).toEqual({ shown: true, disabled: true })
        expect(fresh.remove.shown).toBe(false)
        const described = vendoredRowActions(row({ described: true }), true, true)
        expect(described.attest).toEqual({ shown: true, disabled: false })
        expect(described.remove).toEqual({ shown: true, disabled: false })
    })

    it('a describable false row shows Describe disabled with the refusal and no Attest (F-6)', () => {
        const a = vendoredRowActions(row({ describable: false, refusal: 'version holds a control character' }), true, true)
        expect(a.describe).toEqual({ shown: true, disabled: true })
        expect(a.refusal).toBe('version holds a control character')
        expect(a.attest.shown).toBe(false)
        expect(a.remove.shown).toBe(false)
    })
})

describe('Describe', () => {
    it('fetches vendoredIdentifier with the node\'s name and version and shows it', async () => {
        const { c, query } = client([])
        const seed = await openVendoredEditor(c, RELEASE, row({ described: true }), true)
        expect(seed.identifier).toBe(KEY)
        expect(sentQueries(query)).toEqual(['vendoredIdentifier'])
        expect((query.mock.calls[0] as any)[0].variables).toEqual({ releaseUuid: RELEASE, name: 'libdosecalc', version: '2.3' })
    })

    it('for an undescribed node also fetches vendoredDescriptionsByName and offers the copy sources', async () => {
        const copies = [{ uuid: 'ov-1', name: 'libdosecalc', version: '2.2', origin: 'in-house fork',
            supplier: 'Acme', licence: 'MIT', description: 'dose calculation' }]
        const { c, query } = client([], { copies })
        const seed = await openVendoredEditor(c, RELEASE, row(), true)
        expect(sentQueries(query)).toEqual(['vendoredIdentifier', 'vendoredDescriptionsByName'])
        expect((query.mock.calls[1] as any)[0].variables).toEqual({ releaseUuid: RELEASE, name: 'libdosecalc' })
        expect(seed.copySources).toEqual(copies)
        expect(seed.readOnly).toBe(false)
    })

    it('with no rows back the picker has nothing to offer', async () => {
        const { c } = client([], { copies: [] })
        expect((await openVendoredEditor(c, RELEASE, row(), true)).copySources).toEqual([])
    })

    it('without organization write the read-only editor sends no vendoredDescriptionsByName and shows no picker (F-17)', async () => {
        const { c, query } = client([], { copies: [{ uuid: 'ov-1' }] })
        const seed = await openVendoredEditor(c, RELEASE, row(), false)
        expect(sentQueries(query)).not.toContain('vendoredDescriptionsByName')
        expect(seed.copySources).toEqual([])
        expect(seed.readOnly).toBe(true)
    })

    it('Copy from pre-fills the four fields; Save sends the node\'s own name and version and no reference to the source (F-7)', async () => {
        const source = { uuid: 'ov-1', name: 'libdosecalc', version: '2.2', origin: 'in-house fork',
            supplier: 'Acme', licence: 'MIT', description: 'dose calculation' }
        const form = vendoredDescribeFormOf(null)
        copyVendoredDescription(form, source)
        expect([form.origin, form.supplier, form.licence, form.description])
            .toEqual(['in-house fork', 'Acme', 'MIT', 'dose calculation'])
        const vars = describeVendoredVariables(RELEASE, row({ name: 'libdosecalc', version: '3.1' }), form, null)
        expect(vars).toEqual({ releaseUuid: RELEASE, name: 'libdosecalc', version: '3.1',
            overlay: { origin: 'in-house fork', supplier: 'Acme', licence: 'MIT', description: 'dose calculation' },
            reason: null })
        expect(JSON.stringify(vars)).not.toContain('ov-1')
        expect(JSON.stringify(vars)).not.toContain('2.2')
    })

    it('Save sends describeVendoredComponent with releaseUuid, name, version and the four fields, never a key or purl', async () => {
        const { c, mutate } = client([])
        const form = { origin: 'vendored from upstream', supplier: 'Acme', licence: 'MIT', description: 'd', reason: 'r' }
        await describeVendored(c, describeVendoredVariables(RELEASE, row(), form, null))
        const opts = (mutate.mock.calls[0] as any)[0]
        expect(opts.mutation).toBe(DESCRIBE_VENDORED_COMPONENT)
        expect(Object.keys(opts.variables).sort()).toEqual(['name', 'overlay', 'reason', 'releaseUuid', 'version'])
        expect(Object.keys(opts.variables.overlay).sort()).toEqual(['description', 'licence', 'origin', 'supplier'])
        expect(JSON.stringify(opts.variables)).not.toContain('pkg:')
    })

    it('a described node\'s editor patches against its stored description', () => {
        const stored = { origin: 'fork', supplier: 'Acme', licence: 'MIT', description: 'd' }
        const form = { ...vendoredDescribeFormOf(stored), licence: '' }
        expect(describeVendoredVariables(RELEASE, row({ described: true }), form, stored).overlay)
            .toEqual({ origin: null, supplier: null, licence: '', description: null })
    })

    it('an empty description is refused before any call', () => {
        expect(validateVendoredDescribe(vendoredDescribeFormOf(null)))
            .toContain('a description must say something: supply an origin, a supplier, a licence or a description')
        expect(validateVendoredDescribe({ ...vendoredDescribeFormOf(null), origin: 'fork' })).toEqual([])
    })
})

describe('Remove description', () => {
    it('requires a reason and sends nothing without one', async () => {
        const { c, mutate } = client([])
        await expect(removeVendored(c, RELEASE, row({ described: true }), '  ')).rejects
            .toThrow('removing a description needs a reason')
        expect(mutate).not.toHaveBeenCalled()
    })

    it('shows the consequence sentence', () => {
        expect(vendoredRemoveSentence(row())).toBe('Removing the description makes libdosecalc 2.3 unidentified again in'
            + ' the next export, and an open draft must be rebuilt before Finalize. Its support attestation, if any,'
            + ' is kept and reunited if the component is described again.')
    })

    it('sends removeVendoredDescription with the node\'s name, version and the reason', async () => {
        const { c, mutate } = client([])
        await removeVendored(c, RELEASE, row({ described: true, version: null }), ' superseded ')
        expect((mutate.mock.calls[0] as any)[0]).toEqual({ mutation: REMOVE_VENDORED_DESCRIPTION,
            variables: { releaseUuid: RELEASE, name: 'libdosecalc', version: null, reason: 'superseded' } })
    })
})

/** FDAW-51 3.3.1 (F-1): the vendored Attest seed. */
describe('the vendored Attest seed', () => {
    it('sends one network-only releaseUnidentifiedComponents and no getReleaseSbomComponentGraph', async () => {
        const { c, query } = client([row({ described: true, component: { ...ATTESTED } })])
        await loadVendoredSupportDetail(c, RELEASE, 'libdosecalc', '2.3')
        expect(query).toHaveBeenCalledTimes(1)
        expect(sentQueries(query)).toEqual(['releaseUnidentifiedComponents'])
        expect((query.mock.calls[0] as any)[0].fetchPolicy).toBe('network-only')
    })

    it('answers the attested component, and open() of it seeds the level and the justification', async () => {
        const { c } = client([row({ name: 'other' }), row({ described: true, component: { ...ATTESTED } })])
        const res = await loadVendoredSupportDetail(c, RELEASE, 'libdosecalc', '2.3')
        expect(res).toEqual({ kind: 'ok', attestation: ATTESTED })
        const f = useAttestationForm()
        f.open(res.attestation)
        expect(f.form.levelOfSupport).toBe('ACTIVELY_MAINTAINED')
        expect(f.form.justification).toBe('vendor confirms maintenance in writing')
    })

    it('a WITHDRAWN seed re-asserts, and the vendored variables carry releaseUuid, name and version and no uuid', async () => {
        const withdrawn = { ...ATTESTED, attestationState: 'WITHDRAWN' }
        const { c } = client([row({ described: true, component: withdrawn })])
        const res = await loadVendoredSupportDetail(c, RELEASE, 'libdosecalc', '2.3')
        const f = useAttestationForm()
        f.open(res.attestation)
        expect(f.isUnRetract()).toBe(true)
        expect(f.form.state).toBe('ATTESTED')
        f.form.reason = 'the withdrawal was a mistake'
        const sets = f.variableSets({ kind: 'vendored', releaseUuid: RELEASE, name: 'libdosecalc', version: '2.3' })
        expect(sets).toHaveLength(1)
        expect(sets[0]).toMatchObject({ releaseUuid: RELEASE, name: 'libdosecalc', version: '2.3', state: 'ATTESTED' })
        expect(sets[0]).not.toHaveProperty('sbomComponentUuid')
    })

    it('matches a null version by null and not by ""', async () => {
        const { c } = client([row({ version: null, described: true, component: { ...ATTESTED } })])
        expect((await loadVendoredSupportDetail(c, RELEASE, 'libdosecalc', null)).attestation).toEqual(ATTESTED)
        expect((await loadVendoredSupportDetail(c, RELEASE, 'libdosecalc', '')).attestation).toBeNull()
    })

    it('a described row with component null and a missing row both answer null', async () => {
        const { c } = client([row({ described: true, component: null })])
        expect(await loadVendoredSupportDetail(c, RELEASE, 'libdosecalc', '2.3')).toEqual({ kind: 'ok', attestation: null })
        expect(await loadVendoredSupportDetail(c, RELEASE, 'absent', '1.0')).toEqual({ kind: 'ok', attestation: null })
    })
})
