import { describe, expect, it } from 'vitest'
import { ref } from 'vue'
import { print } from 'graphql'
import {
    AUTO_INTEGRATE_EXPLAINED_MUTATION, AUTO_INTEGRATE_LEGACY_MUTATION, AUTO_INTEGRATE_PREVIEW_QUERY,
    FOREIGN_RELEASE_NOTE, NOT_READABLE_NOTE,
    alertTypeFor, blockingRows, hiddenRequiredRows, isRedactedRow, loadAutoIntegratePreview, outcomeLine, refLabel,
    releaseCellText, releaseText, rowKind, runAutoIntegrateExplained, runAutoIntegrateLegacy, sourceLabel, triggerNotice,
    useAutoIntegratePreview,
    type AutoIntegrateDependencyRow, type AutoIntegrateEvaluation
} from './autoIntegratePreview'
import { isDriftRejected } from './graphqlDriftFallback'
import { lifecycleLabel } from './lifecycle'

// TPF-120 (architecture round 2, test rows 15, 16, 17 and 22): the auto-integrate preview loader, the two
// trigger runners, the pure helpers and the composable.

// The validationError() fixture of apiKeyGroupsQuery.spec.ts 7-14.
function validationError (field = 'autoIntegrateFeatureSetPreview'): any {
    const err: any = new Error(`Validation error of type FieldUndefined: Field '${field}' is undefined`)
    err.errors = [{
        message: `Validation error of type FieldUndefined: Field '${field}' is undefined`,
        extensions: { classification: 'ValidationError' },
    }]
    return err
}
function notAuthorized (): any {
    const e: any = new Error('Not authorized')
    e.errors = [{ message: 'Not authorized', extensions: { classification: 'DataFetchingException' } }]
    return e
}
const rejecting = (e: any) => ({ query: async () => { throw e }, mutate: async () => { throw e } })
const opName = (doc: any): string => (doc.definitions[0] as any).name.value

const relA = { uuid: 'r1', version: '1.0.0', lifecycle: 'ASSEMBLED', createdDate: '2026-10-29T10:00:00Z' }
const relADate = (new Date(relA.createdDate)).toLocaleString('en-CA', { hour12: false })
const c1: AutoIntegrateDependencyRow = { component: { uuid: 'c1', name: 'api' }, branch: { uuid: 'b1', name: 'main' }, status: 'REQUIRED',
    source: 'LATEST_ASSEMBLED', release: relA, note: 'latest ASSEMBLED release of branch main' }
const c3none: AutoIntegrateDependencyRow = { component: { uuid: 'c3', name: 'db' }, branch: { uuid: 'b3', name: 'main' }, status: 'REQUIRED',
    source: 'NONE', release: null, note: 'no release on branch main' }
const c4draft: AutoIntegrateDependencyRow = { ...c3none, component: { uuid: 'c4', name: 'db' }, note: 'no ASSEMBLED release on branch main; latest is DRAFT 0.1.0' }
const cForeign: AutoIntegrateDependencyRow = { component: { uuid: 'c5', name: 'ext' }, branch: { uuid: 'b5', name: 'main' }, status: 'TRANSIENT',
    source: 'LATEST_ASSEMBLED', release: null, note: FOREIGN_RELEASE_NOTE }
const cForeignReq: AutoIntegrateDependencyRow = { ...cForeign, status: 'REQUIRED' }
const cRedacted: AutoIntegrateDependencyRow = { component: { uuid: 'c6', name: 'secret' }, branch: { uuid: 'b6', name: 'main' }, status: 'REQUIRED',
    source: 'PINNED', release: null, note: NOT_READABLE_NOTE }
const cIgnored: AutoIntegrateDependencyRow = { component: { uuid: 'c7', name: 'old' }, branch: { uuid: 'b7', name: 'main' }, status: 'IGNORED',
    source: 'NONE', release: null, note: 'ignored' }
const cNoBranch: AutoIntegrateDependencyRow = { component: { uuid: 'c8', name: 'loose' }, branch: null, status: 'TRANSIENT',
    source: 'NONE', release: null, note: 'no branch set on this dependency' }

const ev = (o: Partial<AutoIntegrateEvaluation>): AutoIntegrateEvaluation => ({
    outcome: 'WOULD_CREATE', existingRelease: null, projectedVersion: null, dependencies: [], note: null, ...o
})
const words = { branchFirstUpper: 'Feature Set' }
const evMatching = ev({ outcome: 'MATCHING_PRODUCT_RELEASE_EXISTS', existingRelease: relA, dependencies: [c1] })

describe('loadAutoIntegratePreview and the trigger runners (row 15)', () => {
    it('(a) returns the evaluation and supported true, sending the preview query uncached', async () => {
        const evaluation = ev({ dependencies: [c1] })
        const calls: any[] = []
        const client = { query: async (opts: any) => { calls.push(opts); return { data: { autoIntegrateFeatureSetPreview: evaluation } } } }
        expect(await loadAutoIntegratePreview(client, 'b1')).toEqual({ evaluation, supported: true })
        expect(calls).toHaveLength(1)
        expect(calls[0].query).toBe(AUTO_INTEGRATE_PREVIEW_QUERY)
        expect(calls[0].variables).toEqual({ branchUuid: 'b1' })
        expect(calls[0].fetchPolicy).toBe('no-cache')
    })

    it('(b) answers supported false with no evaluation on drift and marks the document rejected', async () => {
        expect(await loadAutoIntegratePreview(rejecting(validationError()), 'b1')).toEqual({ evaluation: null, supported: false })
        expect(isDriftRejected(AUTO_INTEGRATE_PREVIEW_QUERY)).toBe(true)
    })

    it('(c) rethrows a network error and a Not authorized error as the same object', async () => {
        const net = new Error('Failed to fetch')
        await expect(loadAutoIntegratePreview(rejecting(net), 'b1')).rejects.toBe(net)
        const auth = notAuthorized()
        await expect(loadAutoIntegratePreview(rejecting(auth), 'b1')).rejects.toBe(auth)
    })

    it('(d) runAutoIntegrateExplained sends the explained mutation and rethrows every error, drift included', async () => {
        const evaluation = ev({ outcome: 'CREATE_FAILED', note: 'x' })
        const calls: any[] = []
        const client = { mutate: async (opts: any) => {
            calls.push(opts)
            return { data: { autoIntegrateFeatureSetExplained: { release: { uuid: 'p', version: '2.0.0' }, evaluation } } }
        } }
        expect(await runAutoIntegrateExplained(client, 'b1')).toEqual({ release: { uuid: 'p', version: '2.0.0' }, evaluation })
        expect(calls).toHaveLength(1)
        expect(calls[0].mutation).toBe(AUTO_INTEGRATE_EXPLAINED_MUTATION)
        expect(calls[0].variables).toEqual({ branchUuid: 'b1' })
        expect(calls[0].fetchPolicy).toBe('no-cache')
        for (const e of [validationError('autoIntegrateFeatureSetExplained'), new Error('Failed to fetch'), notAuthorized()]) {
            await expect(runAutoIntegrateExplained(rejecting(e), 'b1')).rejects.toBe(e)
        }
    })

    it('(e) runAutoIntegrateLegacy sends the legacy mutation and returns no evaluation', async () => {
        const calls: any[] = []
        const created = { uuid: 'p', version: '2.0.0' }
        const client = { mutate: async (opts: any) => { calls.push(opts); return { data: { autoIntegrateFeatureSet: created } } } }
        expect(await runAutoIntegrateLegacy(client, 'b1')).toEqual({ release: created, evaluation: null })
        expect(calls[0].mutation).toBe(AUTO_INTEGRATE_LEGACY_MUTATION)
        expect(calls[0].variables).toEqual({ branchUuid: 'b1' })
        expect(calls[0].fetchPolicy).toBe('no-cache')
        const nullClient = { mutate: async () => ({ data: { autoIntegrateFeatureSet: null } }) }
        expect(await runAutoIntegrateLegacy(nullClient, 'b1')).toEqual({ release: null, evaluation: null })
    })

    it('(f) names the three operations', () => {
        expect(opName(AUTO_INTEGRATE_PREVIEW_QUERY)).toBe('autoIntegrateFeatureSetPreview')
        expect(opName(AUTO_INTEGRATE_EXPLAINED_MUTATION)).toBe('autoIntegrateFeatureSetExplained')
        expect(opName(AUTO_INTEGRATE_LEGACY_MUTATION)).toBe('autoIntegrateFeatureSet')
    })
})

describe('pure helpers (row 16)', () => {
    it('(a) the two sentences equal the backend constants', () => {
        // Sources: AutoIntegrateDependencyRow.NOT_READABLE_NOTE and ReleaseService.FOREIGN_RELEASE_NOTE on the server.
        expect(NOT_READABLE_NOTE).toBe('release not shown: you have no read permission on this component')
        expect(FOREIGN_RELEASE_NOTE).toBe('release belongs to another organization and cannot be a parent')
    })

    it('(b) rowKind decides by the note first', () => {
        expect([c1, c3none, c4draft, cForeign, cRedacted, cIgnored, cNoBranch].map(rowKind))
            .toEqual(['release', 'none', 'none', 'foreign', 'redacted', 'ignored', 'none'])
        expect(rowKind({ ...c1, note: NOT_READABLE_NOTE })).toBe('redacted')
        expect(isRedactedRow(cRedacted)).toBe(true)
        expect(isRedactedRow(c3none)).toBe(false)
    })

    it('(c) releaseCellText and releaseText', () => {
        expect(lifecycleLabel('ASSEMBLED')).toBe('Assembled')
        expect(releaseCellText(c1)).toBe(`1.0.0 (${lifecycleLabel('ASSEMBLED')}, ${relADate})`)
        expect(releaseCellText(cRedacted)).toBe('not shown')
        expect(releaseCellText(cForeign)).toBe('another organization')
        expect(releaseCellText(cIgnored)).toBe('ignored')
        expect(releaseCellText(c3none)).toBe('none')
        expect(releaseText({ uuid: 'r2', version: '2.0.0', lifecycle: null, createdDate: null })).toBe('2.0.0')
        expect(releaseText({ uuid: 'r3', version: null, lifecycle: 'DRAFT', createdDate: null }).startsWith('r3')).toBe(true)
    })

    it('(d) sourceLabel', () => {
        expect(['PINNED', 'LATEST_ASSEMBLED', 'NONE', 'X'].map(sourceLabel)).toEqual(['pinned', 'latest assembled', 'none', 'X'])
    })

    it('(e) refLabel', () => {
        expect(refLabel({ uuid: 'u', name: null })).toBe('u')
        expect(refLabel(null)).toBe('')
        expect(refLabel({ uuid: 'u', name: 'n' })).toBe('n')
    })

    it('(f) blockingRows and hiddenRequiredRows', () => {
        const e = { dependencies: [c1, c3none, cForeignReq, cRedacted, cForeign] }
        expect(blockingRows(e)).toEqual([c3none, cForeignReq])
        expect(hiddenRequiredRows(e)).toEqual([cRedacted])
    })

    it('(g) alertTypeFor', () => {
        expect(['WOULD_CREATE', 'MATCHING_PRODUCT_RELEASE_EXISTS', 'REQUIRED_DEPENDENCY_WITHOUT_RELEASE', 'AUTO_INTEGRATE_DISABLED', 'CREATE_FAILED', 'X']
            .map(alertTypeFor)).toEqual(['success', 'info', 'warning', 'warning', 'error', 'info'])
    })

    it('(h) outcomeLine for every outcome', () => {
        expect(outcomeLine(ev({ projectedVersion: '1.4.0', dependencies: [c1, c1, c1] }), words))
            .toBe('A product release would be created now from 3 dependencies (projected version 1.4.0).')
        expect(outcomeLine(ev({ dependencies: [c1] }), words)).toBe('A product release would be created now from 1 dependency.')
        expect(outcomeLine(ev({ dependencies: [c1, cIgnored] }), words)).toBe('A product release would be created now from 1 dependency.')
        expect(outcomeLine(ev({ outcome: 'AUTO_INTEGRATE_DISABLED' }), words))
            .toBe('Auto-integrate is DISABLED on this Feature Set; nothing would be created.')
        const req = (deps: AutoIntegrateDependencyRow[]) => ev({ outcome: 'REQUIRED_DEPENDENCY_WITHOUT_RELEASE', dependencies: deps })
        expect(outcomeLine(req([c1, c3none, c4draft]), words)).toBe('Not created: REQUIRED dependencies without a release: '
            + 'db (main): no release on branch main; db (main): no ASSEMBLED release on branch main; latest is DRAFT 0.1.0.')
        expect(outcomeLine(req([c1, c3none]), words))
            .toBe('Not created: REQUIRED dependency without a release: db (main): no release on branch main.')
        expect(outcomeLine(req([c1, cRedacted]), words))
            .toBe('Not created: a REQUIRED dependency has no release; its row is not shown (no read permission on the component).')
        expect(outcomeLine(req([c1, cForeignReq]), words))
            .toBe('Not created: REQUIRED dependency without a release: ext (main): release belongs to another organization and cannot be a parent.')
        expect(outcomeLine(req([c1]), words)).toBe('Not created: a REQUIRED dependency has no release.')
        expect(outcomeLine(req([{ ...c3none, branch: null }]), words))
            .toBe('Not created: REQUIRED dependency without a release: db (no branch): no release on branch main.')
        expect(outcomeLine(evMatching, words))
            .toBe(`Not created: a matching product release already exists: 1.0.0 (Assembled, ${relADate}).`)
        expect(outcomeLine(ev({ outcome: 'MATCHING_PRODUCT_RELEASE_EXISTS' }), words))
            .toBe('Not created: a matching product release already exists.')
        const lock = 'Component P is locked, so ReARM will not assign a version: frozen. Or ask an administrator to release lock L'
        expect(outcomeLine(ev({ outcome: 'CREATE_FAILED', note: lock }), words)).toBe('Not created: ' + lock)
        expect(outcomeLine(ev({ outcome: 'CREATE_FAILED', note: FOREIGN_RELEASE_NOTE }), words))
            .toBe('Not created: release belongs to another organization and cannot be a parent')
        expect(outcomeLine(ev({ outcome: 'CREATE_FAILED', note: null }), words))
            .toBe('Not created: the create step was refused (no detail given)')
        expect(outcomeLine(ev({ outcome: 'NEW_THING' as any }), words)).toBe('Outcome NEW_THING.')
        expect(outcomeLine(ev({ outcome: 'AUTO_INTEGRATE_DISABLED' }), {}))
            .toBe('Auto-integrate is DISABLED on this Feature Set; nothing would be created.')
    })

    it('(i) triggerNotice', () => {
        expect(triggerNotice({ release: { uuid: 'p', version: '2.0.0' } } as any, evMatching, words))
            .toEqual({ created: true, notice: 'Release 2.0.0 was created via auto-integration.' })
        expect(triggerNotice({ release: null }, evMatching, words)).toEqual({ created: false, notice: outcomeLine(evMatching, words) })
        expect(triggerNotice({ release: null }, null, words))
            .toEqual({ created: false, notice: 'Auto-integrate was attempted, but no new release was created.' })
    })
})

describe('useAutoIntegratePreview (row 17)', () => {
    const evaluation = ev({ dependencies: [c1] })
    function okClient () {
        const calls: any[] = []
        return { calls, query: async (opts: any) => { calls.push(opts); return { data: { autoIntegrateFeatureSetPreview: evaluation } } } }
    }
    const fresh = () => useAutoIntegratePreview(ref('b1'))

    it('(a) probes once on open and keeps no evaluation', async () => {
        const p = fresh()
        const c = okClient()
        await p.probeOnOpen(c)
        expect(p.supported.value).toBe(true)
        expect(p.evaluation.value).toBeNull()
        expect(p.panelVisible.value).toBe(false)
        expect(c.calls).toHaveLength(1)
        await p.probeOnOpen(c)
        expect(c.calls).toHaveLength(1)
    })

    it('(b) a drift probe sets supported false without throwing, and is not repeated', async () => {
        const p = fresh()
        await p.probeOnOpen(rejecting(validationError()))
        expect(p.supported.value).toBe(false)
        expect(p.panelVisible.value).toBe(false)
        const c = okClient()
        await p.probeOnOpen(c)
        expect(c.calls).toHaveLength(0)
    })

    it('(c) a non-drift probe error rejects, leaves supported null, and the next open probes again', async () => {
        const p = fresh()
        const e = new Error('Failed to fetch')
        await expect(p.probeOnOpen(rejecting(e))).rejects.toBe(e)
        expect(p.supported.value).toBeNull()
        const c = okClient()
        await p.probeOnOpen(c)
        expect(c.calls).toHaveLength(1)
        expect(p.supported.value).toBe(true)
    })

    it('(d) the Preview click queries afresh; drift hides; other errors reject and keep the evaluation', async () => {
        const p = fresh()
        await p.preview(okClient())
        expect(p.evaluation.value).toEqual(evaluation)
        expect(p.panelVisible.value).toBe(true)
        expect(p.supported.value).toBe(true)
        const e = new Error('Not authorized')
        await expect(p.preview(rejecting(e))).rejects.toBe(e)
        expect(p.evaluation.value).toEqual(evaluation)
        await p.preview(rejecting(validationError()))
        expect(p.supported.value).toBe(false)
        expect(p.panelVisible.value).toBe(false)
    })

    it('(e) a second Preview while one is in flight sends nothing', async () => {
        const p = fresh()
        let release: () => void = () => {}
        const calls: any[] = []
        const client = { query: (opts: any) => {
            calls.push(opts)
            return new Promise((resolve) => { release = () => resolve({ data: { autoIntegrateFeatureSetPreview: evaluation } }) })
        } }
        const first = p.preview(client)
        expect(p.inFlight.value).toBe(true)
        await p.preview(client)
        release()
        await first
        expect(calls).toHaveLength(1)
        expect(p.inFlight.value).toBe(false)
    })

    function mutateClient (answers: Record<string, any>) {
        const calls: any[] = []
        return { calls, mutate: async (opts: any) => {
            calls.push(opts)
            const a = answers[opName(opts.mutation)]
            if (a instanceof Error) throw a
            return { data: a }
        } }
    }

    it('(f) the legacy document while unsupported or never probed; the explained one when supported', async () => {
        const created = { uuid: 'p', version: '2.0.0' }
        for (const s of [null, false]) {
            const p = fresh()
            p.supported.value = s
            const c = mutateClient({ autoIntegrateFeatureSet: { autoIntegrateFeatureSet: created } })
            expect(await p.runTrigger(c)).toEqual({ release: created, evaluation: null })
            expect(c.calls.map((x) => opName(x.mutation))).toEqual(['autoIntegrateFeatureSet'])
            expect(c.calls[0].mutation).toBe(AUTO_INTEGRATE_LEGACY_MUTATION)
        }
        const p = fresh()
        p.supported.value = true
        const evNone = ev({ outcome: 'MATCHING_PRODUCT_RELEASE_EXISTS', existingRelease: relA })
        const createdC = mutateClient({ autoIntegrateFeatureSetExplained: { autoIntegrateFeatureSetExplained: { release: created, evaluation } } })
        expect(await p.runTrigger(createdC)).toEqual({ release: created, evaluation })
        expect(createdC.calls[0].mutation).toBe(AUTO_INTEGRATE_EXPLAINED_MUTATION)
        const nullC = mutateClient({ autoIntegrateFeatureSetExplained: { autoIntegrateFeatureSetExplained: { release: null, evaluation: evNone } } })
        expect(await p.runTrigger(nullC)).toEqual({ release: null, evaluation: evNone })
    })

    it('(g) a drift rejection of the explained document falls back to the legacy one once', async () => {
        const p = fresh()
        p.supported.value = true
        const created = { uuid: 'p', version: '2.0.0' }
        const c = mutateClient({
            autoIntegrateFeatureSetExplained: validationError('autoIntegrateFeatureSetExplained'),
            autoIntegrateFeatureSet: { autoIntegrateFeatureSet: created }
        })
        expect(await p.runTrigger(c)).toEqual({ release: created, evaluation: null })
        expect(c.calls.map((x) => opName(x.mutation))).toEqual(['autoIntegrateFeatureSetExplained', 'autoIntegrateFeatureSet'])
        expect(p.supported.value).toBe(false)
        expect(isDriftRejected(AUTO_INTEGRATE_EXPLAINED_MUTATION)).toBe(true)
        await p.runTrigger(c)
        expect(c.calls.map((x) => opName(x.mutation))).toEqual(['autoIntegrateFeatureSetExplained', 'autoIntegrateFeatureSet', 'autoIntegrateFeatureSet'])
    })

    it('(h) a non-drift error of the explained document is rethrown after one call', async () => {
        const p = fresh()
        p.supported.value = true
        const e = new Error('Component P is locked, so ReARM will not assign a version')
        const c = mutateClient({ autoIntegrateFeatureSetExplained: e })
        await expect(p.runTrigger(c)).rejects.toBe(e)
        expect(c.calls).toHaveLength(1)
        expect(p.supported.value).toBe(true)
    })

    it('(i) showEvaluation, closePanel and reset', () => {
        const p = fresh()
        p.supported.value = true
        p.showEvaluation(evaluation)
        expect(p.evaluation.value).toEqual(evaluation)
        expect(p.panelVisible.value).toBe(true)
        p.closePanel()
        expect(p.panelVisible.value).toBe(false)
        p.showEvaluation(evaluation)
        p.showEvaluation(null)
        expect(p.panelVisible.value).toBe(false)
        p.showEvaluation(evaluation)
        p.reset()
        expect(p.evaluation.value).toBeNull()
        expect(p.panelVisible.value).toBe(false)
        expect(p.supported.value).toBe(true)
    })
})

describe('legacy path parity (row 22)', () => {
    it('the legacy document selects exactly uuid and version', () => {
        const printed = print(AUTO_INTEGRATE_LEGACY_MUTATION)
        const inner = printed.match(/autoIntegrateFeatureSet\(branchUuid: \$branchUuid\) \{([^}]*)\}/)
        expect(inner).not.toBeNull()
        expect((inner as RegExpMatchArray)[1].trim().split(/\s+/)).toEqual(['uuid', 'version'])
    })
})
