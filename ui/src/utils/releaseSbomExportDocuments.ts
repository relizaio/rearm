import gql from 'graphql-tag'

/**
 * The release BOM export, in three shapes. Written out as constants rather than built by string
 * concatenation so each is parsed at module load and validate-graphql.mjs can see it, and kept
 * out of ReleaseView so the schema-drift spec can validate them (nothing mounts ReleaseView).
 *
 * WITH_METADATA_FLAGS carries the per-export metadata flags; CORE is the document every backend
 * has always accepted. WITH_FILE_SWITCH adds excludeFileComponents (SCORE-11) and is sent ONLY
 * when the operator turned the switch on: a backend without the argument (a CE mirror before its
 * sync) rejects the whole document, and folding the argument into WITH_METADATA_FLAGS would turn
 * that rejection into flagless exports for everyone on such a server, switch or no switch.
 */
export const SBOM_EXPORT_WITH_FILE_SWITCH = gql`
    mutation releaseSbomExport($release: ID!, $tldOnly: Boolean, $ignoreDev: Boolean, $structure: BomStructureType, $belongsTo: ArtifactBelongsToEnum, $mediaType: BomMediaType, $excludeCoverageTypes: [ArtifactCoverageType], $includeSupportMetadata: Boolean, $includeInternalMetadata: Boolean, $excludeFileComponents: Boolean) {
        releaseSbomExport(release: $release, tldOnly: $tldOnly, ignoreDev: $ignoreDev, structure: $structure, belongsTo: $belongsTo, mediaType: $mediaType, excludeCoverageTypes: $excludeCoverageTypes, includeSupportMetadata: $includeSupportMetadata, includeInternalMetadata: $includeInternalMetadata, excludeFileComponents: $excludeFileComponents)
    }`

export const SBOM_EXPORT_WITH_METADATA_FLAGS = gql`
    mutation releaseSbomExport($release: ID!, $tldOnly: Boolean, $ignoreDev: Boolean, $structure: BomStructureType, $belongsTo: ArtifactBelongsToEnum, $mediaType: BomMediaType, $excludeCoverageTypes: [ArtifactCoverageType], $includeSupportMetadata: Boolean, $includeInternalMetadata: Boolean) {
        releaseSbomExport(release: $release, tldOnly: $tldOnly, ignoreDev: $ignoreDev, structure: $structure, belongsTo: $belongsTo, mediaType: $mediaType, excludeCoverageTypes: $excludeCoverageTypes, includeSupportMetadata: $includeSupportMetadata, includeInternalMetadata: $includeInternalMetadata)
    }`

export const SBOM_EXPORT_CORE = gql`
    mutation releaseSbomExport($release: ID!, $tldOnly: Boolean, $ignoreDev: Boolean, $structure: BomStructureType, $belongsTo: ArtifactBelongsToEnum, $mediaType: BomMediaType, $excludeCoverageTypes: [ArtifactCoverageType]) {
        releaseSbomExport(release: $release, tldOnly: $tldOnly, ignoreDev: $ignoreDev, structure: $structure, belongsTo: $belongsTo, mediaType: $mediaType, excludeCoverageTypes: $excludeCoverageTypes)
    }`
