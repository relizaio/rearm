import gql from 'graphql-tag'

// SAFETY_CONTEXT import (CycloneDX 2.0 safety integrity levels). Every level is the issuer's
// declaration; verificationStatus is always UNVERIFIED.

const ENTRY_FIELDS = `
    uuid kind org proposalStatus recordStatus
    scope scopeUuid componentPurl sbomComponentUuid schemeKey blueprintRefId assetRefId
    levelRaw levelNormalized levelRank levelNote levelChange componentMostConservative
    verificationStatus issuerClassDerived issuerClassEffective originBinding issuerOverride
    overrideHonoured elevatedByOverride overrideActorName importMode gateRule notes matchStatus
    statusReason conflictFlag sourceArtifact sourceDeleted releaseUuid specCommit docSerialNumber
    docVersion uploadedBy actedBy actedAt acceptComment rejectReason acceptedVia acceptedByName
    acceptedAt acceptedByUploader supersededBy
`

export const GET_SAFETY_PROPOSALS = gql`
    query GetSafetyContextProposals($org: ID!, $status: ProposalStatus, $limit: Int, $offset: Int) {
        getSafetyContextProposals(org: $org, status: $status, limit: $limit, offset: $offset) { ${ENTRY_FIELDS} }
    }
`

export const GET_SAFETY_PROPOSALS_BY_RELEASE = gql`
    query GetSafetyContextProposalsByRelease($org: ID!, $release: ID!, $limit: Int, $offset: Int) {
        getSafetyContextProposalsByRelease(org: $org, release: $release, limit: $limit, offset: $offset) { ${ENTRY_FIELDS} }
    }
`

export const GET_SAFETY_RECORDS_BY_RELEASE = gql`
    query GetSafetyContextRecordsByRelease($org: ID!, $release: ID!, $includeSuperseded: Boolean, $limit: Int, $offset: Int) {
        getSafetyContextRecordsByRelease(org: $org, release: $release, includeSuperseded: $includeSuperseded,
            limit: $limit, offset: $offset) { ${ENTRY_FIELDS} }
    }
`

export const GET_SAFETY_SOURCE_ASSERTION = gql`
    query GetSafetyContextSourceAssertion($uuid: ID!) {
        getSafetyContextSourceAssertion(uuid: $uuid)
    }
`

export const GET_RELEASE_SBOM_COMPONENT_CHOICES = gql`
    query GetReleaseSbomComponentChoices($releaseUuid: ID!) {
        getReleaseSbomComponents(releaseUuid: $releaseUuid) {
            sbomComponentUuid
            component { canonicalPurl }
        }
    }
`

export const ACCEPT_SAFETY_PROPOSAL = gql`
    mutation AcceptSafetyContextProposal($uuid: ID!, $component: ID, $comment: String) {
        acceptSafetyContextProposal(uuid: $uuid, component: $component, comment: $comment) { uuid proposalStatus }
    }
`

export const REJECT_SAFETY_PROPOSAL = gql`
    mutation RejectSafetyContextProposal($uuid: ID!, $reason: String) {
        rejectSafetyContextProposal(uuid: $uuid, reason: $reason) { uuid proposalStatus }
    }
`

/** Human labels for the trust-gate rule that decided a level. */
export const GATE_RULE_LABELS: Record<string, string> = {
    DOWNGRADE: 'Lower than the current level',
    UNCOMPARABLE: 'Not comparable with the current level',
    THIRD_PARTY_STAGE: 'Third-party issuer',
    SELF_AUTO: 'Own organization (auto-accepted)',
    VENDOR_AUTO: 'Vendor (auto-accepted)',
    VENDOR_CUSTOM: 'Vendor, custom scheme',
    VENDOR_UNMAPPED: 'Vendor, level not recognised',
    VENDOR_DECOMPOSITION: 'Vendor, ASIL decomposition',
    VENDOR_BOTTOM: 'Vendor, lowest level of the scheme',
    INTRA_ASSET_CONFLICT: 'Asset declares conflicting levels',
    UNMATCHED: 'Component not in the release SBOM',
    AMBIGUOUS: 'Component matches several SBOM entries',
    SBOM_PENDING: 'SBOM still being processed',
    WIDE_SCOPE: 'Organization or branch scope needs review'
}

/** Shown wherever accepted levels are displayed. */
export const DECLARED_NOT_VERIFIED = 'Declared by the issuer; not verified by ReARM.'
