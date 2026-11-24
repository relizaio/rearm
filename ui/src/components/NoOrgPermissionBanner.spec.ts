// @vitest-environment happy-dom
//
// License seats review follow-up (TPF-168): the banner of a signed-in user with no
// organization-wide permission above NONE in the current organization, mounted by AppWrapper under
// the over-license banner. Reads the stored myuser (combined permissions) and myorg; no query.
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'

const query = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: vi.fn() } }))
vi.mock('@/utils/keycloak', () => ({ default: { token: 'test', updateToken: vi.fn(), login: vi.fn(), logout: vi.fn() } }))

const store = (await import('@/store')).default.store
const { default: NoOrgPermissionBanner } = await import('./NoOrgPermissionBanner.vue')

const ORG = '11111111-1111-4111-8111-111111111111'
const OTHER = '22222222-2222-4222-8222-222222222222'
const TEXT = 'You were granted no permissions in this organization. Please contact your administrator.'
const orgPerm = (type: string, org = ORG) => ({ org, object: org, scope: 'ORGANIZATION', type })
const user = (perms: any[], extra: any = {}) => ({ uuid: 'u1', email: 'u@x.io', installationType: 'MANAGED_SERVICE',
    isGlobalAdmin: false, permissions: { permissions: perms }, ...extra })

const mounted: VueWrapper[] = []
async function mountAs (me: any, org: string | null = ORG) {
    store.commit('SET_ORGANIZATIONS', [{ uuid: ORG, name: 'One' }, { uuid: OTHER, name: 'Two' }])
    store.commit('UPDATE_MY_ORG', org)
    store.commit('SET_MY_USER', structuredClone(me))
    const w = mount(NoOrgPermissionBanner, { global: { plugins: [store] } })
    mounted.push(w)
    await flushPromises()
    return w
}
const banner = (w: VueWrapper) => w.find('[data-testid="no-org-permission-banner"]')

describe('the no-permission banner', () => {
    beforeEach(() => query.mockReset())
    afterEach(() => {
        while (mounted.length) mounted.pop()?.unmount()
    })

    it('shows the text to a user with NONE, or nothing, organization-wide in the current organization', async () => {
        for (const perms of [[], [orgPerm('NONE')], [orgPerm('ADMIN', OTHER)],
            [{ org: ORG, object: 'c', scope: 'COMPONENT', type: 'READ_WRITE' }]]) {
            const w = await mountAs(user(perms))
            expect(banner(w).exists()).toBe(true)
            expect(banner(w).text()).toBe(TEXT)
        }
        expect(query).not.toHaveBeenCalled()
    })

    it('nothing with an organization-wide permission above NONE, for a global administrator, or before an organization is chosen', async () => {
        for (const t of ['READ_ONLY', 'READ_WRITE', 'ADMIN']) {
            expect(banner(await mountAs(user([orgPerm(t)]))).exists()).toBe(false)
        }
        expect(banner(await mountAs(user([], { isGlobalAdmin: true }))).exists()).toBe(false)
        expect(banner(await mountAs(user([]), 'not-an-org-of-the-user')).exists()).toBe(false)
    })

    it('follows the organization switch', async () => {
        const w = await mountAs(user([orgPerm('READ_ONLY', OTHER)]))
        expect(banner(w).exists()).toBe(true)
        store.commit('UPDATE_MY_ORG', OTHER)
        await flushPromises()
        expect(banner(w).exists()).toBe(false)
    })
})

describe('AppWrapper mounts the no-permission banner', () => {
    const source = readFileSync(resolve(__dirname, 'AppWrapper.vue'), 'utf8')
    it('after the over-license banner and before the left navigation, once the user is known', () => {
        const template = source.slice(0, source.indexOf('</template>'))
        const license = template.indexOf('<license-over-limit-banner v-if="myUser && myUser.uuid" />')
        const at = template.indexOf('<no-org-permission-banner v-if="myUser && myUser.uuid" />')
        const left = template.indexOf('<left-nav-bar />')
        expect(license).toBeGreaterThan(-1)
        expect(at).toBeGreaterThan(license)
        expect(left).toBeGreaterThan(at)
        expect(source).toMatch(/import NoOrgPermissionBanner from '\.\/NoOrgPermissionBanner\.vue'/)
    })
})

describe('the store keeps the combined permissions across user mutations', () => {
    const source = readFileSync(resolve(__dirname, '../store.ts'), 'utf8')
    it('updateUserName and acceptUserPolicies commit through withCombinedPermissionsOf', () => {
        expect(source).toContain("context.commit('SET_MY_USER', withCombinedPermissionsOf(context.state.iam.user, response.data.updateUserName))")
        expect(source).toContain("context.commit('SET_MY_USER', withCombinedPermissionsOf(context.state.iam.user, data.data.acceptUserPolicies))")
    })
})
