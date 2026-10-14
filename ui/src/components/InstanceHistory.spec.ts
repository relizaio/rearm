// @vitest-environment happy-dom
//
// Instance history labels who changed a revision (task t20261013-191133-18220): the rows show at once, a key
// that changed something reads as its key label once the keys arrive (admins), and as Key and its short id
// otherwise; an automatic change reads System; the cell's tooltip is the full uuid.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import InstanceHistory from './InstanceHistory.vue'

const ORG = 'org-1'
const KEY = '77781336-0000-4000-8000-000000000001'
const DOT = ' \u00B7 '
const dispatch = vi.fn()
const getters: any = { myuser: null, allUsers: [], instanceById: () => ({}) }
vi.mock('vuex', () => ({ useStore: () => ({ dispatch, getters }) }))
vi.mock('@/utils/commonFunctions', () => ({
    default: {
        isAdmin: (org: string, myUser: any) => (myUser?.permissions?.permissions || [])
            .some((p: any) => p.org === org && p.object === org && p.scope === 'ORGANIZATION' && p.type === 'ADMIN')
    }
}))
vi.mock('@/components/SideBySide.vue', () => ({ default: { template: '<div />' } }))

const HISTORY = [
    { uuid: 'rev-2', instanceUuid: 'i-1', revision: 2, date: '2026-10-14T10:00:00Z', updateType: 'API', updatedBy: KEY, changeType: 'PRODUCT_RELEASE' },
    { uuid: 'rev-1', instanceUuid: 'i-1', revision: 1, date: '2026-10-14T09:00:00Z', updateType: 'MANUAL', updatedBy: 'u-1', changeType: 'PROPERTY' },
    { uuid: 'rev-0', instanceUuid: 'i-1', revision: 0, date: '2026-10-14T08:00:00Z', updateType: 'AUTO', updatedBy: null, changeType: 'DEPLOYMENT' }
]
const USERS = [{ uuid: 'u-1', name: 'Ada', email: 'ada@example.com' }]
const KEYS = [{ uuid: KEY, type: 'FREEFORM', declaredName: 'CI pipeline', notes: null, status: 'ACTIVE' }]

function updatedBy (w: any): string[] {
    return w.findAll('tbody tr').map((r: any) => r.findAll('td')[2].text())
}

function respondWith (users: any, keys: any) {
    dispatch.mockImplementation((action: string) => action === 'fetchUsers' ? users : action === 'fetchApiKeyRefsOfOrg' ? keys : Promise.resolve(null))
}

describe('InstanceHistory updated by', () => {
    beforeEach(() => {
        dispatch.mockReset()
        getters.myuser = { isGlobalAdmin: true }
        getters.allUsers = []
    })

    it('shows the rows before the users and keys arrive, then relabels them', async () => {
        let giveUsers: (u: any[]) => void = () => {}
        let giveKeys: (k: any[]) => void = () => {}
        respondWith(new Promise((r) => { giveUsers = r }), new Promise((r) => { giveKeys = r }))
        const w = mount(InstanceHistory, { props: { instanceUuid: 'i-1', history: HISTORY, orgProp: ORG } })
        await flushPromises()
        expect(updatedBy(w)).toEqual([KEY, 'u-1', 'System'])

        giveUsers(USERS)
        giveKeys(KEYS)
        await flushPromises()
        expect(updatedBy(w)).toEqual(['Free Form' + DOT + 'CI pipeline' + DOT + '77781336', 'Ada <ada@example.com>', 'System'])
        expect(w.findAll('tbody tr')[0].findAll('td')[2].find('span').attributes('title')).toBe(KEY)
        expect(dispatch).toHaveBeenCalledWith('fetchUsers', ORG)
        expect(dispatch).toHaveBeenCalledWith('fetchApiKeyRefsOfOrg', ORG)
    })

    it('asks no keys for a viewer who may not list them, and shows a key by its short id', async () => {
        getters.myuser = { isGlobalAdmin: false, permissions: { permissions: [{ org: ORG, object: ORG, scope: 'ORGANIZATION', type: 'READ_ONLY' }] } }
        respondWith(Promise.resolve(USERS), Promise.resolve(KEYS))
        const w = mount(InstanceHistory, { props: { instanceUuid: 'i-1', history: HISTORY, orgProp: ORG } })
        await flushPromises()
        expect(dispatch).not.toHaveBeenCalledWith('fetchApiKeyRefsOfOrg', ORG)
        expect(updatedBy(w)[0]).toBe('Key' + DOT + '77781336')
    })

    it('reads System for an automatic change, with or without who made it, and the type for an unnamed other change', async () => {
        respondWith(Promise.resolve(USERS), Promise.resolve(KEYS))
        const history = [
            { uuid: 'rev-1', instanceUuid: 'i-1', revision: 1, date: '2026-10-14T09:00:00Z', updateType: 'AUTO', updatedBy: 'u-1', changeType: 'PROPERTY' },
            { uuid: 'rev-0', instanceUuid: 'i-1', revision: 0, date: '2026-10-14T08:00:00Z', updateType: 'MANUAL', updatedBy: null, changeType: 'PROPERTY' }
        ]
        const w = mount(InstanceHistory, { props: { instanceUuid: 'i-1', history, orgProp: ORG } })
        await flushPromises()
        expect(updatedBy(w)).toEqual(['System', 'MANUAL'])
    })

    it('follows the history it is given, and reads a legacy row by its createdType', async () => {
        respondWith(Promise.resolve(USERS), Promise.resolve(KEYS))
        const w = mount(InstanceHistory, { props: { instanceUuid: 'i-1', history: HISTORY.slice(1), orgProp: ORG } })
        await flushPromises()
        expect(updatedBy(w)).toEqual(['Ada <ada@example.com>', 'System'])
        const legacy = { uuid: 'rev-3', instance: 'i-1', revision: 3, dateActual: '2026-10-14T11:00:00Z', createdType: 'API', lastUpdatedBy: KEY, type: 'PROPERTY' }
        await w.setProps({ history: [legacy, ...HISTORY.slice(1)] })
        expect(updatedBy(w)).toEqual(['Free Form' + DOT + 'CI pipeline' + DOT + '77781336', 'Ada <ada@example.com>', 'System'])
    })

    it('names people from the store at once, before its own fetch answers', async () => {
        getters.allUsers = USERS
        respondWith(new Promise(() => {}), Promise.resolve(KEYS))
        const w = mount(InstanceHistory, { props: { instanceUuid: 'i-1', history: HISTORY, orgProp: ORG } })
        await flushPromises()
        expect(updatedBy(w)[1]).toBe('Ada <ada@example.com>')
    })
})
