import { describe, it, expect } from 'vitest'
import { isLockedByOwnVote, myVotes, othersVoteCounts, voterOf, type MatrixApprovalEvent } from './approvalMatrix'

const vote = (voter: string | null, role: string, state: string, entry = 'e1'): MatrixApprovalEvent =>
    ({ approvalEntry: entry, approvalRoleId: role, state, wu: voter ? { lastUpdatedBy: voter } : null })

describe('voterOf', () => {
    it('prefers the human behind a key', () => {
        expect(voterOf({ approvalEntry: 'e', approvalRoleId: 'QA', state: 'APPROVED', wu: { lastUpdatedBy: 'key', actor: 'alice' } })).toBe('alice')
        expect(voterOf(vote('bob', 'QA', 'APPROVED'))).toBe('bob')
        expect(voterOf(vote(null, 'QA', 'APPROVED'))).toBeNull()
    })
})

describe('myVotes', () => {
    it('holds only this user\'s votes, latest per role', () => {
        const events = [vote('alice', 'QA', 'APPROVED'), vote('bob', 'SEC', 'APPROVED'), vote('alice', 'QA', 'DISAPPROVED')]
        expect(myVotes(events, 'alice')).toEqual({ e1: { QA: 'DISAPPROVED' } })
        expect(myVotes(events, 'carol')).toEqual({})
    })

    it('ignores non-votes', () => {
        expect(myVotes([vote('alice', 'QA', 'UNSET')], 'alice')).toEqual({})
    })
})

describe('othersVoteCounts', () => {
    it('counts other people once each, by their latest vote', () => {
        const events = [
            vote('bob', 'QA', 'APPROVED'), vote('bob', 'QA', 'APPROVED'),
            vote('carol', 'QA', 'DISAPPROVED'), vote('alice', 'QA', 'APPROVED')
        ]
        expect(othersVoteCounts(events, 'alice')).toEqual({ e1: { QA: { APPROVED: 1, DISAPPROVED: 1 } } })
    })

    it('counts a voter-less legacy event on its own', () => {
        expect(othersVoteCounts([vote(null, 'QA', 'APPROVED'), vote(null, 'QA', 'APPROVED')], 'alice'))
            .toEqual({ e1: { QA: { APPROVED: 2, DISAPPROVED: 0 } } })
    })
})

describe('isLockedByOwnVote', () => {
    const reqs = [['QA', 'SEC'], ['OPS']]

    it('another person\'s vote never locks the cell -- the second approver of a role may vote', () => {
        expect(isLockedByOwnVote(reqs, {}, { QA: 'UNSET' }, 'QA')).toBe(false)
    })

    it('a saved vote of mine stands', () => {
        expect(isLockedByOwnVote(reqs, { QA: 'APPROVED' }, {}, 'QA')).toBe(true)
    })

    it('a vote of mine on one role closes the requirement\'s other roles, saved or pending', () => {
        expect(isLockedByOwnVote(reqs, { QA: 'APPROVED' }, {}, 'SEC')).toBe(true)
        expect(isLockedByOwnVote(reqs, {}, { QA: 'APPROVED', SEC: 'UNSET' }, 'SEC')).toBe(true)
    })

    it('a role shared by two requirements locks when I voted on either', () => {
        const shared = [['QA', 'SEC'], ['QA', 'OPS']]
        expect(isLockedByOwnVote(shared, { OPS: 'APPROVED' }, {}, 'QA')).toBe(true)
        expect(isLockedByOwnVote(shared, { OPS: 'APPROVED' }, {}, 'SEC')).toBe(false)
    })

    it('my own pending pick does not lock its cell, and other requirements stay open', () => {
        expect(isLockedByOwnVote(reqs, {}, { QA: 'APPROVED' }, 'QA')).toBe(false)
        expect(isLockedByOwnVote(reqs, { QA: 'APPROVED' }, {}, 'OPS')).toBe(false)
    })
})
