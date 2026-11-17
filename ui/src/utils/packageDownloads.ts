// The package downloads of the release's Regulatory tab (task FDAW-122): the Final zip of one package version,
// its record of review as JSON or HTML, and the draft zip of one draft; and the fetch of a part's stored bytes
// for Print or save as PDF. The one place the UI builds the three package URLs: from a uuid and an integer only,
// never from a name. An answer that is not the file is turned into one of this file's own sentences, by the
// request that was made and the status (ROUTE_ANSWERS): a sentence that names a cause is said only for a request
// whose route has that cause. The server's 409 text is shown as it is only when it is short printable ASCII sent
// as text/plain. No thrown message and no status text is ever shown.
// The file name comes from the server's Content-Disposition and is kept only when it is a name the server can
// have written for that download; a draft zip is never saved under a name without _DRAFT. Pure but for
// fetchPackageFile and saveFile.
import { fetchWithAuth } from './fetchClient'
import { ARTIFACT_UUID } from './artifactDownload'

export type PackageDownloadKind = 'zip' | 'record-json' | 'record-html' | 'draft-zip'
/** Every request of this file: the four downloads, and 'part', the stored bytes of one part (the print fetch). */
export type PackageRequestKind = PackageDownloadKind | 'part'

/** What the server ends the file name of each download with, and the media type it sends. */
export const PACKAGE_DOWNLOAD_SUFFIX: Record<PackageDownloadKind, string> = {
    zip: '.zip',
    'record-json': '-record-of-review.json',
    'record-html': '-record-of-review.html',
    'draft-zip': '_DRAFT.zip',
}
export const PACKAGE_DOWNLOAD_MEDIA_TYPE: Record<PackageDownloadKind, string> = {
    zip: 'application/zip',
    'record-json': 'application/json',
    'record-html': 'text/html',
    'draft-zip': 'application/zip',
}

export const DOWNLOAD_SIGNED_OUT = 'Your session is not valid any more. Sign in again, then try again.'
export const DOWNLOAD_FORBIDDEN = 'ReARM refused this download. It needs the Artifact Download permission on the component of this release.'
export const DOWNLOAD_DRAFT_FORBIDDEN = DOWNLOAD_FORBIDDEN + ' A draft that was finalized or discarded can no longer be downloaded.'
export const DOWNLOAD_VERSION_NOT_FOUND = 'This version of the package was not found. Reload the page.'
export const DOWNLOAD_BUSY = 'ReARM is making other package zips right now, or another zip of this organization. Nothing was downloaded. Try again in about '
export const DOWNLOAD_BUSY_SHORTLY = 'half a minute'
export const DOWNLOAD_RATE_LIMITED = 'ReARM got too many requests from your account in a short time and refused this one. Try again in about '
export const DOWNLOAD_UNAVAILABLE = 'ReARM is not serving downloads at the moment. Try again later; if this stays, tell the administrator of this ReARM instance.'
export const DOWNLOAD_NOT_AS_RECORDED = 'This version cannot be delivered as recorded. Nothing was downloaded. Give this message to the administrator of this ReARM instance: '
export const DOWNLOAD_CONFLICT_UNREAD = 'ReARM could not make this download, and its reason could not be read. Reload the page and try again.'
export const DOWNLOAD_CANNOT_REBUILD = ' A rebuild needs write access; ask a member who has it.'
export const DOWNLOAD_SERVER_FAULT = 'The download failed on the server (HTTP status '
export const DOWNLOAD_SERVER_FAULT_END = '). Try again; if this stays, tell the administrator of this ReARM instance the time of the attempt.'
export const DOWNLOAD_NOT_THE_FILE = 'The server answered with something that is not the expected file. Nothing was saved. Reload the page and try again.'
export const DOWNLOAD_INTERRUPTED = 'The download did not complete. Check the connection and try again; if this stays, reload the page.'
export const DOWNLOAD_NOT_ASKED = 'This page could not make the request. Reload the page and try again.'

/**
 * The two texts of the draft route's 409 that tell the reader to rebuild (rearm-saas DraftZipContents: NO_BUILD,
 * whole, and NOT_MARKED_ADVICE, which ends the text about a document that cannot be marked). Compared whole or at
 * the end, never searched for a word: the other texts hold a file name, and a file name can hold any word.
 */
export const DRAFT_NO_BUILD_TEXT = 'The draft has no finished build to download. When a build is running, wait for it; otherwise rebuild the draft. Then download again.'
export const DRAFT_NOT_MARKED_ADVICE = '. ReARM marks only the HTML it generates and never delivers such a file of a draft unmarked. Rebuild the draft and download again;'
    + ' if this message returns, give it to the administrator of this ReARM instance.'

/** Whether a 409 text of the draft route tells the reader to rebuild the draft. */
export function draftTextAsksRebuild (text: string): boolean {
    return text === DRAFT_NO_BUILD_TEXT || text.endsWith(DRAFT_NOT_MARKED_ADVICE)
}

/**
 * What the route of each request can answer, beyond what every route answers (401, 403, 429, 503 without
 * Retry-After, 500): 404 for a version the head does not have; 409 with a text, of a Final version or of a draft;
 * 503 with Retry-After when no zip permit is free. Read at rearm-saas 715bf899 (SubmissionZipWs, ArtifactWs).
 */
export const ROUTE_ANSWERS: Record<PackageRequestKind, { versionNotFound: boolean, conflict: 'final' | 'draft' | null, busy: boolean }> = {
    zip: { versionNotFound: true, conflict: 'final', busy: true },
    'record-json': { versionNotFound: true, conflict: null, busy: false },
    'record-html': { versionNotFound: true, conflict: null, busy: false },
    'draft-zip': { versionNotFound: false, conflict: 'draft', busy: true },
    part: { versionNotFound: false, conflict: null, busy: false },
}

const SAFE_NAME = /^[A-Za-z0-9._-]{1,255}$/
/** A 409 text the page shows as it is: printable ASCII, at most this long. */
const CONFLICT_TEXT = /^[\x20-\x7e]{1,1000}$/
const SECONDS = /^[0-9]{1,4}$/

function uuidOf (value: unknown, what: string): string {
    if (typeof value !== 'string' || !ARTIFACT_UUID.test(value)) throw new Error('Not a ' + what + ' uuid: ' + String(value))
    return value
}

function versionOf (value: unknown): number {
    if (typeof value !== 'number' || !Number.isInteger(value) || value < 1 || value > 2147483647) {
        throw new Error('Not a package version: ' + String(value))
    }
    return value
}

/** The Final zip of version `version` of the package head `submission`. */
export function packageZipUrl (submission: string, version: number): string {
    return '/api/manual/v1/submission/' + uuidOf(submission, 'submission') + '/version/' + versionOf(version) + '/zip'
}

/** The record of review of one package version, alone, as JSON or as HTML. */
export function recordOfReviewUrl (submission: string, version: number, form: 'json' | 'html'): string {
    if (form !== 'json' && form !== 'html') throw new Error('Not a record of review form: ' + String(form))
    return '/api/manual/v1/submission/' + uuidOf(submission, 'submission') + '/version/' + versionOf(version) + '/record-of-review.' + form
}

/** The draft zip of the draft `draft`. */
export function draftZipUrl (draft: string): string {
    return '/api/manual/v1/submission-draft/' + uuidOf(draft, 'draft') + '/zip'
}

/** The name a download is saved under when the server's header gives none this page keeps. */
export function packageFallbackName (kind: PackageDownloadKind, version: number | null | undefined): string {
    const n = typeof version === 'number' && Number.isInteger(version) && version > 0 ? String(version) : ''
    return (kind === 'draft-zip' ? 'package-draft-v' : 'package-v') + n + PACKAGE_DOWNLOAD_SUFFIX[kind]
}

/**
 * The file name of a Content-Disposition header, kept only when it is made of [A-Za-z0-9._-] and ends as this
 * download's names end (so it is never dots only); else the fallback. So a draft zip always ends with _DRAFT.zip.
 */
export function packageFileName (kind: PackageDownloadKind, disposition: string | null | undefined, fallback: string): string {
    const m = /^attachment; filename="([^"]*)"$/.exec(String(disposition ?? ''))
    const name = m ? m[1] : ''
    if (!SAFE_NAME.test(name) || !name.endsWith(PACKAGE_DOWNLOAD_SUFFIX[kind])) return fallback
    return name
}

/** Whether a Content-Type header names the media type of this download (parameters ignored). */
export function packageMediaTypeMatches (kind: PackageDownloadKind, contentType: string | null | undefined): boolean {
    return String(contentType ?? '').split(';')[0].trim().toLowerCase() === PACKAGE_DOWNLOAD_MEDIA_TYPE[kind]
}

export interface PackageRefusalFacts { status: number, retryAfter?: string | null, contentType?: string | null, body?: string | null, writable?: boolean }

/** "30 seconds." from a Retry-After of 1 to 4 digits above zero; else "half a minute.". Never the header's text. */
function waitOf (after: string | null | undefined): string {
    return typeof after === 'string' && SECONDS.test(after) && Number(after) > 0 ? Number(after) + ' seconds.' : DOWNLOAD_BUSY_SHORTLY + '.'
}

/** The page's sentence for an answer that is not the file, by the request that was made. */
export function packageDownloadProblem (kind: PackageRequestKind, f: PackageRefusalFacts): string {
    const route = ROUTE_ANSWERS[kind]
    const fault = DOWNLOAD_SERVER_FAULT + (Number.isInteger(f.status) ? f.status : 0) + DOWNLOAD_SERVER_FAULT_END
    switch (f.status) {
    case 401: return DOWNLOAD_SIGNED_OUT
    case 403: return kind === 'draft-zip' ? DOWNLOAD_DRAFT_FORBIDDEN : DOWNLOAD_FORBIDDEN
    case 404: return route.versionNotFound ? DOWNLOAD_VERSION_NOT_FOUND : fault
    case 409: {
        if (route.conflict === null) return fault
        const body = String(f.body ?? '')
        const plain = String(f.contentType ?? '').split(';')[0].trim().toLowerCase() === 'text/plain'
        if (!plain || !CONFLICT_TEXT.test(body)) return DOWNLOAD_CONFLICT_UNREAD
        if (route.conflict === 'final') return DOWNLOAD_NOT_AS_RECORDED + body
        return f.writable === false && draftTextAsksRebuild(body) ? body + DOWNLOAD_CANNOT_REBUILD : body
    }
    case 429: return DOWNLOAD_RATE_LIMITED + waitOf(f.retryAfter)
    case 503: {
        const after = f.retryAfter
        if (!route.busy || after === null || after === undefined) return DOWNLOAD_UNAVAILABLE
        return DOWNLOAD_BUSY + waitOf(after)
    }
    default: return fault
    }
}

export type PackageDownloadResult = { ok: true, blob: Blob, fileName: string } | { ok: false, status: number, message: string }

/**
 * One GET with the session's token. Only 200 with the media type of the download is the file; every other
 * answer is a message. Nothing is retried: a second request could take the organization's one zip permit, and
 * every served download writes a download-log row.
 */
export async function fetchPackageFile (kind: PackageDownloadKind, url: string, fallbackName: string,
    facts: { writable?: boolean } = {}): Promise<PackageDownloadResult> {
    let response: Response
    try {
        response = await fetchWithAuth(url)
    } catch (err: any) {
        console.error(err)
        return { ok: false, status: 0, message: DOWNLOAD_INTERRUPTED }
    }
    if (response.status === 200) {
        if (!packageMediaTypeMatches(kind, response.headers.get('Content-Type'))) {
            return { ok: false, status: 200, message: DOWNLOAD_NOT_THE_FILE }
        }
        try {
            const blob = await response.blob()
            return { ok: true, blob, fileName: packageFileName(kind, response.headers.get('Content-Disposition'), fallbackName) }
        } catch (err: any) {
            console.error(err)
            return { ok: false, status: 200, message: DOWNLOAD_INTERRUPTED }
        }
    }
    let body: string | null = null
    if (response.status === 409 && ROUTE_ANSWERS[kind].conflict !== null) {
        try { body = await response.text() } catch (err: any) { console.error(err) }
    }
    return { ok: false, status: response.status, message: packageDownloadProblem(kind, { status: response.status,
        retryAfter: response.headers.get('Retry-After'), contentType: response.headers.get('Content-Type'), body, writable: facts.writable }) }
}

export type PartBytesResult = { ok: true, bytes: ArrayBuffer } | { ok: false, status: number, message: string }

/**
 * The stored bytes of one part, for Print or save as PDF: one GET of the part's download URL with the session's
 * token. Only 200 is the file; every other answer is this file's sentence for a 'part' request, and no body of a
 * refusal is read. Never retried: every served fetch writes a download-log row.
 */
export async function fetchPartBytes (url: string): Promise<PartBytesResult> {
    let response: Response
    try {
        response = await fetchWithAuth(url)
    } catch (err: any) {
        console.error(err)
        return { ok: false, status: 0, message: DOWNLOAD_INTERRUPTED }
    }
    if (response.status !== 200) {
        return { ok: false, status: response.status, message: packageDownloadProblem('part', { status: response.status,
            retryAfter: response.headers.get('Retry-After') }) }
    }
    try {
        return { ok: true, bytes: await response.arrayBuffer() }
    } catch (err: any) {
        console.error(err)
        return { ok: false, status: 200, message: DOWNLOAD_INTERRUPTED }
    }
}

/** How long the object URL of a saved file is kept before it is revoked. */
export const SAVE_URL_LIFETIME_MS = 60000

/**
 * Hands a file to the browser. The object URL names a blob of type application/octet-stream whatever the
 * server sent, so it never names an HTML document of this origin; the name is set through the DOM property.
 */
export function saveFile (blob: Blob, fileName: string): void {
    const url = window.URL.createObjectURL(new Blob([blob], { type: 'application/octet-stream' }))
    const link = document.createElement('a')
    link.href = url
    link.download = fileName
    link.rel = 'noopener'
    link.click()
    setTimeout(() => window.URL.revokeObjectURL(url), SAVE_URL_LIFETIME_MS)
}
