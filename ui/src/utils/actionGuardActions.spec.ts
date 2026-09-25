import { describe, expect, it } from 'vitest'
import { GUARD_VARIABLE_DOCS, GUARDED_ACTION_OPTIONS, guardedActionLabel } from './actionGuardActions'

describe('actionGuardActions', () => {
    it('offers both guarded actions as {label, value}', () => {
        expect(GUARDED_ACTION_OPTIONS.map(o => o.value)).toEqual(['RELEASE_PROMOTION', 'RELEASE_APPROVAL'])
        for (const o of GUARDED_ACTION_OPTIONS) {
            expect(Object.keys(o).sort()).toEqual(['label', 'value'])
            expect(o.label.length).toBeGreaterThan(0)
        }
    })

    it('labels the actions in the table', () => {
        expect(guardedActionLabel('RELEASE_PROMOTION')).toBe('Release promotion')
        expect(guardedActionLabel('RELEASE_APPROVAL')).toBe('Release approval')
        expect(guardedActionLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW')
        expect(guardedActionLabel(null)).toBe('')
    })

    it('documents what an expression may ask about the action and the actor', () => {
        const names = GUARD_VARIABLE_DOCS.map(d => d.name)
        for (const n of ['action.targetLifecycle', 'action.approvals', 'action.actor.kind', 'action.actor.keyType',
            'action.actor.id', 'action.actor.ip']) {
            expect(names).toContain(n)
        }
        for (const d of GUARD_VARIABLE_DOCS) expect(d.snippet.startsWith(d.name.split('.').slice(0, 2).join('.'))).toBe(true)
    })
})
