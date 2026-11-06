// @vitest-environment happy-dom
//
// TPF-159: the subscription editor's "Include board document releases" switch and the list marker.
// The server's upsert REPLACES the subscription row from the input, so an omitted
// includeBoardDocuments clears an opt-in made elsewhere (through the API). These rows pin that the
// editor sends the field on EVERY save path (create, prefilled create, edit, edit after a template
// pick or a mode change), in both filter modes, with the held value whether or not the switch is
// shown. The GraphQL client is answered by operation name; the modal renders in place.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { readFileSync } from 'fs'
import { resolve } from 'path'

const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
vi.mock('@/utils/keycloak', () => ({ default: { token: 'test', updateToken: vi.fn(), login: vi.fn() } }))
vi.mock('vuex', () => ({
    useStore: () => ({ dispatch: vi.fn(async () => null), getters: { perspectivesOfOrg: () => [] } }),
}))
let routeQuery: Record<string, any> = {}
vi.mock('vue-router', () => ({
    useRoute: () => ({ query: routeQuery, path: '/org' }),
    useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}))
vi.mock('naive-ui', async (importOriginal) => {
    const actual: any = await importOriginal()
    return {
        ...actual,
        useDialog: () => ({ warning: vi.fn(), info: vi.fn(), success: vi.fn() }),
        useMessage: () => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn(), loading: () => ({ destroy: vi.fn() }) }),
    }
})

const { default: Pane } = await import('./SubscriptionsOfOrg.vue')
const { NSelect, NSwitch, NInput } = await import('naive-ui')

const SOURCE = readFileSync(resolve(__dirname, 'SubscriptionsOfOrg.vue'), 'utf8')
const COMMON_SOURCE = readFileSync(resolve(__dirname, '../utils/notificationsCommon.ts'), 'utf8')
const norm = (s: string) => s.replace(/\s+/g, ' ').trim()

const ORG = 'o1'
const NINE = ['RELEASE_CREATED', 'RELEASE_LIFECYCLE_CHANGED', 'RELEASE_BOM_DIFF', 'RELEASE_TEA_PUBLISHED',
    'RELEASE_TEA_HIDDEN', 'RELEASE_SUBMISSION_CREATED', 'RELEASE_SUBMISSION_WITHDRAWN', 'APPROVAL_REQUESTED',
    'APPROVAL_RESOLVED']
const CEL = 'event.severity == "HIGH"'

const TOOLTIP_TEXT = 'Off: releases of board document components (a board\'s document rounds) are left out of this'
    + ' subscription even though their event types are selected, before any filter runs. On: the document rounds'
    + ' of every board in this organization, with their commit messages and links, go to this subscription\'s'
    + ' channels, whose recipients need no access to the boards. Approval requests on a document round reach the'
    + ' chosen approvers\' inboxes either way.'
const HINT_TEXT = 'Off for every subscription unless turned on here, including subscriptions that existed before'
    + ' this switch. A filter never needs to exclude board document releases; turn this on and narrow with'
    + ' event.componentKind instead.'
const CEL_HELP_TEXT = 'On release and approval events event.componentKind is the component\'s kind as a string:'
    + ' GENERIC, HELM or BOARD_DOCUMENT, empty on events written before it existed; compare it with == or !=.'
    + ' Board document releases are left out before this filter runs unless "Include board document releases"'
    + ' is on, so a filter never needs to exclude them; use it to narrow after opting in. A filter naming it on a'
    + ' subscription that also selects a non-release event type is refused at save unless guarded with'
    + ' has(event.componentKind).'

function row (over: Record<string, any>): any {
    return {
        uuid: 'u-' + (over.name || 'x'), org: ORG, resourceGroup: null, name: 'x', status: 'ACTIVE',
        eventTypes: ['RELEASE_CREATED'], filter: '{"mode":"PRESET"}', routes: '[{"channels":["ch-1"]}]',
        dedupWindowMinutes: null, rateLimit: null, managedByTeam: null, revision: 3, ...over,
    }
}
const ROW_ON = row({ name: 'docs-on', includeBoardDocuments: true })
const ROW_OFF = row({ name: 'docs-off', includeBoardDocuments: false })
const ROW_NULL = row({ name: 'docs-null', includeBoardDocuments: null })
const ROW_LEGACY = row({ name: 'docs-legacy' })
const ROW_MIRROR_ONLY = row({ name: 'docs-mirror', includeBoardDocuments: false,
    filter: '{"mode":"PRESET","presetConfig":{"includeBoardDocuments":true}}' })
const ROW_ADVANCED = row({ name: 'docs-advanced', includeBoardDocuments: true,
    filter: JSON.stringify({ mode: 'ADVANCED', celExpression: CEL }) })
const ROW_MANAGED = row({ name: 'team-feed', managedByTeam: 't1', includeBoardDocuments: null })

const operation = (opts: any) => (opts.query ?? opts.mutation)?.definitions?.[0]?.name?.value

function answers (rows: any[]) {
    query.mockImplementation(async (opts: any) => {
        switch (operation(opts)) {
        case 'notificationSubscriptions': return { data: { notificationSubscriptions: structuredClone(rows) } }
        case 'notificationChannels': return { data: { notificationChannels: [
            { uuid: 'ch-1', org: ORG, resourceGroup: null, name: 'slack', type: 'SLACK', status: 'ACTIVE', revision: 1 }] } }
        case 'notificationChannelGroups': return { data: { notificationChannelGroups: [] } }
        case 'getTeamsForRoutes': return { data: { getTeams: [] } }
        default: throw new Error('unexpected query ' + operation(opts))
        }
    })
}

const mounted: VueWrapper[] = []
const global = { stubs: { Modal: { props: ['show'], template: '<div v-if="show"><slot/></div>' } } }

async function mountPane (rows: any[] = []) {
    answers(rows)
    const w = mount(Pane, { props: { orguuid: ORG, isWritable: true, installationType: 'SAAS' }, global })
    mounted.push(w)
    await flushPromises()
    return w
}

const byId = (w: any, id: string) => w.find('[data-testid="' + id + '"]')
const comp = (w: any, type: any, id: string) => w.findAllComponents(type).find((c: any) => c.attributes('data-testid') === id)
const switchOf = (w: any, id: string) => (id === 'sub-include-board-documents'
    ? byId(w, 'sub-include-board-documents-block').findComponent(NSwitch)
    : comp(w, NSwitch, id))
async function openCreate (w: any) {
    await byId(w, 'add-subscription').trigger('click')
    await flushPromises()
}
async function selectTypes (w: any, types: string[]) {
    comp(w, NSelect, 'sub-eventtypes').vm.$emit('update:value', types)
    await flushPromises()
}
async function setSwitch (w: any, id: string, on: boolean) {
    switchOf(w, id).vm.$emit('update:value', on)
    await flushPromises()
}
async function typeName (w: any, name: string) {
    w.findAllComponents(NInput)[0].vm.$emit('update:value', name)
    await flushPromises()
}
async function pickChannels (w: any) {
    comp(w, NSelect, 'route-channels').vm.$emit('update:value', ['ch-1'])
    await flushPromises()
}
const celInput = (w: any) => w.findAllComponents(NInput).find((c: any) => c.props('type') === 'textarea')
async function typeCel (w: any, text: string) {
    celInput(w).vm.$emit('update:value', text)
    await flushPromises()
}
async function save (w: any) {
    await byId(w, 'save-subscription').trigger('click')
    await flushPromises()
}
const sentInput = (n = 0) => mutate.mock.calls[n][0].variables.input
const rowOf = (w: any, name: string) => w.findAll('tbody tr').find((tr: any) => tr.text().includes(name))
async function editRow (w: any, name: string) {
    await rowOf(w, name).find('[data-testid="edit-subscription"]').trigger('click')
    await flushPromises()
}
async function createReady (w: any, types: string[] = ['RELEASE_CREATED']) {
    await openCreate(w)
    await typeName(w, 'docs')
    await selectTypes(w, types)
    await pickChannels(w)
}

describe('SubscriptionsOfOrg: Include board document releases (TPF-159)', () => {
    let errors: any

    beforeEach(() => {
        routeQuery = {}
        query.mockReset()
        mutate.mockReset()
        mutate.mockImplementation(async () => ({ data: { upsertNotificationSubscription: { uuid: 'new', revision: 1 } } }))
        errors = vi.spyOn(console, 'error').mockImplementation(() => {})
    })

    afterEach(() => {
        // T-19: no row logs an error.
        expect(errors).not.toHaveBeenCalled()
        mounted.splice(0).forEach(w => w.unmount())
        errors.mockRestore()
    })

    it('T-9: shown with any release-shaped type, hidden otherwise, both directions', async () => {
        const w = await mountPane()
        await openCreate(w)
        const block = () => byId(w, 'sub-include-board-documents-block').exists()
        expect(block()).toBe(false)
        for (const t of NINE) {
            await selectTypes(w, [t])
            expect(block(), t).toBe(true)
        }
        await selectTypes(w, ['NEW_VULN_AFFECTS_RELEASES'])
        expect(block()).toBe(false)
        await selectTypes(w, ['RELEASE_CREATED', 'NEW_VULN_AFFECTS_RELEASES'])
        expect(block()).toBe(true)
        await selectTypes(w, ['NEW_VULN_AFFECTS_RELEASES'])
        expect(block()).toBe(false)
        await selectTypes(w, ['INSTANCE_DEPLOYMENT_FAILED'])
        expect(block()).toBe(false)
        await selectTypes(w, ['AGENT_BOARD_ALERT'])
        expect(block()).toBe(false)
    })

    it('T-10: off by default, with the label, the hint and the tooltip texts', async () => {
        const w = await mountPane()
        await openCreate(w)
        await selectTypes(w, ['RELEASE_CREATED'])
        expect(switchOf(w, 'sub-include-board-documents').props('value')).toBe(false)
        expect(byId(w, 'sub-include-board-documents-block').text()).toContain('Include board document releases')
        expect(norm(byId(w, 'sub-include-board-documents-hint').text())).toBe(HINT_TEXT)
        // naive-ui renders a tooltip's content on hover only, so the tooltip is pinned at the source.
        const src = norm(SOURCE)
        const label = src.indexOf('Include board document releases <n-tooltip')
        expect(label).toBeGreaterThan(-1)
        const end = src.indexOf('</n-tooltip>', label)
        const tooltip = src.slice(label, end)
        expect(tooltip).toContain(TOOLTIP_TEXT)
        expect(tooltip.slice(tooltip.indexOf('</template>') + '</template>'.length).trim()).toBe(TOOLTIP_TEXT)
    })

    it('T-11: create in PRESET mode sends true when on and false when untouched', async () => {
        let w = await mountPane()
        await createReady(w)
        await setSwitch(w, 'sub-include-board-documents', true)
        await save(w)
        expect(sentInput().includeBoardDocuments).toBe(true)
        expect(sentInput().filter).toEqual({ mode: 'PRESET', celExpression: null })
        expect(sentInput().eventTypes).toEqual(['RELEASE_CREATED'])

        mutate.mockClear()
        w = await mountPane()
        await createReady(w)
        await save(w)
        expect(Object.prototype.hasOwnProperty.call(sentInput(), 'includeBoardDocuments')).toBe(true)
        expect(sentInput().includeBoardDocuments).toBe(false)
        expect(sentInput().filter).toEqual({ mode: 'PRESET', celExpression: null })
    })

    it('T-12: create in CUSTOM (CEL) mode sends the held value beside the untouched filter', async () => {
        for (const on of [true, false]) {
            mutate.mockClear()
            const w = await mountPane()
            await createReady(w)
            await setSwitch(w, 'filter-advanced-toggle', true)
            await typeCel(w, CEL)
            if (on) await setSwitch(w, 'sub-include-board-documents', true)
            await save(w)
            expect(Object.prototype.hasOwnProperty.call(sentInput(), 'includeBoardDocuments'), String(on)).toBe(true)
            expect(sentInput().includeBoardDocuments, String(on)).toBe(on)
            expect(sentInput().filter).toEqual({ mode: 'ADVANCED', celExpression: CEL })
            expect(sentInput().filter.celExpression).toBe(CEL)
            expect(JSON.stringify(sentInput().filter)).not.toContain('includeBoardDocuments')
        }
    })

    it('T-13: re-rendered from the stored field only', async () => {
        const w = await mountPane([ROW_ON, ROW_OFF, ROW_NULL, ROW_LEGACY, ROW_MIRROR_ONLY, ROW_ADVANCED])
        const want: Array<[string, boolean]> = [['docs-on', true], ['docs-off', false], ['docs-null', false],
            ['docs-legacy', false], ['docs-mirror', false], ['docs-advanced', true]]
        for (const [name, on] of want) {
            await editRow(w, name)
            expect(byId(w, 'sub-include-board-documents-block').exists(), name).toBe(true)
            expect(switchOf(w, 'sub-include-board-documents').props('value'), name).toBe(on)
            if (name === 'docs-advanced') {
                expect(switchOf(w, 'filter-advanced-toggle').props('value')).toBe(true)
                expect(celInput(w).props('value')).toBe(CEL)
            }
        }
    })

    it('T-14: an untouched edit round-trips the opt-in, also while hidden; off is sent when turned off', async () => {
        const w = await mountPane([ROW_ON, ROW_MIRROR_ONLY])
        await editRow(w, 'docs-on')
        await save(w)
        expect(sentInput(0).includeBoardDocuments).toBe(true)
        expect(sentInput(0).expectedRevision).toBe(3)
        expect(sentInput(0).uuid).toBe(ROW_ON.uuid)

        await editRow(w, 'docs-on')
        await selectTypes(w, ['NEW_VULN_AFFECTS_RELEASES'])
        expect(byId(w, 'sub-include-board-documents-block').exists()).toBe(false)
        await save(w)
        expect(sentInput(1).includeBoardDocuments).toBe(true)

        await editRow(w, 'docs-on')
        await setSwitch(w, 'sub-include-board-documents', false)
        await save(w)
        expect(sentInput(2).includeBoardDocuments).toBe(false)

        await editRow(w, 'docs-mirror')
        await save(w)
        expect(sentInput(3).includeBoardDocuments).toBe(false)
        expect(sentInput(3).filter.presetConfigJson).toBe('{"includeBoardDocuments":true}')
    })

    it('T-15: a team-managed row has no marker, a disabled Edit and the read-only sentence', async () => {
        const w = await mountPane([ROW_MANAGED, ROW_ON])
        const managed = rowOf(w, 'team-feed')
        expect(managed.find('[data-testid="sub-board-documents-marker"]').exists()).toBe(false)
        expect(managed.find('[data-testid="edit-subscription"]').attributes('disabled')).toBeDefined()
        expect(managed.text()).toContain('Managed by t1')
        const start = SOURCE.indexOf('function managedRowExplanation')
        const body = SOURCE.slice(start, SOURCE.indexOf('\n}', start))
        expect(body).toContain('\' Teams. It never includes board document releases.\'')
    })

    it('T-16: the list marker shows on a stored true only', async () => {
        const w = await mountPane([ROW_ON, ROW_OFF, ROW_NULL, ROW_LEGACY, ROW_MIRROR_ONLY, ROW_MANAGED])
        const markers = w.findAll('[data-testid="sub-board-documents-marker"]')
        expect(markers.length).toBe(1)
        expect(markers[0].text()).toBe('includes board documents')
        expect(rowOf(w, 'docs-on').find('[data-testid="sub-board-documents-marker"]').exists()).toBe(true)
        for (const r of w.findAll('tbody tr')) expect(r.text()).toContain('1 type(s)')
    })

    it('T-17: the CEL help names event.componentKind, only with the filter switch on', async () => {
        const w = await mountPane()
        await openCreate(w)
        await selectTypes(w, ['RELEASE_CREATED'])
        expect(byId(w, 'cel-help-component-kind').exists()).toBe(false)
        await setSwitch(w, 'filter-advanced-toggle', true)
        expect(norm(byId(w, 'cel-help-component-kind').text())).toBe(CEL_HELP_TEXT)
        const examples = w.findAll('.field-hint').find((d: any) => d.text().startsWith('A CEL boolean over the event'))
        expect(examples.text()).toContain('event.componentKind == "BOARD_DOCUMENT"')
        await setSwitch(w, 'filter-advanced-toggle', false)
        expect(byId(w, 'cel-help-component-kind').exists()).toBe(false)
    })

    it('T-18: wiring pins at the source', () => {
        const imports = SOURCE.slice(SOURCE.indexOf('import {\n    ChannelRow'), SOURCE.indexOf('} from \'@/utils/notificationsCommon\''))
        for (const name of ['boardDocumentsApplyTo', 'includesBoardDocuments', 'includeBoardDocumentsInput', 'BOARD_DOCUMENTS_MARKER']) {
            expect(imports, name).toContain(name)
        }
        expect(SOURCE).toContain('<n-switch v-model:value="subForm.includeBoardDocuments" data-testid="sub-include-board-documents" />')
        const tpl = SOURCE.indexOf('<template v-if="boardDocumentsApply">')
        const block = SOURCE.indexOf('data-testid="sub-include-board-documents-block"')
        expect(tpl).toBeGreaterThan(-1)
        expect(block).toBeGreaterThan(tpl)
        expect(SOURCE.slice(tpl, block)).not.toContain('</template>')
        expect(SOURCE.indexOf('data-testid="board-preset"')).toBeLessThan(block)
        expect(block).toBeLessThan(SOURCE.indexOf('When to deliver'))
        const fn = (name: string) => {
            const s = SOURCE.indexOf(name)
            return SOURCE.slice(s, SOURCE.indexOf('\n}', s))
        }
        expect(fn('function freshSubscriptionForm')).toContain('includeBoardDocuments: false')
        expect(fn('function openEditSubscription')).toContain('f.includeBoardDocuments = row.includeBoardDocuments === true')
        expect(fn('async function saveSubscription')).toContain('...includeBoardDocumentsInput(f.includeBoardDocuments)')
        expect(fn('async function saveSubscription')).not.toContain('boardDocumentsApply')
        expect(SOURCE).toContain('const boardDocumentsApply = computed<boolean>(() => boardDocumentsApplyTo(subForm.value.eventTypes))')
        const upsert = SOURCE.slice(SOURCE.indexOf('const UPSERT_SUBSCRIPTION_MUTATION'), SOURCE.indexOf('const SET_SUBSCRIPTION_STATUS_MUTATION'))
        expect(upsert).not.toContain('includeBoardDocuments')
        expect(COMMON_SOURCE).toMatch(/const SUBSCRIPTION_ENRICHMENT_FIELDS = '[^']*managedByTeam includeBoardDocuments'/)
    })

    it('T-21: hidden is not omitted: a loaded true is sent with the release types deselected, a loaded null as false', async () => {
        let w = await mountPane([ROW_ON])
        await editRow(w, 'docs-on')
        expect(switchOf(w, 'sub-include-board-documents').props('value')).toBe(true)
        await selectTypes(w, ['NEW_VULN_AFFECTS_RELEASES'])
        expect(byId(w, 'sub-include-board-documents-block').exists()).toBe(false)
        await save(w)
        expect(sentInput().includeBoardDocuments).toBe(true)
        expect(sentInput().eventTypes).toEqual(['NEW_VULN_AFFECTS_RELEASES'])
        expect(sentInput().uuid).toBe(ROW_ON.uuid)
        expect(sentInput().expectedRevision).toBe(3)

        mutate.mockClear()
        w = await mountPane([ROW_NULL])
        await editRow(w, 'docs-null')
        await selectTypes(w, ['NEW_VULN_AFFECTS_RELEASES'])
        await save(w)
        expect(Object.prototype.hasOwnProperty.call(sentInput(), 'includeBoardDocuments')).toBe(true)
        expect(sentInput().includeBoardDocuments).toBe(false)
    })

    describe('T-22: the field on every save (mode x loaded value x visibility)', () => {
        const cells: Array<[string, any, string]> = []
        for (const mode of ['PRESET', 'ADVANCED']) {
            for (const loaded of [ROW_ON, ROW_NULL]) {
                for (const visibility of ['visible', 'hidden']) cells.push([mode, loaded, visibility])
            }
        }
        it.each(cells)('edit %s, loaded %o, switch %s', async (mode, loaded, visibility) => {
            const r = { ...loaded, filter: mode === 'ADVANCED' ? JSON.stringify({ mode, celExpression: CEL }) : '{"mode":"PRESET"}' }
            const w = await mountPane([r])
            await editRow(w, r.name)
            if (visibility === 'hidden') await selectTypes(w, ['NEW_VULN_AFFECTS_RELEASES'])
            expect(byId(w, 'sub-include-board-documents-block').exists()).toBe(visibility === 'visible')
            await save(w)
            expect(Object.prototype.hasOwnProperty.call(sentInput(), 'includeBoardDocuments')).toBe(true)
            expect(sentInput().includeBoardDocuments).toBe(loaded.includeBoardDocuments === true)
            expect(sentInput().filter.mode).toBe(mode)
        })
        it.each(['PRESET', 'ADVANCED'])('create in %s with nothing touched sends false', async (mode) => {
            const w = await mountPane()
            await createReady(w)
            if (mode === 'ADVANCED') {
                await setSwitch(w, 'filter-advanced-toggle', true)
                await typeCel(w, CEL)
            }
            await save(w)
            expect(Object.prototype.hasOwnProperty.call(sentInput(), 'includeBoardDocuments')).toBe(true)
            expect(sentInput().includeBoardDocuments).toBe(false)
            expect(sentInput().filter.mode).toBe(mode)
        })
    })

    describe('T-23: the other save paths keep the field (beyond the design matrix)', () => {
        it('an opted-in edit that switches the filter mode keeps true, both directions', async () => {
            let w = await mountPane([ROW_ON])
            await editRow(w, 'docs-on')
            await setSwitch(w, 'filter-advanced-toggle', true)
            await typeCel(w, CEL)
            await save(w)
            expect(sentInput().filter).toEqual({ mode: 'ADVANCED', celExpression: CEL })
            expect(sentInput().includeBoardDocuments).toBe(true)

            mutate.mockClear()
            w = await mountPane([ROW_ADVANCED])
            await editRow(w, 'docs-advanced')
            await setSwitch(w, 'filter-advanced-toggle', false)
            await save(w)
            expect(sentInput().filter.mode).toBe('PRESET')
            expect(sentInput().includeBoardDocuments).toBe(true)
        })

        it('an opted-in edit that applies an agent-board template (types replaced, switch hidden) keeps true', async () => {
            const w = await mountPane([ROW_ON])
            await editRow(w, 'docs-on')
            await selectTypes(w, ['RELEASE_CREATED', 'AGENT_BOARD_ALERT'])
            const preset = comp(w, NSelect, 'board-preset')
            preset.vm.$emit('update:value', preset.props('options')[0].value)
            await flushPromises()
            expect(byId(w, 'sub-include-board-documents-block').exists()).toBe(false)
            await save(w)
            expect(sentInput().uuid).toBe(ROW_ON.uuid)
            expect(sentInput().includeBoardDocuments).toBe(true)
        })

        it('an opted-in edit that only renames keeps true', async () => {
            const w = await mountPane([ROW_ON])
            await editRow(w, 'docs-on')
            await typeName(w, 'docs-renamed')
            await save(w)
            expect(sentInput().name).toBe('docs-renamed')
            expect(sentInput().includeBoardDocuments).toBe(true)
        })

        it.each([
            ['newBoardSub', 'b1'],
            ['newInstanceSub', 'inst-uri'],
        ])('a deep-link prefilled create (%s) sends the key as false, or true once turned on', async (param, value) => {
            for (const on of [false, true]) {
                mutate.mockClear()
                routeQuery = { [param]: value }
                const w = await mountPane()
                await typeName(w, 'prefilled')
                await pickChannels(w)
                if (on) {
                    // The prefills carry no release-shaped type; add one so the switch shows.
                    const types = comp(w, NSelect, 'sub-eventtypes').props('value')
                    await selectTypes(w, [...types, 'RELEASE_CREATED'])
                    await setSwitch(w, 'sub-include-board-documents', true)
                }
                await save(w)
                expect(sentInput().uuid, param).toBeUndefined()
                expect(Object.prototype.hasOwnProperty.call(sentInput(), 'includeBoardDocuments'), param).toBe(true)
                expect(sentInput().includeBoardDocuments, param).toBe(on)
            }
        })
    })
})
