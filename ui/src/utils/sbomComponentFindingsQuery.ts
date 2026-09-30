// The findings of one SBOM component of a release, for the dependency-graph
// page's findings badge. Kept apart from the page (which pulls in the Apollo
// client) so the schema-drift spec can import it without a browser.
//
// Two documents, richest first, for loadRichestServed: FULL selects the
// component's findings, which a CE backend that has not mirrored
// ReleaseSbomComponent.findings rejects; CORE selects only what every backend
// serves, so the page then shows no badge rather than an error. Both select the
// release's org, which the finding details panel reads the records of.

import gql from 'graphql-tag'
import { VULN_SCORE_FRAGMENT } from './vulnerabilityRecordQuery'
import { FINDING_FIX_FIELDS } from './findingsQuery'

export const SBOM_COMPONENT_FINDINGS_QUERY = gql`
    query getReleaseSbomComponentFindings($releaseUuid: ID!, $sbomComponentUuid: ID!) {
        getReleaseSbomComponentGraph(releaseUuid: $releaseUuid, sbomComponentUuid: $sbomComponentUuid) {
            sbomComponentUuid
            findings {
                vulnId
                purl
                severity
                analysisState
                knownExploited
                topScore { ...VulnScoreFields }${FINDING_FIX_FIELDS}
            }
        }
        release(releaseUuid: $releaseUuid) {
            uuid
            org
        }
    }
    ${VULN_SCORE_FRAGMENT}
`

export const SBOM_COMPONENT_FINDINGS_QUERY_CORE = gql`
    query getReleaseSbomComponentFindings($releaseUuid: ID!, $sbomComponentUuid: ID!) {
        getReleaseSbomComponentGraph(releaseUuid: $releaseUuid, sbomComponentUuid: $sbomComponentUuid) {
            sbomComponentUuid
        }
        release(releaseUuid: $releaseUuid) {
            uuid
            org
        }
    }
`
