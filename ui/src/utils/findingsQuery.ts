// Query documents for the findings modal: a release's findings (with its
// parent releases' artifacts, for the Dependency-Track links) and one
// artifact's findings. Kept apart from the service (which pulls in the Apollo
// client) so the schema-drift spec can import them without a browser
// environment, as vulnerabilityRecordQuery.ts is.

import gql from 'graphql-tag'
import { VULN_SCORE_FRAGMENT } from './vulnerabilityRecordQuery'

// Each finding's scores and fix version, read from the org's vulnerability
// records. A CE backend that has not mirrored a field yet rejects the whole
// document, so each load comes in tiers, richest first: LATEST (release only:
// MATCHED plus each component's latest version), MATCHED (release only: FULL
// plus each finding's SBOM component), FULL (scores and fix versions), SCORED
// (scores only) and CORE (none of them), which keeps the modal working there
// without those columns. See loadFindings.
const FINDING_SCORE_FIELDS = `
            scores { ...VulnScoreFields }
            topScore { ...VulnScoreFields }
            epss { ...VulnScoreFields }`

export const FINDING_FIX_FIELDS = `
            fixedIn { version verdict versionEndIncluding sources identities }`

// Per component, the fix versions its findings' advisories name and which
// findings each one fixes: the group view's Bump to. Metrics-level, so it
// rides with the fix versions in the FULL document.
const FIX_TARGET_FIELDS = `
        fixTargets { purl major targets { version sameMajor fixes } }`

// Which component of the release's SBOM each finding is on, matched by the
// server (the group view's grouping). Release findings only: an artifact's
// findings belong to no one release. It costs one read of the release's SBOM,
// so the MATCHED document is FULL plus this and nothing else.
const SBOM_MATCH_SUBFIELDS = 'sbomComponentUuid canonicalPurl missReason'

const FINDING_SBOM_FIELDS = `
            sbomMatch { ${SBOM_MATCH_SUBFIELDS} }`

// The same plus the latest version of each finding's SBOM component and
// whether it fixes the finding (rearm-saas PR G): the group view's Latest.
// A tier of its own, LATEST, on top of MATCHED, so a backend with the match
// but without the latest version still groups on the server.
const FINDING_SBOM_LATEST_FIELDS = `
            sbomMatch { ${SBOM_MATCH_SUBFIELDS} latestVersion latestVersionChecked }
            latestFix`

const findingsMetrics = (optionalFields: string, metricsFields = '') => `
    metrics {${metricsFields}
        vulnerabilityDetails { 
            purl
            vulnId
            severity
            analysisState
            analysisDate
            attributedAt
            aliases {
                type
                aliasId
            }
            sources {
                artifact
                release
                variant
                releaseDetails {
                    version
                    componentDetails {
                        name
                    }
                }
                artifactDetails {
                    type
                }
            }
            severities {
                source
                severity
            }${optionalFields}
        }
        violationDetails {
            purl
            type
            license
            violationDetails
            analysisState
            analysisDate
            attributedAt
            sources {
                artifact
                release
                variant
                releaseDetails {
                    version
                    componentDetails {
                        name
                    }
                }
                artifactDetails {
                    type
                }
            }
        }
        weaknessDetails { 
            cweId 
            ruleId 
            location 
            fingerprint 
            severity
            analysisState
            analysisDate
            attributedAt
            sources {
                artifact
                release
                variant
                releaseDetails {
                    version
                    componentDetails {
                        name
                    }
                }
                artifactDetails {
                    type
                }
            }
        }
    }
`

// Scores and fix versions are selected on the release itself only: the modal
// shows the release's own findings, and the parents are read for their
// artifacts.
const releaseForFindings = (optionalFields: string, metricsFields = '') => `
    uuid
    version
    org
    artifactDetails {
        uuid
        metrics { dependencyTrackFullUri }
    }
    sourceCodeEntryDetails {
        artifactDetails {
            uuid
            metrics { dependencyTrackFullUri }
        }
    }
    inboundDeliverableDetails {
        artifactDetails {
            uuid
            metrics { dependencyTrackFullUri }
        }
    }
    variantDetails {
        outboundDeliverableDetails {
            artifactDetails {
                uuid
                metrics { dependencyTrackFullUri }
            }
        }
    }
${findingsMetrics(optionalFields, metricsFields)}
`

const singleReleaseForVulnNoParent = releaseForFindings('')

const singleReleaseDataForVulnParentLast = `
    ${singleReleaseForVulnNoParent}
    parentReleases {
        release
        releaseDetails {
            ${singleReleaseForVulnNoParent}
        }
    }
`

const singleReleaseForVulnParentRecursion = `
    ${singleReleaseForVulnNoParent}
    parentReleases {
        release
        releaseDetails {
            ${singleReleaseDataForVulnParentLast}        
        }
    }
`

const releaseForVulnData = (optionalFields: string, metricsFields = '') => `
    ${releaseForFindings(optionalFields, metricsFields)}
    parentReleases {
        release
        releaseDetails {
            ${singleReleaseForVulnParentRecursion}        
        }
    }
`

export const RELEASE_FINDINGS_QUERY_LATEST = gql`
    query getReleaseDetails($releaseUuid: ID!, $orgUuid: ID) {
        release(releaseUuid: $releaseUuid, orgUuid: $orgUuid) {
            ${releaseForVulnData(FINDING_SCORE_FIELDS + FINDING_FIX_FIELDS + FINDING_SBOM_LATEST_FIELDS, FIX_TARGET_FIELDS)}
        }
    }
    ${VULN_SCORE_FRAGMENT}
`

export const RELEASE_FINDINGS_QUERY_MATCHED = gql`
    query getReleaseDetails($releaseUuid: ID!, $orgUuid: ID) {
        release(releaseUuid: $releaseUuid, orgUuid: $orgUuid) {
            ${releaseForVulnData(FINDING_SCORE_FIELDS + FINDING_FIX_FIELDS + FINDING_SBOM_FIELDS, FIX_TARGET_FIELDS)}
        }
    }
    ${VULN_SCORE_FRAGMENT}
`

export const RELEASE_FINDINGS_QUERY = gql`
    query getReleaseDetails($releaseUuid: ID!, $orgUuid: ID) {
        release(releaseUuid: $releaseUuid, orgUuid: $orgUuid) {
            ${releaseForVulnData(FINDING_SCORE_FIELDS + FINDING_FIX_FIELDS, FIX_TARGET_FIELDS)}
        }
    }
    ${VULN_SCORE_FRAGMENT}
`

export const RELEASE_FINDINGS_QUERY_SCORED = gql`
    query getReleaseDetails($releaseUuid: ID!, $orgUuid: ID) {
        release(releaseUuid: $releaseUuid, orgUuid: $orgUuid) {
            ${releaseForVulnData(FINDING_SCORE_FIELDS)}
        }
    }
    ${VULN_SCORE_FRAGMENT}
`

export const RELEASE_FINDINGS_QUERY_CORE = gql`
    query getReleaseDetails($releaseUuid: ID!, $orgUuid: ID) {
        release(releaseUuid: $releaseUuid, orgUuid: $orgUuid) {
            ${releaseForVulnData('')}
        }
    }
`

const artifactFindings = (optionalFields: string, metricsFields = '') => `
    uuid
    displayIdentifier
    ${findingsMetrics(optionalFields, metricsFields)}
`

export const ARTIFACT_FINDINGS_QUERY = gql`
    query getArtifactDetails($artifactUuid: ID!) {
        artifact(artifactUuid: $artifactUuid) {
            ${artifactFindings(FINDING_SCORE_FIELDS + FINDING_FIX_FIELDS, FIX_TARGET_FIELDS)}
        }
    }
    ${VULN_SCORE_FRAGMENT}
`

export const ARTIFACT_FINDINGS_QUERY_SCORED = gql`
    query getArtifactDetails($artifactUuid: ID!) {
        artifact(artifactUuid: $artifactUuid) {
            ${artifactFindings(FINDING_SCORE_FIELDS)}
        }
    }
    ${VULN_SCORE_FRAGMENT}
`

export const ARTIFACT_FINDINGS_QUERY_CORE = gql`
    query getArtifactDetails($artifactUuid: ID!) {
        artifact(artifactUuid: $artifactUuid) {
            ${artifactFindings('')}
        }
    }
`
