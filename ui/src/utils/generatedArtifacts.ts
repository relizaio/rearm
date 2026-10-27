/**
 * Artifacts ReARM generated for a release itself (Release.syntheticArtifactDetails): a published
 * aggregated SBOM, a VDR snapshot. They are never inventory, so the release page lists them in
 * their own read-only "Generated artifacts" section and never folds them into the Artifacts
 * table. Pure functions, no Vue import, so they run under vitest.
 */

import { isProEdition } from './editionCapabilities'

/**
 * Whether this edition serves generated artifacts. Both do: the CE backend serves
 * Release.syntheticArtifactDetails since the CE sync of SCORE-72 (Pro main 3befcf24a). The
 * parameter is kept so the call sites and the specs keep their shape;
 * generatedArtifactsSchemaDrift.spec.ts ties this to the CE schema.
 */
export function syntheticArtifactsAvailable (installationType: string | undefined | null): boolean {
    return isProEdition(installationType) || true
}

const BELONGS_TO_LABELS: Record<string, string> = {
    RELEASE: 'Release',
    SCE: 'Source Code Entry',
    DELIVERABLE: 'Deliverable',
    SYNTHETIC: 'Generated'
}

/** The label of an artifact belongsTo value; an unknown value (or an existing label) passes through. */
export function belongsToLabel (value: string | null | undefined): string {
    if (!value) return ''
    return BELONGS_TO_LABELS[value] ?? value
}

/** True for an artifact ReARM generated: the system tag, or a SYNTHETIC internal BOM. */
export function isSyntheticArtifact (ad: any): boolean {
    if (!ad) return false
    const tagged = Array.isArray(ad.tags)
        && ad.tags.some((t: any) => t && t.key === 'syntheticArtifact' && t.value === 'true')
    return tagged || ad.internalBom?.belongsTo === 'SYNTHETIC'
}

/**
 * Rows for the Generated artifacts table: the release's syntheticArtifactDetails only, in list
 * order, each marked as generated for the release. Empty when the release carries none.
 */
export function generatedArtifactRows (details: any[] | null | undefined, releaseUuid: string | null | undefined): any[] {
    if (!Array.isArray(details)) return []
    return details.map((ad: any) => ({
        ...ad,
        tags: Array.isArray(ad.tags) ? ad.tags : [],
        belongsTo: belongsToLabel('SYNTHETIC'),
        belongsToId: '',
        belongsToUUID: releaseUuid ?? ''
    }))
}

/**
 * The History text of a SYNTHETIC_ARTIFACT event: the generated artifact named by its type and
 * display id while it is still bound, its uuid once it is not (a REMOVED row).
 */
export function syntheticHistoryText (objectId: string | null | undefined, details: any[] | null | undefined): string {
    const ad = Array.isArray(details)
        ? details.find((a: any) => a && a.uuid === objectId)
        : undefined
    if (!ad) return `Generated artifact ${objectId ?? ''}`.trim()
    return ['Generated artifact', ad.type, ad.displayIdentifier].filter(Boolean).join(' ')
}
