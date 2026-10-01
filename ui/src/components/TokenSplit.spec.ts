// @vitest-environment happy-dom
//
// The token split beside a total (task RD2-3): inline beside the total, and in the tooltip of a
// compact one (table rows, cards). The inline suffix is hidden below 1280px by the component's CSS.
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import TokenSplit from './TokenSplit.vue'

const tooltip = { template: '<span class="tt"><slot name="trigger"/><span class="tt__body"><slot/></span></span>' }
const stubs = { NTooltip: tooltip, Tooltip: tooltip }
const usage = { inputTokens: 300_000, outputTokens: 120_000, cacheReadTokens: 700_000, cacheWriteTokens: 80_000 }
const split = 'in 300.0k · out 120.0k · cache 700.0k read / 80.0k write'

describe('TokenSplit', () => {
    it('shows the total and the split inline, and the split on hover too', () => {
        const w = mount(TokenSplit, { props: { usage, suffix: ' tok' }, global: { stubs } })
        expect(w.find('.tokensplit__total').text()).toBe('1.20M tok')
        expect(w.find('.tokensplit__split').text()).toBe(split)
        expect(w.find('.tt__body').text()).toBe(split)
    })

    it('keeps the split in the tooltip when compact', () => {
        const w = mount(TokenSplit, { props: { usage, compact: true }, global: { stubs } })
        expect(w.find('.tokensplit__total').text()).toBe('1.20M')
        expect(w.find('.tokensplit__split').exists()).toBe(false)
        expect(w.find('.tt__body').text()).toBe(split)
    })

    it('shows the total alone when there is no split to show', () => {
        const w = mount(TokenSplit, { props: { usage: null }, global: { stubs } })
        expect(w.text()).toBe('0')
        expect(w.find('.tt').exists()).toBe(false)
    })
})
