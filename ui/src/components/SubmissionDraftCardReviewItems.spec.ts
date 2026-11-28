// @vitest-environment happy-dom
//
// FDAW-161 3.8: the draft card lists the items of an open REVIEW row under its text, as text only.
import { describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { NNotificationProvider } from 'naive-ui'

vi.mock('@/utils/graphql', () => ({ default: { query: vi.fn(), mutate: vi.fn() } }))
vi.mock('@/utils/fetchClient', () => ({ fetchWithAuth: vi.fn(), fetchArrayBufferWithAuth: vi.fn() }))

const { default: Card } = await import('./SubmissionDraftCard.vue')
const { READINESS_ITEMS_TRUNCATED } = await import('@/utils/submissionDrafts')

const HOSTILE = '<img src=x onerror="window.__hit=1">'
const CONFLICT = { rule: 'SUPPORT_CLAIM_CONFLICT', severity: 'REVIEW', outcome: 'FAIL', count: 101, justified: null,
    text: '101 components say actively maintained', verb: 'ASSESS_SUPPORT', itemsTruncated: true,
    items: [{ key: 'k1', label: 'a 1', detail: 'entered in bulk' }, { key: 'k2', label: HOSTILE, detail: null }] }
const SUPPLIER = { rule: 'SBOM_SUPPLIER', severity: 'MUST_FIX', outcome: 'FAIL', count: 1, justified: null,
    text: 'Supplier name: 1 of 2 not met', verb: 'ADD_SUPPLIERS', itemsTruncated: false,
    items: [{ key: 'k3', label: 'c 3', detail: null }] }
const D: any = {
    uuid: 'd1', org: 'o1', release: 'r1', component: 'c1', kind: 'FDA_PREMARKET', submission: null, state: 'EDITING', revision: 7,
    plannedVersion: 2, startedAt: '2026-11-13T05:00:00Z', startedBy: { actor: 'u1', actorName: 'Ann' }, reasonCategory: 'CORRECTION',
    reasonText: 'fix', replaces: null, job: { state: 'BUILT', attempt: 1 },
    build: { builtAt: '2026-11-13T06:07:08.123Z', releaseSbom: { version: 3 }, parts: [], diff: null },
    confirmation: null, changesSinceBuild: { count: 0, fingerprintEqual: true, movedLines: [] },
    readiness: { ruleSetVersion: '3', rows: [CONFLICT, SUPPLIER] },
}

describe('SubmissionDraftCard lists REVIEW row items (FDAW-161)', () => {
    it('the items of the REVIEW row, as text, with the truncation line; none under the MUST_FIX row', async () => {
        const w = mount(defineComponent({
            render: () => h(NNotificationProvider, () => h(Card, { draft: D, secondConfirmer: false, isWritable: true, busy: false,
                stillRunning: false, names: { componentName: 'Pump', releaseVersion: '1.2.0' }, currentUser: null,
                lifecycle: 'ASSEMBLED', head: null, canWriteOrg: true })),
        }), { attachTo: document.body })
        await flushPromises()
        const rows = w.findAll('[data-testid="draft-readiness-row"]')
        expect(rows.length).toBe(2)
        const items = rows[0].findAll('[data-testid="draft-readiness-item"]').map(i => i.text())
        expect(items).toEqual(['a 1 (entered in bulk)', HOSTILE])
        expect(rows[0].find('[data-testid="draft-readiness-items"]').text()).toContain(READINESS_ITEMS_TRUNCATED)
        expect(rows[0].find('img').exists()).toBe(false)
        expect(rows[1].find('[data-testid="draft-readiness-items"]').exists()).toBe(false)
        w.unmount()
    })
})
