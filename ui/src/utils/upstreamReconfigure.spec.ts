import { describe, expect, it } from 'vitest'
import { buildReconfigureInput, heldCountsOf, initialReconfigureRows, reconfigureChangesSomething, type ReconfigureRow } from './upstreamReconfigure'

// T-540 (TPF-185 design 3.9): the input of the reconfigure dialog holds the checked items with
// their depth and the behaviour on drift when it changed, and nothing else.
const ROOT = '88888888-0000-4000-8000-000000000001'
const COMP = '88888888-0000-4000-8000-000000000002'
const COMP_2 = '88888888-0000-4000-8000-000000000003'

function follow (over: any = {}) {
    return {
        uuid: 'cccccccc-0000-4000-8000-000000000001',
        onDrift: 'STOP',
        item: { uuid: ROOT, kind: 'PRODUCT', upstreamUuid: 'dddddddd-0000-4000-8000-000000000001', upstreamName: 'Widget Suite', depth: 2 },
        components: [{ uuid: COMP, kind: 'COMPONENT', upstreamUuid: 'dddddddd-0000-4000-8000-000000000002', upstreamName: 'Widget Core', depth: 1 }],
        pending: [],
        pendingCount: 0,
        ...over,
    }
}

function rowsOf (f: any, change: (rows: ReconfigureRow[]) => void = () => {}): ReconfigureRow[] {
    const rows = initialReconfigureRows(f)
    change(rows)
    return rows
}

const byItem = (rows: ReconfigureRow[], item: string) => rows.find(r => r.item === item)!

describe('T-540 buildReconfigureInput', () => {
    it('(a) nothing checked and on-drift unchanged: neither key, nothing to change', () => {
        const f = follow()
        const input = buildReconfigureInput(rowsOf(f), 'STOP', f)
        expect(input).toEqual({})
        expect('items' in input).toBe(false)
        expect('onDrift' in input).toBe(false)
        expect(reconfigureChangesSomething(input)).toBe(false)
    })

    it('(b) the root checked with its depth unchanged: the root at depth 2, no onDrift', () => {
        const f = follow()
        const input = buildReconfigureInput(rowsOf(f, rows => { byItem(rows, ROOT).accept = true }), 'STOP', f)
        expect(input).toEqual({ items: [{ item: ROOT, depth: 2 }] })
        expect(reconfigureChangesSomething(input)).toBe(true)
    })

    it('(c) the root checked at depth 3, the component unchecked with its select at 2: the root only', () => {
        const f = follow()
        const input = buildReconfigureInput(rowsOf(f, rows => {
            byItem(rows, ROOT).accept = true
            byItem(rows, ROOT).depth = 3
            byItem(rows, COMP).depth = 2
        }), 'STOP', f)
        expect(input).toEqual({ items: [{ item: ROOT, depth: 3 }] })
    })

    it('(d) nothing checked, on-drift ADAPT: exactly the behaviour', () => {
        const f = follow()
        const input = buildReconfigureInput(rowsOf(f), 'ADAPT', f)
        expect(input).toEqual({ onDrift: 'ADAPT' })
        expect(reconfigureChangesSomething(input)).toBe(true)
    })

    it('sends both keys when an item is checked and the behaviour changes', () => {
        const f = follow({ onDrift: 'ADAPT' })
        const input = buildReconfigureInput(rowsOf(f, rows => { byItem(rows, COMP).accept = true }), 'STOP', f)
        expect(input).toEqual({ items: [{ item: COMP, depth: 1 }], onDrift: 'STOP' })
    })

    it('sends no onDrift for an empty choice', () => {
        const f = follow()
        expect(buildReconfigureInput(rowsOf(f), null, f)).toEqual({})
        expect(buildReconfigureInput(rowsOf(f), undefined, f)).toEqual({})
    })
})

describe('the rows of the dialog', () => {
    it('start with nothing checked and every depth at the current depth, in the follow order without held releases', () => {
        const rows = initialReconfigureRows(follow())
        expect(rows.map(r => [r.item, r.name, r.kind, r.currentDepth, r.depth, r.accept, r.held])).toEqual([
            [ROOT, 'Widget Suite', 'PRODUCT', 2, 2, false, 0],
            [COMP, 'Widget Core', 'COMPONENT', 1, 1, false, 0],
        ])
        expect(rows[0].upstreamUuid).toBe('dddddddd-0000-4000-8000-000000000001')
    })

    it('put the items with held releases first and count their pending rows', () => {
        const f = follow({
            components: [
                { uuid: COMP, kind: 'COMPONENT', upstreamUuid: 'd2', upstreamName: 'Widget Core', depth: 1 },
                { uuid: COMP_2, kind: 'COMPONENT', upstreamUuid: 'd3', upstreamName: 'Widget Extra', depth: 0 },
            ],
            pending: [{ uuid: 'p1', item: COMP_2 }, { uuid: 'p2', item: COMP_2 }, { uuid: 'p3', item: COMP }],
            pendingCount: 3,
        })
        expect(heldCountsOf(f)).toEqual({ [COMP_2]: 2, [COMP]: 1 })
        expect(initialReconfigureRows(f).map(r => [r.item, r.held])).toEqual([[COMP, 1], [COMP_2, 2], [ROOT, 0]])
    })

    it('read a follow without components or pending rows', () => {
        expect(initialReconfigureRows({ item: { uuid: ROOT, depth: 0 } }).map(r => r.item)).toEqual([ROOT])
        expect(initialReconfigureRows(null)).toEqual([])
        expect(heldCountsOf(null)).toEqual({})
    })
})
