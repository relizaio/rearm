// The pure half of the organization settings section "SBOM export signing" (SCORE-35, design
// 3.2 to 3.4): how the served key list splits, the public JWKS URL, the date cells, and the
// texts the panel and its spec share.

import { isSchemaDriftError } from './graphqlDriftFallback'

export type SbomSigningKeyStatus = 'CURRENT' | 'RETIRED' | 'REVOKED'

/** One key as rearm-saas serves it (SbomSigningKey); publicKeyJwk is a JSON string. */
export interface SbomSigningKey {
    uuid: string
    keyId: string
    algorithm: string
    status: SbomSigningKeyStatus
    source: string
    publicKeyJwk: string | null
    publicKeyPem: string | null
    createdDate: string | null
    retiredAt: string | null
    revokedAt: string | null
}

export const SECTION_TITLE = 'SBOM export signing'
export const SECTION_INTRO = 'Merged CycloneDX JSON exports of this organization are signed with the current key;'
    + ' CSV, Excel, VDR, VEX and per-artifact downloads are not. See Signing exported SBOMs in the documentation.'
export const NO_KEY_TEXT = 'No current key: exports are not signed.'
export const JWKS_HINT = 'Public, no account needed; every key with its status. Consumers verify with'
export const JWKS_VERIFY_COMMAND = 'rearm bomutils verify-signature --key <this file>'
/**
 * Shown where the public JWKS URL used to be: the public key endpoint is withdrawn for now (rearm-saas #892),
 * so the UI does not advertise it. jwksUrl and JWKS_HINT stay for when it returns.
 */
export const PUBLIC_KEYS_NOTE = 'Publishing the organization\'s public signing keys at a public URL (JWKS), so consumers can'
    + ' verify signed exports without an account, is upcoming experimental functionality and is not available yet.'
export const UNSUPPORTED_TEXT = 'This server does not offer SBOM export signing yet. A ReARM CE installation gains it at its next sync.'

/** The confirm before Generate replaces a CURRENT key (Swal.fire options). */
export const ROTATE_CONFIRM = Object.freeze({
    title: 'Rotate the SBOM signing key?',
    text: 'The current key is retired and new exports sign with the new key. Retired keys stay listed'
        + ' under earlier keys, so documents signed with them can still be verified with them.',
    confirmButtonText: 'Rotate',
})

/** The confirm before a revoke (Swal.fire options). */
export const REVOKE_CONFIRM = Object.freeze({
    title: 'Revoke this SBOM signing key?',
    text: 'A check against a key list that marks this key revoked reports KEY_REVOKED; a copied PEM or JWK carries'
        + ' no status, so tell consumers who hold it. If it is the current key, exports are unsigned until a new key exists.',
    confirmButtonText: 'Revoke',
})

/** The sentence above the import textarea. */
export const IMPORT_HINT = 'An Ed25519 private key as PKCS#8 PEM (BEGIN PRIVATE KEY).'
    + ' The key is encrypted at rest with this installation\'s encryption settings.'

/**
 * The CURRENT key and the others, in the order served (CURRENT first, then by createdDate
 * desc): nothing is re-sorted, the server order is the truth.
 */
export function splitKeys (keys: SbomSigningKey[] | null | undefined): { current: SbomSigningKey | null, others: SbomSigningKey[] } {
    const list = (keys || []).filter(Boolean)
    const current = list.find(k => k.status === 'CURRENT') ?? null
    return { current, others: list.filter(k => k !== current) }
}

/** The organization's public JWKS (rearm-saas SbomSigningKeyPublicWs); the UI is same-origin with the backend. */
export function jwksUrl (origin: string, orgUuid: string): string {
    return `${origin}/api/public/v1/organization/${orgUuid}/sbom-signing-keys`
}

/** A date cell in the organization settings convention, or empty. */
export function dateCell (iso: string | null | undefined): string {
    return iso ? new Date(iso).toLocaleString('en-CA') : ''
}

/** Generate replaces a CURRENT key, so the button says so. */
export function generateLabel (hasCurrent: boolean): string {
    return hasCurrent ? 'Rotate key' : 'Generate key'
}

/** A list query refused as schema drift: this server has no SBOM signing (a CE mirror before its sync). */
export function isSbomSigningUnsupported (err: unknown): boolean {
    return isSchemaDriftError(err)
}
