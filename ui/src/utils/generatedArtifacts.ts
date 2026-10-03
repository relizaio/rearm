/**
 * Artifacts ReARM generated for a release itself (Release.syntheticArtifactDetails): a published
 * aggregated SBOM, a VDR snapshot. They are never inventory, so the release page lists them in
 * their own read-only "Generated artifacts" section and never folds them into the Artifacts
 * table. Pure functions, no Vue import, so they run under vitest.
 */

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
 * order, each marked as generated for this release. Empty when the release carries none.
 */
export function generatedArtifactRows (release: any): any[] {
    const details = release?.syntheticArtifactDetails
    if (!Array.isArray(details)) return []
    return details.map((ad: any) => ({
        ...ad,
        tags: Array.isArray(ad.tags) ? ad.tags : [],
        belongsTo: belongsToLabel('SYNTHETIC'),
        belongsToId: '',
        belongsToUUID: release.uuid
    }))
}

/**
 * The History text of a SYNTHETIC_ARTIFACT event: the generated artifact named by its type and
 * display id while it is still bound, its uuid once it is not (a REMOVED row).
 */
export function syntheticHistoryText (objectId: string | null | undefined, release: any): string {
    const ad = Array.isArray(release?.syntheticArtifactDetails)
        ? release.syntheticArtifactDetails.find((a: any) => a && a.uuid === objectId)
        : undefined
    if (!ad) return `Generated artifact ${objectId ?? ''}`.trim()
    return ['Generated artifact', ad.type, ad.displayIdentifier].filter(Boolean).join(' ')
}
