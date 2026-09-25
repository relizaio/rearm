import { describe, expect, it } from 'vitest'
import { aboutOptionsOf, fileSpecOptions, priorityOptionsOf, roleOptionsOf } from './agentTaskOptions'

function shaped (opts: any[]) {
    for (const o of opts) {
        expect(Object.keys(o).sort()).toEqual(['label', 'value'])
        expect(typeof o.label).toBe('string')
    }
}

describe('agentTaskOptions', () => {
    it('lists P1..Pn, three by default', () => {
        expect(priorityOptionsOf(undefined)).toEqual([
            { label: 'P1', value: 1 }, { label: 'P2', value: 2 }, { label: 'P3', value: 3 }])
        expect(priorityOptionsOf(5).map(o => o.value)).toEqual([1, 2, 3, 4, 5])
    })

    it('lists active roles by name', () => {
        const opts = roleOptionsOf([{ name: 'coder', active: true }, { name: 'old', active: false }])
        expect(opts).toEqual([{ label: 'coder', value: 'coder' }])
        expect(roleOptionsOf(null)).toEqual([])
    })

    it('lists what active roles produce, sorted and readable', () => {
        const opts = aboutOptionsOf([
            { active: true, producesOutputs: [{ specification: 'REVIEW_FINDINGS' }, { specification: 'ARCHITECTURE' }] },
            { active: true, producesOutputs: [{ specification: 'ARCHITECTURE' }, null] },
            { active: false, producesOutputs: [{ specification: 'TEST_PLAN' }] },
        ])
        expect(opts).toEqual([
            { label: 'architecture', value: 'ARCHITECTURE' },
            { label: 'review findings', value: 'REVIEW_FINDINGS' },
        ])
    })

    it('files findings in the indexed types', () => {
        expect(fileSpecOptions.map(o => o.value)).toContain('REVIEW_FINDINGS')
        expect(fileSpecOptions.find(o => o.value === 'TEST_REPORT')?.label).toBe('test report')
    })

    it('every list is {label, value}', () => {
        shaped(priorityOptionsOf(3))
        shaped(roleOptionsOf([{ name: 'coder', active: true }]))
        shaped(aboutOptionsOf([{ active: true, producesOutputs: [{ specification: 'ARCHITECTURE' }] }]))
        shaped(fileSpecOptions)
    })
})
