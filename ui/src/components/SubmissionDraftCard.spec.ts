// @vitest-environment happy-dom
//
// The draft card of the package page (task FDAW-32, architecture round 2, 3.6, row 7 (a) to (i)), mounted
// alone: the job line, the stale banner by label, the five buttons shown, disabled and titled as the page
// knows, the parts with a download named by the server, the diff, hostile text as text only, the sweep,
// the still-running line, and the confirmation line by the setting as read. The card never calls the client.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { NNotificationProvider } from 'naive-ui'

const query = vi.fn()
const mutate = vi.fn()
const downloadArtifactFile = vi.fn()
const saveBlob = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
vi.mock('@/utils/fetchClient', () => ({ fetchWithAuth: vi.fn(), fetchArrayBufferWithAuth: vi.fn() }))
vi.mock('@/utils/artifactDownload', async (importOriginal) => ({
    ...(await importOriginal<typeof import('@/utils/artifactDownload')>()),
    downloadArtifactFile: (...a: any[]) => downloadArtifactFile(...a),
    saveBlob: (...a: any[]) => saveBlob(...a),
}))

const { default: Card } = await import('./SubmissionDraftCard.vue')
const { submissionDateText } = await import('@/utils/submissions')
const { expectSwept } = await import('@/utils/__fixtures__/fdaSweep')

const HOSTILE = '<img src=x onerror="window.__hit=1"><b>b</b>'
const U1 = '11111111-1111-4111-8111-111111111111'
const T = '2026-11-13T07:00:00Z'
const NAMES = { componentName: 'Pump', releaseVersion: '1.2.0' }
const D: any = {
    uuid: 'd1', org: 'o1', release: 'r1', component: 'c1', kind: 'FDA_PREMARKET', submission: null, state: 'EDITING', revision: 7,
    plannedVersion: 2, startedAt: '2026-11-13T05:00:00Z', startedBy: { actor: 'u1', actorName: 'Ann' }, reasonCategory: 'CORRECTION',
    reasonText: 'fix', replaces: null, job: { state: 'BUILT', attempt: 1 },
    build: { builtAt: '2026-11-13T06:07:08.123Z', releaseSbom: { version: 3 }, parts: [
        { part: 'AGGREGATE_SBOM', artifact: U1, sha256: 'a'.repeat(64), sizeBytes: 10, documentVersion: 3 },
        { part: 'VDR', artifact: 'not-a-uuid', sha256: null, sizeBytes: null, documentVersion: null }], diff: null },
    confirmation: null, changesSinceBuild: { count: 0, fingerprintEqual: true, movedLines: [] },
    readiness: { ruleSetVersion: '1', rows: [] },
}
const AWAIT = { state: 'AWAITING_CONFIRMATION', confirmation: { actor: 'u1', actorName: 'Ann', capacity: 'AUTHOR',
    confirmedAt: '2026-11-13T06:09:10Z' } }
const CH = { changesSinceBuild: { count: 3, fingerprintEqual: false, movedLines: ['narrative:' + U1] } }
const DIFF = { baseline: { submission: 's0', version: 1 },
    added: [{ identity: 'pkg:npm/a', name: 'a', version: '1.0' }, { identity: 'pkg:npm/' + HOSTILE, name: null, version: '2' }], addedCount: 600,
    removed: [], removedCount: 0, changed: [{ identity: 'pkg:npm/c', name: 'c', fromVersion: '1', toVersion: '2' }], changedCount: 1,
    supportChanged: [{ key: 'pkg:npm/c@2', property: 'reliza:support:level' }], supportChangedCount: 1, findingsCompared: true,
    findingsAdded: [{ vulnerabilityId: 'CVE-2026-0001', affectedRef: 'pkg:npm/c@2' }, { vulnerabilityId: 'CVE-2026-0002', affectedRef: null }],
    findingsAddedCount: 2, findingsRemoved: [], findingsRemovedCount: 3 }
const SUMMARY = 'Compared with version 1: 600 added, 0 removed, 1 with a changed version, 1 support changes, 5 findings changed'

const T16 = 'Rebuild first'
const T27 = 'A rebuild of this draft is in progress; wait for it to finish'
const T28 = 'Return the draft to editing and rebuild; the inputs changed since this build'
const T29 = 'The second confirmation must come from a different person than the first; ask another member to finalize'
const LIFE = 'The release must be Assembled or later'
const TEXT_11 = 'Still running; this page checks again every 30 seconds'
const TEXT_32 = 'The second-confirmer setting could not be read; this page reads it again every 30 seconds'
const IDS = ['draft-rebuild', 'draft-discard', 'draft-confirm', 'draft-return', 'draft-finalize']
const EVENTS: Record<string, string> = { 'draft-rebuild': 'rebuild', 'draft-discard': 'discard', 'draft-confirm': 'confirm',
    'draft-return': 'return', 'draft-finalize': 'finalize' }

const with_ = (...over: any[]) => Object.assign({}, D, ...over)

async function mountCard (props: Record<string, any> = {}) {
    let card: any = null
    const w = mount(defineComponent({
        render: () => h(NNotificationProvider, () => h(Card, { draft: D, secondConfirmer: false, isWritable: true, busy: false,
            stillRunning: false, names: NAMES, currentUser: null, lifecycle: 'ASSEMBLED', head: null, ...props,
            ref: (c: any) => { card = c } })),
    }), { attachTo: document.body })
    await flushPromises()
    return { w, card: () => w.findComponent(Card), cardRef: () => card }
}

const testid = (w: any, id: string) => w.find('[data-testid="' + id + '"]')
const buttonIds = (w: any) => w.findAll('button[data-testid]').map((b: any) => b.attributes('data-testid')).filter((id: string) => IDS.includes(id))
const btn = (w: any, id: string) => testid(w, id).element as HTMLButtonElement

describe('SubmissionDraftCard (FDAW-32 row 7)', () => {
    beforeEach(() => {
        query.mockReset()
        mutate.mockReset()
        downloadArtifactFile.mockReset()
        saveBlob.mockReset()
    })

    it('(a) the job line by state, the failure text as text, and the kept build', async () => {
        const job = async (draft: any) => {
            const { w } = await mountCard({ draft })
            const text = testid(w, 'draft-job').text()
            return { w, text }
        }
        let r = await job(with_({ job: { state: 'NONE', attempt: 0 }, build: null }))
        expect(r.text).toBe('Not built yet')
        r.w.unmount()
        r = await job(with_({ job: { state: 'REQUESTED', requestedAt: T, requestedBy: { actor: 'u1', actorName: 'Ann' }, attempt: 1 } }))
        expect(r.text).toBe('Rebuild requested on ' + submissionDateText(T) + ' by Ann')
        r.w.unmount()
        r = await job(with_({ job: { state: 'RUNNING', startedAt: T, attempt: 2 } }))
        expect(r.text).toBe('Rebuilding since ' + submissionDateText(T) + ' (attempt 2)')
        r.w.unmount()
        r = await job(D)
        expect(r.text).toBe('Built on ' + submissionDateText('2026-11-13T06:07:08.123Z'))
        expect(testid(r.w, 'draft-kept-build').exists()).toBe(false)
        r.w.unmount()
        r = await job(with_({ job: { state: 'FAILED', finishedAt: T, failureText: HOSTILE, attempt: 1 } }))
        expect(r.text).toBe('Rebuild failed on ' + submissionDateText(T))
        const failure = testid(r.w, 'draft-failure')
        expect(failure.text()).toBe(HOSTILE)
        expect(failure.element.children.length).toBe(0)
        expect(testid(r.w, 'draft-kept-build').exists()).toBe(true)
        r.w.unmount()
        r = await job(with_({ job: { state: 'SOMETHING_NEW', attempt: 1 } }))
        expect(r.text).toBe('Something new')
        r.w.unmount()
    })

    it('(b) the stale banner: unchanged, not built, and changed with labels only', async () => {
        let { w } = await mountCard()
        expect(testid(w, 'draft-stale').text()).toBe('Inputs unchanged')
        w.unmount();
        ({ w } = await mountCard({ draft: with_({ build: null, job: { state: 'NONE', attempt: 0 } }) }))
        expect(testid(w, 'draft-stale').text()).toBe('Not built yet')
        w.unmount();
        ({ w } = await mountCard({ draft: with_({ changesSinceBuild: { count: 3, fingerprintEqual: false,
            movedLines: ['narrative:' + U1, 'overlay:' + HOSTILE] } }) }))
        expect(testid(w, 'draft-stale').text()).toContain('3 changes since this build')
        expect(testid(w, 'draft-stale-lines').findAll('li').map((l: any) => l.text()))
            .toEqual(['assessment narrative changed', 'manual or rule overlay changed'])
        expect(testid(w, 'draft-stale').text()).not.toContain(U1)
        w.unmount()
    })

    it('(c) the buttons: shown, disabled and titled as the page knows; clicks emit once, a disabled one nothing', async () => {
        const ids = async (props: any) => {
            const { w } = await mountCard(props)
            const out = buttonIds(w)
            w.unmount()
            return out
        }
        expect(await ids({ secondConfirmer: false })).toEqual(['draft-rebuild', 'draft-discard', 'draft-finalize'])
        expect(await ids({ secondConfirmer: null })).toEqual(['draft-rebuild', 'draft-discard', 'draft-finalize'])
        expect(await ids({ secondConfirmer: true })).toEqual(['draft-rebuild', 'draft-discard', 'draft-confirm'])
        expect(await ids({ draft: with_(AWAIT) })).toEqual(['draft-return', 'draft-finalize'])
        expect(await ids({ isWritable: false })).toEqual([])

        const state = async (props: any) => {
            const { w } = await mountCard(props)
            const out: Record<string, [boolean, string]> = {}
            for (const id of buttonIds(w)) out[id] = [btn(w, id).disabled, btn(w, id).getAttribute('title') ?? '']
            w.unmount()
            return out
        }
        // The control of what follows: every button of D enabled with an empty title.
        expect(await state({})).toEqual({ 'draft-rebuild': [false, ''], 'draft-discard': [false, ''], 'draft-finalize': [false, ''] })
        expect(await state({ draft: with_({ job: { state: 'RUNNING', attempt: 1 } }) }))
            .toEqual({ 'draft-rebuild': [false, ''], 'draft-discard': [false, ''], 'draft-finalize': [true, T27] })
        expect(await state({ draft: with_({ job: { state: 'REQUESTED', attempt: 1 } }) }))
            .toEqual({ 'draft-rebuild': [false, ''], 'draft-discard': [true, T27], 'draft-finalize': [true, T27] })
        expect((await state({ draft: with_(CH) }))['draft-finalize']).toEqual([true, T16])
        expect((await state({ draft: with_(CH), secondConfirmer: true }))['draft-confirm']).toEqual([true, T16])
        expect(await state({ draft: with_(AWAIT, CH) })).toEqual({ 'draft-return': [false, ''], 'draft-finalize': [true, T28] })
        expect((await state({ draft: with_(AWAIT), secondConfirmer: true, currentUser: 'u1' }))['draft-finalize']).toEqual([true, T29])
        expect((await state({ draft: with_(AWAIT), secondConfirmer: true, currentUser: 'u2' }))['draft-finalize']).toEqual([false, ''])
        expect((await state({ lifecycle: 'DRAFT' }))['draft-finalize']).toEqual([true, LIFE])
        const busy = await state({ busy: true })
        expect(Object.keys(busy)).toEqual(['draft-rebuild', 'draft-discard', 'draft-finalize'])
        for (const v of Object.values(busy)) expect(v[0]).toBe(true)

        // A click on each enabled button emits its event once.
        for (const props of [{}, { secondConfirmer: true }, { draft: with_(AWAIT) }]) {
            const { w, card } = await mountCard(props)
            for (const id of buttonIds(w)) {
                await testid(w, id).trigger('click')
                expect(card().emitted(EVENTS[id]), id).toHaveLength(1)
            }
            w.unmount()
        }
        // A disabled one emits nothing.
        const { w, card } = await mountCard({ draft: with_({ job: { state: 'REQUESTED', attempt: 1 } }) })
        await testid(w, 'draft-discard').trigger('click')
        await testid(w, 'draft-finalize').trigger('click')
        expect(card().emitted('discard')).toBeUndefined()
        expect(card().emitted('finalize')).toBeUndefined()
        w.unmount()
        expect(query).not.toHaveBeenCalled()
        expect(mutate).not.toHaveBeenCalled()
    })

    it('(d) the parts, a download under the server file name or the fallback, and the pin', async () => {
        const { w } = await mountCard()
        const rows = testid(w, 'draft-parts').findAll('tbody tr')
        expect(rows).toHaveLength(2)
        const cells = rows[0].findAll('td').map((c: any) => c.text())
        expect(cells.slice(0, 3)).toEqual(['Aggregate SBOM', '10', 'aaaaaaaaaaaa'])
        expect(rows[0].find('[data-testid="draft-part-download"]').exists()).toBe(true)
        expect(rows[1].find('[data-testid="draft-part-download"]').exists()).toBe(false)
        expect(testid(w, 'draft-pin').text()).toBe('Release SBOM version 3')

        const B = new ArrayBuffer(4)
        downloadArtifactFile.mockResolvedValue({ bytes: B, disposition: 'attachment; filename="widget-sbom.cdx.json"', mediaType: 'application/json' })
        await rows[0].find('[data-testid="draft-part-download"]').trigger('click')
        await flushPromises()
        expect(downloadArtifactFile.mock.calls).toEqual([['/api/manual/v1/artifact/' + U1 + '/rawdownload']])
        expect(saveBlob.mock.calls).toEqual([[B, 'widget-sbom.cdx.json', 'application/json']])

        saveBlob.mockReset()
        downloadArtifactFile.mockResolvedValue({ bytes: B, disposition: null, mediaType: 'application/json' })
        await rows[0].find('[data-testid="draft-part-download"]').trigger('click')
        await flushPromises()
        expect(saveBlob.mock.calls[0][1]).toBe('draft-v2-aggregate-sbom')
        expect(query).not.toHaveBeenCalled()
        expect(mutate).not.toHaveBeenCalled()
        w.unmount()
    })

    it('(e) the diff: summary, the sections with a count, rows and "and n more"; text 10 without a diff', async () => {
        let { w } = await mountCard({ draft: with_({ build: { ...D.build, diff: DIFF } }) })
        expect(testid(w, 'draft-diff').text()).toContain(SUMMARY)
        for (const k of ['added', 'changed', 'support', 'findings-added', 'findings-removed']) {
            expect(testid(w, 'draft-diff-' + k).exists(), k).toBe(true)
        }
        expect(testid(w, 'draft-diff-removed').exists()).toBe(false)
        expect(testid(w, 'draft-diff-added').findAll('.draft-diff-line').map((l: any) => l.text()))
            .toEqual(['a 1.0', 'pkg:npm/' + HOSTILE + ' 2', 'and 598 more'])
        w.unmount();
        ({ w } = await mountCard())
        expect(testid(w, 'draft-diff').text()).toContain('No earlier version to compare with')
        w.unmount()
    })

    it('(f) hostile text is text: no element built, the text present', async () => {
        const { w } = await mountCard({ draft: with_({ startedBy: { actor: 'u1', actorName: HOSTILE }, reasonText: HOSTILE,
            job: { state: 'FAILED', finishedAt: T, failureText: HOSTILE, attempt: 1 },
            confirmation: { actor: 'u1', actorName: HOSTILE, capacity: 'AUTHOR', confirmedAt: '2026-11-13T06:09:10Z' },
            build: { ...D.build, diff: DIFF } }) })
        const root = testid(w, 'draft-card')
        expect(root.find('img').exists()).toBe(false)
        expect(root.find('b').exists()).toBe(false)
        expect(root.find('[onerror]').exists()).toBe(false)
        expect(root.text().split(HOSTILE).length - 1).toBeGreaterThanOrEqual(5)
        w.unmount()
    })

    it('(g) sweep: the card of a changed draft names what moved in words', async () => {
        const { w } = await mountCard({ draft: with_({ reasonCategory: 'FDA_REQUEST_RESPONSE', changesSinceBuild: { count: 3,
            fingerprintEqual: false, movedLines: ['artifact:' + U1 + ':' + U1, 'disclosure:' + U1, 'support:pkg:npm/x@1'] } }) })
        const text = testid(w, 'draft-card').text()
        expectSwept(text)
        expect(text).toContain('Response to an FDA request')
        expect(text).toContain('support disclosure setting changed')
        w.unmount()
    })

    it('(h) still running: text 11 and Refresh, which emits refresh', async () => {
        let { w, card } = await mountCard({ stillRunning: true })
        expect(testid(w, 'draft-still-running').text()).toContain(TEXT_11)
        await testid(w, 'draft-refresh').trigger('click')
        expect(card().emitted('refresh')).toHaveLength(1)
        w.unmount();
        ({ w } = await mountCard({ stillRunning: false }))
        expect(testid(w, 'draft-still-running').exists()).toBe(false)
        expect(testid(w, 'draft-refresh').exists()).toBe(false)
        w.unmount()
    })

    it('(i) the confirmation line by the setting; the not-read line for a writer only', async () => {
        const lead = 'Confirmed by Ann on 2026-11-13 06:09 UTC'
        const expected: [boolean | null, string][] = [
            [true, lead + '; a different person finalizes'],
            [false, lead + '. The organization no longer requires a second confirmer: this confirmation will not be recorded, '
                + 'and anyone who may write can finalize'],
            [null, lead],
        ]
        for (const [s, line] of expected) {
            const { w } = await mountCard({ draft: with_(AWAIT), secondConfirmer: s })
            expect(testid(w, 'draft-confirmation').text()).toBe(line)
            w.unmount()
        }
        let { w } = await mountCard()
        expect(testid(w, 'draft-confirmation').exists()).toBe(false)
        w.unmount();
        ({ w } = await mountCard({ secondConfirmer: null }))
        expect(testid(w, 'draft-setting-unread').text()).toBe(TEXT_32)
        w.unmount()
        for (const s of [true, false]) {
            ({ w } = await mountCard({ secondConfirmer: s }))
            expect(testid(w, 'draft-setting-unread').exists()).toBe(false)
            w.unmount()
        }
        ({ w } = await mountCard({ secondConfirmer: null, isWritable: false }))
        expect(testid(w, 'draft-setting-unread').exists()).toBe(false)
        w.unmount()
    })
})

// FDAW-16 architecture round 3, section 4 row 14: the readiness block of the card (3.8), its verbs by the two
// facts of F-30, text 13 under the rows, and texts 9 and 16 for a build without readiness.
describe('SubmissionDraftCard: readiness (FDAW-16 row 14)', () => {
    const TEXT_13 = 'These items are read from this build. Fix them, then rebuild the draft. An item without a button, or an entry a '
        + 'button does not list, is corrected in the SBOM uploaded to the release, or to the component release that carries the entry.'
    const TEXT_9 = 'Readiness was not checked for this build; rebuild the draft'
    const TEXT_16 = 'Readiness was not checked for this build; return the draft to editing and rebuild it'
    const ORG_SENTENCE = 'Add suppliers changes organization-wide data and needs organization write access, which an'
        + ' organization administrator grants in Organization Settings > Users'
    const SUPPLIER = { rule: 'SBOM_SUPPLIER', severity: 'MUST_FIX', outcome: 'FAIL', count: 5, justified: null,
        text: 'Supplier name: 5 of 10 not met', verb: 'ADD_SUPPLIERS' }
    const DEVICE = { rule: 'DEVICE_DATES', severity: 'REVIEW', outcome: 'FAIL', count: 1, justified: null,
        text: 'Device end-of-support and end-of-life dates are not both declared', verb: null }
    const PASSED = { rule: 'SBOM_AUTHOR', severity: 'MUST_FIX', outcome: 'PASS', count: 0, justified: null, text: 'SBOM author: Pass', verb: null }
    const THREE = with_({ readiness: { ruleSetVersion: '1', rows: [SUPPLIER, DEVICE, PASSED] } })
    const block = (w: any) => testid(w, 'draft-readiness')
    const rowTexts = (w: any) => block(w).findAll('[data-testid="draft-readiness-text"]').map((e: any) => e.text())
    const verbs = (w: any) => block(w).findAll('button')

    it('a product-scoped writer: three rows, one button, disabled, with the sentence, and text 13', async () => {
        const { w } = await mountCard({ draft: THREE, isWritable: true, canWriteOrg: false })
        expect(rowTexts(w)).toEqual([SUPPLIER.text, DEVICE.text, PASSED.text])
        expect(block(w).findAll('[data-testid="draft-readiness-tag"]').map((e: any) => e.text())).toEqual(['Must fix', 'Review'])
        expect(verbs(w).length).toBe(1)
        expect(verbs(w)[0].text()).toBe('Add suppliers')
        expect((verbs(w)[0].element as HTMLButtonElement).disabled).toBe(true)
        expect(verbs(w).filter((b: any) => !(b.element as HTMLButtonElement).disabled).length).toBe(0)
        expect(block(w).find('[data-testid="draft-readiness-org-write"]').text()).toBe(ORG_SENTENCE)
        expect(testid(w, 'draft-readiness-fix').text()).toBe(TEXT_13)
        await verbs(w)[0].trigger('click')
        expect(w.findComponent(Card).emitted('fix')).toBeUndefined()
        w.unmount()
    })
    it('an organization writer: the button is enabled, no sentence, and a click emits fix with the verb', async () => {
        const { w, card } = await mountCard({ draft: THREE, isWritable: true, canWriteOrg: true })
        expect(verbs(w).length).toBe(1)
        expect((verbs(w)[0].element as HTMLButtonElement).disabled).toBe(false)
        expect(block(w).find('[data-testid="draft-readiness-org-write"]').exists()).toBe(false)
        await verbs(w)[0].trigger('click')
        expect(card().emitted('fix')).toEqual([['ADD_SUPPLIERS']])
        w.unmount()
    })
    it('a reader: three rows and no button', async () => {
        const { w } = await mountCard({ draft: THREE, isWritable: false, canWriteOrg: false })
        expect(rowTexts(w)).toEqual([SUPPLIER.text, DEVICE.text, PASSED.text])
        expect(verbs(w).length).toBe(0)
        w.unmount()
    })
    it('only a REVIEW row failing: no text 13', async () => {
        const { w } = await mountCard({ draft: with_({ readiness: { ruleSetVersion: '1', rows: [DEVICE, PASSED] } }), canWriteOrg: true })
        expect(rowTexts(w)).toEqual([DEVICE.text, PASSED.text])
        expect(testid(w, 'draft-readiness-fix').exists()).toBe(false)
        w.unmount()
    })
    it('a build without readiness: text 9 and no row; awaiting confirmation: text 16', async () => {
        let { w } = await mountCard({ draft: with_({ readiness: null }) })
        expect(testid(w, 'draft-readiness-unchecked').text()).toBe(TEXT_9)
        expect(block(w).findAll('[data-testid="draft-readiness-row"]').length).toBe(0)
        w.unmount();
        ({ w } = await mountCard({ draft: with_(AWAIT, { readiness: null }) }))
        expect(testid(w, 'draft-readiness-unchecked').text()).toBe(TEXT_16)
        expect(block(w).findAll('[data-testid="draft-readiness-row"]').length).toBe(0)
        w.unmount()
    })
    it('the block sits after the stale banner and before the diff; no build, no block', async () => {
        let { w } = await mountCard({ draft: THREE })
        const order = w.findAll('[data-testid]').map((e: any) => e.attributes('data-testid'))
            .filter((id: string) => ['draft-stale', 'draft-readiness', 'draft-diff', 'draft-confirm', 'draft-finalize'].includes(id))
        expect(order).toEqual(['draft-stale', 'draft-readiness', 'draft-diff', 'draft-finalize'])
        w.unmount();
        ({ w } = await mountCard({ draft: with_({ job: { state: 'NONE' }, build: null, readiness: null }) }))
        expect(testid(w, 'draft-readiness').exists()).toBe(false)
        w.unmount()
    })
})
