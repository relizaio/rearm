// @vitest-environment happy-dom
//
// The Per-Board section of the permission editor (board-permissions.md §5; task 428b4a71): grants on
// one board, what each section offers, and a grant on a board the editor cannot list.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { NCheckbox, NCheckboxGroup, NRadioGroup, NSelect } from 'naive-ui'
import constants from '@/utils/constants'

const dispatch = vi.fn(async () => [])
vi.mock('vuex', () => ({ useStore: () => ({ dispatch, getters: { myuser: { installationType: 'SAAS' } } }) }))
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
