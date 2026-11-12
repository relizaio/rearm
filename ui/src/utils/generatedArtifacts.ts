/**
 * Artifacts ReARM generated for a release itself (Release.syntheticArtifactDetails): a published
 * aggregated SBOM, a VDR snapshot. They are never inventory, so the release page lists them in
 * their own read-only "Generated artifacts" section and never folds them into the Artifacts
 * table. Pure functions, no Vue import, so they run under vitest.
 */

import { isProEdition } from './editionCapabilities'
import { artifactDownloadRef, type ArtifactDownloadRef } from './artifactDownload'
import { packageKindLabel, packagePartLabel } from './packageSettings'
import { ARTIFACT_TYPE_LABELS, labelOrHumanized } from './fdaLabels'

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
 * The History text of a SYNTHETIC_ARTIFACT event (FDAW-78 3.4): `Generated artifact: <type label> <display
 * id>` while it is still bound, missing pieces dropped (`Generated artifact` when both are); `Generated
 * artifact (no longer attached)` once it is not (a REMOVED row). Never the artifact's uuid.
 */
export function syntheticHistoryText (objectId: string | null | undefined, details: any[] | null | undefined): string {
    const ad = Array.isArray(details)
        ? details.find((a: any) => a && a.uuid === objectId)
        : undefined
    if (!ad) return 'Generated artifact (no longer attached)'
    const rest = [labelOrHumanized(ARTIFACT_TYPE_LABELS, ad.type), ad.displayIdentifier].filter(Boolean).join(' ')
    return rest ? 'Generated artifact: ' + rest : 'Generated artifact'
}

/** A tag's value on a generated artifact row, or '' when absent. */
function tagValue (ad: any, key: string): string {
    const tags: any[] = Array.isArray(ad?.tags) ? ad.tags : []
    const v = tags.find((t: any) => t && t.key === key)?.value
    return typeof v === 'string' ? v : ''
}

/**
 * The direct download of a generated artifact: its current version, raw. A generated artifact is
 * never augmented after it is written, so the augmented-or-raw choice does not apply. Saved under
 * its display identifier, which every generator sets to a file name (the TEA aggregate's
 * <component>-<version>-<profile>-tea-aggregate.cdx.json, a submission part's package file name).
 * Null, fail closed, without an artifact uuid or an integer version.
 */
export function generatedArtifactDownloadRef (ad: any): ArtifactDownloadRef | null {
    return artifactDownloadRef(ad?.uuid, ad?.version, ad?.displayIdentifier)
}

export interface GeneratedArtifactOrigin {
    /** What the artifact was generated for: 'TEA publication', or '<kind> submission (v<n>)'. */
    title: string
    /** Label and value pairs under the title: the TEA profile, or the submission part. */
    details: Array<{ label: string, value: string }>
}

/**
 * What a generated artifact was generated for, from the system tags its generator writes:
 * - a TEA publication's aggregate (teaAggregate, teaProfileName): 'TEA publication' and its profile;
 * - a regulatory submission's part (SubmissionTags: submissionKind, submissionPart,
 *   submissionManifest, submissionVersion): '<kind> submission (v<n>)' and the part, the manifest
 *   named as such.
 * Null for an artifact neither wrote.
 */
export function generatedArtifactOrigin (ad: any): GeneratedArtifactOrigin | null {
    if (tagValue(ad, 'teaAggregate') === 'true') {
        const profile = tagValue(ad, 'teaProfileName')
        return { title: 'TEA publication', details: profile ? [{ label: 'Profile', value: profile }] : [] }
    }
    const kind = tagValue(ad, 'submissionKind')
    const part = tagValue(ad, 'submissionManifest') === 'true' ? 'Manifest' : packagePartLabel(tagValue(ad, 'submissionPart'))
    if (!kind && !part) return null
    const version = tagValue(ad, 'submissionVersion')
    const title = [kind ? packageKindLabel(kind) : '', 'submission'].filter(Boolean).join(' ') + (version ? ' (v' + version + ')' : '')
    return { title, details: part ? [{ label: 'Part', value: part }] : [] }
}
