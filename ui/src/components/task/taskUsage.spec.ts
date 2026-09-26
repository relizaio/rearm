// @vitest-environment happy-dom
//
// The task usage chip shows what the board charges the task (task 02bfab7c).
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { NTag } from 'naive-ui'
import TaskUsage from './TaskUsage.vue'

function chips (task: any, board: any = { softAlertPercent: 80 }) {
    const w = mount(TaskUsage, { props: { task, board } })
    return { w, spend: w.find('.spendtag'), budget: w.find('.budtag') }
}

describe('task usage chip', () => {
    it('shows a task whose only cost is its coordinator share', () => {
        const { w, spend } = chips({ usage: null, spentMicros: 50_000, coordinatorEstimateMicros: 50_000, budgetMicros: null })
        expect(w.find('.dsec').exists()).toBe(true)
        expect(spend.text()).toBe('$0.05 spent')
    })

    it('hides for a task with no spend and no budget', () => {
        expect(chips({ usage: null, spentMicros: 0, budgetMicros: null }).w.find('.dsec').exists()).toBe(false)
    })

    it('colours the budget at the soft alert and at the limit, on the board\'s figure', () => {
        const at = (spent: number) => chips({ usage: null, spentMicros: spent, budgetMicros: 1_000_000 }).w
            .findAllComponents(NTag).find(t => t.classes().includes('budtag'))!
        expect(at(500_000).props('type')).toBe('success')
        expect(at(800_000).props('type')).toBe('warning')
        expect(at(1_000_000).props('type')).toBe('error')
        expect(at(800_000).text()).toBe("spent $0.80 of $1.00 (80%) of this task's budget")
    })
})
