// @vitest-environment happy-dom
//
// The release page's Regulatory submissions section (task TEA-30, design 4.6 row 33): one head of two
// versions and a withdrawn second kind; the head lines, the version history newest first with the
// reason as text kept in its line breaks, the parts of the newest version with a download per
// generated part through the one URL builder, none for a skipped part or one with no version
// recorded, the manifest download and text, and the withdrawn kind's reason and actions. With the
// host's kinds (Release.regulatoryPackageKinds) the rows are those kinds in their order; the catalog
// is read for its parts, and a failed read of it loses nothing but the narrative prompt. The
// assessment-narrative prompt before Create and Resubmit of a kind with a REQUIRED NARRATIVE part:
// its preselection, the organization option without a default, the write it sends (set, clear or
// none) before the act, and no act when the write fails.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { NNotificationProvider } from 'naive-ui'

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
    { uuid: 's1', release: 'r1', kind: 'FDA_PREMARKET', state: 'ACTIVE', latestVersion: 2, lastActKind: 'RESUBMITTED',
        lastActDate: '2026-10-02T00:00:00Z', lastActBy: 'u1', versions: [v(1), V2] },
    { uuid: 's2', release: 'r1', kind: 'TEST_KIND', state: 'WITHDRAWN', latestVersion: 1, lastActKind: 'WITHDRAWN',
        lastActDate: '2026-10-03T00:00:00Z', lastActBy: 'u1', withdrawnDate: '2026-10-03T00:00:00Z',
        withdrawReason: 'Recalled <i>why</i>', versions: [v(1, { submission: 's2', kind: 'TEST_KIND' })] },
]

function answers () {
    query.mockImplementation(async (opts: any) => {
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
        expect(head.find('[data-testid="submission-state"]').text()).toBe('Active')
        expect(head.text()).toContain('v2')
        expect(head.text()).toContain('Resubmitted on')
        expect(head.find('[data-testid="submission-resubmit"]').exists()).toBe(true)
        expect(head.find('[data-testid="submission-withdraw"]').exists()).toBe(true)
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

    it('expands v2: a download per generated part with a version, none for SKIPPED or no version recorded', async () => {
        const w = await mountPanel()
        const parts = w.find('[data-testid="submission-parts-FDA_PREMARKET-2"]')
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

    it('shows the withdrawn kind with its reason as text, offering Resubmit and not Withdraw', async () => {
        const w = await mountPanel()
        const head = w.find('[data-testid="submission-head-TEST_KIND"]')
        expect(head.find('[data-testid="submission-state"]').text()).toBe('Withdrawn')
        const reason = head.find('[data-testid="submission-withdraw-reason"]')
        expect(reason.text()).toBe('Recalled <i>why</i>')
        expect(reason.find('i').exists()).toBe(false)
        expect(reason.attributes('style')).toContain('white-space: pre-wrap')
        expect(head.find('[data-testid="submission-resubmit"]').exists()).toBe(true)
        expect(head.find('[data-testid="submission-withdraw"]').exists()).toBe(false)
        w.unmount()
    })

    it('a reader sees no action; an empty release says so', async () => {
        const reader = await mountPanel({ isWritable: false })
        for (const id of ['submission-create', 'submission-resubmit', 'submission-withdraw']) {
            expect(reader.find(`[data-testid="${id}"]`).exists(), id).toBe(false)
        }
        reader.unmount()
        query.mockImplementation(async (opts: any) => operation(opts) === 'PackageKinds'
            ? { data: { packageKinds: [{ kind: 'FDA_PREMARKET', parts: [] }] } }
            : { data: { submissionsOfRelease: [] } })
        const empty = await mountPanel({ lifecycle: 'DRAFT' })
        expect(empty.find('[data-testid="submissions-empty"]').text()).toBe('No submission of this release yet')
        const create = empty.find('[data-testid="submission-create"]')
        expect(create.attributes('disabled')).toBeDefined()
        expect(create.attributes('title')).toBe('The release must be ASSEMBLED or above')
        empty.unmount()
    })

    it('renders one row per kind the host passes, in that order, a failed catalog read changing nothing', async () => {
        query.mockImplementation(async (opts: any) => {
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
        const ACTIVE = { uuid: 's1', release: 'r1', kind: 'FDA_PREMARKET', state: 'ACTIVE', latestVersion: 2, lastActKind: 'CREATED',
            lastActDate: '2026-10-02T00:00:00Z', lastActBy: 'u1', versions: [v(1), v(2)] }

        function narrativeAnswers (heads: any[] = []) {
            query.mockImplementation(async (opts: any) => {
                switch (operation(opts)) {
                case 'PackageKinds': return { data: { packageKinds: NARRATIVE_KINDS } }
                case 'SubmissionsOfRelease': return { data: { submissionsOfRelease: heads } }
                case 'SubmissionPreview': return { data: { submissionPreview: { canSubmit: true, nextVersion: 1, parts: [] } } }
                default: throw new Error('unexpected query ' + operation(opts))
                }
            })
            mutate.mockImplementation(async (opts: any) => {
                switch (operation(opts)) {
                case 'updateReleaseNarrative':
                    return { data: { updateRelease: { uuid: 'r1', fdaAssessmentNarrative: opts.variables.release.fdaAssessmentNarrative || null } } }
                case 'CreateSubmission': return { data: { createSubmission: { outcome: 'CREATED', version: { version: 1 } } } }
                case 'ResubmitSubmission': return { data: { resubmitSubmission: { outcome: 'RESUBMITTED', version: { version: 3 } } } }
                default: throw new Error('unexpected mutation ' + operation(opts))
                }
            })
            fire.mockResolvedValue({ isConfirmed: true, value: 'Updated narrative' })
        }

        const radio = (w: any, value: string) => w.findAll('[data-testid="narrative-choice"] input[type="radio"]')
            .find((i: any) => (i.element as HTMLInputElement).value === value)!
        const chosen = (w: any) => (w.findAll('[data-testid="narrative-choice"] input[type="radio"]')
            .find((i: any) => (i.element as HTMLInputElement).checked)?.element as HTMLInputElement | undefined)?.value ?? null
        const mutations = () => mutate.mock.calls.map(c => operation(c[0]))
        const ops = () => [...query.mock.calls.map(c => operation(c[0])), ...mutations()]

        async function openCreate (props: Record<string, any> = {}) {
            const w = await mountPanel({ kinds: ['FDA_PREMARKET'], ...props })
            await w.find('[data-testid="submission-create"]').trigger('click')
            await flushPromises()
            return w
        }

        beforeEach(() => narrativeAnswers())

        it('Create of a kind with a REQUIRED NARRATIVE part opens the prompt first, nothing sent yet', async () => {
            const w = await openCreate({ orgNarrative: 'Org text' })
            expect(w.find('[data-testid="narrative-form"]').exists()).toBe(true)
            expect(w.text()).toContain('Use the organization default')
            expect(w.text()).toContain('Write one for this release')
            expect(ops()).not.toContain('SubmissionPreview')
            expect(mutate).not.toHaveBeenCalled()
            w.unmount()
        })

        it('a kind whose NARRATIVE part is not REQUIRED gets no prompt: Create goes straight to the preview', async () => {
            const w = await mountPanel({ kinds: ['TEST_KIND'], orgNarrative: 'Org text' })
            await w.find('[data-testid="submission-create"]').trigger('click')
            await flushPromises()
            expect(w.find('[data-testid="narrative-form"]').exists()).toBe(false)
            expect(ops()).toContain('SubmissionPreview')
            expect(mutations()).toEqual(['CreateSubmission'])
            w.unmount()
        })

        it('preselects the override when the release has one, the text pre-filled with it', async () => {
            const w = await openCreate({ narrativeOverride: 'Release text', orgNarrative: 'Org text' })
            expect(chosen(w)).toBe('RELEASE')
            expect((w.find('[data-testid="narrative-text"] textarea').element as HTMLTextAreaElement).value).toBe('Release text')
            w.unmount()
        })

        it('preselects the organization default when there is no override, shown read-only', async () => {
            const w = await openCreate({ narrativeOverride: '  ', orgNarrative: 'Org text' })
            expect(chosen(w)).toBe('ORGANIZATION')
            const org = w.find('[data-testid="narrative-org-default"] textarea')
            expect((org.element as HTMLTextAreaElement).value).toBe('Org text')
            expect(org.attributes('readonly')).toBeDefined()
            expect(w.find('[data-testid="narrative-text"]').exists()).toBe(false)
            // Writing one starts from the organization default.
            await radio(w, 'RELEASE').setValue(true)
            await flushPromises()
            expect((w.find('[data-testid="narrative-text"] textarea').element as HTMLTextAreaElement).value).toBe('Org text')
            w.unmount()
        })

        it('without an organization default, that option is disabled with its reason and Write one is chosen', async () => {
            const w = await openCreate({ narrativeOverride: null, orgNarrative: '' })
            expect(radio(w, 'ORGANIZATION').attributes('disabled')).toBeDefined()
            expect(w.find('[data-testid="narrative-no-org-default"]').text()).toBe('No organization default is set')
            expect(chosen(w)).toBe('RELEASE')
            // An empty narrative cannot confirm.
            expect(w.find('[data-testid="narrative-confirm"]').attributes('disabled')).toBeDefined()
            await w.find('[data-testid="narrative-text"] textarea').setValue('  Written here  ')
            await flushPromises()
            expect(w.find('[data-testid="narrative-confirm"]').attributes('disabled')).toBeUndefined()
            await w.find('[data-testid="narrative-confirm"]').trigger('click')
            await flushPromises()
            expect(mutate.mock.calls[0][0].variables).toEqual({ release: { uuid: 'r1', org: 'o1', fdaAssessmentNarrative: 'Written here' } })
            expect(mutations()).toEqual(['updateReleaseNarrative', 'CreateSubmission'])
            w.unmount()
        })

        it('Write one with a changed text saves it, emits narrative-saved, then previews and creates', async () => {
            const w = await openCreate({ narrativeOverride: 'Release text', orgNarrative: 'Org text' })
            await w.find('[data-testid="narrative-text"] textarea').setValue('New release text')
            await w.find('[data-testid="narrative-confirm"]').trigger('click')
            await flushPromises()
            expect(mutate.mock.calls[0][0].variables).toEqual({ release: { uuid: 'r1', org: 'o1', fdaAssessmentNarrative: 'New release text' } })
            const order = ops()
            expect(order.indexOf('updateReleaseNarrative')).toBeLessThan(order.indexOf('CreateSubmission'))
            expect(query.mock.calls.findIndex(c => operation(c[0]) === 'SubmissionPreview')).toBeGreaterThan(-1)
            expect(mutations()).toEqual(['updateReleaseNarrative', 'CreateSubmission'])
            expect(w.findComponent(Panel).emitted('narrative-saved')).toEqual([['New release text']])
            expect(w.find('[data-testid="narrative-form"]').exists()).toBe(false)
            w.unmount()
        })

        it('Write one unchanged omits the write and goes on to the act', async () => {
            const w = await openCreate({ narrativeOverride: 'Release text', orgNarrative: 'Org text' })
            await w.find('[data-testid="narrative-confirm"]').trigger('click')
            await flushPromises()
            expect(mutations()).toEqual(['CreateSubmission'])
            expect(w.findComponent(Panel).emitted('narrative-saved')).toBeUndefined()
            w.unmount()
        })

        it('the organization default over an override clears it with an empty string', async () => {
            const w = await openCreate({ narrativeOverride: 'Release text', orgNarrative: 'Org text' })
            await radio(w, 'ORGANIZATION').setValue(true)
            await flushPromises()
            await w.find('[data-testid="narrative-confirm"]').trigger('click')
            await flushPromises()
            expect(mutate.mock.calls[0][0].variables).toEqual({ release: { uuid: 'r1', org: 'o1', fdaAssessmentNarrative: '' } })
            expect(mutations()).toEqual(['updateReleaseNarrative', 'CreateSubmission'])
            expect(w.findComponent(Panel).emitted('narrative-saved')).toEqual([['']])
            w.unmount()
        })

        it('the organization default with no override sends no write', async () => {
            const w = await openCreate({ narrativeOverride: null, orgNarrative: 'Org text' })
            await w.find('[data-testid="narrative-confirm"]').trigger('click')
            await flushPromises()
            expect(mutations()).toEqual(['CreateSubmission'])
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
            expect(ops()).not.toContain('SubmissionPreview')
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
            expect(ops()).not.toContain('SubmissionPreview')
            w.unmount()
        })

        it('Resubmit asks too, writes first, then keeps its required reason and sends the shown version', async () => {
            narrativeAnswers([ACTIVE])
            const w = await mountPanel({ kinds: ['FDA_PREMARKET'], narrativeOverride: 'Release text', orgNarrative: 'Org text' })
            await w.find('[data-testid="submission-resubmit"]').trigger('click')
            await flushPromises()
            expect(w.find('[data-testid="narrative-form"]').exists()).toBe(true)
            expect(chosen(w)).toBe('RELEASE')
            await radio(w, 'ORGANIZATION').setValue(true)
            await flushPromises()
            await w.find('[data-testid="narrative-confirm"]').trigger('click')
            await flushPromises()
            expect(mutations()).toEqual(['updateReleaseNarrative', 'ResubmitSubmission'])
            expect(mutate.mock.calls[0][0].variables.release.fdaAssessmentNarrative).toBe('')
            const reasonDialog = fire.mock.calls.find(c => c[0]?.inputLabel)![0]
            expect(reasonDialog.inputLabel).toBe('Reason (required)')
            expect(mutate.mock.calls[1][0].variables).toEqual({ submission: 's1', reason: 'Updated narrative', expectedLatestVersion: 2 })
            w.unmount()
        })

        it('a second act after a write starts from the stored override before the host refreshes', async () => {
            narrativeAnswers([ACTIVE])
            const w = await mountPanel({ kinds: ['FDA_PREMARKET'], narrativeOverride: null, orgNarrative: 'Org text' })
            await w.find('[data-testid="submission-resubmit"]').trigger('click')
            await flushPromises()
            expect(chosen(w)).toBe('ORGANIZATION')
            await radio(w, 'RELEASE').setValue(true)
            await flushPromises()
            await w.find('[data-testid="narrative-text"] textarea').setValue('Own text')
            await w.find('[data-testid="narrative-confirm"]').trigger('click')
            await flushPromises()
            await w.find('[data-testid="submission-resubmit"]').trigger('click')
            await flushPromises()
            expect(chosen(w)).toBe('RELEASE')
            expect((w.find('[data-testid="narrative-text"] textarea').element as HTMLTextAreaElement).value).toBe('Own text')
            w.unmount()
        })
    })
})
