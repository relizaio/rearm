// The organizations query, in two shapes.
//
// Its own module rather than inline in the store, for the same reason every other query
// module exists here: a document that has to be validated against a schema must be importable
// without dragging the store -- and therefore keycloak, and therefore `window` -- in with it.

import gql from 'graphql-tag'

/**
 * The organizations query, in two shapes.
 *
 * CORE is the document every backend can answer. FULL adds only
 * Settings.supportInjection, which CE gains at the deferred sync. Split rather than
 * conditional because loadWithSchemaDriftFallback takes two documents, and because a single
 * document assembled by string concatenation is the thing scripts/validate-graphql cannot
 * check.
 */
const ORGANIZATIONS_CORE_SETTINGS = `
                                    justificationMandatory
                                    branchSuffixMode
                                    vexComplianceFramework
                                    sidPurlMode
                                    sidAuthoritySegments
                                    fdaAssessmentNarrative
                                    fdaPatchesMayCeaseStatement
                                    fdaRiskTransferProcessRef
                                    fdaRiskIncreasesNotice`

const organizationsDocument = (settings: string) => `
                        query organizations {
                            organizations {
                                uuid
                                name
                                type
                                approvalRoles {
                                    id
                                    displayView
                                }
                                terminology {
                                    featureSetLabel
                                }
                                ignoreViolation {
                                    licenseViolationRegexIgnore
                                    securityViolationRegexIgnore
                                    operationalViolationRegexIgnore
                                }
                                settings {${settings}
                                }
                            }
                        }`

export const ORGANIZATIONS_CORE = gql`${organizationsDocument(ORGANIZATIONS_CORE_SETTINGS)}`
export const ORGANIZATIONS_FULL = gql`${organizationsDocument(
    ORGANIZATIONS_CORE_SETTINGS + '\n                                    supportInjection')}`

