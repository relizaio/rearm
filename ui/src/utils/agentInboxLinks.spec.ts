import { describe, expect, it } from 'vitest'
import { inboxLinksOf } from './agentInboxLinks'

// RD2-15: an agent-board inbox row links the task and the board it is about.
describe('inbox links of a board notification', () => {
    const row = (eventType: string, payload: any) => ({ org: 'o1', eventType, payloadJson: JSON.stringify(payload) })

    it('links the task and the board of a task event', () => {
        expect(inboxLinksOf(row('AGENT_TASK_NEEDS_PERSON', { board: 'b1', task: 't1', link: '/aiAgentTask/t1' })))
            .toEqual({ task: '/aiAgentTask/t1', board: '/aiAgentsOfOrg/o1?tab=boards&board=b1' })
    })

    it('links only the board of a board alert', () => {
        expect(inboxLinksOf(row('AGENT_BOARD_ALERT', { board: 'b1', task: null })))
            .toEqual({ task: null, board: '/aiAgentsOfOrg/o1?tab=boards&board=b1' })
    })

    it('gives nothing for another kind of row or a payload it cannot read', () => {
        expect(inboxLinksOf(row('RELEASE_LIFECYCLE_CHANGED', { board: 'b1', task: 't1' }))).toEqual({ task: null, board: null })
        expect(inboxLinksOf({ org: 'o1', eventType: 'AGENT_BOARD_ALERT', payloadJson: '{not json' })).toEqual({ task: null, board: null })
        expect(inboxLinksOf({ org: 'o1', eventType: 'AGENT_BOARD_ALERT', payloadJson: null })).toEqual({ task: null, board: null })
        expect(inboxLinksOf(null)).toEqual({ task: null, board: null })
    })
})
