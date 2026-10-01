// @vitest-environment happy-dom
//
// The board page's Documents section (task 36d0549e): one row per document series, and an empty state.
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import Panel from './AgentBoardDocumentsPanel.vue'

const stubs = { RouterLink: { props: ['to'], template: '<a class="rl" :href="JSON.stringify(to)"><slot/></a>' } }

describe('AgentBoardDocumentsPanel', () => {
    it('renders a row per series, linking to the component', () => {
        const w = mount(Panel, {
            props: {
                orgUuid: 'o1',
                series: [
                    { specification: 'ARCHITECTURE', component: { uuid: 'c1', name: 'rd-architecture' },
                        latestRound: { round: 2, version: '5', lifecycle: 'ASSEMBLED' }, roundsCount: 5, openReviewItems: null, elementCheckVerdict: 'PASS' },
                    { specification: 'BOARD_REVIEW_ITEMS', component: { uuid: 'c2', name: 'rd-board-review-items' },
                        latestRound: { round: 1, version: '1', lifecycle: 'ASSEMBLED' }, roundsCount: 1, openReviewItems: 3, elementCheckVerdict: null },
                ],
            },
            global: { stubs },
        })
        const rows = w.findAll('.boardDocuments__row')
        expect(rows).toHaveLength(2)
        expect(rows[0].text()).toContain('architecture')
        expect(rows[0].text()).toContain('rd-architecture')
        expect(rows[0].text()).toContain('PASS')
        expect(rows[1].text()).toContain('review items')
        expect(rows[1].text()).toContain('3')
        expect(rows[0].find('.rl').attributes('href')).toContain('"compuuid":"c1"')
    })

    it('says so when the board has published nothing', () => {
        const w = mount(Panel, { props: { series: [] }, global: { stubs } })
        expect(w.find('.boardDocuments__row').exists()).toBe(false)
        expect(w.text()).toContain('No documents yet')
    })
})

// RD2-24: the Element checks cell says what the task page says, with the verdict by the same rule.
describe('the Documents tab\'s element checks', () => {
    it('counts the element checks and gives the verdict from the counts', () => {
        const w = mount(Panel, { props: { orgUuid: 'o1', series: [{ specification: 'ARCHITECTURE', component: { uuid: 'c1', name: 'arch' },
            latestRound: { round: 1, version: '0', lifecycle: 'ASSEMBLED' }, roundsCount: 1, openReviewItems: null,
            elementCheckVerdict: 'FAIL', elementCheckCounts: { pass: 7, fail: 1, skip: 2, blockingFailed: 1 } }] }, global: { stubs } })
        const cells = w.findAll('.boardDocuments__row td').map(c => c.text())
        expect(cells).toContain('round 1 · v0')
        expect(cells).toContain('assembled')
        expect(w.find('.boardDocuments__checks').text()).toBe('7 pass · 1 fail · 2 skip · FAIL')
    })
})

