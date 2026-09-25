// @vitest-environment happy-dom
//
// Releasing a stop hold (task 4c566d0d): the role picker shows on a loop stop only, and the release
// carries the picked role.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskHeader from './TaskHeader.vue'
import { fixtureRoles, richTask } from './taskFixtures'

vi.mock('vuex', () => ({ useStore: () => ({ dispatch: vi.fn(), getters: {} }) }))

const routing = { kind: 'SYSTEM', uuid: null, name: 'routing' }

function header (hold: any) {
    return mount(TaskHeader, { props: { task: richTask({ status: 'ON_HOLD', hold }), roles: fixtureRoles } })
}

describe('stop hold release', () => {
    it('offers a role only on a loop stop', () => {
        const stop = header({ level: 'OPERATOR', kind: 'MANUAL', reason: 'stopped by no progress: [q1] stayed OPEN',
            heldBy: routing, heldAt: '2026-09-25T10:00:00Z' })
        expect(stop.find('.relrole').exists()).toBe(true)
        expect(stop.find('.relstop').text()).toContain('past this stop once')
        expect(stop.find('.relbtn').text()).toBe('Release past the stop')

        for (const hold of [
            { level: 'OPERATOR', kind: 'MANUAL', reason: 'stopped by budget: does not fit', heldBy: routing },
            { level: 'OPERATOR', kind: 'MANUAL', reason: 'waiting on legal', heldBy: { kind: 'USER', name: 'pavel' } },
        ]) {
            const w = header(hold)
            expect(w.find('.relrole').exists(), hold.reason).toBe(false)
            expect(w.find('.relbtn').text()).toBe('Operator release')
        }
    })

    it('releases with the picked role, and without one lets routing pick', async () => {
        const w = header({ level: 'OPERATOR', kind: 'MANUAL', reason: 'stopped by cycle cap: coder ↔ architect went 3 round(s)',
            heldBy: routing })
        await w.find('.relbtn').trigger('click')
        expect(w.emitted('operator-release')?.[0]?.[0]).not.toHaveProperty('role')

        ;(w.vm as any).releaseRole = 'coder'
        await w.vm.$nextTick()
        expect(w.find('.relbtn').text()).toBe('Release to coder')
        await w.find('.relbtn').trigger('click')
        expect(w.emitted('operator-release')?.[1]?.[0]).toMatchObject({ role: 'coder' })
    })
})
