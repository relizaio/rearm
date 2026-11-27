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
import { print } from 'graphql'

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
    GET_ARTIFACT_VEX_IMPORT, GET_COMPONENT_INGESTED_FROM, GET_MARKER_FOLLOW, GET_RELEASE_INGESTED_FROM,
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
const OTHER = 'eeeeeeee-0000-4000-8000-000000000002'
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

/** The row GET_MARKER_FOLLOW answers. */
function followRow (stopReason: string | null = null, uuid = FOLLOW): any {
    return { uuid, lastRun: { state: 'STOPPED', stopReason, finishedDate: '2026-11-21T10:00:00Z' } }
}

const VEX_IMPORT = { state: 'IMPORTED', reason: null, modeUsed: 'STAGE', storedDate: '2026-11-06T10:00:00Z', finishedDate: '2026-11-06T10:01:00Z',
    statementsTotal: 7, proposalsStaged: 5, proposalsAccepted: 1, statementsUnmatched: 1, statementsErrored: 0 }

/** item: what componentDetails.ingestedFrom of a release answers. */
interface Answers { origin?: any, source?: any, follow?: any, item?: any, vexImport?: any }

/** Reads are answered by document; an Error answer is thrown. */
function answer (a: Answers) {
    query.mockImplementation(async (args: any) => {
        const give = (v: any, data: any) => { if (v instanceof Error) throw v; return { data } }
        if (args.query === GET_COMPONENT_INGESTED_FROM) return give(a.origin, { component: { uuid: COMPONENT, ingestedFrom: a.origin } })
        if (args.query === GET_RELEASE_INGESTED_FROM) {
            return give(a.origin, { release: { uuid: RELEASE, ingestedFrom: a.origin, componentDetails: { ingestedFrom: a.item ?? null } } })
        }
        if (args.query === GET_UPSTREAM_SOURCE) return give(a.source, { upstreamSource: a.source })
        if (args.query === GET_MARKER_FOLLOW) return give(a.follow, { upstreamFollow: a.follow ?? null })
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

const { GET_UPSTREAM_OFFERED_ITEMS } = await import('@/utils/upstreamOfferQueries')

// T-1163 (TPF-243 design 3.2 and 3.8, threat model rows iv and v): the marker opens the start of a
// follow by uuid and never reads the source's list by itself; a reader and the CE edition get no
// dialog and no button.
describe('T-1163: the source list from the marker', () => {
    const listCalls = () => sent(GET_UPSTREAM_OFFERED_ITEMS)

    it('a reader gets no dialog, no button and no list request', async () => {
        answer({ origin: item({ updateState: 'NO_LONGER_UPDATED' }), source: SOURCE_ROW })
        const w = await mountMarker('ITEM', false)
        expect(w.find('[data-testid="upstream-marker-follow-item"]').exists()).toBe(false)
        expect(w.findComponent(StartModal).exists()).toBe(false)
        expect(listCalls()).toEqual([])
        w.unmount()
    })

    it('CE gets no dialog, no button and no request at all', async () => {
        answer({ origin: item({ updateState: 'NO_LONGER_UPDATED' }), source: SOURCE_ROW })
        getters.myuser = { installationType: 'OSS' }
        const w = await mountMarker('ITEM', true)
        expect(w.find('[data-testid="upstream-marker-follow-item"]').exists()).toBe(false)
        expect(w.findComponent(StartModal).exists()).toBe(false)
        expect(query).not.toHaveBeenCalled()
        w.unmount()
    })

    it('an administrator opens the dialog by uuid and no list request is sent', async () => {
        answer({ origin: item({ updateState: 'NO_LONGER_UPDATED' }), source: SOURCE_ROW })
        const w = await mountMarker('ITEM', true)
        await w.get('[data-testid="upstream-marker-follow-item"]').trigger('click')
        await flushPromises()
        expect(w.findComponent(StartModal).exists()).toBe(true)
        expect(w.find('[data-testid="upstream-start-uuid"]').exists()).toBe(true)
        expect(listCalls()).toEqual([])
        w.unmount()
    })
})

describe('T-580: the gate and an item that was not ingested', () => {
    for (const subject of ['ITEM', 'RELEASE'] as const) {
        it(`${subject}: OSS and a user not loaded yet render nothing and send nothing`, async () => {
            answer({ origin: subject === 'ITEM' ? item() : release(), source: SOURCE_ROW })
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
            answer({ origin: null, source: SOURCE_ROW })
            const w = await mountMarker(subject)
            expect(w.find('.upstream-marker').exists()).toBe(false)
            expect(query).toHaveBeenCalledTimes(1)
            expect(query.mock.calls[0][0].query).toBe(subject === 'ITEM' ? GET_COMPONENT_INGESTED_FROM : GET_RELEASE_INGESTED_FROM)
            // A release tells its host that nothing of it is a snapshot; an item tells nothing.
            expect(w.emitted('snapshot-artifacts')).toEqual(subject === 'RELEASE' ? [[[]]] : undefined)
        })
    }
})

// T-1090 to T-1095: the follow comes from the item's own fields (activeFollow, else the oldest of
// coveringFollows) and is read by its uuid; the link and its lines only from an answered read, and
// no list of the source's follows is sent.
describe('T-1090 to T-1095: the follow from the item\'s own fields, read by its uuid', () => {
    const MARKER_READS = [GET_COMPONENT_INGESTED_FROM, GET_RELEASE_INGESTED_FROM, GET_UPSTREAM_SOURCE, GET_MARKER_FOLLOW]
    const roles = [['reader', false], ['administrator', true]] as const
    const byId = (w: any, id: string) => w.find(`[data-testid="${id}"]`)
    const linkOf = (w: any) => {
        const link = byId(w, 'upstream-marker-follow-link')
        return link.exists() ? JSON.parse(link.attributes('data-to')!) : null
    }
    const routeTo = (uuid: string) => ({ name: 'UpstreamFollow', params: { orguuid: ORG, uuid } })
    const followReads = () => sent(GET_MARKER_FOLLOW).map(c => c[0].variables)

    for (const [role, isOrgAdmin] of roles) {
        it(`T-1090 ITEM, ${role}: its own follow first, read by uuid; the link, the last sync and the item-gone line; no button`, async () => {
            answer({ origin: item({ activeFollow: FOLLOW, coveringFollows: [OTHER] }), source: SOURCE_ROW, follow: followRow('ITEM_GONE') })
            const w = await mountMarker('ITEM', isOrgAdmin)
            expect(followReads()).toEqual([{ uuid: FOLLOW }])
            expect(sent(GET_MARKER_FOLLOW)[0][0].fetchPolicy).toBe('no-cache')
            expect(linkOf(w)).toEqual(routeTo(FOLLOW))
            expect(byId(w, 'upstream-marker-last-sync').text()).toContain('Last sync of its follow: Stopped')
            expect(byId(w, 'upstream-marker-item-gone').text()).toContain(runStopSentence.ITEM_GONE)
            expect(byId(w, 'upstream-marker-follow-item').exists()).toBe(false)
        })
    }

    for (const subject of ['ITEM', 'RELEASE'] as const) {
        for (const [role, isOrgAdmin] of roles) {
            for (const followed of [true, false]) {
                it(`T-1091 ${subject}, ${role}, ${followed ? 'followed' : 'not followed'}: only the marker's own reads, none lists the follows`, async () => {
                    const fields = { activeFollow: followed ? FOLLOW : null, coveringFollows: [] }
                    answer(subject === 'ITEM'
                        ? { origin: item(fields), source: SOURCE_ROW, follow: followRow() }
                        : { origin: release(), item: { uuid: ITEM, ...fields }, source: SOURCE_ROW, follow: followRow() })
                    const w = await mountMarker(subject, isOrgAdmin)
                    expect(byId(w, 'upstream-marker-' + subject).exists()).toBe(true)
                    expect(query.mock.calls.length).toBeGreaterThan(0)
                    for (const [args] of query.mock.calls) {
                        expect(MARKER_READS).toContain(args.query)
                        expect(print(args.query)).not.toContain('upstreamFollows')
                    }
                    // The source row is counted apart: whether it is read depends on the role and the subject, not on the follow.
                    expect(query.mock.calls.filter(c => c[0].query !== GET_UPSTREAM_SOURCE)).toHaveLength(followed ? 2 : 1)
                    expect(sent(GET_UPSTREAM_SOURCE).length).toBeLessThanOrEqual(1)
                    expect(followReads()).toEqual(followed ? [{ uuid: FOLLOW }] : [])
                })
            }
        }
    }

    for (const shape of ['null and empty', 'absent'] as const) {
        for (const [role, isOrgAdmin] of roles) {
            it(`T-1092 ITEM, ${role}, the fields ${shape}: no follow read and no follow line; the button for the administrator only`, async () => {
                const o = item(shape === 'absent' ? {} : { activeFollow: null, coveringFollows: [] })
                expect('activeFollow' in o).toBe(shape !== 'absent')
                expect('coveringFollows' in o).toBe(shape !== 'absent')
                answer({ origin: o, source: SOURCE_ROW, follow: followRow() })
                const w = await mountMarker('ITEM', isOrgAdmin)
                expect(sent(GET_MARKER_FOLLOW)).toHaveLength(0)
                expect(byId(w, 'upstream-marker-follow').exists()).toBe(false)
                expect(byId(w, 'upstream-marker-follow-link').exists()).toBe(false)
                expect(byId(w, 'upstream-marker-follow-item').exists()).toBe(isOrgAdmin)
            })
        }
    }

    const FAILED = 'The connection was reset'
    for (const failure of ['refused', 'failed'] as const) {
        for (const [role, isOrgAdmin] of roles) {
            it(`T-1093 ITEM, ${role}, the follow read ${failure}: no follow line, no error text, one read, no button`, async () => {
                answer({ origin: item({ activeFollow: FOLLOW, coveringFollows: [] }), source: SOURCE_ROW,
                    follow: failure === 'refused' ? refusal(NOT_AUTHORIZED) : new Error(FAILED) })
                const w = await mountMarker('ITEM', isOrgAdmin)
                for (const id of ['upstream-marker-follow', 'upstream-marker-follow-link', 'upstream-marker-last-sync', 'upstream-marker-item-gone',
                    'upstream-marker-follow-item']) {
                    expect(byId(w, id).exists(), id).toBe(false)
                }
                expect(w.text()).not.toContain(NOT_AUTHORIZED)
                expect(w.text()).not.toContain(FAILED)
                expect(byId(w, 'upstream-marker-headline').text()).toContain(': Product ' + HOSTILE)
                expect(byId(w, 'upstream-marker-update-state').text()).toBe('Kept up to date')
                expect(followReads()).toEqual([{ uuid: FOLLOW }])
            })
        }
    }

    for (const [role, isOrgAdmin] of roles) {
        it(`T-1094 ITEM, ${role}, covered only through product follows: the oldest one's link and last sync, no item-gone line, no button`, async () => {
            answer({ origin: item({ activeFollow: null, coveringFollows: [FOLLOW, OTHER] }), source: SOURCE_ROW, follow: followRow('ITEM_GONE') })
            const w = await mountMarker('ITEM', isOrgAdmin)
            expect(followReads()).toEqual([{ uuid: FOLLOW }])
            expect(linkOf(w)).toEqual(routeTo(FOLLOW))
            expect(byId(w, 'upstream-marker-last-sync').text()).toContain('Last sync of its follow: Stopped')
            expect(byId(w, 'upstream-marker-item-gone').exists()).toBe(false)
            expect(byId(w, 'upstream-marker-follow-item').exists()).toBe(false)
        })
    }

    it('T-1095 (a) RELEASE: the follow of the item of its component, read by uuid', async () => {
        answer({ origin: release(), item: { uuid: ITEM, activeFollow: FOLLOW, coveringFollows: [] }, source: SOURCE_ROW, follow: followRow() })
        const w = await mountMarker('RELEASE')
        expect(followReads()).toEqual([{ uuid: FOLLOW }])
        expect(linkOf(w)).toEqual(routeTo(FOLLOW))
    })

    it('T-1095 (b) RELEASE whose component carries no item: no follow line and no follow read', async () => {
        answer({ origin: release(), item: null, source: SOURCE_ROW, follow: followRow() })
        const w = await mountMarker('RELEASE')
        expect(byId(w, 'upstream-marker-RELEASE').exists()).toBe(true)
        expect(sent(GET_MARKER_FOLLOW)).toHaveLength(0)
        expect(byId(w, 'upstream-marker-follow').exists()).toBe(false)
    })

    it('T-1095 (c) ITEM whose activeFollow is no uuid: no follow read and no link', async () => {
        answer({ origin: item({ activeFollow: HOSTILE }), source: SOURCE_ROW, follow: followRow() })
        const w = await mountMarker('ITEM', true)
        expect(sent(GET_MARKER_FOLLOW)).toHaveLength(0)
        expect(byId(w, 'upstream-marker-follow-link').exists()).toBe(false)
        expect(w.find('img').exists()).toBe(false)
    })

    it('T-1095 (d) ITEM whose follow read answers another uuid: no link and no line of that row', async () => {
        answer({ origin: item({ activeFollow: FOLLOW, coveringFollows: [] }), source: SOURCE_ROW, follow: followRow('ITEM_GONE', OTHER) })
        const w = await mountMarker('ITEM')
        expect(followReads()).toEqual([{ uuid: FOLLOW }])
        expect(byId(w, 'upstream-marker-follow-link').exists()).toBe(false)
        expect(byId(w, 'upstream-marker-last-sync').exists()).toBe(false)
        expect(byId(w, 'upstream-marker-item-gone').exists()).toBe(false)
    })
})

describe('T-581: the marker of a product or component', () => {
    it('(a) the source and the follows refused: the name from the item, the upstream name as text, the adoption sentence, no follow line, no error', async () => {
        answer({ origin: item({ activeFollow: FOLLOW }), source: refusal(NOT_AUTHORIZED), follow: refusal(NOT_AUTHORIZED) })
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
        expect(sent(GET_MARKER_FOLLOW)[0][0].variables).toEqual({ uuid: FOLLOW })
    })

    it('(b) the item is followed itself and its last run stopped with ITEM_GONE', async () => {
        answer({ origin: item({ activeFollow: FOLLOW, coveringFollows: [] }), source: SOURCE_ROW, follow: followRow('ITEM_GONE') })
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
            answer({ origin: item({ updateState: 'NO_LONGER_UPDATED' }), source: SOURCE_ROW })
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
            answer({ origin: item(), source: ROW })
            const w = await mountMarker('ITEM', isOrgAdmin)
            expect(w.text()).toContain(headlineOf('ITEM', 'Acme TEA'))
            expect(w.text()).not.toContain('Row name')
            const reads = sent(GET_UPSTREAM_SOURCE)
            expect(reads).toHaveLength(isOrgAdmin ? 1 : 0)
            if (isOrgAdmin) expect(reads[0][0].variables).toEqual({ uuid: SOURCE })
        })

        it(`T-1076 RELEASE, ${isOrgAdmin ? 'administrator' : 'reader'}: the name from the release, no source read, the refusal not shown`, async () => {
            answer({ origin: release(), source: refusal(NOT_AUTHORIZED) })
            const w = await mountMarker('RELEASE', isOrgAdmin)
            expect(w.text()).toContain(headlineOf('RELEASE', 'Acme TEA'))
            expect(sent(GET_UPSTREAM_SOURCE)).toHaveLength(0)
            expect(w.text()).not.toContain(NOT_AUTHORIZED)
        })
    }

    // A follow named on the item hides the button by itself, so the source refusal is checked on an
    // item that names none, and the follow refusal on one whose activeFollow is read.
    it('T-1077 ITEM, administrator, the source refused and no follow named: the name stays, no button, no follow read, no error', async () => {
        answer({ origin: item(), source: refusal(NOT_AUTHORIZED) })
        const w = await mountMarker('ITEM', true)
        expect(w.text()).toContain(headlineOf('ITEM', 'Acme TEA'))
        expect(sent(GET_UPSTREAM_SOURCE)).toHaveLength(1)
        expect(sent(GET_MARKER_FOLLOW)).toHaveLength(0)
        expect(w.find('[data-testid="upstream-marker-follow-item"]').exists()).toBe(false)
        expect(w.find('[data-testid="upstream-marker-follow"]').exists()).toBe(false)
        expect(w.text()).not.toContain(NOT_AUTHORIZED)
    })

    it('T-1077 ITEM, administrator, the source and the follow read refused: the name stays, one follow read, no follow line or link, no error', async () => {
        answer({ origin: item({ activeFollow: FOLLOW }), source: refusal(NOT_AUTHORIZED), follow: refusal(NOT_AUTHORIZED) })
        const w = await mountMarker('ITEM', true)
        expect(w.text()).toContain(headlineOf('ITEM', 'Acme TEA'))
        expect(sent(GET_UPSTREAM_SOURCE)).toHaveLength(1)
        expect(sent(GET_MARKER_FOLLOW).map(c => c[0].variables)).toEqual([{ uuid: FOLLOW }])
        expect(w.find('[data-testid="upstream-marker-follow"]').exists()).toBe(false)
        expect(w.find('[data-testid="upstream-marker-follow-link"]').exists()).toBe(false)
        expect(w.find('[data-testid="upstream-marker-last-sync"]').exists()).toBe(false)
        expect(w.find('[data-testid="upstream-marker-item-gone"]').exists()).toBe(false)
        expect(w.find('[data-testid="upstream-marker-follow-item"]').exists()).toBe(false)
        expect(w.text()).not.toContain(NOT_AUTHORIZED)
    })

    for (const subject of ['ITEM', 'RELEASE'] as const) {
        for (const shape of ['null', 'absent', 'empty'] as const) {
            it(`T-1078 ${subject}, sourceName ${shape}: "an upstream source", never the name of the source row`, async () => {
                const o = origin(subject, { sourceName: shape === 'null' ? null : '' })
                if (shape === 'absent') delete o.sourceName
                expect('sourceName' in o).toBe(shape !== 'absent')
                answer({ origin: o, source: ROW })
                const w = await mountMarker(subject, true)
                expect(w.text()).toContain(headlineOf(subject, 'an upstream source'))
                expect(w.text()).not.toContain('Row name')
            })
        }

        it(`T-1079 ${subject}: a markup name is shown as text and creates no element`, async () => {
            answer({ origin: origin(subject, { sourceName: HOSTILE }), source: ROW })
            const w = await mountMarker(subject, true)
            expect(w.text()).toContain(headlineOf(subject, HOSTILE))
            expect(w.find('img').exists()).toBe(false)
            expect(w.find('[onerror]').exists()).toBe(false)
        })
    }

    it('T-1079 ITEM: the administrator flag set after the read brings one source read and the button', async () => {
        answer({ origin: item(), source: ROW })
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
        answer({ origin: release(), source: SOURCE_ROW, vexImport: VEX_IMPORT })
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
        answer({ origin: release(), source: SOURCE_ROW, vexImport: VEX_IMPORT })
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
        answer({ origin: release(), source: SOURCE_ROW, vexImport: refusal(NOT_AUTHORIZED) })
        const w = await mountMarker('RELEASE')
        await w.find('[data-testid="upstream-snapshot"] .n-collapse-item__header-main').trigger('click')
        await flushPromises()
        expect(sent(GET_ARTIFACT_VEX_IMPORT)).toHaveLength(1)
        expect(w.find('[data-testid="upstream-snapshot-vex-import"]').exists()).toBe(false)
        expect(w.text()).not.toContain(NOT_AUTHORIZED)
        expect(w.find('[data-testid="upstream-snapshot-version-2"]').exists()).toBe(true)
    })

    it('a release whose item a follow covers carries the follow line too', async () => {
        answer({ origin: release({ presence: 'PRESENT', goneSince: null, oneOff: null }), item: { uuid: ITEM, activeFollow: FOLLOW, coveringFollows: [] },
            source: SOURCE_ROW, follow: followRow() })
        const w = await mountMarker('RELEASE')
        expect(w.find('[data-testid="upstream-marker-follow-link"]').exists()).toBe(true)
        expect(w.find('[data-testid="upstream-marker-gone"]').exists()).toBe(false)
        expect(w.find('[data-testid="upstream-marker-one-off"]').exists()).toBe(false)
        expect(w.find('[data-testid="upstream-marker-follow-item"]').exists()).toBe(false)
    })
})
