// @vitest-environment happy-dom
//
// TPF-168 U4 (TPF-165 4.2, D10): the admin banner of an instance over its license, mounted by
// AppWrapper above the routed page. Exact text from the license counts; nothing for zero accounts
// outside the limit; for a user refused by the license gate, no banner and no console error; no
// query at all for a user who is neither a global administrator nor an organization ADMIN.
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'

const query = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: vi.fn() } }))
vi.mock('@/utils/keycloak', () => ({ default: { token: 'test', updateToken: vi.fn(), login: vi.fn(), logout: vi.fn() } }))

const store = (await import('@/store')).default.store
const { default: LicenseOverLimitBanner } = await import('./LicenseOverLimitBanner.vue')
const { LICENSE_BANNER_QUERY, overLicenseBannerText } = await import('@/utils/licenseSeats')

const ORG = '11111111-1111-4111-8111-111111111111'
const GLOBAL_ADMIN = { uuid: 'u1', installationType: 'MANAGED_SERVICE', isGlobalAdmin: true, permissions: { permissions: [] } }
const ORG_ADMIN = { ...GLOBAL_ADMIN, isGlobalAdmin: false,
    permissions: { permissions: [{ org: ORG, object: ORG, scope: 'ORGANIZATION', type: 'ADMIN' }] } }
const READER = { ...GLOBAL_ADMIN, isGlobalAdmin: false,
    permissions: { permissions: [{ org: ORG, object: ORG, scope: 'ORGANIZATION', type: 'READ_ONLY' }] } }
const CE_ADMIN = { ...GLOBAL_ADMIN, installationType: 'OSS' }

const OVER = { maxReadUsers: 1, maxWriteUsers: 2, globalUserCounts: { readUsers: 2, writeUsers: 3, outsideLimit: 2 } }
const FITS = { maxReadUsers: 1, maxWriteUsers: 2, globalUserCounts: { readUsers: 1, writeUsers: 2, outsideLimit: 0 } }
const BANNER = 'This instance is over its license: 2 read users of 1, 3 write users of 2. Accounts outside the limit, by creation date, cannot sign in.'
const U8_OVER_I = { maxReadUsers: 1, maxWriteUsers: 2,
    globalUserCounts: { writeUsers: 4, readUsers: 0, totalUsers: 4, noRoleUsers: 0, outsideLimit: 1 } }
const U8_OVER_II = { maxReadUsers: 1, maxWriteUsers: 2,
    globalUserCounts: { writeUsers: 3, readUsers: 0, totalUsers: 3, noRoleUsers: 0, outsideLimit: 0 } }
const U8_BANNER_I = 'This instance is over its license: 0 read users of 1, 4 write users of 2. Accounts outside the limit, by creation date, cannot sign in.'

const mounted: VueWrapper[] = []
async function mountAs (user: any) {
    store.commit('SET_MY_USER', structuredClone(user))
    const w = mount(LicenseOverLimitBanner, { global: { plugins: [store] } })
    mounted.push(w)
    await flushPromises()
    return w
}
const banner = (w: VueWrapper) => w.find('[data-testid="license-over-limit-banner"]')

describe('the over-license admin banner', () => {
    let consoleError: any
    beforeEach(() => {
        query.mockReset()
        consoleError = vi.spyOn(console, 'error')
    })
    afterEach(() => {
        while (mounted.length) mounted.pop()?.unmount()
        expect(consoleError).not.toHaveBeenCalled()
        consoleError.mockRestore()
    })

    it('shows the exact text for an admin when accounts are outside the limit', async () => {
        query.mockResolvedValue({ data: { license: structuredClone(OVER) } })
        const w = await mountAs(GLOBAL_ADMIN)
        expect(banner(w).exists()).toBe(true)
        expect(banner(w).text()).toBe(BANNER)
        const opts = query.mock.calls[0][0]
        expect(opts.query).toBe(LICENSE_BANNER_QUERY)
        expect(opts.fetchPolicy).toBe('no-cache')
        expect(opts.errorPolicy).toBe('all')
    })

    it('an organization ADMIN asks too', async () => {
        query.mockResolvedValue({ data: { license: structuredClone(OVER) } })
        const w = await mountAs(ORG_ADMIN)
        expect(banner(w).text()).toBe(BANNER)
    })

    it('no banner when nobody is outside the limit', async () => {
        query.mockResolvedValue({ data: { license: structuredClone(FITS) } })
        const w = await mountAs(GLOBAL_ADMIN)
        expect(query).toHaveBeenCalledTimes(1)
        expect(banner(w).exists()).toBe(false)
    })

    it('no banner and no console error when the license query answers Not authorized', async () => {
        query.mockResolvedValue({ data: { license: null }, error: { errors: [{ message: 'Not authorized' }] } })
        const w = await mountAs(ORG_ADMIN)
        expect(banner(w).exists()).toBe(false)
    })

    it('no banner and no console error when the query throws', async () => {
        query.mockRejectedValue(new Error('Not authorized'))
        const w = await mountAs(ORG_ADMIN)
        expect(banner(w).exists()).toBe(false)
    })

    it('no query for a non-admin, nor on CE', async () => {
        await mountAs(READER)
        await mountAs(CE_ADMIN)
        expect(query).not.toHaveBeenCalled()
    })

    // TPF-168 U8 (architecture round 3, T-1): a global administrator created beyond the write limit
    // is counted WRITE but admitted, so the backend leaves them out of outsideLimit. The banner prints
    // the fields as given: no UI-side arithmetic on writeUsers or outsideLimit.
    it('U8 (i) the administrator and an ordinary user beyond the limit: the banner with the backend numbers', async () => {
        query.mockResolvedValue({ data: { license: structuredClone(U8_OVER_I) } })
        const w = await mountAs(GLOBAL_ADMIN)
        expect(banner(w).text()).toBe(U8_BANNER_I)
        expect(overLicenseBannerText(U8_OVER_I)).toBe(U8_BANNER_I)
    })

    it('U8 (ii) the administrator the only one beyond the limit: outsideLimit 0, no banner', async () => {
        query.mockResolvedValue({ data: { license: structuredClone(U8_OVER_II) } })
        const w = await mountAs(GLOBAL_ADMIN)
        expect(query).toHaveBeenCalledTimes(1)
        expect(overLicenseBannerText(U8_OVER_II)).toBeNull()
        expect(banner(w).exists()).toBe(false)
    })
})

describe('AppWrapper mounts the banner above the routed page', () => {
    const source = readFileSync(resolve(__dirname, 'AppWrapper.vue'), 'utf8')
    it('between the top bar and the left navigation (which holds the router view), once the user is known', () => {
        const template = source.slice(0, source.indexOf('</template>'))
        const top = template.indexOf('<top-nav-bar />')
        const bannerAt = template.indexOf('<license-over-limit-banner v-if="myUser && myUser.uuid" />')
        const left = template.indexOf('<left-nav-bar />')
        expect(top).toBeGreaterThan(-1)
        expect(bannerAt).toBeGreaterThan(top)
        expect(left).toBeGreaterThan(bannerAt)
        expect(source).toMatch(/import LicenseOverLimitBanner from '\.\/LicenseOverLimitBanner\.vue'/)
    })
    it('passes a seat refusal from fetchMyUser to handleFetchUserResult', () => {
        expect(source).toMatch(/if \(!fetchUserResult \|\| fetchUserResult\._seatRefused \|\| fetchUserResult\._unauthorized \|\| fetchUserResult\._offline\) \{/)
    })
})
