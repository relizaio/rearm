// TEA publications (task TEA-5): the pure logic of the release page's TEA actions and section --
// the confirmations before publish, re-publish and hide, the summary after one, the red banner,
// the collection and entry rows, the History text of a TEA_PUBLICATION event. Kept out of
// ReleaseView.vue, with no Vue import, so teaPublication.spec.ts runs it.

import { graphqlErrorEntries, graphqlRefusalText, NULL_PROPAGATION_CLASSIFICATION } from './graphqlErrorText'
import { listedNames, teaPublicConfirmText, teaSourceLabel } from './teaProfile'
import { artifactDownloadRef, type ArtifactDownloadRef } from './artifactDownload'
import { labelOrLower, lowerEnum } from './fdaLabels'
import { labelOf, SUBMISSION_STATE_LABELS } from './submissions'
import { PACKAGE_PART_LABELS } from './packageSettings'

/** What the confirmation names: the release, and the perspective names a profile source may point at. */
export interface TeaPublishNames {
    component: string
    version: string
    perspectives?: Record<string, string>
}

/** The Swal options of a confirmation; showCancelButton is always set, so Cancel sends nothing. */
export interface TeaConfirm {
    title?: string
    titleText?: string
    text: string
    icon?: 'warning'
    showCancelButton: true
    confirmButtonText: string
    confirmButtonColor?: string
    showDenyButton?: true
    denyButtonText?: string
    customClass?: Record<string, string>
}

const RED = '#d33'
const ID_PLACEHOLDER = '<id assigned on publish>'

function kindPath (view: any): string {
    return view?.kind === 'PRODUCT_RELEASE' ? 'productRelease' : 'componentRelease'
}

function kindWord (view: any): string {
    return view?.kind === 'PRODUCT_RELEASE' ? 'product' : 'component'
}

const trimBase = (apiBase: string) => apiBase.replace(/\/+$/, '')

/**
 * The TEA URL of the release object: the publication's own once it exists, else the URL it will
 * have (`<apiBase>/v1.0.0/<productRelease|componentRelease>/<id assigned on publish>`; the id
 * does not exist until the publication row does), else a note that the organization has no TEA
 * id yet.
 */
export function teaPublicUrlText (view: any): string {
    const known = view?.publication?.publicUrl || view?.publicUrl
    if (known) return known
    if (view?.apiBase) return trimBase(view.apiBase) + '/v1.0.0/' + kindPath(view) + '/' + ID_PLACEHOLDER
    return 'no TEA URL yet: the organization has no TEA id'
}

function profileSourceText (view: any, perspectives: Record<string, string>): string {
    const source = view?.profileSource
    const label = !source || source === 'DEFAULT'
        ? 'built-in default'
        : teaSourceLabel(source, view?.profileSourceObject, perspectives)
    const revision = view?.profileRevision
    return 'the ' + label + ' TEA profile' + (revision != null ? ' (revision ' + revision + ')' : '')
}

/**
 * The cascade part of a product's publish or share dialog (FDAW-39 3.7), with the dialog keys the
 * list needs: today's count sentence when the list was not read, else what the list says, one line
 * per child left behind, and the offer or the hint.
 */
function cascadePart (view: any, partial: TeaPartial | null | undefined): { text: string, keys: Record<string, any> } | null {
    if (view?.kind !== 'PRODUCT_RELEASE' || !view?.cascades) return null
    if (!partial || !partial.loaded) {
        const n = view.cascadeChildren ?? 0
        return { keys: {}, text: n === 1
            ? 'Its 1 component release is published with it unless its own TEA profile forbids it.'
            : 'Its ' + n + ' component releases are published with it unless their own TEA profile forbids it.' }
    }
    if (partial.total === 0) return { text: TEA_CASCADE_NONE, keys: {} }
    if (partial.notPublished === 0) {
        return { text: partial.total === 1 ? TEA_CASCADE_ALL_ONE : TEA_CASCADE_ALL(partial.total), keys: {} }
    }
    let text = '\n' + TEA_PARTIAL_LEAD(partial.notPublished, partial.total) + '\n' + partial.lines.join('\n') + '\n'
        + TEA_PARTIAL_CONSUMER
    const keys: Record<string, any> = { customClass: { htmlContainer: 'tea-partial-children' } }
    if (partial.offer.length) {
        text += ' ' + teaPartialOfferText(partial.offer)
        keys.showDenyButton = true
        keys.denyButtonText = TEA_TURN_ON_BOTH
        keys.customClass.denyButton = 'tea-turn-on-both'
    } else if (partial.hint) {
        text += ' ' + TEA_PARTIAL_HINT
    }
    return { text, keys }
}

/**
 * The confirmation before Publish (no publication yet) or Re-publish (a PUBLISHED or HIDDEN one).
 * PUBLIC asks with the warning icon and the red button and names the URL anyone can read; a
 * product whose profile publishes its component releases with it says how many, or, with the
 * preview's list read (`partial`, FDAW-39 3.7), which of them stay behind and why.
 */
export function teaPublishConfirm (view: any, names: TeaPublishNames, partial?: TeaPartial | null): TeaConfirm {
    const republish = !!view?.publication
    const verb = republish ? 'Re-publish' : 'Publish'
    const isPublic = view?.effectiveVisibility === 'PUBLIC'
    const parts: string[] = [
        'Published as a TEA ' + kindWord(view) + ' release under '
            + profileSourceText(view, names?.perspectives || {}) + '.',
    ]
    parts.push(isPublic
        ? 'Visibility is PUBLIC: anyone who knows the URL can read it without authentication: ' + teaPublicUrlText(view)
        : 'Visibility: PRIVATE: readable with an external key that covers this ' + kindWord(view) + '.')
    const cascade = cascadePart(view, partial)
    if (cascade) parts.push(cascade.text)
    const confirm: TeaConfirm = {
        titleText: verb + ' ' + teaOneLine(names?.component) + ' ' + teaOneLine(names?.version) + ' on TEA?',
        text: parts.join(' '),
        showCancelButton: true,
        confirmButtonText: verb,
        ...(cascade?.keys ?? {}),
    }
    if (isPublic) {
        confirm.icon = 'warning'
        confirm.confirmButtonColor = RED
    }
    return confirm
}

/** A TEA act the release page waits for: the server does it in the request, start to finish. */
export type TeaAct = 'publish' | 'republish' | 'hide'

/**
 * The modal shown while a TEA act runs (SweetAlert2 options): it cannot be dismissed, shows the
 * loader, and says why it takes time; the act's own result dialog replaces it when the server answers.
 * A publish generates every aggregated SBOM of the profile before it answers, which takes a while for
 * a large inventory.
 */
export function teaActProgress (act: TeaAct, view: any): Record<string, any> {
    const title = act === 'hide' ? 'Hiding from TEA…' : act === 'republish' ? 'Re-publishing on TEA…' : 'Publishing on TEA…'
    let text = act === 'hide'
        ? 'Concealing the release on TEA.'
        : 'Generating the aggregated SBOM and the collection. A large release can take a minute or more.'
    if (act !== 'hide' && view?.kind === 'PRODUCT_RELEASE' && view?.cascades) {
        text += ' Its component releases are published with it.'
    }
    return {
        title,
        text: text + ' Keep this page open until it finishes.',
        allowOutsideClick: false,
        allowEscapeKey: false,
        showConfirmButton: false,
    }
}

/** The label beside the header spinner while a TEA act runs. */
export function teaActLabel (act: TeaAct): string {
    return act === 'hide' ? 'Hiding from TEA…' : act === 'republish' ? 'Re-publishing on TEA…' : 'Publishing on TEA…'
}

/** The confirmation before Hide. A product's cascade children are concealed with it at request time. */
export function teaHideConfirm (view: any): TeaConfirm {
    let text = 'The release stops being served on TEA; its published collections are kept and re-publish '
        + 'continues their numbering.'
    if (view?.kind === 'PRODUCT_RELEASE') {
        text += ' Component releases published with this product are concealed with it.'
    }
    return {
        title: 'Hide from TEA?',
        text,
        icon: 'warning',
        showCancelButton: true,
        confirmButtonText: 'Hide',
        confirmButtonColor: RED,
    }
}

/**
 * The Pro TeaRideAlongStatus values: one artifact (or signature) row of a publish, PUBLISHED or
 * the reason it stayed out. teaProfileSchemaDrift.spec.ts holds this list to the Pro enum.
 */
export const TEA_RIDE_ALONG_STATUSES = [
    'PUBLISHED', 'SKIPPED_NO_RAW_BYTES', 'SKIPPED_NO_CHECKSUM', 'SKIPPED_VERSION_NOT_INTEGER',
    'SKIPPED_SIGNATURE_TYPE', 'SKIPPED_NO_DOWNLOAD_LINK', 'SKIPPED_LINK_NOT_HTTP_URL', 'SKIPPED_VDR_SNAPSHOT',
] as const
export type TeaRideAlongStatus = typeof TEA_RIDE_ALONG_STATUSES[number]

/** The summary's text per skip status; a status added to the list above and left out here does not compile. */
export const TEA_RIDE_ALONG_SKIP_LABELS: Record<Exclude<TeaRideAlongStatus, 'PUBLISHED'>, string> = {
    SKIPPED_NO_RAW_BYTES: 'no retained raw bytes',
    SKIPPED_NO_CHECKSUM: 'no checksum',
    SKIPPED_VERSION_NOT_INTEGER: 'version is not an integer',
    SKIPPED_SIGNATURE_TYPE: 'signature, published with its artifact',
    SKIPPED_NO_DOWNLOAD_LINK: 'no download link',
    SKIPPED_LINK_NOT_HTTP_URL: 'download link is not an absolute http(s) URL',
    SKIPPED_VDR_SNAPSHOT: 'VDR snapshot, never published on TEA',
}

const CHILD_PUBLISHED = ['PUBLISHED', 'REPUBLISHED', 'UNCHANGED']
const CHILD_SKIPPED: Record<string, string> = {
    SKIPPED_DIRECT: 'skipped, published directly',
    SKIPPED_HIDDEN: 'skipped, hidden',
    SKIPPED_OTHER_PRODUCT: 'skipped, published with another product',
}

/** A skipped child's text; SKIPPED_OTHER_PRODUCT carries its owner in the detail ("published with product <label>"). */
function childSkipText (c: any): string {
    if (c?.outcome === 'SKIPPED_OTHER_PRODUCT' && c?.detail) return 'skipped, ' + c.detail
    return CHILD_SKIPPED[c?.outcome]
}

/**
 * The aggregate SBOM's revision when the publication names no aggregate list (a head written before
 * TEA-28, V101): the deprecated aggregateArtifact's entry, else the first generated entry.
 */
function aggregateRevision (result: any): number | null {
    const entries: any[] = result?.collection?.entries || []
    const agg = entries.find(e => e && e.artifact === result?.publication?.aggregateArtifact)
        ?? entries.find(e => e && e.origin === 'SYNTHETIC')
    return agg ? agg.revision : null
}

/** One aggregate per SBOM profile the publication names (TEA-28), in its order, with the entry revision. */
function aggregateSummary (result: any): string | null {
    const list: any[] = result?.publication?.aggregateArtifacts || []
    if (!list.length) return null
    const entries: any[] = result?.collection?.entries || []
    return 'aggregate SBOMs: ' + list.map(a => {
        const rev = entries.find(e => e && e.artifact === a?.artifact)?.revision
        return (a?.profileName ?? '') + (rev != null ? ' r' + rev : '')
    }).join(', ')
}

function childName (c: any): string {
    return [c?.componentName, c?.version].filter(Boolean).join(' ') || c?.release || ''
}

/**
 * The text of the success dialog after a publish or re-publish: the collection version, the
 * aggregate SBOM revision, the ride-along counts with the skip reasons, and for a product the
 * component release counts with each skip and refusal named. `opts.worded` (the share's result,
 * FDAW-39 3.8): every child in words (teaChildReason with the act's wording), a child of an outcome
 * the summary does not count named as "other", a ride-along status without a label humanized, and an
 * unchanged release that still names its children.
 */
export function teaPublishSummary (result: any, opts: { worded?: boolean } = {}): string {
    const worded = opts?.worded === true
    const version = result?.collection?.version ?? result?.publication?.latestVersion
    if (result?.outcome === 'UNCHANGED') {
        const listed = Array.isArray(result?.children) && result.children.some((c: any) => c != null)
        if (!worded || !listed) return 'Nothing changed since collection version ' + version
        return wordedChildrenText([TEA_SHARE_UNCHANGED_LEAD(version)], result.children)
    }
    const lead = result?.outcome === 'REPUBLISHED' ? 'Re-published' : 'Published'
    const parts: string[] = [lead + ': collection version ' + version]
    const aggregates = aggregateSummary(result)
    const revision = aggregates ? null : aggregateRevision(result)
    if (aggregates) parts.push(aggregates)
    else if (revision != null) parts.push('aggregate SBOM revision ' + revision)

    const artifacts: any[] = result?.artifacts || []
    const published = artifacts.filter(a => a?.status === 'PUBLISHED').length
    const skipped = artifacts.filter(a => a?.status !== 'PUBLISHED')
    let artifactPart = published + ' artifacts published, ' + skipped.length + ' skipped'
    if (skipped.length) {
        const counts = new Map<string, number>()
        for (const a of skipped) {
            const label = worded
                ? labelOrLower(TEA_RIDE_ALONG_SKIP_LABELS, a.status)
                : TEA_RIDE_ALONG_SKIP_LABELS[a.status as keyof typeof TEA_RIDE_ALONG_SKIP_LABELS] ?? a.status
            counts.set(label, (counts.get(label) ?? 0) + 1)
        }
        artifactPart += ' (' + [...counts].map(([label, n]) => n + ' ' + label).join(', ') + ')'
    }
    parts.push(artifactPart)

    const children: any[] = result?.children || []
    if (worded && children.length) return wordedChildrenText(parts, children)
    let text: string
    if (children.length) {
        const ok = children.filter(c => CHILD_PUBLISHED.includes(c?.outcome)).length
        const skippedChildren = children.filter(c => CHILD_SKIPPED[c?.outcome])
        const refused = children.filter(c => c?.outcome === 'REFUSED')
        parts.push(ok + ' component releases published, ' + skippedChildren.length + ' skipped, '
            + refused.length + ' refused')
        text = parts.join('; ')
        const reasons = [
            ...skippedChildren.map(c => childName(c) + ': ' + childSkipText(c)),
            ...refused.map(c => childName(c) + ': refused' + (c.refusal ? ' (' + c.refusal + ')' : '')
                + (c.detail ? ': ' + c.detail : '')),
        ]
        if (reasons.length) text += '. ' + reasons.join('; ')
    } else {
        text = parts.join('; ')
    }
    return text
}

/**
 * The worded children part of the share's result: the counts, an "other" count only when a child has
 * an outcome none of the three groups knows, then each child left behind in words, in the result's
 * order within skipped, refused and other.
 */
function wordedChildrenText (parts: string[], children: any[]): string {
    const listed = children.filter(c => c != null)
    const ok = listed.filter(c => CHILD_PUBLISHED.includes(c.outcome)).length
    const skipped = listed.filter(c => CHILD_SKIPPED[c.outcome])
    const refused = listed.filter(c => c.outcome === 'REFUSED')
    const other = listed.filter(c => !CHILD_PUBLISHED.includes(c.outcome) && !CHILD_SKIPPED[c.outcome] && c.outcome !== 'REFUSED')
    parts.push(ok + ' component releases published, ' + skipped.length + ' skipped, ' + refused.length + ' refused'
        + (other.length ? ', ' + other.length + ' other' : ''))
    let text = parts.join('; ')
    const reasons = [...skipped, ...refused, ...other].map(c => teaChildName(c) + ': ' + teaChildReason(c, { act: true }))
    if (reasons.length) text += '. ' + reasons.join('; ')
    return text
}

/**
 * The section's state line when the exposure is CONCEALED: why, per TeaConcealReason, or null
 * while the publication is served. LIFECYCLE_BELOW_ASSEMBLED names the release's lifecycle when
 * the page knows it; PRODUCT_CONCEALED names the product through cascadeOfLabel.
 */
export function teaConcealedText (publication: any, lifecycle?: string | null): string | null {
    if (!publication || publication.exposure !== 'CONCEALED') return null
    const product = publication.cascadeOfLabel
    switch (publication.concealedBecause) {
    case 'HIDDEN': return 'Concealed: hidden'
    case 'RELEASE_MISSING': return 'Concealed: the release is missing'
    case 'RELEASE_ARCHIVED': return 'Concealed: the release is archived'
    case 'LIFECYCLE_BELOW_ASSEMBLED': return 'Concealed: the release is ' + (lifecycle || 'below ASSEMBLED')
    case 'COMPONENT_MISSING': return 'Concealed: the component is missing'
    case 'PROFILE_CONFLICT': return 'Concealed: the component\'s TEA profiles conflict'
    case 'PUBLISHING_DISABLED': return 'Concealed: publishing is disabled by the component\'s own TEA profile'
    case 'PRODUCT_MISSING': return 'Concealed: the product it was published with is missing'
    case 'PRODUCT_CONCEALED': return 'Concealed: with its product' + (product ? ' ' + product : '')
    default: return 'Concealed'
    }
}

/** Where the section says a publication's profile came from: its product for a cascade child, else the source. */
export function teaPublishedWithText (publication: any): string | null {
    if (!publication?.cascadeOf) return null
    return 'Published with product ' + (publication.cascadeOfLabel || publication.cascadeOf)
}

/** The persistent red banner of a release served on TEA to anyone, or null. */
export function teaPublicationBanner (view: any): string | null {
    const p = view?.publication
    if (!p || p.state !== 'PUBLISHED' || p.exposure !== 'PUBLIC') return null
    const url = p.publicUrl || view?.publicUrl
    return 'Public on TEA: this release is readable by anyone who knows its TEA URL'
        + (url ? ': ' + url : '.')
}

export const TEA_UPDATE_REASON_LABELS: Record<string, string> = {
    INITIAL_RELEASE: 'Initial release',
    VEX_UPDATED: 'VEX updated',
    ARTIFACT_UPDATED: 'Artifact updated',
    ARTIFACT_ADDED: 'Artifact added',
    ARTIFACT_REMOVED: 'Artifact removed',
}

/** sha256 shortened for a table cell; the full value is copied. */
export function teaShortSha (sha: string | null | undefined): string {
    return sha ? sha.slice(0, 12) : ''
}

/** Rows of the collection versions table, in version order. */
export function teaCollectionRows (collections: any[] | null | undefined): any[] {
    if (!Array.isArray(collections)) return []
    return [...collections]
        .sort((a, b) => (a?.version ?? 0) - (b?.version ?? 0))
        .map(c => ({
            key: c.version,
            version: c.version,
            createdDate: c.createdDate ?? null,
            createdBy: c.createdBy ?? null,
            reason: TEA_UPDATE_REASON_LABELS[c.updateReasonType] ?? c.updateReasonType ?? '',
            comment: c.updateReasonComment ?? '',
            sha256: c.documentSha256 ?? '',
            sha256Short: teaShortSha(c.documentSha256),
            artifactCount: (c.entries || []).length,
            collection: c,
        }))
}

/** The TEA URL of one artifact revision, served by the TEA surface. */
export function teaArtifactUrl (apiBase: string | null | undefined, teaUuid: string, revision: number): string {
    return apiBase ? trimBase(apiBase) + '/v1.0.0/artifact/' + teaUuid + '/' + revision : ''
}

/** Rows of one collection version's entries, in document order. */
export function teaEntryRows (collection: any, apiBase: string | null | undefined): any[] {
    const entries: any[] = collection?.entries || []
    return entries.map(e => ({
        key: e.teaUuid + ':' + e.revision,
        teaUuid: e.teaUuid,
        revision: e.revision,
        origin: e.origin === 'SYNTHETIC' ? 'Generated' : 'Uploaded',
        type: e.type ?? '',
        name: e.name ?? '',
        profile: e.sbomProfileName ?? '',
        mediaType: e.mediaType ?? '',
        checksum: (e.checksums || []).map((c: any) => c.algType + ':' + c.algValue).join(', '),
        checksums: teaEntryChecksums(e),
        teaUrl: teaArtifactUrl(apiBase, e.teaUuid, e.revision),
        externalUrl: e.url ?? '',
        download: teaEntryDownloadRef(e),
    }))
}

/** An entry's checksums, one per algorithm as frozen; an entry may carry several, or none. */
export function teaEntryChecksums (entry: any): Array<{ alg: string, value: string }> {
    const checksums: any[] = Array.isArray(entry?.checksums) ? entry.checksums : []
    return checksums
        .filter(c => typeof c?.algValue === 'string' && c.algValue !== '')
        .map(c => ({ alg: String(c.algType ?? ''), value: c.algValue }))
}

/**
 * Extensions by media type (type/subtype, lowercase, no parameters). The backend's TEA file name rule
 * (io.reliza.service.tea.TeaFileNames) holds the same table; change both together.
 */
const TEA_MEDIA_TYPE_EXTENSIONS: Record<string, string> = {
    'application/vnd.cyclonedx+json': '.cdx.json',
    'application/vnd.cyclonedx+xml': '.cdx.xml',
    'application/spdx+json': '.spdx.json',
    'application/spdx+xml': '.spdx.xml',
    'application/spdx+yaml': '.spdx.yaml',
    'text/spdx': '.spdx',
    'application/vnd.in-toto+json': '.intoto.json',
    'application/json': '.json',
    'application/xml': '.xml',
    'text/xml': '.xml',
    'application/yaml': '.yaml',
    'text/yaml': '.yaml',
    'application/pdf': '.pdf',
    'text/plain': '.txt',
    'text/markdown': '.md',
    'text/html': '.html',
    'text/csv': '.csv',
    'application/zip': '.zip',
    'application/gzip': '.gz',
    'application/x-tar': '.tar',
    'application/pgp-signature': '.asc',
}

/**
 * The extension of a media type: a known type's, else .json or .xml for a +json or +xml structured
 * suffix, else none (octet-stream, unknown or absent).
 */
export function teaMediaTypeExtension (mediaType: string | null | undefined): string {
    if (typeof mediaType !== 'string' || !mediaType.trim()) return ''
    const base = mediaType.split(';')[0].trim().toLowerCase()
    const known = TEA_MEDIA_TYPE_EXTENSIONS[base]
    if (known) return known
    if (base.endsWith('+json')) return '.json'
    if (base.endsWith('+xml')) return '.xml'
    return ''
}

/**
 * The file name TEA serves an entry's bytes under (TeaFileNames.artifactFileName):
 * {@code <teaUuid>-r<revision>} and the extension of its media type, never the artifact's own name.
 */
export function teaEntryFileName (entry: any): string {
    return entry?.teaUuid + '-r' + entry?.revision + teaMediaTypeExtension(entry?.mediaType)
}

/**
 * The manual download of an entry: the artifact row at the frozen revision, the same row and version
 * TEA serves, fetched through the manual raw download and so under the caller's own artifact download
 * permission, whatever the publication's TEA visibility. Null for an entry stored outside ReARM (its
 * bytes are at its url) and, fail closed, for an entry naming no artifact uuid or integer revision.
 */
export function teaEntryDownloadRef (entry: any): ArtifactDownloadRef | null {
    if (typeof entry?.url === 'string' && entry.url !== '') return null
    return artifactDownloadRef(entry?.artifact, entry?.revision, teaEntryFileName(entry))
}

/**
 * The History text of a TEA_PUBLICATION release event: ADDED is the first publish ("v1"),
 * CHANGED a re-publish ("v1" -> "v2", or "HIDDEN" -> "PUBLISHED v3") or a cascade child handed
 * to another product ("with <product>" -> "with <product>"), REMOVED a hide. The
 * view is accepted for the signature the other helpers share; the row carries everything.
 */
export function teaPublicationHistoryText (row: any, view?: any): string {
    switch (row?.rua) {
    case 'ADDED': return 'Published on TEA' + (row.newValue ? ': ' + row.newValue : '')
    case 'CHANGED':
        if (typeof row.oldValue === 'string' && row.oldValue.startsWith('with ')) {
            return 'Published on TEA with another product: ' + row.oldValue + ' -> ' + (row.newValue ?? '')
        }
        return 'Re-published on TEA' + (row.oldValue || row.newValue
            ? ': ' + (row.oldValue ?? '') + ' -> ' + (row.newValue ?? '') : '')
    case 'REMOVED': return 'Hidden from TEA'
    default: return [row?.rua, row?.newValue].filter(Boolean).join(' ')
    }
}

/** What the error dialog says when a TEA act's answer never arrived (round 4, T-9 point 6). */
export const TEA_ACT_GATEWAY_TEXT = 'The server is still working on this act and will finish it on its own. '
    + 'Reload the release page in a minute to see the result; repeating the act is safe.'

/**
 * The error dialog's text for a failed publish, re-publish or hide. A proxy that gave up (502,
 * 503 or 504) or a fetch that failed outright carries no GraphQL error: the act may still be
 * running on the server, so the text says so and that repeating it is safe. Anything else is
 * the server's message (PUBLISH_IN_PROGRESS among them), cleaned as every other dialog cleans it.
 * Part 6's UI (TEA-13) reuses this rather than a second string.
 */
export function teaActErrorText (err: any): string {
    const status = err?.networkError?.statusCode ?? err?.networkError?.status
    if (status === 502 || status === 503 || status === 504) return TEA_ACT_GATEWAY_TEXT
    const message = String(err?.message ?? '')
    if (/status code 50[234]\b/.test(message) || /Failed to fetch/.test(message)) return TEA_ACT_GATEWAY_TEXT
    return graphqlRefusalText(err)
}

// ---------------------------------------------------------------------------------------------
// FDAW-39 (FDAW-25 D2f): the partial-publish list, "Turn on for both", Share with customers and the
// share's result. Every name a writer chose goes through teaOneLine before it is put into a dialog,
// and only into `text`; titles and button texts are constants (SweetAlert2 renders them as HTML).

/** The longest a name may be in a dialog, in code points. */
export const TEA_NAME_MAX = 100

const BIDI_CONTROLS = /\p{Bidi_Control}/gu
const BREAKS = /[\s\p{Cc}\p{Zl}\p{Zp}]+/gu

/**
 * A name as a dialog shows it: one line, with no bidirectional control character, cut at `max`
 * code points with "…". The empty string for a value that is not a string. It does not escape markup.
 */
export function teaOneLine (value: unknown, max: number = TEA_NAME_MAX): string {
    if (typeof value !== 'string') return ''
    const line = value.replace(BIDI_CONTROLS, '').replace(BREAKS, ' ').trim()
    const points = Array.from(line)
    return points.length > max ? points.slice(0, max - 1).join('') + '…' : line
}

export const TEA_CHILD_UNNAMED = 'A component release that could not be read'

/** The act's words for a child whose publish was attempted and failed (FDAW-39 round 5, F-12). */
export const TEA_CHILD_PUBLISH_FAILED = 'publishing it on TEA failed (the server log has the cause)'

/** A child's name in a dialog: its component name and version, one line each; never its uuid. */
export function teaChildName (child: any): string {
    return [teaOneLine(child?.componentName), teaOneLine(child?.version)].filter(Boolean).join(' ') || TEA_CHILD_UNNAMED
}

/** Why a child is not published with its product, by skip outcome; SKIPPED_OTHER_PRODUCT prefers its detail. */
export const TEA_CHILD_SKIP_REASONS: Record<string, string> = {
    SKIPPED_DIRECT: 'published on its own already',
    SKIPPED_HIDDEN: 'hidden on TEA by a person',
    SKIPPED_OTHER_PRODUCT: 'published with another product',
}

/** Why a REFUSED child is not published, by refusal (the list before the click's words for GENERATION_FAILED). */
export const TEA_CHILD_REFUSAL_REASONS: Record<string, string> = {
    PUBLISHING_DISABLED: 'its TEA profile has publishing disabled',
    LIFECYCLE_TOO_LOW: 'its lifecycle is below the minimum for publishing',
    RELEASE_NOT_FOUND: 'it could not be found in this organization',
    RELEASE_ARCHIVED: 'it is archived',
    COMPONENT_ARCHIVED: 'its component is archived',
    PROFILE_CONFLICT: 'its settings conflict between perspectives',
    PROFILE_NOT_FOUND: 'an SBOM profile its TEA profile names is not defined for it',
    GENERATION_FAILED: 'it could not be checked',
}

/**
 * Why a child is left behind, in words (FDAW-39 3.9). `{ act: true }` words the act's result, where a
 * GENERATION_FAILED child was attempted and failed; every other line is the same in both lists. The
 * server's detail is never printed, except the SKIPPED_OTHER_PRODUCT one, which names the product.
 */
export function teaChildReason (child: any, opts: { act?: unknown } = {}): string {
    const outcome = child?.outcome
    if (outcome === 'SKIPPED_OTHER_PRODUCT') {
        const detail = child?.detail
        return typeof detail === 'string' && detail !== '' ? teaOneLine(detail, 200) : TEA_CHILD_SKIP_REASONS[outcome]
    }
    if (Object.prototype.hasOwnProperty.call(TEA_CHILD_SKIP_REASONS, outcome)) return TEA_CHILD_SKIP_REASONS[outcome]
    if (outcome === 'REFUSED') {
        const refusal = child?.refusal
        if (!refusal) return 'refused'
        if (refusal === 'GENERATION_FAILED' && opts?.act === true) return TEA_CHILD_PUBLISH_FAILED
        return Object.prototype.hasOwnProperty.call(TEA_CHILD_REFUSAL_REASONS, refusal)
            ? TEA_CHILD_REFUSAL_REASONS[refusal] : 'refused: ' + lowerEnum(refusal)
    }
    return lowerEnum(outcome)
}

/** What the preview's children say about a product publish (FDAW-39 3.7). */
export interface TeaPartial {
    loaded: boolean
    total: number
    notPublished: number
    lines: string[]
    offer: any[]
    hint: boolean
}

/**
 * The list before the click: one line per child that will not be published, in the list's order, and
 * the children "Turn on for both" may turn on. The offer is made to an organization admin only, and
 * only when every refused child is refused by its own disabled row whose component is known; one
 * child per row.
 */
export function teaPartialChildren (children: any[] | null | undefined,
    { isOrgAdmin, componentOf }: { isOrgAdmin?: boolean, componentOf: (release: string) => string | null | undefined }): TeaPartial {
    if (!Array.isArray(children)) return { loaded: false, total: 0, notPublished: 0, lines: [], offer: [], hint: false }
    const listed = children.filter(c => c != null)
    const left = listed.filter(c => c.outcome !== 'WILL_PUBLISH')
    const refused = listed.filter(c => c.outcome === 'REFUSED')
    const ownDisabled = (c: any) => c.refusal === 'PUBLISHING_DISABLED' && typeof c.ownRow === 'string' && c.ownRow !== ''
        && Boolean(componentOf(c.release))
    const offer: any[] = []
    if (isOrgAdmin === true && refused.length > 0 && refused.every(ownDisabled)) {
        const rows = new Set<string>()
        for (const c of refused) {
            if (rows.has(c.ownRow)) continue
            rows.add(c.ownRow)
            offer.push(c)
        }
    }
    return {
        loaded: true,
        total: listed.length,
        notPublished: left.length,
        lines: left.map(c => teaChildName(c) + ': ' + teaChildReason(c)),
        offer,
        hint: offer.length === 0 && refused.some(c => c.refusal === 'PUBLISHING_DISABLED'),
    }
}

export const TEA_TURN_ON_BOTH = 'Turn on for both'
export const TEA_PARTIAL_CONSUMER = 'Customers still see these components inside the product SBOM; they cannot open the '
    + 'component releases on TEA.'
export const TEA_PARTIAL_HINT = "To publish them too, a component or organization admin turns publishing on in each "
    + "component's TEA settings."
export const TEA_CASCADE_NONE = 'It lists no component release, so nothing is published with it.'
export const TEA_CASCADE_ALL_ONE = 'Its 1 component release is expected to be published with it.'

export function TEA_CASCADE_ALL (total: number): string {
    return 'All ' + total + ' of its component releases are expected to be published with it.'
}

export function TEA_PARTIAL_LEAD (notPublished: number, total: number): string {
    return notPublished + ' of ' + total + ' component releases will not be published with it:'
}

export function TEA_PARTIAL_OFFER (names: string): string {
    return '"' + TEA_TURN_ON_BOTH + '" turns on TEA publishing in the TEA settings of ' + names
        + ', and it stays on for their later releases; this product is then published.'
}

/** The offer's sentence: the distinct component names of the offer, the first five, then ", and <n> more". */
export function teaPartialOfferText (offer: any[]): string {
    const names: string[] = []
    for (const c of offer ?? []) {
        const name = teaOneLine(c?.componentName) || teaChildName(c)
        if (!names.includes(name)) names.push(name)
    }
    return TEA_PARTIAL_OFFER(listedNames(names))
}

export const TEA_SHARE_BUTTON = 'Share with customers'
export const TEA_SHARE_TITLE = 'Share with customers?'
export const TEA_SHARE_CONFIRM = 'Share'
export const TEA_SHARE_COMMENT = 'Shared with customers'
export const TEA_SHARE_HIDDEN = 'This release is hidden on TEA now; sharing makes it readable again.'
export const TEA_SHARE_NOT_AUTHORIZED = 'Sharing needs the Publish Externally (TEA) permission on this release. An '
    + 'organization admin can grant it.'
export const TEA_ERROR_TITLE = 'Error!'
export const TEA_NOT_PUBLISHED_TITLE = 'Not published'

/** What the share dialog says customers get and never get, and the package head's version and state. */
export function TEA_SHARE_LEAD (component: string, version: string, profiles: string[], latestVersion: any, stateLabel: string): string {
    return 'Sharing ' + component + ' ' + version + '. Customers get, on TEA: the release SBOM per profile'
        + (profiles.length ? ' (' + profiles.join(', ') + ')' : '') + ', its living VEX, and the device support statement '
        + 'when the organization text is authored. They never get the regulatory package: its '
        + PACKAGE_PART_LABELS.NARRATIVE + ', ' + PACKAGE_PART_LABELS.SUPPORT_ADDENDUM + ' and ' + PACKAGE_PART_LABELS.VDR
        + ' are not published, and the package cannot be downloaded from TEA. Package v' + latestVersion + ' is '
        + stateLabel + '.'
}

export function TEA_SHARE_PUBLIC (url: string): string {
    return 'Its TEA settings are public: anyone who knows the URL can read it without a key: ' + url
}

export function TEA_SHARE_PRIVATE (kind: string): string {
    return 'Its TEA settings are private: only a customer whose key covers this ' + kind + ' can read it.'
}

export function TEA_SHARE_NOT_ON_LIST (profiles: string, name: string): string {
    return 'Customers get the ' + profiles + ' SBOM; the package\'s ' + name + ' SBOM is not on your TEA list, so it has '
        + 'a different serial from the one the package carries. Add ' + name + ' in TEA settings to share the package\'s serial.'
}

/** The share's result when the release itself is unchanged and the result has children; the counts follow. */
export function TEA_SHARE_UNCHANGED_LEAD (version: any): string {
    return 'This release is unchanged since collection version ' + version
}

/** The share button's disabled title, by the view's refusal, in words (never the server's refusalMessage). */
export const TEA_SHARE_REFUSAL_TEXTS: Record<string, string> = {
    LIFECYCLE_TOO_LOW: 'This release has not reached the lifecycle its TEA settings require.',
    PROFILE_CONFLICT: "This release's TEA settings conflict between perspectives.",
    PROFILE_NOT_FOUND: 'An SBOM profile named in the TEA settings is not defined for this release.',
    RELEASE_ARCHIVED: 'This release is archived.',
    COMPONENT_ARCHIVED: "This release's component is archived.",
}

export function teaShareRefusalText (view: any): string {
    const refusal = view?.refusal
    if (!refusal) return 'Cannot be shared now.'
    if (Object.prototype.hasOwnProperty.call(TEA_SHARE_REFUSAL_TEXTS, refusal)) return TEA_SHARE_REFUSAL_TEXTS[refusal]
    return 'Cannot be shared: ' + lowerEnum(refusal) + '.'
}

/** The head states from which a package is shared with customers. */
export const TEA_SHARE_STATES = ['FINAL', 'SENT', 'AMENDED']

/**
 * The share button of a package head: shown to a writer of the panel for a head from Final onward
 * while the release's TEA settings publish; disabled while an act runs or while nothing is published
 * and the release cannot be published, with the reason as its title.
 */
export function teaShareButtonState (view: any, head: any, { writable, busy }: { writable?: boolean, busy?: boolean }):
    { shown: boolean, disabled: boolean, title: string } {
    const shown = Boolean(writable) && Boolean(head) && TEA_SHARE_STATES.includes(head?.state) && view?.publishing === 'ENABLED'
    const blocked = Boolean(view) && !view.canPublish && !view.publication
    return { shown, disabled: Boolean(busy) || blocked, title: blocked ? teaShareRefusalText(view) : '' }
}

/** What the share click carries: the head's kind, newest version, state and its package's SBOM profile name. */
export interface TeaShare {
    kind: string
    latestVersion: any
    state: string
    profileName: string | null
}

/**
 * The share dialog (FDAW-39 3.8): a constant title, what customers get and never get, how visible the
 * release is, a hidden publication and a package profile that is not on the TEA list, then the
 * cascade part of 3.7 for a product. Names go into `text` only, one line each.
 */
export function teaShareConfirm (view: any, names: TeaPublishNames, share: TeaShare, partial?: TeaPartial | null): Record<string, any> {
    const served: string[] = Array.isArray(view?.profiles) ? view.profiles : []
    const profiles = served.map(p => teaOneLine(p))
    const parts: string[] = [TEA_SHARE_LEAD(teaOneLine(names?.component), teaOneLine(names?.version), profiles,
        share?.latestVersion, labelOf(SUBMISSION_STATE_LABELS, share?.state))]
    const isPublic = view?.effectiveVisibility === 'PUBLIC'
    if (isPublic) parts.push(TEA_SHARE_PUBLIC(teaPublicUrlText(view)))
    else if (view?.effectiveVisibility === 'PRIVATE') parts.push(TEA_SHARE_PRIVATE(kindWord(view)))
    if (view?.publication?.state === 'HIDDEN') parts.push(TEA_SHARE_HIDDEN)
    const name = share?.profileName
    if (typeof name === 'string' && name !== '' && !served.includes(name)) {
        parts.push(TEA_SHARE_NOT_ON_LIST(profiles.join(', '), teaOneLine(name)))
    }
    const cascade = cascadePart(view, partial)
    if (cascade) parts.push(cascade.text)
    const confirm: Record<string, any> = {
        titleText: TEA_SHARE_TITLE,
        text: parts.join(' '),
        showCancelButton: true,
        confirmButtonText: TEA_SHARE_CONFIRM,
        ...(cascade?.keys ?? {}),
    }
    if (isPublic) {
        confirm.icon = 'warning'
        confirm.confirmButtonColor = RED
    }
    return confirm
}

/** A refused share's text: the missing permission for "Not authorized", else what teaActErrorText says. */
export function teaShareErrorText (err: any): string {
    const text = teaActErrorText(err)
    return text === 'Not authorized' ? TEA_SHARE_NOT_AUTHORIZED : text
}

export const TEA_TURN_ON_PUBLIC_TITLE = 'Publish this component publicly?'
export const TEA_TURN_ON_PUBLIC_BUTTON = 'Publish publicly'

export function TEA_TURN_ON_PUBLIC_TEXT (component: string, child: string): string {
    return 'The TEA settings of ' + component + ' are public, with publishing turned off. Turning publishing on makes its '
        + 'published releases readable by anyone who knows the TEA URL, without a key: a release published on its own '
        + 'always, and a release published with a product, as ' + child + ' is here, when that product\'s TEA settings '
        + 'are public too.'
}

/**
 * The red confirmation "Turn on for both" asks before it turns publishing on for a PUBLIC row (F-8),
 * whatever the dry-run says; null unless the input is PUBLIC. The focus is on Cancel.
 */
export function teaTurnOnPublicConfirm (child: any, input: Record<string, any> | null | undefined, exposure: any): Record<string, any> | null {
    if (input?.visibility !== 'PUBLIC') return null
    const component = teaOneLine(child?.componentName) || teaChildName(child)
    return {
        title: TEA_TURN_ON_PUBLIC_TITLE,
        text: TEA_TURN_ON_PUBLIC_TEXT(component, teaChildName(child)) + ' '
            + teaPublicConfirmText(exposure?.publishedReleases, exposure?.publishedVersions),
        confirmButtonText: TEA_TURN_ON_PUBLIC_BUTTON,
        confirmButtonColor: RED,
        focusCancel: true,
    }
}

/** The dialog over the page while a product's children are read (no button, no dismissal). */
export function teaChildrenReadProgress (): Record<string, any> {
    return {
        titleText: 'Checking the component releases…',
        text: 'Reading what a publish would do with each component release of this product. The publish has not started yet.',
        allowOutsideClick: false,
        allowEscapeKey: false,
        showConfirmButton: false,
    }
}

/** The dialog over the page while "Turn on for both" reads and saves settings. */
export function teaTurnOnProgress (): Record<string, any> {
    return {
        titleText: 'Turning publishing on…',
        text: 'Checking and saving the TEA settings of the listed components. The publish of this product release has not started yet.',
        allowOutsideClick: false,
        allowEscapeKey: false,
        showConfirmButton: false,
    }
}

/**
 * Whether the server answered the call with an error: an error entry with a message that is not blank
 * and is not graphql-java's null-propagation entry. False for a network failure or a gateway status.
 */
export function teaServerAnswered (err: any): boolean {
    return graphqlErrorEntries(err).some((e: any) => typeof e?.message === 'string' && e.message.trim() !== ''
        && e?.extensions?.classification !== NULL_PROPAGATION_CLASSIFICATION)
}

export const TEA_CHILDREN_READ_FAILED = "The list of this product's component releases could not be read, so nothing "
    + 'was published. Try again.'

export function teaChildrenReadErrorText (err: any): string {
    return TEA_CHILDREN_READ_FAILED + (teaServerAnswered(err) ? ' The server answered: ' + graphqlRefusalText(err) : '')
}

/** The sentence of a stop of "Turn on for both", by kind; `refusal` is the server's text of a refused one. */
export function teaTurnOnStopSentence (kind: string, child: string, refusal = ''): string {
    switch (kind) {
    case 'changed': return 'The TEA settings of ' + child + ' changed since this dialog opened, so nothing was saved for it.'
    case 'declined': return 'Stopped at ' + child + ': its confirmation was declined.'
    case 'unanswered': return 'No answer arrived for saving the TEA settings of ' + child + ', so publishing may or may '
        + 'not be turned on for it. Open its TEA settings to check.'
    default: return refusal
    }
}

const savedText = (saved: string[] | null | undefined) =>
    saved?.length ? ' Publishing stays turned on for ' + saved.join(', ') + '.' : ''

/** The stop dialog's text: the stop's sentence, the saves that stand, and that nothing was published. */
export function teaTurnOnStopText (result: { saved: string[], stop: { kind: string, child: string, text: string } | null }): string {
    return (result?.stop?.text ?? '') + savedText(result?.saved) + ' Nothing was published.'
}

export const TEA_ACT_NOT_STARTED = 'Another TEA act on this release is still running, so this one was not started.'

/** The dialog when the act was not started because another one runs, with the saves that stand. */
export function teaNotStartedText (saved: string[]): string {
    return TEA_ACT_NOT_STARTED + savedText(saved)
}
