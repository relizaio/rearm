// @vitest-environment happy-dom
//
// T-509 (TPF-185 design 3.6): the Sources tab. A member who is not an administrator sees the list
// and no control and no credential field, and the form is not even mounted; an administrator sees
// the four row actions and Add source; a refused first read is the forbidden state only for the
// plain "Not authorized" and the error state, with its own sentence, for any other; archiving asks
// first and sends exactly one mutation on confirm.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
vi.mock('@/utils/commonFunctions', () => ({ default: { dateDisplay: (d: string) => 'D:' + d } }))

const { default: Panel } = await import('./UpstreamSourcesPanel.vue')
const { default: FormModal } = await import('./UpstreamSourceFormModal.vue')
const { ARCHIVE_UPSTREAM_SOURCE, GET_UPSTREAM_SOURCES, PROBE_UPSTREAM_SOURCE, SYNC_UPSTREAM_SOURCE_NOW } = await import('@/utils/upstreamSourceQueries')
const { archiveSourceStatement, forbiddenSentence } = await import('@/utils/upstreamLabels')

const ORG = '11111111-2222-3333-4444-555555555555'
const SEALED = 'System is sealed. Please unseal the system first.'
const global = { stubs: { Modal: { props: ['show'], template: '<div v-if="show" class="modal"><slot/></div>' } } }

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

    it('aMemberGetsNoActionsColumn', async () => {
        const { w } = await mountPanel(false)
        expect(headers(w)).toContain('Name')
        expect(headers(w)).not.toContain('Actions')
        w.unmount()
    })

    it('theActionsCellDrawsNothingForAMemberOnItsOwn', async () => {
        const member = await mountPanel(false)
        expect((member.w.vm as any).actionsCell(SOURCES[0])).toBeNull()
        member.w.unmount()
        const admin = await mountPanel(true)
        expect(headers(admin.w)).toContain('Actions')
        expect((admin.w.vm as any).actionsCell(SOURCES[0])).not.toBeNull()
        admin.w.unmount()
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
