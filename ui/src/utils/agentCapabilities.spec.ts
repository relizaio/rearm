import { describe, it, expect } from 'vitest'
import { CAPABILITIES, COORDINATOR_CAPABILITIES, toOptions } from './agentCapabilities'

describe('capability options', () => {
    it('are label/value pairs of the verb, which a select needs to render and to save', () => {
        for (const o of [...toOptions(CAPABILITIES), ...toOptions(COORDINATOR_CAPABILITIES)]) {
            expect(typeof o.label).toBe('string')
            expect(o.value).toBe(o.label)
        }
        expect(toOptions(CAPABILITIES).map(o => o.value)).toEqual(['TRACKER_READ', 'TRACKER_WRITE', 'CODE_PUSH', 'PR_MERGE'])
    })

    it('offer the coordinator only what a board may declare for it', () => {
        expect(toOptions(COORDINATOR_CAPABILITIES).map(o => o.value)).toEqual(['CODE_PUSH', 'PR_MERGE'])
    })
})
