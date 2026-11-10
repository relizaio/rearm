import { describe, it, expect, vi } from 'vitest'
import { OVERLAY_ORG_WRITE_SENTENCE, SBOM_COMPONENT_OVERLAY_DETAIL, SET_SBOM_COMPONENT_OVERLAY, loadComponentOverlay,
    overlayEditorAccess, overlayFieldValue, overlayFormOf, overlaySendsAnything, overlayVariables,
    saveComponentOverlay, validateOverlay } from './overlayInput'
import { orgWriteSentence } from './orgWrite'

/** FDAW-15 row 14 (REQ-13): the component overlay editor's variables rule and its access. */
describe('the presence rule', () => {
    const stored = { supplier: 'Acme Corp', licence: 'MIT' }

    it('an untouched field sends null, an erased one "", a typed one its stripped value', () => {
        expect(overlayFieldValue('Acme Corp', 'Acme Corp')).toBeNull()
        expect(overlayFieldValue('  Acme Corp ', 'Acme Corp')).toBeNull()
        expect(overlayFieldValue('', 'Acme Corp')).toBe('')
        expect(overlayFieldValue('   ', 'Acme Corp')).toBe('')
        expect(overlayFieldValue('  Acme GmbH, Berlin ', 'Acme Corp')).toBe('Acme GmbH, Berlin')
        expect(overlayFieldValue('', null)).toBeNull()
        expect(overlayFieldValue('MIT', undefined)).toBe('MIT')
    })

    it('builds the variables of setSbomComponentOverlay against the stored overlay', () => {
        const form = { ...overlayFormOf(stored), licence: '', reason: ' upstream README ' }
        expect(overlayVariables('sc-1', form, stored)).toEqual({
            sbomComponentUuid: 'sc-1',
            overlay: { supplier: null, licence: '' },
            reason: 'upstream README'
        })
    })

    it('a row holding no overlay sends what was typed and null for what was left empty', () => {
        const form = { supplier: 'Acme Corp', licence: '', reason: '' }
        expect(overlayVariables('sc-1', form, null)).toEqual({
            sbomComponentUuid: 'sc-1', overlay: { supplier: 'Acme Corp', licence: null }, reason: null })
    })

    it('a form equal to the stored overlay sends nothing', () => {
        expect(overlaySendsAnything(overlayFormOf(stored), stored)).toBe(false)
        expect(overlaySendsAnything({ ...overlayFormOf(stored), reason: 'only a reason' }, stored)).toBe(false)
        expect(overlaySendsAnything({ ...overlayFormOf(stored), supplier: '' }, stored)).toBe(true)
    })

    it('a licence-only edit is a change: Save stays enabled', () => {
        expect(overlaySendsAnything({ ...overlayFormOf(stored), licence: 'Apache-2.0' }, stored)).toBe(true)
    })

    it('never sends a description or an origin: those are for vendored components', () => {
        const v = overlayVariables('sc-1', { supplier: 'x', licence: 'y', reason: '' }, null)
        expect(Object.keys(v.overlay).sort()).toEqual(['licence', 'supplier'])
    })
})

describe('the bounds', () => {
    it('refuses with the server\'s texts', () => {
        expect(validateOverlay({ supplier: 'x'.repeat(513), licence: '', reason: '' }))
            .toEqual(['supplier is longer than 512 characters'])
        expect(validateOverlay({ supplier: 'a\nb', licence: '', reason: '' }))
            .toEqual(['supplier holds a control character'])
        expect(validateOverlay({ supplier: '', licence: 'l'.repeat(513), reason: '' }))
            .toEqual(['licence is longer than 512 characters'])
        expect(validateOverlay({ supplier: '', licence: '', reason: 'r'.repeat(2049) }))
            .toEqual(['reason is longer than 2048 characters'])
        expect(validateOverlay({ supplier: 'Acme', licence: 'MIT', reason: 'line one\nline two' })).toEqual([])
    })

    it('takes a value at the bound itself', () => {
        expect(validateOverlay({ supplier: 's'.repeat(512), licence: 'l'.repeat(512), reason: 'r'.repeat(2048) }))
            .toEqual([])
    })

    it('a reason may hold a tab and a line feed, never another control character', () => {
        expect(validateOverlay({ supplier: '', licence: '', reason: 'a\tb\nc' })).toEqual([])
        expect(validateOverlay({ supplier: '', licence: '', reason: 'a\u0000b' }))
            .toEqual(['reason holds a control character'])
    })
})

describe('the editor\'s access', () => {
    it('opens read-only with the sentence for a caller without organization write', () => {
        expect(overlayEditorAccess(false)).toEqual({ readOnly: true, sentence: OVERLAY_ORG_WRITE_SENTENCE })
        expect(OVERLAY_ORG_WRITE_SENTENCE).toBe(orgWriteSentence('Editing the supplier and licence'))
    })

    it('opens enabled without the sentence for an organization writer', () => {
        expect(overlayEditorAccess(true)).toEqual({ readOnly: false, sentence: null })
    })
})

describe('the documents', () => {
    it('loads the overlay network-only through getReleaseSbomComponentGraph', async () => {
        const query = vi.fn(async (_o: any) => ({ data: { getReleaseSbomComponentGraph: { component: {
            uuid: 'sc-1', overlay: { supplier: 'Acme', licence: null, revision: 2, supplierBulkAct: 'act-1' } } } } }))
        const got = await loadComponentOverlay({ query } as any, 'rel-1', 'sc-1')
        expect(got).toEqual({ supplier: 'Acme', licence: null, revision: 2, supplierBulkAct: 'act-1' })
        const opts = (query.mock.calls[0] as any)[0]
        expect(opts.query).toBe(SBOM_COMPONENT_OVERLAY_DETAIL)
        expect(opts.variables).toEqual({ releaseUuid: 'rel-1', sbomComponentUuid: 'sc-1' })
        expect(opts.fetchPolicy).toBe('network-only')
    })

    it('answers null for a row with no overlay', async () => {
        const query = vi.fn(async () => ({ data: { getReleaseSbomComponentGraph: { component: { uuid: 'sc-1', overlay: null } } } }))
        expect(await loadComponentOverlay({ query } as any, 'rel-1', 'sc-1')).toBeNull()
    })

    it('a vanished row is an error, never an empty baseline', async () => {
        const query = vi.fn(async () => ({ data: { getReleaseSbomComponentGraph: null } }))
        await expect(loadComponentOverlay({ query } as any, 'rel-1', 'sc-1')).rejects.toThrow('sbom component not found')
    })

    it('saves through setSbomComponentOverlay with the built variables', async () => {
        const mutate = vi.fn(async (_o: any) => ({ data: { setSbomComponentOverlay: { uuid: 'sc-1',
            overlay: { supplier: 'Acme' } } } }))
        const vars = overlayVariables('sc-1', { supplier: 'Acme', licence: '', reason: '' }, null)
        expect(await saveComponentOverlay({ query: vi.fn(), mutate } as any, vars)).toEqual({ supplier: 'Acme' })
        expect((mutate.mock.calls[0] as any)[0]).toEqual({ mutation: SET_SBOM_COMPONENT_OVERLAY, variables: vars })
    })
})
