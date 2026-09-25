import { describe, expect, it } from 'vitest'
import { taskSummary } from './agentTaskSummary'
import { fixtureRoles, fixtureTasks, questionsTask, richTask } from '../components/task/taskFixtures'

describe('taskSummary', () => {
    it('counts the open findings of the newest rounds by priority', () => {
        const s = taskSummary(richTask(), fixtureTasks(richTask()), fixtureRoles, {})
        // review round 3: F-4 (P2) open, F-1 resolved; test round 1: T-1 (P3) open. Older rounds do not count.
        expect(s.openFindings).toEqual([{ priority: 2, count: 1 }, { priority: 3, count: 1 }])
    })

    it('says how many questions are open, who asked, in which round and about what', () => {
        const s = taskSummary(questionsTask(), [], fixtureRoles, {})
        expect(s.openQuestions).toBe(1)
        expect(s.questions).toBe('1 open question from coder (round 1, about ARCHITECTURE round 1)')
        // No QUESTIONS round on the read: the frame still names the asker.
        expect(taskSummary(richTask(), [], fixtureRoles, {}).questions).toBe('1 open question from coder')
        expect(taskSummary(richTask({ questionStack: [] }), [], fixtureRoles, {}).questions).toBe('1 open question')
        expect(taskSummary(richTask({ openQuestions: [] }), [], fixtureRoles, {}).questions).toBeNull()
    })

    it('keeps the latest document of each type only', () => {
        const s = taskSummary(richTask(), [], fixtureRoles, {})
        expect(s.latestDocuments.map(d => d.uuid)).toEqual(['a1', 'tr1', 'r3'])
    })

    it('states the dependencies both ways', () => {
        const t = richTask()
        expect(taskSummary(t, fixtureTasks(t), fixtureRoles, {}).dependencies).toEqual({ done: 1, pending: 0, blocks: 1 })
        const pending = richTask({ dependsOn: ['tp', 'missing'] })
        expect(taskSummary(pending, fixtureTasks(pending), fixtureRoles, {}).dependencies).toEqual({ done: 0, pending: 2, blocks: 1 })
    })

    it('puts the assignment and the usage on one line each', () => {
        const t = richTask({ assignment: { role: 'coder', agent: 'a1', assignedAt: '2026-09-24T09:00:00Z' } })
        const s = taskSummary(t, [], fixtureRoles, { a1: 'Arch' })
        expect(s.assignment).toMatch(/^coder · Arch · since /)
        expect(s.usage).toBe('$0.42 · 1.5k tok · 3 requests')
        const none = taskSummary(richTask({ usage: null }), [], fixtureRoles, {})
        expect(none.assignment).toBeNull()
        expect(none.usage).toBeNull()
    })

    it('reads an empty task as nothing to show', () => {
        const s = taskSummary({ uuid: 'x' }, [], [], {})
        expect(s).toEqual({ openFindings: [], openQuestions: 0, questions: null, latestDocuments: [],
            dependencies: { done: 0, pending: 0, blocks: 0 }, assignment: null, usage: null })
    })
})
