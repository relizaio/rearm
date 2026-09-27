import { describe, expect, it } from 'vitest'
import { boardTargetOptions, targetChip, targetMissing, targetOptionType, targetPatch, TARGET_HINT } from './agentBoardTarget'
import { boardFieldOfError } from './agentBoardNaming'

// The target picker in the board form (task RD2-4).
describe('the board target picker', () => {
    const comps = [
        { uuid: 'c2', name: 'web', type: 'COMPONENT' },
        { uuid: 'c1', name: 'api', type: 'COMPONENT' },
        { uuid: 'd1', name: 'Payments documents', type: 'COMPONENT', kind: 'DOCUMENT' },
        { uuid: 'c3', name: 'old', type: 'COMPONENT', status: 'ARCHIVED' },
    ]

    it('offers the software components by name; no DOCUMENT component, nothing archived', () => {
        const opts = boardTargetOptions(comps)
        expect(opts).toEqual([{ label: 'api', value: 'c1', type: 'COMPONENT' }, { label: 'web', value: 'c2', type: 'COMPONENT' }])
        expect(opts.map(o => o.value)).not.toContain('d1')
        expect(boardTargetOptions(null)).toEqual([])
        expect(targetOptionType(opts[0])).toBe('component')
    })

    it('keeps the current target by name when the list does not have it', () => {
        const opts = boardTargetOptions(comps, { uuid: 'x9', name: 'checkout', type: 'COMPONENT' })
        expect(opts.find(o => o.value === 'x9')?.label).toBe('checkout')
        expect(boardTargetOptions(comps, { uuid: 'c1', name: 'api' }).filter(o => o.value === 'c1')).toHaveLength(1)
    })

    it('a new board waits on a target; an existing one never does', () => {
        expect(targetMissing(true, null)).toBe(true)
        expect(targetMissing(true, '')).toBe(true)
        expect(targetMissing(true, 'c1')).toBe(false)
        expect(targetMissing(false, null)).toBe(false)
        expect(TARGET_HINT).toBe('the node the board builds')
    })

    it('sends the uuid on create, and on update only when changed', () => {
        expect(targetPatch(null, 'c1')).toBe('c1')
        expect(targetPatch(null, null)).toBeUndefined()
        expect(targetPatch({ target: 'c1' }, 'c1')).toBeUndefined()
        expect(targetPatch({ target: 'c1' }, 'c2')).toBe('c2')
        expect(targetPatch({ target: 'c1' }, null)).toBeUndefined()
    })

    it('the header chip names the target and links to its component', () => {
        expect(targetChip({ target: 'c1', targetDetails: { uuid: 'c1', name: 'api' } }, 'o1'))
            .toEqual({ label: 'target api', to: '/componentsOfOrg/o1/c1' })
        expect(targetChip({ target: 'c1abcdef99' }, 'o1')?.label).toBe('target c1abcdef')
        expect(targetChip({}, 'o1')).toBeNull()
        expect(targetChip(null, 'o1')).toBeNull()
    })

    it('shows the server\'s target refusals beside the field', () => {
        for (const m of ['target api is not a member of payments; add it first',
            'Board requires a target component -- the node it builds',
            'Target component not found: 0000', 'Target component is archived',
            'Target component belongs to another organization']) {
            expect(boardFieldOfError(m)).toBe('target')
        }
        expect(boardFieldOfError("taskPrefix 'R' must be 2 to 8 letters and digits, e.g. RD")).toBe('taskPrefix')
        expect(boardFieldOfError('Board name is required')).toBeNull()
    })
})
