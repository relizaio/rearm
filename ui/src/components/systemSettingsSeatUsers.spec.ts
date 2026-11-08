// @vitest-environment happy-dom
//
// TPF-168 U5 (TPF-165 4.2, D10): the license seat view of System Settings. One row per email with
// type, created, last login and the outside-limit flag; the counts header from globalUserCounts;
// Refresh re-queries with fetchPolicy no-cache; "Remove accounts with no role" asks for
// confirmation naming the number of accounts, sends the mutation, then re-queries.
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'

const query = vi.fn()
const mutate = vi.fn()
const fire = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
vi.mock('@/utils/keycloak', () => ({ default: { token: 'test', updateToken: vi.fn(), login: vi.fn(), logout: vi.fn() } }))
vi.mock('sweetalert2', () => ({ default: { fire: (...a: any[]) => fire(...a) } }))

const { default: LicenseSeatUsers } = await import('./LicenseSeatUsers.vue')
const { LICENSE_SEAT_USERS_QUERY, REMOVE_ACCOUNTS_WITHOUT_ROLE } = await import('@/utils/licenseSeats')

const ROWS = [
    { email: 'w1@x.io', name: 'Writer One', userUuids: ['a'], organizations: ['o'], seatType: 'WRITE', admission: 'INSIDE',
        createdDate: '2026-01-01T10:00:00Z', lastLoginDate: '2026-11-01T10:00:00Z', globalAdmin: true },
    { email: 'w2@x.io', name: null, userUuids: ['b'], organizations: ['o'], seatType: 'WRITE', admission: 'OUTSIDE',
        createdDate: '2026-02-01T10:00:00Z', lastLoginDate: null, globalAdmin: false },
    { email: 'r1@x.io', name: null, userUuids: ['c'], organizations: ['o'], seatType: 'READ', admission: 'INSIDE',
        createdDate: '2026-03-01T10:00:00Z', lastLoginDate: null, globalAdmin: false },
    { email: 'n1@x.io', name: null, userUuids: ['d'], organizations: ['o'], seatType: 'NONE', admission: 'NOT_COUNTED',
        createdDate: '2026-04-01T10:00:00Z', lastLoginDate: null, globalAdmin: false },
]
const LICENSE = { maxWriteUsers: 1, maxReadUsers: 1,
    globalUserCounts: { writeUsers: 2, readUsers: 1, totalUsers: 3, noRoleUsers: 1, outsideLimit: 1, countedAt: '2026-11-08T10:00:00Z' } }

const mounted: VueWrapper[] = []
async function mountView () {
    const w = mount(LicenseSeatUsers)
    mounted.push(w)
    await flushPromises()
    return w
}
const byId = (w: VueWrapper, id: string) => w.find('[data-testid="' + id + '"]')
const click = async (w: VueWrapper, id: string) => { await byId(w, id).find('button').trigger('click'); await flushPromises() }
const dateText = (d: string) => new Date(d).toLocaleString('en-CA')

describe('System Settings: license seat users', () => {
    beforeEach(() => {
        query.mockReset()
        mutate.mockReset()
        fire.mockReset()
        query.mockImplementation(async () => ({ data: { licenseSeatUsers: structuredClone(ROWS), license: structuredClone(LICENSE) } }))
    })
    afterEach(() => {
        while (mounted.length) mounted.pop()?.unmount()
    })

    it('reads the view with no-cache and shows one row per email with every column', async () => {
        const w = await mountView()
        expect(query).toHaveBeenCalledTimes(1)
        expect(query.mock.calls[0][0].query).toBe(LICENSE_SEAT_USERS_QUERY)
        expect(query.mock.calls[0][0].fetchPolicy).toBe('no-cache')
        expect(w.findAll('thead th').map(t => t.text())).toEqual(['Email', 'Type', 'Created', 'Last login', 'Outside limit'])
        expect(w.findAll('tbody tr')).toHaveLength(4)
        expect(byId(w, 'seat-user-type-w1@x.io').text()).toBe('WRITE')
        expect(byId(w, 'seat-user-type-r1@x.io').text()).toBe('READ')
        expect(byId(w, 'seat-user-type-n1@x.io').text()).toBe('NONE')
        expect(byId(w, 'seat-user-created-w1@x.io').text()).toBe(dateText('2026-01-01T10:00:00Z'))
        expect(byId(w, 'seat-user-last-login-w1@x.io').text()).toBe(dateText('2026-11-01T10:00:00Z'))
        expect(byId(w, 'seat-user-last-login-w2@x.io').text()).toBe('never')
        expect(byId(w, 'seat-user-outside-w2@x.io').text()).toBe('outside the limit')
        for (const e of ['w1@x.io', 'r1@x.io', 'n1@x.io']) expect(byId(w, 'seat-user-outside-' + e).text()).toBe('')
        expect(byId(w, 'seat-user-global-admin-w1@x.io').exists()).toBe(true)
        expect(byId(w, 'seat-user-global-admin-w2@x.io').exists()).toBe(false)
    })

    it('shows the counts header from globalUserCounts and emits the fresh license', async () => {
        const w = await mountView()
        expect(byId(w, 'seat-counts-write').text()).toBe('Write: 2 / 1')
        expect(byId(w, 'seat-counts-read').text()).toBe('Read: 1 / 1')
        expect(byId(w, 'seat-counts-norole').text()).toBe('No role (not counted): 1')
        expect(byId(w, 'seat-counts-outside').text()).toBe('Outside the limit: 1')
        expect(byId(w, 'seat-counts-at').text()).toBe('counted ' + dateText('2026-11-08T10:00:00Z'))
        expect(w.emitted('license')?.[0]).toEqual([LICENSE])
    })

    it('Refresh re-queries with no-cache and shows the new rows', async () => {
        const w = await mountView()
        query.mockImplementation(async () => ({ data: { licenseSeatUsers: structuredClone(ROWS.slice(0, 1)), license: structuredClone(LICENSE) } }))
        await click(w, 'seat-users-refresh')
        expect(query).toHaveBeenCalledTimes(2)
        expect(query.mock.calls[1][0].fetchPolicy).toBe('no-cache')
        expect(w.findAll('tbody tr')).toHaveLength(1)
    })

    it('Remove accounts with no role asks first, naming the number, then sends the mutation and re-queries', async () => {
        const w = await mountView()
        fire.mockResolvedValueOnce({ isConfirmed: true }).mockResolvedValue({})
        mutate.mockResolvedValue({ data: { removeAccountsWithoutRole: 1 } })
        await click(w, 'seat-users-remove-no-role')
        expect(fire.mock.calls[0][0].title).toBe('Remove accounts with no role')
        expect(fire.mock.calls[0][0].text).toMatch(/^Deactivate 1 account with no role in any organization\?/)
        expect(fire.mock.calls[0][0].showCancelButton).toBe(true)
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mutate.mock.calls[0][0].mutation).toBe(REMOVE_ACCOUNTS_WITHOUT_ROLE)
        expect(fire.mock.calls[1]).toEqual(['Done', '1 account deactivated.', 'success'])
        expect(query).toHaveBeenCalledTimes(2)
    })

    it('a cancelled confirmation sends nothing', async () => {
        const w = await mountView()
        fire.mockResolvedValue({ isConfirmed: false, isDismissed: true })
        await click(w, 'seat-users-remove-no-role')
        expect(mutate).not.toHaveBeenCalled()
        expect(query).toHaveBeenCalledTimes(1)
    })

    it('a refused mutation shows the server text and still re-queries', async () => {
        const w = await mountView()
        fire.mockResolvedValueOnce({ isConfirmed: true }).mockResolvedValue({})
        const err: any = new Error('Not authorized')
        err.errors = [{ message: 'Not authorized' }]
        mutate.mockRejectedValue(err)
        await click(w, 'seat-users-remove-no-role')
        expect(fire.mock.calls[1]).toEqual(['Error!', 'Not authorized', 'error'])
        expect(query).toHaveBeenCalledTimes(2)
    })

    it('a refused read shows the server text instead of the table', async () => {
        const err: any = new Error('Not authorized')
        err.errors = [{ message: 'Not authorized' }]
        query.mockRejectedValue(err)
        const w = await mountView()
        expect(byId(w, 'seat-users-error').text()).toBe('Not authorized')
        expect(byId(w, 'seat-users-table').exists()).toBe(false)
    })
})

describe('SystemSettings mounts the view in the license block', () => {
    const page = readFileSync(resolve(__dirname, 'SystemSettings.vue'), 'utf8')
    it('inside the licensed branch, after Update License, and merges the counts it emits', () => {
        const licensed = page.slice(page.indexOf('<div v-if="licenseData">'), page.indexOf('<div v-else>'))
        expect(licensed).toMatch(/Update License<\/n-button>\s*<LicenseSeatUsers @license="onSeatLicense" \/>/)
        expect(licensed).toContain('licenseData.globalUserCounts?.noRoleUsers')
        expect(licensed).toContain('licenseData.globalUserCounts?.outsideLimit')
        expect(page).toMatch(/import LicenseSeatUsers from '\.\/LicenseSeatUsers\.vue'/)
        expect(page).toMatch(/licenseData\.value = \{ \.\.\.\(licenseData\.value \|\| \{\}\), \.\.\.fresh \}/)
    })
})
