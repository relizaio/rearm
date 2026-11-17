// FDAW-122 row 6: the documentation of the package downloads says what the page says and does. It pins the
// verify line in its general form (the README of each zip holds the line made for that zip), the page's own
// sentences and labels word for word, and the lines of the two pages that this task made true again.
import { describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
vi.mock('./fetchClient', () => ({ fetchWithAuth: vi.fn() }))

import {
    DOWNLOAD_BUSY, DOWNLOAD_CANNOT_REBUILD, DOWNLOAD_DRAFT_FORBIDDEN, DOWNLOAD_FORBIDDEN, DOWNLOAD_INTERRUPTED, DOWNLOAD_NOT_AS_RECORDED,
    DOWNLOAD_RATE_LIMITED, DOWNLOAD_SIGNED_OUT, DOWNLOAD_UNAVAILABLE, DOWNLOAD_VERSION_NOT_FOUND, PACKAGE_DOWNLOAD_SUFFIX,
    draftZipUrl, packageDownloadProblem, packageZipUrl, recordOfReviewUrl,
} from './packageDownloads'
import { NOT_PRINTABLE, PRINT_BUTTON } from './documentPrint'
import { DOWNLOAD_TYPE_LABELS } from './downloadLog'

const doc = (f: string): string => readFileSync(resolve(__dirname, '../../../documentation_site/docs/workflows', f), 'utf8')
const page = doc('regulatory-packages.md')
const exporting = doc('exporting-compliance-artifacts.md')
const section = (text: string, heading: string): string => {
    const from = text.indexOf('\n' + heading + '\n')
    expect(from, heading).toBeGreaterThan(-1)
    const next = text.slice(from + 1).search(/\n#{2,3} /)
    return next < 0 ? text.slice(from) : text.slice(from, from + 1 + next)
}

// The line of FDAW-118 3.5 as the Final zip's README holds it since FDAW-119, for a zip with files under fda/.
const VERIFY_LINE = '( cd internal && LC_ALL=C sha256sum -c manifest.sha256 ) && find fda internal -type f ! -path internal/manifest.json'
    + ' ! -path internal/README.txt ! -path internal/manifest.sha256 ! -path internal/record-of-review.html ! -path internal/record-of-review.json'
    + ' ! -path internal/zip-index.json -exec sha256sum {} + | while read -r h f; do if grep -qiF "\\"value\\":\\"$h\\"" internal/manifest.json;'
    + ' then echo "OK      $f"; else echo "FAILED  $f"; fi; done'
const U = '<submission uuid>'

describe('documentation: regulatory package downloads (FDAW-122)', () => {
    it('(a) the five sections are there, in this order, between the part downloads and the permissions', () => {
        const headings = [...page.matchAll(/^### (.+)$/gm)].map(m => m[1])
        const at = headings.indexOf('Downloading the documents')
        expect(headings.slice(at, at + 7)).toEqual(['Downloading the documents', 'Downloading a version as one zip', 'The record of review',
            'Downloading a draft as a zip', 'Checking a downloaded zip', 'Printing the statement', 'Permissions'])
    })

    it('(b) the verify line is on the page once, alone in a code block, with the three conditions of a pass', () => {
        const s = section(page, '### Checking a downloaded zip')
        expect(s).toContain('\n```\n' + VERIFY_LINE + '\n```\n')
        expect(page.split('sha256sum -c manifest.sha256').length - 1).toBe(1)
        expect(s).toContain('1. The first line printed is `manifest.json: OK`.')
        expect(s).toContain('2. The sha256 in `internal/manifest.sha256` equals the manifest sha256 ReARM shows for this package version')
        expect(s).toContain('3. Each file of `fda/`, and each file of `internal/` other than the six the line leaves out, has a line that starts with `OK`, and no line starts with `FAILED`.')
        expect((VERIFY_LINE.match(/ ! -path /g) ?? []).length).toBe(6)
        expect(s).toContain("`find: 'fda': No such file or directory`")
        expect(s).toContain('Prefer the line of the README of the zip you hold')
        expect(s).toContain('A draft zip has no manifest and no such line.')
        expect(s).toContain('the line compares content, not names')
    })

    it('(c) the four routes are the ones the page calls', () => {
        const shape = (url: string) => url.replace(/[0-9a-f]{8}-[0-9a-f-]{27}/, 'UUID').replace('/version/7/', '/version/<version number>/')
        const id = '11111111-1111-4111-8111-111111111111'
        const want = (what: string, url: string) => 'GET <your ReARM URL>' + shape(url).replace('UUID', what)
        expect(section(page, '### Downloading a version as one zip')).toContain('\n' + want(U, packageZipUrl(id, 7)) + '\n')
        const record = section(page, '### The record of review')
        expect(record).toContain('\n' + want(U, recordOfReviewUrl(id, 7, 'html')) + '\n')
        expect(record).toContain('\n' + want(U, recordOfReviewUrl(id, 7, 'json')) + '\n')
        expect(section(page, '### Downloading a draft as a zip')).toContain('\n' + want('<draft uuid>', draftZipUrl(id)) + '\n')
    })

    it('(d) the buttons and the refusal sentences are quoted as the page writes them', () => {
        const zip = section(page, '### Downloading a version as one zip')
        expect(zip).toContain('**Download zip**')
        // every sentence whole, as the page says it for a zip
        const says = (f: any) => packageDownloadProblem('zip', f)
        expect(zip).toContain('| `401` | ' + says({ status: 401 }) + ' |')
        expect(says({ status: 401 })).toBe(DOWNLOAD_SIGNED_OUT)
        expect(zip).toContain('| `403` | ' + says({ status: 403 }) + ' |')
        expect(says({ status: 403 })).toBe(DOWNLOAD_FORBIDDEN)
        expect(zip).toContain('| `404` | ' + says({ status: 404 }) + ' |')
        expect(says({ status: 404 })).toBe(DOWNLOAD_VERSION_NOT_FOUND)
        expect(zip).toContain('| `409` | ' + DOWNLOAD_NOT_AS_RECORDED + "(then the server's sentence, which names at most one file of the zip) |")
        expect(zip).toContain('| `429` | ' + says({ status: 429, retryAfter: '30' }) + " (the number is the server's) |")
        expect(says({ status: 429, retryAfter: '30' })).toBe(DOWNLOAD_RATE_LIMITED + '30 seconds.')
        expect(zip).toContain('| `503` with `Retry-After` | ' + says({ status: 503, retryAfter: '30' }) + ' |')
        expect(says({ status: 503, retryAfter: '30' })).toBe(DOWNLOAD_BUSY + '30 seconds.')
        expect(zip).toContain('| `503` without `Retry-After` | ' + says({ status: 503 }) + ' |')
        expect(says({ status: 503 })).toBe(DOWNLOAD_UNAVAILABLE)
        expect(zip).toContain('| any other status | ' + says({ status: 500 }) + ' (with the status that came) |')
        expect(zip).toContain('"' + DOWNLOAD_INTERRUPTED + '"')
        expect(zip).toContain('The page does not try again by itself')
        expect(zip).toContain('a second zip of your organization is refused at once while one is being made or sent')
        expect(zip).toContain('there is no route for an API key')
        const record = section(page, '### The record of review')
        expect(record).toContain('**Record of review (HTML)** and **Record of review (JSON)**')
        expect(record).toContain('It names people, with their e-mail')
        // an export has no 409 and no turn to wait for: the page says so, in the sentence the page says
        expect(record).toContain('every `503` of an export reads "' + packageDownloadProblem('record-html', { status: 503, retryAfter: '30' }) + '"')
        expect(packageDownloadProblem('record-json', { status: 503, retryAfter: '30' })).toBe(DOWNLOAD_UNAVAILABLE)
        const draft = section(page, '### Downloading a draft as a zip')
        expect(draft).toContain('**Download draft zip**')
        expect(draft).toContain('ends with `' + PACKAGE_DOWNLOAD_SUFFIX['draft-zip'] + '`')
        expect(draft).toContain('A file downloaded from the contents table of the draft card is the stored file, without a draft mark')
        expect(draft).toContain('Where that sentence says to rebuild the draft, a member without write access also reads "' + DOWNLOAD_CANNOT_REBUILD.trim() + '"')
        expect(draft).not.toContain('A rebuild needs write access. ')
        expect(draft).toContain('the card says "' + packageDownloadProblem('draft-zip', { status: 403 }) + '"')
        expect(packageDownloadProblem('draft-zip', { status: 403 })).toBe(DOWNLOAD_DRAFT_FORBIDDEN)
        const print = section(page, '### Printing the statement')
        expect(print).toContain('**' + PRINT_BUTTON + '**')
        expect(print).toContain('A PDF made this way is made by your browser, not by ReARM: it is not a part of the package, it has no sha256 in the manifest')
        expect(print).toContain('The draft card has no print button')
        expect(print).toContain('it says "' + NOT_PRINTABLE + '"')
        expect(print).toContain('without the Artifact Download function, "' + packageDownloadProblem('part', { status: 403 }) + '"')
    })

    it('(e) the lines this task made false are gone, here and on the export page', () => {
        expect(page).not.toContain('a PDF rendering is not planned')
        expect(page).toContain('ReARM renders no PDF.')
        expect(page).toContain('- **PDF rendering of the addendum and the statement by ReARM.** Not planned, by operator decision.')
        expect(page).toContain('| Download the zip of a version, a draft zip or the record of review; print the statement | The Artifact Download function')
        expect(page).toContain("each version's zip and record of review to download, its manifest")
        const log = section(exporting, '## Download Log')
        expect(log).not.toContain('records SBOM, VDR, and VEX download events')
        expect(log).not.toContain('only records server-side exports from the Release view')
        expect(log).not.toMatch(/`[A-Z]+_(EXPORT|DOWNLOAD)`/)
        for (const label of Object.values(DOWNLOAD_TYPE_LABELS)) expect(log, label).toContain(label)
        expect(log).toContain('are each recorded before the first byte is sent')
    })

    it('(f) the two pages are ASCII where this task wrote, with LF line ends and no tab', () => {
        for (const h of ['### Downloading a version as one zip', '### The record of review', '### Downloading a draft as a zip',
            '### Checking a downloaded zip', '### Printing the statement']) {
            // eslint-disable-next-line no-control-regex
            expect(section(page, h), h).toMatch(/^[\x0a\x20-\x7e]+$/)
        }
    })
})
