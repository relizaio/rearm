// @vitest-environment happy-dom
//
// System Settings, Update License (task TPF-166 U3, D2): the seat view's counts header is the page's
// only copy of the instance seat figures, so a successful upload reloads it through the seat view's
// exposed reload (the Refresh path) and the new limits and outside count show with no Refresh click;
// a failed upload reloads nothing. The page is mounted with the real Vuex store and the GraphQL client
// mocked per operation name (the SystemSettingsTeaDomains.spec.ts rig); the upload is the page's own
// uploadLicense, which the Submit button of the modal calls.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'

const query = vi.fn()
const mutate = vi.fn()
const fire = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
vi.mock('@/utils/keycloak', () => ({ default: { token: 'test', updateToken: vi.fn(), login: vi.fn() } }))
vi.mock('sweetalert2', () => ({ default: { fire: (...a: any[]) => fire(...a) } }))

const store = (await import('@/store')).default.store
const { default: SystemSettings } = await import('./SystemSettings.vue')

const PRO_ADMIN = { uuid: 'u1', installationType: 'MANAGED_SERVICE', isGlobalAdmin: true }
const BLOCK = { baseUri: 'https://rearm.example.com', validFrom: '2026-01-01T00:00:00Z', validTo: '2027-01-01T00:00:00Z' }
const NEW_BLOCK = { baseUri: 'https://rearm.example.com', validFrom: '2026-11-01T00:00:00Z', validTo: '2027-11-01T00:00:00Z' }
const OLD_LICENSE = { maxWriteUsers: 2, maxReadUsers: 2,
    globalUserCounts: { writeUsers: 3, readUsers: 2, totalUsers: 5, noRoleUsers: 4, outsideLimit: 1, countedAt: '2026-11-09T08:00:00Z' } }
const NEW_LICENSE = { maxWriteUsers: 5, maxReadUsers: 3,
    globalUserCounts: { writeUsers: 3, readUsers: 2, totalUsers: 5, noRoleUsers: 4, outsideLimit: 0, countedAt: '2026-11-09T09:00:00Z' } }

const operation = (opts: any) => (opts.query ?? opts.mutation)?.definitions?.[0]?.name?.value
const byId = (w: any, id: string) => w.find('[data-testid="' + id + '"]')
const callsOf = (name: string) => query.mock.calls.filter(c => operation(c[0]) === name)

let seatLicense: any = OLD_LICENSE

function answers () {
    query.mockImplementation(async (opts: any) => {
        switch (operation(opts)) {
        case 'user': return { data: { user: structuredClone(PRO_ADMIN) } }
        case 'getSystemInfoIsSet': return { data: { getSystemInfoIsSet: { emailDetailsSet: false, defaultOrg: null, defaultOrgDetails: null } } }
        case 'getLicense': return { data: { license: structuredClone(BLOCK) } }
        case 'licenseSeatUsers': return { data: { licenseSeatUsers: [], license: structuredClone(seatLicense) } }
        case 'TeaHostAssignments': return { data: { teaHostAssignments: [] } }
        case 'AllOrganizations': return { data: { allOrganizations: [] } }
        default: throw new Error('unexpected query ' + operation(opts))
        }
    })
}

const mounted: VueWrapper[] = []

async function mountPage () {
    const w = mount(SystemSettings, { global: { plugins: [store] } })
    mounted.push(w)
    await flushPromises()
    await flushPromises()
    return w
}

describe('System Settings, Update License reloads the seat counts header', () => {
    beforeEach(() => {
        query.mockReset()
        mutate.mockReset()
        fire.mockReset()
        seatLicense = OLD_LICENSE
        store.commit('SET_MY_USER', {})
        answers()
    })

    afterEach(() => {
        mounted.splice(0).forEach(w => w.unmount())
    })

    it('U3: a successful upload shows the new limits and outside count with no Refresh click', async () => {
        const w = await mountPage()
        expect(byId(w, 'seat-counts-write').text()).toBe('Write: 3 / 2')
        expect(byId(w, 'seat-counts-outside').text()).toBe('Outside the limit: 1')
        const seatReadsAtMount = callsOf('licenseSeatUsers').length
        expect(seatReadsAtMount).toBe(1)

        seatLicense = NEW_LICENSE
        mutate.mockResolvedValueOnce({ data: { uploadLicense: structuredClone(NEW_BLOCK) } })
        await (w.vm as any).uploadLicense()
        await flushPromises()

        expect(mutate).toHaveBeenCalledTimes(1)
        expect(operation(mutate.mock.calls[0][0])).toBe('uploadLicense')
        expect(callsOf('licenseSeatUsers')).toHaveLength(seatReadsAtMount + 1)
        expect(callsOf('licenseSeatUsers').at(-1)?.[0].fetchPolicy).toBe('no-cache')
        expect(byId(w, 'seat-counts-write').text()).toBe('Write: 3 / 5')
        expect(byId(w, 'seat-counts-read').text()).toBe('Read: 2 / 3')
        expect(byId(w, 'seat-counts-total').text()).toBe('Total: 5 / 8')
        expect(byId(w, 'seat-counts-outside').text()).toBe('Outside the limit: 0')
        expect(w.text()).toContain(new Date(NEW_BLOCK.validTo).toLocaleDateString())
        expect(fire).toHaveBeenCalledWith('Success!', 'License uploaded successfully.', 'success')
    })

    it('U3: a failed upload issues no reload and leaves the header as it was', async () => {
        const w = await mountPage()
        const seatReadsAtMount = callsOf('licenseSeatUsers').length
        seatLicense = NEW_LICENSE
        mutate.mockRejectedValueOnce(new Error('Invalid license'))
        await (w.vm as any).uploadLicense()
        await flushPromises()

        expect(mutate).toHaveBeenCalledTimes(1)
        expect(callsOf('licenseSeatUsers')).toHaveLength(seatReadsAtMount)
        expect(byId(w, 'seat-counts-write').text()).toBe('Write: 3 / 2')
        expect(byId(w, 'seat-counts-outside').text()).toBe('Outside the limit: 1')
    })
})

