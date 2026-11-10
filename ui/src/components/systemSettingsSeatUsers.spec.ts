// @vitest-environment happy-dom
//
// TPF-168 U5 (TPF-165 4.2, D10): the license seat view of System Settings. One row per email with
// type, created, last login and the outside-limit flag; the counts header from globalUserCounts;
// Refresh re-queries with fetchPolicy no-cache; "Remove accounts with no role" asks for
// confirmation naming the number of accounts, sends the mutation, then re-queries. U8 (T-1): a global
// administrator beyond the write limit is shown as the backend reports it, flag and marker apart.
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

const store = (await import('@/store')).default.store
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

const MARKUP_REFUSAL = 'Not <b>authorized</b>'

const mounted: VueWrapper[] = []
const ME = { uuid: 'me-id', email: 'me@x.io', installationType: 'MANAGED_SERVICE', isGlobalAdmin: true, permissions: { permissions: [] } }
async function mountView (me: any = ME) {
    store.commit('SET_MY_USER', structuredClone(me))
    const w = mount(LicenseSeatUsers, { global: { plugins: [store] } })
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
        expect(w.findAll('thead th').map(t => t.text())).toEqual(['Email', 'Name', 'Type', 'Created', 'Last login', 'Outside limit'])
        expect(w.findAll('tbody tr')).toHaveLength(4)
        expect(byId(w, 'seat-user-type-w1@x.io').text()).toBe('WRITE')
        expect(byId(w, 'seat-user-type-r1@x.io').text()).toBe('READ')
        expect(byId(w, 'seat-user-type-n1@x.io').text()).toBe('NONE')
        expect(byId(w, 'seat-user-created-w1@x.io').text()).toBe(dateText('2026-01-01T10:00:00Z'))
        expect(byId(w, 'seat-user-last-login-w1@x.io').text()).toBe(dateText('2026-11-01T10:00:00Z'))
        expect(byId(w, 'seat-user-last-login-w2@x.io').text()).toBe('never')
        expect(byId(w, 'seat-user-outside-w2@x.io').text()).toBe('outside the limit')
        for (const e of ['w1@x.io', 'r1@x.io', 'n1@x.io']) expect(byId(w, 'seat-user-outside-' + e).text()).toBe('')
        expect(byId(w, 'seat-user-global-admin-w1@x.io').text()).toBe('Global administrator')
        expect(byId(w, 'seat-user-global-admin-w2@x.io').exists()).toBe(false)
        expect(byId(w, 'seat-user-name-w1@x.io').text()).toBe('Writer One')
        expect(byId(w, 'seat-user-name-w2@x.io').text()).toBe('')
    })

    it('shows the counts header from globalUserCounts and emits nothing', async () => {
        const w = await mountView()
        expect(byId(w, 'seat-counts-write').text()).toBe('Write: 2 / 1')
        expect(byId(w, 'seat-counts-read').text()).toBe('Read: 1 / 1')
        expect(byId(w, 'seat-counts-total').text()).toBe('Total: 3 / 2')
        expect(byId(w, 'seat-counts-norole').text()).toBe('Users without a role (not counted): 1')
        expect(byId(w, 'seat-counts-outside').text()).toBe('Outside the limit: 1')
        expect(byId(w, 'seat-counts-at').text()).toBe('counted ' + dateText('2026-11-08T10:00:00Z'))
        expect(w.emitted('license')).toBeUndefined()
    })

    // TPF-166 U3 (D2): the one counts header of the page, with the shared-capacity Total line.
    it('U3: the header lines exactly, and Total unlimited when either limit is null', async () => {
        const counts = { writeUsers: 3, readUsers: 2, totalUsers: 5, noRoleUsers: 4, outsideLimit: 1, countedAt: '2026-11-09T08:00:00Z' }
        const header = (w: VueWrapper) => byId(w, 'seat-counts').findAll('span').map(e => e.text())
        query.mockImplementation(async () => ({ data: { licenseSeatUsers: [],
            license: { maxWriteUsers: 2, maxReadUsers: 2, globalUserCounts: structuredClone(counts) } } }))
        const w = await mountView()
        expect(header(w)).toEqual(['Write: 3 / 2', 'Read: 2 / 2', 'Total: 5 / 4',
            'Users without a role (not counted): 4', 'Outside the limit: 1', 'counted ' + dateText('2026-11-09T08:00:00Z')])
        for (const limits of [{ maxWriteUsers: 2, maxReadUsers: null }, { maxWriteUsers: null, maxReadUsers: 2 }]) {
            query.mockImplementation(async () => ({ data: { licenseSeatUsers: [],
                license: { ...limits, globalUserCounts: structuredClone(counts) } } }))
            const v = await mountView()
            expect(byId(v, 'seat-counts-total').text()).toBe('Total: 5 / unlimited')
        }
    })

    it('U3: the view exposes reload, the Refresh path (a no-cache re-query)', async () => {
        const w = await mountView()
        expect(typeof (w.vm as any).reload).toBe('function')
        await (w.vm as any).reload()
        expect(query).toHaveBeenCalledTimes(2)
        expect(query.mock.calls[1][0].query).toBe(LICENSE_SEAT_USERS_QUERY)
        expect(query.mock.calls[1][0].fetchPolicy).toBe('no-cache')
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
        expect(fire.mock.calls[0][0].text).toBe('Deactivate 1 account with no role in any organization? Account to deactivate: n1@x.io. '
            + 'Global administrators and your own account are never deactivated. A deactivated account can be reactivated later.')
        expect(fire.mock.calls[0][0].showCancelButton).toBe(true)
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mutate.mock.calls[0][0].mutation).toBe(REMOVE_ACCOUNTS_WITHOUT_ROLE)
        expect(fire.mock.calls[1]).toEqual([{ title: 'Done', text: '1 account deactivated.', icon: 'success' }])
        expect(query).toHaveBeenCalledTimes(2)
    })

    it('a cancelled confirmation sends nothing', async () => {
        const w = await mountView()
        fire.mockResolvedValue({ isConfirmed: false, isDismissed: true })
        await click(w, 'seat-users-remove-no-role')
        expect(mutate).not.toHaveBeenCalled()
        expect(query).toHaveBeenCalledTimes(1)
    })

    it('a refused mutation shows the server text as plain text, never as markup, and still re-queries', async () => {
        const w = await mountView()
        fire.mockResolvedValueOnce({ isConfirmed: true }).mockResolvedValue({})
        const err: any = new Error(MARKUP_REFUSAL)
        err.errors = [{ message: MARKUP_REFUSAL }]
        mutate.mockRejectedValue(err)
        await click(w, 'seat-users-remove-no-role')
        // F-4: the positional Swal.fire(title, msg, icon) shorthand would put msg in `html`.
        expect(fire.mock.calls[1]).toHaveLength(1)
        expect(fire.mock.calls[1][0]).toEqual({ title: 'Error!', text: MARKUP_REFUSAL, icon: 'error' })
        expect(fire.mock.calls[1][0]).not.toHaveProperty('html')
        expect(query).toHaveBeenCalledTimes(2)
    })

    it('a refused read shows the server text as plain text instead of the table', async () => {
        const err: any = new Error(MARKUP_REFUSAL)
        err.errors = [{ message: MARKUP_REFUSAL }]
        query.mockRejectedValue(err)
        const w = await mountView()
        expect(byId(w, 'seat-users-error').text()).toBe(MARKUP_REFUSAL)
        expect(byId(w, 'seat-users-error').find('b').exists()).toBe(false)
        expect(byId(w, 'seat-users-table').exists()).toBe(false)
    })

    it('every dialog of the view passes its text as text: no positional Swal.fire shorthand, no html', () => {
        const source = readFileSync(resolve(__dirname, 'LicenseSeatUsers.vue'), 'utf8')
        expect(source).not.toMatch(/Swal\.fire\(\s*['"`]/)
        expect(source).not.toMatch(/\bhtml\s*:/)
        expect(source).not.toContain('v-html')
        expect(source).not.toContain('innerHTML')
    })
})

// TPF-168 U8 (architecture round 3, T-1): a global administrator created beyond the write limit,
// under the creation-date rank of decision 4 and the admission contract the backend reports
// (an ACTIVE global administrator is always admitted: counted WRITE, admission INSIDE, never in
// outsideLimit). The view prints every field as given: the flag from `admission` alone, the marker
// from `globalAdmin` alone, the counts header unchanged.
const u8Row = (email: string, createdDate: string, admission: string, globalAdmin: boolean) => ({
    email, name: null, userUuids: [email], organizations: ['o'], seatType: 'WRITE', admission,
    createdDate, lastLoginDate: null, globalAdmin })
const U8_ROWS_I = [
    u8Row('a@x.io', '2024-01-01T00:00:00Z', 'INSIDE', false),
    u8Row('b@x.io', '2024-02-01T00:00:00Z', 'INSIDE', false),
    u8Row('admin@x.io', '2025-01-01T00:00:00Z', 'INSIDE', true),
    u8Row('c@x.io', '2025-02-01T00:00:00Z', 'OUTSIDE', false),
]
const U8_LICENSE_I = { maxWriteUsers: 2, maxReadUsers: 1,
    globalUserCounts: { writeUsers: 4, readUsers: 0, totalUsers: 4, noRoleUsers: 0, outsideLimit: 1, countedAt: '2026-11-08T10:00:00Z' } }
const U8_ROWS_II = U8_ROWS_I.filter(r => r.email !== 'c@x.io')
const U8_LICENSE_II = { maxWriteUsers: 2, maxReadUsers: 1,
    globalUserCounts: { writeUsers: 3, readUsers: 0, totalUsers: 3, noRoleUsers: 0, outsideLimit: 0, countedAt: '2026-11-08T10:00:00Z' } }

describe('T-1 (U8): a global administrator beyond the write limit', () => {
    beforeEach(() => {
        query.mockReset()
        mutate.mockReset()
        fire.mockReset()
    })
    afterEach(() => {
        while (mounted.length) mounted.pop()?.unmount()
    })
    const answer = (rows: any[], license: any) =>
        query.mockImplementation(async () => ({ data: { licenseSeatUsers: structuredClone(rows), license: structuredClone(license) } }))

    it('(i) the administrator and an ordinary user beyond the limit: only the ordinary user is flagged', async () => {
        answer(U8_ROWS_I, U8_LICENSE_I)
        const w = await mountView()
        expect(byId(w, 'seat-user-outside-c@x.io').text()).toBe('outside the limit')
        expect(byId(w, 'seat-user-outside-admin@x.io').text()).toBe('')
        expect(byId(w, 'seat-user-global-admin-admin@x.io').text()).toBe('Global administrator')
        for (const e of ['a@x.io', 'b@x.io']) {
            expect(byId(w, 'seat-user-outside-' + e).text()).toBe('')
            expect(byId(w, 'seat-user-global-admin-' + e).exists()).toBe(false)
        }
        expect(byId(w, 'seat-user-global-admin-c@x.io').exists()).toBe(false)
        expect(byId(w, 'seat-counts-write').text()).toBe('Write: 4 / 2')
        expect(byId(w, 'seat-counts-read').text()).toBe('Read: 0 / 1')
        expect(byId(w, 'seat-counts-outside').text()).toBe('Outside the limit: 1')
    })

    it('(ii) the administrator the only one beyond the limit: no row flagged, the counts header shows the overflow', async () => {
        answer(U8_ROWS_II, U8_LICENSE_II)
        const w = await mountView()
        expect(w.findAll('tbody tr')).toHaveLength(3)
        for (const e of ['a@x.io', 'b@x.io', 'admin@x.io']) expect(byId(w, 'seat-user-outside-' + e).text()).toBe('')
        expect(byId(w, 'seat-user-global-admin-admin@x.io').text()).toBe('Global administrator')
        expect(byId(w, 'seat-counts-write').text()).toBe('Write: 3 / 2')
        expect(byId(w, 'seat-counts-outside').text()).toBe('Outside the limit: 0')
    })

    it('the flag depends on admission alone: globalAdmin neither adds nor removes it', async () => {
        // A row the backend should never send once it reports the admission decision; the UI
        // shows it as given rather than hiding a backend defect.
        answer([u8Row('admin@x.io', '2025-01-01T00:00:00Z', 'OUTSIDE', true),
            u8Row('c@x.io', '2025-02-01T00:00:00Z', 'INSIDE', false)], U8_LICENSE_I)
        const w = await mountView()
        expect(byId(w, 'seat-user-outside-admin@x.io').text()).toBe('outside the limit')
        expect(byId(w, 'seat-user-global-admin-admin@x.io').text()).toBe('Global administrator')
        expect(byId(w, 'seat-user-outside-c@x.io').text()).toBe('')
        expect(byId(w, 'seat-counts-outside').text()).toBe('Outside the limit: 1')
    })

    it('the admission cell reads admission only, and the marker reads globalAdmin only', () => {
        const source = readFileSync(resolve(__dirname, 'LicenseSeatUsers.vue'), 'utf8')
        const cell = source.split('\n').filter(l => l.includes("testid('seat-user-outside-'")).join('\n')
            + source.split('\n').find(l => l.startsWith('    r.admission === SeatAdmission.OUTSIDE'))
        expect(cell).toContain('r.admission === SeatAdmission.OUTSIDE')
        expect(cell).not.toContain('globalAdmin')
        const marker = source.slice(source.indexOf('const globalAdminMarker'), source.indexOf('const columns'))
        expect(marker).toContain('r.globalAdmin')
        expect(marker).not.toContain('admission')
    })
})

// License seats review follow-up: the no-role confirmation lists the emails the server deactivates.
describe('Remove accounts with no role: the listed accounts', () => {
    beforeEach(() => {
        query.mockReset()
        mutate.mockReset()
        fire.mockReset()
    })
    afterEach(() => {
        while (mounted.length) mounted.pop()?.unmount()
    })
    const none = (email: string, globalAdmin = false, userUuids = [email + '-id']) => ({ email, name: null, userUuids,
        organizations: [], seatType: 'NONE', admission: 'NOT_COUNTED', createdDate: '2026-01-01T00:00:00Z', lastLoginDate: null, globalAdmin })

    it('lists seat type NONE rows, never a global administrator, never the caller', async () => {
        query.mockImplementation(async () => ({ data: { license: structuredClone(LICENSE), licenseSeatUsers: [
            ...structuredClone(ROWS), none('ga@x.io', true), none('me@x.io', false, ['me-id']), none('z@x.io')] } }))
        const w = await mountView()
        fire.mockResolvedValue({ isConfirmed: false })
        await click(w, 'seat-users-remove-no-role')
        expect(fire.mock.calls[0][0].text).toMatch(/^Deactivate 2 accounts with no role in any organization\? Accounts to deactivate: (n1@x\.io, z@x\.io|z@x\.io, n1@x\.io)\. Global/)
        expect(fire.mock.calls[0][0].text).not.toContain('ga@x.io')
        expect(fire.mock.calls[0][0].text).not.toContain('me@x.io')
        expect(fire.mock.calls[0][0]).not.toHaveProperty('html')
    })

    it('a long list: the first 20, then "and N more"', async () => {
        const many = Array.from({ length: 23 }, (_, i) => none(`n${String(i + 1).padStart(2, '0')}@x.io`))
        query.mockImplementation(async () => ({ data: { license: structuredClone(LICENSE), licenseSeatUsers: many } }))
        const w = await mountView()
        fire.mockResolvedValue({ isConfirmed: false })
        await click(w, 'seat-users-remove-no-role')
        const text = fire.mock.calls[0][0].text
        expect(text).toMatch(/^Deactivate 23 accounts with no role/)
        expect(text).toContain(' and 3 more. Global administrators')
        expect((text.match(/@x\.io/g) || []).length).toBe(20)
    })
})

// License seats review follow-up: UI-side sorting and pagination of the seat view.
describe('the seat view table: default order, sorting and pagination', () => {
    beforeEach(() => {
        query.mockReset()
        mutate.mockReset()
        fire.mockReset()
    })
    afterEach(() => {
        while (mounted.length) mounted.pop()?.unmount()
    })
    const row = (email: string, admission: string, createdDate: string, extra: any = {}) => ({ email, name: null, userUuids: [email],
        organizations: ['o'], seatType: 'WRITE', admission, createdDate, lastLoginDate: null, globalAdmin: false, ...extra })
    const shown = (w: VueWrapper) => w.findAll('tbody tr').map(tr => tr.find('[data-testid^="seat-user-email-"]').attributes('data-testid')
        ?.replace('seat-user-email-', ''))
    const header = (w: VueWrapper, title: string) => w.findAll('thead th').find(t => t.text() === title)!

    it('opens with the accounts outside the limit first, then by creation date', async () => {
        query.mockImplementation(async () => ({ data: { license: structuredClone(LICENSE), licenseSeatUsers: [
            row('a@x.io', 'INSIDE', '2025-01-01T00:00:00Z'), row('b@x.io', 'OUTSIDE', '2026-02-01T00:00:00Z'),
            row('c@x.io', 'NOT_COUNTED', '2020-01-01T00:00:00Z', { seatType: 'NONE' }), row('d@x.io', 'OUTSIDE', '2026-01-01T00:00:00Z'),
            row('e@x.io', 'INSIDE', '2024-01-01T00:00:00Z')] } }))
        const w = await mountView()
        expect(shown(w)).toEqual(['d@x.io', 'b@x.io', 'e@x.io', 'a@x.io', 'c@x.io'])
    })

    it('every column sorts on a header click', async () => {
        query.mockImplementation(async () => ({ data: { license: structuredClone(LICENSE), licenseSeatUsers: [
            row('b@x.io', 'INSIDE', '2025-01-01T00:00:00Z', { name: 'Zed', seatType: 'READ', lastLoginDate: '2026-05-01T00:00:00Z' }),
            row('a@x.io', 'OUTSIDE', '2026-01-01T00:00:00Z', { name: 'Adam', seatType: 'WRITE', lastLoginDate: '2026-01-01T00:00:00Z' }),
            row('c@x.io', 'NOT_COUNTED', '2024-01-01T00:00:00Z', { seatType: 'NONE' })] } }))
        const expected: Record<string, string[]> = {
            'Email': ['a@x.io', 'b@x.io', 'c@x.io'],
            'Name': ['a@x.io', 'b@x.io', 'c@x.io'],
            'Type': ['a@x.io', 'b@x.io', 'c@x.io'],
            'Created': ['c@x.io', 'b@x.io', 'a@x.io'],
            'Last login': ['a@x.io', 'b@x.io', 'c@x.io'],
            'Outside limit': ['a@x.io', 'b@x.io', 'c@x.io'],
        }
        for (const [title, order] of Object.entries(expected)) {
            const w = await mountView()
            // naive-ui cycles descending, ascending, then back to the default order
            await header(w, title).trigger('click')
            await flushPromises()
            expect(shown(w), title + ' descending').toEqual([...order].reverse())
            await header(w, title).trigger('click')
            await flushPromises()
            expect(shown(w), title + ' ascending').toEqual(order)
        }
    })

    it('pages 20 rows at a time by default', async () => {
        const many = Array.from({ length: 45 }, (_, i) => row(`u${String(i + 1).padStart(2, '0')}@x.io`, 'INSIDE',
            new Date(Date.UTC(2020, 0, i + 1)).toISOString()))
        query.mockImplementation(async () => ({ data: { license: structuredClone(LICENSE), licenseSeatUsers: many } }))
        const w = await mountView()
        expect(w.findAll('tbody tr')).toHaveLength(20)
        expect(shown(w)[0]).toBe('u01@x.io')
        expect(w.find('.n-pagination').exists()).toBe(true)
        const page3 = w.findAll('.n-pagination-item').find(i => i.text() === '3')!
        await page3.trigger('click')
        await flushPromises()
        expect(shown(w)).toEqual(['u41@x.io', 'u42@x.io', 'u43@x.io', 'u44@x.io', 'u45@x.io'])
    })
})

describe('SystemSettings mounts the view in the license block', () => {
    const page = readFileSync(resolve(__dirname, 'SystemSettings.vue'), 'utf8')
    const view = readFileSync(resolve(__dirname, 'LicenseSeatUsers.vue'), 'utf8')
    const documentOf = (name: string) => {
        const start = page.indexOf(name)
        expect(start).toBeGreaterThan(-1)
        return page.slice(start, page.indexOf('`', start))
    }
    it('inside the licensed branch, after Update License, holding a ref to reload it (TPF-166 U3)', () => {
        const licensed = page.slice(page.indexOf('<div v-if="licenseData">'), page.indexOf('<div v-else>'))
        expect(licensed).toMatch(/Update License<\/n-button>\s*<LicenseSeatUsers ref="seatUsers" \/>/)
        expect(page).toMatch(/import LicenseSeatUsers from '\.\/LicenseSeatUsers\.vue'/)
        expect(page).toContain('const seatUsers = ref<InstanceType<typeof LicenseSeatUsers> | null>(null)')
        expect(page).toContain('await seatUsers.value?.reload()')
    })
    it('shows no second copy of the instance figures (TPF-166 D2)', () => {
        for (const gone of ['globalUserCounts', 'maxWriteUsers', 'maxReadUsers', 'license-no-role-users',
            'license-outside-limit', 'onSeatLicense', '@license']) {
            expect(page).not.toContain(gone)
        }
        for (const name of ['query getLicense', 'mutation uploadLicense']) {
            const doc = documentOf(name)
            expect(doc.split('{').length - 1).toBe(2)
            const selection = doc.slice(doc.lastIndexOf('{') + 1, doc.indexOf('}')).split(/\s+/).filter(Boolean)
            expect(selection).toEqual(['baseUri', 'validFrom', 'validTo'])
        }
        expect(view).not.toContain('defineEmits')
        expect(view).not.toContain("emit('license'")
        expect(view).toContain('defineExpose({ reload: load })')
    })
})
