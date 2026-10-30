// @vitest-environment happy-dom
//
// The TEA profile editor (task TEA-2, design 4.8 cases 42-47): the pre-fill and its source line, the
// conflict alert and the mode radio, the PUBLIC banner, the confirmation before a PUBLIC save, the
// fields an editor may not change, and a FOLLOW row. Task TEA-9 (design 4.5 cases 25-29): every
// save and removal first asks the server's exposure dry-run and confirms on its answer, and an
// archived object shows its notice. The Inherit mode (UI only): with no stored row the editor opens
// in INHERIT, read-only and without Save; INHERIT over a stored row asks "Inherit again?" (naive's
// dialog) and removes the row; at COMPONENT scope with perspectives a muted hint under its source line
// says inheriting can stop with a conflict. The GraphQL client, Swal and naive's dialog are mocked; the query mock
// answers by document.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { readFileSync } from 'fs'
import { resolve } from 'path'
import { NAlert, NSelect } from 'naive-ui'

const query = vi.fn()
const mutate = vi.fn()
const fire = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
vi.mock('sweetalert2', () => ({ default: { fire: (...a: any[]) => fire(...a) } }))
const dialogWarning = vi.fn()
vi.mock('naive-ui', async (importOriginal) => {
    const actual: any = await importOriginal()
    return { ...actual, useDialog: () => ({ warning: dialogWarning }) }
})

const { default: Editor } = await import('./TeaProfileEditor.vue')
const { teaBuiltInDefaults, TEA_PROFILE_FIELDS } = await import('@/utils/teaProfile')

const P1 = { uuid: 'p1', name: 'Payments', type: 'PERSPECTIVE' }
const P2 = { uuid: 'p2', name: 'Retail', type: 'PERSPECTIVE' }
const profile = (changes: Record<string, any> = {}) => ({ ...teaBuiltInDefaults(), uuid: 'row', ...changes })
const resolved = (source: string, p: any, sourceObject: string | null = null) =>
    ({ status: 'RESOLVED', source, sourceObject, profile: p, conflictingPerspectives: [] })

function view (changes: Record<string, any> = {}) {
    return {
        stored: null,
        effective: resolved('ORGANIZATION', profile({ publishing: 'ENABLED' })),
        parent: resolved('ORGANIZATION', profile({ publishing: 'ENABLED', minimumLifecycle: 'READY_TO_SHIP' })),
        publishedReleases: 0,
        supportInjection: 'ENABLED',
        ...changes,
    }
}

const exposure = (before: string, after: string, path: string, changes: Record<string, any> = {}) =>
    ({ before, after, path, afterSource: null, afterSourceObject: null, publishedReleases: 0, ...changes })
const NONE = exposure('PRIVATE', 'PRIVATE', 'NONE')

const operation = (opts: any) => opts.query?.definitions?.[0]?.name?.value
const exposureCalls = () => query.mock.calls.filter(c => operation(c[0]) === 'TeaProfileExposureChange')

// The SBOM profile catalog at the editor's scope (TEA-30): the picker's entries.
const entry = (name: string, status = 'RESOLVED', changes: Record<string, any> = {}) => ({ name, resolution: {
    status, source: status === 'RESOLVED' ? 'ORGANIZATION' : null, sourceObject: null,
    profile: status === 'RESOLVED' ? { name, revision: 1 } : null, conflictingPerspectives: [], ...changes } })
let catalogAnswer: any = [entry('default'), entry('top')]

/** The editor view for the view query; the exposure answer (or a rejection) for the dry-run; the catalog. */
function answers (v: any, x: any = NONE) {
    query.mockImplementation(async (opts: any) => {
        if (operation(opts) === 'TeaProfileExposureChange') {
            if (x instanceof Error) throw x
            return { data: { teaProfileExposureChange: x } }
        }
        if (operation(opts) === 'SbomProfileCatalog') {
            if (catalogAnswer instanceof Error) throw catalogAnswer
            return { data: { sbomProfileCatalog: catalogAnswer } }
        }
        return { data: { teaProfileEditorView: v } }
    })
}
const calls = (name: string) => query.mock.calls.filter(c => operation(c[0]) === name)

async function mountWith (v: any, props: Record<string, any> = {}, x: any = NONE) {
    answers(v, x)
    const w = mount(Editor, { props: { orgUuid: 'o1', scope: 'COMPONENT', objectUuid: 'c1', isWritable: true,
        isOrgAdmin: true, installationType: 'SAAS', perspectiveOptions: [{ label: 'Payments', value: 'p1' },
            { label: 'Retail', value: 'p2' }], ...props } })
    await flushPromises()
    return w
}

const select = (w: any, testid: string) =>
    w.findAllComponents(NSelect).find((s: any) => s.attributes('data-testid') === testid)
const radios = (w: any) => w.findAll('[data-testid="tea-mode"] input[type="radio"]')
const modeValues = (w: any) => radios(w).map((i: any) => (i.element as HTMLInputElement).value)
const checkedMode = (w: any) => (radios(w).find((i: any) => (i.element as HTMLInputElement).checked)?.element as HTMLInputElement)?.value
async function pickMode (w: any, mode: string) {
    await radios(w).find((i: any) => (i.element as HTMLInputElement).value === mode)!.setValue(true)
    await flushPromises()
}
const FIELD_SELECTS = ['tea-publishing', 'tea-visibility', 'tea-raw-artifacts', 'tea-minimum-lifecycle', 'tea-tei']
const disabled = (w: any, testid: string) => Boolean((select(w, testid)!.vm as any).mergedDisabled)
/** Confirms the "Inherit again?" dialog the last Save opened. */
async function confirmInherit () {
    await dialogWarning.mock.calls.at(-1)![0].onPositiveClick()
    await flushPromises()
}

describe('TeaProfileEditor', () => {
    beforeEach(() => {
        query.mockReset()
        mutate.mockReset()
        fire.mockReset()
        dialogWarning.mockReset()
        mutate.mockResolvedValue({ data: { saveTeaProfile: { uuid: 'saved' } } })
        catalogAnswer = [entry('default'), entry('top')]
    })

    it('opens in INHERIT when nothing is stored: the parent values read-only, the source line, no Save, no remove', async () => {
        const w = await mountWith(view())
        expect(query.mock.calls[0][0].variables).toEqual({ org: 'o1', scope: 'COMPONENT', object: 'c1' })
        expect(modeValues(w)).toEqual(['INHERIT', 'OVERRIDE', 'FOLLOW_PERSPECTIVE'])
        expect(w.find('[data-testid="tea-mode"]').text()).toContain('Inherit: use the perspective or organization profile')
        expect(checkedMode(w)).toBe('INHERIT')
        expect(w.find('[data-testid="tea-source"]').text()).toBe('Inherited from organization')
        expect(w.find('[data-testid="tea-fields"]').attributes('data-inherited')).toBe('true')
        expect(select(w, 'tea-minimum-lifecycle').props('value')).toBe('READY_TO_SHIP')
        expect(select(w, 'tea-publishing').props('value')).toBe('ENABLED')
        for (const id of FIELD_SELECTS) expect(disabled(w, id), id).toBe(true)
        expect(select(w, 'sbom-profile-picker')!.props('disabled')).toBe(true)
        expect(w.find('[data-testid="tea-save"]').exists()).toBe(false)
        expect(w.find('[data-testid="tea-remove"]').exists()).toBe(false)
        expect(w.find('[data-testid="tea-reset"]').exists()).toBe(false)
    })

    it('names the perspective or the built-in defaults an INHERIT form comes from', async () => {
        const fromPerspective = await mountWith(view({ parent: resolved('PERSPECTIVE', profile(), 'p1') }))
        expect(fromPerspective.find('[data-testid="tea-source"]').text()).toBe('Inherited from perspective Payments')
        const fromDefaults = await mountWith(view({ parent: resolved('DEFAULT', profile()) }))
        expect(fromDefaults.find('[data-testid="tea-source"]').text()).toBe('Inherited from built-in defaults')
    })

    it('switching INHERIT to Override enables the pre-filled fields and Save, and the save sends OVERRIDE, never INHERIT', async () => {
        const w = await mountWith(view())
        await pickMode(w, 'OVERRIDE')
        expect(w.find('[data-testid="tea-fields"]').attributes('data-inherited')).toBeUndefined()
        for (const id of FIELD_SELECTS) expect(disabled(w, id), id).toBe(false)
        expect(select(w, 'tea-minimum-lifecycle').props('value')).toBe('READY_TO_SHIP')
        expect(w.find('[data-testid="tea-source"]').text()).toBe('No override: pre-filled from organization')
        await w.find('[data-testid="tea-save"]').trigger('click')
        await flushPromises()
        expect(dialogWarning).not.toHaveBeenCalled()
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(operation({ query: mutate.mock.calls[0][0].mutation })).toBe('SaveTeaProfile')
        expect(mutate.mock.calls[0][0].variables.profile.mode).toBe('OVERRIDE')
        expect(JSON.stringify(mutate.mock.calls[0][0].variables)).not.toContain('INHERIT')
        expect(JSON.stringify(exposureCalls().map(c => c[0].variables))).not.toContain('INHERIT')
    })

    it('a perspective offers Inherit and Override only; nothing stored opens in INHERIT, a stored row in OVERRIDE', async () => {
        const none = await mountWith(view(), { scope: 'PERSPECTIVE', objectUuid: 'p2' })
        expect(modeValues(none)).toEqual(['INHERIT', 'OVERRIDE'])
        expect(none.find('[data-testid="tea-mode"]').text()).toContain('Inherit: use the organization profile')
        expect(none.find('[data-testid="tea-mode"]').text()).not.toContain('Follow')
        expect(checkedMode(none)).toBe('INHERIT')
        expect(none.find('[data-testid="tea-save"]').exists()).toBe(false)

        const stored = await mountWith(view({ stored: profile({ scope: 'PERSPECTIVE' }) }), { scope: 'PERSPECTIVE', objectUuid: 'p2' })
        expect(checkedMode(stored)).toBe('OVERRIDE')
        expect(stored.find('[data-testid="tea-save"]').exists()).toBe(true)
        await stored.find('[data-testid="tea-save"]').trigger('click')
        await flushPromises()
        expect(mutate.mock.calls[0][0].variables.profile).not.toHaveProperty('mode')
    })

    it('the organization keeps no mode radio, its Save and its Reset to defaults', async () => {
        const org = await mountWith(view({ parent: resolved('DEFAULT', profile()) }), { scope: 'ORGANIZATION', objectUuid: null })
        expect(org.find('[data-testid="tea-mode"]').exists()).toBe(false)
        expect(org.find('[data-testid="tea-save"]').exists()).toBe(true)
        expect(org.find('[data-testid="tea-reset"]').exists()).toBe(true)
        for (const id of FIELD_SELECTS) expect(disabled(org, id), id).toBe(false)
    })

    it('names both perspectives of a conflicted parent; CE offers Inherit and Override, Pro Follow as well', async () => {
        const conflicted = view({ parent: { status: 'CONFLICT', source: 'PERSPECTIVE', sourceObject: null, profile: null,
            conflictingPerspectives: [P1, P2] } })
        const pro = await mountWith(conflicted)
        const alert = pro.find('[data-testid="tea-conflict"]')
        expect(alert.text()).toContain('Payments')
        expect(alert.text()).toContain('Retail')
        expect(pro.find('[data-testid="tea-mode"]').exists()).toBe(true)
        expect(pro.find('[data-testid="tea-source"]').text()).toBe('Cannot inherit: its perspectives each carry a TEA profile')

        const ce = await mountWith(conflicted, { installationType: 'OSS' })
        expect(modeValues(ce)).toEqual(['INHERIT', 'OVERRIDE'])
        expect(ce.find('[data-testid="tea-mode"]').text()).toContain('Inherit: use the organization profile')
    })

    it('shows the persistent red banner for a stored PUBLIC row and an inherited PUBLIC profile', async () => {
        const stored = await mountWith(view({ stored: profile({ mode: 'OVERRIDE', visibility: 'PUBLIC' }) }))
        expect(stored.find('[data-testid="tea-public-banner"]').exists()).toBe(true)
        const banner = stored.findAllComponents(NAlert).find((a: any) => a.text().includes('This TEA profile is PUBLIC'))!
        expect(banner).toBeDefined()
        expect(banner.props('type')).toBe('error')
        expect(banner.props('closable')).toBe(false)

        const priv = await mountWith(view({ stored: profile({ mode: 'OVERRIDE', visibility: 'PRIVATE' }) }))
        expect(priv.find('[data-testid="tea-public-banner"]').exists()).toBe(false)

        const inherited = await mountWith(view({ effective: resolved('PERSPECTIVE', profile({ visibility: 'PUBLIC' }), 'p1') }))
        expect(inherited.find('[data-testid="tea-public-banner"]').text()).toContain('inherited from perspective Payments')
    })

    it('shows the red banner for a FOLLOW row whose followed perspective is PUBLIC, and none when it is PRIVATE', async () => {
        const follow = { uuid: 'row', mode: 'FOLLOW_PERSPECTIVE', followedPerspective: 'p1', publishing: null, visibility: null }
        const pub = await mountWith(view({ stored: follow,
            effective: resolved('FOLLOWED_PERSPECTIVE', profile({ visibility: 'PUBLIC' }), 'p1') }))
        const banner = pub.find('[data-testid="tea-public-banner"]')
        expect(banner.exists()).toBe(true)
        expect(banner.text()).toContain('PUBLIC, inherited from perspective Payments')

        const priv = await mountWith(view({ stored: follow,
            effective: resolved('FOLLOWED_PERSPECTIVE', profile({ visibility: 'PRIVATE' }), 'p1') }))
        expect(priv.find('[data-testid="tea-public-banner"]').exists()).toBe(false)
    })

    it('asks before a PUBLIC save, says how many releases it exposes, and sends nothing on cancel', async () => {
        const w = await mountWith(view({ publishedReleases: 3 }), {},
            exposure('PRIVATE', 'PUBLIC', 'ROW_PUBLIC', { afterSource: 'COMPONENT', afterSourceObject: 'c1', publishedReleases: 3 }))
        await pickMode(w, 'OVERRIDE')
        await select(w, 'tea-visibility').vm.$emit('update:value', 'PUBLIC')
        fire.mockResolvedValueOnce({ isConfirmed: false })
        await w.find('[data-testid="tea-save"]').trigger('click')
        await flushPromises()
        expect(fire.mock.calls[0][0].text).toContain('3 published release(s)')
        expect(mutate).not.toHaveBeenCalled()
        const sent = exposureCalls()[0][0].variables
        expect(sent).toMatchObject({ org: 'o1', scope: 'COMPONENT', object: 'c1' })
        expect(sent.profile.mode).toBe('OVERRIDE')
        expect(sent.profile.visibility).toBe('PUBLIC')

        fire.mockResolvedValueOnce({ isConfirmed: true })
        await w.find('[data-testid="tea-save"]').trigger('click')
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(1)
        const vars = mutate.mock.calls[0][0].variables
        expect(vars).toMatchObject({ org: 'o1', scope: 'COMPONENT', object: 'c1' })
        expect(vars.profile.mode).toBe('OVERRIDE')
        expect(vars.profile.visibility).toBe('PUBLIC')
        for (const f of TEA_PROFILE_FIELDS) expect(vars.profile).toHaveProperty(f)
        expect(w.emitted('saved')).toHaveLength(1)
    })

    it('says no release is published yet when the count is zero', async () => {
        const w = await mountWith(view({ publishedReleases: 0 }), {}, exposure('PRIVATE', 'PUBLIC', 'ROW_PUBLIC'))
        await pickMode(w, 'OVERRIDE')
        await select(w, 'tea-visibility').vm.$emit('update:value', 'PUBLIC')
        fire.mockResolvedValueOnce({ isConfirmed: false })
        await w.find('[data-testid="tea-save"]').trigger('click')
        await flushPromises()
        expect(fire.mock.calls[0][0].text).toContain('No release is published under this profile yet')
    })

    it('disables what this editor may not change, and shows product components only where they apply', async () => {
        const nonAdmin = await mountWith(view({ supportInjection: 'DISABLED' }), { isOrgAdmin: false })
        await pickMode(nonAdmin, 'OVERRIDE')
        expect(select(nonAdmin, 'tea-visibility').props('disabled')).toBe(true)
        expect(disabled(nonAdmin, 'tea-publishing')).toBe(false)
        expect(nonAdmin.find('[data-testid="tea-visibility-reason"]').text()).toContain('organization admin')

        expect(select(nonAdmin, 'tea-product-components')).toBeUndefined()
        const product = await mountWith(view(), { objectIsProduct: true })
        expect(select(product, 'tea-product-components')).toBeDefined()
        const org = await mountWith(view({ parent: resolved('DEFAULT', profile()) }), { scope: 'ORGANIZATION', objectUuid: null })
        expect(select(org, 'tea-product-components')).toBeDefined()
        expect(org.find('[data-testid="tea-mode"]').exists()).toBe(false)
    })

    it('shows a stored FOLLOW row by its perspective; OVERRIDE brings the parent-filled fields back', async () => {
        const w = await mountWith(view({ stored: { uuid: 'row', mode: 'FOLLOW_PERSPECTIVE', followedPerspective: 'p2',
            publishing: null, visibility: null } }))
        expect(select(w, 'tea-follow-perspective').props('value')).toBe('p2')
        expect(w.find('[data-testid="tea-fields"]').exists()).toBe(false)

        await pickMode(w, 'OVERRIDE')
        expect(w.find('[data-testid="tea-fields"]').exists()).toBe(true)
        expect(select(w, 'tea-minimum-lifecycle').props('value')).toBe('READY_TO_SHIP')
    })

    it('confirms a FOLLOW that makes the component PUBLIC, naming the perspective; cancel sends nothing', async () => {
        const w = await mountWith(view(), {}, exposure('PRIVATE', 'PUBLIC', 'FOLLOW_RESOLVES_PUBLIC',
            { afterSource: 'FOLLOWED_PERSPECTIVE', afterSourceObject: 'p1', publishedReleases: 2 }))
        await pickMode(w, 'FOLLOW_PERSPECTIVE')
        await select(w, 'tea-follow-perspective').vm.$emit('update:value', 'p1')
        fire.mockResolvedValueOnce({ isConfirmed: false })
        await w.find('[data-testid="tea-save"]').trigger('click')
        await flushPromises()
        expect(exposureCalls()[0][0].variables.profile).toEqual({ mode: 'FOLLOW_PERSPECTIVE', followedPerspective: 'p1' })
        const dialog = fire.mock.calls[0][0]
        expect(dialog.title).toBe('Make this TEA profile public?')
        expect(dialog.icon).toBe('warning')
        expect(dialog.text).toContain('Following perspective Payments')
        expect(dialog.text).toContain('2 published release(s)')
        expect(mutate).not.toHaveBeenCalled()

        fire.mockResolvedValueOnce({ isConfirmed: true })
        await w.find('[data-testid="tea-save"]').trigger('click')
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mutate.mock.calls[0][0].variables.profile).toEqual({ mode: 'FOLLOW_PERSPECTIVE', followedPerspective: 'p1' })
        expect(w.emitted('saved')).toHaveLength(1)
    })

    it('sends a FOLLOW that widens nothing without asking', async () => {
        const w = await mountWith(view({ stored: { uuid: 'row', mode: 'FOLLOW_PERSPECTIVE', followedPerspective: 'p2',
            publishing: null, visibility: null } }), {}, exposure('PUBLIC', 'PUBLIC', 'NONE'))
        await w.find('[data-testid="tea-save"]').trigger('click')
        await flushPromises()
        expect(fire).not.toHaveBeenCalled()
        expect(mutate).toHaveBeenCalledTimes(1)
    })

    it('INHERIT over a stored row shows the parent values read-only, keeps Save, and asks "Inherit again?"; cancel removes nothing', async () => {
        const w = await mountWith(view({ stored: profile({ mode: 'OVERRIDE', minimumLifecycle: 'GENERAL_AVAILABILITY' }) }))
        expect(checkedMode(w)).toBe('OVERRIDE')
        expect(w.find('[data-testid="tea-remove"]').exists()).toBe(false)
        expect(w.find('[data-testid="tea-source"]').text()).toBe('Stored override')
        await pickMode(w, 'INHERIT')
        expect(select(w, 'tea-minimum-lifecycle').props('value')).toBe('READY_TO_SHIP')
        for (const id of FIELD_SELECTS) expect(disabled(w, id), id).toBe(true)
        expect(w.find('[data-testid="tea-source"]').text()).toBe('Inherited from organization: Save removes the stored override')
        expect(w.find('[data-testid="tea-save"]').exists()).toBe(true)
        await w.find('[data-testid="tea-save"]').trigger('click')
        await flushPromises()
        expect(dialogWarning).toHaveBeenCalledTimes(1)
        const d = dialogWarning.mock.calls[0][0]
        expect(d.title).toBe('Inherit again?')
        expect(d.content).toBe("Remove this component's override and inherit the perspective or organization profile again?")
        // Nothing is asked of the server, nor sent, before the dialog is confirmed.
        expect(exposureCalls()).toHaveLength(0)
        expect(mutate).not.toHaveBeenCalled()
        expect(fire).not.toHaveBeenCalled()

        // Back to Override: the stored values return, editable.
        await pickMode(w, 'OVERRIDE')
        expect(select(w, 'tea-minimum-lifecycle').props('value')).toBe('GENERAL_AVAILABILITY')
        expect(disabled(w, 'tea-minimum-lifecycle')).toBe(false)
    })

    it('confirming "Inherit again?" removes the row and reloads into INHERIT with no row and no Save', async () => {
        const w = await mountWith(view({ stored: profile({ mode: 'OVERRIDE', visibility: 'PRIVATE' }) }), {}, NONE)
        await pickMode(w, 'INHERIT')
        await w.find('[data-testid="tea-save"]').trigger('click')
        await flushPromises()
        mutate.mockResolvedValueOnce({ data: { deleteTeaProfile: true } })
        answers(view(), NONE)
        await confirmInherit()
        expect(exposureCalls()[0][0].variables).toEqual({ org: 'o1', scope: 'COMPONENT', object: 'c1', profile: null })
        // The dialog was the confirmation: no plain Swal on top of it when nothing becomes PUBLIC.
        expect(fire).not.toHaveBeenCalled()
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(operation({ query: mutate.mock.calls[0][0].mutation })).toBe('DeleteTeaProfile')
        expect(mutate.mock.calls[0][0].variables).toEqual({ org: 'o1', scope: 'COMPONENT', object: 'c1' })
        expect(w.emitted('removed')).toHaveLength(1)
        expect(w.emitted('saved')).toBeUndefined()
        expect(checkedMode(w)).toBe('INHERIT')
        expect(w.find('[data-testid="tea-source"]').text()).toBe('Inherited from organization')
        expect(w.find('[data-testid="tea-save"]').exists()).toBe(false)
    })

    it('after "Inherit again?" still asks the PUBLIC dialog when the removal makes the component PUBLIC', async () => {
        const stored = view({ stored: profile({ mode: 'OVERRIDE', visibility: 'PRIVATE' }) })
        const w = await mountWith(stored, {}, exposure('PRIVATE', 'PUBLIC', 'REMOVAL_RESOLVES_PUBLIC',
            { afterSource: 'PERSPECTIVE', afterSourceObject: 'p1' }))
        await pickMode(w, 'INHERIT')
        await w.find('[data-testid="tea-save"]').trigger('click')
        await flushPromises()
        fire.mockResolvedValueOnce({ isConfirmed: false })
        await confirmInherit()
        expect(fire.mock.calls[0][0].title).toBe('Make this TEA profile public?')
        expect(fire.mock.calls[0][0].confirmButtonText).toBe('Remove and make it public')
        expect(fire.mock.calls[0][0].text).toContain('perspective Payments')
        expect(mutate).not.toHaveBeenCalled()

        fire.mockResolvedValueOnce({ isConfirmed: true })
        mutate.mockResolvedValueOnce({ data: { deleteTeaProfile: true } })
        await confirmInherit()
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(w.emitted('removed')).toHaveLength(1)
    })

    // TEA-9 round 2, design 4.5 case 54.
    it('asks the PUBLIC dialog before a perspective-row removal that makes a member PUBLIC, naming it', async () => {
        const stored = view({ stored: profile({ scope: 'PERSPECTIVE', visibility: 'PRIVATE' }) })
        const w = await mountWith(stored, { scope: 'PERSPECTIVE', objectUuid: 'p2' },
            exposure('PRIVATE', 'PRIVATE', 'REMOVAL_RESOLVES_PUBLIC', { widened: [{ component: 'c16', name: 'checkout-api',
                afterSource: 'PERSPECTIVE', afterSourceObject: 'p1' }] }))
        await pickMode(w, 'INHERIT')
        await w.find('[data-testid="tea-save"]').trigger('click')
        await flushPromises()
        expect(dialogWarning.mock.calls[0][0].content)
            .toBe("Remove this perspective's override and inherit the organization profile again?")
        fire.mockResolvedValueOnce({ isConfirmed: false })
        await confirmInherit()
        expect(exposureCalls()[0][0].variables).toEqual({ org: 'o1', scope: 'PERSPECTIVE', object: 'p2', profile: null })
        expect(fire.mock.calls[0][0].title).toBe('Make this TEA profile public?')
        expect(fire.mock.calls[0][0].icon).toBe('warning')
        expect(fire.mock.calls[0][0].text).toContain('1 component(s) of this perspective then resolve to a PUBLIC profile: checkout-api.')
        expect(fire.mock.calls[0][0].confirmButtonText).toBe('Remove and make them public')
        expect(mutate).not.toHaveBeenCalled()

        fire.mockResolvedValueOnce({ isConfirmed: true })
        mutate.mockResolvedValueOnce({ data: { deleteTeaProfile: true } })
        await confirmInherit()
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(operation({ query: mutate.mock.calls[0][0].mutation })).toBe('DeleteTeaProfile')
        expect(mutate.mock.calls[0][0].variables).toEqual({ org: 'o1', scope: 'PERSPECTIVE', object: 'p2' })
        expect(w.emitted('removed')).toHaveLength(1)
    })

    it('the source holds no separate Remove override button any more, and no save sends INHERIT', () => {
        const src = readFileSync(resolve(__dirname, 'TeaProfileEditor.vue'), 'utf8')
        expect(src).not.toContain('data-testid="tea-remove"')
        expect(src).not.toContain('Remove override')
        expect(src).toContain('async function remove (')
        const save = src.slice(src.indexOf('async function save ('), src.indexOf('async function remove ('))
        expect(save.indexOf('if (inherits.value) return')).toBeGreaterThan(-1)
        expect(save.indexOf('if (inherits.value) return')).toBeLessThan(save.indexOf('teaProfileInput('))
    })

    it('shows a failed dry-run as an error and neither asks nor sends', async () => {
        const w = await mountWith(view(), {}, new Error('minimumLifecycle DRAFT is too low'))
        await pickMode(w, 'OVERRIDE')
        await w.find('[data-testid="tea-save"]').trigger('click')
        await flushPromises()
        expect(fire).toHaveBeenCalledTimes(1)
        // TEA-30 row 19 (design 3.8, D18): the object form, the message in text, one argument, no html key.
        expect(fire.mock.calls[0]).toHaveLength(1)
        expect(fire.mock.calls[0][0]).toEqual({ title: 'Error!', text: 'minimumLifecycle DRAFT is too low', icon: 'error' })
        expect(fire.mock.calls[0][0]).not.toHaveProperty('html')
        expect(mutate).not.toHaveBeenCalled()
    })

    it('INHERIT at COMPONENT scope with perspectives shows the conflict hint under the source line; Override and Follow do not', async () => {
        const w = await mountWith(view())
        expect(checkedMode(w)).toBe('INHERIT')
        const hint = w.find('[data-testid="tea-inherit-hint"]')
        expect(hint.text()).toBe("Inheriting resolves through this component's perspectives: if more than one of them carries its own "
            + 'TEA profile, inheriting stops with a conflict \u2014 use Follow one of its perspectives to pin one.')
        expect(hint.classes()).toContain('text-muted')
        const html = w.html()
        expect(html.indexOf('data-testid="tea-source"')).toBeLessThan(html.indexOf('data-testid="tea-inherit-hint"'))
        await pickMode(w, 'OVERRIDE')
        expect(w.find('[data-testid="tea-inherit-hint"]').exists()).toBe(false)
        await pickMode(w, 'FOLLOW_PERSPECTIVE')
        expect(w.find('[data-testid="tea-inherit-hint"]').exists()).toBe(false)
    })

    it('no conflict hint for a component without perspectives, at a perspective, or under a CONFLICT (its line stays)', async () => {
        const none = await mountWith(view(), { perspectiveOptions: [] })
        expect(checkedMode(none)).toBe('INHERIT')
        expect(none.find('[data-testid="tea-inherit-hint"]').exists()).toBe(false)
        const perspective = await mountWith(view(), { scope: 'PERSPECTIVE', objectUuid: 'p1' })
        expect(checkedMode(perspective)).toBe('INHERIT')
        expect(perspective.find('[data-testid="tea-inherit-hint"]').exists()).toBe(false)
        const conflicted = await mountWith(view({ parent: { status: 'CONFLICT', source: null, sourceObject: null, profile: null,
            conflictingPerspectives: [P1, P2] } }))
        expect(conflicted.find('[data-testid="tea-inherit-hint"]').exists()).toBe(false)
        expect(conflicted.find('[data-testid="tea-source"]').text()).toBe('Cannot inherit: its perspectives each carry a TEA profile')
    })

    it('shows the archived notice on an archived object only', async () => {
        const archived = await mountWith(view(), { objectArchived: true })
        const notice = archived.find('[data-testid="tea-archived"]')
        expect(notice.exists()).toBe(true)
        expect(notice.text()).toContain('This component is archived')
        const alert = archived.findAllComponents(NAlert).find((a: any) => a.text().includes('This component is archived'))!
        expect(alert.props('closable')).toBe(false)
        expect(alert.props('type')).toBe('warning')

        const active = await mountWith(view())
        expect(active.find('[data-testid="tea-archived"]').exists()).toBe(false)
    })

    // TEA-30 row 19: the recipe fields left the editor; the SBOM profile picker names the profiles.
    describe('the SBOM profile picker (TEA-30)', () => {
        const RECIPE_TESTIDS = ['tea-dependency-depth', 'tea-optional-dependencies', 'tea-structure', 'tea-sources',
            'tea-excluded-coverage', 'tea-support-metadata', 'tea-internal-metadata', 'tea-vulnerability-documents']

        it('shows no recipe field, and the picker between visibility and raw artifacts with the stored names', async () => {
            const w = await mountWith(view({ stored: profile({ mode: 'OVERRIDE', profiles: ['top', 'default'] }) }))
            for (const id of RECIPE_TESTIDS) expect(w.find(`[data-testid="${id}"]`).exists(), id).toBe(false)
            const root = w.find('[data-testid="tea-profiles"]')
            expect(root.exists()).toBe(true)
            expect(root.element.tagName).toBe('DIV')
            expect(root.classes()).toContain('sbom-profile-picker-root')
            expect(root.find('[data-testid="sbom-profile-picker"]').exists()).toBe(true)
            const html = w.html()
            const at = (id: string) => html.indexOf(`data-testid="${id}"`)
            expect(at('tea-profiles')).toBeGreaterThan(at('tea-visibility'))
            expect(at('tea-profiles')).toBeLessThan(at('tea-raw-artifacts'))
            expect(select(w, 'sbom-profile-picker').props('value')).toEqual(['top', 'default'])
            const sent = calls('SbomProfileCatalog')
            expect(sent).toHaveLength(1)
            expect(sent[0][0].variables).toEqual({ org: 'o1', scope: 'COMPONENT', object: 'c1' })
            expect(sent[0][0].fetchPolicy).toBe('no-cache')
        })

        it('shows a catalog failure under the picker and keeps the stored names', async () => {
            catalogAnswer = new Error('catalog unavailable')
            const w = await mountWith(view({ stored: profile({ mode: 'OVERRIDE', profiles: ['top'] }) }))
            expect(w.find('[data-testid="tea-profiles-error"]').text()).toBe('catalog unavailable')
            expect(select(w, 'sbom-profile-picker').props('value')).toEqual(['top'])
            expect(w.find('[data-testid="tea-load-error"]').exists()).toBe(false)
        })

        it('reloadCatalog() reads the catalog alone, the lines follow it, and an unsaved edit survives', async () => {
            const w = await mountWith(view({ stored: profile({ mode: 'OVERRIDE', profiles: ['top'] }) }))
            await select(w, 'sbom-profile-picker').vm.$emit('update:value', ['top', 'default'])
            await select(w, 'tea-publishing').vm.$emit('update:value', 'ENABLED')
            expect(w.findAll('[data-testid="sbom-picker-line"]')[0].classes()).not.toContain('text-danger')
            const viewReads = calls('TeaProfileEditorView').length
            catalogAnswer = [entry('default'), entry('top', 'CONFLICT', { conflictingPerspectives: [P1, P2] })]
            await (w.vm as any).reloadCatalog()
            await flushPromises()
            expect(calls('SbomProfileCatalog')).toHaveLength(2)
            expect(calls('TeaProfileEditorView')).toHaveLength(viewReads)
            expect(select(w, 'sbom-profile-picker').props('value')).toEqual(['top', 'default'])
            expect(select(w, 'tea-publishing').props('value')).toBe('ENABLED')
            const line = w.findAll('[data-testid="sbom-picker-line"]')[0]
            expect(line.classes()).toContain('text-danger')
            expect(line.text()).toContain('Payments, Retail')
        })

        it('saves exactly the channel fields with mode, profiles in the picker order; CE sends default', async () => {
            const w = await mountWith(view({ stored: profile({ mode: 'OVERRIDE', profiles: ['default'] }) }))
            await select(w, 'sbom-profile-picker').vm.$emit('update:value', ['top', 'default'])
            await w.find('[data-testid="tea-save"]').trigger('click')
            await flushPromises()
            const sent = mutate.mock.calls[0][0].variables.profile
            expect(Object.keys(sent).sort()).toEqual([...TEA_PROFILE_FIELDS, 'mode'].sort())
            expect(sent.profiles).toEqual(['top', 'default'])

            mutate.mockClear()
            // A CE row stored before the CE rule may still name another profile: CE sends default regardless.
            const ce = await mountWith(view({ stored: profile({ mode: 'OVERRIDE', profiles: ['top'] }) }),
                { installationType: 'OSS' })
            await ce.find('[data-testid="tea-save"]').trigger('click')
            await flushPromises()
            expect(mutate.mock.calls[0][0].variables.profile.profiles).toEqual(['default'])
        })

        it('CE: no picker, a read-only "SBOM profile: default" line in its place', async () => {
            const ce = await mountWith(view({ stored: profile({ mode: 'OVERRIDE', profiles: ['top'] }) }),
                { installationType: 'OSS' })
            expect(ce.find('[data-testid="tea-profiles"]').exists()).toBe(false)
            expect(select(ce, 'sbom-profile-picker')).toBeUndefined()
            const line = ce.find('[data-testid="tea-profiles-ce"]')
            expect(line.exists()).toBe(true)
            expect(line.text()).toBe('SBOM profile: default')
            const html = ce.html()
            const at = (id: string) => html.indexOf(`data-testid="${id}"`)
            expect(at('tea-profiles-ce')).toBeGreaterThan(at('tea-visibility'))
            expect(at('tea-profiles-ce')).toBeLessThan(at('tea-raw-artifacts'))

            const pro = await mountWith(view({ stored: profile({ mode: 'OVERRIDE', profiles: ['top'] }) }))
            expect(pro.find('[data-testid="tea-profiles-ce"]').exists()).toBe(false)
            expect(pro.find('[data-testid="tea-profiles"]').exists()).toBe(true)
        })

        it('shows a refused save naming a perspective as text in the object form, with one argument', async () => {
            const message = 'SBOM profile top resolves to a conflict between perspectives <img src=x onerror=alert(1)>, '
                + 'Payments at component widget: define it on the component or follow one perspective for it'
            const w = await mountWith(view({ stored: profile({ mode: 'OVERRIDE', profiles: ['top'] }) }))
            mutate.mockRejectedValueOnce(new Error(message))
            await w.find('[data-testid="tea-save"]').trigger('click')
            await flushPromises()
            const errors = fire.mock.calls.filter(c => c[0]?.title === 'Error!')
            expect(errors).toHaveLength(1)
            expect(errors[0]).toHaveLength(1)
            expect(errors[0][0]).toEqual({ title: 'Error!', text: message, icon: 'error' })
            expect(fire.mock.calls.every(c => c.length === 1 && typeof c[0] === 'object' && !('html' in c[0]))).toBe(true)
            expect(w.emitted('saved')).toBeUndefined()
        })
    })
})
