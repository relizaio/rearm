// @vitest-environment happy-dom
//
// The release page's Regulatory submissions section (task TEA-30, design 4.6 row 33): one head of two
// versions and a withdrawn second kind; the head lines, the version history newest first with the
// reason as text kept in its line breaks, the parts of the newest version with a download per
// generated part through the one URL builder, none for a skipped part or one with no version
// recorded, the manifest download and text, and the withdrawn kind's reason and actions.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { NNotificationProvider } from 'naive-ui'

const query = vi.fn()
const downloadArtifactBytes = vi.fn(async () => new ArrayBuffer(4))
const saveBlob = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: vi.fn() } }))
vi.mock('sweetalert2', () => ({ default: { fire: vi.fn() } }))
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
const operation = (opts: any) => opts.query?.definitions?.[0]?.name?.value

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

async function mountPanel (props: Record<string, any> = {}) {
    const w = mount(defineComponent({
        render: () => h(NNotificationProvider, () => h(Panel, { orgUuid: 'o1', releaseUuid: 'r1', releaseVersion: '1.0.0',
            lifecycle: 'ASSEMBLED', componentUuid: 'c1', componentName: 'widget', isWritable: true, installationType: 'SAAS', ...props })),
    }), { attachTo: document.body })
    await flushPromises()
    return w
}

describe('ReleaseSubmissionsPanel', () => {
    beforeEach(() => {
        query.mockReset()
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
})
