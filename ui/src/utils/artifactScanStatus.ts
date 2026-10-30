import gql from 'graphql-tag'
import { ref } from 'vue'
import { isSchemaDriftError, markDriftRejected } from './graphqlDriftFallback'

/**
 * TPF-122: why a BOM artifact is or is not scanned. One read-only document, sent when a user
 * opens the scan status panel of an artifact and on each Refresh click; never polled.
 *
 * The selection is written out, never interpolated: scripts/validate-graphql.mjs skips a gql
 * template that interpolates, and this document is ahead of the CE mirror (the loader probes it).
 */
export const ARTIFACT_SCAN_STATUS_QUERY = gql`
    query artifactScanStatus($artifactUuid: ID!) {
        artifactScanStatus(artifactUuid: $artifactUuid) {
            artifactUuid type state mapped canonicalArtifactUuid
            mappedAt firstScanned lastScanned fanOutAt fanOutRequeuedAt
            newestIngestedBucketUpdate ingestedBucketCount
            enrichmentGated inCandidateSet candidateRule
            matchableComponentCount rootComponentCount terminalComponentCount unmatchableComponentCount
            noMatchableComponentsReason
            notScannableAt
            blockerTotal blockersTruncated
            blockers { purl enrichment terminalReason bucketIndex bucketIngestState bucketLastUpdatedDate reason }
        }
    }`

export type ArtifactScanState = 'NOT_A_BOM' | 'UNMAPPED' | 'BLOCKED_ON_COVERAGE' | 'WAITING_FOR_FAN_OUT' | 'SCANNED' | 'NO_MATCHABLE_COMPONENTS'
export type ScanBlockerEnrichmentState = 'UNENRICHED' | 'ENRICHED' | 'TERMINAL'
export type NoMatchableComponentsReason = 'NO_COMPONENTS' | 'ONLY_ROOT' | 'ALL_TERMINAL' | 'NO_PURL_OR_CPE'

export interface ScanBlocker {
    purl: string
    enrichment: ScanBlockerEnrichmentState | string
    terminalReason: string | null
    bucketIndex: number | null
    bucketIngestState: string | null
    bucketLastUpdatedDate: string | null
    reason: string
}

export interface ArtifactScanStatus {
    artifactUuid: string
    type: string
    state: ArtifactScanState | string
    mapped: boolean
    canonicalArtifactUuid: string | null
    mappedAt: string | null
    firstScanned: string | null
    lastScanned: string | null
    fanOutAt: string | null
    fanOutRequeuedAt: string | null
    newestIngestedBucketUpdate: string | null
    ingestedBucketCount: number
    enrichmentGated: boolean | null
    inCandidateSet: boolean
    candidateRule: string
    matchableComponentCount: number
    rootComponentCount: number
    terminalComponentCount: number
    unmatchableComponentCount: number
    noMatchableComponentsReason: NoMatchableComponentsReason | string | null
    /** When the fan-out marked this artifact not scannable (Artifact.metrics.notScannable.at); null when unmarked. */
    notScannableAt?: string | null
    blockerTotal: number
    blockersTruncated: boolean
    blockers: ScanBlocker[]
}

/** Artifact.metrics.notScannable as served: the fan-out's marker on a BOM with no matchable component. */
export interface ArtifactNotScannable {
    reason: NoMatchableComponentsReason | string
    at: string | null
    components: number
    root: number
    terminal: number
    unmatchable: number
}

export interface ScanStatusLoadResult { status: ArtifactScanStatus | null, supported: boolean }

/**
 * Whether the server answered the scan-status document this page load: null until the first answer, true
 * once served, false once refused as schema drift (CE, or a Pro server before the query). Module-level and
 * reactive: every artifact table on the page reads it in its Actions column and drops the icon when it is
 * false. A non-drift error leaves it as it was.
 */
export const scanStatusSupported = ref<boolean | null>(null)
export function resetScanStatusSupportForTests (): void { scanStatusSupported.value = null }

/**
 * Loads the status. A schema-drift error (a server without the query) answers supported false with no
 * status and sets the latch; every other error (auth, transport, server) is rethrown so the caller shows it.
 * The client is a parameter so a spec passes a stub.
 */
export async function loadArtifactScanStatus (client: { query: Function }, artifactUuid: string): Promise<ScanStatusLoadResult> {
    try {
        const resp: any = await client.query({
            query: ARTIFACT_SCAN_STATUS_QUERY,
            variables: { artifactUuid },
            fetchPolicy: 'no-cache'
        })
        scanStatusSupported.value = true
        return { status: resp?.data?.artifactScanStatus ?? null, supported: true }
    } catch (e: any) {
        if (isSchemaDriftError(e)) {
            markDriftRejected(ARTIFACT_SCAN_STATUS_QUERY)
            scanStatusSupported.value = false
            return { status: null, supported: false }
        }
        throw e
    }
}

function parseableIso (iso: string | null | undefined): iso is string {
    return typeof iso === 'string' && iso !== '' && !isNaN(Date.parse(iso))
}

/** A server instant in the page's locale (the renderDtrackPill idiom); '' for none or garbage. */
export function formatInstant (iso: string | null | undefined): string {
    if (!parseableIso(iso)) return ''
    return new Date(iso).toLocaleString('en-CA', { hour12: false })
}

/** The age of an instant: just now, minutes, hours, then days with no upper bound. */
export function ageText (iso: string | null | undefined, nowMs: number = Date.now()): string {
    if (!parseableIso(iso)) return ''
    const seconds = Math.floor((nowMs - Date.parse(iso)) / 1000)
    if (seconds < 60) return 'just now'
    const minutes = Math.floor(seconds / 60)
    if (minutes < 60) return `${minutes}m ago`
    const hours = Math.floor(minutes / 60)
    if (hours < 24) return `${hours}h ago`
    return `${Math.floor(hours / 24)}d ago`
}

/** A date cell: '-' for none, else the instant and its age. */
export function dateCell (iso: string | null | undefined, nowMs: number = Date.now()): string {
    if (iso == null) return '-'
    return `${formatInstant(iso)} (${ageText(iso, nowMs)})`
}

const REASON_LABELS: Record<NoMatchableComponentsReason, string> = {
    NO_COMPONENTS: 'no components',
    ONLY_ROOT: "only the BOM's own root component",
    ALL_TERMINAL: 'every dependency is enrichment-terminal',
    NO_PURL_OR_CPE: 'components carry no purl or cpe'
}

export function reasonLabel (reason: NoMatchableComponentsReason | string | null | undefined): string {
    if (reason == null) return ''
    return REASON_LABELS[reason as NoMatchableComponentsReason] ?? reason
}

/**
 * The title of the "No scannable components" pill and the facts line (TPF-127): the reason in the
 * explainer's words, the gate's counts, what to do, and when the fan-out decided.
 */
export function notScannableTitle (marker: ArtifactNotScannable): string {
    const counts = `${marker.components} component rows: ${marker.root} root, ${marker.terminal} enrichment-terminal, ${marker.unmatchable} without purl or cpe`
    let text = `Nothing to scan: ${reasonLabel(marker.reason) || String(marker.reason)} (${counts})`
    if (marker.reason === 'ONLY_ROOT' || marker.reason === 'NO_PURL_OR_CPE') {
        text += '; components without a purl or cpe are not inventoried, so re-generate the SBOM with package coordinates'
    }
    if (marker.reason === 'NO_COMPONENTS') text += '; an empty SBOM, or one whose parse has not landed yet'
    const when = formatInstant(marker.at)
    if (when) text += `; evaluated ${when}`
    return text
}

/**
 * The title of a release-level "No scannable components" badge or note (TPF-127): the one marker's
 * title when there is exactly one, else the distinct reasons in order, with the count of marked BOMs.
 */
export function notScannableReleaseTitle (markers: ArtifactNotScannable[], notScannableBoms: number, reasons: string[]): string {
    if (markers.length === 1) return notScannableTitle(markers[0])
    const labels = reasons.map((r) => reasonLabel(r) || r).join('; ')
    const head = notScannableBoms === 1 ? 'Nothing to scan' : `Nothing to scan in ${notScannableBoms} BOMs`
    return labels ? `${head}: ${labels}` : 'Nothing to scan'
}

/** The grey note beside a list row's circles (TPF-127): how many of the BOMs the row holds carry the marker. */
export function notScannableCountLabel (count: number): string {
    return `${count} BOM${count === 1 ? '' : 's'} not scannable`
}

export function candidateFlagText (inCandidateSet: boolean): string {
    return inCandidateSet ? 'qualifies for a fan-out pass' : 'not a candidate'
}

export type StateTone = 'default' | 'info' | 'success' | 'warning' | 'error'
export interface StateLine { label: string, tone: StateTone, text: string }

/** The state in words: one label, one tone, one sentence. An unknown state renders as its raw word. */
export function stateLine (status: ArtifactScanStatus): StateLine {
    const d = formatInstant
    switch (status.state) {
    case 'NOT_A_BOM':
        return { label: 'Not an SBOM', tone: 'default', text: `no synthetic scan runs on this artifact type (${status.type})` }
    case 'UNMAPPED':
        return {
            label: 'Not mapped yet',
            tone: 'warning',
            text: 'no canonical map row yet: the post-upload reconcile has not mapped this artifact; a user with write access to this release can map it now with Reconcile under SBOM Components on this page (BOM Components tab); otherwise the scheduled sweep maps a BOM that every reconcile missed once it is 90 minutes old'
        }
    case 'BLOCKED_ON_COVERAGE':
        return {
            label: 'Blocked on coverage',
            tone: 'warning',
            text: `${status.blockerTotal} of ${status.matchableComponentCount} matchable components are not covered by an ingested Dependency-Track bucket`
        }
    case 'WAITING_FOR_FAN_OUT':
        return { label: 'Waiting for fan-out', tone: 'info', text: `coverage complete; ${candidateFlagText(status.inCandidateSet)}` }
    case 'SCANNED': {
        let text = `first scanned ${d(status.firstScanned) || 'unknown'}, last scanned ${d(status.lastScanned) || 'unknown'}`
        if (status.blockerTotal > 0) text += `; ${status.blockerTotal} components block the next refresh`
        if (status.noMatchableComponentsReason != null) {
            text += `; scan done, but no component was scannable (${reasonLabel(status.noMatchableComponentsReason)})`
        }
        return { label: 'Scanned', tone: 'success', text }
    }
    case 'NO_MATCHABLE_COMPONENTS': {
        let text = `${reasonLabel(status.noMatchableComponentsReason) || 'no matchable component'}: nothing to scan (${status.rootComponentCount} root, ${status.terminalComponentCount} terminal, ${status.unmatchableComponentCount} without purl or cpe)`
        if (typeof status.notScannableAt === 'string' && status.notScannableAt !== '') {
            text += `; marked not scannable at ${d(status.notScannableAt)}`
        }
        if (status.matchableComponentCount > 0) {
            text += `; a matchable component appeared since; ${status.inCandidateSet ? 'qualifies for a clearing pass' : 'not a candidate for a clearing pass'}`
        }
        return { label: 'No scannable components', tone: 'warning', text }
    }
    default:
        return { label: String(status.state), tone: 'default', text: `state ${status.state} is not known to this UI` }
    }
}

/** The fan-out candidate flag and the server's rule sentence, shown verbatim. */
export function candidateLine (status: ArtifactScanStatus): { flag: string, rule: string } {
    return { flag: candidateFlagText(status.inCandidateSet), rule: status.candidateRule || '' }
}

/**
 * The Enrichment fact. The server asks rebom only when a blocking component exists, so its nullable
 * flag has four meanings once read with the blocker count.
 */
export type EnrichmentFact = 'GATED' | 'NOT_GATED' | 'NOT_EVALUATED' | 'UNANSWERED'

export const ENRICHMENT_FACT_TEXT: Record<EnrichmentFact, string> = {
    GATED: 'submission waits for BEAR enrichment',
    NOT_GATED: 'enrichment not required in this organization',
    NOT_EVALUATED: 'not determined (asked only when a component blocks)',
    UNANSWERED: 'unknown (rebom did not answer)'
}

/** The only place the null-and-no-blocker rule lives. */
export function enrichmentFactOf (gated: boolean | null | undefined, blockerTotal: number): EnrichmentFact {
    if (gated === true) return 'GATED'
    if (gated === false) return 'NOT_GATED'
    return blockerTotal > 0 ? 'UNANSWERED' : 'NOT_EVALUATED'
}

export function enrichmentGatedText (gated: boolean | null | undefined, blockerTotal: number): string {
    return ENRICHMENT_FACT_TEXT[enrichmentFactOf(gated, blockerTotal)]
}

/** The note above the blockers table; rendered only when rows exist, where null means rebom did not answer. */
export function enrichmentNote (gated: boolean | null | undefined): string {
    if (gated === true) return 'This organization submits only enriched components; an un-enriched component without a bucket waits for enrichment first.'
    if (gated === false) return 'Enrichment is not required in this organization; the bucket columns carry the cause.'
    return 'Whether this organization requires enrichment could not be determined (rebom did not answer); the reason column is authoritative.'
}

export function enrichmentCell (row: ScanBlocker, gated: boolean | null | undefined): string {
    switch (row.enrichment) {
    case 'ENRICHED':
        return 'enriched'
    case 'TERMINAL':
        return row.terminalReason ? `terminal (${row.terminalReason})` : 'terminal'
    case 'UNENRICHED':
        if (gated === true) return row.bucketIndex == null ? 'not enriched (waiting for enrichment)' : 'not enriched'
        if (gated === false) return 'not enriched (not required in this organization)'
        return row.bucketIndex == null ? 'enrichment state unknown' : 'not enriched'
    default:
        return String(row.enrichment)
    }
}

export interface BlockerRow {
    key: number
    purl: string
    enrichment: string
    bucket: string
    ingestState: string
    bucketUpdated: string
    bucketUpdatedIso: string
    reason: string
}

/** One row per blocking component, in the server's order (never re-sorted). */
export function blockerRows (status: ArtifactScanStatus, nowMs: number = Date.now()): BlockerRow[] {
    return (status.blockers || []).map((b, index) => ({
        key: index,
        purl: b.purl,
        enrichment: enrichmentCell(b, status.enrichmentGated),
        bucket: b.bucketIndex == null ? '-' : String(b.bucketIndex),
        ingestState: b.bucketIngestState ?? '-',
        bucketUpdated: dateCell(b.bucketLastUpdatedDate, nowMs),
        bucketUpdatedIso: b.bucketLastUpdatedDate ?? '',
        reason: b.reason
    }))
}

export function truncatedNote (status: ArtifactScanStatus): string | null {
    return status.blockersTruncated ? `showing ${status.blockers.length} of ${status.blockerTotal} blocking components` : null
}

export interface FactRow { key: string, label: string, value: string, iso?: string }

/** The facts list, twelve rows in a fixed order; every date row carries its raw ISO string. */
export function factRows (status: ArtifactScanStatus, nowMs: number = Date.now()): FactRow[] {
    const dateRow = (key: keyof ArtifactScanStatus, label: string): FactRow => {
        const iso = status[key] as string | null
        return { key, label, value: dateCell(iso, nowMs), iso: iso ?? '' }
    }
    return [
        { key: 'type', label: 'Type', value: status.type },
        { key: 'mapped', label: 'Mapped', value: status.mapped ? 'yes' : 'no' },
        dateRow('mappedAt', 'Mapped at'),
        dateRow('firstScanned', 'First scanned'),
        dateRow('lastScanned', 'Last scanned'),
        dateRow('fanOutAt', 'Fan-out at'),
        dateRow('fanOutRequeuedAt', 'Requeued at'),
        dateRow('newestIngestedBucketUpdate', 'Newest ingested bucket update'),
        {
            key: 'ingestedBucketCount',
            label: 'Ingested buckets',
            value: status.ingestedBucketCount === 0
                ? '0 (no ingested bucket yet; no fan-out pass runs until one ingests)'
                : String(status.ingestedBucketCount)
        },
        {
            key: 'components',
            label: 'Components',
            value: `${status.matchableComponentCount} matchable, ${status.rootComponentCount} root, ${status.terminalComponentCount} terminal, ${status.unmatchableComponentCount} without purl or cpe`
        },
        { key: 'enrichmentGated', label: 'Enrichment', value: enrichmentGatedText(status.enrichmentGated, status.blockerTotal) },
        { key: 'blockerTotal', label: 'Blocking components', value: String(status.blockerTotal) }
    ]
}
