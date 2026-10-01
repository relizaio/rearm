import { describe, expect, it } from 'vitest'
import { boardTargetOptions, targetChip, targetMissing, targetOf, targetOptionType, targetPatch, TARGET_HINT } from './agentBoardTarget'
import { boardFieldOfError } from './agentBoardNaming'

// The target picker in the board form (task RD2-4).
describe('the board target picker', () => {
    const comps = [
        { uuid: 'c2', name: 'web', type: 'COMPONENT' },
        { uuid: 'c1', name: 'api', type: 'COMPONENT' },
        { uuid: 'd1', name: 'Payments documents', type: 'COMPONENT', kind: 'BOARD_DOCUMENT' },
        { uuid: 'c3', name: 'old', type: 'COMPONENT', status: 'ARCHIVED' },
    ]

    it('offers the software components by name; no BOARD_DOCUMENT component, nothing archived', () => {
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

    // T-1: the server answered targetDetails with null for every board; the name then comes from the
    // loaded components, and the uuid's first eight characters only when nothing names it.
    it('names the target from targetDetails, else the loaded components, else the uuid', () => {
        const served = { target: 'c1', targetDetails: { uuid: 'c1', name: 'api' } }
        const unserved = { target: 'c1', targetDetails: null }
        expect(targetChip(served, 'o1', comps)?.label).toBe('target api')
        expect(targetChip({ ...served, targetDetails: { uuid: 'c1', name: 'renamed' } }, 'o1', comps)?.label,
            'the server wins over the cache').toBe('target renamed')
        expect(targetChip(unserved, 'o1', comps)).toEqual({ label: 'target api', to: '/componentsOfOrg/o1/c1' })
        expect(targetChip({ target: 'feedbeef0001', targetDetails: null }, 'o1', comps)?.label).toBe('target feedbeef')
        expect(targetOf(unserved, comps)).toEqual({ uuid: 'c1', name: 'api', type: 'COMPONENT' })
        expect(targetOf({ target: 'zz' }, null)).toEqual({ uuid: 'zz' })
        expect(targetOf({}, comps)).toBeNull()
    })

    it('keeps the current target on Edit by the name the cache gives it, the uuid prefix last', () => {
        const current = targetOf({ target: 'c3', targetDetails: null }, comps)
        expect(boardTargetOptions(comps.filter(c => c.uuid !== 'c3'), current).find(o => o.value === 'c3')?.label).toBe('old')
        expect(boardTargetOptions([], { uuid: 'feedbeef0001' }).map(o => o.label)).toEqual(['feedbeef'])
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
