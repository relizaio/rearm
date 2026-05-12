import gql from 'graphql-tag'

export const GET_VEX_PROPOSALS = gql`
    query GetVexStatementProposals($org: ID!, $status: ProposalStatus) {
        getVexStatementProposals(org: $org, status: $status) {
            uuid org sourceArtifact sourceFormat sourceStatementJson signatureStatus
            scope scopeUuid location rawLocation findingId findingType
            analysisState analysisJustification details severity
            responses recommendation workaround
            status statusReason targetVulnAnalysis mitigationAttestation
            actedAt actedBy
            translationNotes
            issuerClass userImportMode userIssuerClassOverride demotionReason
        }
    }
`

export const GET_VEX_PROPOSALS_BY_RELEASE = gql`
    query GetVexStatementProposalsByRelease($org: ID!, $release: ID!) {
        getVexStatementProposalsByRelease(org: $org, release: $release) {
            uuid org sourceArtifact sourceFormat
            scope scopeUuid location findingId findingType
            analysisState analysisJustification
            status actedAt
            issuerClass demotionReason
        }
    }
`

export const GET_VEX_PROPOSAL = gql`
    query GetVexStatementProposal($uuid: ID!) {
        getVexStatementProposal(uuid: $uuid) {
            uuid org sourceArtifact sourceFormat sourceStatementJson signatureStatus
            scope scopeUuid location rawLocation findingId findingType
            analysisState analysisJustification details severity
            responses recommendation workaround
            status statusReason targetVulnAnalysis mitigationAttestation
            actedAt actedBy
            translationNotes
            issuerClass userImportMode userIssuerClassOverride demotionReason
        }
    }
`

export const UPDATE_VEX_PROPOSAL = gql`
    mutation UpdateVexStatementProposal($uuid: ID!, $updates: VexProposalUpdateInput!) {
        updateVexStatementProposal(uuid: $uuid, updates: $updates) {
            uuid status analysisState analysisJustification details severity
            responses recommendation workaround
        }
    }
`

export const GET_VULN_ANALYSIS_BY_LOCATION_AND_FINDING = gql`
    query GetVulnAnalysisByLocationAndFinding($org: ID!, $location: String!, $findingId: String!, $findingType: FindingType!) {
        getVulnAnalysisByLocationAndFinding(org: $org, location: $location, findingId: $findingId, findingType: $findingType) {
            uuid scope scopeUuid analysisState analysisJustification
        }
    }
`

export const ACCEPT_VEX_PROPOSAL = gql`
    mutation AcceptVexStatementProposal($uuid: ID!, $comment: String) {
        acceptVexStatementProposal(uuid: $uuid, comment: $comment) { uuid status mitigationAttestation targetVulnAnalysis statusReason }
    }
`

export const REJECT_VEX_PROPOSAL = gql`
    mutation RejectVexStatementProposal($uuid: ID!, $reason: String) {
        rejectVexStatementProposal(uuid: $uuid, reason: $reason) { uuid status statusReason }
    }
`

export const GET_MITIGATION_ATTESTATIONS = gql`
    query GetMitigationAttestations($org: ID!, $status: AttestationStatus) {
        getMitigationAttestations(org: $org, status: $status) {
            uuid org proposal claimType claimText scope scopeUuid
            assignedTo assignedAt deadline status evidence
            attestedAt attestedBy statusReason
        }
    }
`

export const GET_MITIGATION_ATTESTATION = gql`
    query GetMitigationAttestation($uuid: ID!) {
        getMitigationAttestation(uuid: $uuid) {
            uuid org proposal claimType claimText scope scopeUuid
            assignedTo assignedAt deadline status evidence
            attestedAt attestedBy statusReason
        }
    }
`

export const ATTEST_MITIGATION = gql`
    mutation AttestMitigation($uuid: ID!, $evidence: String!) {
        attestMitigation(uuid: $uuid, evidence: $evidence) {
            uuid status evidence attestedAt
        }
    }
`

export const WAIVE_MITIGATION = gql`
    mutation WaiveMitigation($uuid: ID!, $reason: String!) {
        waiveMitigation(uuid: $uuid, reason: $reason) {
            uuid status statusReason
        }
    }
`
