// The organization's SBOM signing keys (SCORE-35, design 3.7): the list and the three key
// operations of rearm-saas SCORE-32.
//
// Its own module, importable without the store (and therefore without keycloak and `window`),
// so the schema-drift spec and scripts/validate-graphql read these documents as they are sent.
// The list needs org READ, the mutations org ADMIN; Generate rotates when a CURRENT key exists,
// there is no separate rotate mutation.

import gql from 'graphql-tag'

export const SBOM_SIGNING_KEYS = gql`
    query sbomSigningKeys($orgUuid: ID!) {
        sbomSigningKeys(orgUuid: $orgUuid) {
            uuid keyId algorithm status source publicKeyJwk publicKeyPem createdDate retiredAt revokedAt
        }
    }`

export const GENERATE_SBOM_SIGNING_KEY = gql`
    mutation generateSbomSigningKey($orgUuid: ID!) {
        generateSbomSigningKey(orgUuid: $orgUuid) {
            uuid keyId algorithm status source publicKeyJwk publicKeyPem createdDate retiredAt revokedAt
        }
    }`

export const IMPORT_SBOM_SIGNING_KEY = gql`
    mutation importSbomSigningKey($orgUuid: ID!, $privateKeyPem: String!) {
        importSbomSigningKey(orgUuid: $orgUuid, privateKeyPem: $privateKeyPem) {
            uuid keyId algorithm status source publicKeyJwk publicKeyPem createdDate retiredAt revokedAt
        }
    }`

export const REVOKE_SBOM_SIGNING_KEY = gql`
    mutation revokeSbomSigningKey($uuid: ID!) {
        revokeSbomSigningKey(uuid: $uuid) {
            uuid keyId algorithm status source publicKeyJwk publicKeyPem createdDate retiredAt revokedAt
        }
    }`
