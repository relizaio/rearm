// @vitest-environment happy-dom
//
// The product page's Submissions tab (task TEA-30, design 4.6 row 32): three releases of the feature
// set (an ASSEMBLED one with an ACTIVE head v2, a DRAFT one without, and, round 4, a
// GENERAL_AVAILABILITY one with a WITHDRAWN head) and a head on a release of another feature set; the
// actions only for a writer, Withdraw only on an ACTIVE head, Create disabled below ASSEMBLED; the
// filters passed to the server; the release links; a failed read showing the refusal entry alone;
// the branches and refresh-token props.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import { NNotificationProvider, NSelect } from 'naive-ui'

const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
vi.mock('sweetalert2', () => ({ default: { fire: vi.fn() } }))
vi.mock('@/utils/fetchClient', () => ({ fetchWithAuth: vi.fn(), fetchArrayBufferWithAuth: vi.fn() }))

const { default: Panel } = await import('./ProductSubmissionsPanel.vue')

const operation = (opts: any) => (opts.query ?? opts.mutation)?.definitions?.[0]?.name?.value
const calls = (name: string) => query.mock.calls.filter(c => operation(c[0]) === name)
const BRANCHES = [{ uuid: 'b-main', name: 'main', key: 'b-main' }, { uuid: 'b-feat', name: 'feature', key: 'b-feat' }]
const RELEASES = [
    { uuid: 'r1', version: '1.0.0', lifecycle: 'ASSEMBLED', createdDate: '2026-10-01T00:00:00Z' },
    { uuid: 'r2', version: '1.1.0', lifecycle: 'DRAFT', createdDate: '2026-10-05T00:00:00Z' },
    { uuid: 'r3', version: '0.8.0', lifecycle: 'GENERAL_AVAILABILITY', createdDate: '2026-09-15T00:00:00Z' },
]
const HEADS = [
    { uuid: 's1', release: 'r1', releaseVersion: '1.0.0', kind: 'FDA_PREMARKET', state: 'ACTIVE', latestVersion: 2,
        lastActKind: 'RESUBMITTED', lastActDate: '2026-10-02T00:00:00Z' },
    { uuid: 's3', release: 'r3', releaseVersion: '0.8.0', kind: 'FDA_PREMARKET', state: 'WITHDRAWN', latestVersion: 3,
        lastActKind: 'WITHDRAWN', lastActDate: '2026-09-20T00:00:00Z' },
    { uuid: 's9', release: 'r-old', releaseVersion: '0.9.0', kind: 'FDA_PREMARKET', state: 'WITHDRAWN', latestVersion: 1,
        lastActKind: 'WITHDRAWN', lastActDate: '2026-09-01T00:00:00Z' },
]
const NULL_TEXT = "The field at path '/submissionsOfComponent' was declared as a non null type, but the code involved "
    + 'in retrieving data has wrongly returned a null value.  The graphql specification requires that the parent '
    + 'field be set to null, or if that is non nullable that it bubble up null to its parent and so on. The '
    + "non-nullable type is '[Submission!]' within parent type 'Query'"
let failing = false

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
    }), { attachTo: document.body, global: { stubs: { RouterLink: RouterLinkStub } } })
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
        answers()
    })

    it('shows the head and its actions for a writer, and Create disabled with the lifecycle title on a DRAFT', async () => {
        const { w } = await mountPanel()
        const assembled = rowOf(w, '1.0.0')
        expect(assembled.find('[data-testid="submission-state"]').text()).toBe('Active')
        expect(assembled.text()).toContain('v2')
        expect(assembled.find('[data-testid="submission-resubmit"]').exists()).toBe(true)
        expect(assembled.find('[data-testid="submission-withdraw"]').exists()).toBe(true)
        expect(assembled.find('[data-testid="submission-create"]').exists()).toBe(false)
        const draft = rowOf(w, '1.1.0')
        const create = draft.find('[data-testid="submission-create"]')
        expect(create.exists()).toBe(true)
        expect(create.attributes('disabled')).toBeDefined()
        expect(create.attributes('title')).toBe('The release must be ASSEMBLED or above')
        const rows = w.findAll('[data-testid="submissions-release-rows"] tbody tr')
        expect(rows.map((r: any) => r.find('td').text())).toEqual(['1.1.0', '1.0.0', '0.8.0'])
        w.unmount()
    })

    it('offers Resubmit and no Withdraw on a WITHDRAWN head', async () => {
        const { w } = await mountPanel()
        const cell = rowOf(w, '0.8.0').find('[data-testid="submission-cell-FDA_PREMARKET"]')
        expect(cell.exists()).toBe(true)
        expect(cell.find('[data-testid="submission-state"]').text()).toBe('Withdrawn')
        expect(cell.text()).toContain('v3')
        expect(cell.find('[data-testid="submission-resubmit"]').exists()).toBe(true)
        expect(cell.find('[data-testid="submission-withdraw"]').exists()).toBe(false)
        const active = rowOf(w, '1.0.0').find('[data-testid="submission-cell-FDA_PREMARKET"]')
        expect(active.find('[data-testid="submission-resubmit"]').exists()).toBe(true)
        expect(active.find('[data-testid="submission-withdraw"]').exists()).toBe(true)
        w.unmount()
    })

    it('offers no action button at all to a reader', async () => {
        const { w } = await mountPanel({ isWritable: false })
        for (const id of ['submission-create', 'submission-resubmit', 'submission-withdraw']) {
            expect(w.find(`[data-testid="${id}"]`).exists(), id).toBe(false)
        }
        expect(rowOf(w, '1.0.0').find('[data-testid="submission-state"]').exists()).toBe(true)
        w.unmount()
    })

    it('lists the head of another feature set with its release version, and links releases to the release page', async () => {
        const { w } = await mountPanel()
        const other = w.find('[data-testid="submissions-other-rows"]')
        expect(other.text()).toContain('0.9.0')
        expect(other.text()).toContain('Withdrawn')
        const links = w.findAllComponents(RouterLinkStub)
        expect(links.map((l: any) => l.props('to'))).toContainEqual({ name: 'ReleaseView', params: { uuid: 'r1' } })
        expect(links.map((l: any) => l.props('to'))).toContainEqual({ name: 'ReleaseView', params: { uuid: 'r-old' } })
        w.unmount()
    })

    it('passes the kind and state filters to the server, and null for All', async () => {
        const { w } = await mountPanel()
        expect(calls('SubmissionsOfComponent')[0][0].variables).toEqual({ component: 'c1', kind: null, state: null })
        expect(calls('SubmissionReleasesOfBranch')[0][0].variables).toEqual({ branch: 'b-main', numRecords: 50 })
        await select(w, 'submissions-kind').vm.$emit('update:value', 'FDA_PREMARKET')
        await flushPromises()
        expect(calls('SubmissionsOfComponent').at(-1)![0].variables).toEqual({ component: 'c1', kind: 'FDA_PREMARKET', state: null })
        await select(w, 'submissions-state').vm.$emit('update:value', 'WITHDRAWN')
        await flushPromises()
        expect(calls('SubmissionsOfComponent').at(-1)![0].variables).toEqual({ component: 'c1', kind: 'FDA_PREMARKET', state: 'WITHDRAWN' })
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
