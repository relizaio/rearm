// @vitest-environment happy-dom
//
// TPF-122 (architecture-3 section 5, row 22): the scan status panel renders the answer its load
// prop returns, and nothing else. No client is involved: every case passes a load stub.
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('@/utils/commonFunctions', () => ({
    default: {
        extractGraphQLErrorMessage: (e: any) => e?.message || 'Unknown error',
        parseGraphQLError: (s: string) => s
    }
}))

import { NTag } from 'naive-ui'
import ArtifactScanStatusPanel from './ArtifactScanStatusPanel.vue'
import { formatInstant } from '@/utils/artifactScanStatus'
import type { ArtifactScanStatus, ScanBlocker, ScanStatusLoadResult } from '@/utils/artifactScanStatus'

const UUID = '0d0e0f00-1111-4222-8333-444455556666'
const CANONICAL = 'c0ffee00-aaaa-4bbb-8ccc-ddddeeeeffff'
const UNMAPPED_SENTENCE = 'no canonical map row yet: the post-upload reconcile has not mapped this artifact; a user with write access to this release can map it now with Reconcile under SBOM Components on this page (BOM Components tab); otherwise the scheduled sweep maps a BOM that every reconcile missed once it is 90 minutes old'

const rowA: ScanBlocker = {
    purl: 'pkg:npm/a@1', enrichment: 'UNENRICHED', terminalReason: null, bucketIndex: null,
    bucketIngestState: null, bucketLastUpdatedDate: null, reason: 'not enriched yet; submitOrg ships only enriched components'
}
const rowB: ScanBlocker = {
    purl: 'pkg:npm/b@2', enrichment: 'ENRICHED', terminalReason: null, bucketIndex: 7,
    bucketIngestState: 'SUBMITTED', bucketLastUpdatedDate: '2026-10-29T09:30:00Z',
    reason: 'bucket 7 is SUBMITTED since 2026-10-29T09:30:00Z; its components are uncovered until it is INGESTED'
}

const blocked: ArtifactScanStatus = {
    artifactUuid: UUID,
    type: 'BOM',
    state: 'BLOCKED_ON_COVERAGE',
    mapped: true,
    canonicalArtifactUuid: CANONICAL,
    mappedAt: '2026-10-28T09:00:00Z',
    firstScanned: null,
    lastScanned: null,
    fanOutAt: '2026-10-28T10:00:00.123456789Z',
    fanOutRequeuedAt: null,
    newestIngestedBucketUpdate: '2026-10-29T09:00:00Z',
    ingestedBucketCount: 0,
    enrichmentGated: true,
    inCandidateSet: false,
    candidateRule: 'not a candidate: fanned out at 2026-10-28T10:00:00.123456789Z and no INGESTED bucket holding one of its components moved since',
    matchableComponentCount: 5,
    rootComponentCount: 1,
    terminalComponentCount: 0,
    unmatchableComponentCount: 0,
    noMatchableComponentsReason: null,
    blockerTotal: 2,
    blockersTruncated: false,
    blockers: [rowA, rowB]
}
const truncatedFx: ArtifactScanStatus = {
    ...blocked,
    blockers: Array.from({ length: 50 }, (_, i) => ({ ...rowA, purl: `pkg:npm/p${i}@1` })),
    blockerTotal: 73,
    blockersTruncated: true
}
const scanned: ArtifactScanStatus = {
    ...blocked,
    state: 'SCANNED',
    firstScanned: '2026-10-28T10:00:00Z',
    lastScanned: '2026-10-29T10:00:00Z',
    enrichmentGated: null,
    blockerTotal: 0,
    blockers: []
}
const notABom: ArtifactScanStatus = { ...scanned, state: 'NOT_A_BOM', type: 'TEST_REPORT', mapped: false, canonicalArtifactUuid: null }
const marked: ArtifactScanStatus = {
    ...scanned,
    state: 'NO_MATCHABLE_COMPONENTS',
    notScannableAt: '2026-10-29T08:00:00Z',
    noMatchableComponentsReason: 'NO_PURL_OR_CPE',
    matchableComponentCount: 1,
    inCandidateSet: true
}
const unmapped: ArtifactScanStatus = {
    ...scanned,
    state: 'UNMAPPED',
    mapped: false,
    canonicalArtifactUuid: null,
    mappedAt: null,
    firstScanned: null,
    lastScanned: null,
    fanOutAt: null,
    fanOutRequeuedAt: null,
    newestIngestedBucketUpdate: null,
    blockers: [],
    blockerTotal: 0,
    candidateRule: 'not mapped: no canonical map row yet (the post-upload reconcile has not mapped this artifact)'
}

const served = (status: ArtifactScanStatus | null) => vi.fn(async (): Promise<ScanStatusLoadResult> => ({ status, supported: true }))

async function mountPanel (load: (uuid: string) => Promise<ScanStatusLoadResult>, notify = vi.fn()) {
    const w = mount(ArtifactScanStatusPanel, { props: { artifactUuid: UUID, load, notify }, attachTo: document.body })
    await flushPromises()
    return { w, notify }
}

const tid = (id: string) => `[data-testid="artifact-scan-status-${id}"]`

afterEach(() => {
    vi.restoreAllMocks()
    document.body.innerHTML = ''
})

describe('ArtifactScanStatusPanel (row 22)', () => {
    it('(a) blocked: the state line, the candidate line, one load for the prop uuid', async () => {
        const load = served(blocked)
        const { w } = await mountPanel(load)
        expect(w.find(tid('panel')).exists()).toBe(true)
        const state = w.find(tid('state'))
        expect(state.attributes('data-state')).toBe('BLOCKED_ON_COVERAGE')
        const tag = w.findComponent(NTag)
        expect(tag.text()).toBe('Blocked on coverage')
        expect(tag.props('type')).toBe('warning')
        expect(w.find(tid('state-text')).text()).toBe('2 of 5 matchable components are not covered by an ingested Dependency-Track bucket')
        const candidate = w.find(tid('candidate'))
        expect(candidate.attributes('data-candidate')).toBe('false')
        expect(candidate.text()).toContain('Fan-out candidate: not a candidate')
        expect(candidate.text()).toContain(blocked.candidateRule)
        expect(load).toHaveBeenCalledTimes(1)
        expect(load).toHaveBeenCalledWith(UUID)
    })

    it('(b) the twelve facts in order, ISO titles, dashes for null dates', async () => {
        const { w } = await mountPanel(served(blocked))
        const facts = w.find(tid('facts')).findAll('dd[data-testid^="artifact-scan-status-fact-"]')
        expect(facts.map(f => f.attributes('data-testid')!.replace('artifact-scan-status-fact-', ''))).toEqual([
            'type', 'mapped', 'mappedAt', 'firstScanned', 'lastScanned', 'fanOutAt', 'fanOutRequeuedAt',
            'newestIngestedBucketUpdate', 'ingestedBucketCount', 'components', 'enrichmentGated', 'blockerTotal'])
        expect(w.find(tid('fact-fanOutAt')).attributes('title')).toBe(blocked.fanOutAt)
        expect(w.find(tid('fact-firstScanned')).text()).toBe('-')
        expect(w.find(tid('fact-ingestedBucketCount')).text()).toBe('0 (no ingested bucket yet; no fan-out pass runs until one ingests)')
        expect(w.find(tid('fact-enrichmentGated')).text()).toBe('submission waits for BEAR enrichment')
    })

    it('(c) the canonical uuid is code with a copy button, never a link', async () => {
        const { w, notify } = await mountPanel(served(blocked))
        expect(w.find(`code${tid('canonical')}`).text()).toBe(CANONICAL)
        const copy = w.find(tid('canonical-copy'))
        expect(copy.element.tagName).toBe('BUTTON')
        expect(copy.attributes('title')).toBe('Copy Canonical artifact UUID')
        expect(copy.attributes('aria-label')).toBe('Copy Canonical artifact UUID')
        expect(w.find('a[href]').exists()).toBe(false)

        const writeText = vi.fn().mockResolvedValue(undefined)
        Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
        await copy.trigger('click')
        await flushPromises()
        expect(writeText).toHaveBeenCalledWith(CANONICAL)
        expect(notify).toHaveBeenCalledWith('success', 'Copied', 'Canonical artifact UUID copied')

        notify.mockReset()
        Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true })
        await copy.trigger('click')
        await flushPromises()
        expect(notify).toHaveBeenCalledTimes(1)
        expect(notify.mock.calls[0][0]).toBe('warning')
        expect(notify.mock.calls[0][1]).toBe('Copy unavailable')

        const none = await mountPanel(served(notABom))
        expect(none.w.find(tid('canonical-none')).text()).toBe('-')
        expect(none.w.find(tid('canonical-copy')).exists()).toBe(false)
    })

    it('(d) the blockers table: six columns, rows in server order, the gated note', async () => {
        const { w } = await mountPanel(served(blocked))
        const table = w.find(tid('blockers'))
        expect(table.exists()).toBe(true)
        expect(table.findAll('th').map(th => th.text())).toEqual(['Component', 'Enrichment', 'Bucket', 'Ingest state', 'Bucket updated', 'Reason'])
        const rows = table.findAll('tr[data-testid^="artifact-scan-status-blocker-"]')
        expect(rows.map(r => r.attributes('data-testid'))).toEqual(['artifact-scan-status-blocker-0', 'artifact-scan-status-blocker-1'])
        const cells = (i: number) => rows[i].findAll('td').map(td => td.text())
        expect(cells(0)).toEqual(['pkg:npm/a@1', 'not enriched (waiting for enrichment)', '-', '-', '-', rowA.reason])
        const row1 = cells(1)
        expect(row1.slice(0, 4)).toEqual(['pkg:npm/b@2', 'enriched', '7', 'SUBMITTED'])
        expect(row1[4]).toMatch(/ago\)$/)
        expect(row1[5]).toBe(rowB.reason)
        expect(rows[1].findAll('td')[4].find('span[title]').attributes('title')).toBe(rowB.bucketLastUpdatedDate)
        const note = w.find(tid('enrichment-note'))
        expect(note.attributes('data-gated')).toBe('true')
        expect(note.text()).toBe('This organization submits only enriched components; an un-enriched component without a bucket waits for enrichment first.')
        expect(w.find(tid('truncated')).exists()).toBe(false)
        expect(w.text()).not.toContain('organization admins')
        expect(w.text()).not.toContain('DTrack project')
    })

    it('(e) enrichmentGated false and null on the blocked fixture', async () => {
        const off = (await mountPanel(served({ ...blocked, enrichmentGated: false }))).w
        expect(off.find(tid('enrichment-note')).attributes('data-gated')).toBe('false')
        expect(off.find(tid('enrichment-note')).text()).toBe('Enrichment is not required in this organization; the bucket columns carry the cause.')
        expect(off.find(tid('blocker-0')).findAll('td')[1].text()).toBe('not enriched (not required in this organization)')
        expect(off.find(tid('fact-enrichmentGated')).text()).toBe('enrichment not required in this organization')

        const unknown = (await mountPanel(served({ ...blocked, enrichmentGated: null }))).w
        expect(unknown.find(tid('enrichment-note')).attributes('data-gated')).toBe('unknown')
        expect(unknown.find(tid('enrichment-note')).text()).toBe('Whether this organization requires enrichment could not be determined (rebom did not answer); the reason column is authoritative.')
        expect(unknown.find(tid('blocker-0')).findAll('td')[1].text()).toBe('enrichment state unknown')
        expect(unknown.find(tid('fact-enrichmentGated')).text()).toBe('unknown (rebom did not answer)')
    })

    it('(f) truncated: fifty rows and the truncation line', async () => {
        const { w } = await mountPanel(served(truncatedFx))
        expect(w.findAll('tr[data-testid^="artifact-scan-status-blocker-"]')).toHaveLength(50)
        expect(w.find(tid('truncated')).text()).toBe('showing 50 of 73 blocking components')
    })

    it('(g) scanned: no table, no note, success tag, enrichment not determined', async () => {
        const { w } = await mountPanel(served(scanned))
        expect(w.find(tid('blockers')).exists()).toBe(false)
        expect(w.find(tid('enrichment-note')).exists()).toBe(false)
        expect(w.find(tid('truncated')).exists()).toBe(false)
        expect(w.findComponent(NTag).props('type')).toBe('success')
        expect(w.find(tid('fact-enrichmentGated')).text()).toBe('not determined (asked only when a component blocks)')
        expect(w.text()).not.toContain('rebom did not answer')
    })

    it('(h) Refresh: disabled while pending, one call per click, the loaded panel stays during a refresh', async () => {
        let release!: () => void
        const load = vi.fn(() => new Promise<ScanStatusLoadResult>(resolve => { release = () => resolve({ status: blocked, supported: true }) }))
        const { w } = await mountPanel(load)
        const refresh = () => w.find(`button${tid('refresh')}`)
        expect(refresh().exists()).toBe(true)
        expect(refresh().attributes('disabled')).toBeDefined()
        expect(w.find(tid('loading')).exists()).toBe(true)

        release()
        await flushPromises()
        expect(refresh().attributes('disabled')).toBeUndefined()
        expect(w.find(tid('state')).exists()).toBe(true)

        await refresh().trigger('click')
        await flushPromises()
        expect(load).toHaveBeenCalledTimes(2)
        expect(refresh().attributes('disabled')).toBeDefined()
        expect(w.find(tid('state')).exists()).toBe(true)
        expect(w.find(tid('loading')).exists()).toBe(false)

        await refresh().trigger('click')
        await flushPromises()
        expect(load).toHaveBeenCalledTimes(2)
        release()
        await flushPromises()
        expect(refresh().attributes('disabled')).toBeUndefined()
    })

    it('(i) unsupported: the one line and nothing else, no toast', async () => {
        const { w, notify } = await mountPanel(vi.fn(async () => ({ status: null, supported: false })))
        expect(w.find(tid('unsupported')).text()).toBe('Scan status is not available on this server')
        expect(w.find(tid('state')).exists()).toBe(false)
        expect(w.find(tid('blockers')).exists()).toBe(false)
        expect(notify).not.toHaveBeenCalled()
    })

    it('(j) a refusal: the server text in the toast, the fixed line, Refresh retries', async () => {
        const e: any = new Error('Not authorized')
        e.errors = [{ message: 'Not authorized', extensions: { classification: 'DataFetchingException' } }]
        const load = vi.fn(async (): Promise<ScanStatusLoadResult> => { throw e })
        const { w, notify } = await mountPanel(load)
        expect(notify).toHaveBeenCalledTimes(1)
        expect(notify).toHaveBeenCalledWith('error', 'Error', 'Not authorized')
        expect(w.find(tid('error')).text()).toBe('Scan status could not be loaded.')
        expect(w.find(tid('state')).exists()).toBe(false)
        const refresh = w.find(`button${tid('refresh')}`)
        expect(refresh.attributes('disabled')).toBeUndefined()
        expect(w.html()).not.toContain(UUID)
        expect(w.html()).not.toContain('Artifact not found')
        await refresh.trigger('click')
        await flushPromises()
        expect(load).toHaveBeenCalledTimes(2)
    })

    it('(k) marked not scannable: the marker date and the disagreement line', async () => {
        const { w } = await mountPanel(served(marked))
        expect(w.find(tid('state')).attributes('data-state')).toBe('NO_MATCHABLE_COMPONENTS')
        expect(w.findComponent(NTag).text()).toBe('No scannable components')
        const text = w.find(tid('state-text')).text()
        expect(text).toContain('components carry no purl or cpe: nothing to scan')
        expect(text).toContain('; marked not scannable at ' + formatInstant('2026-10-29T08:00:00Z'))
        expect(text).toContain('; a matchable component appeared since; qualifies for a clearing pass')

        const withoutMarker: ArtifactScanStatus = { ...marked }
        delete withoutMarker.notScannableAt
        const plain = (await mountPanel(served(withoutMarker))).w
        expect(plain.find(tid('state-text')).text()).not.toContain('marked not scannable')
    })

    it('(l) server strings render as text', async () => {
        const hostile: ArtifactScanStatus = {
            ...blocked,
            candidateRule: '<script>z</script>',
            blockers: [{ ...rowA, purl: '<b>x</b>', reason: '<i>y</i>' }, rowB]
        }
        const { w } = await mountPanel(served(hostile))
        expect(w.find('b').exists()).toBe(false)
        expect(w.html()).not.toContain('<script')
        // NIcon renders an <i>, so check that no <i> holds the reason rather than that none exists.
        expect(w.findAll('i').some(i => i.text() === 'y')).toBe(false)
        const row0 = w.find(tid('blocker-0')).text()
        expect(row0).toContain('<b>x</b>')
        expect(row0).toContain('<i>y</i>')
        expect(w.find(tid('candidate')).text()).toContain('<script>z</script>')
    })

    it('(m) a late answer after unmount changes nothing and toasts nothing', async () => {
        let reject!: (e: any) => void
        const load = vi.fn(() => new Promise<ScanStatusLoadResult>((_, rej) => { reject = rej }))
        const { w, notify } = await mountPanel(load)
        w.unmount()
        reject(new Error('late'))
        await flushPromises()
        expect(notify).not.toHaveBeenCalled()
    })

    it('(n) a null status with supported true is the empty line', async () => {
        const { w } = await mountPanel(served(null))
        expect(w.find(tid('empty')).text()).toBe('No scan status was returned for this artifact')
    })

    it('(o) unmapped: the sentence, no canonical, and Refresh the only button', async () => {
        const load = served(unmapped)
        const { w } = await mountPanel(load)
        expect(w.find(tid('state')).attributes('data-state')).toBe('UNMAPPED')
        const tag = w.findComponent(NTag)
        expect(tag.text()).toBe('Not mapped yet')
        expect(tag.props('type')).toBe('warning')
        expect(w.find(tid('state-text')).text()).toBe(UNMAPPED_SENTENCE)
        expect(w.find(tid('canonical-none')).text()).toBe('-')
        expect(w.find(tid('blockers')).exists()).toBe(false)
        expect(w.find(tid('enrichment-note')).exists()).toBe(false)
        const buttons = w.findAll('button')
        expect(buttons).toHaveLength(1)
        expect(buttons[0].text()).toBe('Refresh')
        expect(w.find('a[href]').exists()).toBe(false)
        expect(Object.keys(w.props()).sort()).toEqual(['artifactUuid', 'load', 'notify'])
        expect(load).toHaveBeenCalledTimes(1)
    })
})
