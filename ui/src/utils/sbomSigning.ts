// What a signed SBOM export says about itself (SCORE-35, design 3.5 and 3.6). Pure: no store,
// no client, so every case runs in the node environment.
//
// rearm-saas (SCORE-33) stamps every merged CycloneDX JSON export at the end of
// metadata.properties: reliza:export:signed "true" plus reliza:export:signatureKeyId and a root
// JSF `signature`, or "false" plus reliza:export:unsignedReason (NO_KEY, SIGNING_ERROR). The
// dialog line is read from the document the user received, never from the organization's key
// state, so it cannot drift from the file. The texts equal the notice `rearm exportreleasebom`
// prints (rearm-cli SCORE-34), so the CLI and the UI say the same thing about the same file.

export const PROP_SIGNED = 'reliza:export:signed'
export const PROP_SIGNATURE_KEY_ID = 'reliza:export:signatureKeyId'
export const PROP_UNSIGNED_REASON = 'reliza:export:unsignedReason'

/** rearm-saas UnsignedReason (SCORE-33), open to any other string a server writes. */
export type UnsignedReason = 'NO_KEY' | 'SIGNING_ERROR' | (string & {})

/** rearm-cli input.signature.verification (SCORE-34), open to a value this UI does not know yet. */
export type SignatureVerification = 'NOT_REQUESTED' | 'VERIFIED' | 'FAILED' | 'KEY_MISMATCH' | 'KEY_REVOKED'
    | 'UNSUPPORTED_ALGORITHM' | 'UNSUPPORTED_FORM' | 'NO_KEY' | (string & {})

export type SignedStatus =
    | { kind: 'SIGNED', keyId: string }
    | { kind: 'UNSIGNED', reason: UnsignedReason } // '' when absent
    | { kind: 'UNSTAMPED' } // no reliza:export:signed property: a server that does not stamp
    | { kind: 'UNREADABLE' } // not JSON, not an object

export type ExportMediaType = 'JSON' | 'CSV' | 'EXCEL'

/** rearm-cli `input.signature` of a score report (SCORE-34, reportVersion 1). */
export interface SignatureReportInput {
    present: boolean
    wellFormed: boolean
    algorithm: string
    keyId: string
    verification: SignatureVerification
    detail: string
}

export const SIGNED_LINE_PREFIX = 'Signed with organization key '
export const NOT_SIGNED_NO_KEY = 'Not signed: no organization SBOM signing key is configured (Organization settings, SBOM export signing)'
export const NOT_SIGNED_SIGNING_ERROR = 'Not signed: signing failed on the server (SIGNING_ERROR)'
export const NOT_SIGNED = 'Not signed'
export const NOT_JSON_NOTE = 'Signing applies to CycloneDX JSON exports only; this file carries no signature.'

/** The document as an object, or null when it is not a JSON object. Never throws. */
function asObject (doc: string | object): Record<string, any> | null {
    let value: any = doc
    if (typeof doc === 'string') {
        try {
            value = JSON.parse(doc)
        } catch {
            return null
        }
    }
    return value !== null && typeof value === 'object' && !Array.isArray(value) ? value : null
}

/** The first value of each metadata property name, as rearm exportreleasebom reads them. */
function firstProperties (bom: Record<string, any>): Map<string, unknown> {
    const out = new Map<string, unknown>()
    const props = bom.metadata?.properties
    if (!Array.isArray(props)) return out
    for (const p of props) {
        if (p === null || typeof p !== 'object' || typeof p.name !== 'string') continue
        if (!out.has(p.name)) out.set(p.name, p.value)
    }
    return out
}

const str = (v: unknown): string => typeof v === 'string' ? v : ''

/** Whether the export says it is signed, and with which key or why not. Never throws. */
export function signatureStatus (doc: string | object): SignedStatus {
    const bom = asObject(doc)
    if (!bom) return { kind: 'UNREADABLE' }
    const props = firstProperties(bom)
    if (!props.has(PROP_SIGNED)) return { kind: 'UNSTAMPED' }
    // CycloneDX property values are strings: only the string "true" says signed.
    if (props.get(PROP_SIGNED) === 'true') return { kind: 'SIGNED', keyId: str(props.get(PROP_SIGNATURE_KEY_ID)) }
    return { kind: 'UNSIGNED', reason: str(props.get(PROP_UNSIGNED_REASON)) }
}

/** Whether the document carries a root `signature` object with a string `value`; independent of the stamp. */
export function hasRootSignature (doc: string | object): boolean {
    const sig = asObject(doc)?.signature
    return sig !== null && typeof sig === 'object' && !Array.isArray(sig) && typeof sig.value === 'string'
}

/** The dialog's status line after an export, or null for none. */
export function exportSigningLine (status: SignedStatus, mediaType: ExportMediaType): string | null {
    if (mediaType === 'CSV' || mediaType === 'EXCEL') return NOT_JSON_NOTE
    switch (status.kind) {
    case 'SIGNED':
        // The CLI prints <unknown> for a signed stamp without its key id; so does the dialog.
        return SIGNED_LINE_PREFIX + (status.keyId || '<unknown>')
    case 'UNSIGNED':
        if (status.reason === 'NO_KEY') return NOT_SIGNED_NO_KEY
        if (status.reason === 'SIGNING_ERROR') return NOT_SIGNED_SIGNING_ERROR
        return status.reason ? `${NOT_SIGNED}: ${status.reason}` : NOT_SIGNED
    default:
        // An older server stamps nothing; the file was still downloaded.
        return null
    }
}

/** The score report's signature line from input.signature, or null when the report has none. */
export function scoreSignatureLine (signature: SignatureReportInput | undefined): string | null {
    if (!signature || typeof signature !== 'object') return null
    if (!signature.present) return 'Signature: none'
    const detail = signature.detail ? `: ${signature.detail}` : ''
    if (!signature.wellFormed) return `Signature: present but malformed${detail}`
    const head = `Signature: ${signature.algorithm}${signature.keyId ? `, key ${signature.keyId}` : ''}`
    if (signature.verification === 'NOT_REQUESTED') return `${head}, present, not verified (no key given)`
    if (signature.verification === 'VERIFIED') return `${head}, verified`
    return `${head}, ${signature.verification}${detail}`
}
