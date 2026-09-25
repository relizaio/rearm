import { describe, expect, it } from 'vitest'
import {
    GUARD_ACTION_KEYS, GUARD_SAMPLES, GUARD_VARIABLE_DOCS, GUARDED_ACTION_OPTIONS, actionKeysRead, foreignActionKeys,
    guardedActionLabel,
} from './actionGuardActions'

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

    // task 3204c981, round 2, T-1: a sample used under the other action refuses every call
    it('every sample carries its action and reads only the keys that action is given', () => {
        expect(GUARD_SAMPLES.length).toBe(12)
        for (const s of GUARD_SAMPLES) {
            expect(Object.keys(GUARD_ACTION_KEYS)).toContain(s.action)
            expect(foreignActionKeys(s.cel, s.action), s.label).toEqual([])
            expect(s.label.length && s.cel.length && s.help.length).toBeTruthy()
        }
        expect(GUARD_SAMPLES.filter(s => s.action === 'RELEASE_APPROVAL').map(s => s.label))
            .toEqual(['No key approves a baseline entry'])
    })

    it('finds the action keys an expression reads, as the server does', () => {
        expect(actionKeysRead('action.targetLifecycle in ["X"] ? action.actor.kind == "USER" : true')).toEqual(['targetLifecycle', 'actor'])
        expect(actionKeysRead('action["approvals"].size() > 0')).toEqual(['approvals'])
        expect(actionKeysRead('!has(action.approvals) || true')).toEqual([], 'has() reads false safely')
        expect(actionKeysRead('release.lastaction.x == 1')).toEqual([], 'a field that ends in action is not the action')
        expect(foreignActionKeys('action.approvals.exists(a, a.entry == "baseline")', 'RELEASE_PROMOTION')).toEqual(['approvals'])
        expect(foreignActionKeys('action.targetLifecycle == "ASSEMBLED"', 'RELEASE_APPROVAL')).toEqual(['targetLifecycle'])
        expect(foreignActionKeys('action.actor.kind == "USER"', 'RELEASE_APPROVAL')).toEqual([])
    })
})
