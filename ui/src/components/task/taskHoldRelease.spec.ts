// @vitest-environment happy-dom
//
// Releasing a hold (task 4c566d0d): the release carries the picked role. RD2-20: the picker shows on every
// release a person gives -- a manual operator hold's and a budget stop's as well as a loop stop's.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskHeader from './TaskHeader.vue'
import { fixtureRoles, richTask } from './taskFixtures'

vi.mock('vuex', () => ({ useStore: () => ({ dispatch: vi.fn(), getters: {} }) }))

const routing = { kind: 'SYSTEM', uuid: null, name: 'routing' }

function header (hold: any) {
    return mount(TaskHeader, { props: { task: richTask({ status: 'ON_HOLD', hold }), roles: fixtureRoles, canOperate: true } })
}

describe('stop hold release', () => {
    it('offers a role on a loop stop, with the stop\'s words', () => {
        const stop = header({ level: 'OPERATOR', kind: 'MANUAL', reason: 'stopped by no progress: [q1] stayed OPEN',
            heldBy: routing, heldAt: '2026-09-25T10:00:00Z' })
        expect(stop.find('.relrole').exists()).toBe(true)
        expect(stop.find('.relstop').text()).toContain('past this stop once')
        expect(stop.find('.relbtn').text()).toBe('Release past the stop')

    })

    it('offers the same picker on a manual operator hold and a budget stop (RD2-20)', () => {
        for (const hold of [
            { level: 'OPERATOR', kind: 'MANUAL', reason: 'stopped by budget: does not fit', heldBy: routing },
            { level: 'OPERATOR', kind: 'MANUAL', reason: 'waiting on legal', heldBy: { kind: 'USER', name: 'pavel' } },
        ]) {
            const w = header(hold)
            expect(w.find('.relrole').exists(), hold.reason).toBe(true)
            expect(w.find('.relstop').exists(), 'no stop to route past').toBe(false)
            expect(w.find('.relbtn').text()).toBe('Operator release')
        }
    })

    it('releases a manual hold to the picked role, and without one lets routing pick (RD2-20)', async () => {
        const w = header({ level: 'OPERATOR', kind: 'MANUAL', reason: 'waiting on legal', heldBy: { kind: 'USER', name: 'pavel' } })
        expect((w.vm as any).roleOptions.map((o: any) => o.value)).toContain('coder')
        await w.find('.relbtn').trigger('click')
        const plain = w.emitted('operator-release')?.[0]?.[0] as any
        expect(plain).not.toHaveProperty('role')
        expect(plain).toMatchObject({ note: '' })

        ;(w.vm as any).releaseNote = 'legal cleared it'
        ;(w.vm as any).releaseRole = 'coder'
        await w.vm.$nextTick()
        expect(w.find('.relbtn').text()).toBe('Release to coder')
        await w.find('.relbtn').trigger('click')
        expect(w.emitted('operator-release')?.[1]?.[0]).toMatchObject({ note: 'legal cleared it', role: 'coder' })
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

    it('shows who may release, and lets a person release the coordinator stop (task c0a2134c)', async () => {
        const first = header({ level: 'COORDINATOR', kind: 'MANUAL', stop: 'NO_PROGRESS', heldBy: routing,
            reason: 'stopped by no progress: [q1] stayed OPEN; the coordinator may release once' })
        expect(first.find('.holdwho').text()).toBe('coordinator may release once')
        expect(first.find('.relstop').text()).toContain('counts as the one release of this')
        await first.find('.relbtn').trigger('click')
        expect(first.emitted('operator-release')).toHaveLength(1)

        const second = header({ level: 'OPERATOR', kind: 'MANUAL', stop: 'NO_PROGRESS', heldBy: routing,
            reason: 'stopped by no progress: [q1] stayed OPEN; released once already' })
        expect(second.find('.holdwho').text()).toBe('operator only')
        expect(second.find('.relstop').text()).not.toContain('counts as the one release')

        const own = header({ level: 'COORDINATOR', kind: 'MANUAL', stop: null, heldBy: { kind: 'SESSION', uuid: 's1' },
            reason: 'waiting on the tracker' })
        expect(own.find('.holdwho').exists()).toBe(false)
        expect(own.find('.relbtn').exists()).toBe(false)
    })
})
