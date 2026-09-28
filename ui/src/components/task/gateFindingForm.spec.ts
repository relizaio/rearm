// @vitest-environment happy-dom
//
// One finding form at a human gate (RD2-18, sweep UI-39). At a gate the gate box's form rides on the verdict
// and says so; the findings section's "File a finding", which files a round now and leaves the task at the
// gate, is hidden until asked for from the gate box, and then says what it does. Outside a gate, as before.
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskHeader from './TaskHeader.vue'
import TaskFindings from './TaskFindings.vue'
import { fixtureRoles, richTask } from './taskFixtures'
import { fileNowOpened, toggleFileNow } from '@/utils/agentGateFinding'

vi.mock('vuex', () => ({ useStore: () => ({ dispatch: vi.fn(), getters: {} }) }))

// P1 blocks here, P2 and P3 do not: a finding filed now at the gate may be P2 or P3 (RD2-18 run 1, T-1).
const writer = { myPermissions: ['BOARD_READ', 'BOARD_WRITE'], blockingPriority: 1 }
const strict = { myPermissions: ['BOARD_READ', 'BOARD_WRITE'], blockingPriority: null }
const gated = () => richTask({ uuid: 'gate-1', status: 'ON_HOLD',
    hold: { level: 'OPERATOR', kind: 'HUMAN_GATE', gateRole: 'reviewer', reason: 'gate', heldAt: '2026-09-27T10:00:00Z' },
    signOffs: [{ role: 'reviewer', outcome: 'PASSED', outputs: [], signedOffAt: '2026-09-27T09:00:00Z' }], documents: [] })
const header = (task: any, board: any = writer) => mount(TaskHeader, { props: { task, roles: fixtureRoles, canOperate: true, board,
    priorityLevels: 3 } })
const findings = (task: any, board: any = writer) => mount(TaskFindings, { props: { task, roles: fixtureRoles, board, priorityLevels: 3 } as any })

afterEach(() => { if (fileNowOpened({ uuid: 'gate-1' })) toggleFileNow({ uuid: 'gate-1' }) })

describe('at a human gate', () => {
    it('shows one form, the gate box\'s, saying it rides on the verdict, with a link to file now', () => {
        const h = header(gated())
        expect(h.find('[data-testid="gate-finding-mode"]').text()).toContain(
            'Filed with your verdict: on Approve it is a correction the producer addresses next round; on Reject it is a finding that blocks.')
        expect(h.find('[data-testid="gate-file-now"]').text()).toBe('file now without deciding')
        expect(findings(gated()).find('[data-testid="file-finding"]').exists()).toBe(false, 'the standalone form waits')
    })

    it('reveals the standalone form from the gate box, saying the task stays at the gate', async () => {
        const h = header(gated())
        const f = findings(gated())
        await h.find('[data-testid="gate-file-now"]').trigger('click')
        await f.vm.$nextTick()
        const form = f.find('[data-testid="file-finding"]')
        expect(form.exists()).toBe(true)
        expect(form.find('[data-testid="file-now-line"]').text()).toBe('Files a round now; the task stays at the gate.'
            + ' Only a finding that does not block (P2–P3) can be filed here; a blocking one goes with your verdict.')
        await h.vm.$nextTick()
        expect(h.find('[data-testid="gate-file-now"]').text()).toBe('hide the file-now form')
        await h.find('[data-testid="gate-file-now"]').trigger('click')
        await f.vm.$nextTick()
        expect(f.find('[data-testid="file-finding"]').exists()).toBe(false)
    })
})

describe('outside a gate', () => {
    it('keeps the standalone form as it was, with no gate line', () => {
        const f = findings(richTask({ uuid: 'q-1', status: 'QUEUED', hold: null, documents: [] }))
        const form = f.find('[data-testid="file-finding"]')
        expect(form.exists()).toBe(true)
        expect(form.find('[data-testid="file-now-line"]').exists()).toBe(false)
        const manual = findings(richTask({ uuid: 'h-1', status: 'ON_HOLD', documents: [],
            hold: { level: 'OPERATOR', kind: 'MANUAL', reason: 'wait', heldAt: '2026-09-27T10:00:00Z' } }))
        expect(manual.find('[data-testid="file-finding"]').exists()).toBe(true, 'a hold that is not a gate is not a gate')
    })
})

// Tester run 1 T-1: the server refuses a blocking finding while the task waits on a person's verdict, so filing
// now offers only what does not block -- and nothing on a board where every priority blocks.
describe('filing now at a gate takes only what does not block', () => {
    it('offers the priorities that do not block, and drops a blocking one already picked', async () => {
        const f = findings(gated())
        toggleFileNow({ uuid: 'gate-1' })
        await f.vm.$nextTick()
        expect((f.vm as any).filePriorityOptions.map((o: any) => o.value)).toEqual([2, 3])
        ;(f.vm as any).filePriority = 1
        ;(f.vm as any).fileTitle = 'blocks'
        await f.vm.$nextTick()
        expect(f.find('[data-testid="file-submit"]').attributes('disabled')).toBeDefined()
        ;(f.vm as any).filePriority = 2
        await f.vm.$nextTick()
        expect(f.find('[data-testid="file-submit"]').attributes('disabled')).toBeUndefined()
    })

    it('says every priority blocks on a strict board, and offers no filing now', async () => {
        const h = header(gated(), strict)
        expect(h.find('[data-testid="gate-file-now"]').exists()).toBe(false)
        expect(h.find('[data-testid="gate-every-blocks"]').text()).toBe('Every priority blocks on this board, so a finding goes with your verdict.')
        toggleFileNow({ uuid: 'gate-1' })
        const f = findings(gated(), strict)
        await f.vm.$nextTick()
        expect(f.find('[data-testid="file-finding"]').exists()).toBe(false, 'even asked for, nothing can be filed now')
    })

    it('leaves every priority outside a gate', () => {
        const f = findings(richTask({ uuid: 'q-2', status: 'QUEUED', hold: null, documents: [] }), strict)
        expect((f.vm as any).filePriorityOptions.map((o: any) => o.value)).toEqual([1, 2, 3])
    })
})

