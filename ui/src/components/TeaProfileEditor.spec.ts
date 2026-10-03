// @vitest-environment happy-dom
//
// The TEA profile editor (task TEA-2, design 4.8 cases 42-47): the pre-fill and its source line, the
// conflict alert and the mode radio, the PUBLIC banner, the confirmation before a PUBLIC save, the
// fields an editor may not change, and a FOLLOW row. The GraphQL client and Swal are mocked.
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
        parent: resolved('ORGANIZATION', profile({ publishing: 'ENABLED', dependencyDepth: 'TOP_LEVEL_ONLY' })),
        publishedReleases: 0,
        supportInjection: 'ENABLED',
        ...changes,
    }
}

async function mountWith (v: any, props: Record<string, any> = {}) {
    query.mockResolvedValue({ data: { teaProfileEditorView: v } })
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
    })

    it('pre-fills from the organization when nothing is stored, and offers no remove', async () => {
        const w = await mountWith(view())
        expect(query.mock.calls[0][0].variables).toEqual({ org: 'o1', scope: 'COMPONENT', object: 'c1' })
        expect(w.find('[data-testid="tea-source"]').text()).toContain('pre-filled from organization')
        expect(select(w, 'tea-dependency-depth').props('value')).toBe('TOP_LEVEL_ONLY')
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
        const w = await mountWith(view({ publishedReleases: 3 }))
        await select(w, 'tea-visibility').vm.$emit('update:value', 'PUBLIC')
        fire.mockResolvedValueOnce({ isConfirmed: false })
        await w.find('[data-testid="tea-save"]').trigger('click')
        await flushPromises()
        expect(fire.mock.calls[0][0].text).toContain('3 published release(s)')
        expect(mutate).not.toHaveBeenCalled()

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
        const w = await mountWith(view({ publishedReleases: 0 }))
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
        expect(select(nonAdmin, 'tea-support-metadata').props('disabled')).toBe(true)
        expect(select(nonAdmin, 'tea-support-metadata').props('value')).toBe('EXCLUDE')
        expect(nonAdmin.find('[data-testid="tea-support-metadata-reason"]').exists()).toBe(true)

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
        expect(select(w, 'tea-dependency-depth').props('value')).toBe('TOP_LEVEL_ONLY')
    })
})
