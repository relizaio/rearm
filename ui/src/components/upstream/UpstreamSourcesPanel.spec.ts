// @vitest-environment happy-dom
//
// T-509 (TPF-185 design 3.6): the Sources tab. A member who is not an administrator sees the list
// and no control and no credential field, and the form is not even mounted; an administrator sees
// the four row actions and Add source; a refused first read is the forbidden state only for the
// plain "Not authorized" and the error state, with its own sentence, for any other; archiving asks
// first and sends exactly one mutation on confirm.
// T-1170, T-1171 (TPF-243 design 3.10): a newly created source lands on its list in the dialog
// Follow an item, and the row action Follow an item opens that dialog on an ACTIVE row.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { page } from './startDialogTestKit'

const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
vi.mock('@/utils/commonFunctions', () => ({ default: { dateDisplay: (d: string) => 'D:' + d } }))
const router = { push: vi.fn(), replace: vi.fn() }
vi.mock('vue-router', async (orig) => ({ ...(await orig<any>()), useRouter: () => router }))

const { default: Panel } = await import('./UpstreamSourcesPanel.vue')
const { default: FormModal } = await import('./UpstreamSourceFormModal.vue')
const { default: StartModal } = await import('./UpstreamFollowStartModal.vue')
const { ARCHIVE_UPSTREAM_SOURCE, GET_UPSTREAM_SOURCE, GET_UPSTREAM_SOURCES, PROBE_UPSTREAM_SOURCE, SYNC_UPSTREAM_SOURCE_NOW } = await import('@/utils/upstreamSourceQueries')
const { GET_UPSTREAM_OFFERED_ITEMS } = await import('@/utils/upstreamOfferQueries')
const { archiveSourceStatement, forbiddenSentence, offerProbePending } = await import('@/utils/upstreamLabels')

const ORG = '11111111-2222-3333-4444-555555555555'
const SEALED = 'System is sealed. Please unseal the system first.'
// The row action Follows is a router link; the stub shows its target as JSON.
const RouterLink = { name: 'RouterLink', props: ['to'], template: '<a class="rl" :data-to="JSON.stringify(to)"><slot/></a>' }
const global = { stubs: { Modal: { props: ['show'], template: '<div v-if="show" class="modal"><slot/></div>' }, RouterLink } }

function source (n: number, name: string, credential: any) {
    return {
        uuid: `aaaaaaaa-0000-4000-8000-00000000000${n}`,
        org: ORG,
        kind: 'TEA',
        name,
        status: 'ACTIVE',
        documentClasses: ['SBOM', 'VEX'],
        vexImportMode: 'STAGE',
        autoIntegrate: 'DISABLED',
        tea: { root: `https://tea${n}.example.com/tea` },
        credential,
        probe: { state: 'DONE', result: 'REACHABLE', message: 'The server answered as a TEA server and did not reject the credential.', requestedDate: '2026-11-01T10:00:00Z', finishedDate: '2026-11-01T10:00:01Z' },
        createdDate: '2026-10-01T10:00:00Z',
        lastUpdatedDate: '2026-10-01T10:00:00Z',
        archivedDate: null,
        syncIntervalMinutes: 1440,
        nextSync: null,
        syncFailures: 0,
        lastSync: null,
        documentReServing: 'WITHHELD',
    }
}

const SOURCES = [
    source(1, 'Acme TEA', { kind: 'API_KEY', keyId: null, setDate: '2026-11-01T10:00:00Z' }),
    source(2, 'Globex TEA', { kind: 'NONE', keyId: null, setDate: null }),
]

function listReply () {
    return { data: { upstreamSources: SOURCES } }
}

function refusal (...messages: string[]): any {
    const err: any = new Error(messages[0])
    err.errors = messages.map(message => ({ message }))
    return err
}

async function mountPanel (isOrgAdmin: boolean, first: any = listReply()) {
    if (first instanceof Error) query.mockRejectedValueOnce(first)
    else query.mockResolvedValueOnce(first)
    const notify = vi.fn()
    const w = mount(Panel, { props: { orgUuid: ORG, isOrgAdmin, notify }, global, attachTo: document.body })
    await flushPromises()
    return { w, notify }
}

const testids = ['upstream-action-edit', 'upstream-action-probe', 'upstream-action-sync', 'upstream-action-archive']

beforeEach(() => {
    query.mockReset()
    mutate.mockReset()
})

describe('UpstreamSourcesPanel', () => {
    it('aMemberWhoIsNotAnAdministratorSeesTheListAndNoControl', async () => {
        const { w } = await mountPanel(false)

        expect(w.find('[data-testid="upstream-sources-table"]').exists()).toBe(true)
        expect(w.text()).toContain('Acme TEA')
        expect(w.text()).toContain('Globex TEA')
        expect(w.find('[data-testid="upstream-add-source"]').exists()).toBe(false)
        for (const id of testids) expect(w.find(`[data-testid="${id}"]`).exists()).toBe(false)
        expect(w.findComponent(FormModal).exists()).toBe(false)
        expect(document.body.querySelector('input[type=password]')).toBeNull()
        expect(w.find('input[type=password]').exists()).toBe(false)

        expect(w.text()).toContain('API key')
        expect(w.find('[data-testid="upstream-key-id-hidden"]').text()).toBe('key id not shown to you')

        expect(query).toHaveBeenCalledTimes(1)
        expect(query.mock.calls[0][0].query).toBe(GET_UPSTREAM_SOURCES)
        expect(mutate).not.toHaveBeenCalled()
        w.unmount()
    })

    it('anAdministratorSeesTheFourActionsAndAddSource', async () => {
        const { w } = await mountPanel(true)

        expect(w.find('[data-testid="upstream-add-source"]').exists()).toBe(true)
        for (const id of testids) expect(w.findAll(`[data-testid="${id}"]`).length).toBe(2)
        expect(w.findComponent(FormModal).exists()).toBe(true)
        expect(mutate).not.toHaveBeenCalled()
        w.unmount()
    })

    it('aPlainNotAuthorizedFirstReadShowsTheForbiddenSentenceAndNoTable', async () => {
        const err: any = new Error('Not authorized')
        err.errors = [{ message: 'Not authorized' }]
        const { w } = await mountPanel(false, err)

        expect(w.find('[data-testid="upstream-sources-forbidden"]').exists()).toBe(true)
        expect(w.find('[data-testid="upstream-sources-forbidden"]').text()).toBe(forbiddenSentence)
        expect(w.find('[data-testid="upstream-sources-error"]').exists()).toBe(false)
        expect(w.find('[data-testid="upstream-sources-table"]').exists()).toBe(false)
        expect(mutate).not.toHaveBeenCalled()
        w.unmount()
    })

    it('aSealedSystemFirstReadShowsItsOwnSentenceInPlaceOfTheList', async () => {
        const { w } = await mountPanel(true, refusal(SEALED))

        expect(w.find('[data-testid="upstream-sources-error"]').exists()).toBe(true)
        expect(w.find('[data-testid="upstream-sources-error"]').text()).toBe(SEALED)
        expect(w.html()).not.toContain(forbiddenSentence)
        expect(w.find('[data-testid="upstream-sources-forbidden"]').exists()).toBe(false)
        expect(w.find('[data-testid="upstream-sources-table"]').exists()).toBe(false)
        w.unmount()
    })

    it('archivingAsksFirstAndSendsOneMutationOnConfirm', async () => {
        const { w, notify } = await mountPanel(true)
        mutate.mockResolvedValueOnce({ data: { archiveUpstreamSource: { uuid: SOURCES[0].uuid } } })

        await w.findAll('[data-testid="upstream-action-archive"]')[0].trigger('click')
        await flushPromises()
        expect(mutate).not.toHaveBeenCalled()
        expect(w.find('[data-testid="upstream-archive-statement"]').exists()).toBe(true)
        expect(w.find('[data-testid="upstream-archive-statement"]').text()).toBe(archiveSourceStatement('Acme TEA'))

        await w.find('[data-testid="upstream-archive-cancel"]').trigger('click')
        await flushPromises()
        expect(mutate).not.toHaveBeenCalled()
        expect(w.find('[data-testid="upstream-archive-dialog"]').exists()).toBe(false)

        await w.findAll('[data-testid="upstream-action-archive"]')[0].trigger('click')
        await flushPromises()
        query.mockResolvedValueOnce({ data: { upstreamSources: [SOURCES[1]] } })
        await w.find('[data-testid="upstream-archive-confirm"]').trigger('click')
        await flushPromises()

        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mutate.mock.calls[0][0].mutation).toBe(ARCHIVE_UPSTREAM_SOURCE)
        expect(mutate.mock.calls[0][0].variables).toEqual({ uuid: SOURCES[0].uuid })
        expect(notify).toHaveBeenCalledWith('success', 'Upstream sources', 'Source archived')
        expect(w.find('[data-testid="upstream-archive-dialog"]').exists()).toBe(false)
        w.unmount()
    })
})

// The controls of an administrator: each guard holds on its own.
describe('UpstreamSourcesPanel administrator controls', () => {
    const headers = (w: any): string[] => w.findAll('th').map((t: any) => t.text())

    // From TPF-229 on (design 3.6, the row action Follows of child 2) the Actions column is there
    // for every member, and for a member it holds the Follows link only.
    it('aMemberGetsTheActionsColumnWithFollowsOnly', async () => {
        const { w } = await mountPanel(false)
        expect(headers(w)).toContain('Name')
        expect(headers(w)).toContain('Actions')
        expect(w.findAll('[data-testid="upstream-action-follows"]')).toHaveLength(2)
        for (const id of testids) expect(w.find(`[data-testid="${id}"]`).exists()).toBe(false)
        w.unmount()
    })

    it('theActionsCellDrawsOnlyFollowsForAMemberOnItsOwn', async () => {
        const member = await mountPanel(false)
        const memberHtml = mount({ render: () => (member.w.vm as any).actionsCell(SOURCES[0]) }, { global }).html()
        expect(memberHtml).toContain('Follows')
        for (const label of ['Edit', 'Probe', 'Sync now', 'Archive']) expect(memberHtml).not.toContain(label)
        member.w.unmount()
        const admin = await mountPanel(true)
        expect(headers(admin.w)).toContain('Actions')
        const adminHtml = mount({ render: () => (admin.w.vm as any).actionsCell(SOURCES[0]) }, { global }).html()
        for (const label of ['Follows', 'Edit', 'Probe', 'Sync now', 'Archive']) expect(adminHtml).toContain(label)
        admin.w.unmount()
    })

    it('theRowActionFollowsLeadsToTheFollowsOfTheSourceForEveryRow', async () => {
        const archived = { ...source(3, 'Initech TEA', { kind: 'NONE', keyId: null, setDate: null }), status: 'ARCHIVED', archivedDate: '2026-11-02T10:00:00Z' }
        const { w } = await mountPanel(false, { data: { upstreamSources: [SOURCES[0], archived] } })
        const links = w.findAll('[data-testid="upstream-action-follows"]')
        expect(links).toHaveLength(2)
        expect(JSON.parse(links[0].attributes('data-to')!)).toEqual({
            name: 'UpstreamOfOrg', params: { orguuid: ORG }, query: { tab: 'follows', source: SOURCES[0].uuid },
        })
        expect(JSON.parse(links[1].attributes('data-to')!).query.source).toBe(archived.uuid)
        w.unmount()
    })

    it('anArchivedRowHasNoAction', async () => {
        const archived = { ...source(3, 'Initech TEA', { kind: 'NONE', keyId: null, setDate: null }), status: 'ARCHIVED', archivedDate: '2026-11-02T10:00:00Z' }
        const { w } = await mountPanel(true, { data: { upstreamSources: [SOURCES[0], archived] } })
        expect(w.text()).toContain('Initech TEA')
        expect(w.text()).toContain('Archived')
        for (const id of testids) expect(w.findAll(`[data-testid="${id}"]`).length).toBe(1)
        w.unmount()
    })

    it('theCredentialCellShowsKindKeyIdAndDateOnly', async () => {
        const credential = { kind: 'API_KEY', keyId: 'kid-9', setDate: '2026-11-01T10:00:00Z', token: 'S3CR3T-marker-185', keySecret: 'S3CR3T-marker-185' }
        const { w } = await mountPanel(true, { data: { upstreamSources: [source(3, 'Initech TEA', credential)] } })
        expect(w.text()).toContain('key id kid-9')
        expect(w.text()).toContain('set on D:2026-11-01T10:00:00Z')
        expect(w.html()).not.toContain('S3CR3T-marker-185')
        w.unmount()
    })

    it('editOpensTheFormOnThatRow', async () => {
        const { w } = await mountPanel(true)
        expect(w.findComponent(FormModal).props('show')).toBe(false)
        await w.findAll('[data-testid="upstream-action-edit"]')[1].trigger('click')
        await flushPromises()
        expect(w.findComponent(FormModal).props('show')).toBe(true)
        expect(w.findComponent(FormModal).props('source')).toEqual(SOURCES[1])
        w.unmount()
    })

    it('addSourceAfterAnEditOpensAnEmptyCreateForm', async () => {
        const { w } = await mountPanel(true)
        await w.findAll('[data-testid="upstream-action-edit"]')[0].trigger('click')
        await flushPromises()
        expect(w.findComponent(FormModal).props('source')).toEqual(SOURCES[0])
        expect((w.findComponent(FormModal).vm as any).model.name).toBe('Acme TEA')
        w.findComponent(FormModal).vm.$emit('update:show', false)
        await flushPromises()
        expect(w.findComponent(FormModal).props('show')).toBe(false)

        await w.find('[data-testid="upstream-add-source"]').trigger('click')
        await flushPromises()
        expect(w.findComponent(FormModal).props('show')).toBe(true)
        expect(w.findComponent(FormModal).props('source')).toBeNull()
        expect((w.findComponent(FormModal).vm as any).model.name).toBe('')
        expect((w.findComponent(FormModal).vm as any).model.root).toBe('')
        w.unmount()
    })

    it('aSaveReadsTheListAgain', async () => {
        const { w } = await mountPanel(true)
        query.mockResolvedValueOnce(listReply())
        w.findComponent(FormModal).vm.$emit('saved', SOURCES[0].uuid)
        await flushPromises()
        expect(query).toHaveBeenCalledTimes(2)
        expect(query.mock.calls[1][0].query).toBe(GET_UPSTREAM_SOURCES)
        w.unmount()
    })
})

// T-1170 (TPF-243 design 3.10): the landing. Only the save of a create whose reload holds the new
// source as ACTIVE opens the dialog Follow an item, on that source, in mode From the list; the
// dialog then waits for the pending probe. The panel itself never asks for the list.
describe('UpstreamSourcesPanel landing after a create', () => {
    const NEW = 'aaaaaaaa-0000-4000-8000-000000000009'
    const pendingProbe = { state: 'PENDING', result: null, message: null, requestedDate: '2026-11-27T10:00:00Z', finishedDate: null }
    const created = (over: Record<string, any> = {}) => ({ ...source(9, 'Hooli TEA', { kind: 'NONE', keyId: null, setDate: null }), uuid: NEW, probe: pendingProbe, ...over })
    const archivedOther = { ...source(8, 'Initech TEA', { kind: 'NONE', keyId: null, setDate: null }), status: 'ARCHIVED', archivedDate: '2026-11-02T10:00:00Z' }

    // Answers by document: the list of sources in turn (an Error is a refusal), any read of one
    // source with the new source as served, and nothing else.
    function answerByDocument (lists: any[]) {
        query.mockImplementation(async (opts: any) => {
            if (opts.query === GET_UPSTREAM_SOURCES) {
                const next = lists.shift()
                if (next instanceof Error) throw next
                return { data: { upstreamSources: next } }
            }
            if (opts.query === GET_UPSTREAM_SOURCE) return { data: { upstreamSource: created() } }
            return undefined
        })
    }

    async function mountWith (lists: any[]) {
        answerByDocument(lists)
        const notify = vi.fn()
        const w = mount(Panel, { props: { orgUuid: ORG, isOrgAdmin: true, notify }, global, attachTo: document.body })
        await flushPromises()
        return { w, notify }
    }

    async function saveFrom (w: any, opener: () => Promise<void>, uuid: string) {
        await opener()
        await flushPromises()
        w.findComponent(FormModal).vm.$emit('update:show', false)
        w.findComponent(FormModal).vm.$emit('saved', uuid)
        await flushPromises()
    }

    const addSource = (w: any) => () => w.find('[data-testid="upstream-add-source"]').trigger('click')
    const listCalls = () => query.mock.calls.filter(c => c[0].query === GET_UPSTREAM_OFFERED_ITEMS)
    const sourcesReads = () => query.mock.calls.filter(c => c[0].query === GET_UPSTREAM_SOURCES)

    it('aCreatedSourceTheReloadHoldsAsActiveOpensTheDialogOnItsListWaitingForTheProbe', async () => {
        const { w } = await mountWith([SOURCES, [...SOURCES, created(), archivedOther]])
        expect(w.findComponent(StartModal).props('show')).toBe(false)

        await saveFrom(w, addSource(w), NEW)

        expect(sourcesReads()).toHaveLength(2)
        const start = w.findComponent(StartModal)
        expect(start.props('show')).toBe(true)
        expect(start.props('source')).toBe(NEW)
        expect(start.props('sources').map((s: any) => s.uuid)).toEqual([SOURCES[0].uuid, SOURCES[1].uuid, NEW])
        expect(start.props('upstreamUuid') ?? null).toBeNull()
        expect(start.props('mode') ?? null).toBeNull()
        expect(w.find('[data-testid="upstream-start-list-pane"]').exists()).toBe(true)
        expect(w.find('[data-testid="upstream-start-uuid"]').exists()).toBe(false)
        expect(w.find('[data-testid="upstream-start-gate"]').text()).toContain(offerProbePending)
        const reads = query.mock.calls.filter(c => c[0].query === GET_UPSTREAM_SOURCE)
        expect(reads.length).toBeGreaterThanOrEqual(1)
        for (const r of reads) expect(r[0].variables).toEqual({ uuid: NEW })
        expect(listCalls()).toHaveLength(0)
        expect(mutate).not.toHaveBeenCalled()
        w.unmount()
    })

    it('aCreatedSourceTheReloadDoesNotHoldOpensNothing', async () => {
        const { w } = await mountWith([SOURCES, SOURCES])
        await saveFrom(w, addSource(w), NEW)
        expect(sourcesReads()).toHaveLength(2)
        expect(w.findComponent(StartModal).props('show')).toBe(false)
        expect(w.find('[data-testid="upstream-follow-start"]').exists()).toBe(false)
        expect(listCalls()).toHaveLength(0)
        w.unmount()
    })

    it('aCreatedSourceTheReloadHoldsAsArchivedOpensNothing', async () => {
        const { w } = await mountWith([SOURCES, [...SOURCES, created({ status: 'ARCHIVED', archivedDate: '2026-11-27T10:00:00Z' })]])
        await saveFrom(w, addSource(w), NEW)
        expect(sourcesReads()).toHaveLength(2)
        expect(w.text()).toContain('Hooli TEA')
        expect(w.findComponent(StartModal).props('show')).toBe(false)
        expect(listCalls()).toHaveLength(0)
        w.unmount()
    })

    it('aFailedReloadAfterACreateOpensNothing', async () => {
        const { w, notify } = await mountWith([SOURCES, refusal(SEALED)])
        await saveFrom(w, addSource(w), NEW)
        expect(sourcesReads()).toHaveLength(2)
        expect(notify).toHaveBeenCalledWith('error', 'Upstream sources', SEALED)
        expect(w.findComponent(StartModal).props('show')).toBe(false)
        expect(listCalls()).toHaveLength(0)
        w.unmount()
    })

    it('aSavedEditOpensNothingEvenWhenTheRowIsActive', async () => {
        const { w } = await mountWith([SOURCES, SOURCES])
        await saveFrom(w, () => w.findAll('[data-testid="upstream-action-edit"]')[0].trigger('click'), SOURCES[0].uuid)
        expect(sourcesReads()).toHaveLength(2)
        expect(w.findComponent(StartModal).props('show')).toBe(false)
        expect(listCalls()).toHaveLength(0)
        w.unmount()
    })

    it('aSavedEditAfterACancelledAddSourceOpensNothing', async () => {
        const { w } = await mountWith([SOURCES, SOURCES])
        await w.find('[data-testid="upstream-add-source"]').trigger('click')
        await flushPromises()
        w.findComponent(FormModal).vm.$emit('update:show', false)
        await flushPromises()
        await saveFrom(w, () => w.findAll('[data-testid="upstream-action-edit"]')[1].trigger('click'), SOURCES[1].uuid)
        expect(w.findComponent(StartModal).props('show')).toBe(false)
        expect(listCalls()).toHaveLength(0)
        w.unmount()
    })
})

// T-1171 (TPF-243 design 3.10): the row action Follow an item, for an administrator on an ACTIVE
// row only; a member has Follows only and no dialog in the tree.
describe('UpstreamSourcesPanel row action Follow an item', () => {
    const archived = { ...source(3, 'Initech TEA', { kind: 'NONE', keyId: null, setDate: null }), status: 'ARCHIVED', archivedDate: '2026-11-02T10:00:00Z' }
    const rows = () => ({ data: { upstreamSources: [SOURCES[0], archived] } })

    it('anAdministratorHasItOnTheActiveRowOnlyAndItOpensTheDialogOnThatRow', async () => {
        const { w } = await mountPanel(true, rows())
        const actions = w.findAll('[data-testid="upstream-action-follow-item"]')
        expect(actions).toHaveLength(1)
        expect(actions[0].text()).toBe('Follow an item')
        const cell = mount({ render: () => (w.vm as any).actionsCell(SOURCES[0]) }, { global })
        expect(cell.findAll('a, button').map(b => b.text())).toEqual(['Follows', 'Follow an item', 'Edit', 'Probe', 'Sync now', 'Archive'])
        expect(mount({ render: () => (w.vm as any).actionsCell(archived) }, { global }).find('[data-testid="upstream-action-follow-item"]').exists()).toBe(false)
        expect(w.findComponent(StartModal).props('show')).toBe(false)

        query.mockImplementation(async (opts: any) => {
            if (opts.query === GET_UPSTREAM_SOURCE) return { data: { upstreamSource: SOURCES[0] } }
            if (opts.query === GET_UPSTREAM_OFFERED_ITEMS) return { data: { upstreamOfferedItems: page({ source: SOURCES[0].uuid }) } }
            return undefined
        })
        await actions[0].trigger('click')
        await flushPromises()

        const start = w.findComponent(StartModal)
        expect(start.props('show')).toBe(true)
        expect(start.props('source')).toBe(SOURCES[0].uuid)
        expect(start.props('sources').map((s: any) => s.uuid)).toEqual([SOURCES[0].uuid])
        expect(w.find('[data-testid="upstream-start-list-pane"]').exists()).toBe(true)
        expect(query.mock.calls.filter(c => c[0].query === GET_UPSTREAM_SOURCE).map(c => c[0].variables)).toEqual([{ uuid: SOURCES[0].uuid }])
        expect(mutate).not.toHaveBeenCalled()
        w.unmount()
    })

    it('aMemberHasFollowsOnlyAndNoDialogInTheTree', async () => {
        const { w } = await mountPanel(false, rows())
        expect(w.findAll('[data-testid="upstream-action-follows"]')).toHaveLength(2)
        expect(w.find('[data-testid="upstream-action-follow-item"]').exists()).toBe(false)
        expect(w.html()).not.toContain('Follow an item')
        expect(w.findComponent(StartModal).exists()).toBe(false)
        const cell = mount({ render: () => (w.vm as any).actionsCell(SOURCES[0]) }, { global })
        expect(cell.findAll('a, button').map(b => b.text())).toEqual(['Follows'])
        expect(query).toHaveBeenCalledTimes(1)
        w.unmount()
    })
})

// Probe and Sync now: the document, its variables and what the administrator is told.
describe('UpstreamSourcesPanel row actions', () => {
    it('probeSendsTheProbeOfThatSource', async () => {
        const { w, notify } = await mountPanel(true)
        mutate.mockResolvedValueOnce({ data: { probeUpstreamSource: SOURCES[1] } })
        await w.findAll('[data-testid="upstream-action-probe"]')[1].trigger('click')
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mutate.mock.calls[0][0].mutation).toBe(PROBE_UPSTREAM_SOURCE)
        expect(mutate.mock.calls[0][0].variables).toEqual({ uuid: SOURCES[1].uuid })
        expect(notify).toHaveBeenCalledWith('success', 'Upstream sources', 'Probe started')
        w.unmount()
    })

    it('syncNowSendsTheSyncOfThatSource', async () => {
        const { w, notify } = await mountPanel(true)
        mutate.mockResolvedValueOnce({ data: { syncUpstreamSourceNow: SOURCES[0] } })
        await w.findAll('[data-testid="upstream-action-sync"]')[0].trigger('click')
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mutate.mock.calls[0][0].mutation).toBe(SYNC_UPSTREAM_SOURCE_NOW)
        expect(mutate.mock.calls[0][0].variables).toEqual({ source: SOURCES[0].uuid })
        expect(notify).toHaveBeenCalledWith('success', 'Upstream sources', 'Sync started')
        w.unmount()
    })

    it('aRefusedActionShowsTheSentenceThroughNotify', async () => {
        const { w, notify } = await mountPanel(true)
        const sentence = 'The upstream source has no active follow.'
        mutate.mockRejectedValueOnce(refusal(sentence))
        await w.findAll('[data-testid="upstream-action-sync"]')[0].trigger('click')
        await flushPromises()
        expect(notify).toHaveBeenCalledTimes(1)
        expect(notify).toHaveBeenCalledWith('error', 'Upstream sources', sentence)
        expect(w.find('[data-testid="upstream-sources-table"]').exists()).toBe(true)
        w.unmount()
    })
})

// The statuses read, and a refusal of a read after the first.
describe('UpstreamSourcesPanel reads', () => {
    it('readsActiveSourcesThenArchivedOnesTooAndALaterRefusalKeepsTheList', async () => {
        const { w, notify } = await mountPanel(false)
        expect(query.mock.calls[0][0].variables).toEqual({ org: ORG, statuses: ['ACTIVE'] })

        query.mockRejectedValueOnce(refusal(SEALED))
        await w.find('[data-testid="upstream-show-archived"]').trigger('click')
        await flushPromises()

        expect(query).toHaveBeenCalledTimes(2)
        expect(query.mock.calls[1][0].variables).toEqual({ org: ORG, statuses: ['ACTIVE', 'ARCHIVED'] })
        expect(notify).toHaveBeenCalledWith('error', 'Upstream sources', SEALED)
        expect(w.find('[data-testid="upstream-sources-table"]').exists()).toBe(true)
        expect(w.find('[data-testid="upstream-sources-error"]').exists()).toBe(false)
        w.unmount()
    })
})
