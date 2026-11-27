// @vitest-environment happy-dom
//
// The SAFETY_CONTEXT review inbox: pending rows offer accept and reject; an asset that declares
// conflicting levels cannot be accepted; a downgrade cannot be accepted without a comment; an
// unmatched level needs a release component before it can be accepted. The accept dialog shows the
// current level the accept compares with; several levels, or every pending level of one upload,
// can be rejected at once.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query, mutate } }))
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { orguuid: 'org-1' } }), RouterLink: { props: ['to'], template: '<a :href="to"><slot/></a>' } }))
vi.mock('sweetalert2', () => ({ default: { fire: vi.fn() } }))
const { default: Swal } = await import('sweetalert2')
const { default: Proposals } = await import('./SafetyContextProposals.vue')

// Modals teleport; render them in place when shown.
const modal = { props: ['show', 'title'], template: '<div v-if="show" class="modal" :data-title="title"><slot/></div>' }
const tooltip = { template: '<span><slot name="trigger"/><slot/></span>' }

function row (over: any) {
    return {
        uuid: 'p1', kind: 'PROPOSAL', org: 'org-1', proposalStatus: 'PENDING', releaseUuid: 'rel-1',
        componentPurl: 'pkg:generic/acme/fw@1.0.0', schemeKey: 'IEC-62304', levelRaw: 'Class C',
        levelNormalized: 'Class C', levelChange: 'NEW', gateRule: 'THIRD_PARTY_STAGE', matchStatus: 'MATCHED',
        issuerClassEffective: 'THIRD_PARTY', notes: [], sourceReleaseState: 'AVAILABLE', sourceComponentName: 'fw',
        sourceReleaseVersion: '1.0.0', ...over
    }
}

function record (over: any) {
    return {
        uuid: 'rec-1', kind: 'RECORD', org: 'org-1', recordStatus: 'ACTIVE', scope: 'COMPONENT', releaseUuid: 'rel-0',
        componentPurl: 'pkg:generic/acme/fw@1.0.0', schemeKey: 'IEC-62304', levelRaw: 'Class B', levelNormalized: 'Class B',
        acceptedVia: 'AUTO_SELF', acceptedAt: '2026-11-20T23:30:00Z', sourceReleaseState: 'AVAILABLE',
        sourceComponentName: 'fw', sourceReleaseVersion: '0.9.0', ...over
    }
}

function context (over: any = {}) {
    return { proposal: row({}), levelChange: 'UPGRADE', comparedWith: 'LATEST_FOR_PACKAGE', baseline: record({}),
        baselineDetail: 'FULL', baselineToken: 'tok', ...over }
}

function docName (doc: any): string {
    return doc?.definitions?.[0]?.name?.value ?? ''
}

function callsOf (fn: any, name: string): any[] {
    return fn.mock.calls.map((c: any) => c[0]).filter((c: any) => docName(c.query ?? c.mutation) === name)
}

/**
 * Mounts the inbox (or the release list) with queries routed by document: the list answers rows,
 * the decision context answers ctx (a function may answer per call), anything else what extra says.
 */
async function mounted (rows: any[], opts: { ctx?: any, extra?: Record<string, any>, props?: any } = {}) {
    query.mockImplementation(({ query: doc }: any) => {
        const name = docName(doc)
        if (name === 'GetSafetyContextProposals') return Promise.resolve({ data: { getSafetyContextProposals: rows } })
        if (name === 'GetSafetyContextProposalsByRelease') {
            return Promise.resolve({ data: { getSafetyContextProposalsByRelease: rows } })
        }
        if (name === 'GetSafetyContextDecisionContext') {
            const c = typeof opts.ctx === 'function' ? opts.ctx() : opts.ctx ?? context()
            return c instanceof Error ? Promise.reject(c)
                : c instanceof Promise ? c : Promise.resolve({ data: { getSafetyContextDecisionContext: c } })
        }
        if (opts.extra && name in opts.extra) return Promise.resolve(opts.extra[name])
        return Promise.reject(new Error('unexpected query ' + name))
    })
    const w = mount(Proposals, {
        props: opts.props ?? {},
        global: { stubs: { NModal: modal, Modal: modal, NTooltip: tooltip, Tooltip: tooltip, SafetyContextSourceModal: true } }
    })
    await flushPromises()
    return w
}

function button (w: any, title: string) {
    return w.findAll('button').find((b: any) => b.attributes('title') === title)
}

function openModal (w: any, title: string) {
    return w.findAll('.modal').find((m: any) => m.attributes('data-title') === title)
}

function modalButton (m: any, text: string) {
    return m.findAll('button').find((b: any) => b.text() === text)
}

async function openAccept (w: any) {
    await button(w, 'Accept')!.trigger('click')
    await flushPromises()
    return openModal(w, 'Accept safety level')
}

function gqlError (message: string) {
    return Object.assign(new Error(message), { errors: [{ message }] })
}

describe('SafetyContextProposals', () => {
    beforeEach(() => {
        query.mockReset(); mutate.mockReset(); mutate.mockResolvedValue({ data: {} }); vi.mocked(Swal.fire).mockClear()
    })

    it('lists pending proposals of the org and accepts one', async () => {
        const w = await mounted([row({})])
        expect(callsOf(query, 'GetSafetyContextProposals')[0].variables).toMatchObject({ org: 'org-1', status: 'PENDING' })
        expect(w.text()).toContain('pkg:generic/acme/fw@1.0.0')
        const m = await openAccept(w)
        await modalButton(m, 'Accept').trigger('click')
        await flushPromises()
        expect(mutate.mock.calls[0][0].variables).toEqual({ uuid: 'p1', component: null, comment: null, expectedBaseline: 'tok' })
    })

    it('does not offer accept for conflicting levels in one asset', async () => {
        const w = await mounted([row({ gateRule: 'INTRA_ASSET_CONFLICT', levelRaw: 'C | A', levelNormalized: null })])
        const accept = button(w, 'Conflicting levels: reject and correct the document')!
        expect(accept.attributes('disabled')).toBeDefined()
    })

    it('needs a comment to accept a downgrade', async () => {
        const w = await mounted([row({ levelChange: 'DOWNGRADE', gateRule: 'DOWNGRADE' })],
            { ctx: context({ levelChange: 'DOWNGRADE' }) })
        const m = await openAccept(w)
        const confirm = modalButton(m, 'Accept')
        expect(confirm.attributes('disabled')).toBeDefined()
        await m.find('textarea').setValue('re-assessed')
        expect(confirm.attributes('disabled')).toBeUndefined()
    })

    it('asks for a release component before accepting an unmatched level', async () => {
        const w = await mounted([row({ matchStatus: 'UNMATCHED', gateRule: 'UNMATCHED' })], { extra: {
            GetReleaseSbomComponentChoices: { data: { getReleaseSbomComponents: [
                { sbomComponentUuid: 'sc-1', component: { canonicalPurl: 'pkg:generic/acme/fw@1.0.0' } }] } } } })
        const m = await openAccept(w)
        expect(callsOf(query, 'GetReleaseSbomComponentChoices')[0].variables).toEqual({ releaseUuid: 'rel-1' })
        expect(modalButton(m, 'Accept').attributes('disabled')).toBeDefined()
    })

    it('says a level the upload mode staged was staged by the mode, not auto-accepted', async () => {
        const w = await mounted([row({ gateRule: 'SELF_AUTO', notes: ['USER_MODE_STAGE'], issuerClassEffective: 'SELF' })])
        expect(w.text()).toContain("Staged by the upload's import mode")
        expect(w.text()).not.toContain('auto-accepted')
    })

    it('switches the org inbox status with labelled buttons', async () => {
        const w = await mounted([row({})])
        expect(w.find('[data-testid="safety-context-status-filter"]').text()).toContain('Rejected')
        query.mockResolvedValueOnce({ data: { getSafetyContextProposals: [] } })
        const rejected = w.findAll('input[type="radio"]').find((i: any) => i.attributes('value') === 'REJECTED')!
        await rejected.setValue(true)
        await flushPromises()
        expect(query.mock.calls.at(-1)![0].variables).toMatchObject({ status: 'REJECTED' })
    })
})

describe('SafetyContextProposals accept dialog: the current level', () => {
    beforeEach(() => {
        query.mockReset(); mutate.mockReset(); mutate.mockResolvedValue({ data: {} }); vi.mocked(Swal.fire).mockClear()
    })

    const text = (m: any, id: string) => m.find(`[data-testid="${id}"]`).text()

    it('u1 FULL: every row with its exact text', async () => {
        const w = await mounted([row({ levelChange: 'UPGRADE' })], { ctx: context({
            baseline: record({ acceptComment: 'checked\nby QA' }) }) })
        const m = await openAccept(w)
        expect(callsOf(query, 'GetSafetyContextDecisionContext')[0].variables).toEqual({ uuid: 'p1' })
        expect(text(m, 'safety-current-level')).toBe('Class B (IEC-62304)')
        expect(text(m, 'safety-baseline-scope')).toBe('Component')
        expect(text(m, 'safety-baseline-compared')).toBe('latest level declared for this package')
        expect(text(m, 'safety-baseline-source')).toBe('fw 0.9.0')
        expect(m.find('[data-testid="safety-baseline-source"] a').attributes('href')).toBe('/release/show/rel-0')
        expect(text(m, 'safety-baseline-accepted')).toBe('automatically (own organization) on 2026-11-20 23:30 UTC')
        expect(text(m, 'safety-baseline-comment')).toBe('checked\nby QA')
        expect(m.find('[data-testid="safety-baseline-comment"]').attributes('style')).toContain('pre-wrap')
        expect(text(m, 'safety-level-change')).toBe('UPGRADE')
        expect(m.find('[data-testid="safety-level-change-moved"]').exists()).toBe(false)
        expect(modalButton(m, 'Accept').attributes('disabled')).toBeUndefined()
    })

    it.each([
        ['MANUAL', { acceptedByName: 'Dana' }, 'Dana on 2026-11-20 23:30 UTC'],
        ['MANUAL', { acceptedByName: null }, 'a user no longer in the organization on 2026-11-20 23:30 UTC'],
        ['AUTO_VENDOR', {}, 'automatically (vendor) on 2026-11-20 23:30 UTC']
    ])('u1 FULL: accepted %s %o', async (via, over, expected) => {
        const w = await mounted([row({})], { ctx: context({ baseline: record({ acceptedVia: via, ...over }) }) })
        const m = await openAccept(w)
        expect(text(m, 'safety-baseline-accepted')).toBe(expected)
        expect(m.find('[data-testid="safety-baseline-comment"]').exists()).toBe(false)
    })

    it('u1 FULL: an archived source release is linked and says archived', async () => {
        const w = await mounted([row({})], { ctx: context({ baseline: record({ sourceReleaseState: 'ARCHIVED' }) }) })
        const m = await openAccept(w)
        expect(text(m, 'safety-baseline-source')).toBe('fw 0.9.0 (archived)')
        expect(m.find('[data-testid="safety-baseline-source"] a').exists()).toBe(true)
    })

    it('u2 LEVEL_ONLY: "not visible to you" twice, no link', async () => {
        const w = await mounted([row({})], { ctx: context({ baselineDetail: 'LEVEL_ONLY', comparedWith: 'LATEST_ON_CHAIN',
            baseline: { kind: 'RECORD', scope: 'ORG', schemeKey: 'IEC-62304', levelRaw: 'Class B', levelNormalized: 'Class B',
                acceptedVia: 'MANUAL', acceptedAt: '2026-11-20T23:30:00Z', sourceReleaseState: 'NOT_VISIBLE' } }) })
        const m = await openAccept(w)
        expect(text(m, 'safety-current-level')).toBe('Class B (IEC-62304)')
        expect(text(m, 'safety-baseline-scope')).toBe('Organization')
        expect(text(m, 'safety-baseline-compared')).toBe('latest level for this package on this release\'s scopes')
        expect(text(m, 'safety-baseline-source')).toBe('not visible to you')
        expect(text(m, 'safety-baseline-accepted')).toBe('not visible to you, on 2026-11-20 23:30 UTC')
        expect(m.find('[data-testid="safety-baseline-source"] a').exists()).toBe(false)
    })

    it('u3 NEW: none declared, no baseline rows', async () => {
        const w = await mounted([row({})], { ctx: context({ levelChange: 'NEW', comparedWith: null, baseline: null,
            baselineDetail: null }) })
        const m = await openAccept(w)
        expect(text(m, 'safety-current-level')).toBe('None declared for this package on this release\'s scopes')
        expect(m.find('[data-testid="safety-baseline-scope"]').exists()).toBe(false)
        expect(modalButton(m, 'Accept').attributes('disabled')).toBeUndefined()
    })

    it('u3 loading: the loading text, Accept disabled', async () => {
        let resolve: (v: any) => void = () => {}
        const w = await mounted([row({})], { ctx: () => new Promise((r) => { resolve = r }) })
        const m = await openAccept(w)
        expect(text(m, 'safety-current-level')).toBe('Loading the current level...')
        expect(modalButton(m, 'Accept').attributes('disabled')).toBeDefined()
        resolve({ data: { getSafetyContextDecisionContext: context() } })
        await flushPromises()
        expect(modalButton(m, 'Accept').attributes('disabled')).toBeUndefined()
    })

    it('u3 query error: inline alert, no popup, Accept disabled', async () => {
        const w = await mounted([row({})], { ctx: gqlError('Safety context entry not found') })
        const m = await openAccept(w)
        expect(text(m, 'safety-context-error')).toBe('Could not load the current level: Safety context entry not found')
        expect(Swal.fire).not.toHaveBeenCalled()
        expect(modalButton(m, 'Accept').attributes('disabled')).toBeDefined()
    })

    it('u3 the scope refusal renders exactly', async () => {
        const w = await mounted([row({})], { ctx: gqlError(
            'You need Finding Analysis read and write at the COMPONENT scope to decide this level') })
        const m = await openAccept(w)
        expect(text(m, 'safety-context-error')).toBe('Could not load the current level: You need Finding Analysis read and '
            + 'write at the COMPONENT scope to decide this level')
        expect(modalButton(m, 'Accept').attributes('disabled')).toBeDefined()
    })

    it('u3 not pending: the already decided warning; closing refreshes the list', async () => {
        const w = await mounted([row({})], { ctx: gqlError('Safety context proposal is not pending (was ACCEPTED)') })
        const m = await openAccept(w)
        expect(text(m, 'safety-context-decided')).toBe('This proposal was already decided (ACCEPTED). Close to refresh the list.')
        expect(m.find('[data-testid="safety-context-error"]').exists()).toBe(false)
        expect(modalButton(m, 'Accept').attributes('disabled')).toBeDefined()
        const lists = callsOf(query, 'GetSafetyContextProposals').length
        await modalButton(m, 'Cancel').trigger('click')
        await flushPromises()
        expect(callsOf(query, 'GetSafetyContextProposals').length).toBe(lists + 1)
    })

    it('u4 recomputed DOWNGRADE on a row stored UPGRADE: the warning, comment required, expectedBaseline sent', async () => {
        const w = await mounted([row({ levelChange: 'UPGRADE', gateRule: 'THIRD_PARTY_STAGE' })],
            { ctx: context({ levelChange: 'DOWNGRADE', baselineToken: 'tok' }) })
        const m = await openAccept(w)
        expect(text(m, 'safety-level-change-moved'))
            .toBe('When it was uploaded this level compared as UPGRADE; against the current level it is now DOWNGRADE.')
        expect(text(m, 'safety-level-change')).toBe('DOWNGRADE')
        const confirm = modalButton(m, 'Accept')
        expect(confirm.attributes('disabled')).toBeDefined()
        await m.find('textarea').setValue('re-assessed')
        expect(confirm.attributes('disabled')).toBeUndefined()
        await confirm.trigger('click')
        await flushPromises()
        expect(mutate.mock.calls[0][0].variables)
            .toEqual({ uuid: 'p1', component: null, comment: 're-assessed', expectedBaseline: 'tok' })
    })

    it('u4 a stored DOWNGRADE that is now an UPGRADE needs no comment', async () => {
        const w = await mounted([row({ levelChange: 'DOWNGRADE', gateRule: 'DOWNGRADE' })], { ctx: context({ levelChange: 'UPGRADE' }) })
        const m = await openAccept(w)
        expect(modalButton(m, 'Accept').attributes('disabled')).toBeUndefined()
    })

    it('u5 the changed-baseline refusal: inline, the context reloads, the comment stays, no popup', async () => {
        const w = await mounted([row({})])
        const m = await openAccept(w)
        await m.find('textarea').setValue('my note')
        mutate.mockRejectedValueOnce(gqlError('The current level changed since you opened this proposal; review it again'))
        await modalButton(m, 'Accept').trigger('click')
        await flushPromises()
        expect(text(m, 'safety-accept-error'))
            .toBe('The current level changed since you opened this proposal; review it again')
        expect(callsOf(query, 'GetSafetyContextDecisionContext')).toHaveLength(2)
        expect((m.find('textarea').element as HTMLTextAreaElement).value).toBe('my note')
        expect(modalButton(m, 'Accept').attributes('disabled')).toBeUndefined()
        expect(Swal.fire).not.toHaveBeenCalled()
    })

    it('another accept refusal: inline, Accept stays enabled, no reload', async () => {
        const w = await mounted([row({})])
        const m = await openAccept(w)
        mutate.mockRejectedValueOnce(gqlError('Not authorized'))
        await modalButton(m, 'Accept').trigger('click')
        await flushPromises()
        expect(text(m, 'safety-accept-error')).toBe('Not authorized')
        expect(callsOf(query, 'GetSafetyContextDecisionContext')).toHaveLength(1)
        expect(modalButton(m, 'Accept').attributes('disabled')).toBeUndefined()
        expect(Swal.fire).not.toHaveBeenCalled()
    })
})

describe('SafetyContextProposals bulk reject', () => {
    beforeEach(() => {
        query.mockReset(); mutate.mockReset(); vi.mocked(Swal.fire).mockClear()
    })

    function table (w: any) {
        return w.findComponent({ name: 'DataTable' })
    }

    async function select (w: any, keys: string[]) {
        table(w).vm.$emit('update:checkedRowKeys', keys)
        await flushPromises()
    }

    function result (over: any) {
        return { data: { rejectSafetyContextProposals: { rejected: 0, alreadyDecided: 0, notPermitted: 0, notFound: 0,
            restored: 0, batch: null, rows: [], ...over } } }
    }

    const MULTI_BODY = 'Reject the 2 selected levels. Rejected levels stay rejected: the same level uploaded again by the '
        + 'same uploader is not staged again.'

    it('u6 multi-select: the body, the variables, the result lines', async () => {
        const w = await mounted([row({ uuid: 'p1' }), row({ uuid: 'p2' })])
        const bar = w.find('[data-testid="safety-reject-selected"]')
        expect(bar.text()).toBe('Reject selected (0)')
        expect(bar.attributes('disabled')).toBeDefined()
        await select(w, ['p1', 'p2'])
        expect(bar.text()).toBe('Reject selected (2)')
        await bar.trigger('click')
        const m = openModal(w, 'Reject safety levels')
        expect(m.find('[data-testid="safety-bulk-reject-body"]').text()).toBe(MULTI_BODY)
        expect(m.text()).toContain('Reason (required, recorded on every level)')
        const submit = m.find('[data-testid="safety-bulk-reject-submit"]')
        expect(submit.attributes('disabled')).toBeDefined()
        await m.find('textarea').setValue('   ')
        expect(submit.attributes('disabled')).toBeDefined()
        await m.find('textarea').setValue('wrong file')
        mutate.mockResolvedValueOnce(result({ rejected: 1, alreadyDecided: 1 }))
        await submit.trigger('click')
        await flushPromises()
        expect(mutate.mock.calls[0][0].variables)
            .toEqual({ org: 'org-1', selection: { proposals: ['p1', 'p2'] }, reason: 'wrong file' })
        const lines = m.findAll('[data-testid="safety-bulk-reject-line"]').map((d: any) => d.text())
        expect(lines).toEqual(['Rejected: 1.', 'Already decided by someone else, left as they are: 1.'])
        expect(modalButton(m, 'Close')).toBeDefined()
        expect(m.find('[data-testid="safety-bulk-reject-submit"]').exists()).toBe(false)
        expect(w.find('[data-testid="safety-reject-selected"]').text()).toBe('Reject selected (0)')
        expect(callsOf(query, 'GetSafetyContextProposals')).toHaveLength(2)
        expect(w.emitted('changed')).toHaveLength(1)
    })

    it('u6 nothing rejected, two not permitted: "Rejected: 0." and the not-permitted line', async () => {
        const w = await mounted([row({ uuid: 'p1' }), row({ uuid: 'p2' })])
        await select(w, ['p1', 'p2'])
        await w.find('[data-testid="safety-reject-selected"]').trigger('click')
        const m = openModal(w, 'Reject safety levels')
        await m.find('textarea').setValue('wrong file')
        mutate.mockResolvedValueOnce(result({ notPermitted: 2 }))
        await m.find('[data-testid="safety-bulk-reject-submit"]').trigger('click')
        await flushPromises()
        const alert = m.find('[data-testid="safety-bulk-reject-result"]')
        expect(alert.findAll('[data-testid="safety-bulk-reject-line"]').map((d: any) => d.text())).toEqual(['Rejected: 0.',
            'Not permitted (needs Finding Analysis write at the level\'s scope), left pending: 2.'])
        expect(alert.text()).not.toContain('Nothing to reject')
        expect(w.emitted('changed')).toBeUndefined()
    })

    it('u6 the Reject button is disabled while the call runs: a double click sends one call', async () => {
        const w = await mounted([row({ uuid: 'p1' })])
        await select(w, ['p1'])
        await w.find('[data-testid="safety-reject-selected"]').trigger('click')
        const m = openModal(w, 'Reject safety levels')
        await m.find('textarea').setValue('wrong file')
        let resolve: (v: any) => void = () => {}
        mutate.mockImplementationOnce(() => new Promise((r) => { resolve = r }))
        const submit = m.find('[data-testid="safety-bulk-reject-submit"]')
        await submit.trigger('click')
        expect(submit.attributes('disabled')).toBeDefined()
        await submit.trigger('click')
        expect(mutate).toHaveBeenCalledTimes(1)
        resolve(result({ rejected: 1 }))
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(1)
    })

    const UNDO_BODY = 'Undo the upload of the document urn:uuid:doc-1 v3, uploaded on fw 1.0.0: reject every pending level '
        + 'from it, including levels not listed on this page. Pending levels from an earlier upload that this one replaced '
        + 'come back to review. Uploading a corrected document afterwards stages its levels again. Levels this upload '
        + 'accepted automatically stay in force until a corrected level is reviewed.'

    async function openUndo (w: any) {
        await button(w, 'Undo this upload: reject all its pending levels')!.trigger('click')
        return openModal(w, 'Reject safety levels')
    }

    const docRow = () => row({ sourceArtifact: 'art-1', sourceDocumentHash: 'h1', docSerialNumber: 'urn:uuid:doc-1', docVersion: 3 })

    it('u7 document mode: the undo body, the selection, the restored line', async () => {
        const w = await mounted([docRow()])
        const m = await openUndo(w)
        expect(m.find('[data-testid="safety-bulk-reject-body"]').text()).toBe(UNDO_BODY)
        await m.find('textarea').setValue('wrong file')
        mutate.mockResolvedValueOnce(result({ rejected: 2, restored: 1 }))
        await m.find('[data-testid="safety-bulk-reject-submit"]').trigger('click')
        await flushPromises()
        expect(mutate.mock.calls[0][0].variables)
            .toEqual({ org: 'org-1', selection: { sourceArtifact: 'art-1', sourceDocumentHash: 'h1' }, reason: 'wrong file' })
        expect(m.findAll('[data-testid="safety-bulk-reject-line"]').map((d: any) => d.text()))
            .toEqual(['Rejected: 2.', 'Earlier pending levels this upload had replaced, back in review: 1.'])
    })

    it('u7 no undo action without the document, or on a row not pending', async () => {
        const w = await mounted([row({ uuid: 'p1', sourceArtifact: 'art-1' }),
            row({ uuid: 'p2', proposalStatus: 'ACCEPTED', sourceArtifact: 'art-1', sourceDocumentHash: 'h1' })])
        expect(button(w, 'Undo this upload: reject all its pending levels')).toBeUndefined()
    })

    it('u7 the all-zero result says there is nothing to reject', async () => {
        const w = await mounted([docRow()])
        const m = await openUndo(w)
        await m.find('textarea').setValue('wrong file')
        mutate.mockResolvedValueOnce(result({}))
        await m.find('[data-testid="safety-bulk-reject-submit"]').trigger('click')
        await flushPromises()
        expect(m.findAll('[data-testid="safety-bulk-reject-line"]').map((d: any) => d.text()))
            .toEqual(['Nothing to reject: no pending levels from this document.'])
    })

    it.each([
        'A reason is needed to reject several levels at once',
        'Internal server error (ref ab12cd34)'
    ])('u7 a refused or failed call: "Nothing was rejected." inline, no popup (%s)', async (message) => {
        const w = await mounted([docRow()])
        const m = await openUndo(w)
        await m.find('textarea').setValue('wrong file')
        mutate.mockRejectedValueOnce(gqlError(message))
        await m.find('[data-testid="safety-bulk-reject-submit"]').trigger('click')
        await flushPromises()
        expect(m.find('[data-testid="safety-bulk-reject-error"]').text()).toBe(`Nothing was rejected. ${message}`)
        expect(m.find('[data-testid="safety-bulk-reject-result"]').exists()).toBe(false)
        expect(Swal.fire).not.toHaveBeenCalled()
    })

    it('u7 a document without a serial number or version', async () => {
        const w = await mounted([row({ sourceArtifact: 'art-1', sourceDocumentHash: 'h1' })])
        const m = await openUndo(w)
        expect(m.find('[data-testid="safety-bulk-reject-body"]').text())
            .toContain('Undo the upload of the document this uploaded document, uploaded on fw 1.0.0: ')
    })

    it('u8 rows not pending have a disabled selection box', async () => {
        const w = await mounted([row({ uuid: 'p1' }), row({ uuid: 'p2', proposalStatus: 'ACCEPTED' })],
            { props: { orgUuid: 'org-1', releaseUuid: 'rel-1' } })
        const boxes = w.findAll('[data-col-key="__n_selection__"] .n-checkbox')
        const disabled = boxes.map((b: any) => b.classes().includes('n-checkbox--disabled'))
        expect(disabled.slice(-2)).toEqual([false, true])
    })

    it('u8 501 selected rows: the 500 text, and no call', async () => {
        const rows = Array.from({ length: 501 }, (_, i) => row({ uuid: `p${i}` }))
        const w = await mounted(rows)
        await select(w, rows.map((r) => r.uuid))
        const bar = w.find('[data-testid="safety-reject-selected"]')
        expect(w.find('[data-testid="safety-reject-too-many"]').text()).toBe('Select at most 500 proposals at a time')
        expect(bar.attributes('disabled')).toBeDefined()
        await bar.trigger('click')
        expect(openModal(w, 'Reject safety levels')).toBeUndefined()
        expect(mutate).not.toHaveBeenCalled()
    })

    it('the selection column shows in release mode and the inbox PENDING filter only', async () => {
        const w = await mounted([row({})])
        expect(w.find('[data-testid="safety-bulk-reject-bar"]').exists()).toBe(true)
        query.mockResolvedValueOnce({ data: { getSafetyContextProposals: [] } })
        const accepted = w.findAll('input[type="radio"]').find((i: any) => i.attributes('value') === 'ACCEPTED')!
        await accepted.setValue(true)
        await flushPromises()
        expect(w.find('[data-testid="safety-bulk-reject-bar"]').exists()).toBe(false)
    })
})

describe('SafetyContextProposals Release column, status and hostile values', () => {
    beforeEach(() => { query.mockReset(); mutate.mockReset(); vi.mocked(Swal.fire).mockClear() })

    function releaseCell (w: any, i: number) {
        return w.findAll('[data-col-key="releaseUuid"]')[i + 1]
    }

    it('u9 one row per release state: the text and whether it links', async () => {
        const w = await mounted([
            row({ uuid: 'a', releaseUuid: null, sourceReleaseState: null, sourceComponentName: null, sourceReleaseVersion: null }),
            row({ uuid: 'b', releaseUuid: 'rel-1', sourceReleaseState: 'AVAILABLE' }),
            row({ uuid: 'c', releaseUuid: 'rel-2', sourceReleaseState: 'ARCHIVED', sourceReleaseVersion: '0.5.0' }),
            row({ uuid: 'd', releaseUuid: 'rel-3', sourceReleaseState: 'DELETED', sourceComponentName: null }),
            row({ uuid: 'e', releaseUuid: 'rel-4', sourceReleaseState: 'NOT_VISIBLE', sourceComponentName: null })
        ])
        const expected: Array<[string, string | null]> = [
            ['-', null], ['fw 1.0.0', '/release/show/rel-1'], ['fw 0.5.0 (archived)', '/release/show/rel-2'],
            ['release no longer exists', null], ['not visible to you', null]
        ]
        expected.forEach(([text, href], i) => {
            const cell = releaseCell(w, i)
            expect(cell.text()).toContain(text)
            const a = cell.find('a')
            expect(a.exists() ? a.attributes('href') : null).toBe(href)
        })
        expect(w.text()).not.toContain('open release')
    })

    it('u9 a withheld source without a release id still says not visible', async () => {
        const w = await mounted([row({ releaseUuid: null, sourceReleaseState: 'NOT_VISIBLE', sourceComponentName: null })])
        expect(releaseCell(w, 0).text()).toContain('not visible to you')
    })

    it('u15 the status column marks an undone upload', async () => {
        const w = await mounted([
            row({ uuid: 'a', proposalStatus: 'REJECTED', rejectKind: 'UPLOAD_UNDONE' }),
            row({ uuid: 'b', proposalStatus: 'REJECTED', rejectKind: 'REVIEWED' }),
            row({ uuid: 'c', proposalStatus: 'PENDING', rejectKind: null }),
            row({ uuid: 'd', proposalStatus: 'ACCEPTED', rejectKind: null })
        ], { props: { orgUuid: 'org-1', releaseUuid: 'rel-1' } })
        const cells = w.findAll('[data-col-key="proposalStatus"]').slice(1).map((c: any) => c.text())
        expect(cells).toEqual(['REJECTED (upload undone)', 'REJECTED', 'PENDING', 'ACCEPTED'])
    })

    it('release mode: a load error is inline, no popup', async () => {
        query.mockRejectedValue(gqlError('Not authorized'))
        const w = mount(Proposals, {
            props: { orgUuid: 'org-1', releaseUuid: 'rel-1' },
            global: { stubs: { NModal: modal, Modal: modal, NTooltip: tooltip, Tooltip: tooltip, SafetyContextSourceModal: true } }
        })
        await flushPromises()
        expect(w.find('[data-testid="safety-proposals-load-error"]').text())
            .toBe('Could not load the proposals of this release: Not authorized')
        expect(Swal.fire).not.toHaveBeenCalled()
    })

    it('u10 hostile values render as text', async () => {
        const hostile = '<img src=x onerror=alert(1)>'
        const long = 'n'.repeat(2000)
        const w = await mounted([
            row({ uuid: 'a', sourceComponentName: hostile, sourceReleaseVersion: '1.0\n<b>2</b>' }),
            row({ uuid: 'b', sourceComponentName: long, sourceArtifact: 'art-1', sourceDocumentHash: 'h1',
                docSerialNumber: '<img src=y>' })
        ], { ctx: context({ baseline: record({ acceptComment: 'line1\n<img src=z onerror=alert(2)>',
            sourceComponentName: hostile }) }) })
        expect(w.find('img').exists()).toBe(false)
        expect(w.find('b').exists()).toBe(false)
        expect(w.text()).toContain(hostile)
        expect(w.text()).toContain(long)
        expect(w.text()).toContain('1.0\n<b>2</b>')
        const m = await openAccept(w)
        expect(m.find('img').exists()).toBe(false)
        expect(m.find('[data-testid="safety-baseline-comment"]').text()).toBe('line1\n<img src=z onerror=alert(2)>')
        expect(m.find('[data-testid="safety-baseline-source"]').text()).toBe(`${hostile} 0.9.0`)
        await modalButton(m, 'Cancel').trigger('click')
        await button(w, 'Undo this upload: reject all its pending levels')!.trigger('click')
        const u = openModal(w, 'Reject safety levels')
        expect(u.find('img').exists()).toBe(false)
        expect(u.find('[data-testid="safety-bulk-reject-body"]').text()).toContain('<img src=y>')
    })
})
