import gql from 'graphql-tag'

// Regulatory submissions (task TEA-30; the backend of TEA-35). Pro-only: the product page's
// Submissions tab and the release page's section send these only behind submissionsAvailable, and
// none of them is part of a shared release fragment (Release.submissions would break every release
// read on CE, so the release page reads submissionsOfRelease instead). teaProfileSchemaDrift.spec.ts
// validates them against both schemas.

const SUBMISSION_REF_GQL = `row revision source sourceObject`

const SUBMISSION_PART_GQL = `part requirement outcome artifact artifactVersion fileName mediaType size sha256 skipReason detail`

const SUBMISSION_VERSION_GQL = `
    submission
    version
    org
    release
    kind
    createdDate
    createdBy
    createdByName
    reason
    settings { ${SUBMISSION_REF_GQL} }
    sbomProfile { name ${SUBMISSION_REF_GQL} }
    parts { ${SUBMISSION_PART_GQL} }
    manifestArtifact
    manifestArtifactVersion
    manifestSha256
    manifest
    signature
`

const SUBMISSION_HEAD_GQL = `
    uuid
    org
    release
    releaseVersion
    component
    componentType
    kind
    state
    latestVersion
    createdDate
    createdBy
    lastActDate
    lastActBy
    lastAct { kind actedAt actorName }
    withdrawnDate
    withdrawnBy
    withdrawReason
    legacyState
    currentSentVersion
    versionStates { version state byVersion }
    acts { uuid kind cause version actedAt actor actorName reasonText }
    settings { ${SUBMISSION_REF_GQL} }
`

// The actors' names (task FDAW-4, B14): resolved by the server per head, so only the release page's
// read, which shows them, selects them; the product tab's list and the act results do not.
const SUBMISSION_HEAD_NAMES_GQL = `createdByName lastActByName withdrawnByName`

const SUBMISSIONS_OF_COMPONENT = gql`
query SubmissionsOfComponent($component: ID!, $kind: PackageKind, $state: SubmissionState) {
    submissionsOfComponent(component: $component, kind: $kind, state: $state) { ${SUBMISSION_HEAD_GQL} latest { version confirmations { confirmedAt } } }
}`

// Each version's confirmations: the record lines Finalize wrote, in order (FDAW-32 3.6).
const SUBMISSIONS_OF_RELEASE = gql`
query SubmissionsOfRelease($release: ID!) {
    submissionsOfRelease(release: $release) { ${SUBMISSION_HEAD_GQL} ${SUBMISSION_HEAD_NAMES_GQL} versions { ${SUBMISSION_VERSION_GQL} confirmations { actor actorName capacity confirmedAt } } }
}`

// The draft of a submission's next version (FDAW-32, the package page): read by the release page only.
const DRAFT_JOB_GQL = `state requestedAt requestedBy { actor actorName } startedAt finishedAt attempt failureRefusal failureText`

const DRAFT_DIFF_GQL = `baseline { submission version } added { identity name version } addedCount removed { identity name version } removedCount
    changed { identity name fromVersion toVersion } changedCount supportChanged { key property } supportChangedCount findingsCompared
    findingsAdded { vulnerabilityId affectedRef } findingsAddedCount findingsRemoved { vulnerabilityId affectedRef } findingsRemovedCount`

const DRAFT_GQL = `uuid org release component kind submission state revision plannedSubmission plannedVersion startedAt
    startedBy { actor actorName } reasonCategory reasonText replaces { submission version } job { ${DRAFT_JOB_GQL} }
    build { builtAt requestedBy { actor actorName } releaseSbom { version } parts { part artifact sha256 sizeBytes documentVersion }
        diff { ${DRAFT_DIFF_GQL} } }
    confirmation { actor actorName capacity confirmedAt } changesSinceBuild { count fingerprintEqual movedLines }`

const RELEASE_DRAFTS = gql`
query ReleaseDrafts($releaseID: ID!, $orgID: ID) {
    release(releaseUuid: $releaseID, orgUuid: $orgID) { uuid drafts { ${DRAFT_GQL} } }
}`

const DRAFT = gql`
query Draft($uuid: ID!) {
    draft(uuid: $uuid) { ${DRAFT_GQL} }
}`

// The fast poll of a job in flight: no changesSinceBuild, which the server recomputes on every read.
const DRAFT_JOB = gql`
query DraftJob($uuid: ID!) {
    draft(uuid: $uuid) { uuid state revision job { ${DRAFT_JOB_GQL} } }
}`

// What an act answers: the draft, the head and the version as the act left them. Never `outcome`.
const DRAFT_ACT_RESULT_GQL = `draft { uuid state revision } submission { uuid state latestVersion } version { version }`

const START_DRAFT = gql`
mutation StartDraft($release: ID!, $kind: PackageKind!, $reasonCategory: NewVersionReason!, $reasonText: String, $replacesSubmission: ID, $replacesVersion: Int) {
    startDraft(release: $release, kind: $kind, reasonCategory: $reasonCategory, reasonText: $reasonText,
        replacesSubmission: $replacesSubmission, replacesVersion: $replacesVersion) { ${DRAFT_ACT_RESULT_GQL} }
}`

const REBUILD_DRAFT = gql`
mutation RebuildDraft($draft: ID!) {
    rebuildDraft(draft: $draft) { ${DRAFT_ACT_RESULT_GQL} }
}`

const DISCARD_DRAFT = gql`
mutation DiscardDraft($draft: ID!, $reason: String!) {
    discardDraft(draft: $draft, reason: $reason) { ${DRAFT_ACT_RESULT_GQL} }
}`

const CONFIRM_DRAFT = gql`
mutation ConfirmDraft($draft: ID!, $expectedRevision: Int!) {
    confirmDraft(draft: $draft, expectedRevision: $expectedRevision) { ${DRAFT_ACT_RESULT_GQL} }
}`

const RETURN_DRAFT_TO_EDITING = gql`
mutation ReturnDraftToEditing($draft: ID!, $reason: String!) {
    returnDraftToEditing(draft: $draft, reason: $reason) { ${DRAFT_ACT_RESULT_GQL} }
}`

const FINALIZE_DRAFT = gql`
mutation FinalizeDraft($draft: ID!, $expectedRevision: Int!) {
    finalizeDraft(draft: $draft, expectedRevision: $expectedRevision) { ${DRAFT_ACT_RESULT_GQL} }
}`

const CANCEL_SUBMISSION = gql`
mutation CancelSubmission($submission: ID!, $reason: String!) {
    cancelSubmission(submission: $submission, reason: $reason) { ${DRAFT_ACT_RESULT_GQL} }
}`

// The organization's second-confirmer setting (FDAW-32 3.9): its own read, filtered by uuid in the client, and
// a one-field patch. Sent with no-cache: Settings has no key field, so a partial selection in the cache would
// replace the cached organization's settings.
const ORG_SECOND_CONFIRMER = gql`
query OrgSecondConfirmer {
    organizations { uuid settings { submissionSecondConfirmer } }
}`

const UPDATE_SECOND_CONFIRMER = gql`
mutation UpdateSecondConfirmer($orgUuid: ID!, $on: Boolean!) {
    updateOrganizationSettings(orgUuid: $orgUuid, settings: { submissionSecondConfirmer: $on }) { uuid settings { submissionSecondConfirmer } }
}`

// Long-standing Release fields only: valid on CE and Pro, sent only behind the gate.
const SUBMISSION_RELEASES_OF_BRANCH = gql`
query SubmissionReleasesOfBranch($branch: ID!, $numRecords: Int) {
    releases(branchFilter: $branch, numRecords: $numRecords) { uuid version lifecycle createdDate }
}`

// The kinds the release page offers (switched on for the component, plus any the release holds a
// submission of): its own read, never the shared release fragments, for the reason above.
const RELEASE_REGULATORY_PACKAGE_KINDS = gql`
query ReleaseRegulatoryPackageKinds($releaseID: ID!, $orgID: ID) {
    release(releaseUuid: $releaseID, orgUuid: $orgID) { uuid regulatoryPackageKinds }
}`

// The kinds switched on for a product or component (task FDAW-4, B18): the product tab links a release
// to its draft only for these. Its own read, never a shared component fragment: CE must keep reading components.
const COMPONENT_REGULATORY_PACKAGE_KINDS = gql`
query ComponentRegulatoryPackageKinds($componentUuid: ID!) {
    component(componentUuid: $componentUuid) { uuid regulatoryPackageKinds }
}`

export default {
    SubmissionsOfComponentGql: SUBMISSIONS_OF_COMPONENT,
    SubmissionsOfReleaseGql: SUBMISSIONS_OF_RELEASE,
    ReleaseDraftsGql: RELEASE_DRAFTS,
    DraftGql: DRAFT,
    DraftJobGql: DRAFT_JOB,
    StartDraftGql: START_DRAFT,
    RebuildDraftGql: REBUILD_DRAFT,
    DiscardDraftGql: DISCARD_DRAFT,
    ConfirmDraftGql: CONFIRM_DRAFT,
    ReturnDraftToEditingGql: RETURN_DRAFT_TO_EDITING,
    FinalizeDraftGql: FINALIZE_DRAFT,
    CancelSubmissionGql: CANCEL_SUBMISSION,
    OrgSecondConfirmerGql: ORG_SECOND_CONFIRMER,
    UpdateSecondConfirmerGql: UPDATE_SECOND_CONFIRMER,
    SubmissionReleasesOfBranchGql: SUBMISSION_RELEASES_OF_BRANCH,
    ReleaseRegulatoryPackageKindsGql: RELEASE_REGULATORY_PACKAGE_KINDS,
    ComponentRegulatoryPackageKindsGql: COMPONENT_REGULATORY_PACKAGE_KINDS,
}
