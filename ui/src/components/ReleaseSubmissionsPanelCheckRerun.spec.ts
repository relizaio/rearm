// @vitest-environment happy-dom
//
// FDAW-16 3.9a: Re-run check on a version row that keeps a check. The button sits on the rows whose version has
// a check summary and a downloadable Check report part, for a reader too (the server decides the right); a press
// sends one query for the version, and the dialog shows the stored and the fresh report side by side under their
// checkers. A refusal is shown in the panels, as the export check shows it.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { NNotificationProvider } from 'naive-ui'

const query = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: vi.fn() } }))
vi.mock('sweetalert2', () => ({ default: { fire: vi.fn() } }))
vi.mock('@/utils/fetchClient', () => ({ fetchWithAuth: vi.fn(), fetchArrayBufferWithAuth: vi.fn() }))

const { default: Panel } = await import('./ReleaseSubmissionsPanel.vue')
const { fixtureText } = await import('@/utils/__fixtures__/sbomScore/fixtures')

const HEAD = '44444444-4444-4444-8444-444444444444'
const A1 = '11111111-1111-4111-8111-111111111111'
const A2 = '22222222-2222-4222-8222-222222222222'
const M = '33333333-3333-4333-8333-333333333333'
const operation = (opts: any) => (opts.query ?? opts.mutation)?.definitions?.[0]?.name?.value

// The stored report and the fresh one: the engine's fda golden, the fresh one from a newer engine.
const STORED = fixtureText('fda.cdx')
const FRESH = JSON.stringify({ ...JSON.parse(STORED), engine: { ...JSON.parse(STORED).engine, version: '26.11.7' } })

const CHECK_PART = { part: 'CHECK_REPORT', requirement: 'REQUIRED', outcome: 'PRODUCED', artifact: A2, artifactVersion: '1',
    fileName: 'widget-check-report.json', mediaType: 'application/json', size: 10, sha256: 'b'.repeat(64) }
const v = (n: number, checked: boolean) => ({
    submission: HEAD, version: n, kind: 'FDA_PREMARKET', createdDate: '2026-11-0' + n + 'T00:00:00Z', createdBy: 'u1', reason: null,
    settings: { row: null, revision: 0, source: 'DEFAULT' }, sbomProfile: { name: 'default', row: null, revision: 0, source: 'DEFAULT' },
    manifestArtifact: M, manifestArtifactVersion: '1', manifestSha256: 'c'.repeat(64), manifest: '{}', confirmations: [],
    parts: [{ part: 'AGGREGATE_SBOM', requirement: 'REQUIRED', outcome: 'REFERENCED', artifact: A1, artifactVersion: '4',
        fileName: 'widget-sbom.cdx.json', mediaType: 'application/json', size: 10, sha256: 'a'.repeat(64) },
    ...(checked ? [CHECK_PART] : [])],
    checkSummary: checked ? { checkerVersion: 'rearm-cli 26.10.15', ranAt: '2026-11-02T00:00:00Z' } : null,
})
const HEADS = [{ uuid: HEAD, release: 'r1', kind: 'FDA_PREMARKET', state: 'FINAL', latestVersion: 2,
    lastAct: { kind: 'FINALIZE', actedAt: '2026-11-02T00:00:00Z', actorName: 'Ann' }, lastActDate: '2026-11-02T00:00:00Z', lastActBy: 'u1',
    versions: [v(1, false), v(2, true)] }]

let rerun: (vars: any) => Promise<any>
function answers () {
    query.mockImplementation(async (opts: any) => {
        switch (operation(opts)) {
        case 'ReleaseDrafts': return { data: { release: { uuid: 'r1', drafts: [] } } }
        case 'OrgSecondConfirmer': return { data: { organizations: [{ uuid: 'o1', settings: { submissionSecondConfirmer: false } }] } }
        case 'PackageKinds': return { data: { packageKinds: [{ kind: 'FDA_PREMARKET', parts: [] }] } }
        case 'SubmissionsOfRelease': return { data: { submissionsOfRelease: HEADS } }
        case 'SubmissionVersionCheckRerun': return rerun(opts.variables)
        default: throw new Error('unexpected query ' + operation(opts))
        }
    })
}

async function mountPanel (props: Record<string, any> = {}) {
    const w = mount(defineComponent({
        render: () => h(NNotificationProvider, () => h(Panel, { orgUuid: 'o1', releaseUuid: 'r1', releaseVersion: '1.0.0',
            lifecycle: 'ASSEMBLED', componentUuid: 'c1', componentName: 'widget', isWritable: false, installationType: 'SAAS', ...props })),
    }), { attachTo: document.body })
    await flushPromises()
    return w
}
const versions = (w: any) => w.find('[data-testid="submission-versions-FDA_PREMARKET"]')
const rerunButtons = (w: any) => versions(w).findAll('[data-testid="submission-check-rerun"]')
const rerunCalls = () => query.mock.calls.filter(([o]) => operation(o) === 'SubmissionVersionCheckRerun')
const byTestId = (id: string) => document.body.querySelector(`[data-testid="${id}"]`)

describe('ReleaseSubmissionsPanel: Re-run check (FDAW-16 3.9a)', () => {
    beforeEach(() => {
        query.mockReset()
        document.body.innerHTML = ''
        rerun = async () => ({ data: { submissionVersionCheckRerun: { storedChecker: 'rearm-cli 26.10.15', stored: STORED,
            freshChecker: 'rearm-cli 26.11.7', fresh: FRESH } } })
        answers()
    })

    it('the button is on the row of the version that keeps a check only, for a reader too, and sends nothing', async () => {
        const w = await mountPanel()
        expect(rerunButtons(w).map((b: any) => b.text())).toEqual(['Re-run check'])
        const rows = versions(w).findAll('tbody tr')
        // newest first: v2 keeps a check, v1 does not
        expect(rows.map((r: any) => [r.text().match(/^v\d/)?.[0], r.find('[data-testid="submission-check-rerun"]').exists()]))
            .toEqual([['v2', true], ['v1', false]])
        expect(rerunCalls()).toHaveLength(0)
        w.unmount()
    })

    it('a press asks once for that version and shows both reports under their checkers', async () => {
        const w = await mountPanel()
        await rerunButtons(w)[0].trigger('click')
        await flushPromises()
        expect(rerunCalls().map(([o]) => o.variables)).toEqual([{ submission: HEAD, version: 2 }])
        expect(byTestId('check-rerun-stored-heading')?.textContent).toBe('Stored (rearm-cli 26.10.15)')
        expect(byTestId('check-rerun-fresh-heading')?.textContent).toBe('Now (rearm-cli 26.11.7)')
        expect(byTestId('check-rerun-stored')?.querySelectorAll('.sbom-score-report')).toHaveLength(1)
        expect(byTestId('check-rerun-fresh')?.querySelectorAll('.sbom-score-report')).toHaveLength(1)
        w.unmount()
    })

    it('a refusal is shown in both panels as the export check shows it, and no report', async () => {
        rerun = async () => { throw Object.assign(new Error('Not authorized'), { graphQLErrors: [{ message: 'Not authorized' }] }) }
        const w = await mountPanel()
        await rerunButtons(w)[0].trigger('click')
        await flushPromises()
        for (const side of ['check-rerun-stored', 'check-rerun-fresh']) {
            expect(byTestId(side)?.querySelector('[data-testid="sbom-score-error"]')?.textContent?.trim())
                .toBe('You are not authorized to score this SBOM (it needs the permission to download it).')
            expect(byTestId(side)?.querySelectorAll('.sbom-score-report')).toHaveLength(0)
        }
        expect(byTestId('check-rerun-stored-heading')?.textContent).toBe('Stored')
        w.unmount()
    })
})
