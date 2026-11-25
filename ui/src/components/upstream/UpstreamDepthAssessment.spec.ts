// @vitest-environment happy-dom
//
// TPF-229 (TPF-185 design 3.8 section 3 and 3.9): the depth choice with its line preview. In the
// form it emits the chosen depth and previews the chosen depth's lines; in its read-only form the
// radio only changes which depth's lines are shown, chooses nothing, and "Use this depth" emits
// the depth shown.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { NRadio } from 'naive-ui'

vi.mock('@/utils/graphql', () => ({ default: { query: vi.fn(), mutate: vi.fn() } }))

const { default: DepthAssessment } = await import('./UpstreamDepthAssessment.vue')

function proposal () {
    const lines = (name: string) => [{ lineKey: name, branchName: name, releases: 4, lowestVersion: '1.0.0', highestVersion: '1.3.0', newestDate: null }]
    return {
        proposedDepth: 1, depthBasis: 'HISTORY',
        depths: [0, 1, 2].map(depth => ({ depth, fit: 'FITS', lineCount: depth, unplaceable: 0, linesTruncated: false, lines: depth ? lines(`line-${depth}`) : [] })),
    }
}

async function choose (w: any, depth: number) {
    const radio = w.findAllComponents(NRadio).find((r: any) => r.attributes('data-testid') === `upstream-depth-${depth}`)
    await radio.find('input').setValue(true)
}

const preview = (w: any) => w.find('[data-testid="upstream-depth-preview"]')

describe('UpstreamDepthAssessment', () => {
    it('emitsTheChosenDepthAndPreviewsTheDepthItIsGiven', async () => {
        const w = mount(DepthAssessment, { props: { proposal: proposal(), modelValue: 2 } })
        expect(preview(w).exists()).toBe(true)
        expect(preview(w).text()).toContain('line-2')
        await choose(w, 0)
        expect(w.emitted('update:modelValue')).toEqual([[0]])
        w.unmount()
    })

    it('onlyChangesThePreviewInItsReadOnlyFormAndUsesTheDepthShown', async () => {
        const w = mount(DepthAssessment, { props: { proposal: proposal(), readOnly: true } })
        expect(preview(w).text()).toContain('line-1')
        await choose(w, 2)
        expect(preview(w).text()).toContain('line-2')
        expect(w.emitted('update:modelValue')).toBeUndefined()
        await w.find('[data-testid="upstream-depth-use"]').trigger('click')
        expect(w.emitted('use')).toEqual([[2]])
        w.unmount()
    })
})
