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
        { uuid: 'b1', name: 'ReARM Dogfood 2', taskPrefix: 'RD2', pause: null },
        { uuid: 'b2', name: 'gate2', taskPrefix: 'G2', pause: { level: 'OPERATOR' } },
        { uuid: 'b3', name: 'no prefix yet' },
    ]

    it('reads prefix · name, and flags a paused board', () => {
        expect(boardPickerOptions(boards)).toEqual([
            { label: 'RD2 · ReARM Dogfood 2', value: 'b1', paused: false },
            { label: 'G2 · gate2', value: 'b2', paused: true },
            { label: 'no prefix yet', value: 'b3', paused: false },
        ])
    })

    it('draws the pause as a tag after the name, and nothing on a board that is not paused', () => {
        const draw = (i: number) => mount(defineComponent({ render: () => renderBoardOption(boardPickerOptions(boards)[i]) }))
        const paused = draw(1)
        expect(paused.text()).toBe('G2 · gate2paused')
        expect(paused.find('[data-testid="board-option-paused"]').text()).toBe('paused')
        expect(draw(0).find('[data-testid="board-option-paused"]').exists()).toBe(false)
    })
})
