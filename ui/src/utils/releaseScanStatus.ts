import gql from 'graphql-tag'
import graphqlClient from './graphql'
import { isDocumentComponent } from './agentDocumentsView'
import { notScannableReleaseTitle, notScannableCountLabel, type ArtifactNotScannable } from './artifactScanStatus'

/**
 * Per-org cache of configuredBaseIntegrations. The list is keyed by org uuid;
 * a missing entry means we haven't loaded yet for that org. Cleared per-page
 * mount in practice — rearm UI doesn't re-mount the same org constantly.
 */
const dtrackConfiguredCache = new Map<string, boolean>()

export async function isDtrackConfiguredForOrg (orgUuid: string): Promise<boolean> {
    if (!orgUuid) return false
    const cached = dtrackConfiguredCache.get(orgUuid)
    if (cached !== undefined) return cached
    try {
        const resp = await graphqlClient.query({
            query: gql`
                query configuredBaseIntegrations($org: ID!) {
                    configuredBaseIntegrations(org: $org)
                }`,
            variables: { org: orgUuid },
            fetchPolicy: 'cache-first'
        })
        const list: string[] = resp.data?.configuredBaseIntegrations || []
        const has = list.includes('DEPENDENCYTRACK')
        dtrackConfiguredCache.set(orgUuid, has)
        return has
    } catch {
        // Conservatively treat lookup failure as "not configured" so we don't
        // mislabel artifacts as DTrack-pending in an org that may never have
        // wired the integration up.
        dtrackConfiguredCache.set(orgUuid, false)
        return false
    }
}

export type ReleaseScanStatusKind = 'not-applicable' | 'enrichment-pending' | 'dtrack-pending' | 'scan-pending' | 'rejected' | 'not-scannable' | 'ready'

export interface ReleaseScanStatus {
    kind: ReleaseScanStatusKind
    label: string
    title: string
}

/** TPF-129's four states of a release's gathered BOMs, as the server names them. */
export type BomScanState = 'NO_BOMS' | 'ALL_SCANNABLE' | 'SOME_NOT_SCANNABLE' | 'ALL_NOT_SCANNABLE'
const BOM_SCAN_STATES: readonly BomScanState[] = ['NO_BOMS', 'ALL_SCANNABLE', 'SOME_NOT_SCANNABLE', 'ALL_NOT_SCANNABLE']

/** The release's BOM scan state as the UI knows it: the server's statement, or a derivation from the whole set. */
export interface ReleaseBomScan {
    state: BomScanState
    /** gathered BOMs carrying the marker: the server's count, or the markers' length */
    notScannableBoms: number
    /** the distinct reasons in first-seen order: the server's list, or the markers' */
    reasons: string[]
    /** the markers themselves when derived; empty when the server stated the tally */
    markers: ArtifactNotScannable[]
    source: 'server' | 'derived'
}

/**
 * Whether a release row carries the release's whole scan-input set, the set the backend gathers for
 * its metrics (own artifacts, the source code entry's, the variants' outbound deliverables'): the
 * shape the full single-release document selects, and the product page's child rows (TPF-127). A
 * list row (own artifacts only, no variantDetails), the product document's own row (variantDetails
 * without deliverables) and a child row served without the field (no artifactDetails) do not. A
 * release-level state may be derived from the whole set only (D17): a row proves what is true of
 * that row, never what is true of every BOM the release has.
 */
export function hasCompleteScanInputs (release: any): boolean {
    if (!Array.isArray(release?.artifactDetails)) return false
    if (release.sourceCodeEntryDetails === undefined) return false
    if (release.sourceCodeEntryDetails !== null && !Array.isArray(release.sourceCodeEntryDetails.artifactDetails)) return false
    if (!Array.isArray(release.variantDetails)) return false
    return release.variantDetails.every((v: any) => Array.isArray(v?.outboundDeliverableDetails))
}

/**
 * The partition of a release's gathered BOMs by the not-scannable marker (TPF-127), named as TPF-129's
 * BomScanState names it. The server's statement wins whenever a document selects it
 * (release.metrics.bomScan, exposed by a later task from the tally TPF-129 persists); otherwise the
 * partition is derived from the rows, and only when the row holds the whole scan-input set
 * (hasCompleteScanInputs). Null means "not known from this row": the caller renders as today. A BOM
 * is scannable unless it carries the marker: an absent key (a server without the field, or the core
 * document) and null both count as scannable.
 */
export function releaseBomScanState (release: any): ReleaseBomScan | null {
    const served = release?.metrics?.bomScan
    if (served && BOM_SCAN_STATES.includes(served.state)) {
        return {
            state: served.state,
            notScannableBoms: typeof served.notScannableBoms === 'number' ? served.notScannableBoms : 0,
            reasons: Array.isArray(served.reasons) ? served.reasons.map((r: any) => String(r)) : [],
            markers: [],
            source: 'server'
        }
    }
    if (!hasCompleteScanInputs(release)) return null
    const boms = collectArtifactsForStatus(release).filter((a) => a?.type === 'BOM')
    const markers: ArtifactNotScannable[] = boms.map((a) => a?.metrics?.notScannable).filter((m) => !!m)
    const reasons = [...new Set(markers.map((m) => String(m.reason)))]
    const base = { notScannableBoms: markers.length, reasons, markers, source: 'derived' as const }
    if (boms.length === 0) return { state: 'NO_BOMS', ...base }
    if (markers.length === 0) return { state: 'ALL_SCANNABLE', ...base }
    if (markers.length === boms.length) return { state: 'ALL_NOT_SCANNABLE', ...base }
    return { state: 'SOME_NOT_SCANNABLE', ...base }
}

/**
 * Decide whether to show a pending badge or fall through to the regular
 * vulnerability/violation circles for a release row in a list view.
 *
 *  enrichment-pending  any BOM artifact still being enriched in rebom
 *  dtrack-pending      DTrack is configured but at least one BOM artifact
 *                      hasn't been submitted yet (no project UUID, no failure)
 *  scan-pending        no firstScanned on the release yet — initial scan has
 *                      not completed across all scannable inputs (artifacts +
 *                      child releases for products). Catches PENDING-lifecycle
 *                      releases and product releases waiting on a child.
 *  rejected            the release is REJECTED and its scan never completed —
 *                      the scan will not run for a rejected release, so a
 *                      "pending" badge would wait forever. Enrichment-pending
 *                      is NOT remapped: rebom enrichment is artifact-level and
 *                      still finishes regardless of release lifecycle.
 *  not-scannable       every gathered BOM carries the fan-out's not-scannable marker (TPF-127): nothing to scan, said before firstScanned,
 *                      because the backend anchors such a release as scan complete with zero counts; only from the server's
 *                      statement or a document holding the whole scan-input set
 *  ready               firstScanned is set — caller renders the existing circles
 *  not-applicable      a board's document round (task RD4-11): never scanned, so
 *                      the caller shows no scan state at all — no badge and no
 *                      circles. Checked first: a document never becomes ready.
 */
export function getReleaseScanStatus (release: any, dtrackConfigured: boolean): ReleaseScanStatus {
    if (isScanNotApplicable(release)) {
        return { kind: 'not-applicable', label: '', title: 'Documents are not scanned for vulnerabilities' }
    }
    const artifacts: any[] = collectArtifactsForStatus(release)
    const hasEnrichmentPending = artifacts.some((a) => a?.enrichmentStatus === 'PENDING')
    if (hasEnrichmentPending) {
        return {
            kind: 'enrichment-pending',
            label: 'Enriching…',
            title: 'BOM enrichment in progress for at least one artifact'
        }
    }
    const rejected = release?.lifecycle === 'REJECTED'
    if (dtrackConfigured) {
        const hasDtrackPending = artifacts.some((a) => isBomDtrackPending(a))
        if (hasDtrackPending) {
            if (rejected) return rejectedStatus()
            return {
                kind: 'dtrack-pending',
                label: 'DTrack pending',
                title: 'Awaiting Dependency-Track submission for at least one BOM'
            }
        }
    }
    // TPF-127: every gathered BOM carries the fan-out's not-scannable marker: nothing to scan. Said
    // before the firstScanned test, because the backend anchors such a release as scan complete
    // (firstScanned, lastScanned, zero counts) and the circles would read as a clean scan. Known only
    // from the server's statement or from a document that holds the whole scan-input set (null
    // otherwise: today's test decides, D17). Never for a product: its counts are its children's rollup.
    if (release?.componentDetails?.type !== 'PRODUCT') {
        const scan = releaseBomScanState(release)
        if (scan?.state === 'ALL_NOT_SCANNABLE') {
            return { kind: 'not-scannable', label: 'No scannable components', title: notScannableReleaseTitle(scan.markers, scan.notScannableBoms, scan.reasons) }
        }
    }
    if (!release?.metrics?.firstScanned) {
        if (rejected) return rejectedStatus()
        return {
            kind: 'scan-pending',
            label: 'Scan pending',
            title: 'Initial scan has not completed for this release yet'
        }
    }
    return { kind: 'ready', label: '', title: '' }
}

/**
 * Whether a release is outside vulnerability scanning (task RD4-11): its component
 * is a board's BOARD_DOCUMENT component, or the backend says so on the metrics it serves
 * (NOT_APPLICABLE, which also covers a legacy board's GENERIC document component).
 */
export function isScanNotApplicable (release: any): boolean {
    return isDocumentComponent(release?.componentDetails)
        || release?.metrics?.dtrackFetchStatus === 'NOT_APPLICABLE'
}

/** Whether a status is drawn as a badge: every pending or rejected state, not ready or not-applicable. */
export function showsScanBadge (status: ReleaseScanStatus): boolean {
    return status.kind !== 'ready' && status.kind !== 'not-applicable'
}

export const NOT_SCANNABLE_GREY = '#9e9e9e'

/** The badge colour per kind: red for rejected, orange for enrichment, grey for nothing to scan, yellow for the pending states. */
export function scanBadgeBackground (status: ReleaseScanStatus): string {
    if (status.kind === 'rejected') return '#d03050'
    if (status.kind === 'enrichment-pending') return '#fd8c00'
    if (status.kind === 'not-scannable') return NOT_SCANNABLE_GREY
    return '#ffc107'
}

/** What a list row may say beside its circles about the BOMs it holds (TPF-127, round 5). */
export interface ReleaseNotScannableNote { count: number, label: string, title: string }

/**
 * The note beside a row's circles when the row's own BOM artifacts carry the fan-out's marker: a count
 * of what the row holds, never a release-level verdict (D17: that needs the whole set or the server's
 * tally). Own artifacts only (the list documents select no other); typed BOMs only (an untyped row,
 * the branch list's, holds no marker a reader can read, D18). Null when no held BOM is marked.
 */
export function ownNotScannableNote (release: any): ReleaseNotScannableNote | null {
    const own: any[] = Array.isArray(release?.artifactDetails) ? release.artifactDetails : []
    const markers: ArtifactNotScannable[] = own.filter((a) => a?.type === 'BOM' && a?.metrics?.notScannable).map((a) => a.metrics.notScannable)
    if (markers.length === 0) return null
    const reasons = [...new Set(markers.map((m) => String(m.reason)))]
    return { count: markers.length, label: notScannableCountLabel(markers.length), title: notScannableReleaseTitle(markers, markers.length, reasons) }
}

function rejectedStatus (): ReleaseScanStatus {
    return {
        kind: 'rejected',
        label: 'Release rejected',
        title: 'Release was rejected — the scan will not run for this release'
    }
}

/** Commit-scoped SCE artifact types — properties of the commit, not of any
 *  one component; shown (and counted) on every release sharing the SCE. */
const COMMIT_SCOPED_ARTIFACT_TYPES = ['SIGNATURE', 'SIGNED_PAYLOAD']

/**
 * Collect the artifacts that should drive this release's scan-status badge.
 *
 * SCE artifacts are component-scoped to match the release-view artifact
 * table: SCEs are canonical per (vcs, commit) and shared across every
 * component built from that commit, with per-component artifacts tagged
 * with the attaching component's uuid. Another component's still-scanning
 * BOM must not pin this release's badge on "DTrack pending" — the table
 * doesn't even display that artifact, so the badge would point at nothing
 * (observed in prod: one component's stuck fs-BOM kept every release
 * sharing the commit on a permanent pending badge). Falls back to
 * including everything when the row lacks component attribution fields
 * (older data / slimmer fragments).
 */
export function collectArtifactsForStatus (release: any): any[] {
    const out: any[] = []
    if (Array.isArray(release?.artifactDetails)) out.push(...release.artifactDetails)
    if (Array.isArray(release?.sourceCodeEntryDetails?.artifactDetails)) {
        const releaseComponent = release?.componentDetails?.uuid || release?.component
        out.push(...release.sourceCodeEntryDetails.artifactDetails.filter((ad: any) =>
            !ad?.componentUuid
            || !releaseComponent
            || ad.componentUuid === releaseComponent
            || COMMIT_SCOPED_ARTIFACT_TYPES.includes(ad?.type)))
    }
    if (Array.isArray(release?.variantDetails)) {
        for (const v of release.variantDetails) {
            const dels = v?.outboundDeliverableDetails || []
            for (const d of dels) {
                if (Array.isArray(d?.artifactDetails)) out.push(...d.artifactDetails)
            }
        }
    }
    return out
}

function isBomDtrackPending (a: any): boolean {
    if (!a || a.type !== 'BOM') return false
    const m = a.metrics || {}
    if (m.dtrackSubmissionFailed) return false
    if (m.dependencyTrackFullUri) return false
    // A BOM the fan-out marked not scannable (metrics.notScannable, TPF-127) is not awaiting submission:
    // nothing will be submitted. The release-level kind decides next (releaseBomScanState); a list row
    // says what it holds beside its circles (ownNotScannableNote).
    if (m.notScannable) return false
    // Synthetic-DTrack artifacts are scanned via a shared bucket project and so
    // never carry their own dependencyTrackFullUri; once firstScanned is set the
    // artifact has been scanned and is no longer awaiting submission.
    if (m.firstScanned) return false
    return true
}
