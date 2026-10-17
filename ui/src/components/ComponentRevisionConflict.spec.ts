// @vitest-environment happy-dom
//
// The refusal panel on the component settings page (TPF-62): who changed the component and when,
// Reload for everyone, Force only for a writer.
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ComponentRevisionConflict from './ComponentRevisionConflict.vue'

const conflict = { revision: 9, lastUpdatedBy: 'u-alice', lastUpdatedType: 'MANUAL', lastUpdatedActor: null,
    lastUpdatedDate: '2026-10-13T10:00:00Z' }
const users = [{ label: 'Alice', value: 'u-alice' }]
const when = (iso: string) => iso.slice(0, 16).replace('T', ' ')

describe('the revision conflict panel', () => {
    it('names who changed it and when, and emits Reload and Force', async () => {
        const w = mount(ComponentRevisionConflict, { props: { conflict, users, when, canForce: true, componentWord: 'component' } })
        expect(w.find('[data-testid="revision-conflict-who"]').text()).toBe('Changed by Alice at 2026-10-13 10:00.')
        const alert = w.find('[data-testid="revision-conflict"]').text()
        expect(alert).toContain('This component was changed after you opened it')
        expect(alert).toContain('Locks, archive status and perspective membership are not part of this save.')
        expect(alert).toContain('Force saves your values over that change.')

        await w.find('[data-testid="revision-conflict-reload"]').trigger('click')
        expect(w.emitted('reload')).toHaveLength(1)
        expect(w.emitted('force')).toBeUndefined()

        await w.find('[data-testid="revision-conflict-force"]').trigger('click')
        expect(w.emitted('force')).toHaveLength(1)
        expect(w.emitted('reload')).toHaveLength(1)
    })

    it('offers no Force without write permission', () => {
        const w = mount(ComponentRevisionConflict, { props: { conflict, users, when, canForce: false, componentWord: 'component' } })
        expect(w.find('[data-testid="revision-conflict-force"]').exists()).toBe(false)
        expect(w.find('[data-testid="revision-conflict"]').text()).not.toContain('Force saves your values over that change.')
        expect(w.find('[data-testid="revision-conflict-reload"]').exists()).toBe(true)
    })

    it('leaves the date out when the refusal has none', () => {
        const w = mount(ComponentRevisionConflict, { props: { conflict: { ...conflict, lastUpdatedDate: null }, users, when } })
        expect(w.find('[data-testid="revision-conflict-who"]').text()).toBe('Changed by Alice.')
    })
})
