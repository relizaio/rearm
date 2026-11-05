// @vitest-environment happy-dom
//
// TPF-157: the full most-recent-releases page lists software only, as the home widget does. The page
// mounts the widget with the same bindings as before minus include-documents, so the widget sends the
// software kinds on both of its reads; the widget's own reads are pinned in MostRecentReleasesWidget.spec.ts.
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'

const { getters } = vi.hoisted(() => ({ getters: { myorg: { uuid: 'o1' }, myperspective: 'default' as string } }))
vi.mock('vue-router', () => ({
    useRoute: () => ({ params: { orguuid: 'o1' }, query: {} }),
    useRouter: () => ({ replace: vi.fn() }),
    RouterLink: { name: 'RouterLink', render: () => null },
}))
vi.mock('vuex', () => ({ useStore: () => ({ getters }) }))
vi.mock('./MostRecentReleasesWidget.vue', () => ({ default: { name: 'MostRecentReleasesWidget', render: () => null } }))
const { default: Page } = await import('./MostRecentReleasesPage.vue')

const BINDINGS = ['component-type', 'end-date', 'limit', 'org-uuid', 'perspective-uuid', 'show-full-page-icon', 'start-date']

let wrapper: VueWrapper | null = null
afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    getters.myperspective = 'default'
})

function widgetAttrs (): Record<string, unknown> {
    wrapper = mount(Page)
    const widget = wrapper.findComponent({ name: 'MostRecentReleasesWidget' })
    expect(widget.exists()).toBe(true)
    return { ...widget.vm.$attrs }
}

describe('MostRecentReleasesPage (TPF-157)', () => {
    it('(a) does not pass include-documents to the widget', () => {
        const attrs = widgetAttrs()
        expect(attrs).not.toHaveProperty('include-documents')
        expect(attrs).not.toHaveProperty('includeDocuments')
    })

    it('(b) passes exactly the seven filter bindings', () => {
        expect(Object.keys(widgetAttrs()).sort()).toEqual(BINDINGS)
    })

    it('(c) passes the org, the default filters and no perspective on the default perspective', () => {
        const attrs = widgetAttrs()
        expect(attrs['org-uuid']).toBe('o1')
        expect(attrs['show-full-page-icon']).toBe(false)
        expect(attrs.limit).toBe(10)
        expect(attrs['component-type']).toBeNull()
        expect(attrs['perspective-uuid']).toBeUndefined()
        const start = attrs['start-date'] as Date
        const end = attrs['end-date'] as Date
        expect(start).toBeInstanceOf(Date)
        expect(end).toBeInstanceOf(Date)
        expect(end.getTime() - start.getTime()).toBe(86400000)
    })

    it('(d) passes a chosen perspective, with the same bindings', () => {
        getters.myperspective = 'p1'
        const attrs = widgetAttrs()
        expect(attrs['perspective-uuid']).toBe('p1')
        expect(Object.keys(attrs).sort()).toEqual(BINDINGS)
    })
})
