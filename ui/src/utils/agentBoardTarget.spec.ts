import { describe, expect, it } from 'vitest'
import { boardTargetOptions, targetChip, targetFilter, targetMissing, targetOf, targetOptionType, targetPatch, TARGET_HINT } from './agentBoardTarget'
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
            'Target component belongs to another organization',
            // T-1384: the board document refusal (task TPF-274) reaches the field too.
            'Target component is a board document component; a board builds a component or product']) {
            expect(boardFieldOfError(m)).toBe('target')
        }
        expect(boardFieldOfError("taskPrefix 'R' must be 2 to 8 letters and digits, e.g. RD")).toBe('taskPrefix')
        expect(boardFieldOfError('Board name is required')).toBeNull()
    })
    // Task TPF-274: products are offered beside components, and a typed or pasted uuid selects its item.
    const prods = [
        { uuid: 'p1', name: 'shop', type: 'PRODUCT' },
        { uuid: 'p2', name: 'api', type: 'PRODUCT' },
        { uuid: 'p3', name: 'gone', type: 'PRODUCT', status: 'ARCHIVED' },
    ]
    const SHOP = '5b1c0a7e-3f4d-4e2a-9c61-0d8e7f6a5b41'
    const API_C = '0c9e2d4f-7a1b-4c3d-8e5f-6a7b8c9d0e12'
    const real = [
        { uuid: '1d2e3f40-5a6b-4c7d-8e9f-0a1b2c3d4e5f', name: 'web', type: 'COMPONENT' },
        { uuid: API_C, name: 'api', type: 'COMPONENT' },
        { uuid: SHOP, name: 'shop', type: 'PRODUCT' },
        { uuid: '9f8e7d6c-5b4a-4392-8170-6f5e4d3c2b1a', name: 'api', type: 'PRODUCT' },
    ]

    it('T-1380: offers products with their type beside components, sorted by name then type', () => {
        const opts = boardTargetOptions([...comps, ...prods])
        expect(opts.map(o => [o.label, o.type])).toEqual([
            ['api', 'COMPONENT'], ['api', 'PRODUCT'], ['shop', 'PRODUCT'], ['web', 'COMPONENT']])
        expect(opts.map(o => o.value)).not.toContain('d1')
        expect(opts.map(o => o.value)).not.toContain('c3')
        expect(opts.map(o => o.value)).not.toContain('p3')
        expect(targetOptionType(opts.find(o => o.label === 'shop'))).toBe('product')
    })

    it('T-1381: a full uuid matches its own item only; any other text matches names', () => {
        const opts = boardTargetOptions(real)
        const matching = (p: string) => opts.filter(o => targetFilter(p, o)).map(o => `${o.label} ${o.type}`)
        expect(matching('SHO')).toEqual(['shop PRODUCT'])
        expect(matching(' api ')).toEqual(['api COMPONENT', 'api PRODUCT'])
        expect(matching(`  ${SHOP.toUpperCase()} `)).toEqual(['shop PRODUCT'])
        expect(matching(` ${API_C.toUpperCase()}`)).toEqual(['api COMPONENT'])
        expect(matching(SHOP.slice(0, 8))).toEqual([])
        expect(matching('')).toHaveLength(opts.length)
    })

    it('T-1382: a uuid no option holds adds one unlisted option, sent normalized', () => {
        const other = 'AB12CD34-EF56-4A78-9B01-23456789ABCD'
        const value = other.toLowerCase()
        const opts = boardTargetOptions(real, null, [`  ${other} `, value])
        const unlisted = opts.filter(o => o.unlisted)
        expect(unlisted).toEqual([{ label: value, value, type: '', unlisted: true }])
        expect(targetOptionType(unlisted[0])).toBe('not in the list, checked on save')
        expect(targetPatch(null, unlisted[0].value)).toBe(value)
        const current = { uuid: '7e6d5c4b-3a29-4180-9f7e-6d5c4b3a2918', name: 'checkout', type: 'PRODUCT' }
        for (const entered of [SHOP, current.uuid, '00000000-0000-0000-0000-000000000000', 'abc', null]) {
            expect(boardTargetOptions(real, current, [entered]).filter(o => o.unlisted), String(entered)).toEqual([])
        }
    })

    it('T-1383: the chip of a product target links to the Products page; its label is plain text', () => {
        expect(targetChip({ target: 'p1', targetDetails: { uuid: 'p1', name: 'shop', type: 'PRODUCT' } }, 'o1'))
            .toEqual({ label: 'target shop', to: '/productsOfOrg/o1/p1' })
        expect(targetChip({ target: 'p1', targetDetails: null }, 'o1', prods)?.to).toBe('/productsOfOrg/o1/p1')
        expect(targetChip({ target: 'x1', targetDetails: { uuid: 'x1', name: 'x' } }, 'o1')?.to).toBe('/componentsOfOrg/o1/x1')
        expect(targetChip({ target: 'x1', targetDetails: { uuid: 'x1', name: 'x', type: 'SOMETHING' } }, 'o1')?.to)
            .toBe('/componentsOfOrg/o1/x1')
        expect(targetChip({ target: 'p9', targetDetails: { uuid: 'p9', name: '<b>x</b>', type: 'PRODUCT' } }, 'o1')?.label)
            .toBe('target <b>x</b>')
    })
})
