import { describe, it, expect } from 'vitest'
import { buildChannelOptions } from './channelOptions'

const TYPES = { SLACK: 'Slack', EMAIL: 'Email' }

const channels = [
    { uuid: 'a', name: 'Alerts', type: 'SLACK', status: 'ENABLED' },
    { uuid: 'b', name: 'Old', type: 'EMAIL', status: 'DISABLED' },
]

describe('buildChannelOptions', () => {
    it('lists enabled channels with a type-qualified label', () => {
        expect(buildChannelOptions(channels, [], TYPES))
            .toEqual([{ label: 'Alerts (Slack)', value: 'a' }])
    })

    it('adds a ghost for a referenced DISABLED channel so it is not a bare uuid', () => {
        const opts = buildChannelOptions(channels, ['b'], TYPES)
        expect(opts.map(o => o.value)).toEqual(['a', 'b'])
        expect(opts[1].label).toBe('Old (Email) (disabled)')
    })

    it('adds a ghost for a referenced DELETED channel', () => {
        const opts = buildChannelOptions(channels, ['deadbeef-cafe'], TYPES)
        expect(opts[1].label).toBe('(deleted channel) deadbeef')
    })

    it('does not duplicate a referenced channel that is still enabled', () => {
        expect(buildChannelOptions(channels, ['a'], TYPES)).toHaveLength(1)
    })

    it('does not duplicate the same dangling reference twice', () => {
        // Two routes can reference the same dead channel; one ghost is enough.
        expect(buildChannelOptions(channels, ['gone', 'gone'], TYPES)).toHaveLength(2)
    })

    it('tolerates empty inputs', () => {
        expect(buildChannelOptions([], [], TYPES)).toEqual([])
    })

    it('still shows a junk reference so it can be removed', () => {
        // Hiding a null/'' entry would leave it in the saved data with no way to
        // clear it -- the opposite of why ghosts exist.
        const opts = buildChannelOptions(channels, ['' as any], TYPES)
        expect(opts).toHaveLength(2)
        expect(opts[1].label).toMatch(/deleted channel/)
    })
})
