// @vitest-environment happy-dom
//
// The product page's Submissions tab (task TEA-30, design 4.6 row 32): three releases of the feature
// set (an ASSEMBLED one with a FINAL head v2, a DRAFT one without, and, round 4, a
// GENERAL_AVAILABILITY one with a CANCELLED head) and a head on a release of another feature set; the
// actions only for a writer, Cancel only on a FINAL head, Create disabled below ASSEMBLED; the
// filters passed to the server; the release links; a failed read showing the refusal entry alone;
// the branches and refresh-token props.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import { routeLocationKey } from 'vue-router'
import { NNotificationProvider, NSelect } from 'naive-ui'

const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
vi.mock('sweetalert2', () => ({ default: { fire: vi.fn() } }))
vi.mock('@/utils/fetchClient', () => ({ fetchWithAuth: vi.fn(), fetchArrayBufferWithAuth: vi.fn() }))

const { default: Panel } = await import('./ProductSubmissionsPanel.vue')
const { expectSwept, UUID_RE } = await import('@/utils/__fixtures__/fdaSweep')

const operation = (opts: any) => (opts.query ?? opts.mutation)?.definitions?.[0]?.name?.value
const calls = (name: string) => query.mock.calls.filter(c => operation(c[0]) === name)
const BRANCHES = [{ uuid: 'b-main', name: 'main', key: 'b-main' }, { uuid: 'b-feat', name: 'feature', key: 'b-feat' }]
const RELEASES = [
    { uuid: 'r1', version: '1.0.0', lifecycle: 'ASSEMBLED', createdDate: '2026-10-01T00:00:00Z' },
    { uuid: 'r2', version: '1.1.0', lifecycle: 'DRAFT', createdDate: '2026-10-05T00:00:00Z' },
    { uuid: 'r3', version: '0.8.0', lifecycle: 'GENERAL_AVAILABILITY', createdDate: '2026-09-15T00:00:00Z' },
]
const HEADS = [
    { uuid: 's1', release: 'r1', releaseVersion: '1.0.0', kind: 'FDA_PREMARKET', state: 'FINAL', latestVersion: 2,
        lastAct: { kind: 'FINALIZE', actedAt: '2026-10-02T00:00:00Z', actorName: 'Ann' }, lastActDate: '2026-10-02T00:00:00Z' },
    { uuid: 's3', release: 'r3', releaseVersion: '0.8.0', kind: 'FDA_PREMARKET', state: 'CANCELLED', latestVersion: 3,
        lastAct: { kind: 'CANCEL', actedAt: '2026-09-20T00:00:00Z', actorName: 'Ann' }, lastActDate: '2026-09-20T00:00:00Z' },
    { uuid: 's9', release: 'r-old', releaseVersion: '0.9.0', kind: 'FDA_PREMARKET', state: 'CANCELLED', latestVersion: 1,
        lastAct: { kind: 'CANCEL', actedAt: '2026-09-01T00:00:00Z', actorName: 'Ann' }, lastActDate: '2026-09-01T00:00:00Z', legacyState: 'WITHDRAWN' },
]
const NULL_TEXT = "The field at path '/submissionsOfComponent' was declared as a non null type, but the code involved "
    + 'in retrieving data has wrongly returned a null value.  The graphql specification requires that the parent '
    + 'field be set to null, or if that is non nullable that it bubble up null to its parent and so on. The '
    + "non-nullable type is '[Submission!]' within parent type 'Query'"
let failing = false
// The product's switched-on kinds (Component.regulatoryPackageKinds, FDAW-4): on by default, as today.
let enabledKinds: string[] = ['FDA_PREMARKET']
let kindsFailing = false
const ROUTE = { path: '/componentsOfOrg/o1/c1', query: { tab: 'submissions' }, params: {}, name: 'ComponentsOfOrg' }

function answers () {
    query.mockImplementation(async (opts: any) => {
        if (failing) {
            throw Object.assign(new Error('submissions read refused\n' + NULL_TEXT), { errors: [
                { message: 'submissions read refused', extensions: { classification: 'BAD_REQUEST' } },
                { message: NULL_TEXT, path: ['submissionsOfComponent'], extensions: { classification: 'NullValueInNonNullableField' } },
            ] })
        }
        switch (operation(opts)) {
        case 'PackageKinds': return { data: { packageKinds: [{ kind: 'FDA_PREMARKET', parts: [] }] } }
        case 'SubmissionsOfComponent': return { data: { submissionsOfComponent: HEADS } }
        case 'SubmissionReleasesOfBranch': return { data: { releases: RELEASES } }
        case 'ComponentRegulatoryPackageKinds':
            if (kindsFailing) throw new Error('kinds read refused')
            return { data: { component: { uuid: 'c1', regulatoryPackageKinds: enabledKinds } } }
        default: throw new Error('unexpected query ' + operation(opts))
        }
    })
}

async function mountPanel (props: Record<string, any> = {}) {
    const branches = ref<any[]>(props.branches ?? BRANCHES)
    const token = ref(0)
    const w = mount(defineComponent({
        render: () => h(NNotificationProvider, () => h(Panel, { orgUuid: 'o1', componentUuid: 'c1', componentName: 'widget',
            isWritable: true, installationType: 'SAAS', ...props, branches: branches.value, refreshToken: token.value })),
    }), { attachTo: document.body, global: { stubs: { RouterLink: RouterLinkStub }, provide: { [routeLocationKey as symbol]: ROUTE } } })
    await flushPromises()
    return { w, branches, token }
}

const rowOf = (w: any, version: string) => w.findAll('[data-testid="submissions-release-rows"] tbody tr')
    .find((r: any) => r.find('td').text() === version)
const select = (w: any, testid: string) =>
    w.findAllComponents(NSelect).find((s: any) => s.attributes('data-testid') === testid)

describe('ProductSubmissionsPanel', () => {
    beforeEach(() => {
        query.mockReset()
        failing = false
        enabledKinds = ['FDA_PREMARKET']
        kindsFailing = false
        answers()
    })

    it('shows the head with no button for a writer, and the lifecycle reason, no link, on a DRAFT (FDAW-32 3.8)', async () => {
        const { w } = await mountPanel()
        const assembled = rowOf(w, '1.0.0')
        expect(assembled.find('[data-testid="submission-state"]').text()).toBe('Final (no confirmation record) - not sent')
        expect(assembled.text()).toContain('v2')
        expect(assembled.find('[data-testid="submission-cell-FDA_PREMARKET"]').findAll('button')).toHaveLength(0)
        expect(assembled.find('[data-testid="submission-draft-link"]').exists()).toBe(false)
        const draft = rowOf(w, '1.1.0')
        expect(draft.find('[data-testid="submission-draft-link"]').exists()).toBe(false)
        expect(draft.findAll('td')[3].text()).toBe('The release must be Assembled or later')
        const rows = w.findAll('[data-testid="submissions-release-rows"] tbody tr')
        expect(rows.map((r: any) => r.find('td').text())).toEqual(['1.1.0', '1.0.0', '0.8.0'])
        w.unmount()
    })

    it('a CANCELLED head and a FINAL head show their cells with no button and no link (FDAW-32 3.8)', async () => {
        const { w } = await mountPanel()
        const cell = rowOf(w, '0.8.0').find('[data-testid="submission-cell-FDA_PREMARKET"]')
        expect(cell.exists()).toBe(true)
        expect(cell.find('[data-testid="submission-state"]').text()).toBe('Cancelled')
        expect(cell.text()).toContain('v3')
        expect(cell.findAll('button')).toHaveLength(0)
        expect(cell.find('[data-testid="submission-draft-link"]').exists()).toBe(false)
        const active = rowOf(w, '1.0.0').find('[data-testid="submission-cell-FDA_PREMARKET"]')
        expect(active.findAll('button')).toHaveLength(0)
        expect(active.find('[data-testid="submission-draft-link"]').exists()).toBe(false)
        w.unmount()
    })

    it('offers no action button and no draft link to a reader', async () => {
        const { w } = await mountPanel({ isWritable: false })
        for (const id of ['submission-create', 'submission-resubmit', 'submission-withdraw', 'submission-draft-link']) {
            expect(w.find(`[data-testid="${id}"]`).exists(), id).toBe(false)
        }
        expect(rowOf(w, '1.0.0').find('[data-testid="submission-state"]').exists()).toBe(true)
        w.unmount()
    })

    it('lists the head of another feature set with its release version, and links releases to the release page', async () => {
        const { w } = await mountPanel()
        const other = w.find('[data-testid="submissions-other-rows"]')
        expect(other.text()).toContain('0.9.0')
        expect(other.text()).toContain('Cancelled')
        const links = w.findAllComponents(RouterLinkStub)
        expect(links.map((l: any) => l.props('to'))).toContainEqual({ name: 'ReleaseView', params: { uuid: 'r1' } })
        expect(links.map((l: any) => l.props('to'))).toContainEqual({ name: 'ReleaseView', params: { uuid: 'r-old' } })
        w.unmount()
    })

    it('passes the kind filter to the server and never the state (FDAW-63)', async () => {
        const { w } = await mountPanel()
        expect(calls('SubmissionsOfComponent')[0][0].variables).toEqual({ component: 'c1', kind: null, state: null })
        expect(calls('SubmissionReleasesOfBranch')[0][0].variables).toEqual({ branch: 'b-main', numRecords: 50 })
        await select(w, 'submissions-kind').vm.$emit('update:value', 'FDA_PREMARKET')
        await flushPromises()
        expect(calls('SubmissionsOfComponent').at(-1)![0].variables).toEqual({ component: 'c1', kind: 'FDA_PREMARKET', state: null })
        const before = calls('SubmissionsOfComponent').length
        await select(w, 'submissions-state').vm.$emit('update:value', 'CANCELLED')
        await flushPromises()
        expect(calls('SubmissionsOfComponent').at(-1)![0].variables).toEqual({ component: 'c1', kind: 'FDA_PREMARKET', state: null })
        expect(calls('SubmissionsOfComponent')).toHaveLength(before + 1)
        expect(calls('SubmissionsOfComponent').every(c => c[0].variables.state === null)).toBe(true)
        await select(w, 'submissions-kind').vm.$emit('update:value', '')
        await flushPromises()
        expect(calls('SubmissionsOfComponent').at(-1)![0].variables.kind).toBeNull()
        w.unmount()
    })

    it('shows a failed read as a notification and Retry', async () => {
        failing = true
        const { w } = await mountPanel()
        expect(document.body.textContent).toContain('submissions read refused')
        expect(document.body.textContent).not.toContain('declared as a non null type')
        expect(w.find('[data-testid="submissions-retry"]').exists()).toBe(true)
        w.unmount()
    })

    it('reads nothing until the branches arrive, then selects the first; a token bump re-reads, keeping or falling back', async () => {
        const { w, branches, token } = await mountPanel({ branches: [] })
        expect(calls('SubmissionReleasesOfBranch')).toHaveLength(0)
        expect(query).not.toHaveBeenCalled()
        branches.value = BRANCHES
        await flushPromises()
        expect(calls('SubmissionReleasesOfBranch')).toHaveLength(1)
        expect(calls('SubmissionReleasesOfBranch')[0][0].variables.branch).toBe('b-main')

        await select(w, 'submissions-branch').vm.$emit('update:value', 'b-feat')
        await flushPromises()
        expect(calls('SubmissionReleasesOfBranch').at(-1)![0].variables.branch).toBe('b-feat')
        const before = calls('SubmissionReleasesOfBranch').length
        token.value++
        await flushPromises()
        expect(calls('SubmissionReleasesOfBranch')).toHaveLength(before + 1)
        expect(calls('SubmissionReleasesOfBranch').at(-1)![0].variables.branch).toBe('b-feat')
        expect(calls('PackageKinds').length).toBeGreaterThanOrEqual(3)

        branches.value = [BRANCHES[0]]
        await flushPromises()
        token.value++
        await flushPromises()
        expect(calls('SubmissionReleasesOfBranch').at(-1)![0].variables.branch).toBe('b-main')
        w.unmount()
    })
})

// Task FDAW-63 (ARCHITECTURE r2 section 4, rows 2-5, 7 and 9): the panel reads every head (state null) and applies
// the state filter itself; a head the filter hides shows the fixed "Not in filter" marker with no action button,
// a release with no head of the kind keeps the no-head cell (since FDAW-32: the draft link or the lifecycle
// reason, No submission, or not set up).
describe('ProductSubmissionsPanel: state filter (FDAW-63)', () => {
    const ACT_IDS = ['submission-draft-link']
    const filterBy = async (w: any, state: string) => {
        await select(w, 'submissions-state').vm.$emit('update:value', state)
        await flushPromises()
    }
    const marker = (row: any) => row.find('[data-testid="submission-filtered-FDA_PREMARKET"]')
    const expectMarkerOnly = (row: any) => {
        expect(marker(row).exists()).toBe(true)
        expect(marker(row).text()).toBe('Not in filter')
        for (const id of [...ACT_IDS, 'submission-state', 'submission-cell-FDA_PREMARKET']) {
            expect(row.find(`[data-testid="${id}"]`).exists(), id).toBe(false)
        }
    }

    beforeEach(() => {
        query.mockReset()
        failing = false
        enabledKinds = ['FDA_PREMARKET']
        kindsFailing = false
        answers()
    })

    it('row 2: under Cancelled, the cancelled head shows, the filtered-out head shows the marker, the no-head release keeps its cell', async () => {
        const { w } = await mountPanel()
        await filterBy(w, 'CANCELLED')
        const cancelled = rowOf(w, '0.8.0')
        const cell = cancelled.find('[data-testid="submission-cell-FDA_PREMARKET"]')
        expect(cell.exists()).toBe(true)
        expect(cell.find('[data-testid="submission-state"]').text()).toBe('Cancelled')
        expect(cell.findAll('button')).toHaveLength(0)
        expect(marker(cancelled).exists()).toBe(false)
        expectMarkerOnly(rowOf(w, '1.0.0'))
        const draft = rowOf(w, '1.1.0')
        expect(draft.findAll('td')[3].text()).toBe('The release must be Assembled or later')
        expect(draft.find('[data-testid="submission-draft-link"]').exists()).toBe(false)
        expect(marker(draft).exists()).toBe(false)
        expect(w.find('[data-testid="submissions-none"]').exists()).toBe(false)
        w.unmount()
    })

    it('row 3: under Final the other way round, the other feature set\'s cancelled head is not listed; All shows every head again', async () => {
        const { w } = await mountPanel()
        await filterBy(w, 'FINAL')
        const final = rowOf(w, '1.0.0').find('[data-testid="submission-cell-FDA_PREMARKET"]')
        expect(final.exists()).toBe(true)
        expect(final.findAll('button')).toHaveLength(0)
        expectMarkerOnly(rowOf(w, '0.8.0'))
        expect(rowOf(w, '1.1.0').findAll('td')[3].text()).toBe('The release must be Assembled or later')
        expect(w.find('[data-testid="submissions-other-rows"]').exists()).toBe(false)
        expect(w.find('[data-testid="submissions-other-none"]').text()).toBe('None')

        await filterBy(w, '')
        const assembled = rowOf(w, '1.0.0')
        expect(assembled.find('[data-testid="submission-state"]').text()).toBe('Final (no confirmation record) - not sent')
        expect(assembled.text()).toContain('v2')
        expect(assembled.findAll('button')).toHaveLength(0)
        expect(assembled.find('[data-testid="submission-draft-link"]').exists()).toBe(false)
        expect(rowOf(w, '1.1.0').findAll('td')[3].text()).toBe('The release must be Assembled or later')
        expect(rowOf(w, '1.1.0').find('[data-testid="submission-draft-link"]').exists()).toBe(false)
        expect(rowOf(w, '0.8.0').find('[data-testid="submission-state"]').text()).toBe('Cancelled')
        const rows = w.findAll('[data-testid="submissions-release-rows"] tbody tr')
        expect(rows.map((r: any) => r.find('td').text())).toEqual(['1.1.0', '1.0.0', '0.8.0'])
        expect(w.find('[data-testid^="submission-filtered-"]').exists()).toBe(false)
        expect(w.find('[data-testid="submissions-other-rows"]').text()).toContain('0.9.0')
        w.unmount()
    })

    it('row 4: a reader sees the marker on a filtered-out head, No submission only where there is no head, and no button', async () => {
        const { w } = await mountPanel({ isWritable: false })
        await filterBy(w, 'CANCELLED')
        const assembled = rowOf(w, '1.0.0')
        expect(marker(assembled).text()).toBe('Not in filter')
        expect(assembled.text()).not.toContain('No submission')
        expect(rowOf(w, '1.1.0').text()).toContain('No submission')
        for (const id of ACT_IDS) expect(w.find(`[data-testid="${id}"]`).exists(), id).toBe(false)
        w.unmount()
    })

    it('row 5: on a switched-off kind the filtered-out head shows the marker, the no-head release Not set up', async () => {
        enabledKinds = []
        const { w } = await mountPanel({ canConfigure: true })
        await filterBy(w, 'CANCELLED')
        const assembled = rowOf(w, '1.0.0')
        expect(marker(assembled).text()).toBe('Not in filter')
        expect(assembled.find('[data-testid="submission-not-set-up-FDA_PREMARKET"]').exists()).toBe(false)
        expect(rowOf(w, '1.1.0').find('[data-testid="submission-not-set-up-FDA_PREMARKET"]').exists()).toBe(true)
        w.unmount()
    })

    it('row 7: a filter matching no head shows markers and no empty line; with no head at all the empty line stays', async () => {
        const { w } = await mountPanel()
        await filterBy(w, 'SUPERSEDED')
        expectMarkerOnly(rowOf(w, '1.0.0'))
        expectMarkerOnly(rowOf(w, '0.8.0'))
        expect(rowOf(w, '1.1.0').findAll('td')[3].text()).toBe('The release must be Assembled or later')
        expect(w.find('[data-testid="submissions-none"]').exists()).toBe(false)
        w.unmount()

        query.mockImplementation(async (opts: any) => {
            switch (operation(opts)) {
            case 'PackageKinds': return { data: { packageKinds: [{ kind: 'FDA_PREMARKET', parts: [] }] } }
            case 'SubmissionsOfComponent': return { data: { submissionsOfComponent: [] } }
            case 'SubmissionReleasesOfBranch': return { data: { releases: RELEASES } }
            case 'ComponentRegulatoryPackageKinds': return { data: { component: { uuid: 'c1', regulatoryPackageKinds: ['FDA_PREMARKET'] } } }
            default: throw new Error('unexpected query ' + operation(opts))
            }
        })
        const empty = await mountPanel()
        await filterBy(empty.w, 'CANCELLED')
        expect(empty.w.find('[data-testid="submissions-none"]').text())
            .toBe('No submission yet; start a draft on a release that is Assembled or later')
        expect(rowOf(empty.w, '1.1.0').findAll('td')[3].text()).toBe('The release must be Assembled or later')
        empty.w.unmount()
    })

    it('row 9: a release at ASSEMBLED or above with no head keeps the draft link under every filter', async () => {
        query.mockImplementation(async (opts: any) => {
            switch (operation(opts)) {
            case 'PackageKinds': return { data: { packageKinds: [{ kind: 'FDA_PREMARKET', parts: [] }] } }
            case 'SubmissionsOfComponent': return { data: { submissionsOfComponent: HEADS } }
            case 'SubmissionReleasesOfBranch': return { data: { releases: [...RELEASES,
                { uuid: 'r4', version: '1.2.0', lifecycle: 'READY_TO_SHIP', createdDate: '2026-10-08T00:00:00Z' }] } }
            case 'ComponentRegulatoryPackageKinds': return { data: { component: { uuid: 'c1', regulatoryPackageKinds: ['FDA_PREMARKET'] } } }
            default: throw new Error('unexpected query ' + operation(opts))
            }
        })
        const { w } = await mountPanel()
        const expectEnabledCreate = (label: string) => {
            const row = rowOf(w, '1.2.0')
            const link = row.findComponent(RouterLinkStub)
            expect(row.find('[data-testid="submission-draft-link"]').exists(), label).toBe(true)
            expect(link.props('to'), label).toEqual({ name: 'ReleaseView', params: { uuid: 'r4' } })
            expect(marker(row).exists(), label).toBe(false)
        }
        expectEnabledCreate('All')
        await filterBy(w, 'CANCELLED')
        expectEnabledCreate('CANCELLED')
        expect(marker(rowOf(w, '1.0.0')).exists()).toBe(true)
        await filterBy(w, 'FINAL')
        expectEnabledCreate('FINAL')
        w.unmount()
    })
})

// Task FDAW-4 (B18, T6, T7): the draft link (FDAW-32; Create before it) only for a kind switched on for the product; otherwise what to set
// up, with a link to the settings modal's pane. FDAW-18 (row 20): the link opens the Medical device pane.
describe('ProductSubmissionsPanel: a kind that is not set up (FDAW-4)', () => {
    const SETUP_TO = { path: ROUTE.path, query: { tab: 'submissions', componentSettingsView: 'true', componentSettingsTab: 'device' } }
    const setupLinks = (w: any) => w.findAllComponents(RouterLinkStub)
        .filter((l: any) => l.attributes('data-testid') === 'submission-setup-link')

    beforeEach(() => {
        query.mockReset()
        failing = false
        enabledKinds = ['FDA_PREMARKET']
        kindsFailing = false
        answers()
    })

    it('T6: switched off, a writer gets no draft link but Not set up with the Medical device link', async () => {
        enabledKinds = []
        const { w } = await mountPanel({ canConfigure: true })
        const assembled = rowOf(w, '1.1.0')
        expect(assembled.find('[data-testid="submission-draft-link"]').exists()).toBe(false)
        expect(w.find('[data-testid="submission-draft-link"]').exists()).toBe(false)
        const cell = assembled.find('[data-testid="submission-not-set-up-FDA_PREMARKET"]')
        expect(cell.exists()).toBe(true)
        expect(cell.text().replace(/\s+/g, ' ')).toBe('Not set up: FDA premarket is switched off for this product. '
            + 'Set it up under Settings > Medical device')
        const link = setupLinks(w).find((l: any) => cell.element.contains(l.element))!
        expect(link.props('to')).toEqual(SETUP_TO)
        expect(link.text()).toBe('Set it up under Settings > Medical device')
        w.unmount()
    })

    it('T6: a head on a switched-off kind keeps its cell, with no button', async () => {
        enabledKinds = []
        const { w } = await mountPanel({ canConfigure: true })
        const active = rowOf(w, '1.0.0').find('[data-testid="submission-cell-FDA_PREMARKET"]')
        expect(active.exists()).toBe(true)
        expect(active.find('[data-testid="submission-state"]').exists()).toBe(true)
        expect(active.findAll('button')).toHaveLength(0)
        w.unmount()
    })

    it('T6: for a non-administrator the sentence names the administrator, link kept', async () => {
        enabledKinds = []
        const { w } = await mountPanel({ canConfigure: false })
        const cell = rowOf(w, '1.1.0').find('[data-testid="submission-not-set-up-FDA_PREMARKET"]')
        expect(cell.text()).toContain('(the organization steps need an organization administrator)')
        expect(setupLinks(w).some((l: any) => cell.element.contains(l.element))).toBe(true)
        w.unmount()
    })

    it('T6: switched on, the no-head cell is offered as before (the lifecycle reason on a DRAFT); a reader sees No submission either way', async () => {
        const { w } = await mountPanel({ canConfigure: true })
        expect(rowOf(w, '1.1.0').findAll('td')[3].text()).toBe('The release must be Assembled or later')
        expect(w.find('[data-testid^="submission-not-set-up-"]').exists()).toBe(false)
        w.unmount()
        enabledKinds = []
        const reader = await mountPanel({ isWritable: false })
        const cell = rowOf(reader.w, '1.1.0')
        expect(cell.text()).toContain('No submission')
        expect(reader.w.find('[data-testid^="submission-not-set-up-"]').exists()).toBe(false)
        reader.w.unmount()
    })

    it('T6: with no head and no kind on, the empty line says what to set up, with the same link', async () => {
        enabledKinds = []
        query.mockImplementation(async (opts: any) => {
            switch (operation(opts)) {
            case 'PackageKinds': return { data: { packageKinds: [{ kind: 'FDA_PREMARKET', parts: [] }] } }
            case 'SubmissionsOfComponent': return { data: { submissionsOfComponent: [] } }
            case 'SubmissionReleasesOfBranch': return { data: { releases: RELEASES } }
            case 'ComponentRegulatoryPackageKinds': return { data: { component: { uuid: 'c1', regulatoryPackageKinds: [] } } }
            default: throw new Error('unexpected query ' + operation(opts))
            }
        })
        const { w } = await mountPanel({ canConfigure: false })
        const none = w.find('[data-testid="submissions-none"]')
        expect(none.text().replace(/\s+/g, ' ')).toBe('No submission yet. widget is not set up for FDA premarket submissions: '
            + 'set it up under Settings > Medical device (one save). (the organization steps need an organization administrator)')
        const link = setupLinks(w).find((l: any) => none.element.contains(l.element))!
        expect(link.props('to')).toEqual(SETUP_TO)
        w.unmount()
    })

    it('T7: the kinds read goes out with the product uuid beside the three reads, and again on a token bump', async () => {
        const { w, token } = await mountPanel()
        expect(calls('ComponentRegulatoryPackageKinds')).toHaveLength(1)
        expect(calls('ComponentRegulatoryPackageKinds')[0][0].variables).toEqual({ componentUuid: 'c1' })
        expect(calls('ComponentRegulatoryPackageKinds')[0][0].fetchPolicy).toBe('no-cache')
        expect(calls('PackageKinds')).toHaveLength(1)
        expect(calls('SubmissionsOfComponent')).toHaveLength(1)
        expect(calls('SubmissionReleasesOfBranch')).toHaveLength(1)
        token.value++
        await flushPromises()
        expect(calls('ComponentRegulatoryPackageKinds')).toHaveLength(2)
        w.unmount()
    })

    it('T7: a failed kinds read leaves today\'s cells (the no-head cell offered) and logs', async () => {
        kindsFailing = true
        const error = vi.spyOn(console, 'error').mockImplementation(() => {})
        const { w } = await mountPanel()
        expect(rowOf(w, '1.1.0').findAll('td')[3].text()).toBe('The release must be Assembled or later')
        expect(w.find('[data-testid^="submission-not-set-up-"]').exists()).toBe(false)
        expect(error.mock.calls.some(c => String(c[0]?.message ?? c[0]).includes('kinds read refused'))).toBe(true)
        error.mockRestore()
        w.unmount()
    })
})


/** FDAW-78 row 15 (REQ-67): the product Submissions list in words: lifecycles, the empty line, link texts. */
describe('ProductSubmissionsPanel in words (FDAW-78 row 15)', () => {
    const UUID = '3bef7cde-2edb-47cb-aa4e-94487c2cdced'
    const LIFE_RELEASES = [
        { uuid: 'l1', version: '4.0.0', lifecycle: 'ASSEMBLED', createdDate: '2026-10-04T00:00:00Z' },
        { uuid: 'l2', version: '3.0.0', lifecycle: 'GENERAL_AVAILABILITY', createdDate: '2026-10-03T00:00:00Z' },
        { uuid: 'l3', version: '2.0.0', lifecycle: 'DRAFT', createdDate: '2026-10-02T00:00:00Z' },
        { uuid: 'l4', version: '1.0.0', lifecycle: 'SUNSET_REVIEW', createdDate: '2026-10-01T00:00:00Z' },
    ]
    let heads: any[] = []

    beforeEach(() => {
        query.mockReset()
        query.mockImplementation(async (opts: any) => {
            switch (operation(opts)) {
            case 'PackageKinds': return { data: { packageKinds: [{ kind: 'FDA_PREMARKET', parts: [] }] } }
            case 'SubmissionsOfComponent': return { data: { submissionsOfComponent: heads } }
            case 'SubmissionReleasesOfBranch': return { data: { releases: LIFE_RELEASES } }
            case 'ComponentRegulatoryPackageKinds': return { data: { component: { uuid: 'c1', regulatoryPackageKinds: ['FDA_PREMARKET'] } } }
            default: throw new Error('unexpected query ' + operation(opts))
            }
        })
    })

    it('the empty line, the lifecycles in words in order, the lifecycle reason, and swept', async () => {
        heads = []
        const { w } = await mountPanel()
        expect(w.find('[data-testid="submissions-none"]').text())
            .toBe('No submission yet; start a draft on a release that is Assembled or later')
        const rows = w.findAll('[data-testid="submissions-release-rows"] tbody tr')
        expect(rows.map((r: any) => r.findAll('td')[1].text())).toEqual(['Assembled', 'Shipped', 'Draft', 'Sunset review'])
        expect(rowOf(w, '2.0.0').findAll('td')[3].text()).toBe('The release must be Assembled or later')
        expectSwept((w.element.parentElement ?? document.body).textContent ?? '', 'product panel')
        w.unmount()
    })

    it('a head on another feature set\'s release with no version reads release as its link text, never the uuid', async () => {
        heads = [{ uuid: 's1', release: UUID, releaseVersion: '', kind: 'FDA_PREMARKET', state: 'FINAL', latestVersion: 1,
            lastAct: { kind: 'FINALIZE', actedAt: '2026-10-02T00:00:00Z', actorName: 'Ann' }, lastActDate: '2026-10-02T00:00:00Z' }]
        const { w } = await mountPanel()
        const other = w.find('[data-testid="submissions-other-rows"]')
        const link = other.find('[data-testid="submission-release-link"]')
        expect(link.text()).toBe('release')
        expect(other.text()).not.toMatch(UUID_RE)
        expect(UUID).toMatch(UUID_RE)
        expectSwept(other.text(), 'other feature sets')
        w.unmount()
    })
})

// Task FDAW-32 (architecture round 2, 3.8, row 9): the product tab no longer acts. A release without a
// version of a kind links to its release page, after the filter marker and the not-set-up cell; a head's
// cell is its tag, version and last act in words, and nothing else.
describe('ProductSubmissionsPanel offers no act (FDAW-32 row 9)', () => {
    const R4 = { uuid: 'r4', version: '1.2.0', lifecycle: 'READY_TO_SHIP', createdDate: '2026-10-08T00:00:00Z' }
    const LIFE_RELEASES = [
        { uuid: 'l1', version: '4.0.0', lifecycle: 'ASSEMBLED', createdDate: '2026-10-04T00:00:00Z' },
        { uuid: 'l3', version: '2.0.0', lifecycle: 'DRAFT', createdDate: '2026-10-02T00:00:00Z' },
    ]
    const OTHER_HEAD = { uuid: 's5', release: '3bef7cde-2edb-47cb-aa4e-94487c2cdced', releaseVersion: '', kind: 'FDA_PREMARKET',
        state: 'FINAL', latestVersion: 1, lastAct: { kind: 'FINALIZE', actedAt: '2026-10-02T00:00:00Z', actorName: 'Ann' },
        lastActDate: '2026-10-02T00:00:00Z' }
    const serve = (releases: any[], heads: any[]) => {
        query.mockReset()
        query.mockImplementation(async (opts: any) => {
            switch (operation(opts)) {
            case 'PackageKinds': return { data: { packageKinds: [{ kind: 'FDA_PREMARKET', parts: [] }] } }
            case 'SubmissionsOfComponent': return { data: { submissionsOfComponent: heads } }
            case 'SubmissionReleasesOfBranch': return { data: { releases } }
            case 'ComponentRegulatoryPackageKinds': return { data: { component: { uuid: 'c1', regulatoryPackageKinds: ['FDA_PREMARKET'] } } }
            default: throw new Error('unexpected query ' + operation(opts))
            }
        })
    }
    const FIXTURES: [string, any[], any[]][] = [
        ['the feature set with a release to start', [...RELEASES, R4], HEADS],
        ['lifecycles, no head', LIFE_RELEASES, []],
        ['lifecycles, a head on another feature set', LIFE_RELEASES, [OTHER_HEAD]],
    ]

    beforeEach(() => {
        mutate.mockReset()
        failing = false
        enabledKinds = ['FDA_PREMARKET']
        kindsFailing = false
    })

    it('(a) over every fixture, as a writer: no act button anywhere and no button in the table; the link is the control', async () => {
        for (const [label, releases, heads] of FIXTURES) {
            serve(releases, heads)
            const { w } = await mountPanel()
            for (const id of ['submission-create', 'submission-resubmit', 'submission-withdraw']) {
                expect(w.find(`[data-testid="${id}"]`).exists(), label + ' ' + id).toBe(false)
            }
            expect(w.find('[data-testid="submissions-release-rows"]').findAll('button'), label).toHaveLength(0)
            expect(w.find('[data-testid="submission-draft-link"]').exists(), label).toBe(true)
            w.unmount()
        }
    })

    it('(b) an ASSEMBLED release without a head links to its release page; a DRAFT one reads the lifecycle; a reader No submission', async () => {
        serve(LIFE_RELEASES, [])
        let { w } = await mountPanel()
        const link = rowOf(w, '4.0.0').findComponent(RouterLinkStub)
        expect(rowOf(w, '4.0.0').find('[data-testid="submission-draft-link"]').text()).toBe('Draft on the release page')
        expect(link.props('to')).toEqual({ name: 'ReleaseView', params: { uuid: 'l1' } })
        const draft = rowOf(w, '2.0.0')
        expect(draft.findAll('td')[3].text()).toBe('The release must be Assembled or later')
        expect(draft.find('[data-testid="submission-draft-link"]').exists()).toBe(false)
        w.unmount();
        ({ w } = await mountPanel({ isWritable: false }))
        expect(rowOf(w, '4.0.0').findAll('td')[3].text()).toBe('No submission')
        expect(w.find('[data-testid="submission-draft-link"]').exists()).toBe(false)
        w.unmount()
    })

    it('(c) a filtered-out head is the marker with no link; the release to start keeps its link under every filter', async () => {
        serve([...RELEASES, R4], HEADS)
        const { w } = await mountPanel()
        for (const state of ['CANCELLED', 'FINAL', 'SUPERSEDED']) {
            await select(w, 'submissions-state').vm.$emit('update:value', state)
            await flushPromises()
            const hidden = rowOf(w, state === 'FINAL' ? '0.8.0' : '1.0.0')
            expect(hidden.find('[data-testid="submission-filtered-FDA_PREMARKET"]').exists(), state).toBe(true)
            expect(hidden.find('[data-testid="submission-draft-link"]').exists(), state).toBe(false)
            expect(rowOf(w, '1.2.0').find('[data-testid="submission-draft-link"]').exists(), state).toBe(true)
        }
        w.unmount()
    })

    it('(d) a switched-off kind shows the not-set-up cell and no link', async () => {
        serve([...RELEASES, R4], HEADS)
        enabledKinds = []
        query.mockImplementation(async (opts: any) => {
            switch (operation(opts)) {
            case 'PackageKinds': return { data: { packageKinds: [{ kind: 'FDA_PREMARKET', parts: [] }] } }
            case 'SubmissionsOfComponent': return { data: { submissionsOfComponent: HEADS } }
            case 'SubmissionReleasesOfBranch': return { data: { releases: [...RELEASES, R4] } }
            case 'ComponentRegulatoryPackageKinds': return { data: { component: { uuid: 'c1', regulatoryPackageKinds: [] } } }
            default: throw new Error('unexpected query ' + operation(opts))
            }
        })
        const { w } = await mountPanel({ canConfigure: true })
        expect(rowOf(w, '1.2.0').find('[data-testid="submission-not-set-up-FDA_PREMARKET"]').exists()).toBe(true)
        expect(w.find('[data-testid="submission-draft-link"]').exists()).toBe(false)
        w.unmount()
    })

    it('(e) a FINAL head whose newest version holds a confirmation reads Final - not sent and Finalized; the read is as today', async () => {
        const head = { ...HEADS[0], latest: { version: 2, confirmations: [{ confirmedAt: 'x' }] },
            lastAct: { kind: 'FINALIZE', actedAt: '2026-10-02T00:00:00Z', actorName: 'Ann' } }
        serve(RELEASES, [head])
        const { w } = await mountPanel()
        const cell = rowOf(w, '1.0.0').find('[data-testid="submission-cell-FDA_PREMARKET"]')
        expect(cell.find('[data-testid="submission-state"]').text()).toBe('Final - not sent')
        expect(cell.text()).toContain('Finalized')
        expect(calls('SubmissionsOfComponent')[0][0].variables).toEqual({ component: 'c1', kind: null, state: null })
        w.unmount()
    })

    it('(f) with no head at all the empty line says to start a draft', async () => {
        serve(RELEASES, [])
        const { w } = await mountPanel()
        expect(w.find('[data-testid="submissions-none"]').text())
            .toBe('No submission yet; start a draft on a release that is Assembled or later')
        w.unmount()
    })

    it('(g) the panel reads and never writes: no mutate after a click on every clickable element of the table', async () => {
        serve([...RELEASES, R4], HEADS)
        const { w } = await mountPanel()
        const table = w.find('[data-testid="submissions-release-rows"]')
        const clickable = table.findAll('a, button, [data-testid], [role="button"], td')
        expect(clickable.length).toBeGreaterThan(0)
        for (const el of clickable) await el.trigger('click')
        await flushPromises()
        expect(mutate.mock.calls).toEqual([])
        expect(query).toHaveBeenCalled()
        w.unmount()
    })
})
