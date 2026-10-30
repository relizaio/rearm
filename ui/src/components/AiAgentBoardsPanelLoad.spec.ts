// @vitest-environment happy-dom
//
// The board's load, mounted with a stub store: a spinner until the tasks arrive (no "empty board" or "no boards"
// before then), the board opened again from this browser per organization (relizaLastBoard_<org>), at most five
// cards a column with Show more / Show fewer, and the drawer's task read whole when it opens.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick, reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'

const dispatch = vi.fn()
const replace = vi.fn(() => Promise.resolve())
const route = reactive<{ query: Record<string, string> }>({ query: {} })
vi.mock('vuex', () => ({
    useStore: () => ({ dispatch, getters: { orgById: () => null, myuser: null, perspectivesOfOrg: () => [] } }),
}))
vi.mock('vue-router', async (orig) => ({ ...(await orig() as any), useRoute: () => route, useRouter: () => ({ replace, push: vi.fn() }) }))
vi.mock('naive-ui', async (orig) => ({ ...(await orig() as any), useNotification: () => ({ success: vi.fn(), error: vi.fn() }) }))

const { default: Panel } = await import('./AiAgentBoardsPanel.vue')

// The drawer as a probe: which task it holds, and whether it waits for the task's whole read.
const DrawerProbe = defineComponent({
    props: ['task', 'loading', 'loadError'],
    setup: (p: any) => () => h('div', { 'data-testid': 'drawer', 'data-task': p.task?.uuid ?? '', 'data-loading': String(!!p.loading),
        'data-full': String(!!p.task?.documents) }),
})
const stubs = {
    RouterLink: { props: ['to'], template: '<a><slot/></a>' },
    AiAgentTaskDetailDrawer: DrawerProbe,
    AiAgentTaskPertView: true, AiAgentTaskTimelineView: true, AiAgentTaskTableView: true,
    AgentBoardAgentsPanel: true, AgentBoardDocumentsPanel: true, AgentBoardGroupsPanel: true, DeclarativeApplyModal: true,
}

const BOARDS: Record<string, any[]> = {
    o1: [{ uuid: 'b1', name: 'One', perAgentWipLimit: 2 }, { uuid: 'b2', name: 'Two', perAgentWipLimit: 2 }],
    o2: [{ uuid: 'b3', name: 'Three', perAgentWipLimit: 2 }, { uuid: 'b4', name: 'Four', perAgentWipLimit: 2 }],
}
const queued = (n: number, board = 'b1') => Array.from({ length: n }, (_, i) => ({ uuid: `${board}-t${i}`, key: `T-${i}`,
    title: `task ${i}`, status: 'PENDING_INTAKE', orderIndex: i, signOffs: [], returns: [] }))

let tasksOf: Record<string, any[]> = {}
let pendingTasks: ((v: any[]) => void) | null = null
let holdTasks = false
function serve () {
    dispatch.mockImplementation(async (action: string, arg: any) => {
        switch (action) {
        case 'fetchAgentBoardsOfOrg': return BOARDS[arg] ?? []
        case 'fetchAgentTasksOfBoard':
            if (holdTasks) return new Promise(res => { pendingTasks = res })
            return tasksOf[arg.boardUuid] ?? []
        case 'fetchAgentTask': return { uuid: arg, documents: [], description: 'whole' }
        default: return []
        }
    })
}

function mountPanel (orgUuid = 'o1') {
    return mount(Panel, { props: { orgUuid }, global: { stubs } })
}

beforeEach(() => {
    dispatch.mockReset()
    replace.mockClear()
    route.query = {}
    tasksOf = {}
    pendingTasks = null
    holdTasks = false
    window.localStorage.clear()
    serve()
})

describe('the board load: spinner', () => {
    it('shows "Loading board…" until the tasks arrive, and neither the empty-board hint nor the no-boards note before', async () => {
        holdTasks = true
        const w = mountPanel()
        expect(w.find('[data-testid="board-loading"]').text()).toContain('Loading board…')
        expect(w.find('[data-testid="no-boards"]').exists()).toBe(false)
        await flushPromises()
        expect(w.find('[data-testid="board-loading"]').exists()).toBe(true)
        expect(w.find('[data-testid="empty-board"]').exists()).toBe(false)
        expect(w.find('[data-testid="kanban-bar"]').exists(), 'no view while loading').toBe(false)
        pendingTasks?.([])
        await flushPromises()
        expect(w.find('[data-testid="board-loading"]').exists()).toBe(false)
        expect(w.find('[data-testid="empty-board"]').exists()).toBe(true)
    })

    it('says "no boards" once the list is in and empty, with no spinner', async () => {
        const w = mountPanel('o-empty')
        await flushPromises()
        expect(w.find('[data-testid="no-boards"]').exists()).toBe(true)
        expect(w.find('[data-testid="board-loading"]').exists()).toBe(false)
    })

    it('reads the board\'s tasks card-level, once', async () => {
        mountPanel()
        await flushPromises()
        const reads = dispatch.mock.calls.filter(c => c[0] === 'fetchAgentTasksOfBoard')
        expect(reads).toEqual([['fetchAgentTasksOfBoard', { boardUuid: 'b1' }]])
    })
})

describe('the board load: the last board, per organization', () => {
    it('opens the remembered board when the URL names none', async () => {
        window.localStorage.setItem('relizaLastBoard_o1', 'b2')
        mountPanel()
        await flushPromises()
        expect(dispatch).toHaveBeenCalledWith('fetchAgentTasksOfBoard', { boardUuid: 'b2' })
        expect(dispatch).not.toHaveBeenCalledWith('fetchAgentTasksOfBoard', { boardUuid: 'b1' })
    })

    it('passes over a remembered board the list does not hold, for the first', async () => {
        window.localStorage.setItem('relizaLastBoard_o1', 'gone')
        const w = mountPanel()
        await flushPromises()
        expect(dispatch).toHaveBeenCalledWith('fetchAgentTasksOfBoard', { boardUuid: 'b1' })
        expect(w.find('[data-testid="hidden-board"]').exists()).toBe(false)
    })

    it('lets the URL\'s board win', async () => {
        window.localStorage.setItem('relizaLastBoard_o1', 'b2')
        route.query = { board: 'b1' }
        mountPanel()
        await flushPromises()
        expect(dispatch).toHaveBeenCalledWith('fetchAgentTasksOfBoard', { boardUuid: 'b1' })
        expect(dispatch).not.toHaveBeenCalledWith('fetchAgentTasksOfBoard', { boardUuid: 'b2' })
    })

    it('remembers the board opened, under the organization\'s own key', async () => {
        mountPanel()
        await flushPromises()
        expect(window.localStorage.getItem('relizaLastBoard_o1')).toBe('b1')
        expect(window.localStorage.getItem('relizaLastBoard_o2')).toBeNull()
    })

    it('lists another organization\'s boards when the org changes, on the board last opened there', async () => {
        window.localStorage.setItem('relizaLastBoard_o2', 'b4')
        const w = mountPanel()
        await flushPromises()
        route.query = { board: 'b1' } // the URL still names the previous organization's board
        await w.setProps({ orgUuid: 'o2' })
        await flushPromises()
        expect(dispatch).toHaveBeenCalledWith('fetchAgentBoardsOfOrg', 'o2')
        expect(dispatch).toHaveBeenCalledWith('fetchAgentTasksOfBoard', { boardUuid: 'b4' })
        expect(w.find('[data-testid="hidden-board"]').exists()).toBe(false)
        expect(window.localStorage.getItem('relizaLastBoard_o1')).toBe('b1')
        expect(window.localStorage.getItem('relizaLastBoard_o2')).toBe('b4')
    })
})

describe('the kanban: five cards a column', () => {
    const intake = (w: any) => w.find('[data-col="intake"]')

    it('shows five of seven, counts seven, and shows them all on "Show 2 more", then five on "Show fewer"', async () => {
        tasksOf = { b1: queued(7) }
        const w = mountPanel()
        await flushPromises()
        expect(intake(w).find('[data-testid="col-count"]').text()).toBe('7')
        expect(intake(w).findAll('.tcard')).toHaveLength(5)
        const more = intake(w).find('[data-testid="col-more"]')
        expect(more.text()).toBe('Show 2 more')
        await more.trigger('click')
        expect(intake(w).findAll('.tcard')).toHaveLength(7)
        expect(intake(w).find('[data-testid="col-more"]').text()).toBe('Show fewer')
        await intake(w).find('[data-testid="col-more"]').trigger('click')
        expect(intake(w).findAll('.tcard')).toHaveLength(5)
    })

    it('offers no toggle on a column of five or fewer', async () => {
        tasksOf = { b1: queued(5) }
        const w = mountPanel()
        await flushPromises()
        expect(intake(w).findAll('.tcard')).toHaveLength(5)
        expect(intake(w).find('[data-testid="col-more"]').exists()).toBe(false)
    })

    it('folds an expanded column back on another board', async () => {
        tasksOf = { b1: queued(7), b2: queued(7, 'b2') }
        const w = mountPanel()
        await flushPromises()
        await intake(w).find('[data-testid="col-more"]').trigger('click')
        expect(intake(w).findAll('.tcard')).toHaveLength(7)
        ;(w.vm as any).$.setupState.selectedBoard = 'b2'
        await flushPromises()
        expect(intake(w).findAll('.tcard')).toHaveLength(5)
        expect(intake(w).find('[data-testid="col-more"]').text()).toBe('Show 2 more')
    })
})

describe('the drawer: the task read whole on open', () => {
    it('opens on the card with a spinner, then holds the task\'s whole read', async () => {
        tasksOf = { b1: queued(1) }
        let resolveTask: (v: any) => void = () => {}
        const w = mountPanel()
        await flushPromises()
        dispatch.mockImplementation(async (action: string, arg: any) => action === 'fetchAgentTask'
            ? new Promise(res => { resolveTask = res })
            : action === 'fetchAgentTasksOfBoard' ? tasksOf[arg.boardUuid] : [])
        await w.find('.tcard').trigger('click')
        await nextTick()
        const drawer = () => w.find('[data-testid="drawer"]')
        expect(drawer().attributes('data-task')).toBe('b1-t0')
        expect(drawer().attributes('data-loading')).toBe('true')
        expect(drawer().attributes('data-full')).toBe('false')
        expect(dispatch).toHaveBeenCalledWith('fetchAgentTask', 'b1-t0')
        resolveTask({ uuid: 'b1-t0', documents: [] })
        await flushPromises()
        expect(drawer().attributes('data-loading')).toBe('false')
        expect(drawer().attributes('data-full')).toBe('true')
    })
})
