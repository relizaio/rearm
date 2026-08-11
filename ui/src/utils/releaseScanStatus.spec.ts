import { describe, it, expect, vi } from 'vitest'

// releaseScanStatus imports the graphql client (for the org-integration
// lookup), which transitively boots keycloak against window at module load —
// mock it out; these tests only exercise the pure status logic.
vi.mock('./graphql', () => ({ default: { query: vi.fn() } }))

import { collectArtifactsForStatus, getReleaseScanStatus } from './releaseScanStatus'

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
