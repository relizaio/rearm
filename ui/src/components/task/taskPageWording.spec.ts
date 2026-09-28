// @vitest-environment happy-dom
//
// Task page wording (task RD2-23, sweep UI-15, UI-16, UI-31, UI-32, UI-49, UI-50): distinct headings, one date
// format with the full timestamp on hover, words for the board's enums, the required sign-offs under the status,
// the File toast, and routing's reason on a status-history row.
import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import TaskTitle from './TaskTitle.vue'
import TaskHeader from './TaskHeader.vue'
import TaskHistory from './TaskHistory.vue'
import TaskHops from './TaskHops.vue'
import TaskFindings from './TaskFindings.vue'
import TaskDocuments from './TaskDocuments.vue'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import AiAgentRevisionHistory from '../AiAgentRevisionHistory.vue'
import AgentTime from '../AgentTime.vue'
import { fixtureRoles, richTask } from './taskFixtures'
import { decisionFailedText } from '@/utils/agentTaskActions'
import { ts, tsFull } from '@/utils/agentTaskFormat'

const dispatch = vi.fn()
vi.mock('vuex', () => ({ useStore: () => ({ dispatch, getters: {} }) }))

describe('task page wording', () => {
    it('names the hop log "Hops" and the revisions "Revisions", with outcomes in words', async () => {
        const hops = mount(TaskHops, { props: { task: richTask(), agentNames: {} } })
        expect(hops.find('.dsec__h').text()).toBe('Hops')
        expect(hops.findAll('.hist__row .n-tag').map(t => t.text())).toEqual(expect.arrayContaining(['passed', 'rejected', 'returned']))
        expect(hops.text()).not.toContain('PASSED')
        const revs = mount(AiAgentRevisionHistory, { props: { kind: 'task', uuid: 't1' } })
        expect(revs.find('.n-collapse-item__header-main').text()).toBe('Revisions')
    })

    it('puts the required sign-offs under the status, not under Human review', () => {
        // The fixture's task has a child; a parent waits on its children, not on required roles.
        const task = richTask({ childTasks: [] })
        const title = mount(TaskTitle, { props: { task, roles: fixtureRoles } })
        expect(title.find('[data-testid="required-signoffs"]').text()).toBe('Required sign-offs: coder ✗ · reviewer ✗')
        const sub = title.html()
        expect(sub.indexOf('data-testid="required-signoffs"')).toBeGreaterThan(sub.indexOf('on hold'))
        expect(mount(TaskTitle, { props: { task } }).find('[data-testid="required-signoffs"]').exists()).toBe(false)
        const header = mount(TaskHeader, { props: { task: { ...task, requireHumanReview: true }, roles: fixtureRoles, canOperate: true, board: {} } })
        expect(header.text()).not.toContain('required:')
        expect(header.text()).toContain('Human review')
        // A reader with nothing to act on and no flag set sees no empty Human review heading.
        const reader = mount(TaskHeader, { props: { task, roles: fixtureRoles, canOperate: false, board: {} } })
        expect(reader.findAll('.dsec__h').map(h => h.text())).not.toContain('Human review')
    })

    it('says a gated task is held at the human gate, not by "humanGate"', () => {
        const task = richTask({ hold: { level: 'OPERATOR', kind: 'HUMAN_GATE', gateRole: 'reviewer', reason: 'gate',
            heldBy: { kind: 'SYSTEM', uuid: null, name: 'humanGate' }, heldAt: '2026-09-27T14:58:00Z' } })
        const w = mount(TaskHeader, { props: { task, roles: fixtureRoles, canOperate: false, board: {} } })
        const meta = w.find('.holdmeta').text()
        expect(meta).toContain('held at the human gate')
        expect(meta).not.toContain('humanGate')
        expect(meta).toContain(ts('2026-09-27T14:58:00Z'))
    })

    it('shows routing\'s reason on its status-history row, and a person\'s row as before', () => {
        const task = richTask({ statusHistory: [
            { from: 'PENDING_INTAKE', to: 'QUEUED', at: '2026-09-27T15:00:00Z', trigger: 'AUTHORIZE', actor: { kind: 'USER', uuid: 'u1', name: 'pavel' }, note: 'go' },
            { from: 'QUEUED', to: 'QUEUED', at: '2026-09-27T15:37:00Z', trigger: 'AUTHORIZE', actor: { kind: 'SYSTEM', uuid: null, name: 'routing' },
                note: 'findings decided; back to designer' },
            { from: 'QUEUED', to: 'QUEUED', at: '2026-09-27T15:40:00Z', trigger: 'AUTHORIZE', actor: { kind: 'SYSTEM', uuid: null, name: 'routing' }, note: null },
        ] })
        const w = mount(TaskHistory, { props: { task } })
        const rows = w.findAll('.shist__row')
        expect(rows[1].find('.shist__arrow').text()).toBe('queued → queued')
        expect(rows[1].find('[data-testid="routing-note"]').text()).toBe('routing: findings decided; back to designer')
        expect(rows[1].find('.shist__trig').exists()).toBe(false)
        expect(rows[0].find('.shist__trig').text()).toBe('authorize')
        expect(rows[0].text()).toContain('“go”')
        // A routing row from before notes were kept falls back to today's text.
        expect(rows[2].find('[data-testid="routing-note"]').exists()).toBe(false)
        expect(rows[2].find('.shist__trig').text()).toBe('authorize')
    })

    it('writes every time in the one format with the full timestamp on hover', () => {
        const at = '2026-09-27T15:36:12Z'
        const w = mount(AgentTime, { props: { at } })
        expect(w.text()).toBe(ts(at))
        expect(w.attributes('title')).toBe(tsFull(at))
        const hist = mount(TaskHistory, { props: { task: richTask() } })
        const first = hist.find('.shist__time .agent-time')
        expect(first.text()).toBe(ts('2026-09-20T10:00:00Z'))
        expect(first.attributes('title')).toBe(tsFull('2026-09-20T10:00:00Z'))
        expect(mount(AgentTime, { props: { at: null } }).attributes('title')).toBeUndefined()
    })

    it('opens a revision with its time in the one format', async () => {
        dispatch.mockResolvedValue([{ revision: 1, at: '2026-09-25T09:00:00Z', task: { uuid: 't1', status: 'QUEUED' } }])
        const w = mount(AiAgentRevisionHistory, { props: { kind: 'task', uuid: 't1' } })
        await w.find('.n-collapse-item__header-main').trigger('click')
        await flushPromises()
        const at = w.find('.revhist__at')
        expect(at.text()).toBe(ts('2026-09-25T09:00:00Z'))
        expect(at.attributes('title')).toBe(tsFull('2026-09-25T09:00:00Z'))
    })

    it('says a refused File could not file the finding; a decision keeps "Decision failed"', () => {
        expect(decisionFailedText([{ action: 'FILE' }])).toBe('Could not file the finding')
        expect(decisionFailedText([{ action: 'ACCEPT' }])).toBe('Decision failed')
        expect(decisionFailedText([{ action: 'FILE' }, { action: 'DISMISS' }])).toBe('Decision failed')
        expect(decisionFailedText([])).toBe('Decision failed')
    })

    // Tester run 1 T-1: a round's verdict tag printed "PASSED"/"REJECTED" where the hop tags beside it read words.
    it('names a round\'s verdict in words in its heading, in the Documents list and on its release page', () => {
        const task = richTask()
        const findings = mount(TaskFindings, { props: { task, roles: fixtureRoles, board: {}, priorityLevels: 3 } as any })
        expect(findings.findAll('.dsec__h .n-tag').map(t => t.text())).toEqual(['rejected', 'passed'])
        const documents = mount(TaskDocuments, { props: { task } })
        const tags = documents.findAll('.drow .n-tag').map(t => t.text())
        expect(tags.filter(t => t === 'rejected' || t === 'passed')).toEqual(['rejected', 'rejected', 'rejected', 'passed'])
        expect(tags.join(' ')).not.toMatch(/PASSED|REJECTED/)
        const release = readFileSync(join(process.cwd(), 'src/components/ReleaseView.vue'), 'utf8')
        expect(release).toContain('{{ outcomeWord(documentVerdict(release)) }}')
        expect(release).not.toMatch(/\{\{ documentVerdict\(release\) \}\}/)
    })
})
