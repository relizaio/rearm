// @vitest-environment happy-dom
//
// The Per-Board section of the permission editor (board-permissions.md §5; task 428b4a71): grants on
// one board, what each section offers, and a grant on a board the editor cannot list.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { NButton, NCheckbox, NCheckboxGroup, NRadioButton, NRadioGroup, NSelect } from 'naive-ui'
import constants from '@/utils/constants'

const dispatch = vi.fn(async () => [])
const myuser = { installationType: 'SAAS' }
vi.mock('vuex', () => ({ useStore: () => ({ dispatch, getters: { myuser } }) }))
// The graphql client connects on import; the editor never calls it here (the store is mocked).
vi.mock('@/utils/graphql', () => ({ default: {} }))
const { default: ScopedPermissions } = await import('./ScopedPermissions.vue')

const boards = [{ uuid: 'b1', name: 'Board One' }, { uuid: 'b2', name: 'Board Two' }]

function mountEditor (scopedPermissions: any[], orgType = 'READ_ONLY', extra: Record<string, any> = {}) {
    return mount(ScopedPermissions, {
        props: {
            orgUuid: 'o1', approvalRoles: [], boards,
            perspectives: [{ uuid: 'p1', name: 'Payments' }],
            products: [{ uuid: 'prod1', name: 'Shop' }],
            components: [{ uuid: 'c1', name: 'api' }],
            modelValue: { orgPermission: { type: orgType, functions: [], approvals: [] }, scopedPermissions },
            ...extra,
        },
    })
}

const NAMES: Record<string, string> = { p1: 'Payments', prod1: 'Shop', c1: 'api' }
const grant = (scope: string, objectId: string, type = 'READ_ONLY', functions: string[] = []) =>
    ({ scope, objectId, objectName: NAMES[objectId] ?? objectId, type, functions, approvals: [] })

const lastModel = (w: any) => {
    const e = w.emitted('update:modelValue') as any[][]
    return e[e.length - 1][0]
}

/** The function values a card offers. */
const offered = (card: any) => card.findAllComponents(NCheckbox).map((c: any) => c.props('value'))

describe('ScopedPermissions: Per-Board', () => {
    it('shows each BOARD grant by its board name; an unknown board by its uuid, still removable', async () => {
        const w = mountEditor([grant('BOARD', 'b1', 'READ_WRITE', ['BOARD_WRITE']), grant('BOARD', 'gone-uuid')])
        const cards = w.findAll('[data-testid="board-permission-card"]')
        expect(cards).toHaveLength(2)
        expect(cards[0].text()).toContain('Board One')
        expect(cards[0].text()).not.toContain('board not found')
        expect(cards[1].text()).toContain('gone-uuid')
        expect(cards[1].text()).toContain('board not found')

        await cards[1].find('[data-testid="board-permission-remove"]').trigger('click')
        const model = lastModel(w)
        expect(model.scopedPermissions.map((sp: any) => sp.objectId)).toEqual(['b1'])
    })

    it('offers only the boards not granted yet, and adds one as scope BOARD with its type and functions', async () => {
        const w = mountEditor([grant('BOARD', 'b1')])
        const select = w.findAllComponents(NSelect).find((s: any) => s.attributes('data-testid') === 'board-permission-select')!
        expect(select.props('options')).toEqual([{ label: 'Board Two', value: 'b2' }])

        await select.vm.$emit('update:value', 'b2')
        await w.find('[data-testid="board-permission-add"]').trigger('click')
        expect(lastModel(w).scopedPermissions.find((sp: any) => sp.objectId === 'b2'))
            .toMatchObject({ scope: 'BOARD', type: 'READ_ONLY', functions: [] })

        const card = w.findAll('[data-testid="board-permission-card"]')[1]
        await card.findComponent(NRadioGroup).vm.$emit('update:value', 'READ_WRITE')
        await card.findComponent(NCheckboxGroup).vm.$emit('update:value', ['BOARD_AGENT', 'BOARD_WRITE'])
        expect(lastModel(w).scopedPermissions.find((sp: any) => sp.objectId === 'b2'))
            .toMatchObject({ scope: 'BOARD', type: 'READ_WRITE', functions: ['BOARD_AGENT', 'BOARD_WRITE'] })
    })

    it('offers the board functions on a board, a perspective and the organization, never on a product or component', () => {
        const w = mountEditor([grant('BOARD', 'b1'), grant('PERSPECTIVE', 'p1'), grant('COMPONENT', 'prod1'), grant('COMPONENT', 'c1')])
        const board = w.find('[data-testid="board-permission-card"]')
        expect(offered(board)).toEqual(constants.BoardScopeFunctions)
        const cards = w.findAll('.n-card')
        const byName = (n: string) => cards.find((c: any) => c.text().includes(n))!
        for (const f of constants.BoardFunctions) {
            expect(offered(byName('Payments')), 'perspective ' + f).toContain(f)
            expect(offered(byName('Shop')), 'product ' + f).not.toContain(f)
            expect(offered(byName('api')), 'component ' + f).not.toContain(f)
        }
        const orgChecks = w.findAllComponents(NCheckbox).filter((c: any) => !cards.some((card: any) => card.element.contains(c.element)))
            .map((c: any) => c.props('value'))
        expect(orgChecks).toEqual(expect.arrayContaining(constants.BoardFunctions))

        // PUBLISH_EXTERNALLY (task TEA-3): organization-wide and on a perspective, a product and a
        // component, never on a board. TEA_READ, the EXTERNAL key function, is offered nowhere.
        expect(orgChecks).toContain('PUBLISH_EXTERNALLY')
        for (const n of ['Payments', 'Shop', 'api']) expect(offered(byName(n)), n).toContain('PUBLISH_EXTERNALLY')
        expect(offered(board)).not.toContain('PUBLISH_EXTERNALLY')
        expect(w.findAllComponents(NCheckbox).map((c: any) => c.props('value'))).not.toContain('TEA_READ')
    })

    it('offers PUBLISH_EXTERNALLY nowhere where the backend serves no TEA (CE)', () => {
        myuser.installationType = 'OSS'
        try {
            const w = mountEditor([grant('PERSPECTIVE', 'p1'), grant('COMPONENT', 'prod1'), grant('COMPONENT', 'c1')])
            const values = w.findAllComponents(NCheckbox).map((c: any) => c.props('value'))
            expect(values).toContain('ARTIFACT_DOWNLOAD')
            expect(values).not.toContain('PUBLISH_EXTERNALLY')
        } finally {
            myuser.installationType = 'SAAS'
        }
    })

    it('does not offer the board functions at Essential Read, and a key capped by its owner sees only the allowed ones', () => {
        const essential = mountEditor([], 'ESSENTIAL_READ')
        expect(essential.findAllComponents(NCheckbox).map((c: any) => c.props('value'))).not.toEqual(
            expect.arrayContaining(['BOARD_READ']))
        const capped = mountEditor([grant('BOARD', 'b1')], 'READ_ONLY', { allowedFunctions: ['BOARD_READ', 'CONFIGURATION_READ'] })
        expect(offered(capped.find('[data-testid="board-permission-card"]'))).toEqual(['BOARD_READ', 'CONFIGURATION_READ'])
    })

    it('hides the section for an organization admin, and reads the boards itself when none are given', async () => {
        expect(mountEditor([], 'ADMIN').find('[data-testid="board-permissions"]').exists()).toBe(false)
        dispatch.mockClear()
        dispatch.mockResolvedValueOnce([{ uuid: 'b9', name: 'Read Here' }] as any)
        const w = mountEditor([], 'READ_ONLY', { boards: undefined })
        await new Promise(r => setTimeout(r, 0))
        expect(dispatch).toHaveBeenCalledWith('fetchAgentBoardNamesOfOrg', 'o1')
        const select = w.findAllComponents(NSelect).find((s: any) => s.attributes('data-testid') === 'board-permission-select')!
        expect(select.props('options')).toEqual([{ label: 'Read Here', value: 'b9' }])
    })
})

describe('ScopedPermissions: externalKey (task TEA-3)', () => {
    const allGrants = () => [grant('PERSPECTIVE', 'p1'), grant('COMPONENT', 'prod1'), grant('COMPONENT', 'c1'), grant('BOARD', 'b1')]
    const mountExternal = (scoped: any[] = allGrants(), orgType = 'NONE') => mountEditor(scoped, orgType, {
        externalKey: true,
        approvalRoles: [{ id: 'qa', displayView: 'QA' }],
        instances: [{ uuid: 'i1', uri: 'https://i1', instanceType: 'STANDALONE_INSTANCE' }],
        clusters: [{ uuid: 'cl1', name: 'Cluster One', instanceType: 'CLUSTER', instances: [] }],
    })

    it('offers only NONE and Read Only organization-wide, and no functions or approvals there', () => {
        const w = mountExternal([], 'READ_ONLY')
        expect(w.findAllComponents(NRadioButton).map((r: any) => r.props('value'))).toEqual(['NONE', 'READ_ONLY'])
        expect(w.text()).not.toContain('Organization-Wide Functions')
        expect(w.text()).not.toContain('Organization-Wide Approval Permissions')
        expect(w.findAllComponents(NCheckbox)).toHaveLength(0)
    })

    it('hides the board, cluster and instance sections and every function or approval row', () => {
        const w = mountExternal()
        expect(w.find('[data-testid="board-permissions"]').exists()).toBe(false)
        expect(w.text()).not.toContain('Per-Board Permissions')
        expect(w.text()).not.toContain('Per-Cluster Permissions')
        expect(w.text()).not.toContain('Per-Instance Permissions')
        expect(w.text()).not.toContain('Functions:')
        expect(w.text()).not.toContain('Approvals:')
        expect(w.findAllComponents(NCheckboxGroup)).toHaveLength(0)
        // a normal key sees all of them with the same props
        const normal = mountEditor(allGrants(), 'NONE', { approvalRoles: [{ id: 'qa', displayView: 'QA' }],
            instances: [{ uuid: 'i1', uri: 'https://i1', instanceType: 'STANDALONE_INSTANCE' }],
            clusters: [{ uuid: 'cl1', name: 'Cluster One', instanceType: 'CLUSTER', instances: [] }] })
        for (const t of ['Per-Board Permissions', 'Per-Cluster Permissions', 'Per-Instance Permissions', 'Functions:', 'Approvals:']) {
            expect(normal.text(), t).toContain(t)
        }
    })

    it('shows a fixed Read Only on perspective, product and component cards, with no type radio', () => {
        const w = mountExternal()
        const cards = w.findAll('.n-card')
        const byName = (n: string) => cards.find((c: any) => c.text().includes(n))!
        for (const n of ['Payments', 'Shop', 'api']) {
            const card = byName(n)
            expect(card.find('[data-testid="external-key-read-only"]').text(), n).toBe('Read Only')
            expect(card.findAllComponents(NRadioGroup), n).toHaveLength(0)
            expect(card.findAllComponents(NCheckbox), n).toHaveLength(0)
        }
    })

    it('adds a product as a COMPONENT grant, Read Only, with no functions or approvals', async () => {
        const w = mountExternal([])
        const select = w.findAllComponents(NSelect).find((s: any) => s.props('placeholder') === 'Add product...')!
        await select.vm.$emit('update:value', 'prod1')
        const adds = w.findAllComponents(NButton).filter((b: any) => b.text() === 'Add')
        const add = adds.find((b: any) => !b.props('disabled'))!
        expect(adds.filter((b: any) => !b.props('disabled'))).toHaveLength(1)
        await add.trigger('click')
        expect(lastModel(w).scopedPermissions).toEqual([
            { scope: 'COMPONENT', objectId: 'prod1', objectName: 'Shop', type: 'READ_ONLY', functions: [], approvals: [] },
        ])
    })

    it('does not read the org boards for an external key', async () => {
        dispatch.mockClear()
        mountEditor([], 'NONE', { externalKey: true, boards: undefined })
        await new Promise(r => setTimeout(r, 0))
        expect(dispatch).not.toHaveBeenCalledWith('fetchAgentBoardNamesOfOrg', 'o1')
    })
})
