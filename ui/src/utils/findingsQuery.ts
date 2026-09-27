// Query documents for the findings modal: a release's findings (with its
// parent releases' artifacts, for the Dependency-Track links) and one
// artifact's findings. Kept apart from the service (which pulls in the Apollo
// client) so the schema-drift spec can import them without a browser
// environment, as vulnerabilityRecordQuery.ts is.

import gql from 'graphql-tag'
import { VULN_SCORE_FRAGMENT } from './vulnerabilityRecordQuery'

// Each finding's scores, read from the org's vulnerability records. Only the
// FULL documents select them: a CE backend that has not mirrored the fields
// yet rejects the whole document, and the CORE documents (everything else)
// keep the modal working there, without the score columns. See
// loadWithSchemaDriftFallback.
const FINDING_SCORE_FIELDS = `
            scores { ...VulnScoreFields }
            topScore { ...VulnScoreFields }
            epss { ...VulnScoreFields }`

const findingsMetrics = (scoreFields: string) => `
    metrics {
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
            }${scoreFields}
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

// Scores are selected on the release itself only: the modal shows the
// release's own findings, and the parents are read for their artifacts.
const releaseForFindings = (scoreFields: string) => `
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
${findingsMetrics(scoreFields)}
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

const releaseForVulnData = (scoreFields: string) => `
    ${releaseForFindings(scoreFields)}
    parentReleases {
        release
        releaseDetails {
            ${singleReleaseForVulnParentRecursion}        
        }
    }
`

export const RELEASE_FINDINGS_QUERY = gql`
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

const artifactFindings = (scoreFields: string) => `
    uuid
    displayIdentifier
    ${findingsMetrics(scoreFields)}
`

export const ARTIFACT_FINDINGS_QUERY = gql`
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
