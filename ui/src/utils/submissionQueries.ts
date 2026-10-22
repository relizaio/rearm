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
    lastActKind
    withdrawnDate
    withdrawnBy
    withdrawReason
    settings { ${SUBMISSION_REF_GQL} }
`

const SUBMISSIONS_OF_COMPONENT = gql`
query SubmissionsOfComponent($component: ID!, $kind: PackageKind, $state: SubmissionState) {
    submissionsOfComponent(component: $component, kind: $kind, state: $state) { ${SUBMISSION_HEAD_GQL} }
}`

const SUBMISSIONS_OF_RELEASE = gql`
query SubmissionsOfRelease($release: ID!) {
    submissionsOfRelease(release: $release) { ${SUBMISSION_HEAD_GQL} versions { ${SUBMISSION_VERSION_GQL} } }
}`

const SUBMISSION_PREVIEW = gql`
query SubmissionPreview($release: ID!, $kind: PackageKind!) {
    submissionPreview(release: $release, kind: $kind) {
        canSubmit
        refusal
        message
        nextVersion
        head { uuid state latestVersion }
        settings { ${SUBMISSION_REF_GQL} }
        sbomProfile { name ${SUBMISSION_REF_GQL} }
        plan { part requirement selection generatorPresent }
    }
}`

const SUBMISSION_ACT_RESULT_GQL = `outcome submission { ${SUBMISSION_HEAD_GQL} } version { version createdDate }`

const CREATE_SUBMISSION = gql`
mutation CreateSubmission($release: ID!, $kind: PackageKind!, $reason: String) {
    createSubmission(release: $release, kind: $kind, reason: $reason) { ${SUBMISSION_ACT_RESULT_GQL} }
}`

const RESUBMIT_SUBMISSION = gql`
mutation ResubmitSubmission($submission: ID!, $reason: String!, $expectedLatestVersion: Int) {
    resubmitSubmission(submission: $submission, reason: $reason, expectedLatestVersion: $expectedLatestVersion) { ${SUBMISSION_ACT_RESULT_GQL} }
}`

const WITHDRAW_SUBMISSION = gql`
mutation WithdrawSubmission($submission: ID!, $reason: String!) {
    withdrawSubmission(submission: $submission, reason: $reason) { ${SUBMISSION_ACT_RESULT_GQL} }
}`

// Long-standing Release fields only: valid on CE and Pro, sent only behind the gate.
const SUBMISSION_RELEASES_OF_BRANCH = gql`
query SubmissionReleasesOfBranch($branch: ID!, $numRecords: Int) {
    releases(branchFilter: $branch, numRecords: $numRecords) { uuid version lifecycle createdDate }
}`

export default {
    SubmissionsOfComponentGql: SUBMISSIONS_OF_COMPONENT,
    SubmissionsOfReleaseGql: SUBMISSIONS_OF_RELEASE,
    SubmissionPreviewGql: SUBMISSION_PREVIEW,
    CreateSubmissionGql: CREATE_SUBMISSION,
    ResubmitSubmissionGql: RESUBMIT_SUBMISSION,
    WithdrawSubmissionGql: WITHDRAW_SUBMISSION,
    SubmissionReleasesOfBranchGql: SUBMISSION_RELEASES_OF_BRANCH,
}
