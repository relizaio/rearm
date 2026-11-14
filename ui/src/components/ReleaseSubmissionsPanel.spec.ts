// @vitest-environment happy-dom
//
// The release page's Regulatory submissions section (task TEA-30, design 4.6 row 33): one head of two
// versions and a cancelled second kind; the head lines, the version history newest first with the
// reason as text kept in its line breaks, the parts of the newest version with a download per
// generated part through the one URL builder, none for a skipped part or one with no version
// recorded, the manifest download and text, and the cancelled kind's reason and actions. With the
// host's kinds (Release.regulatoryPackageKinds) the rows are those kinds in their order; the catalog
// is read for its parts, and a failed read of it loses nothing but the narrative prompt. The
// assessment-narrative prompt before Create and Resubmit of a kind with a REQUIRED NARRATIVE part:
// its preselection, the organization option without a default, the write it sends (set, clear or
// none) before the act, and no act when the write fails.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { NNotificationProvider, NSelect } from 'naive-ui'

const query = vi.fn()
const mutate = vi.fn()
const fire = vi.fn()
const downloadArtifactBytes = vi.fn(async () => new ArrayBuffer(4))
const saveBlob = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
vi.mock('sweetalert2', () => ({ default: { fire: (...a: any[]) => fire(...a) } }))
vi.mock('@/utils/fetchClient', () => ({ fetchWithAuth: vi.fn(), fetchArrayBufferWithAuth: vi.fn() }))
vi.mock('@/utils/artifactDownload', async (importOriginal) => ({
    ...(await importOriginal<typeof import('@/utils/artifactDownload')>()),
    downloadArtifactBytes: (...a: any[]) => (downloadArtifactBytes as any)(...a),
    saveBlob: (...a: any[]) => saveBlob(...a),
}))

const { default: Panel } = await import('./ReleaseSubmissionsPanel.vue')
const { artifactDownloadUrl } = await import('@/utils/artifactDownload')
const { expectSwept } = await import('@/utils/__fixtures__/fdaSweep')

const A1 = '11111111-1111-4111-8111-111111111111'
const A2 = '22222222-2222-4222-8222-222222222222'
const M2 = '33333333-3333-4333-8333-333333333333'
const operation = (opts: any) => (opts.query ?? opts.mutation)?.definitions?.[0]?.name?.value

const v = (n: number, changes: Record<string, any> = {}) => ({
    submission: 's1', version: n, kind: 'FDA_PREMARKET', createdDate: '2026-10-0' + n + 'T00:00:00Z', createdBy: 'u1', reason: null,
    settings: { row: null, revision: 0, source: 'DEFAULT' }, sbomProfile: { name: 'default', row: null, revision: 0, source: 'DEFAULT' },
    manifestArtifact: M2, manifestArtifactVersion: '1', manifestSha256: 'c'.repeat(64), manifest: '{"reason":"<script>alert(1)</script>"}',
    parts: [], ...changes,
})
const V2 = v(2, { reason: 'Updated <b>SBOM</b>\nline two', parts: [
    { part: 'AGGREGATE_SBOM', requirement: 'REQUIRED', outcome: 'PRODUCED', artifact: A1, artifactVersion: '1',
        fileName: 'widget-sbom.cdx.json', mediaType: 'application/json', size: 10, sha256: 'a'.repeat(64) },
    { part: 'VEX', requirement: 'OPTIONAL', outcome: 'SKIPPED', skipReason: 'EXCLUDED_BY_SETTINGS', detail: null },
    { part: 'NARRATIVE', requirement: 'REQUIRED', outcome: 'PRODUCED', artifact: A2, artifactVersion: null,
        fileName: 'narrative.md', mediaType: 'text/markdown', size: 5, sha256: 'b'.repeat(64) },
] })
const HEADS = [
    { uuid: 's1', release: 'r1', kind: 'FDA_PREMARKET', state: 'FINAL', latestVersion: 2, lastAct: { kind: 'FINALIZE', actedAt: '2026-10-02T00:00:00Z', actorName: 'Ann' },
        lastActDate: '2026-10-02T00:00:00Z', lastActBy: 'u1', versions: [v(1), V2] },
    { uuid: 's2', release: 'r1', kind: 'TEST_KIND', state: 'CANCELLED', latestVersion: 1, lastAct: { kind: 'CANCEL', actedAt: '2026-10-03T00:00:00Z', actorName: 'Ann' },
        lastActDate: '2026-10-03T00:00:00Z', lastActBy: 'u1', legacyState: null,
        acts: [
            { uuid: 'a1', kind: 'FINALIZE', cause: 'USER', version: 1, actedAt: '2026-10-01T00:00:00Z', actorName: 'Ann', reasonText: 'first' },
            { uuid: 'a2', kind: 'CANCEL', cause: 'USER', version: 1, actedAt: '2026-10-02T00:00:00Z', actorName: 'Ann', reasonText: 'older' },
            { uuid: 'a3', kind: 'FINALIZE', cause: 'USER', version: 1, actedAt: '2026-10-02T12:00:00Z', actorName: 'Ann', reasonText: 'again' },
            { uuid: 'a4', kind: 'CANCEL', cause: 'USER', version: 1, actedAt: '2026-10-03T00:00:00Z', actorName: 'Bob <b>', reasonText: 'Recalled <i>why</i>' },
        ],
        versions: [v(1, { submission: 's2', kind: 'TEST_KIND' })] },
]

// FDAW-32 3.11: every mount reads the drafts and the second-confirmer setting, and the Start form the
// component's heads; a cell that needs other answers installs them before this helper's line.
function draftReads (opts: any): any {
    switch (operation(opts)) {
    case 'ReleaseDrafts': return { data: { release: { uuid: 'r1', drafts: [] } } }
    case 'OrgSecondConfirmer': return { data: { organizations: [{ uuid: 'o1', settings: { submissionSecondConfirmer: false } }] } }
    case 'SubmissionsOfComponent': return { data: { submissionsOfComponent: [] } }
    default: return undefined
    }
}

function answers () {
    query.mockImplementation(async (opts: any) => {
        const d = draftReads(opts); if (d) return d
        switch (operation(opts)) {
        case 'PackageKinds': return { data: { packageKinds: [{ kind: 'FDA_PREMARKET', parts: [] }, { kind: 'TEST_KIND', parts: [] }] } }
        case 'SubmissionsOfRelease': return { data: { submissionsOfRelease: HEADS } }
        default: throw new Error('unexpected query ' + operation(opts))
        }
    })
}

// The modal renders in place, its footer after its body.
const ModalStub = { props: ['show'], template: '<div v-if="show" data-testid="modal-stub"><slot/><slot name="footer"/></div>' }

async function mountPanel (props: Record<string, any> = {}) {
    const w = mount(defineComponent({
        render: () => h(NNotificationProvider, () => h(Panel, { orgUuid: 'o1', releaseUuid: 'r1', releaseVersion: '1.0.0',
            lifecycle: 'ASSEMBLED', componentUuid: 'c1', componentName: 'widget', isWritable: true, installationType: 'SAAS', ...props })),
    }), { attachTo: document.body, global: { stubs: { Modal: ModalStub } } })
    await flushPromises()
    return w
}

describe('ReleaseSubmissionsPanel', () => {
    beforeEach(() => {
        query.mockReset()
        mutate.mockReset()
        fire.mockReset()
        downloadArtifactBytes.mockClear()
        saveBlob.mockClear()
        answers()
    })

    it('reads the release submissions behind the gate and shows the head lines', async () => {
        const w = await mountPanel()
        expect(query.mock.calls.find(c => operation(c[0]) === 'SubmissionsOfRelease')![0].variables).toEqual({ release: 'r1' })
        const head = w.find('[data-testid="submission-head-FDA_PREMARKET"]')
        expect(head.text()).toContain('FDA premarket')
        expect(head.find('[data-testid="submission-state"]').text()).toBe('Final (no confirmation record) - not sent')
        expect(head.text()).toContain('v2')
        expect(head.text()).toContain('Finalized on')
        expect(head.find('[data-testid="draft-start"]').exists()).toBe(true)
        expect(head.find('[data-testid="submission-cancel"]').exists()).toBe(true)
        w.unmount()
    })

    it('lists the versions newest first, the reason as text with its line breaks kept', async () => {
        const w = await mountPanel()
        const table = w.find('[data-testid="submission-versions-FDA_PREMARKET"]')
        const rows = table.findAll('tbody tr.n-data-table-tr:not(.n-data-table-tr--expanded-content)')
            .filter((r: any) => /^v\d/.test(r.findAll('td')[1]?.text() ?? ''))
        expect(rows.map((r: any) => r.findAll('td')[1].text())).toEqual(['v2', 'v1'])
        const reason = table.find('[data-testid="submission-reason"]')
        expect(reason.text()).toContain('Updated <b>SBOM</b>')
        expect(reason.html()).toContain('&lt;b&gt;')
        expect(reason.find('b').exists()).toBe(false)
        expect(reason.attributes('style')).toContain('white-space: pre-wrap')
        w.unmount()
    })

    it('the newest version v2 is in the open: a download per generated part with a version, none for SKIPPED or no version recorded', async () => {
        const w = await mountPanel()
        const parts = w.find('[data-testid="submission-latest-FDA_PREMARKET"] [data-testid="submission-parts-FDA_PREMARKET-2"]')
        expect(parts.exists()).toBe(true)
        const buttons = parts.findAll('[data-testid="submission-download"]')
        expect(buttons).toHaveLength(1)
        expect(buttons[0].attributes('aria-label')).toBe('Download widget-sbom.cdx.json')
        expect(parts.findAll('[data-testid="submission-not-downloadable"]')).toHaveLength(1)
        expect(parts.find('[data-testid="submission-not-downloadable"]').attributes('title'))
            .toBe('not downloadable: no artifact version recorded')
        expect(parts.text()).toContain('Skipped: excluded by settings')
        await buttons[0].trigger('click')
        await flushPromises()
        expect(downloadArtifactBytes).toHaveBeenCalledWith(artifactDownloadUrl(A1, true, 1))
        expect(saveBlob.mock.calls[0][1]).toBe('widget-sbom.cdx.json')
        w.unmount()
    })

    // FDAW-4 (B13, T2): the history keeps its chevrons; expanding v1 still shows v1's parts.
    it('expanding v1 in the history shows v1\'s parts', async () => {
        const w = await mountPanel()
        expect(w.find('[data-testid="submission-parts-FDA_PREMARKET-1"]').exists()).toBe(false)
        const table = w.find('[data-testid="submission-versions-FDA_PREMARKET"]')
        const v1 = table.findAll('tbody tr').find((r: any) => r.findAll('td')[1]?.text() === 'v1')!
        await v1.find('.n-data-table-expand-trigger').trigger('click')
        await flushPromises()
        expect(w.find('[data-testid="submission-parts-FDA_PREMARKET-1"]').exists()).toBe(true)
        w.unmount()
    })

    it('downloads the manifest under the D7 name and shows its text as text', async () => {
        const w = await mountPanel()
        const download = w.findAll('[data-testid="submission-manifest-download"]')[0]
        await download.trigger('click')
        await flushPromises()
        expect(downloadArtifactBytes).toHaveBeenCalledWith(artifactDownloadUrl(M2, true, 1))
        expect(saveBlob.mock.calls[0][1]).toBe('submission-s1-v2-manifest.json')
        expect(w.find('[data-testid="submission-manifest"]').exists()).toBe(false)
        await w.findAll('[data-testid="submission-manifest-show"]')[0].trigger('click')
        await flushPromises()
        const pre = w.find('[data-testid="submission-manifest"]')
        expect(pre.text()).toContain('<script>alert(1)</script>')
        expect(pre.find('script').exists()).toBe(false)
        expect(pre.element.tagName).toBe('PRE')
        w.unmount()
    })

    it('shows the cancelled kind with its newest CANCEL act as text, offering Start draft and not Cancel', async () => {
        const w = await mountPanel()
        const head = w.find('[data-testid="submission-head-TEST_KIND"]')
        expect(head.find('[data-testid="submission-state"]').text()).toBe('Cancelled')
        expect(head.find('.submission-withdrawn').text())
            .toContain('Cancelled on ' + new Date('2026-10-03T00:00:00Z').toLocaleString('en-CA') + ' by Bob <b>:')
        expect(head.find('.submission-withdrawn').find('b').exists()).toBe(false)
        expect(head.text()).not.toContain('older')
        const reason = head.find('[data-testid="submission-withdraw-reason"]')
        expect(reason.text()).toBe('Recalled <i>why</i>')
        expect(reason.find('i').exists()).toBe(false)
        expect(reason.attributes('style')).toContain('white-space: pre-wrap')
        expect(head.find('[data-testid="draft-start"]').exists()).toBe(true)
        expect(head.find('[data-testid="submission-cancel"]').exists()).toBe(false)
        w.unmount()
    })

    it('offers Cancel on the FINAL head', async () => {
        const w = await mountPanel()
        const button = w.find('[data-testid="submission-head-FDA_PREMARKET"]').find('[data-testid="submission-cancel"]')
        expect(button.text()).toBe('Cancel')
        w.unmount()
    })

    it('a legacy cancelled head reads its withdrawal triple and says it was migrated', async () => {
        const legacy = { uuid: 's3', release: 'r1', kind: 'TEST_KIND', state: 'CANCELLED', latestVersion: 1,
            lastAct: { kind: 'CANCEL', actedAt: '2026-09-03T00:00:00Z', actorName: 'Ann' }, lastActDate: '2026-09-03T00:00:00Z', lastActBy: 'u1', legacyState: 'WITHDRAWN',
            withdrawnDate: '2026-09-03T00:00:00Z', withdrawReason: 'Old <i>reason</i>', acts: [],
            versions: [v(1, { submission: 's3', kind: 'TEST_KIND' })] }
        query.mockImplementation(async (opts: any) => {
            const d = draftReads(opts); if (d) return d
            switch (operation(opts)) {
            case 'PackageKinds': return { data: { packageKinds: [{ kind: 'TEST_KIND', parts: [] }] } }
            case 'SubmissionsOfRelease': return { data: { submissionsOfRelease: [legacy] } }
            default: throw new Error('unexpected query ' + operation(opts))
            }
        })
        const w = await mountPanel()
        const head = w.find('[data-testid="submission-head-TEST_KIND"]')
        const date = new Date('2026-09-03T00:00:00Z').toLocaleString('en-CA')
        expect(head.find('[data-testid="submission-state"]').text()).toBe('Cancelled (legacy: withdrawn ' + date + ')')
        expect(head.find('.submission-withdrawn').text()).toContain('Withdrawn on ' + date + ':')
        expect(head.find('[data-testid="submission-withdraw-reason"]').text()).toBe('Old <i>reason</i>')
        expect(head.find('[data-testid="submission-cancel"]').exists()).toBe(false)
        w.unmount()
    })

    it('a reader sees no action; an empty release says so', async () => {
        const reader = await mountPanel({ isWritable: false })
        for (const id of ['draft-start', 'submission-cancel']) {
            expect(reader.find(`[data-testid="${id}"]`).exists(), id).toBe(false)
        }
        reader.unmount()
        query.mockImplementation(async (opts: any) => draftReads(opts) ?? (operation(opts) === 'PackageKinds'
            ? { data: { packageKinds: [{ kind: 'FDA_PREMARKET', parts: [] }] } }
            : { data: { submissionsOfRelease: [] } }))
        const empty = await mountPanel({ lifecycle: 'DRAFT' })
        expect(empty.find('[data-testid="submissions-empty"]').text()).toBe('No submission of this release yet')
        const create = empty.find('[data-testid="draft-start"]')
        expect(create.attributes('disabled')).toBeDefined()
        expect(create.attributes('title')).toBe('The release must be Assembled or later')
        empty.unmount()
    })

    it('renders one row per kind the host passes, in that order, a failed catalog read changing nothing', async () => {
        query.mockImplementation(async (opts: any) => {
            const d = draftReads(opts); if (d) return d
            if (operation(opts) === 'SubmissionsOfRelease') return { data: { submissionsOfRelease: [] } }
            throw new Error('unexpected query ' + operation(opts))
        })
        const w = await mountPanel({ kinds: ['TEST_KIND', 'FDA_PREMARKET'] })
        const rows = w.findAll('.submission-kind').map((r: any) => r.attributes('data-testid'))
        expect(rows).toEqual(['submission-kind-TEST_KIND', 'submission-kind-FDA_PREMARKET'])
        expect(w.find('[data-testid="submissions-load-error"]').exists()).toBe(false)
        w.unmount()

        // A kind switched off for the component is not passed and gets no row.
        const one = await mountPanel({ kinds: ['FDA_PREMARKET'] })
        expect(one.findAll('.submission-kind').map((r: any) => r.attributes('data-testid'))).toEqual(['submission-kind-FDA_PREMARKET'])
        expect(one.find('[data-testid="submission-kind-TEST_KIND"]').exists()).toBe(false)
        one.unmount()
    })

    it('keeps a kind the release holds a submission of after the passed kinds; the catalog is read for its parts only', async () => {
        const w = await mountPanel({ kinds: ['FDA_PREMARKET'] })
        expect(w.findAll('.submission-kind').map((r: any) => r.attributes('data-testid')))
            .toEqual(['submission-kind-FDA_PREMARKET', 'submission-kind-TEST_KIND'])
        expect(query.mock.calls.some(c => operation(c[0]) === 'PackageKinds')).toBe(true)
        w.unmount()
    })

    it('without kinds from the host, reads the catalog for its rows', async () => {
        const w = await mountPanel()
        expect(query.mock.calls.some(c => operation(c[0]) === 'PackageKinds')).toBe(true)
        expect(w.findAll('.submission-kind')).toHaveLength(2)
        w.unmount()
    })

    describe('the assessment-narrative prompt', () => {
        const NARRATIVE_KINDS = [
            { kind: 'FDA_PREMARKET', parts: [{ part: 'AGGREGATE_SBOM', requirement: 'REQUIRED' }, { part: 'NARRATIVE', requirement: 'REQUIRED' }] },
            { kind: 'TEST_KIND', parts: [{ part: 'AGGREGATE_SBOM', requirement: 'REQUIRED' }, { part: 'NARRATIVE', requirement: 'OPTIONAL' }] },
        ]
        const ACTIVE = { uuid: 's1', release: 'r1', kind: 'FDA_PREMARKET', state: 'FINAL', latestVersion: 2, lastAct: { kind: 'FINALIZE', actedAt: '2026-10-02T00:00:00Z', actorName: 'Ann' },
            lastActDate: '2026-10-02T00:00:00Z', lastActBy: 'u1', versions: [v(1), v(2)] }

        function narrativeAnswers (heads: any[] = []) {
            query.mockImplementation(async (opts: any) => {
                const d = draftReads(opts); if (d) return d
                switch (operation(opts)) {
                case 'PackageKinds': return { data: { packageKinds: NARRATIVE_KINDS } }
                case 'SubmissionsOfRelease': return { data: { submissionsOfRelease: heads } }
                default: throw new Error('unexpected query ' + operation(opts))
                }
            })
            mutate.mockImplementation(async (opts: any) => {
                switch (operation(opts)) {
                case 'updateReleaseNarrative':
                    return { data: { updateRelease: { uuid: 'r1', fdaAssessmentNarrative: opts.variables.release.fdaAssessmentNarrative || null } } }
                default: throw new Error('unexpected mutation ' + operation(opts))
                }
            })
            fire.mockResolvedValue({ isConfirmed: true, value: 'Updated narrative' })
        }

        const mutations = () => mutate.mock.calls.map(c => operation(c[0]))
        const ops = () => [...query.mock.calls.map(c => operation(c[0])), ...mutations()]

        async function openCreate (props: Record<string, any> = {}) {
            const w = await mountPanel({ kinds: ['FDA_PREMARKET'], ...props })
            await w.find('[data-testid="draft-start"]').trigger('click')
            await flushPromises()
            return w
        }

        beforeEach(() => narrativeAnswers())

        it('Create of a kind with a REQUIRED NARRATIVE part opens the prompt first, nothing sent yet', async () => {
            const w = await openCreate({ orgNarrative: 'Org text' })
            expect(w.find('[data-testid="narrative-form"]').exists()).toBe(true)
            expect(w.find('[data-testid="narrative-choice"]').exists()).toBe(false)
            expect(w.find('[data-testid="narrative-text"]').exists()).toBe(true)
            expect(ops()).not.toContain('SubmissionsOfComponent')
            expect(w.find('[data-testid="draft-start-form"]').exists()).toBe(false)
            expect(mutate).not.toHaveBeenCalled()
            w.unmount()
        })

        it('a kind whose NARRATIVE part is not REQUIRED gets no prompt: Create goes straight to the preview', async () => {
            const w = await mountPanel({ kinds: ['TEST_KIND'], orgNarrative: 'Org text' })
            await w.find('[data-testid="draft-start"]').trigger('click')
            await flushPromises()
            expect(w.find('[data-testid="narrative-form"]').exists()).toBe(false)
            expect(w.find('[data-testid="draft-start-form"]').exists()).toBe(true)
            expect(mutations()).toEqual([])
            w.unmount()
        })

        it('preselects the override when the release has one, the text pre-filled with it', async () => {
            const w = await openCreate({ narrativeOverride: 'Release text', orgNarrative: 'Org text' })
            expect((w.find('[data-testid="narrative-text"] textarea').element as HTMLTextAreaElement).value).toBe('Release text')
            w.unmount()
        })

        it('starts from the organization default when there is no override, and cannot continue on it unchanged', async () => {
            const w = await openCreate({ narrativeOverride: '  ', orgNarrative: 'Org text' })
            const org = w.find('[data-testid="narrative-org-default"] textarea')
            expect((org.element as HTMLTextAreaElement).value).toBe('Org text')
            expect(org.attributes('readonly')).toBeDefined()
            expect((w.find('[data-testid="narrative-text"] textarea').element as HTMLTextAreaElement).value).toBe('Org text')
            // FDAW-16 3.9 text 14: the default unchanged is not a cover summary of this release.
            expect(w.find('[data-testid="narrative-confirm"]').attributes('disabled')).toBeDefined()
            expect(w.find('[data-testid="narrative-problem"]').text())
                .toBe("Write this release's own cover summary; the organization default is only a starting point")
            await w.find('[data-testid="narrative-text"] textarea').setValue('Org text, and more')
            await flushPromises()
            expect(w.find('[data-testid="narrative-confirm"]').attributes('disabled')).toBeUndefined()
            expect(w.find('[data-testid="narrative-problem"]').exists()).toBe(false)
            w.unmount()
        })

        it('without an organization default, says so, and the empty text cannot confirm until written', async () => {
            const w = await openCreate({ narrativeOverride: null, orgNarrative: '' })
            expect(w.find('[data-testid="narrative-no-org-default"]').text()).toBe('No organization default is set')
            // An empty narrative cannot confirm.
            expect(w.find('[data-testid="narrative-confirm"]').attributes('disabled')).toBeDefined()
            await w.find('[data-testid="narrative-text"] textarea').setValue('  Written here  ')
            await flushPromises()
            expect(w.find('[data-testid="narrative-confirm"]').attributes('disabled')).toBeUndefined()
            await w.find('[data-testid="narrative-confirm"]').trigger('click')
            await flushPromises()
            expect(mutate.mock.calls[0][0].variables).toEqual({ release: { uuid: 'r1', org: 'o1', fdaAssessmentNarrative: 'Written here' } })
            expect(mutations()).toEqual(['updateReleaseNarrative'])
            expect(w.find('[data-testid="draft-start-form"]').exists()).toBe(true)
            w.unmount()
        })

        it('Write one with a changed text saves it, emits narrative-saved, then previews and creates', async () => {
            const w = await openCreate({ narrativeOverride: 'Release text', orgNarrative: 'Org text' })
            await w.find('[data-testid="narrative-text"] textarea').setValue('New release text')
            let resolveWrite!: () => void
            mutate.mockImplementationOnce((opts: any) => new Promise(resolve => {
                resolveWrite = () => resolve({ data: { updateRelease: { uuid: 'r1', fdaAssessmentNarrative: opts.variables.release.fdaAssessmentNarrative } } })
            }))
            await w.find('[data-testid="narrative-confirm"]').trigger('click')
            await flushPromises()
            expect(mutate.mock.calls[0][0].variables).toEqual({ release: { uuid: 'r1', org: 'o1', fdaAssessmentNarrative: 'New release text' } })
            const order = ops()
            expect(w.find('[data-testid="draft-start-form"]').exists()).toBe(false)
            resolveWrite(); await flushPromises(); expect(w.find('[data-testid="draft-start-form"]').exists()).toBe(true)
            expect(mutations()).toEqual(['updateReleaseNarrative'])
            expect(order).toContain('updateReleaseNarrative')
            expect(w.findComponent(Panel).emitted('narrative-saved')).toEqual([['New release text']])
            expect(w.find('[data-testid="narrative-form"]').exists()).toBe(false)
            w.unmount()
        })

        it('Write one unchanged omits the write and goes on to the act', async () => {
            const w = await openCreate({ narrativeOverride: 'Release text', orgNarrative: 'Org text' })
            await w.find('[data-testid="narrative-confirm"]').trigger('click')
            await flushPromises()
            expect(mutations()).toEqual([])
            expect(w.find('[data-testid="draft-start-form"]').exists()).toBe(true)
            expect(w.findComponent(Panel).emitted('narrative-saved')).toBeUndefined()
            w.unmount()
        })

        it('a failed narrative write shows the error in the prompt, which stays open, and submits nothing', async () => {
            const w = await openCreate({ narrativeOverride: 'Release text', orgNarrative: 'Org text' })
            mutate.mockRejectedValueOnce(new Error('fdaAssessmentNarrative must not exceed 8000 characters'))
            await w.find('[data-testid="narrative-text"] textarea').setValue('Changed')
            await w.find('[data-testid="narrative-confirm"]').trigger('click')
            await flushPromises()
            expect(w.find('[data-testid="narrative-save-error"]').text()).toContain('fdaAssessmentNarrative must not exceed 8000 characters')
            expect(w.find('[data-testid="narrative-form"]').exists()).toBe(true)
            expect(mutations()).toEqual(['updateReleaseNarrative'])
            expect(w.find('[data-testid="draft-start-form"]').exists()).toBe(false)
            expect(ops()).not.toContain('SubmissionsOfComponent')
            expect(fire).not.toHaveBeenCalled()
            expect(w.findComponent(Panel).emitted('narrative-saved')).toBeUndefined()
            w.unmount()
        })

        it('Cancel closes the prompt and sends nothing', async () => {
            const w = await openCreate({ narrativeOverride: 'Release text', orgNarrative: 'Org text' })
            await w.find('[data-testid="narrative-cancel"]').trigger('click')
            await flushPromises()
            expect(w.find('[data-testid="narrative-form"]').exists()).toBe(false)
            expect(mutate).not.toHaveBeenCalled()
            expect(w.find('[data-testid="draft-start-form"]').exists()).toBe(false)
            expect(ops()).not.toContain('SubmissionsOfComponent')
            w.unmount()
        })

        it('Start draft over a FINAL head asks too, writes first, then the form shows with no category chosen', async () => {
            narrativeAnswers([ACTIVE])
            const w = await mountPanel({ kinds: ['FDA_PREMARKET'], narrativeOverride: 'Release text', orgNarrative: 'Org text' })
            await w.find('[data-testid="draft-start"]').trigger('click')
            await flushPromises()
            expect(w.find('[data-testid="narrative-form"]').exists()).toBe(true)
            expect(w.find('[data-testid="draft-start-form"]').exists()).toBe(false)
            await w.find('[data-testid="narrative-text"] textarea').setValue('New release text')
            await flushPromises()
            await w.find('[data-testid="narrative-confirm"]').trigger('click')
            await flushPromises()
            expect(mutations()).toEqual(['updateReleaseNarrative'])
            expect(mutate.mock.calls[0][0].variables.release.fdaAssessmentNarrative).toBe('New release text')
            expect(w.find('[data-testid="draft-start-form"]').exists()).toBe(true)
            const category = w.findAllComponents(NSelect).find((c: any) => c.attributes('data-testid') === 'draft-reason-category')!
            expect(category.props('value')).toBeNull()
            expect(w.find('[data-testid="draft-start-problem"]').text()).toBe('Choose a reason')
            w.unmount()
        })

        it('a second act after a write starts from the stored override before the host refreshes', async () => {
            narrativeAnswers([ACTIVE])
            const w = await mountPanel({ kinds: ['FDA_PREMARKET'], narrativeOverride: null, orgNarrative: 'Org text' })
            await w.find('[data-testid="draft-start"]').trigger('click')
            await flushPromises()
            await w.find('[data-testid="narrative-text"] textarea').setValue('Own text')
            await w.find('[data-testid="narrative-confirm"]').trigger('click')
            await flushPromises()
            await w.find('[data-testid="draft-start-cancel"]').trigger('click')
            await flushPromises()
            await w.find('[data-testid="draft-start"]').trigger('click')
            await flushPromises()
            expect((w.find('[data-testid="narrative-text"] textarea').element as HTMLTextAreaElement).value).toBe('Own text')
            w.unmount()
        })
    })
})

// Task FDAW-4: the newest version in the open (B13, T2, T3) and the actors as names (B14, T4).
describe('ReleaseSubmissionsPanel: the newest version and the actors (FDAW-4)', () => {
    const U = 'd961c662-0000-4000-8000-000000000001'
    let heads: any[] = []

    beforeEach(() => {
        query.mockReset()
        mutate.mockReset()
        fire.mockReset()
        heads = HEADS
        query.mockImplementation(async (opts: any) => {
            const d = draftReads(opts); if (d) return d
            switch (operation(opts)) {
            case 'PackageKinds': return { data: { packageKinds: [{ kind: 'FDA_PREMARKET', parts: [] }] } }
            case 'SubmissionsOfRelease': return { data: { submissionsOfRelease: heads } }
            default: throw new Error('unexpected query ' + operation(opts))
            }
        })
        mutate.mockImplementation(async (opts: any) => {
            switch (operation(opts)) {
            case 'FinalizeDraft': return { data: { finalizeDraft: { draft: null, submission: { uuid: 's1', state: 'FINAL', latestVersion: 1 },
                version: { version: 1 } } } }
            default: throw new Error('unexpected mutation ' + operation(opts))
            }
        })
        fire.mockResolvedValue({ isConfirmed: true, value: 'why' })
    })

    it('T2: the latest block renders before the history, headed Latest: v2, with parts, downloads and the manifest; no row expanded', async () => {
        const w = await mountPanel({ kinds: ['FDA_PREMARKET'] })
        const kind = w.find('[data-testid="submission-kind-FDA_PREMARKET"]')
        const latest = kind.find('[data-testid="submission-latest-FDA_PREMARKET"]')
        expect(latest.exists()).toBe(true)
        const html = kind.html()
        expect(html.indexOf('submission-latest-FDA_PREMARKET')).toBeLessThan(html.indexOf('submission-versions-FDA_PREMARKET'))
        expect(latest.text()).toContain('Latest: v2')
        expect(latest.find('[data-testid="submission-parts-FDA_PREMARKET-2"]').exists()).toBe(true)
        expect(latest.findAll('[data-testid="submission-download"]')).toHaveLength(1)
        expect(latest.find('[data-testid="submission-manifest-download"]').exists()).toBe(true)
        expect(latest.find('[data-testid="submission-manifest-show"]').exists()).toBe(true)
        expect(kind.findAll('tr.n-data-table-tr--expanded-content')).toHaveLength(0)
        expect(kind.findAll('[data-testid="submission-versions-FDA_PREMARKET"] [data-testid^="submission-parts-"]')).toHaveLength(0)
        w.unmount()
    })

    it('T2: without a version at latestVersion, the block shows the highest version the head lists', async () => {
        heads = [{ ...HEADS[0], latestVersion: 7 }]
        const w = await mountPanel({ kinds: ['FDA_PREMARKET'] })
        expect(w.find('[data-testid="submission-latest-FDA_PREMARKET"]').text()).toContain('Latest: v2')
        w.unmount()
    })

    it('T3: after a Finalize the re-read shows the new version without a click', async () => {
        const built = { uuid: 'd1', org: 'o1', release: 'r1', component: 'c1', kind: 'FDA_PREMARKET', submission: null, state: 'EDITING',
            revision: 7, plannedVersion: 1, startedAt: '2026-11-13T05:00:00Z', startedBy: { actor: 'u1', actorName: 'Ann' },
            reasonCategory: 'FIRST_VERSION', reasonText: null, replaces: null, job: { state: 'BUILT', attempt: 1 },
            build: { builtAt: '2026-11-13T06:07:08.123Z', releaseSbom: { version: 3 }, parts: [], diff: null }, confirmation: null,
            changesSinceBuild: { count: 0, fingerprintEqual: true, movedLines: [] } }
        let drafts: any[] = [built]
        heads = []
        query.mockImplementation(async (opts: any) => {
            if (operation(opts) === 'ReleaseDrafts') return { data: { release: { uuid: 'r1', drafts } } }
            const d = draftReads(opts); if (d) return d
            switch (operation(opts)) {
            case 'PackageKinds': return { data: { packageKinds: [{ kind: 'FDA_PREMARKET', parts: [] }] } }
            case 'SubmissionsOfRelease': return { data: { submissionsOfRelease: heads } }
            default: throw new Error('unexpected query ' + operation(opts))
            }
        })
        fire.mockResolvedValue({ isConfirmed: true, value: 1 })
        const w = await mountPanel({ kinds: ['FDA_PREMARKET'] })
        expect(w.find('[data-testid="submission-latest-FDA_PREMARKET"]').exists()).toBe(false)
        heads = [{ ...HEADS[0], latestVersion: 1, versions: [v(1)] }]
        drafts = []
        await w.find('[data-testid="draft-finalize"]').trigger('click')
        await flushPromises()
        expect(mutate.mock.calls.map(c => operation(c[0]))).toEqual(['FinalizeDraft'])
        let latest = w.find('[data-testid="submission-latest-FDA_PREMARKET"]')
        expect(latest.text()).toContain('Latest: v1')
        expect(latest.find('[data-testid="submission-parts-FDA_PREMARKET-1"]').exists()).toBe(true)
        drafts = [{ ...built, uuid: 'd2', submission: 's1', plannedVersion: 2 }]
        await (w.findComponent(Panel).vm as any).read()
        await flushPromises()
        heads = [{ ...HEADS[0], latestVersion: 2, versions: [v(1), v(2)] }]
        drafts = []
        await w.find('[data-testid="draft-finalize"]').trigger('click')
        await flushPromises()
        expect(mutate.mock.calls.map(c => operation(c[0]))).toEqual(['FinalizeDraft', 'FinalizeDraft'])
        latest = w.find('[data-testid="submission-latest-FDA_PREMARKET"]')
        expect(latest.text()).toContain('Latest: v2')
        expect(latest.find('[data-testid="submission-parts-FDA_PREMARKET-2"]').exists()).toBe(true)
        w.unmount()
    })

    it('T4: the head line names the actor, the uuid only in the title; a version without a name reads removed user', async () => {
        heads = [{ ...HEADS[0], lastActBy: U, lastActByName: 'Ada Lovelace',
            versions: [v(1, { createdBy: U, createdByName: null }), { ...V2, createdBy: U, createdByName: 'Ada Lovelace' }] }]
        const w = await mountPanel({ kinds: ['FDA_PREMARKET'] })
        const head = w.find('[data-testid="submission-head-FDA_PREMARKET"]')
        expect(head.text().replace(/\s+/g, ' ')).toContain('by Ada Lovelace')
        const actor = head.find('[data-testid="submission-actor"]')
        expect(actor.text()).toBe('Ada Lovelace')
        expect(actor.attributes('title')).toBe(U)
        const table = w.find('[data-testid="submission-versions-FDA_PREMARKET"]')
        const byCells = table.findAll('[data-testid="submission-actor"]')
        expect(byCells.map((c: any) => c.text())).toEqual(['Ada Lovelace', 'removed user'])
        expect(byCells.every((c: any) => c.attributes('title') === U)).toBe(true)
        expect(w.text()).not.toContain(U)
        w.unmount()
    })

    it('T4: the panel takes no userLabel prop any more', () => {
        expect(Object.keys((Panel as any).props ?? {})).not.toContain('userLabel')
    })
})

/** FDAW-78 row 15 (REQ-67): the release panel's Requirement and Settings cells in words, swept. */
describe('ReleaseSubmissionsPanel in words (FDAW-78 row 15)', () => {
    const HEAD = { uuid: 's9', release: 'r1', kind: 'FDA_PREMARKET', state: 'FINAL', latestVersion: 2, lastAct: { kind: 'FINALIZE', actedAt: '2026-10-02T00:00:00Z', actorName: 'Ann' },
        lastActDate: '2026-10-02T00:00:00Z', lastActBy: 'u1', versions: [
            v(1, { submission: 's9' }),
            v(2, { submission: 's9', settings: { row: 'row-1', revision: 0, source: 'COMPONENT' }, parts: [
                { part: 'NARRATIVE', requirement: 'REQUIRED', outcome: 'PRODUCED', artifact: A1, artifactVersion: '1',
                    fileName: 'narrative.txt', mediaType: 'text/plain', size: 5, sha256: 'd'.repeat(64) },
                { part: 'VEX', requirement: 'OPTIONAL', outcome: 'SKIPPED', skipReason: 'EXCLUDED_BY_SETTINGS', detail: null },
            ] }),
        ] }

    beforeEach(() => {
        query.mockReset()
        query.mockImplementation(async (opts: any) => {
            const d = draftReads(opts); if (d) return d
            switch (operation(opts)) {
            case 'PackageKinds': return { data: { packageKinds: [{ kind: 'FDA_PREMARKET', parts: [] }] } }
            case 'SubmissionsOfRelease': return { data: { submissionsOfRelease: [HEAD] } }
            default: throw new Error('unexpected query ' + operation(opts))
            }
        })
    })

    it('Required and Optional in the parts table, the settings in words, and the panel swept', async () => {
        const w = await mountPanel({ componentType: 'PRODUCT' })
        const parts = w.find('[data-testid="submission-parts-FDA_PREMARKET-2"]')
        const reqCells = parts.findAll('tbody tr').map((r: any) => r.findAll('td')[1].text())
        expect(reqCells).toEqual(['Required', 'Optional'])
        const table = w.find('[data-testid="submission-versions-FDA_PREMARKET"]')
        const text = table.text()
        expect(text).toContain('This product\'s own settings, revision 0')
        expect(text).toContain('Built-in defaults')
        // the sweep reads the panel's text with the sha256 cells removed by their test id
        const root = w.find('[data-testid="submission-head-FDA_PREMARKET"]').element.closest('div')!.parentElement!.cloneNode(true) as HTMLElement
        root.querySelectorAll('[data-testid="submission-sha256"]').forEach(e => e.remove())
        const swept = root.textContent ?? ''
        expect(swept).toContain('Required')
        expect(swept).toContain('This product\'s own settings, revision 0')
        expectSwept(swept, 'release panel')
        w.unmount()
    })
})

// FDAW-39 row 51 (d) and row 53 (e): Share with customers on the package head.
describe('ReleaseSubmissionsPanel: Share with customers (FDAW-39 row 51 (d))', () => {
    const LIFECYCLE_TEXT = 'This release has not reached the lifecycle its TEA settings require.'
    const ENABLED = { publishing: 'ENABLED', canPublish: true, publication: null, refusal: null }
    const BLOCKED = { publishing: 'ENABLED', canPublish: false, publication: null, refusal: 'LIFECYCLE_TOO_LOW',
        refusalMessage: 'Release Pump 1.2.0 is ASSEMBLED; the TEA profile\'s minimum lifecycle is GENERAL_AVAILABILITY' }
    const fdaHead = { uuid: 's1', release: 'r1', kind: 'FDA_PREMARKET', state: 'FINAL', latestVersion: 2, lastAct: { kind: 'FINALIZE', actedAt: '2026-10-02T00:00:00Z', actorName: 'Ann' },
        lastActDate: '2026-10-02T00:00:00Z', lastActBy: 'u1',
        versions: [v(1), v(2, { sbomProfile: { name: 'fda', row: null, revision: 0, source: 'DEFAULT' } })] }
    const testHead = (state: string, changes: Record<string, any> = {}) => ({ uuid: 's2', release: 'r1', kind: 'TEST_KIND', state,
        latestVersion: 1, lastAct: { kind: 'FINALIZE', actedAt: '2026-10-03T00:00:00Z', actorName: 'Ann' }, lastActDate: '2026-10-03T00:00:00Z', lastActBy: 'u1', legacyState: null,
        versions: [v(1, { submission: 's2', kind: 'TEST_KIND' })], ...changes })
    let heads: any[] = []
    const shareIn = (w: any, kind: string) => w.find(`[data-testid="submission-head-${kind}"] [data-testid="submission-share"]`)
    const panel = (w: any) => w.findComponent(Panel)

    beforeEach(() => {
        query.mockReset()
        fire.mockReset()
        heads = [fdaHead, testHead('SENT')]
        query.mockImplementation(async (opts: any) => {
            const d = draftReads(opts); if (d) return d
            switch (operation(opts)) {
            case 'PackageKinds': return { data: { packageKinds: [{ kind: 'FDA_PREMARKET', parts: [] }, { kind: 'TEST_KIND', parts: [] }] } }
            case 'SubmissionsOfRelease': return { data: { submissionsOfRelease: heads } }
            default: throw new Error('unexpected query ' + operation(opts))
            }
        })
    })

    it('the button on a Final head, last in its actions, enabled with no title', async () => {
        const w = await mountPanel({ teaView: ENABLED })
        const b = shareIn(w, 'FDA_PREMARKET')
        expect(b.exists()).toBe(true)
        expect(b.text()).toBe('Share with customers')
        expect((b.element as HTMLButtonElement).disabled).toBe(false)
        expect(b.attributes('title') ?? '').toBe('')
        const actions = w.find('[data-testid="submission-head-FDA_PREMARKET"] .submission-actions')
        expect(actions.findAll('button[data-testid]').map((e: any) => e.attributes('data-testid')))
            .toEqual(['draft-start', 'submission-cancel', 'submission-share'])
        w.unmount()
    })

    it('Sent and Amended heads have it; Superseded, Withdrawn and Cancelled do not, while the Final head keeps its own', async () => {
        for (const [state, has] of [['SENT', true], ['AMENDED', true], ['SUPERSEDED', false], ['WITHDRAWN', false], ['CANCELLED', false]] as const) {
            heads = [fdaHead, testHead(state)]
            const w = await mountPanel({ teaView: ENABLED })
            expect(shareIn(w, 'TEST_KIND').exists(), state).toBe(has)
            expect(shareIn(w, 'FDA_PREMARKET').exists(), state).toBe(true)
            w.unmount()
        }
        heads = [fdaHead]
        const none = await mountPanel({ teaView: ENABLED })
        expect(none.find('[data-testid="submission-head-TEST_KIND"]').exists()).toBe(true)
        expect(shareIn(none, 'TEST_KIND').exists()).toBe(false)
        expect(shareIn(none, 'FDA_PREMARKET').exists()).toBe(true)
        none.unmount()
    })

    it('no button without a view, with publishing off, or for a reader; the head still renders', async () => {
        for (const props of [{ teaView: null }, { teaView: { ...ENABLED, publishing: 'DISABLED' } }, { teaView: ENABLED, isWritable: false }]) {
            const w = await mountPanel(props)
            expect(w.find('[data-testid="submission-share"]').exists(), JSON.stringify(props)).toBe(false)
            expect(w.find('[data-testid="submission-head-FDA_PREMARKET"] [data-testid="submission-state"]').text())
                .toBe('Final (no confirmation record) - not sent')
            w.unmount()
        }
    })

    it('disabled with the refusal in words when nothing is published and it cannot be; enabled with a publication; disabled while an act runs', async () => {
        const blocked = await mountPanel({ teaView: BLOCKED })
        const b = shareIn(blocked, 'FDA_PREMARKET')
        expect((b.element as HTMLButtonElement).disabled).toBe(true)
        expect(b.attributes('title')).toBe(LIFECYCLE_TEXT)
        blocked.unmount()

        const published = await mountPanel({ teaView: { ...ENABLED, canPublish: false, publication: { state: 'PUBLISHED' },
            refusal: 'ALREADY_PUBLISHED' } })
        expect((shareIn(published, 'FDA_PREMARKET').element as HTMLButtonElement).disabled).toBe(false)
        published.unmount()

        const acting = await mountPanel({ teaView: ENABLED, teaActing: true })
        expect((shareIn(acting, 'FDA_PREMARKET').element as HTMLButtonElement).disabled).toBe(true)
        acting.unmount()
    })

    it('a click emits share once with the head\'s kind, version, state and package profile; a disabled one emits nothing', async () => {
        const w = await mountPanel({ teaView: ENABLED })
        await shareIn(w, 'FDA_PREMARKET').trigger('click')
        expect(panel(w).emitted('share')).toEqual([[{ kind: 'FDA_PREMARKET', latestVersion: 2, state: 'FINAL', profileName: 'fda' }]])
        await shareIn(w, 'TEST_KIND').trigger('click')
        expect(panel(w).emitted('share')![1]).toEqual([{ kind: 'TEST_KIND', latestVersion: 1, state: 'SENT', profileName: 'default' }])
        w.unmount()

        heads = [fdaHead, testHead('SENT', { versions: [v(1, { submission: 's2', kind: 'TEST_KIND', sbomProfile: null })] })]
        const noProfile = await mountPanel({ teaView: ENABLED })
        await shareIn(noProfile, 'TEST_KIND').trigger('click')
        expect(panel(noProfile).emitted('share')).toEqual([[{ kind: 'TEST_KIND', latestVersion: 1, state: 'SENT', profileName: null }]])
        noProfile.unmount()

        const blocked = await mountPanel({ teaView: BLOCKED })
        await shareIn(blocked, 'FDA_PREMARKET').trigger('click')
        expect(panel(blocked).emitted('share')).toBeUndefined()
        blocked.unmount()
    })

    it('row 53 (e): the panel with the share button and its title swept, with the refusalMessage\'s words as the positive control', async () => {
        expect(BLOCKED.refusalMessage).toMatch(/\b[A-Z][A-Z0-9]+(_[A-Z0-9]+)+\b/)
        const w = await mountPanel({ teaView: BLOCKED })
        const b = shareIn(w, 'FDA_PREMARKET')
        expect(b.exists()).toBe(true)
        const root = w.find('[data-testid="submission-head-FDA_PREMARKET"]').element.closest('div')!.parentElement!.cloneNode(true) as HTMLElement
        root.querySelectorAll('[data-testid="submission-sha256"]').forEach(e => e.remove())
        const swept = (root.textContent ?? '') + ' ' + (b.attributes('title') ?? '')
        expect(swept).toContain('Share with customers')
        expect(swept).toContain(LIFECYCLE_TEXT)
        expectSwept(swept, 'release panel with share')
        w.unmount()
    })
})

// FDAW-32 (architecture-2 section 4): the draft of a kind's next version on the release's Regulatory tab.
const { default: submissionQueries } = await import('@/utils/submissionQueries')
const U1 = A1
const D: any = { uuid: 'd1', org: 'o1', release: 'r1', component: 'c1', kind: 'FDA_PREMARKET', submission: null, state: 'EDITING',
    revision: 7, plannedVersion: 2, startedAt: '2026-11-13T05:00:00Z', startedBy: { actor: 'u1', actorName: 'Ann' },
    reasonCategory: 'CORRECTION', reasonText: 'fix', replaces: null, job: { state: 'BUILT', attempt: 1 },
    build: { builtAt: '2026-11-13T06:07:08.123Z', releaseSbom: { version: 3 }, parts: [
        { part: 'AGGREGATE_SBOM', artifact: U1, sha256: 'a'.repeat(64), sizeBytes: 10, documentVersion: 3 },
        { part: 'VDR', artifact: 'not-a-uuid', sha256: null, sizeBytes: null, documentVersion: null }], diff: null },
    confirmation: null, changesSinceBuild: { count: 0, fingerprintEqual: true, movedLines: [] } }
const DH = { ...D, submission: 's1', plannedVersion: 3 }
const AWAIT = { state: 'AWAITING_CONFIRMATION', confirmation: { actor: 'u1', actorName: 'Ann', capacity: 'AUTHOR',
    confirmedAt: '2026-11-13T06:09:10Z' } }
const FINAL_S1 = { uuid: 's1', release: 'r1', kind: 'FDA_PREMARKET', state: 'FINAL', latestVersion: 2,
    lastAct: { kind: 'FINALIZE', actedAt: '2026-10-02T00:00:00Z', actorName: 'Ann' }, lastActDate: '2026-10-02T00:00:00Z',
    lastActBy: 'u1', versions: [v(1), v(2)] }
const TEA_ENABLED = { publishing: 'ENABLED', canPublish: true, publication: null, refusal: null }
const DRAFTS = (d: any) => ({ data: { release: { uuid: 'r1', drafts: [d] } } })
const SETTING = (val: any) => ({ data: { organizations: [{ uuid: 'o1', settings: { submissionSecondConfirmer: val } }] } })
const FIVE = ['draft-rebuild', 'draft-discard', 'draft-confirm', 'draft-return', 'draft-finalize']
const T16 = 'Rebuild first'
const T29 = 'The second confirmation must come from a different person than the first; ask another member to finalize'
const LIFE = 'The release must be Assembled or later'
const PLAN = (p: number, l: number) => 'This draft was planned as version ' + p + ', but the package already has version ' + l
    + '; discard this draft and start a new one'
const T32 = 'The second-confirmer setting could not be read; this page reads it again every 30 seconds'
const T17_BASE = 'Writes v2 of the FDA premarket submission of widget 1.0.0 from the build of 2026-11-13 06:07 UTC. Nothing is generated '
    + 'again, and a Final version cannot be changed.'

// Each answer by operation name: a value, a function of the options, or an Error (rejected); the rest through draftReads.
let replies: Record<string, any> = {}
function install (map: Record<string, any>) {
    replies = { PackageKinds: { data: { packageKinds: [{ kind: 'FDA_PREMARKET', parts: [] }, { kind: 'TEST_KIND', parts: [] }] } },
        SubmissionsOfRelease: { data: { submissionsOfRelease: [] } }, ...map }
    query.mockImplementation(async (opts: any) => {
        const op = operation(opts)
        if (op in replies) {
            const r = typeof replies[op] === 'function' ? replies[op](opts) : replies[op]
            if (r instanceof Error || (r && r.errors)) throw r
            return r
        }
        const d = draftReads(opts); if (d) return d
        throw new Error('unexpected query ' + op)
    })
}
const opsOf = (calls: any[]) => calls.map(c => operation(c[0]))
const callsOf = (name: string) => query.mock.calls.filter(c => operation(c[0]) === name).map(c => c[0])
const draftButtonIds = (w: any) => w.findAll('[data-testid="draft-card"] button[data-testid]')
    .map((b: any) => b.attributes('data-testid')).filter((id: string) => FIVE.includes(id))
const button = (w: any, id: string) => w.find(`[data-testid="${id}"]`)
const isDisabled = (w: any, id: string) => (button(w, id).element as HTMLButtonElement).disabled
const titleOf = (w: any, id: string) => button(w, id).attributes('title') ?? ''
const selectOf = (w: any, id: string) => w.findAllComponents(NSelect).find((c: any) => c.attributes('data-testid') === id)

function resetMocks () {
    query.mockReset()
    mutate.mockReset()
    fire.mockReset()
    mutate.mockImplementation(async (opts: any) => {
        const field = (opts.mutation.definitions[0].selectionSet.selections[0].name.value)
        return { data: { [field]: { draft: { uuid: 'd9', state: 'EDITING', revision: 1 }, submission: null, version: { version: 2 } } } }
    })
}

describe('ReleaseSubmissionsPanel: the draft (FDAW-32 row 6)', () => {
    beforeEach(() => {
        resetMocks()
        install({})
    })

    it('(a) reads the drafts and the setting with no-cache; a failed drafts read is the load error, a failed setting read is not', async () => {
        install({ SubmissionsOfRelease: { data: { submissionsOfRelease: [FINAL_S1] } } })
        const w = await mountPanel()
        expect(opsOf(query.mock.calls).sort()).toEqual(['OrgSecondConfirmer', 'PackageKinds', 'ReleaseDrafts', 'SubmissionsOfRelease'])
        expect(callsOf('ReleaseDrafts')[0].variables).toEqual({ releaseID: 'r1', orgID: 'o1' })
        expect(query.mock.calls.every(c => c[0].fetchPolicy === 'no-cache')).toBe(true)
        w.unmount()

        install({ SubmissionsOfRelease: { data: { submissionsOfRelease: [FINAL_S1] } }, ReleaseDrafts: new Error('drafts down') })
        const failed = await mountPanel()
        expect(failed.find('[data-testid="submissions-load-error"]').exists()).toBe(true)
        failed.unmount()

        install({ SubmissionsOfRelease: { data: { submissionsOfRelease: [FINAL_S1] } }, OrgSecondConfirmer: new Error('setting down') })
        const noSetting = await mountPanel()
        expect(noSetting.find('[data-testid="submissions-load-error"]').exists()).toBe(false)
        expect(noSetting.find('[data-testid="submission-head-FDA_PREMARKET"] [data-testid="submission-state"]').exists()).toBe(true)
        noSetting.unmount()

        install({ ReleaseDrafts: DRAFTS(D), OrgSecondConfirmer: new Error('setting down') })
        const unread = await mountPanel()
        expect(draftButtonIds(unread)).toEqual(['draft-rebuild', 'draft-discard', 'draft-finalize'])
        expect(unread.find('[data-testid="draft-setting-unread"]').text()).toBe(T32)
        unread.unmount()

        install({ ReleaseDrafts: DRAFTS(D), OrgSecondConfirmer: SETTING(false) })
        const read = await mountPanel()
        expect(read.find('[data-testid="draft-setting-unread"]').exists()).toBe(false)
        read.unmount()

        install({ ReleaseDrafts: DRAFTS(D),
            OrgSecondConfirmer: { data: { organizations: [{ uuid: 'o2', settings: { submissionSecondConfirmer: true } }] } } })
        const other = await mountPanel()
        expect(draftButtonIds(other)).toEqual(['draft-rebuild', 'draft-discard', 'draft-finalize'])
        expect(other.find('[data-testid="draft-setting-unread"]').text()).toBe(T32)
        other.unmount()
    })

    it('(b) no head, no draft: Start draft alone, and none of the one-click buttons; below Assembled it is disabled with the reason', async () => {
        const w = await mountPanel({ kinds: ['FDA_PREMARKET'] })
        const actions = w.find('[data-testid="submission-kind-FDA_PREMARKET"] .submission-actions')
        expect(actions.findAll('button[data-testid]').map((b: any) => b.attributes('data-testid'))).toEqual(['draft-start'])
        for (const id of ['submission-create', 'submission-resubmit', 'submission-withdraw']) {
            expect(w.find(`[data-testid="${id}"]`).exists(), id).toBe(false)
        }
        w.unmount()
        const low = await mountPanel({ kinds: ['FDA_PREMARKET'], lifecycle: 'DRAFT' })
        expect(isDisabled(low, 'draft-start')).toBe(true)
        expect(titleOf(low, 'draft-start')).toBe(LIFE)
        low.unmount()
    })

    it('(c) the head actions with and without a draft; a reader sees the card without actions', async () => {
        install({ SubmissionsOfRelease: { data: { submissionsOfRelease: [FINAL_S1] } } })
        const ids = (w: any) => w.findAll('[data-testid="submission-kind-FDA_PREMARKET"] .submission-actions button[data-testid]')
            .map((b: any) => b.attributes('data-testid'))
        const w = await mountPanel({ kinds: ['FDA_PREMARKET'], teaView: TEA_ENABLED })
        expect(ids(w)).toEqual(['draft-start', 'submission-cancel', 'submission-share'])
        w.unmount()

        install({ SubmissionsOfRelease: { data: { submissionsOfRelease: [FINAL_S1] } }, ReleaseDrafts: DRAFTS(DH) })
        const withDraft = await mountPanel({ kinds: ['FDA_PREMARKET'], teaView: TEA_ENABLED })
        expect(ids(withDraft)).toEqual(['submission-cancel', 'submission-share'])
        expect(withDraft.findAll('[data-testid="draft-card"]')).toHaveLength(1)
        withDraft.unmount()

        install({ ReleaseDrafts: DRAFTS(D), OrgSecondConfirmer: new Error('setting down') })
        const reader = await mountPanel({ kinds: ['FDA_PREMARKET'], isWritable: false })
        expect(reader.find('.submission-actions').exists()).toBe(false)
        expect(reader.find('[data-testid="draft-card"]').exists()).toBe(true)
        expect(draftButtonIds(reader)).toEqual([])
        expect(reader.find('[data-testid="draft-setting-unread"]').exists()).toBe(false)
        reader.unmount()
    })

    it('(d) a draft of a kind the host did not pass and with no head renders its kind block with the card', async () => {
        install({ ReleaseDrafts: DRAFTS(D) })
        const w = await mountPanel({ kinds: ['TEST_KIND'] })
        expect(w.find('[data-testid="submission-kind-FDA_PREMARKET"] [data-testid="draft-card"]').exists()).toBe(true)
        w.unmount()
    })

    it('(e) without a head: the Start form with First version alone, then StartDraft and RebuildDraft', async () => {
        const w = await mountPanel({ kinds: ['FDA_PREMARKET'] })
        await button(w, 'draft-start').trigger('click')
        await flushPromises()
        expect(w.find('[data-testid="draft-start-form"]').exists()).toBe(true)
        expect(mutate.mock.calls).toEqual([])
        const category = selectOf(w, 'draft-reason-category')!
        expect(category.props('options')).toEqual([{ label: 'First version', value: 'FIRST_VERSION' }])
        expect(category.props('value')).toBe('FIRST_VERSION')
        expect(category.props('disabled')).toBe(true)
        expect(callsOf('SubmissionsOfComponent').map(c => c.variables)).toEqual([{ component: 'c1', kind: 'FDA_PREMARKET', state: null }])
        await button(w, 'draft-start-confirm').trigger('click')
        await flushPromises()
        expect(opsOf(mutate.mock.calls)).toEqual(['StartDraft', 'RebuildDraft'])
        expect(mutate.mock.calls[0][0].variables).toEqual({ release: 'r1', kind: 'FDA_PREMARKET', reasonCategory: 'FIRST_VERSION',
            reasonText: null, replacesSubmission: null, replacesVersion: null })
        expect(w.find('[data-testid="draft-start-form"]').exists()).toBe(false)
        w.unmount()
    })

    it('(f) with a FINAL head: three reasons, a required reason text, and the replaced version sent', async () => {
        install({ SubmissionsOfRelease: { data: { submissionsOfRelease: [FINAL_S1] } },
            SubmissionsOfComponent: { data: { submissionsOfComponent: [{ uuid: 's3', release: 'r3',
                releaseVersion: '<img src=x onerror="window.__hit=1"><b>b</b>',
                versionStates: [{ version: 1, state: 'SENT' }, { version: 2, state: 'FINAL' }] }] } } })
        const w = await mountPanel({ kinds: ['FDA_PREMARKET'] })
        await button(w, 'draft-start').trigger('click')
        await flushPromises()
        const category = selectOf(w, 'draft-reason-category')!
        expect(category.props('options').map((o: any) => o.value)).toEqual(['FDA_REQUEST_RESPONSE', 'CORRECTION', 'OTHER'])
        expect(category.props('value')).toBeNull()
        expect(w.find('[data-testid="draft-start-problem"]').text()).toBe('Choose a reason')
        expect(isDisabled(w, 'draft-start-confirm')).toBe(true)
        category.vm.$emit('update:value', 'CORRECTION')
        await flushPromises()
        expect(w.find('[data-testid="draft-start-problem"]').text()).toBe('A reason is required')
        await w.find('[data-testid="draft-reason-text"] textarea').setValue('fix')
        await flushPromises()
        expect(w.find('[data-testid="draft-start-problem"]').exists()).toBe(false)
        expect(isDisabled(w, 'draft-start-confirm')).toBe(false)
        selectOf(w, 'draft-replaces')!.vm.$emit('update:value', 's3:2')
        await flushPromises()
        await button(w, 'draft-start-confirm').trigger('click')
        await flushPromises()
        expect(mutate.mock.calls[0][0].variables).toEqual({ release: 'r1', kind: 'FDA_PREMARKET', reasonCategory: 'CORRECTION',
            reasonText: 'fix', replacesSubmission: 's3', replacesVersion: 2 })
        w.unmount()
    })

    it('(g) the replaceable versions could not be read: no select, text 13, and Start still sent without one', async () => {
        install({ SubmissionsOfComponent: new Error('heads down') })
        const w = await mountPanel({ kinds: ['FDA_PREMARKET'] })
        await button(w, 'draft-start').trigger('click')
        await flushPromises()
        expect(w.find('[data-testid="draft-replaces"]').exists()).toBe(false)
        expect(w.find('[data-testid="draft-replaces-error"]').text())
            .toBe('The versions that can be replaced could not be read; the draft can be started without one')
        await button(w, 'draft-start-confirm').trigger('click')
        await flushPromises()
        expect(opsOf(mutate.mock.calls)[0]).toBe('StartDraft')
        expect(mutate.mock.calls[0][0].variables.replacesSubmission).toBeNull()
        expect(mutate.mock.calls[0][0].variables.replacesVersion).toBeNull()
        w.unmount()
    })

    it('(h) a refused Start keeps the form and its text, sends nothing further and shows the server\'s sentence', async () => {
        install({ SubmissionsOfRelease: { data: { submissionsOfRelease: [FINAL_S1] } } })
        const refusal = 'Release widget 1.0.0: A draft already exists for this release and kind'
        mutate.mockImplementation(async () => { throw { errors: [{ message: refusal }] } })
        const w = await mountPanel({ kinds: ['FDA_PREMARKET'] })
        await button(w, 'draft-start').trigger('click')
        await flushPromises()
        selectOf(w, 'draft-reason-category')!.vm.$emit('update:value', 'CORRECTION')
        await w.find('[data-testid="draft-reason-text"] textarea').setValue('fix')
        await flushPromises()
        await button(w, 'draft-start-confirm').trigger('click')
        await flushPromises()
        expect(mutate.mock.calls).toHaveLength(1)
        expect(fire.mock.calls).toEqual([[{ titleText: 'Error!', text: refusal, icon: 'error' }]])
        expect(w.find('[data-testid="draft-start-form"]').exists()).toBe(true)
        expect((w.find('[data-testid="draft-reason-text"] textarea').element as HTMLTextAreaElement).value).toBe('fix')
        w.unmount()
    })

    it('(i) a kind with a REQUIRED NARRATIVE part: the prompt first, its write, then the form; no StartDraft', async () => {
        install({ PackageKinds: { data: { packageKinds: [{ kind: 'FDA_PREMARKET', parts: [{ part: 'NARRATIVE', requirement: 'REQUIRED' }] }] } } })
        mutate.mockImplementation(async (opts: any) => ({ data: { updateRelease: { uuid: 'r1',
            fdaAssessmentNarrative: opts.variables.release.fdaAssessmentNarrative } } }))
        const w = await mountPanel({ kinds: ['FDA_PREMARKET'], narrativeOverride: 'Release text', orgNarrative: 'Org text' })
        await button(w, 'draft-start').trigger('click')
        await flushPromises()
        expect(w.find('[data-testid="narrative-prompt"]').exists()).toBe(true)
        expect(w.find('[data-testid="draft-start-form"]').exists()).toBe(false)
        expect(mutate.mock.calls).toEqual([])
        await w.find('[data-testid="narrative-text"] textarea').setValue('Changed text')
        await button(w, 'narrative-confirm').trigger('click')
        await flushPromises()
        expect(opsOf(mutate.mock.calls)).toEqual(['updateReleaseNarrative'])
        expect(w.find('[data-testid="draft-start-form"]').exists()).toBe(true)
        expect(opsOf(mutate.mock.calls)).not.toContain('StartDraft')
        w.unmount()
    })

    it('(j) the last act from lastAct, the state from the newest version\'s confirmations, and one record line per confirmation', async () => {
        const confirmed = { ...FINAL_S1, versions: [v(1), v(2, { confirmations: [
            { actor: 'u1', actorName: 'Ann', capacity: 'AUTHOR', confirmedAt: '2026-11-13T06:07:59.999Z' },
            { actor: 'u2', actorName: 'Bo', capacity: 'REVIEWER', confirmedAt: '2026-11-13T06:09:10Z' }] })] }
        install({ SubmissionsOfRelease: { data: { submissionsOfRelease: [confirmed] } } })
        const w = await mountPanel({ kinds: ['FDA_PREMARKET'] })
        const head = w.find('[data-testid="submission-head-FDA_PREMARKET"]')
        expect(head.text()).toContain('Finalized on')
        expect(head.find('[data-testid="submission-state"]').text()).toBe('Final - not sent')
        const lines = () => w.findAll('[data-testid="submission-latest-FDA_PREMARKET"] [data-testid="submission-confirmation"]')
            .map((e: any) => e.text())
        expect(lines()).toEqual(['Reviewed and confirmed ready for submission by Ann on 2026-11-13 06:07 UTC',
            'Reviewed and confirmed ready for submission by Bo on 2026-11-13 06:09 UTC'])
        const table = w.find('[data-testid="submission-versions-FDA_PREMARKET"]')
        const v1 = table.findAll('tbody tr').find((r: any) => r.findAll('td')[1]?.text() === 'v1')!
        await v1.find('.n-data-table-expand-trigger').trigger('click')
        await flushPromises()
        expect(w.find('[data-testid="submission-parts-FDA_PREMARKET-1"]').exists()).toBe(true)
        expect(w.findAll('[data-testid="submission-confirmation"]')).toHaveLength(2)
        w.unmount()

        install({ SubmissionsOfRelease: { data: { submissionsOfRelease: [{ ...FINAL_S1, lastAct: null }] } } })
        const old = await mountPanel({ kinds: ['FDA_PREMARKET'] })
        expect(old.find('[data-testid="submission-head-FDA_PREMARKET"]').text()).toContain('Last act on')
        old.unmount()
    })

    it('(k) Cancel sends cancelSubmission with the reason; dismissed, nothing', async () => {
        install({ SubmissionsOfRelease: { data: { submissionsOfRelease: [FINAL_S1] } } })
        fire.mockResolvedValue({ isConfirmed: true, value: 'why' })
        const w = await mountPanel({ kinds: ['FDA_PREMARKET'] })
        await button(w, 'submission-cancel').trigger('click')
        await flushPromises()
        expect(opsOf(mutate.mock.calls)).toEqual(['CancelSubmission'])
        expect(mutate.mock.calls.map(c => c[0].variables)).toEqual([{ submission: 's1', reason: 'why' }])
        mutate.mockClear()
        fire.mockReset()
        fire.mockResolvedValue({ isConfirmed: false })
        await button(w, 'submission-cancel').trigger('click')
        await flushPromises()
        expect(mutate.mock.calls).toEqual([])
        w.unmount()
    })

    it('(l) Finalize sends the revision of the card clicked; Rebuild sends the draft with no dialog', async () => {
        let held: any = D
        install({ ReleaseDrafts: () => DRAFTS(held) })
        fire.mockResolvedValue({ isConfirmed: true, value: 1 })
        const w = await mountPanel({ kinds: ['FDA_PREMARKET'] })
        held = { ...D, revision: 9 }
        await button(w, 'draft-finalize').trigger('click')
        await flushPromises()
        const finalizes = () => mutate.mock.calls.filter(c => operation(c[0]) === 'FinalizeDraft').map(c => c[0].variables)
        expect(finalizes()).toEqual([{ draft: 'd1', expectedRevision: 7 }])
        await button(w, 'draft-finalize').trigger('click')
        await flushPromises()
        expect(finalizes()).toEqual([{ draft: 'd1', expectedRevision: 7 }, { draft: 'd1', expectedRevision: 9 }])
        mutate.mockClear()
        fire.mockReset()
        await button(w, 'draft-rebuild').trigger('click')
        await flushPromises()
        expect(opsOf(mutate.mock.calls)).toEqual(['RebuildDraft'])
        expect(mutate.mock.calls[0][0].variables).toEqual({ draft: 'd1' })
        expect(fire).not.toHaveBeenCalled()
        w.unmount()
    })

    it('(m) the panel hands the card the user, the lifecycle, the head and the setting', async () => {
        install({ ReleaseDrafts: DRAFTS({ ...D, ...AWAIT }), OrgSecondConfirmer: SETTING(true) })
        const u1 = await mountPanel({ kinds: ['FDA_PREMARKET'], currentUser: 'u1' })
        expect(isDisabled(u1, 'draft-finalize')).toBe(true)
        expect(titleOf(u1, 'draft-finalize')).toBe(T29)
        u1.unmount()
        const u2 = await mountPanel({ kinds: ['FDA_PREMARKET'], currentUser: 'u2' })
        expect(isDisabled(u2, 'draft-finalize')).toBe(false)
        expect(titleOf(u2, 'draft-finalize')).toBe('')
        u2.unmount()
        const nobody = await mountPanel({ kinds: ['FDA_PREMARKET'] })
        expect(isDisabled(nobody, 'draft-finalize')).toBe(false)
        nobody.unmount()

        install({ ReleaseDrafts: DRAFTS(D) })
        const low = await mountPanel({ kinds: ['FDA_PREMARKET'], lifecycle: 'DRAFT' })
        expect(isDisabled(low, 'draft-finalize')).toBe(true)
        expect(titleOf(low, 'draft-finalize')).toBe(LIFE)
        low.unmount()

        install({ ReleaseDrafts: DRAFTS(D), SubmissionsOfRelease: { data: { submissionsOfRelease: [FINAL_S1] } } })
        const stale = await mountPanel({ kinds: ['FDA_PREMARKET'] })
        expect(isDisabled(stale, 'draft-finalize')).toBe(true)
        expect(titleOf(stale, 'draft-finalize')).toBe(PLAN(2, 2))
        stale.unmount()

        install({ ReleaseDrafts: DRAFTS(DH), SubmissionsOfRelease: { data: { submissionsOfRelease: [FINAL_S1] } } })
        const next = await mountPanel({ kinds: ['FDA_PREMARKET'] })
        expect(isDisabled(next, 'draft-finalize')).toBe(false)
        next.unmount()
    })

    it('(n) the setting the panel holds words the Finalize dialog', async () => {
        const ending = (setting: any) => {
            install({ ReleaseDrafts: DRAFTS({ ...D, ...AWAIT }), OrgSecondConfirmer: setting })
        }
        const cases: [any, string][] = [
            [SETTING(true), ' The confirmation by Ann is recorded first, then yours.'],
            [SETTING(false), ' The organization no longer requires a second confirmer: the confirmation by Ann will not be recorded, and you are recorded alone.'],
            [new Error('setting down'), ' The second-confirmer setting could not be read: whether the confirmation by Ann is recorded follows the setting as it is now.'],
        ]
        for (const [setting, tail] of cases) {
            ending(setting)
            fire.mockReset()
            fire.mockResolvedValue({ isConfirmed: false })
            const w = await mountPanel({ kinds: ['FDA_PREMARKET'], currentUser: 'u2' })
            await button(w, 'draft-finalize').trigger('click')
            await flushPromises()
            expect(fire.mock.calls).toHaveLength(1)
            expect(fire.mock.calls[0][0].text).toBe(T17_BASE + tail)
            expect(mutate.mock.calls).toEqual([])
            w.unmount()
        }
    })
})

describe('ReleaseSubmissionsPanel: polling (FDAW-32 row 8)', () => {
    const T = '2026-11-13T06:00:00Z'
    const RUNNING = { ...D, job: { state: 'RUNNING', startedAt: T, attempt: 1 } }
    const jobAnswer = (state: string) => ({ data: { draft: { uuid: 'd1', state: 'EDITING', revision: 7,
        job: { state, startedAt: T, attempt: 1 } } } })
    const jobCalls = () => callsOf('DraftJob')
    const draftCalls = () => callsOf('Draft')
    const settingCalls = () => callsOf('OrgSecondConfirmer')
    let w: any = null

    async function advance (ms: number) {
        await vi.advanceTimersByTimeAsync(ms)
        await flushPromises()
    }

    beforeEach(() => {
        resetMocks()
        vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'] })
    })

    afterEach(() => {
        w?.unmount()
        w = null
        delete (document as any).hidden
        vi.useRealTimers()
    })

    it('(a) a job in flight is read alone every 5 seconds, and its end re-reads the panel', async () => {
        let state = 'RUNNING'
        install({ ReleaseDrafts: () => DRAFTS(state === 'RUNNING' ? RUNNING : D), DraftJob: () => jobAnswer(state) })
        w = await mountPanel({ kinds: ['FDA_PREMARKET'] })
        await advance(4999)
        expect(jobCalls()).toEqual([])
        await advance(1)
        expect(jobCalls()).toEqual([{ query: submissionQueries.DraftJobGql, variables: { uuid: 'd1' }, fetchPolicy: 'no-cache' }])
        await advance(5000)
        expect(jobCalls()).toHaveLength(2)
        state = 'BUILT'
        const heads = callsOf('SubmissionsOfRelease').length
        const drafts = callsOf('ReleaseDrafts').length
        await advance(5000)
        expect(jobCalls()).toHaveLength(3)
        expect(callsOf('SubmissionsOfRelease')).toHaveLength(heads + 1)
        expect(callsOf('ReleaseDrafts')).toHaveLength(drafts + 1)
        await advance(5000)
        expect(jobCalls()).toHaveLength(3)
        expect(draftCalls()).toEqual([])
    })

    it('(b) a built draft is read in full every 30 seconds, and the answer turns its banner', async () => {
        install({ ReleaseDrafts: DRAFTS(D),
            Draft: { data: { draft: { ...D, changesSinceBuild: { count: 2, fingerprintEqual: false, movedLines: [] } } } } })
        w = await mountPanel({ kinds: ['FDA_PREMARKET'] })
        expect(w.find('[data-testid="draft-stale"]').text()).toContain('Inputs unchanged')
        await advance(29999)
        expect(draftCalls()).toEqual([])
        await advance(1)
        expect(draftCalls()).toEqual([{ query: submissionQueries.DraftGql, variables: { uuid: 'd1' }, fetchPolicy: 'no-cache' }])
        expect(w.find('[data-testid="draft-stale"]').text()).toContain('2 changes since this build')
        expect(isDisabled(w, 'draft-finalize')).toBe(true)
        expect(titleOf(w, 'draft-finalize')).toBe(T16)
    })

    it('(c) nothing is read while the tab is hidden, a form is open or an act waits on its dialog', async () => {
        install({ ReleaseDrafts: DRAFTS(D), Draft: { data: { draft: D } } })
        w = await mountPanel({ kinds: ['FDA_PREMARKET'] })
        Object.defineProperty(document, 'hidden', { configurable: true, get: () => true })
        await advance(60000)
        expect(draftCalls()).toEqual([])
        expect(settingCalls()).toHaveLength(1)
        delete (document as any).hidden
        await advance(30000)
        expect(draftCalls()).toHaveLength(1)
        w.unmount()

        query.mockClear()
        w = await mountPanel({ kinds: ['FDA_PREMARKET', 'TEST_KIND'] })
        await button(w.find('[data-testid="submission-kind-TEST_KIND"]'), 'draft-start').trigger('click')
        await flushPromises()
        expect(w.find('[data-testid="draft-start-form"]').exists()).toBe(true)
        await advance(60000)
        expect(draftCalls()).toEqual([])
        await button(w, 'draft-start-cancel').trigger('click')
        await advance(30000)
        expect(draftCalls()).toHaveLength(1)
        w.unmount()

        query.mockClear()
        fire.mockImplementation(() => new Promise(() => {}))
        w = await mountPanel({ kinds: ['FDA_PREMARKET'] })
        await button(w, 'draft-discard').trigger('click')
        await flushPromises()
        expect(fire).toHaveBeenCalledTimes(1)
        await advance(60000)
        expect(draftCalls()).toEqual([])
    })

    it('(d) a draft not found re-reads the panel; any other failed poll is logged and the poll goes on', async () => {
        install({ ReleaseDrafts: DRAFTS(D), Draft: { errors: [{ message: 'Draft not found' }] } })
        w = await mountPanel({ kinds: ['FDA_PREMARKET'] })
        const heads = callsOf('SubmissionsOfRelease').length
        const drafts = callsOf('ReleaseDrafts').length
        await advance(30000)
        expect(callsOf('SubmissionsOfRelease')).toHaveLength(heads + 1)
        expect(callsOf('ReleaseDrafts')).toHaveLength(drafts + 1)
        w.unmount()

        query.mockClear()
        install({ ReleaseDrafts: DRAFTS(D), Draft: new Error('boom') })
        w = await mountPanel({ kinds: ['FDA_PREMARKET'] })
        await advance(30000)
        expect(draftCalls()).toHaveLength(1)
        expect(fire).not.toHaveBeenCalled()
        expect(w.find('[data-testid="submissions-load-error"]').exists()).toBe(false)
        await advance(30000)
        expect(draftCalls()).toHaveLength(2)
    })

    it('(e) nothing after the unmount, and no timer on a panel without a draft', async () => {
        install({ ReleaseDrafts: DRAFTS(D), Draft: { data: { draft: D } } })
        w = await mountPanel({ kinds: ['FDA_PREMARKET'] })
        await advance(30000)
        expect(draftCalls()).toHaveLength(1)
        w.unmount()
        w = null
        await advance(120000)
        expect(draftCalls()).toHaveLength(1)
        expect(vi.getTimerCount()).toBe(0)

        query.mockClear()
        install({})
        w = await mountPanel({ kinds: ['FDA_PREMARKET'] })
        const atMount = query.mock.calls.length
        await advance(120000)
        expect(query.mock.calls).toHaveLength(atMount)
    })

    it('(f) the cap: 180 fast polls, then the 30 second read and text 11; Refresh starts again', async () => {
        install({ ReleaseDrafts: DRAFTS(RUNNING), DraftJob: jobAnswer('RUNNING'), Draft: { data: { draft: RUNNING } } })
        w = await mountPanel({ kinds: ['FDA_PREMARKET'] })
        await advance(899999)
        expect(jobCalls()).toHaveLength(179)
        await advance(1)
        expect(jobCalls()).toHaveLength(180)
        expect(w.find('[data-testid="draft-still-running"]').text()).toContain('Still running; this page checks again every 30 seconds')
        await advance(30000)
        expect(jobCalls()).toHaveLength(180)
        expect(draftCalls()).toHaveLength(1)
        await button(w, 'draft-refresh').trigger('click')
        await flushPromises()
        expect(w.find('[data-testid="draft-still-running"]').exists()).toBe(false)
        await advance(5000)
        expect(jobCalls()).toHaveLength(181)
    })

    it('(g) the setting is read again every 30 seconds, and a failed re-read keeps the held value', async () => {
        let setting: any = SETTING(false)
        install({ ReleaseDrafts: DRAFTS(D), Draft: { data: { draft: D } }, OrgSecondConfirmer: () => setting })
        w = await mountPanel({ kinds: ['FDA_PREMARKET'] })
        expect(draftButtonIds(w)).toEqual(['draft-rebuild', 'draft-discard', 'draft-finalize'])
        expect(settingCalls()).toHaveLength(1)
        setting = SETTING(true)
        await advance(29999)
        expect(settingCalls()).toHaveLength(1)
        await advance(1)
        expect(settingCalls()).toEqual([{ query: submissionQueries.OrgSecondConfirmerGql, fetchPolicy: 'no-cache' },
            { query: submissionQueries.OrgSecondConfirmerGql, fetchPolicy: 'no-cache' }])
        expect(draftButtonIds(w)).toEqual(['draft-rebuild', 'draft-discard', 'draft-confirm'])
        setting = new Error('setting down')
        await advance(30000)
        expect(settingCalls()).toHaveLength(3)
        expect(draftButtonIds(w)).toEqual(['draft-rebuild', 'draft-discard', 'draft-confirm'])
    })
})
