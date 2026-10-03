// TEA publications (task TEA-5): the pure logic of the release page's TEA actions and section --
// the confirmations before publish, re-publish and hide, the summary after one, the red banner,
// the collection and entry rows, the History text of a TEA_PUBLICATION event. Kept out of
// ReleaseView.vue, with no Vue import, so teaPublication.spec.ts runs it.

import { teaSourceLabel } from './teaProfile'

/** What the confirmation names: the release, and the perspective names a profile source may point at. */
export interface TeaPublishNames {
    component: string
    version: string
    perspectives?: Record<string, string>
}

/** The Swal options of a confirmation; showCancelButton is always set, so Cancel sends nothing. */
export interface TeaConfirm {
    title: string
    text: string
    icon?: 'warning'
    showCancelButton: true
    confirmButtonText: string
    confirmButtonColor?: string
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
 * The confirmation before Publish (no publication yet) or Re-publish (a PUBLISHED or HIDDEN one).
 * PUBLIC asks with the warning icon and the red button and names the URL anyone can read; a
 * product whose profile publishes its component releases with it says how many.
 */
export function teaPublishConfirm (view: any, names: TeaPublishNames): TeaConfirm {
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
    if (view?.kind === 'PRODUCT_RELEASE' && view?.cascades) {
        const n = view.cascadeChildren ?? 0
        parts.push(n === 1
            ? 'Its 1 component release is published with it unless its own TEA profile forbids it.'
            : 'Its ' + n + ' component releases are published with it unless their own TEA profile forbids it.')
    }
    const confirm: TeaConfirm = {
        title: verb + ' ' + (names?.component ?? '') + ' ' + (names?.version ?? '') + ' on TEA?',
        text: parts.join(' '),
        showCancelButton: true,
        confirmButtonText: verb,
    }
    if (isPublic) {
        confirm.icon = 'warning'
        confirm.confirmButtonColor = RED
    }
    return confirm
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

export const TEA_RIDE_ALONG_SKIP_LABELS: Record<string, string> = {
    SKIPPED_NO_RAW_BYTES: 'no retained raw bytes',
    SKIPPED_NO_CHECKSUM: 'no checksum',
    SKIPPED_VERSION_NOT_INTEGER: 'version is not an integer',
    SKIPPED_SIGNATURE_TYPE: 'signature, published with its artifact',
    SKIPPED_NO_DOWNLOAD_LINK: 'no download link',
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

function aggregateRevision (result: any): number | null {
    const entries: any[] = result?.collection?.entries || []
    const agg = entries.find(e => e && e.artifact === result?.publication?.aggregateArtifact)
        ?? entries.find(e => e && e.origin === 'SYNTHETIC')
    return agg ? agg.revision : null
}

function childName (c: any): string {
    return [c?.componentName, c?.version].filter(Boolean).join(' ') || c?.release || ''
}

/**
 * The text of the success dialog after a publish or re-publish: the collection version, the
 * aggregate SBOM revision, the ride-along counts with the skip reasons, and for a product the
 * component release counts with each skip and refusal named.
 */
export function teaPublishSummary (result: any): string {
    const version = result?.collection?.version ?? result?.publication?.latestVersion
    if (result?.outcome === 'UNCHANGED') return 'Nothing changed since collection version ' + version
    const lead = result?.outcome === 'REPUBLISHED' ? 'Re-published' : 'Published'
    const parts: string[] = [lead + ': collection version ' + version]
    const revision = aggregateRevision(result)
    if (revision != null) parts.push('aggregate SBOM revision ' + revision)

    const artifacts: any[] = result?.artifacts || []
    const published = artifacts.filter(a => a?.status === 'PUBLISHED').length
    const skipped = artifacts.filter(a => a?.status !== 'PUBLISHED')
    let artifactPart = published + ' artifacts published, ' + skipped.length + ' skipped'
    if (skipped.length) {
        const counts = new Map<string, number>()
        for (const a of skipped) {
            const label = TEA_RIDE_ALONG_SKIP_LABELS[a.status] ?? a.status
            counts.set(label, (counts.get(label) ?? 0) + 1)
        }
        artifactPart += ' (' + [...counts].map(([label, n]) => n + ' ' + label).join(', ') + ')'
    }
    parts.push(artifactPart)

    const children: any[] = result?.children || []
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
        mediaType: e.mediaType ?? '',
        checksum: (e.checksums || []).map((c: any) => c.algType + ':' + c.algValue).join(', '),
        teaUrl: teaArtifactUrl(apiBase, e.teaUuid, e.revision),
        externalUrl: e.url ?? '',
    }))
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
