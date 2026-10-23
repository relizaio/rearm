// The one place the UI builds an artifact download URL (task TEA-30, design 3.5.2, D8), extracted
// from the release page's downloadArtifact, which now delegates here. The path is built from the
// artifact uuid and an integer version only: no file name, media type or user text reaches the URL.
// A named version that is not an integer throws rather than turning into a silent "latest" fetch
// (D17); a part with no integer version recorded is not downloadable at all (D9).
import { fetchArrayBufferWithAuth } from './fetchClient'

export interface ArtifactDownloadRef { uuid: string, version: number | null, fileName: string }

export const ARTIFACT_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

const INTEGER = /^\d+$/

function integerVersion (v: unknown): number | null {
    if (typeof v === 'number') return Number.isInteger(v) && v >= 0 ? v : null
    if (typeof v === 'string' && INTEGER.test(v)) return Number(v)
    return null
}

/**
 * The download URL of an artifact: the raw bytes or the augmented download. The version query is
 * appended for a non-negative integer (a number or a string of digits), omitted for null, undefined
 * or '' (the row's latest version), and anything else throws, as does a uuid that is not one.
 */
export function artifactDownloadUrl (uuid: string, raw: boolean, version?: number | string | null): string {
    if (typeof uuid !== 'string' || !ARTIFACT_UUID.test(uuid)) throw new Error('Not an artifact uuid: ' + String(uuid))
    let url = '/api/manual/v1/artifact/' + uuid + (raw ? '/rawdownload' : '/download')
    if (version === null || version === undefined || version === '') return url
    const v = integerVersion(version)
    if (v === null) throw new Error('Not an artifact version: ' + String(version))
    url += '?version=' + v
    return url
}

/** A file name safe for the anchor's download property: no path separators, no control characters. */
export function downloadFileName (name: string | null | undefined, fallback: string): string {
    // eslint-disable-next-line no-control-regex
    const cleaned = String(name ?? '').replace(/[/\\\u0000-\u001f]/g, '')
    return cleaned || fallback
}

/** A part's download, or null when the record names no artifact uuid or no integer version (fail closed). */
export function artifactDownloadRef (artifact: string | null | undefined, artifactVersion: string | number | null | undefined,
    fileName: string | null | undefined): ArtifactDownloadRef | null {
    if (typeof artifact !== 'string' || !ARTIFACT_UUID.test(artifact)) return null
    const version = integerVersion(artifactVersion)
    if (version === null) return null
    return { uuid: artifact, version, fileName: downloadFileName(fileName, artifact) }
}

/** The bytes behind a download URL, with the session's bearer token and XSRF header. */
export async function downloadArtifactBytes (url: string): Promise<ArrayBuffer> {
    return fetchArrayBufferWithAuth(url)
}

/** Hands the bytes to the browser as a file; the name is set through the DOM property, never as markup. */
export function saveBlob (bytes: ArrayBuffer, fileName: string, mediaType?: string): void {
    const blob = new Blob([bytes], mediaType ? { type: mediaType } : undefined)
    const link = document.createElement('a')
    link.href = window.URL.createObjectURL(blob)
    link.download = fileName
    link.click()
}

