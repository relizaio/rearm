// @vitest-environment happy-dom
//
// FDAW-78 row 17 (REQ-68, C6): the product page heading pluralizes the feature-set label as a word:
// `Feature Set` reads `feature sets`, never `feature setes`; the default `Branch` reads `branches`.
import { describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { NNotificationProvider } from 'naive-ui'

vi.mock('vuex', () => ({ useStore: () => ({ dispatch: vi.fn(), getters: {}, state: {} }) }))
vi.mock('vue-router', async (importOriginal) => ({
    ...(await importOriginal<typeof import('vue-router')>()),
    useRouter: () => ({ push: vi.fn() }),
}))
vi.mock('@/utils/graphql', () => ({ default: { query: vi.fn(async () => ({ data: { latestReleasesOfComponent: [] } })) } }))
vi.mock('@/utils/releaseScanStatus', () => ({ isDtrackConfiguredForOrg: vi.fn(async () => false), getReleaseScanStatus: vi.fn() }))
vi.mock('@/utils/fetchClient', () => ({ fetchWithAuth: vi.fn(), fetchArrayBufferWithAuth: vi.fn() }))

const { default: Latest } = await import('./LatestReleasesOfComponent.vue')

async function mountWith (props: Record<string, any> = {}) {
    const w = mount(defineComponent({
        render: () => h(NNotificationProvider, () => h(Latest, { componentUuid: 'c1', orgUuid: 'o1', ...props })),
    }))
    await flushPromises()
    return w
}

describe('LatestReleasesOfComponent: the heading (FDAW-78 row 17, C6)', () => {
    it('reads feature sets for the Feature Set label', async () => {
        const w = await mountWith({ featureSetLabel: 'Feature Set' })
        expect(w.find('.latestReleasesTitle').text()).toBe('Most recent releases across feature sets')
        expect(w.text()).not.toContain('setes')
        w.unmount()
    })

    it('reads branches for the default label', async () => {
        const w = await mountWith()
        expect(w.find('.latestReleasesTitle').text()).toBe('Most recent releases across branches')
        w.unmount()
    })
})
