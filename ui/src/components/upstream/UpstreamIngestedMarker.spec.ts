// @vitest-environment happy-dom
//
// T-580 to T-582: the marker of an ingested product, component or release. Nothing and no request
// outside the Pro edition or before the user has loaded; one read and nothing else when the thing
// was not ingested; the source's name and the follow when they can be read, and their absence
// without an error when they cannot; supplier text as text; the snapshot read-only, its VEX import
// lines read only when it is opened.
// T-1075 to T-1079: the source's name from the item and the release; the source row read only for
// an administrator's "Follow this item".
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))

const getters: any = { myuser: null }
vi.mock('vuex', () => ({ useStore: () => ({ getters }) }))

const router = { push: vi.fn(), replace: vi.fn() }
vi.mock('vue-router', async (orig) => ({ ...(await orig<any>()), useRoute: () => ({ params: {}, query: {} }), useRouter: () => router }))

const { default: Marker } = await import('./UpstreamIngestedMarker.vue')
const { default: StartModal } = await import('./UpstreamFollowStartModal.vue')
const {
    GET_ARTIFACT_VEX_IMPORT, GET_COMPONENT_INGESTED_FROM, GET_MARKER_FOLLOWS, GET_RELEASE_INGESTED_FROM,
} = await import('@/utils/upstreamMarkerQueries')
const { GET_UPSTREAM_SOURCE } = await import('@/utils/upstreamSourceQueries')
const { itemMappingLabel, oneOffFrozenSentence, runStopSentence } = await import('@/utils/upstreamLabels')

const ORG = '11111111-2222-3333-4444-555555555555'
const SOURCE = 'aaaaaaaa-0000-4000-8000-000000000001'
const COMPONENT = 'bbbbbbbb-0000-4000-8000-000000000001'
const RELEASE = 'bbbbbbbb-0000-4000-8000-000000000002'
const ITEM = 'cccccccc-0000-4000-8000-000000000001'
const UPSTREAM_UUID = 'dddddddd-0000-4000-8000-000000000001'
const FOLLOW = 'eeeeeeee-0000-4000-8000-000000000001'
const ONE_OFF = 'ffffffff-0000-4000-8000-000000000001'
const SBOM_ARTIFACT = '99999999-0000-4000-8000-000000000001'
const VEX_ARTIFACT = '99999999-0000-4000-8000-000000000002'
const HOSTILE = '<img src=x onerror=alert(1)>'
const NOT_AUTHORIZED = 'Not authorized'

const RouterLink = { name: 'RouterLink', props: ['to'], template: '<a class="rl" :data-to="JSON.stringify(to)"><slot/></a>' }
const global = { stubs: { RouterLink, Modal: { props: ['show'], template: '<div v-if="show" class="modal"><slot/></div>' } } }

function refusal (message: string): any {
    const err: any = new Error(message)
    err.graphQLErrors = [{ message }]
    err.errors = [{ message }]
    return err
}

function item (over: any = {}): any {
    return {
        uuid: ITEM, source: SOURCE, sourceName: 'Acme TEA', kind: 'PRODUCT', upstreamUuid: UPSTREAM_UUID, upstreamName: HOSTILE, depth: 2, depthOrigin: 'PROPOSED',
        mapping: 'ADOPTED', updateState: 'UPDATED', ...over,
    }
}

function file (over: any = {}): any {
    return {
        number: 1, outcome: 'STORED_SBOM', reason: null, message: null, artifact: null, conflictArtifact: null, upstreamArtifactName: 'sbom.json',
        upstreamArtifactType: 'BOM', documentClass: 'SBOM', mediaType: 'application/vnd.cyclonedx+json', sizeBytes: 2048,
        sha256: 'abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789', ...over,
    }
}

function release (over: any = {}): any {
    return {
        uuid: 'cccccccc-0000-4000-8000-000000000002', item: ITEM, source: SOURCE, sourceName: 'Acme TEA', upstreamUuid: UPSTREAM_UUID,
        upstreamVersion: '1.4.0',
        upstreamDate: '2026-11-01T10:00:00Z', state: 'INGESTED', integrity: 'CONSISTENT', updateState: 'UPDATED', presence: 'GONE',
        goneSince: '2026-11-20T10:00:00Z', lineKey: '1.x', oneOff: ONE_OFF,
        collections: [
            { version: 1, upstreamCreatedDate: '2026-11-01T10:00:00Z', updateReasonType: 'INITIAL_RELEASE', updateReasonComment: null,
                ingestedDate: '2026-11-02T10:00:00Z', files: [file({ artifact: SBOM_ARTIFACT })] },
            { version: 2, upstreamCreatedDate: '2026-11-05T10:00:00Z', updateReasonType: 'VEX_UPDATED', updateReasonComment: HOSTILE,
                ingestedDate: '2026-11-06T10:00:00Z', files: [
                    file({ number: 1, artifact: SBOM_ARTIFACT }),
                    file({ number: 2, outcome: 'STORED_FILE', artifact: VEX_ARTIFACT, upstreamArtifactName: 'vex.json', upstreamArtifactType: 'VEX', documentClass: 'VEX' }),
                    file({ number: 3, outcome: 'NOT_KEPT', reason: 'TOO_LARGE', message: 'The document is larger than ReARM keeps.', upstreamArtifactName: 'big.bin',
                        upstreamArtifactType: 'OTHER', documentClass: 'OTHER' }),
                ] },
        ],
        ...over,
    }
}

const SOURCE_ROW = { uuid: SOURCE, name: 'Acme TEA', status: 'ACTIVE' }

function coveringFollow (stopReason: string | null = null): any {
    return { uuid: FOLLOW, item: { uuid: 'cccccccc-0000-4000-8000-000000000009' }, components: [{ uuid: ITEM }],
        lastRun: { state: 'STOPPED', stopReason, finishedDate: '2026-11-21T10:00:00Z' } }
}

const VEX_IMPORT = { state: 'IMPORTED', reason: null, modeUsed: 'STAGE', storedDate: '2026-11-06T10:00:00Z', finishedDate: '2026-11-06T10:01:00Z',
    statementsTotal: 7, proposalsStaged: 5, proposalsAccepted: 1, statementsUnmatched: 1, statementsErrored: 0 }

interface Answers { origin?: any, source?: any, follows?: any, vexImport?: any }

/** Reads are answered by document; an Error answer is thrown. */
function answer (a: Answers) {
    query.mockImplementation(async (args: any) => {
        const give = (v: any, data: any) => { if (v instanceof Error) throw v; return { data } }
        if (args.query === GET_COMPONENT_INGESTED_FROM) return give(a.origin, { component: { uuid: COMPONENT, ingestedFrom: a.origin } })
        if (args.query === GET_RELEASE_INGESTED_FROM) return give(a.origin, { release: { uuid: RELEASE, ingestedFrom: a.origin } })
        if (args.query === GET_UPSTREAM_SOURCE) return give(a.source, { upstreamSource: a.source })
        if (args.query === GET_MARKER_FOLLOWS) return give(a.follows, { upstreamFollows: a.follows })
        if (args.query === GET_ARTIFACT_VEX_IMPORT) return give(a.vexImport, { artifact: { uuid: args.variables.uuid, vexImport: a.vexImport } })
        throw new Error('unexpected query')
    })
}

const sent = (doc: any) => query.mock.calls.filter(c => c[0].query === doc)

async function mountMarker (subject: 'ITEM' | 'RELEASE', isOrgAdmin = false) {
    const w = mount(Marker, { props: { subject, orgUuid: ORG, uuid: subject === 'ITEM' ? COMPONENT : RELEASE, isOrgAdmin }, global })
    await flushPromises()
    return w
}

beforeEach(() => {
    query.mockReset()
    mutate.mockReset()
    router.push.mockReset()
    router.replace.mockReset()
    getters.myuser = { installationType: 'SAAS' }
})

afterEach(() => {
    expect(mutate).not.toHaveBeenCalled()
})

describe('T-580: the gate and an item that was not ingested', () => {
    for (const subject of ['ITEM', 'RELEASE'] as const) {
        it(`${subject}: OSS and a user not loaded yet render nothing and send nothing`, async () => {
            answer({ origin: subject === 'ITEM' ? item() : release(), source: SOURCE_ROW, follows: [] })
            for (const installationType of ['OSS', undefined]) {
                getters.myuser = installationType === undefined ? undefined : { installationType }
                const w = await mountMarker(subject)
                expect(w.find('.upstream-marker').exists()).toBe(false)
                expect(w.text()).toBe('')
                w.unmount()
            }
            expect(query).not.toHaveBeenCalled()
        })

        it(`${subject}: ingestedFrom null renders nothing after exactly the marker's own read`, async () => {
            answer({ origin: null, source: SOURCE_ROW, follows: [] })
            const w = await mountMarker(subject)
            expect(w.find('.upstream-marker').exists()).toBe(false)
            expect(query).toHaveBeenCalledTimes(1)
            expect(query.mock.calls[0][0].query).toBe(subject === 'ITEM' ? GET_COMPONENT_INGESTED_FROM : GET_RELEASE_INGESTED_FROM)
            // A release tells its host that nothing of it is a snapshot; an item tells nothing.
            expect(w.emitted('snapshot-artifacts')).toEqual(subject === 'RELEASE' ? [[[]]] : undefined)
        })
    }
})

describe('T-581: the marker of a product or component', () => {
    it('(a) the source and the follows refused: the name from the item, the upstream name as text, the adoption sentence, no follow line, no error', async () => {
        answer({ origin: item(), source: refusal(NOT_AUTHORIZED), follows: refusal(NOT_AUTHORIZED) })
        const w = await mountMarker('ITEM', true)
        const text = w.text()
        expect(text).toContain('Ingested from Acme TEA: Product ' + HOSTILE)
        expect(w.find('img').exists()).toBe(false)
        expect(w.find('[onerror]').exists()).toBe(false)
        expect(text).toContain(itemMappingLabel.ADOPTED)
        expect(w.find('[data-testid="upstream-marker-follow"]').exists()).toBe(false)
        expect(w.find('[data-testid="upstream-marker-follow-item"]').exists()).toBe(false)
        expect(text).not.toContain(NOT_AUTHORIZED)
        expect(text).toContain('Version lines at depth 2, proposed by ReARM and applied')
        expect(sent(GET_UPSTREAM_SOURCE)[0][0].variables).toEqual({ uuid: SOURCE })
        expect(sent(GET_MARKER_FOLLOWS)[0][0].variables).toEqual({ org: ORG, source: SOURCE })
    })

    it('(b) a follow covers the item and its last run stopped with ITEM_GONE', async () => {
        answer({ origin: item(), source: SOURCE_ROW, follows: [coveringFollow('ITEM_GONE')] })
        const w = await mountMarker('ITEM')
        expect(w.text()).toContain('Ingested from Acme TEA: Product ' + HOSTILE)
        const link = w.find('[data-testid="upstream-marker-follow-link"]')
        expect(JSON.parse(link.attributes('data-to')!)).toEqual({ name: 'UpstreamFollow', params: { orguuid: ORG, uuid: FOLLOW } })
        const lastSync = w.find('[data-testid="upstream-marker-last-sync"]').text()
        expect(lastSync).toContain('Last sync of its follow: Stopped')
        expect(lastSync).toMatch(/2026/)
        const gone = w.find('[data-testid="upstream-marker-item-gone"]').text()
        expect(gone).toContain('Gone upstream')
        expect(gone).toContain(runStopSentence.ITEM_GONE)
        expect(w.find('[data-testid="upstream-marker-follow-item"]').exists()).toBe(false)
    })

    for (const isOrgAdmin of [true, false]) {
        it(`(c) no follow covers it, no longer updated, the source active: ${isOrgAdmin ? 'administrator' : 'reader'}`, async () => {
            answer({ origin: item({ updateState: 'NO_LONGER_UPDATED' }), source: SOURCE_ROW, follows: [] })
            const w = await mountMarker('ITEM', isOrgAdmin)
            expect(w.find('[data-testid="upstream-marker-update-state"]').text()).toBe('No longer updated')
            expect(w.text()).toContain('No active follow of an active source covers it. What was ingested is kept.')
            const button = w.find('[data-testid="upstream-marker-follow-item"]')
            expect(button.exists()).toBe(isOrgAdmin)
            expect(w.findComponent(StartModal).exists()).toBe(false)
            if (!isOrgAdmin) return
            const before = query.mock.calls.length
            await button.trigger('click')
            await flushPromises()
            const modal = w.findComponent(StartModal)
            expect(modal.exists()).toBe(true)
            expect(modal.props()).toEqual(expect.objectContaining({ show: true, orgUuid: ORG, source: SOURCE, kind: 'PRODUCT', upstreamUuid: UPSTREAM_UUID }))
            expect(query.mock.calls.length).toBe(before)
            expect(router.push).not.toHaveBeenCalled()
        })
    }
})

describe('T-1075 to T-1079: the source name comes from the item and the release, the source row only for the follow button', () => {
    const ROW = { uuid: SOURCE, name: 'Row name', status: 'ACTIVE' }
    const origin = (subject: 'ITEM' | 'RELEASE', over: any = {}) => (subject === 'ITEM' ? item(over) : release(over))
    const headlineOf = (subject: 'ITEM' | 'RELEASE', name: string) => (subject === 'ITEM'
        ? 'Ingested from ' + name + ': Product ' + HOSTILE
        : 'Ingested from ' + name + ': upstream release 1.4.0')

    for (const isOrgAdmin of [false, true]) {
        it(`T-1075 ITEM, ${isOrgAdmin ? 'administrator' : 'reader'}: the name from the item; the source row read for the administrator only`, async () => {
            answer({ origin: item(), source: ROW, follows: [] })
            const w = await mountMarker('ITEM', isOrgAdmin)
            expect(w.text()).toContain(headlineOf('ITEM', 'Acme TEA'))
            expect(w.text()).not.toContain('Row name')
            const reads = sent(GET_UPSTREAM_SOURCE)
            expect(reads).toHaveLength(isOrgAdmin ? 1 : 0)
            if (isOrgAdmin) expect(reads[0][0].variables).toEqual({ uuid: SOURCE })
        })

        it(`T-1076 RELEASE, ${isOrgAdmin ? 'administrator' : 'reader'}: the name from the release, no source read, the refusal not shown`, async () => {
            answer({ origin: release(), source: refusal(NOT_AUTHORIZED), follows: [] })
            const w = await mountMarker('RELEASE', isOrgAdmin)
            expect(w.text()).toContain(headlineOf('RELEASE', 'Acme TEA'))
            expect(sent(GET_UPSTREAM_SOURCE)).toHaveLength(0)
            expect(w.text()).not.toContain(NOT_AUTHORIZED)
        })
    }

    it('T-1077 ITEM, administrator, the source and the follows refused: the name stays, no button, no follow line, no error', async () => {
        answer({ origin: item(), source: refusal(NOT_AUTHORIZED), follows: refusal(NOT_AUTHORIZED) })
        const w = await mountMarker('ITEM', true)
        expect(w.text()).toContain(headlineOf('ITEM', 'Acme TEA'))
        expect(sent(GET_UPSTREAM_SOURCE)).toHaveLength(1)
        expect(w.find('[data-testid="upstream-marker-follow-item"]').exists()).toBe(false)
        expect(w.find('[data-testid="upstream-marker-follow"]').exists()).toBe(false)
        expect(w.text()).not.toContain(NOT_AUTHORIZED)
    })

    for (const subject of ['ITEM', 'RELEASE'] as const) {
        for (const shape of ['null', 'absent', 'empty'] as const) {
            it(`T-1078 ${subject}, sourceName ${shape}: "an upstream source", never the name of the source row`, async () => {
                const o = origin(subject, { sourceName: shape === 'null' ? null : '' })
                if (shape === 'absent') delete o.sourceName
                expect('sourceName' in o).toBe(shape !== 'absent')
                answer({ origin: o, source: ROW, follows: [] })
                const w = await mountMarker(subject, true)
                expect(w.text()).toContain(headlineOf(subject, 'an upstream source'))
                expect(w.text()).not.toContain('Row name')
            })
        }

        it(`T-1079 ${subject}: a markup name is shown as text and creates no element`, async () => {
            answer({ origin: origin(subject, { sourceName: HOSTILE }), source: ROW, follows: [] })
            const w = await mountMarker(subject, true)
            expect(w.text()).toContain(headlineOf(subject, HOSTILE))
            expect(w.find('img').exists()).toBe(false)
            expect(w.find('[onerror]').exists()).toBe(false)
        })
    }

    it('T-1079 ITEM: the administrator flag set after the read brings one source read and the button', async () => {
        answer({ origin: item(), source: ROW, follows: [] })
        const w = await mountMarker('ITEM', false)
        expect(sent(GET_UPSTREAM_SOURCE)).toHaveLength(0)
        expect(w.find('[data-testid="upstream-marker-follow-item"]').exists()).toBe(false)
        const reads = query.mock.calls.length
        await w.setProps({ isOrgAdmin: true })
        await flushPromises()
        expect(sent(GET_UPSTREAM_SOURCE)).toHaveLength(1)
        expect(query.mock.calls.length).toBe(reads + 1)
        expect(w.find('[data-testid="upstream-marker-follow-item"]').exists()).toBe(true)
        expect(w.text()).toContain(headlineOf('ITEM', 'Acme TEA'))
    })
})

describe('T-582: the marker of a release and its snapshot', () => {
    it('shows gone, the one-off, the newest collection, emits the snapshot artifacts, and keeps supplier text as text', async () => {
        answer({ origin: release(), source: SOURCE_ROW, follows: [], vexImport: VEX_IMPORT })
        const w = await mountMarker('RELEASE')
        expect(w.text()).toContain('Ingested from Acme TEA: upstream release 1.4.0')
        expect(w.text()).toContain('The version and the upstream identifiers are owned by the sync.')
        const gone = w.find('[data-testid="upstream-marker-gone"]').text()
        expect(gone).toContain('Gone upstream')
        expect(gone).toMatch(/The source no longer lists this release since .*2026.*\. What was ingested is kept\./)
        expect(w.find('[data-testid="upstream-marker-one-off"]').text()).toContain(oneOffFrozenSentence)
        const oneOff = w.find('[data-testid="upstream-marker-one-off-link"]')
        expect(JSON.parse(oneOff.attributes('data-to')!)).toEqual({ name: 'UpstreamOfOrg', params: { orguuid: ORG }, query: { tab: 'tei', oneOff: ONE_OFF } })
        expect(w.find('[data-testid="upstream-marker-newest"]').text()).toContain('collection version 2')
        const emitted = w.emitted('snapshot-artifacts')!
        expect(emitted).toHaveLength(1)
        expect([...(emitted[0][0] as string[])].sort()).toEqual([SBOM_ARTIFACT, VEX_ARTIFACT].sort())
        expect(sent(GET_ARTIFACT_VEX_IMPORT)).toHaveLength(0)
    })

    it('the snapshot opens read-only and reads the import of the one stored VEX file of the newest version', async () => {
        answer({ origin: release(), source: SOURCE_ROW, follows: [], vexImport: VEX_IMPORT })
        const w = await mountMarker('RELEASE')
        expect(sent(GET_ARTIFACT_VEX_IMPORT)).toHaveLength(0)
        await w.find('[data-testid="upstream-snapshot"] .n-collapse-item__header-main').trigger('click')
        await flushPromises()
        expect(sent(GET_ARTIFACT_VEX_IMPORT)).toHaveLength(1)
        expect(sent(GET_ARTIFACT_VEX_IMPORT)[0][0].variables).toEqual({ uuid: VEX_ARTIFACT })
        const snapshot = w.find('[data-testid="upstream-snapshot"]')
        expect(snapshot.findAll('button')).toHaveLength(0)
        expect(snapshot.find('[data-testid="upstream-snapshot-version-2"]').exists()).toBe(true)
        expect(snapshot.find('[data-testid="upstream-snapshot-reason"]').text()).toContain(HOSTILE)
        expect(snapshot.find('img').exists()).toBe(false)
        const versions = snapshot.findAll('.upstream-snapshot-version').map(v => v.attributes('data-testid'))
        expect(versions).toEqual(['upstream-snapshot-version-2', 'upstream-snapshot-version-1'])
        expect(snapshot.find('[data-testid="upstream-snapshot-file-2-3"]').text()).toContain('Not kept: The document is larger than ReARM keeps.')
        expect(snapshot.find('[data-testid="upstream-snapshot-file-2-1"]').text()).toContain('abcdef012345')
        expect(snapshot.find('[data-testid="upstream-snapshot-file-2-1"]').text()).not.toContain('abcdef0123456')
        const line = snapshot.find('[data-testid="upstream-snapshot-vex-import"]').text()
        expect(line).toContain('Imported')
        expect(line).toContain('VEX offered for review')
        expect(line).toContain('7 statements, 5 offered for review, 1 accepted, 1 unmatched, 0 with errors')
        await w.find('[data-testid="upstream-snapshot"] .n-collapse-item__header-main').trigger('click')
        await w.find('[data-testid="upstream-snapshot"] .n-collapse-item__header-main').trigger('click')
        await flushPromises()
        expect(sent(GET_ARTIFACT_VEX_IMPORT)).toHaveLength(1)
    })

    it('a refused import read leaves the line out and shows no error', async () => {
        answer({ origin: release(), source: SOURCE_ROW, follows: [], vexImport: refusal(NOT_AUTHORIZED) })
        const w = await mountMarker('RELEASE')
        await w.find('[data-testid="upstream-snapshot"] .n-collapse-item__header-main').trigger('click')
        await flushPromises()
        expect(sent(GET_ARTIFACT_VEX_IMPORT)).toHaveLength(1)
        expect(w.find('[data-testid="upstream-snapshot-vex-import"]').exists()).toBe(false)
        expect(w.text()).not.toContain(NOT_AUTHORIZED)
        expect(w.find('[data-testid="upstream-snapshot-version-2"]').exists()).toBe(true)
    })

    it('a release whose item a follow covers carries the follow line too', async () => {
        answer({ origin: release({ presence: 'PRESENT', goneSince: null, oneOff: null }), source: SOURCE_ROW, follows: [coveringFollow()] })
        const w = await mountMarker('RELEASE')
        expect(w.find('[data-testid="upstream-marker-follow-link"]').exists()).toBe(true)
        expect(w.find('[data-testid="upstream-marker-gone"]').exists()).toBe(false)
        expect(w.find('[data-testid="upstream-marker-one-off"]').exists()).toBe(false)
        expect(w.find('[data-testid="upstream-marker-follow-item"]').exists()).toBe(false)
    })
})
