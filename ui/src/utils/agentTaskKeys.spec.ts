// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { boardPickerOptions, renderBoardOption, reviewBannerLabel } from './agentTaskKeys'

describe('the review banner (RD2-22)', () => {
    it('names a task key first, then its title and the gate role', () => {
        expect(reviewBannerLabel({ key: 'RD2-22', title: 'Task key missing', externalRef: 'github:acme/x#12',
            hold: { gateRole: 'reviewer' } })).toBe('RD2-22 Task key missing (reviewer)')
    })

    it('falls back to the issue number, then the title, for a task from before keys', () => {
        expect(reviewBannerLabel({ title: 'old', externalRef: 'github:acme/x#12', hold: { gateRole: 'reviewer' } }))
            .toBe('#12 (reviewer)')
        expect(reviewBannerLabel({ title: 'old', hold: {} })).toBe('old')
    })
})

describe('the board picker (RD2-22)', () => {
    const boards = [
        { uuid: 'b1', name: 'ReARM Dogfood 2', taskPrefix: 'RD2', lock: null },
        { uuid: 'b2', name: 'gate2', taskPrefix: 'G2', lock: { level: 'OPERATOR' } },
        { uuid: 'b3', name: 'no prefix yet' },
    ]

    it('reads prefix · name, and flags a locked board', () => {
        expect(boardPickerOptions(boards)).toEqual([
            { label: 'RD2 · ReARM Dogfood 2', value: 'b1', locked: false },
            { label: 'G2 · gate2', value: 'b2', locked: true },
            { label: 'no prefix yet', value: 'b3', locked: false },
        ])
    })

    it('draws the lock as a tag after the name, and nothing on an unlocked board', () => {
        const draw = (i: number) => mount(defineComponent({ render: () => renderBoardOption(boardPickerOptions(boards)[i]) }))
        const locked = draw(1)
        expect(locked.text()).toBe('G2 · gate2locked')
        expect(locked.find('[data-testid="board-option-locked"]').text()).toBe('locked')
        expect(draw(0).find('[data-testid="board-option-locked"]').exists()).toBe(false)
    })
})
