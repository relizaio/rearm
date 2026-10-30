import { describe, it, expect, vi } from 'vitest'

// releaseScanStatus imports the graphql client (for the org-integration
// lookup), which transitively boots keycloak against window at module load —
// mock it out; these tests only exercise the pure status logic.
vi.mock('./graphql', () => ({ default: { query: vi.fn() } }))

import {
    collectArtifactsForStatus, getReleaseScanStatus, isScanNotApplicable, showsScanBadge,
    hasCompleteScanInputs, releaseBomScanState, scanBadgeBackground, ownNotScannableNote, NOT_SCANNABLE_GREY
} from './releaseScanStatus'
import { notScannableTitle, notScannableReleaseTitle, reasonLabel } from './artifactScanStatus'

/** A minimal unscanned BOM artifact — the shape isBomDtrackPending flags. */
function pendingBom (componentUuid?: string) {
    return { type: 'BOM', componentUuid, metrics: {} }
}
function scannedBom (componentUuid?: string) {
    return { type: 'BOM', componentUuid, metrics: { firstScanned: '2026-07-12T21:52:12Z' } }
}

const OWN_COMPONENT = 'aaaaaaaa-0000-0000-0000-000000000001'
const OTHER_COMPONENT = 'bbbbbbbb-0000-0000-0000-000000000002'

function releaseWithSceArts (arts: any[], componentUuid: string | null = OWN_COMPONENT) {
    return {
        componentDetails: componentUuid ? { uuid: componentUuid } : undefined,
        metrics: { firstScanned: '2026-07-12T22:00:00Z' },
        sourceCodeEntryDetails: { artifactDetails: arts },
        variantDetails: []
    }
}

describe('collectArtifactsForStatus SCE component scoping', () => {
    it('excludes another component\'s SCE artifacts', () => {
        const release = releaseWithSceArts([pendingBom(OTHER_COMPONENT), scannedBom(OWN_COMPONENT)])
        const collected = collectArtifactsForStatus(release)
        expect(collected).toHaveLength(1)
        expect(collected[0].componentUuid).toBe(OWN_COMPONENT)
    })

    it('keeps commit-scoped signature artifacts from any component', () => {
        const release = releaseWithSceArts([
            { type: 'SIGNATURE', componentUuid: OTHER_COMPONENT },
            { type: 'SIGNED_PAYLOAD', componentUuid: OTHER_COMPONENT }
        ])
        expect(collectArtifactsForStatus(release)).toHaveLength(2)
    })

    it('keeps artifacts with no component attribution (commit-scoped convention)', () => {
        const release = releaseWithSceArts([pendingBom(undefined)])
        expect(collectArtifactsForStatus(release)).toHaveLength(1)
    })

    it('falls back to including everything when the release lacks component fields', () => {
        const release = releaseWithSceArts([pendingBom(OTHER_COMPONENT)], null)
        expect(collectArtifactsForStatus(release)).toHaveLength(1)
    })
})

describe('getReleaseScanStatus with shared-SCE artifacts', () => {
    it('does not report DTrack pending for another component\'s unscanned BOM', () => {
        // The 2026-07-12 prod shape: this release's own BOMs are scanned, but a
        // sibling component's fs-BOM on the shared commit never was. The badge
        // must not pin on an artifact the artifact table does not display.
        const release = releaseWithSceArts([scannedBom(OWN_COMPONENT), pendingBom(OTHER_COMPONENT)])
        expect(getReleaseScanStatus(release, true).kind).toBe('ready')
    })

    it('still reports DTrack pending for this component\'s own unscanned BOM', () => {
        const release = releaseWithSceArts([pendingBom(OWN_COMPONENT)])
        expect(getReleaseScanStatus(release, true).kind).toBe('dtrack-pending')
    })
})

describe('a board document is never scanned (RD4-11)', () => {
    const unscanned = { lifecycle: 'DRAFT', metrics: null, artifactDetails: [pendingBom()] }

    it('reports not-applicable for a BOARD_DOCUMENT component, whatever its metrics say', () => {
        const release = { ...unscanned, componentDetails: { uuid: OWN_COMPONENT, kind: 'BOARD_DOCUMENT' } }
        expect(isScanNotApplicable(release)).toBe(true)
        expect(getReleaseScanStatus(release, true).kind).toBe('not-applicable')
        expect(getReleaseScanStatus(release, false).kind).toBe('not-applicable')
    })

    it('reports not-applicable when the backend serves NOT_APPLICABLE (a legacy GENERIC document component)', () => {
        const release = { lifecycle: 'ASSEMBLED', componentDetails: { kind: 'GENERIC' },
            metrics: { dtrackFetchStatus: 'NOT_APPLICABLE', firstScanned: null } }
        expect(isScanNotApplicable(release)).toBe(true)
        expect(getReleaseScanStatus(release, true).kind).toBe('not-applicable')
    })

    it('leaves a software release as it was: pending until scanned, then ready', () => {
        const pending = { ...unscanned, componentDetails: { uuid: OWN_COMPONENT, kind: 'GENERIC' } }
        expect(isScanNotApplicable(pending)).toBe(false)
        expect(getReleaseScanStatus(pending, true).kind).toBe('dtrack-pending')
        expect(getReleaseScanStatus({ ...pending, artifactDetails: [], metrics: {} }, false).kind).toBe('scan-pending')
        const ready = { componentDetails: { kind: 'HELM' }, metrics: { firstScanned: '2026-09-29T00:00:00Z' } }
        expect(getReleaseScanStatus(ready, true).kind).toBe('ready')
    })

    it('draws a badge for pending and rejected states only', () => {
        expect(showsScanBadge({ kind: 'not-applicable', label: '', title: '' })).toBe(false)
        expect(showsScanBadge({ kind: 'ready', label: '', title: '' })).toBe(false)
        for (const kind of ['scan-pending', 'dtrack-pending', 'enrichment-pending', 'rejected', 'not-scannable'] as const) {
            expect(showsScanBadge({ kind, label: '', title: '' })).toBe(true)
        }
    })
})

// --- TPF-127: the not-scannable marker, the release-level kind, the colour and the list note ---

const M1 = { reason: 'ONLY_ROOT', at: '2026-10-29T08:00:00Z', components: 1, root: 1, terminal: 0, unmatchable: 0 }
const M2 = { reason: 'NO_COMPONENTS', at: '2026-10-29T08:00:00Z', components: 0, root: 0, terminal: 0, unmatchable: 0 }
const markedBom = (marker: any = M1, componentUuid?: string) => ({ type: 'BOM', componentUuid, metrics: { notScannable: marker } })
const ANCHORED = { firstScanned: '2026-10-29T08:00:00Z', lastScanned: '2026-10-29T08:00:00Z', critical: 0 }
/** The full single-release document's shape: own, SCE and deliverable artifacts selected. */
function full (own: any[], extra: any = {}) {
    return { componentDetails: { uuid: OWN_COMPONENT, type: 'COMPONENT' }, metrics: ANCHORED, artifactDetails: own,
        sourceCodeEntryDetails: null, variantDetails: [], ...extra }
}
/** A list document's row: own artifacts and an SCE without artifacts, no variantDetails. */
function listRow (own: any[], metrics: any = ANCHORED, type = 'COMPONENT') {
    return { componentDetails: { uuid: OWN_COMPONENT, type }, metrics, artifactDetails: own, sourceCodeEntryDetails: { uuid: 's' } }
}

describe('hasCompleteScanInputs (TPF-127, D17)', () => {
    it('holds only for a row that selected the three sources', () => {
        expect(hasCompleteScanInputs({ artifactDetails: [], sourceCodeEntryDetails: null, variantDetails: [] })).toBe(true)
        expect(hasCompleteScanInputs({ artifactDetails: [], sourceCodeEntryDetails: { uuid: 's', artifactDetails: [] }, variantDetails: [] })).toBe(true)
        expect(hasCompleteScanInputs({ artifactDetails: [], sourceCodeEntryDetails: null, variantDetails: [{ uuid: 'v', outboundDeliverableDetails: [] }] })).toBe(true)
        expect(hasCompleteScanInputs({ artifactDetails: [markedBom()], sourceCodeEntryDetails: { uuid: 's' }, componentDetails: { uuid: 'c', type: 'COMPONENT' } })).toBe(false)
        expect(hasCompleteScanInputs({ artifactDetails: [{ metrics: {} }] })).toBe(false)
        expect(hasCompleteScanInputs({ artifactDetails: [], sourceCodeEntryDetails: null, variantDetails: [{ uuid: 'v', type: 'BASE' }] })).toBe(false)
        expect(hasCompleteScanInputs({ artifactDetails: [], variantDetails: [] })).toBe(false)
        expect(hasCompleteScanInputs({ sourceCodeEntryDetails: null, variantDetails: [] })).toBe(false)
        expect(hasCompleteScanInputs(undefined)).toBe(false)
        expect(hasCompleteScanInputs({})).toBe(false)
    })
})

describe('releaseBomScanState (TPF-127)', () => {
    it('derives the partition from the whole set', () => {
        expect(releaseBomScanState(full([]))).toEqual({ state: 'NO_BOMS', notScannableBoms: 0, reasons: [], markers: [], source: 'derived' })
        expect(releaseBomScanState(full([scannedBom(), pendingBom()]))?.state).toBe('ALL_SCANNABLE')
        const some = releaseBomScanState(full([markedBom(), scannedBom()]))
        expect(some).toEqual({ state: 'SOME_NOT_SCANNABLE', notScannableBoms: 1, reasons: ['ONLY_ROOT'], markers: [M1], source: 'derived' })
        const all = releaseBomScanState(full([markedBom(M2), markedBom(M1), markedBom(M2)]))
        expect(all?.state).toBe('ALL_NOT_SCANNABLE')
        expect(all?.markers).toEqual([M2, M1, M2])
        expect(all?.reasons).toEqual(['NO_COMPONENTS', 'ONLY_ROOT'])
    })

    it('counts null and a missing key as unmarked, and ignores a non-BOM carrying a marker', () => {
        expect(releaseBomScanState(full([{ type: 'BOM', metrics: { notScannable: null } }, { type: 'BOM', metrics: {} }]))?.state).toBe('ALL_SCANNABLE')
        expect(releaseBomScanState(full([{ type: 'VEX', metrics: { notScannable: M1 } }]))?.state).toBe('NO_BOMS')
    })

    it('scopes SCE artifacts by component and counts deliverable artifacts', () => {
        const otherSce = full([scannedBom()], { sourceCodeEntryDetails: { uuid: 's', artifactDetails: [markedBom(M1, OTHER_COMPONENT)] } })
        expect(releaseBomScanState(otherSce)?.state).toBe('ALL_SCANNABLE')
        const deliverable = full([scannedBom()], { variantDetails: [{ uuid: 'v', outboundDeliverableDetails: [{ uuid: 'd', artifactDetails: [markedBom()] }] }] })
        expect(releaseBomScanState(deliverable)?.state).toBe('SOME_NOT_SCANNABLE')
        const ownMarkedSceScanned = full([markedBom()], { sourceCodeEntryDetails: { uuid: 's', artifactDetails: [scannedBom(OWN_COMPONENT)] } })
        expect(releaseBomScanState(ownMarkedSceScanned)?.state).toBe('SOME_NOT_SCANNABLE')
    })

    it('returns null for a partial row', () => {
        expect(releaseBomScanState(listRow([markedBom()]))).toBeNull()
        expect(releaseBomScanState({ artifactDetails: [{ metrics: { notScannable: M1 } }] })).toBeNull()
    })

    it('reads the server state first, and only by one of the four names', () => {
        const served = { metrics: { bomScan: { state: 'ALL_NOT_SCANNABLE', scannableBoms: 0, notScannableBoms: 2, reasons: ['ONLY_ROOT', 'NO_COMPONENTS'] } } }
        expect(releaseBomScanState(served)).toEqual({ state: 'ALL_NOT_SCANNABLE', notScannableBoms: 2, reasons: ['ONLY_ROOT', 'NO_COMPONENTS'], markers: [], source: 'server' })
        const wins = full([markedBom()], { metrics: { ...ANCHORED, bomScan: { state: 'SOME_NOT_SCANNABLE', scannableBoms: 1, notScannableBoms: 1, reasons: ['ONLY_ROOT'] } } })
        expect(releaseBomScanState(wins)).toMatchObject({ state: 'SOME_NOT_SCANNABLE', source: 'server' })
        for (const bomScan of [null, {}, { state: 'SOMETHING' }]) {
            expect(releaseBomScanState(full([markedBom()], { metrics: { ...ANCHORED, bomScan } }))).toMatchObject({ state: 'ALL_NOT_SCANNABLE', source: 'derived' })
            expect(releaseBomScanState(listRow([markedBom()], { ...ANCHORED, bomScan }))).toBeNull()
        }
    })
})

describe('getReleaseScanStatus with not-scannable BOMs (TPF-127)', () => {
    it('reads not-scannable for an all-marked release from the whole set, anchored or not, rejected or not', () => {
        const anchored = getReleaseScanStatus(full([markedBom()]), true)
        expect(anchored.kind).toBe('not-scannable')
        expect(anchored.label).toBe('No scannable components')
        expect(anchored.title).toContain(reasonLabel('ONLY_ROOT'))
        expect(getReleaseScanStatus(full([markedBom()], { metrics: { firstScanned: null } }), true).kind).toBe('not-scannable')
        expect(getReleaseScanStatus(full([markedBom()], { lifecycle: 'REJECTED' }), true).kind).toBe('not-scannable')
        expect(getReleaseScanStatus(full([markedBom()]), false).kind).toBe('not-scannable')
    })

    it('keeps ready where a scanned BOM carries the counts (F-5)', () => {
        expect(getReleaseScanStatus(full([markedBom(), scannedBom()]), true).kind).toBe('ready')
        const sce = full([markedBom()], { metrics: { ...ANCHORED, critical: 3 }, sourceCodeEntryDetails: { uuid: 's', artifactDetails: [scannedBom(OWN_COMPONENT)] } })
        expect(getReleaseScanStatus(sce, true).kind).toBe('ready')
        const deliverable = full([markedBom()], { variantDetails: [{ uuid: 'v', outboundDeliverableDetails: [{ uuid: 'd', artifactDetails: [scannedBom()] }] }] })
        expect(getReleaseScanStatus(deliverable, true).kind).toBe('ready')
    })

    it('a marked BOM is never awaiting submission; an unmarked one still is', () => {
        const sceAwaiting = full([markedBom()], { sourceCodeEntryDetails: { uuid: 's', artifactDetails: [pendingBom(OWN_COMPONENT)] } })
        expect(getReleaseScanStatus(sceAwaiting, true).kind).toBe('dtrack-pending')
        expect(getReleaseScanStatus(full([pendingBom()]), true).kind).toBe('dtrack-pending')
        expect(getReleaseScanStatus(full([{ type: 'BOM', metrics: { notScannable: null } }]), true).kind).toBe('dtrack-pending')
        expect(getReleaseScanStatus(full([markedBom(), { type: 'BOM', enrichmentStatus: 'PENDING', metrics: {} }]), true).kind).toBe('enrichment-pending')
    })

    it('never gives a product the kind', () => {
        const product = { componentDetails: { uuid: OWN_COMPONENT, type: 'PRODUCT' } }
        expect(getReleaseScanStatus(full([markedBom()], product), true).kind).toBe('ready')
        expect(getReleaseScanStatus(full([markedBom()], { ...product, metrics: { firstScanned: null } }), true).kind).toBe('scan-pending')
    })

    it('a list row derives nothing; a served state decides there', () => {
        expect(getReleaseScanStatus(listRow([markedBom()]), true).kind).toBe('ready')
        expect(getReleaseScanStatus(listRow([markedBom()], { firstScanned: null }), true).kind).toBe('scan-pending')
        const served = { ...ANCHORED, bomScan: { state: 'ALL_NOT_SCANNABLE', scannableBoms: 0, notScannableBoms: 1, reasons: ['ONLY_ROOT'] } }
        expect(getReleaseScanStatus(listRow([markedBom()], served), true).kind).toBe('not-scannable')
        expect(getReleaseScanStatus(listRow([markedBom()], served, 'PRODUCT'), true).kind).toBe('ready')
    })

    it('a document component stays not-applicable', () => {
        const doc = full([markedBom()], { componentDetails: { uuid: OWN_COMPONENT, type: 'COMPONENT', kind: 'BOARD_DOCUMENT' } })
        expect(getReleaseScanStatus(doc, true).kind).toBe('not-applicable')
    })
})

describe('scanBadgeBackground (TPF-127)', () => {
    it('gives each kind its colour', () => {
        const of = (kind: any) => scanBadgeBackground({ kind, label: '', title: '' })
        expect(of('rejected')).toBe('#d03050')
        expect(of('enrichment-pending')).toBe('#fd8c00')
        expect(of('not-scannable')).toBe('#9e9e9e')
        expect(of('scan-pending')).toBe('#ffc107')
        expect(of('dtrack-pending')).toBe('#ffc107')
    })
})

describe('ownNotScannableNote (TPF-127, round 5)', () => {
    it('counts the own typed BOMs that carry the marker', () => {
        expect(ownNotScannableNote(listRow([markedBom(M1)]))).toEqual({ count: 1, label: '1 BOM not scannable', title: notScannableTitle(M1) })
        expect(ownNotScannableNote(listRow([markedBom(M1), markedBom(M2)])))
            .toEqual({ count: 2, label: '2 BOMs not scannable', title: notScannableReleaseTitle([M1, M2], 2, ['ONLY_ROOT', 'NO_COMPONENTS']) })
        expect(ownNotScannableNote(listRow([markedBom(M1), scannedBom()]))?.count).toBe(1)
    })

    it('is null for an untyped row, a marker on the SCE only, null markers, no artifacts, and a served tally', () => {
        expect(ownNotScannableNote({ artifactDetails: [{ metrics: { notScannable: M1 } }] })).toBeNull()
        expect(ownNotScannableNote(full([scannedBom()], { sourceCodeEntryDetails: { uuid: 's', artifactDetails: [markedBom(M1, OWN_COMPONENT)] } }))).toBeNull()
        expect(ownNotScannableNote({ artifactDetails: [{ type: 'BOM', metrics: { notScannable: null, firstScanned: 'x' } }] })).toBeNull()
        expect(ownNotScannableNote({})).toBeNull()
        expect(ownNotScannableNote({ metrics: { bomScan: { state: 'ALL_NOT_SCANNABLE', scannableBoms: 0, notScannableBoms: 1, reasons: ['ONLY_ROOT'] } } })).toBeNull()
    })

    it('uses the kind grey', () => {
        expect(NOT_SCANNABLE_GREY).toBe(scanBadgeBackground({ kind: 'not-scannable', label: '', title: '' }))
    })
})
