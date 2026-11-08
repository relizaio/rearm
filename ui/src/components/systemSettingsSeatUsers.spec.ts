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
        expect(byId(w, 'seat-user-global-admin-w1@x.io').text()).toBe('Global administrator')
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
        const template = source.slice(0, source.indexOf('</template>'))
        const start = template.indexOf(`<td :data-testid="'seat-user-outside-' + r.email">`)
        expect(start).toBeGreaterThan(-1)
        const cell = template.slice(start, template.indexOf('</td>', start))
        expect(cell).toContain('r.admission === SeatAdmission.OUTSIDE')
        expect(cell).not.toContain('globalAdmin')
        const marker = template.split('\n').find(l => l.includes("'seat-user-global-admin-'")) ?? ''
        expect(marker).toContain('v-if="r.globalAdmin"')
        expect(marker).not.toContain('admission')
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
