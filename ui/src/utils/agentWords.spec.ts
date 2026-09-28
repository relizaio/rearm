// Board enums as words (task RD2-23, sweep UI-31): every family through the one table.
import { describe, expect, it } from 'vitest'
import { enumWord, holdPhrase, holdWord, levelWord, lifecycleWord, outcomeWord, specWord, statusWord, triggerWord } from './agentWords'

describe('agentWords', () => {
    it('names specification types', () => {
        expect(specWord('REVIEW_FINDINGS')).toBe('review findings')
        expect(specWord('DETAILED_DESIGN')).toBe('detailed design')
        expect(specWord('TEST_REPORT')).toBe('test report')
        expect(specWord('ARCHITECTURE')).toBe('architecture')
    })

    it('names lifecycles', () => {
        expect(lifecycleWord('READY_TO_SHIP')).toBe('ready to ship')
        expect(lifecycleWord('ASSEMBLED')).toBe('assembled')
        expect(lifecycleWord('DRAFT')).toBe('draft')
        expect(lifecycleWord(null)).toBe('')
    })

    it('names hold kinds and levels', () => {
        expect(holdWord({ kind: 'HUMAN_GATE', level: 'OPERATOR' })).toBe('human gate')
        expect(holdWord({ kind: 'MANUAL', level: 'OPERATOR' })).toBe('operator hold')
        expect(holdWord({ kind: 'MANUAL', level: 'COORDINATOR' })).toBe('coordinator hold')
        expect(holdWord({ kind: 'MANUAL' })).toBe('manual hold')
        expect(holdWord({ kind: 'QUESTION', level: 'COORDINATOR' })).toBe('question')
        expect(holdWord(null)).toBe('')
        expect(levelWord('OPERATOR')).toBe('operator')
        expect(levelWord('COORDINATOR')).toBe('coordinator')
    })

    it('phrases a hold for a row, whatever the snapshot\'s case', () => {
        expect(holdPhrase({ kind: 'HUMAN_GATE' })).toBe('held at the human gate')
        expect(holdPhrase({ kind: 'human_gate', level: 'operator' })).toBe('held at the human gate')
        expect(holdPhrase({ kind: 'QUESTION' })).toBe('held on a question')
        expect(holdPhrase({ kind: 'manual', level: 'operator' })).toBe('operator hold')
        expect(holdPhrase(undefined)).toBe('')
    })

    it('names outcomes, statuses and triggers', () => {
        expect(outcomeWord('PASSED')).toBe('passed')
        expect(outcomeWord('REJECTED')).toBe('rejected')
        expect(statusWord('AWAITING_COORDINATOR')).toBe('awaiting coordinator')
        expect(statusWord('ON_HOLD')).toBe('on hold')
        expect(triggerWord('HUMAN_REJECT')).toBe('human reject')
        expect(enumWord(undefined)).toBe('')
    })
})
