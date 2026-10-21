// @vitest-environment happy-dom
//
// The SBOM profile editor (task TEA-30, design 4.3 rows 12 to 15): the pre-fill and the source and
// effective lines, the tooltips, the support-injection rule, the conflict alert and the mode radio,
// a FOLLOW row, the save and the create mode, and the delete with the server's refusal shown as
// text in the object form of SweetAlert2. The GraphQL client and Swal are mocked.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { NSelect } from 'naive-ui'

const query = vi.fn()
const mutate = vi.fn()
const fire = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
vi.mock('sweetalert2', () => ({ default: { fire: (...a: any[]) => fire(...a) } }))

const { default: Editor } = await import('./SbomProfileEditor.vue')
const { default: FieldLabel } = await import('./FieldLabel.vue')
const { SBOM_FIELD_HELP, SBOM_PROFILE_FIELDS, sbomBuiltInDefaults, sbomProfileInput } = await import('@/utils/sbomProfile')

const P1 = { uuid: 'p1', name: 'Payments', type: 'PERSPECTIVE' }
const P2 = { uuid: 'p2', name: 'Retail', type: 'PERSPECTIVE' }
const profile = (changes: Record<string, any> = {}) => ({ ...sbomBuiltInDefaults(), name: 'top', revision: 2, ...changes })
const resolved = (source: string, p: any, sourceObject: string | null = null) =>
    ({ status: 'RESOLVED', source, sourceObject, profile: p, conflictingPerspectives: [] })

function view (changes: Record<string, any> = {}) {
    const parent = resolved('ORGANIZATION', profile({ dependencyDepth: 'TOP_LEVEL_ONLY', structure: 'HIERARCHICAL' }))
    return { stored: null, effective: parent, parent, supportInjection: 'ENABLED', ...changes }
}

const operation = (opts: any) => (opts.query ?? opts.mutation)?.definitions?.[0]?.name?.value

async function mountWith (v: any, props: Record<string, any> = {}) {
    query.mockResolvedValue({ data: { sbomProfileEditorView: v } })
    const w = mount(Editor, { props: { orgUuid: 'o1', scope: 'COMPONENT', objectUuid: 'c1', name: 'top', isWritable: true,
        installationType: 'SAAS', perspectiveOptions: [{ label: 'Payments', value: 'p1' }, { label: 'Retail', value: 'p2' }],
        ...props } })
    await flushPromises()
    return w
}

const select = (w: any, testid: string) =>
    w.findAllComponents(NSelect).find((s: any) => s.attributes('data-testid') === testid)

const SELECT_TESTIDS: Record<string, string> = {
    dependencyDepth: 'sbom-dependency-depth', optionalDependencies: 'sbom-optional-dependencies', structure: 'sbom-structure',
    sources: 'sbom-sources', excludedCoverage: 'sbom-excluded-coverage', supportMetadata: 'sbom-support-metadata',
    internalMetadata: 'sbom-internal-metadata', vulnerabilityDocuments: 'sbom-vulnerability-documents',
}

describe('SbomProfileEditor', () => {
    beforeEach(() => {
        query.mockReset()
        mutate.mockReset()
        fire.mockReset()
        mutate.mockResolvedValue({ data: { saveSbomProfile: { name: 'top' }, deleteSbomProfile: true } })
    })

    it('row 12: pre-fills from the organization r2, says so, offers no delete, and carries every tooltip', async () => {
        const w = await mountWith(view())
        expect(query.mock.calls[0][0].variables).toEqual({ org: 'o1', scope: 'COMPONENT', object: 'c1', name: 'top' })
        expect(operation(query.mock.calls[0][0])).toBe('SbomProfileEditorView')
        expect(select(w, 'sbom-dependency-depth').props('value')).toBe('TOP_LEVEL_ONLY')
        expect(select(w, 'sbom-structure').props('value')).toBe('HIERARCHICAL')
        expect(w.find('[data-testid="sbom-source"]').text()).toContain('pre-filled from organization, revision 2')
        expect(w.find('[data-testid="sbom-effective"]').text()).toBe('Effective here: organization, revision 2')
        expect(w.find('[data-testid="sbom-delete"]').exists()).toBe(false)
        const labels = w.findAllComponents(FieldLabel)
        for (const f of SBOM_PROFILE_FIELDS) {
            expect(select(w, SELECT_TESTIDS[f]), f).toBeDefined()
            expect(labels.some((l: any) => l.props('tip') === SBOM_FIELD_HELP[f]), f).toBe(true)
        }
        expect(select(w, 'sbom-vulnerability-documents').props('disabled')).toBe(true)
    })

    it('row 12: support injection DISABLED keeps support metadata at EXCLUDE, disabled, with the reason', async () => {
        const w = await mountWith(view({ supportInjection: 'DISABLED',
            parent: resolved('ORGANIZATION', profile({ supportMetadata: 'INCLUDE' })) }))
        expect(select(w, 'sbom-support-metadata').props('disabled')).toBe(true)
        expect(select(w, 'sbom-support-metadata').props('value')).toBe('EXCLUDE')
        expect(w.find('[data-testid="sbom-support-metadata-reason"]').exists()).toBe(true)
    })

    it('row 13: a CONFLICT parent names both perspectives; the mode radio is Pro only', async () => {
        const conflicted = view({ parent: { status: 'CONFLICT', source: null, sourceObject: null, profile: null,
            conflictingPerspectives: [P1, P2] } })
        const pro = await mountWith(conflicted)
        const alert = pro.find('[data-testid="sbom-conflict"]')
        expect(alert.text()).toContain('Payments')
        expect(alert.text()).toContain('Retail')
        expect(pro.find('[data-testid="sbom-mode"]').exists()).toBe(true)
        const ce = await mountWith(conflicted, { installationType: 'OSS' })
        expect(ce.find('[data-testid="sbom-mode"]').exists()).toBe(false)
    })

    it('row 13: a stored FOLLOW row shows its perspective and no fields; OVERRIDE brings the parent-filled fields', async () => {
        const w = await mountWith(view({ stored: { name: 'top', mode: 'FOLLOW_PERSPECTIVE', followedPerspective: 'p2' } }))
        expect(select(w, 'sbom-follow-perspective').props('value')).toBe('p2')
        expect(w.find('[data-testid="sbom-fields"]').exists()).toBe(false)
        await w.findAll('[data-testid="sbom-mode"] input[type="radio"]')
            .find((i: any) => (i.element as HTMLInputElement).value === 'OVERRIDE')!.setValue(true)
        await flushPromises()
        expect(w.find('[data-testid="sbom-fields"]').exists()).toBe(true)
        expect(select(w, 'sbom-dependency-depth').props('value')).toBe('TOP_LEVEL_ONLY')
    })

    it('row 14: Save sends the input of the form and emits saved', async () => {
        const w = await mountWith(view())
        await select(w, 'sbom-optional-dependencies').vm.$emit('update:value', 'EXCLUDE')
        await w.find('[data-testid="sbom-save"]').trigger('click')
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(1)
        const vars = mutate.mock.calls[0][0].variables
        expect(operation(mutate.mock.calls[0][0])).toBe('SaveSbomProfile')
        const expectedForm = { ...sbomBuiltInDefaults(), mode: 'OVERRIDE', dependencyDepth: 'TOP_LEVEL_ONLY',
            structure: 'HIERARCHICAL', optionalDependencies: 'EXCLUDE' }
        expect(vars).toEqual({ org: 'o1', scope: 'COMPONENT', object: 'c1', name: 'top',
            profile: sbomProfileInput(expectedForm, 'COMPONENT') })
        expect(w.emitted('saved')).toHaveLength(1)
    })

    it('row 14: a refused save shows the parsed text in the object form, one argument, and emits nothing', async () => {
        const w = await mountWith(view())
        mutate.mockRejectedValueOnce(new Error('supportMetadata INCLUDE is refused while support injection is DISABLED'))
        await w.find('[data-testid="sbom-save"]').trigger('click')
        await flushPromises()
        expect(fire).toHaveBeenCalledTimes(1)
        expect(fire.mock.calls[0]).toHaveLength(1)
        expect(fire.mock.calls[0][0]).toEqual({ title: 'Error!',
            text: 'supportMetadata INCLUDE is refused while support injection is DISABLED', icon: 'error' })
        expect(fire.mock.calls[0][0]).not.toHaveProperty('html')
        expect(w.emitted('saved')).toBeUndefined()
    })

    it('row 14: create mode disables Save for a bad name and saves the typed one', async () => {
        const w = await mountWith(view(), { name: null })
        expect(query).not.toHaveBeenCalled()
        const input = w.find('[data-testid="sbom-name"] input')
        await input.setValue('bad name')
        await flushPromises()
        expect(w.find('[data-testid="sbom-name-error"]').exists()).toBe(true)
        expect(w.find('[data-testid="sbom-save"]').attributes('disabled')).toBeDefined()
        await input.setValue('top')
        await input.trigger('blur')
        await flushPromises()
        expect(w.find('[data-testid="sbom-name-error"]').exists()).toBe(false)
        expect(w.find('[data-testid="sbom-save"]').attributes('disabled')).toBeUndefined()
        expect(query.mock.calls.at(-1)![0].variables.name).toBe('top')
        await w.find('[data-testid="sbom-save"]').trigger('click')
        await flushPromises()
        expect(mutate.mock.calls[0][0].variables.name).toBe('top')
    })

    it('row 15: Delete confirms with the default text; cancel sends nothing; confirm deletes and emits removed', async () => {
        const w = await mountWith(view({ stored: profile({ name: 'default' }) }),
            { scope: 'ORGANIZATION', objectUuid: null, name: 'default' })
        fire.mockResolvedValueOnce({ isConfirmed: false })
        await w.find('[data-testid="sbom-delete"]').trigger('click')
        await flushPromises()
        expect(fire.mock.calls[0]).toHaveLength(1)
        expect(fire.mock.calls[0][0].title).toBe('Delete SBOM profile?')
        expect(fire.mock.calls[0][0].text).toContain('This scope then resolves default from its parent')
        expect(mutate).not.toHaveBeenCalled()

        fire.mockResolvedValueOnce({ isConfirmed: true })
        await w.find('[data-testid="sbom-delete"]').trigger('click')
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(operation(mutate.mock.calls[0][0])).toBe('DeleteSbomProfile')
        expect(mutate.mock.calls[0][0].variables).toEqual({ org: 'o1', scope: 'ORGANIZATION', object: null, name: 'default' })
        expect(w.emitted('removed')).toEqual([['default']])
    })

    it('row 15: a refusal naming a component with markup shows verbatim as text, one object argument, and emits nothing', async () => {
        const sentence = 'SBOM profile top of the organization cannot be deleted: the FDA_PREMARKET package settings of '
            + 'component <img src=x onerror=alert(1)> name it and it would no longer resolve there; point them at another profile first'
        const w = await mountWith(view({ stored: profile() }), { scope: 'ORGANIZATION', objectUuid: null })
        fire.mockResolvedValueOnce({ isConfirmed: true })
        mutate.mockRejectedValueOnce(new Error(sentence))
        await w.find('[data-testid="sbom-delete"]').trigger('click')
        await flushPromises()
        expect(fire).toHaveBeenCalledTimes(2)
        expect(fire.mock.calls[1]).toHaveLength(1)
        expect(typeof fire.mock.calls[1][0]).toBe('object')
        expect(fire.mock.calls[1][0]).toEqual({ title: 'Error!', text: sentence, icon: 'error' })
        expect(fire.mock.calls.every(c => c.length === 1 && !('html' in c[0]))).toBe(true)
        expect(w.emitted('removed')).toBeUndefined()
    })

    it('a read-only editor says so and offers no Save', async () => {
        const w = await mountWith(view(), { isWritable: false })
        expect(w.text()).toContain('Read-only: changing SBOM profiles needs admin rights here.')
        expect(w.find('[data-testid="sbom-save"]').exists()).toBe(false)
    })
})
