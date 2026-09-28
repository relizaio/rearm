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

const writer = { myPermissions: ['BOARD_READ', 'BOARD_WRITE'], blockingPriority: null }
const gated = () => richTask({ uuid: 'gate-1', status: 'ON_HOLD',
    hold: { level: 'OPERATOR', kind: 'HUMAN_GATE', gateRole: 'reviewer', reason: 'gate', heldAt: '2026-09-27T10:00:00Z' },
    signOffs: [{ role: 'reviewer', outcome: 'PASSED', outputs: [], signedOffAt: '2026-09-27T09:00:00Z' }], documents: [] })
const header = (task: any) => mount(TaskHeader, { props: { task, roles: fixtureRoles, canOperate: true, board: writer } })
const findings = (task: any) => mount(TaskFindings, { props: { task, roles: fixtureRoles, board: writer } as any })

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
        expect(form.find('[data-testid="file-now-line"]').text()).toBe('Files a round now; the task stays at the gate.')
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
