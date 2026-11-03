import gql from 'graphql-tag'
import { ref, type Ref } from 'vue'
import { isSchemaDriftError, markDriftRejected } from './graphqlDriftFallback'
import { lifecycleLabel } from './lifecycle'

/**
 * TPF-120: the auto-integrate dry run of a product branch (feature set) and the trigger that explains
 * what it did. A server without the preview query (CE, older servers) answers the probe as schema drift:
 * the Preview control stays hidden and the trigger sends the legacy document, so that path is unchanged.
 *
 * The selections are written out, never interpolated: scripts/validate-graphql.mjs skips a gql template
 * containing a dollar-brace placeholder, and the first two documents are ahead of the CE mirror schema.
 */
export const AUTO_INTEGRATE_PREVIEW_QUERY = gql`
    query autoIntegrateFeatureSetPreview($branchUuid: ID!) {
        autoIntegrateFeatureSetPreview(branchUuid: $branchUuid) {
            outcome
            existingRelease { uuid version lifecycle createdDate }
            projectedVersion
            note
            dependencies {
                component { uuid name }
                branch { uuid name }
                status source
                release { uuid version lifecycle createdDate }
                note
            }
        }
    }`

export const AUTO_INTEGRATE_EXPLAINED_MUTATION = gql`
    mutation autoIntegrateFeatureSetExplained($branchUuid: ID!) {
        autoIntegrateFeatureSetExplained(branchUuid: $branchUuid) {
            release { uuid version }
            evaluation {
                outcome
                existingRelease { uuid version lifecycle createdDate }
                projectedVersion
                note
                dependencies {
                    component { uuid name }
                    branch { uuid name }
                    status source
                    release { uuid version lifecycle createdDate }
                    note
                }
            }
        }
    }`

/** The trigger's original document, sent on a server without the preview. */
export const AUTO_INTEGRATE_LEGACY_MUTATION = gql`
    mutation autoIntegrateFeatureSet($branchUuid: ID!) {
        autoIntegrateFeatureSet(branchUuid: $branchUuid) { uuid version }
    }`

/** The server's two fixed row sentences, compared whole (never parsed). */
export const NOT_READABLE_NOTE = 'release not shown: you have no read permission on this component'
export const FOREIGN_RELEASE_NOTE = 'release belongs to another organization and cannot be a parent'

export type AutoIntegrateOutcome = 'WOULD_CREATE' | 'AUTO_INTEGRATE_DISABLED' | 'REQUIRED_DEPENDENCY_WITHOUT_RELEASE'
    | 'MATCHING_PRODUCT_RELEASE_EXISTS' | 'CREATE_FAILED'
export interface AutoIntegrateRef { uuid: string, name: string | null }
export interface AutoIntegrateReleaseRef { uuid: string, version: string | null, lifecycle: string | null, createdDate: string | null }
export interface AutoIntegrateDependencyRow {
    component: AutoIntegrateRef, branch: AutoIntegrateRef | null, status: string,
    source: 'PINNED' | 'LATEST_ASSEMBLED' | 'NONE', release: AutoIntegrateReleaseRef | null, note: string
}
export interface AutoIntegrateEvaluation {
    outcome: AutoIntegrateOutcome, existingRelease: AutoIntegrateReleaseRef | null, projectedVersion: string | null,
    dependencies: AutoIntegrateDependencyRow[], note: string | null
}
export type RowKind = 'release' | 'none' | 'ignored' | 'foreign' | 'redacted'
export interface AutoIntegrateWords { branchFirstUpper?: string }
export interface AutoIntegrateTriggerResult {
    release: { uuid: string, version: string } | null
    evaluation: AutoIntegrateEvaluation | null
}

/**
 * The dry run. A schema-drift error (a server without the preview query) answers supported false with no
 * evaluation; every other error (auth, transport, server) is rethrown so the caller shows it. The client is
 * a parameter so a spec passes a stub.
 */
export async function loadAutoIntegratePreview (client: { query: Function }, branchUuid: string)
    : Promise<{ evaluation: AutoIntegrateEvaluation | null, supported: boolean }> {
    try {
        const resp: any = await client.query({
            query: AUTO_INTEGRATE_PREVIEW_QUERY, variables: { branchUuid }, fetchPolicy: 'no-cache'
        })
        return { evaluation: resp?.data?.autoIntegrateFeatureSetPreview ?? null, supported: true }
    } catch (e: any) {
        if (isSchemaDriftError(e)) {
            markDriftRejected(AUTO_INTEGRATE_PREVIEW_QUERY)
            return { evaluation: null, supported: false }
        }
        throw e
    }
}

/** The explained trigger. Every error is rethrown: the caller decides (a drift error means nothing ran). */
export async function runAutoIntegrateExplained (client: { mutate: Function }, branchUuid: string)
    : Promise<{ release: { uuid: string, version: string } | null, evaluation: AutoIntegrateEvaluation }> {
    const resp: any = await client.mutate({
        mutation: AUTO_INTEGRATE_EXPLAINED_MUTATION, variables: { branchUuid }, fetchPolicy: 'no-cache'
    })
    const r = resp?.data?.autoIntegrateFeatureSetExplained
    return { release: r?.release ?? null, evaluation: r?.evaluation }
}

/** The original trigger, unchanged in selection; the result is data.autoIntegrateFeatureSet or null. */
export async function runAutoIntegrateLegacy (client: { mutate: Function }, branchUuid: string)
    : Promise<{ release: { uuid: string, version: string } | null, evaluation: null }> {
    const resp: any = await client.mutate({
        mutation: AUTO_INTEGRATE_LEGACY_MUTATION, variables: { branchUuid }, fetchPolicy: 'no-cache'
    })
    return { release: resp?.data?.autoIntegrateFeatureSet ?? null, evaluation: null }
}

/** The row's kind, decided by the note FIRST: the redaction sentence is the only reliable marker. */
export function rowKind (row: AutoIntegrateDependencyRow): RowKind {
    if (row.note === NOT_READABLE_NOTE) return 'redacted'
    if (row.release) return 'release'
    if (row.note === FOREIGN_RELEASE_NOTE) return 'foreign'
    if (row.status === 'IGNORED') return 'ignored'
    return 'none'
}

export function isRedactedRow (row: AutoIntegrateDependencyRow): boolean {
    return rowKind(row) === 'redacted'
}

export function refLabel (ref: AutoIntegrateRef | null | undefined): string {
    return ref?.name || ref?.uuid || ''
}

export function sourceLabel (source: string): string {
    switch (source) {
    case 'PINNED': return 'pinned'
    case 'LATEST_ASSEMBLED': return 'latest assembled'
    case 'NONE': return 'none'
    default: return source
    }
}

export function releaseText (release: AutoIntegrateReleaseRef): string {
    const parts: string[] = []
    if (release.lifecycle) parts.push(lifecycleLabel(release.lifecycle))
    if (release.createdDate) parts.push((new Date(release.createdDate)).toLocaleString('en-CA', { hour12: false }))
    const name = release.version || release.uuid
    return parts.length ? `${name} (${parts.join(', ')})` : name
}

export function releaseCellText (row: AutoIntegrateDependencyRow): string {
    switch (rowKind(row)) {
    case 'release': return releaseText(row.release as AutoIntegrateReleaseRef)
    case 'redacted': return 'not shown'
    case 'foreign': return 'another organization'
    case 'ignored': return 'ignored'
    default: return 'none'
    }
}

/** REQUIRED rows without a release that the UI can name: a foreign one counts, a redacted one cannot be judged. */
export function blockingRows (ev: { dependencies: AutoIntegrateDependencyRow[] }): AutoIntegrateDependencyRow[] {
    return (ev.dependencies || []).filter((r) => r.status === 'REQUIRED' && !r.release && rowKind(r) !== 'redacted')
}

export function hiddenRequiredRows (ev: { dependencies: AutoIntegrateDependencyRow[] }): AutoIntegrateDependencyRow[] {
    return (ev.dependencies || []).filter((r) => r.status === 'REQUIRED' && rowKind(r) === 'redacted')
}

export function alertTypeFor (outcome: string): 'success' | 'info' | 'warning' | 'error' {
    switch (outcome) {
    case 'WOULD_CREATE': return 'success'
    case 'MATCHING_PRODUCT_RELEASE_EXISTS': return 'info'
    case 'REQUIRED_DEPENDENCY_WITHOUT_RELEASE': return 'warning'
    case 'AUTO_INTEGRATE_DISABLED': return 'warning'
    case 'CREATE_FAILED': return 'error'
    default: return 'info'
    }
}

const dependencyNoun = (n: number): string => n === 1 ? 'dependency' : 'dependencies'

/** One sentence that says what the evaluation decided and why. */
export function outcomeLine (ev: AutoIntegrateEvaluation, words: AutoIntegrateWords): string {
    const branchFirstUpper = words?.branchFirstUpper || 'Feature Set'
    const rows = ev.dependencies || []
    switch (ev.outcome) {
    case 'WOULD_CREATE': {
        const n = rows.filter((r) => r.status !== 'IGNORED').length
        return `A product release would be created now from ${n} ${dependencyNoun(n)}`
            + (ev.projectedVersion ? ` (projected version ${ev.projectedVersion})` : '') + '.'
    }
    case 'AUTO_INTEGRATE_DISABLED':
        return `Auto-integrate is DISABLED on this ${branchFirstUpper}; nothing would be created.`
    case 'REQUIRED_DEPENDENCY_WITHOUT_RELEASE': {
        const blocking = blockingRows(ev)
        if (blocking.length) {
            const named = blocking.map((r) => `${refLabel(r.component)} (${r.branch ? refLabel(r.branch) : 'no branch'}): ${r.note}`)
            return `Not created: REQUIRED ${dependencyNoun(blocking.length)} without a release: ${named.join('; ')}.`
        }
        if (hiddenRequiredRows(ev).length) {
            return 'Not created: a REQUIRED dependency has no release; its row is not shown (no read permission on the component).'
        }
        return 'Not created: a REQUIRED dependency has no release.'
    }
    case 'MATCHING_PRODUCT_RELEASE_EXISTS':
        return 'Not created: a matching product release already exists'
            + (ev.existingRelease ? ': ' + releaseText(ev.existingRelease) : '') + '.'
    case 'CREATE_FAILED':
        return 'Not created: ' + (ev.note || 'the create step was refused (no detail given)')
    default:
        return `Outcome ${ev.outcome}.`
    }
}

/**
 * The outcome sentence as the panel shows it: the text before the release, the release the sentence names (the
 * panel renders it as the link), and the text after. Only MATCHING_PRODUCT_RELEASE_EXISTS names its release in
 * the sentence; every other outcome is the whole of outcomeLine, with no release.
 */
export function outcomeParts (ev: AutoIntegrateEvaluation, words: AutoIntegrateWords):
        { before: string, release: AutoIntegrateReleaseRef | null, after: string } {
    if (ev.outcome === 'MATCHING_PRODUCT_RELEASE_EXISTS' && ev.existingRelease) {
        return { before: 'Not created: a matching product release already exists: ', release: ev.existingRelease, after: '.' }
    }
    return { before: outcomeLine(ev, words), release: null, after: '' }
}

/**
 * Whether the panel names the existing release on a line of its own: when the evaluation carries one the outcome
 * sentence does not name (a CREATE_FAILED keeps the release it found), never for the matching outcome itself.
 */
export function showsExistingReleaseLine (ev: AutoIntegrateEvaluation): boolean {
    return !!ev.existingRelease && ev.outcome !== 'MATCHING_PRODUCT_RELEASE_EXISTS'
}

/** The trigger's info or success notice text, from what the mutation returned. */
export function triggerNotice (result: { release?: { version?: string | null } | null }, ev: AutoIntegrateEvaluation | null,
    words: AutoIntegrateWords): { created: boolean, notice: string } {
    if (result?.release) return { created: true, notice: `Release ${result.release.version} was created via auto-integration.` }
    if (ev) return { created: false, notice: outcomeLine(ev, words) }
    return { created: false, notice: 'Auto-integrate was attempted, but no new release was created.' }
}

/** The per-instance state of the Preview control and panel, and the trigger's document choice. */
export function useAutoIntegratePreview (branchUuid: Ref<string>) {
    const supported: Ref<boolean | null> = ref(null)
    const inFlight = ref(false)
    const evaluation: Ref<AutoIntegrateEvaluation | null> = ref(null)
    const panelVisible = ref(false)

    /** Detection on modal open: once while supported is null; the evaluation is not kept. */
    async function probeOnOpen (client: { query: Function }): Promise<void> {
        if (supported.value !== null || inFlight.value) return
        inFlight.value = true
        try {
            const r = await loadAutoIntegratePreview(client, branchUuid.value)
            supported.value = r.supported
        } finally { inFlight.value = false }
        // a non-drift error propagates: the caller logs it and supported stays null
    }

    /** The Preview click: a fresh query; drift here flips supported to false and hides the control. */
    async function preview (client: { query: Function }): Promise<void> {
        if (inFlight.value) return
        inFlight.value = true
        try {
            const r = await loadAutoIntegratePreview(client, branchUuid.value)
            if (!r.supported) { supported.value = false; panelVisible.value = false; return }
            supported.value = true
            evaluation.value = r.evaluation
            panelVisible.value = !!r.evaluation
        } finally { inFlight.value = false }
    }

    /**
     * The trigger's call: the explained document on a supported server, else the legacy one. A drift
     * rejection of the explained document executed nothing, so the legacy document is sent once instead
     * and supported becomes false. Every other error is rethrown to the caller's error path.
     */
    async function runTrigger (client: { mutate: Function }): Promise<AutoIntegrateTriggerResult> {
        if (supported.value !== true) return runAutoIntegrateLegacy(client, branchUuid.value)
        try {
            return await runAutoIntegrateExplained(client, branchUuid.value)
        } catch (e: any) {
            if (!isSchemaDriftError(e)) throw e
            supported.value = false
            markDriftRejected(AUTO_INTEGRATE_EXPLAINED_MUTATION)
            return runAutoIntegrateLegacy(client, branchUuid.value)
        }
    }

    /** After a trigger that created nothing: show what the server said. */
    function showEvaluation (ev: AutoIntegrateEvaluation | null): void {
        evaluation.value = ev
        panelVisible.value = !!ev
    }
    function closePanel (): void { panelVisible.value = false }
    function reset (): void { evaluation.value = null; panelVisible.value = false }

    return { supported, inFlight, evaluation, panelVisible, probeOnOpen, preview, runTrigger, showEvaluation, closePanel, reset }
}
