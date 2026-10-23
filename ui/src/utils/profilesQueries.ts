import gql from 'graphql-tag'

// Named SBOM profiles and regulatory package settings (task TEA-30; the backend of TEA-27 and
// TEA-33). Pro-only schema surfaces, like the TEA documents: every caller sends them only behind
// teaProfilesAvailable (profiles) or regulatoryPackagesAvailable (package settings), and none of
// them is part of a shared fragment. teaProfileSchemaDrift.spec.ts validates them against both
// schemas.

const SBOM_PROFILE_FIELDS_GQL = `
    uuid
    org
    scope
    object
    name
    mode
    followedPerspective
    description
    dependencyDepth
    optionalDependencies
    structure
    sources
    excludedCoverage
    supportMetadata
    internalMetadata
    vulnerabilityDocuments
    revision
    createdDate
    lastUpdatedBy
`

const SBOM_PROFILE_RESOLUTION_GQL = `
    status
    source
    sourceObject
    profile { ${SBOM_PROFILE_FIELDS_GQL} }
    conflictingPerspectives { uuid name type }
`

const SBOM_PROFILES = gql`
query SbomProfiles($org: ID!, $scope: SettingsScope!, $object: ID) {
    sbomProfiles(org: $org, scope: $scope, object: $object) { ${SBOM_PROFILE_FIELDS_GQL} }
}`

const SBOM_PROFILES_OF_ORG = gql`
query SbomProfilesOfOrg($org: ID!) {
    sbomProfilesOfOrg(org: $org) { uuid scope object name mode followedPerspective description revision }
}`

const SBOM_PROFILE_CATALOG = gql`
query SbomProfileCatalog($org: ID!, $scope: SettingsScope!, $object: ID) {
    sbomProfileCatalog(org: $org, scope: $scope, object: $object) { name resolution { ${SBOM_PROFILE_RESOLUTION_GQL} } }
}`

const SBOM_PROFILE_EDITOR_VIEW = gql`
query SbomProfileEditorView($org: ID!, $scope: SettingsScope!, $object: ID, $name: String!) {
    sbomProfileEditorView(org: $org, scope: $scope, object: $object, name: $name) {
        stored { ${SBOM_PROFILE_FIELDS_GQL} }
        effective { ${SBOM_PROFILE_RESOLUTION_GQL} }
        parent { ${SBOM_PROFILE_RESOLUTION_GQL} }
        supportInjection
    }
}`

const SAVE_SBOM_PROFILE = gql`
mutation SaveSbomProfile($org: ID!, $scope: SettingsScope!, $object: ID, $name: String!, $profile: SbomProfileInput!) {
    saveSbomProfile(org: $org, scope: $scope, object: $object, name: $name, profile: $profile) { ${SBOM_PROFILE_FIELDS_GQL} }
}`

const DELETE_SBOM_PROFILE = gql`
mutation DeleteSbomProfile($org: ID!, $scope: SettingsScope!, $object: ID, $name: String!) {
    deleteSbomProfile(org: $org, scope: $scope, object: $object, name: $name)
}`

const PACKAGE_PART_OPTIONS_GQL = `selection suppressedFindings inTriageFindings vexFormat artifactTypes`

const PACKAGE_SETTINGS_FIELDS_GQL = `
    uuid
    org
    scope
    object
    kind
    mode
    followedPerspective
    sbomProfile
    parts { part options { ${PACKAGE_PART_OPTIONS_GQL} } }
    description
    revision
    createdDate
    lastUpdatedBy
`

const PACKAGE_SETTINGS_RESOLUTION_GQL = `
    status
    source
    sourceObject
    settings { ${PACKAGE_SETTINGS_FIELDS_GQL} }
    conflictingPerspectives { uuid name type }
`

const PACKAGE_KIND_GQL = `kind parts { part requirement defaultSelection generatorPresent }`

const PACKAGE_KINDS = gql`
query PackageKinds { packageKinds { ${PACKAGE_KIND_GQL} } }`

const PACKAGE_SETTINGS_OF_SCOPE = gql`
query PackageSettingsOfScope($org: ID!, $scope: SettingsScope!, $object: ID) {
    packageSettingsOfScope(org: $org, scope: $scope, object: $object) { ${PACKAGE_SETTINGS_FIELDS_GQL} }
}`

const PACKAGE_SETTINGS_OF_ORG = gql`
query PackageSettingsOfOrg($org: ID!) {
    packageSettingsOfOrg(org: $org) { uuid scope object kind mode followedPerspective sbomProfile description revision }
}`

const PACKAGE_SETTINGS_EDITOR_VIEW = gql`
query PackageSettingsEditorView($org: ID!, $scope: SettingsScope!, $object: ID, $kind: PackageKind!) {
    packageSettingsEditorView(org: $org, scope: $scope, object: $object, kind: $kind) {
        stored { ${PACKAGE_SETTINGS_FIELDS_GQL} }
        effective { ${PACKAGE_SETTINGS_RESOLUTION_GQL} }
        parent { ${PACKAGE_SETTINGS_RESOLUTION_GQL} }
        kind { ${PACKAGE_KIND_GQL} }
    }
}`

const SAVE_PACKAGE_SETTINGS = gql`
mutation SavePackageSettings($org: ID!, $scope: SettingsScope!, $object: ID, $kind: PackageKind!, $settings: PackageSettingsInput!) {
    savePackageSettings(org: $org, scope: $scope, object: $object, kind: $kind, settings: $settings) { ${PACKAGE_SETTINGS_FIELDS_GQL} }
}`

const DELETE_PACKAGE_SETTINGS = gql`
mutation DeletePackageSettings($org: ID!, $scope: SettingsScope!, $object: ID, $kind: PackageKind!) {
    deletePackageSettings(org: $org, scope: $scope, object: $object, kind: $kind)
}`

export default {
    SbomProfilesGql: SBOM_PROFILES,
    SbomProfilesOfOrgGql: SBOM_PROFILES_OF_ORG,
    SbomProfileCatalogGql: SBOM_PROFILE_CATALOG,
    SbomProfileEditorViewGql: SBOM_PROFILE_EDITOR_VIEW,
    SaveSbomProfileGql: SAVE_SBOM_PROFILE,
    DeleteSbomProfileGql: DELETE_SBOM_PROFILE,
    PackageKindsGql: PACKAGE_KINDS,
    PackageSettingsOfScopeGql: PACKAGE_SETTINGS_OF_SCOPE,
    PackageSettingsOfOrgGql: PACKAGE_SETTINGS_OF_ORG,
    PackageSettingsEditorViewGql: PACKAGE_SETTINGS_EDITOR_VIEW,
    SavePackageSettingsGql: SAVE_PACKAGE_SETTINGS,
    DeletePackageSettingsGql: DELETE_PACKAGE_SETTINGS,
}
