import gql from 'graphql-tag'

// addArtifactManual for an upload that runs an import (VEX, SAFETY_CONTEXT): the resulting artifacts with
// their import summary, so the upload dialog can say what was applied, what was staged and why.
export const ADD_ARTIFACT_WITH_IMPORT_SUMMARY = gql`
    mutation addArtifactManualWithImportSummary($artifactInput: CreateArtifactInput) {
        addArtifactManual(artifactInput: $artifactInput) {
            uuid
            artifactDetails {
                uuid displayIdentifier type
                importSummary {
                    statementsTotal proposalsCreated proposalsAutoAccepted proposalsAutoRejected attestationsDeferred
                    statementsUnchanged statementsUnmatched statementsErrored errorMessages
                    demotions { reason count }
                    overrideOutcome
                }
            }
        }
    }
`

// The same, uploading a new version of an existing artifact.
export const UPDATE_ARTIFACT_WITH_IMPORT_SUMMARY = gql`
    mutation addArtifactManualNewVersionWithImportSummary($artifactInput: CreateArtifactInput, $artifactUuid: ID!) {
        addArtifactManual(artifactInput: $artifactInput, artifactUuid: $artifactUuid) {
            uuid
            artifactDetails {
                uuid displayIdentifier type
                importSummary {
                    statementsTotal proposalsCreated proposalsAutoAccepted proposalsAutoRejected attestationsDeferred
                    statementsUnchanged statementsUnmatched statementsErrored errorMessages
                    demotions { reason count }
                    overrideOutcome
                }
            }
        }
    }
`
