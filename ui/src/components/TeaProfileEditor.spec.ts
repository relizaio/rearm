// @vitest-environment happy-dom
//
// The TEA profile editor (task TEA-2, design 4.8 cases 42-47): the pre-fill and its source line, the
// conflict alert and the mode radio, the PUBLIC banner, the confirmation before a PUBLIC save, the
// fields an editor may not change, and a FOLLOW row. Task TEA-9 (design 4.5 cases 25-29): every
// save and removal first asks the server's exposure dry-run and confirms on its answer, and an
// archived object shows its notice. The GraphQL client and Swal are mocked; the query mock answers
// by document.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { NAlert, NSelect } from 'naive-ui'

const query = vi.fn()
const mutate = vi.fn()
const fire = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
vi.mock('sweetalert2', () => ({ default: { fire: (...a: any[]) => fire(...a) } }))

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

describe('TeaProfileEditor', () => {
    beforeEach(() => {
        query.mockReset()
        mutate.mockReset()
        fire.mockReset()
        mutate.mockResolvedValue({ data: { saveTeaProfile: { uuid: 'saved' } } })
        catalogAnswer = [entry('default'), entry('top')]
    })

    it('pre-fills from the organization when nothing is stored, and offers no remove', async () => {
        const w = await mountWith(view())
        expect(query.mock.calls[0][0].variables).toEqual({ org: 'o1', scope: 'COMPONENT', object: 'c1' })
        expect(w.find('[data-testid="tea-source"]').text()).toContain('pre-filled from organization')
        expect(select(w, 'tea-minimum-lifecycle').props('value')).toBe('READY_TO_SHIP')
        expect(select(w, 'tea-publishing').props('value')).toBe('ENABLED')
        expect(w.find('[data-testid="tea-remove"]').exists()).toBe(false)
    })

    it('names both perspectives of a conflicted parent; the mode radio is Pro only', async () => {
        const conflicted = view({ parent: { status: 'CONFLICT', source: 'PERSPECTIVE', sourceObject: null, profile: null,
            conflictingPerspectives: [P1, P2] } })
        const pro = await mountWith(conflicted)
        const alert = pro.find('[data-testid="tea-conflict"]')
        expect(alert.text()).toContain('Payments')
        expect(alert.text()).toContain('Retail')
        expect(pro.find('[data-testid="tea-mode"]').exists()).toBe(true)

        const ce = await mountWith(conflicted, { installationType: 'OSS' })
        expect(ce.find('[data-testid="tea-mode"]').exists()).toBe(false)
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
        await select(w, 'tea-visibility').vm.$emit('update:value', 'PUBLIC')
        fire.mockResolvedValueOnce({ isConfirmed: false })
        await w.find('[data-testid="tea-save"]').trigger('click')
        await flushPromises()
        expect(fire.mock.calls[0][0].text).toContain('No release is published under this profile yet')
    })

    it('disables what this editor may not change, and shows product components only where they apply', async () => {
        const nonAdmin = await mountWith(view({ supportInjection: 'DISABLED' }), { isOrgAdmin: false })
        expect(select(nonAdmin, 'tea-visibility').props('disabled')).toBe(true)
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

        await w.findAll('[data-testid="tea-mode"] input[type="radio"]')
            .find((i: any) => (i.element as HTMLInputElement).value === 'OVERRIDE')!.setValue(true)
        await flushPromises()
        expect(w.find('[data-testid="tea-fields"]').exists()).toBe(true)
        expect(select(w, 'tea-minimum-lifecycle').props('value')).toBe('READY_TO_SHIP')
    })

    it('confirms a FOLLOW that makes the component PUBLIC, naming the perspective; cancel sends nothing', async () => {
        const w = await mountWith(view(), {}, exposure('PRIVATE', 'PUBLIC', 'FOLLOW_RESOLVES_PUBLIC',
            { afterSource: 'FOLLOWED_PERSPECTIVE', afterSourceObject: 'p1', publishedReleases: 2 }))
        await w.findAll('[data-testid="tea-mode"] input[type="radio"]')
            .find((i: any) => (i.element as HTMLInputElement).value === 'FOLLOW_PERSPECTIVE')!.setValue(true)
        await flushPromises()
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

    it('asks the PUBLIC dialog before a removal that makes the component PUBLIC, the plain one otherwise', async () => {
        const stored = view({ stored: profile({ mode: 'OVERRIDE', visibility: 'PRIVATE' }) })
        const widening = await mountWith(stored, {}, exposure('PRIVATE', 'PUBLIC', 'REMOVAL_RESOLVES_PUBLIC',
            { afterSource: 'PERSPECTIVE', afterSourceObject: 'p1' }))
        fire.mockResolvedValueOnce({ isConfirmed: false })
        await widening.find('[data-testid="tea-remove"]').trigger('click')
        await flushPromises()
        expect(exposureCalls()[0][0].variables).toEqual({ org: 'o1', scope: 'COMPONENT', object: 'c1', profile: null })
        expect(fire.mock.calls[0][0].title).toBe('Make this TEA profile public?')
        expect(fire.mock.calls[0][0].confirmButtonText).toBe('Remove and make it public')
        expect(fire.mock.calls[0][0].text).toContain('perspective Payments')
        expect(mutate).not.toHaveBeenCalled()

        fire.mockReset()
        const plain = await mountWith(stored, {}, NONE)
        fire.mockResolvedValueOnce({ isConfirmed: false })
        await plain.find('[data-testid="tea-remove"]').trigger('click')
        await flushPromises()
        expect(fire.mock.calls[0][0].title).toBe('Remove this TEA profile override?')
        expect(fire.mock.calls[0][0].confirmButtonText).toBe('Remove')
        expect(mutate).not.toHaveBeenCalled()

        fire.mockResolvedValueOnce({ isConfirmed: true })
        mutate.mockResolvedValueOnce({ data: { deleteTeaProfile: true } })
        await plain.find('[data-testid="tea-remove"]').trigger('click')
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(plain.emitted('removed')).toHaveLength(1)
    })

    // TEA-9 round 2, design 4.5 case 54.
    it('asks the PUBLIC dialog before a perspective-row removal that makes a member PUBLIC, naming it', async () => {
        const stored = view({ stored: profile({ scope: 'PERSPECTIVE', visibility: 'PRIVATE' }) })
        const w = await mountWith(stored, { scope: 'PERSPECTIVE', objectUuid: 'p2' },
            exposure('PRIVATE', 'PRIVATE', 'REMOVAL_RESOLVES_PUBLIC', { widened: [{ component: 'c16', name: 'checkout-api',
                afterSource: 'PERSPECTIVE', afterSourceObject: 'p1' }] }))
        fire.mockResolvedValueOnce({ isConfirmed: false })
        await w.find('[data-testid="tea-remove"]').trigger('click')
        await flushPromises()
        expect(exposureCalls()[0][0].variables).toEqual({ org: 'o1', scope: 'PERSPECTIVE', object: 'p2', profile: null })
        expect(fire.mock.calls[0][0].title).toBe('Make this TEA profile public?')
        expect(fire.mock.calls[0][0].icon).toBe('warning')
        expect(fire.mock.calls[0][0].text).toContain('1 component(s) of this perspective then resolve to a PUBLIC profile: checkout-api.')
        expect(fire.mock.calls[0][0].confirmButtonText).toBe('Remove and make them public')
        expect(mutate).not.toHaveBeenCalled()

        fire.mockResolvedValueOnce({ isConfirmed: true })
        mutate.mockResolvedValueOnce({ data: { deleteTeaProfile: true } })
        await w.find('[data-testid="tea-remove"]').trigger('click')
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(operation({ query: mutate.mock.calls[0][0].mutation })).toBe('DeleteTeaProfile')
        expect(mutate.mock.calls[0][0].variables).toEqual({ org: 'o1', scope: 'PERSPECTIVE', object: 'p2' })
        expect(w.emitted('removed')).toHaveLength(1)
    })

    it('shows a failed dry-run as an error and neither asks nor sends', async () => {
        const w = await mountWith(view(), {}, new Error('minimumLifecycle DRAFT is too low'))
        await w.find('[data-testid="tea-save"]').trigger('click')
        await flushPromises()
        expect(fire).toHaveBeenCalledTimes(1)
        // TEA-30 row 19 (design 3.8, D18): the object form, the message in text, one argument, no html key.
        expect(fire.mock.calls[0]).toHaveLength(1)
        expect(fire.mock.calls[0][0]).toEqual({ title: 'Error!', text: 'minimumLifecycle DRAFT is too low', icon: 'error' })
        expect(fire.mock.calls[0][0]).not.toHaveProperty('html')
        expect(mutate).not.toHaveBeenCalled()
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

        it('saves exactly the channel fields with mode, profiles in the picker order, one name on CE', async () => {
            const w = await mountWith(view({ stored: profile({ mode: 'OVERRIDE', profiles: ['default'] }) }))
            await select(w, 'sbom-profile-picker').vm.$emit('update:value', ['top', 'default'])
            await w.find('[data-testid="tea-save"]').trigger('click')
            await flushPromises()
            const sent = mutate.mock.calls[0][0].variables.profile
            expect(Object.keys(sent).sort()).toEqual([...TEA_PROFILE_FIELDS, 'mode'].sort())
            expect(sent.profiles).toEqual(['top', 'default'])

            mutate.mockClear()
            const ce = await mountWith(view({ stored: profile({ mode: 'OVERRIDE', profiles: ['default'] }) }),
                { installationType: 'OSS' })
            expect(select(ce, 'sbom-profile-picker').props('multiple')).toBe(false)
            await select(ce, 'sbom-profile-picker').vm.$emit('update:value', 'top')
            await ce.find('[data-testid="tea-save"]').trigger('click')
            await flushPromises()
            expect(mutate.mock.calls[0][0].variables.profile.profiles).toEqual(['top'])
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
