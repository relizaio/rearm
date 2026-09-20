import { describe, it, expect } from 'vitest'
import { actorLabel } from './agentActors'

describe('actorLabel', () => {
    it('shows a user by name, which is their email', () => {
        expect(actorLabel({ kind: 'USER', uuid: 'u-1', name: 'pm@example.com' })).toBe('pm@example.com')
    })

    it('falls back to kind and a short uuid when no name was recorded', () => {
        expect(actorLabel({ kind: 'SESSION', uuid: '2f9c1b4a-0000-0000-0000-000000000000', name: null }))
            .toBe('session 2f9c1b4a')
    })

    it('names the kind alone when there is neither', () => {
        expect(actorLabel({ kind: 'SYSTEM', uuid: null, name: null })).toBe('system')
    })

    it('renders nothing for an absent actor rather than "undefined"', () => {
        expect(actorLabel(null)).toBe('')
        expect(actorLabel(undefined)).toBe('')
    })

    it('does not crash on a kind it has never heard of', () => {
        expect(actorLabel({ kind: 'MARTIAN', uuid: 'abcdefghij', name: null })).toBe('martian abcdefgh')
    })
})
