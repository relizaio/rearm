import gql from 'graphql-tag'

// SAFETY_CONTEXT import (CycloneDX 2.0 safety integrity levels). Every level is the issuer's
// declaration; verificationStatus is always UNVERIFIED.

const ENTRY_FIELDS = `
    uuid kind org proposalStatus recordStatus
    scope scopeUuid componentPurl sbomComponentUuid schemeKey blueprintRefId assetRefId
    levelRaw levelNormalized levelRank levelNote levelChange componentMostConservative
    verificationStatus issuerClassDerived issuerClassEffective originBinding issuerOverride
    overrideHonoured elevatedByOverride overrideActorName importMode gateRule notes matchStatus
    statusReason conflictFlag sourceArtifact sourceDeleted releaseUuid sourceReleaseVersion specCommit docSerialNumber
    docVersion uploadedBy actedBy actedAt acceptComment rejectReason acceptedVia acceptedByName
    acceptedAt acceptedByUploader supersededBy
    comparedRecordUuid comparedWith overridesScope overridesLevel overridesRecordUuid carried shownForPurl
    sourceRedacted appliesToRelease appliesToReleaseVersion appliesToComponent appliesToComponentName
    appliesToReleaseArchived
    sourceDocumentHash sourceComponentName sourceReleaseState rejectBatch rejectKind
`

export const GET_SAFETY_PROPOSALS = gql`
    query GetSafetyContextProposals($org: ID!, $status: ProposalStatus, $limit: Int, $offset: Int) {
        getSafetyContextProposals(org: $org, status: $status, limit: $limit, offset: $offset) { ${ENTRY_FIELDS} }
    }
`

export const GET_SAFETY_PROPOSALS_BY_RELEASE = gql`
    query GetSafetyContextProposalsByRelease($org: ID!, $release: ID!, $limit: Int, $offset: Int) {
        getSafetyContextProposalsByRelease(org: $org, release: $release, limit: $limit, offset: $offset) { ${ENTRY_FIELDS} }
    }
`

export const GET_SAFETY_RECORDS_BY_RELEASE = gql`
    query GetSafetyContextRecordsByRelease($org: ID!, $release: ID!, $includeSuperseded: Boolean, $limit: Int, $offset: Int) {
        getSafetyContextRecordsByRelease(org: $org, release: $release, includeSuperseded: $includeSuperseded,
            limit: $limit, offset: $offset) { ${ENTRY_FIELDS} }
    }
`

/**
 * The release's levels and, for a product release, each component release's levels, with the most
 * stringent level per scheme and the counts of rows left out.
 */
export const GET_SAFETY_RELEASE_VIEW = gql`
    query GetSafetyContextReleaseView($org: ID!, $release: ID!, $includeSuperseded: Boolean, $limit: Int, $offset: Int) {
        getSafetyContextReleaseView(org: $org, release: $release, includeSuperseded: $includeSuperseded,
            limit: $limit, offset: $offset) {
            release productView totalRows
            rows { ${ENTRY_FIELDS} }
            summary { schemeKey level levelRank releaseCount recordCount fromCarriedOnly unrankedLevels }
            constituentReleaseCount evaluatedReleaseCount hiddenReleaseCount hiddenLevelCount
            pendingInventoryReleaseCount complete incompleteReasons
        }
    }
`

export const GET_SAFETY_SOURCE_ASSERTION = gql`
    query GetSafetyContextSourceAssertion($uuid: ID!) {
        getSafetyContextSourceAssertion(uuid: $uuid)
    }
`

export const GET_RELEASE_SBOM_COMPONENT_CHOICES = gql`
    query GetReleaseSbomComponentChoices($releaseUuid: ID!) {
        getReleaseSbomComponents(releaseUuid: $releaseUuid) {
            sbomComponentUuid
            component { canonicalPurl }
        }
    }
`

/**
 * What accepting a pending proposal would compare it with now, for the accept dialog. The accept
 * sends baselineToken back as expectedBaseline, so a level that changed meanwhile is refused.
 */
export const GET_SAFETY_DECISION_CONTEXT = gql`
    query GetSafetyContextDecisionContext($uuid: ID!) {
        getSafetyContextDecisionContext(uuid: $uuid) {
            proposal { ${ENTRY_FIELDS} }
            levelChange comparedWith baselineDetail baselineToken
            baseline { ${ENTRY_FIELDS} }
        }
    }
`

/** Pending counts of a release and whether the caller may read its safety levels; never refused. */
export const GET_SAFETY_RELEASE_SUMMARY = gql`
    query GetSafetyContextReleaseSummary($org: ID!, $release: ID!) {
        getSafetyContextReleaseSummary(org: $org, release: $release) {
            canRead pending pendingDowngrade pendingUncomparable pendingAwaitingSbom
        }
    }
`

export const ACCEPT_SAFETY_PROPOSAL = gql`
    mutation AcceptSafetyContextProposal($uuid: ID!, $component: ID, $comment: String, $expectedBaseline: String) {
        acceptSafetyContextProposal(uuid: $uuid, component: $component, comment: $comment,
            expectedBaseline: $expectedBaseline) { uuid proposalStatus }
    }
`

/**
 * Rejects several pending proposals with one reason: a selection, or every pending level of one
 * uploaded document (which undoes that upload).
 */
export const REJECT_SAFETY_PROPOSALS = gql`
    mutation RejectSafetyContextProposals($org: ID!, $selection: SafetyContextRejectSelection!, $reason: String!) {
        rejectSafetyContextProposals(org: $org, selection: $selection, reason: $reason) {
            rejected alreadyDecided notPermitted notFound restored batch
            rows { uuid outcome status }
        }
    }
`

export const REJECT_SAFETY_PROPOSAL = gql`
    mutation RejectSafetyContextProposal($uuid: ID!, $reason: String) {
        rejectSafetyContextProposal(uuid: $uuid, reason: $reason) { uuid proposalStatus }
    }
`

/** Human labels for the trust-gate rule that decided a level. */
export const GATE_RULE_LABELS: Record<string, string> = {
    DOWNGRADE: 'Lower than the current level',
    UNCOMPARABLE: 'Not comparable with the current level',
    THIRD_PARTY_STAGE: 'Third-party issuer',
    SELF_AUTO: 'Own organization (auto-accepted)',
    VENDOR_AUTO: 'Vendor (auto-accepted)',
    VENDOR_CUSTOM: 'Vendor, custom scheme',
    VENDOR_UNMAPPED: 'Vendor, level not recognised',
    VENDOR_DECOMPOSITION: 'Vendor, ASIL decomposition',
    VENDOR_BOTTOM: 'Vendor, lowest level of the scheme',
    INTRA_ASSET_CONFLICT: 'Asset declares conflicting levels',
    UNMATCHED: 'Component not in the release SBOM',
    AMBIGUOUS: 'Component matches several SBOM entries',
    SBOM_PENDING: 'SBOM still being processed',
    WIDE_SCOPE: 'Organization or branch scope needs review'
}

/**
 * Why a proposal stands where it does. A level the gate would accept but the upload's import
 * mode staged says so, rather than showing the gate's accept reason on a pending row.
 */
export function stagedReason (row: any): string {
    if ((row.notes || []).includes('USER_MODE_STAGE')) return 'Staged by the upload\'s import mode'
    return GATE_RULE_LABELS[row.gateRule] || row.gateRule || '-'
}

/** Shown wherever accepted levels are displayed. */
export const DECLARED_NOT_VERIFIED = 'Declared by the issuer; not verified by ReARM.'

/** How the decision baseline was found, as the accept dialog says it. */
export const COMPARED_WITH_LABELS: Record<string, string> = {
    SHOWN_ON_RELEASE: 'shown on this release',
    LATEST_FOR_PACKAGE: 'latest level declared for this package',
    LATEST_ON_CHAIN: 'latest level for this package on this release\'s scopes',
    CARRIED_ON_RELEASE: 'carried from another version on this release'
}

/** Where a level applies, as the release Safety tab and the accept dialog name it. */
export const SCOPE_LABELS: Record<string, string> = {
    RELEASE: 'This release', BRANCH: 'Branch', COMPONENT: 'Component', ORG: 'Organization'
}

export function scopeLabel (scope: string): string {
    return SCOPE_LABELS[scope] || scope
}

/** The server's refusal of an accept whose baseline changed after the dialog loaded it. */
export const BASELINE_CHANGED_TEXT = 'The current level changed since you opened this proposal; review it again'

/** The status in the server's refusal of a proposal that is no longer pending, else null. */
export function notPendingStatus (message: string): string | null {
    const m = /Safety context proposal is not pending \(was ([A-Z_]+)\)/.exec(message || '')
    return m ? m[1] : null
}

/**
 * A UTC instant as "YYYY-MM-DD HH:mm UTC", built from getUTC* parts so the machine's locale and
 * time zone cannot change it; "" for null or anything that does not parse. Fractions of a second
 * longer than milliseconds (the server sends nanoseconds) are cut to milliseconds first.
 */
export function safetyInstantLabel (iso: string | null | undefined): string {
    if (typeof iso !== 'string' || !/^\d{4}-\d{2}-\d{2}T/.test(iso)) return ''
    const d = new Date(iso.replace(/(\.\d{3})\d+/, '$1'))
    if (isNaN(d.getTime())) return ''
    const two = (n: number) => String(n).padStart(2, '0')
    return `${d.getUTCFullYear()}-${two(d.getUTCMonth() + 1)}-${two(d.getUTCDate())} `
        + `${two(d.getUTCHours())}:${two(d.getUTCMinutes())} UTC`
}

/**
 * The release a level was uploaded on: "<component> <version>", "(archived)" when archived, or
 * why it cannot be named. A withheld source (NOT_VISIBLE) says so even when the server also
 * withheld the release id.
 */
export function sourceReleaseLabel (row: any): string {
    if (row?.sourceReleaseState === 'NOT_VISIBLE') return 'not visible to you'
    if (!row?.releaseUuid) return '-'
    const name = [row.sourceComponentName, row.sourceReleaseVersion].filter(Boolean).join(' ')
    switch (row.sourceReleaseState) {
    case 'AVAILABLE': return name
    case 'ARCHIVED': return `${name} (archived)`
    case 'DELETED': return 'release no longer exists'
    default: return '-'
    }
}

/** The link of sourceReleaseLabel: only a release the caller may open. */
export function sourceReleaseHref (row: any): string | null {
    if (!row?.releaseUuid) return null
    return row.sourceReleaseState === 'AVAILABLE' || row.sourceReleaseState === 'ARCHIVED'
        ? `/release/show/${row.releaseUuid}` : null
}

/** Inline result lines of a bulk reject: the first always (unless everything is 0), others when not 0. */
export function bulkRejectResultLines (r: any): string[] {
    const n = (k: string) => Number(r?.[k] ?? 0)
    if (!n('rejected') && !n('alreadyDecided') && !n('notPermitted') && !n('notFound') && !n('restored')) {
        return ['Nothing to reject: no pending levels from this document.']
    }
    const lines = [`Rejected: ${n('rejected')}.`]
    if (n('alreadyDecided')) lines.push(`Already decided by someone else, left as they are: ${n('alreadyDecided')}.`)
    if (n('notPermitted')) {
        lines.push(`Not permitted (needs Finding Analysis write at the level's scope), left pending: ${n('notPermitted')}.`)
    }
    if (n('notFound')) lines.push(`Not found: ${n('notFound')}.`)
    if (n('restored')) lines.push(`Earlier pending levels this upload had replaced, back in review: ${n('restored')}.`)
    return lines
}

/** The most proposals one bulk reject takes (the server refuses more with the same text). */
export const MAX_BULK_REJECT = 500
export const MAX_BULK_REJECT_TEXT = 'Select at most 500 proposals at a time'

/** The body of the bulk reject modal for a selection of n levels. */
export function bulkRejectSelectionText (n: number): string {
    return `Reject the ${n} selected levels. Rejected levels stay rejected: the same level uploaded again by the same `
        + 'uploader is not staged again.'
}

/** The body of the bulk reject modal that undoes the upload of row's document. */
export function bulkRejectDocumentText (row: any): string {
    const doc = (row?.docSerialNumber || 'this uploaded document')
        + (row?.docVersion !== null && row?.docVersion !== undefined && row?.docVersion !== '' ? ` v${row.docVersion}` : '')
    return `Undo the upload of the document ${doc}, uploaded on ${sourceReleaseLabel(row)}: reject every pending level `
        + 'from it, including levels not listed on this page. Pending levels from an earlier upload that this one '
        + 'replaced come back to review. Uploading a corrected document afterwards stages its levels again. Levels this '
        + 'upload accepted automatically stay in force until a corrected level is reviewed.'
}

/** The status tag of a proposal row; an undone upload's rejection says so. */
export function proposalStatusText (row: any): string {
    return row?.proposalStatus === 'REJECTED' && row?.rejectKind === 'UPLOAD_UNDONE'
        ? 'REJECTED (upload undone)' : row?.proposalStatus
}

/** The pending badge texts of a release summary; empty when there is nothing to show. */
export function pendingBadge (s: any): { pending: string, downgrades: string | null, tooltip: string } | null {
    if (!s || s.canRead !== true || !s.pending || s.pending <= 0) return null
    const cap = (n: number) => (n > 999 ? '999+' : String(n))
    const d = Number(s.pendingDowngrade ?? 0)
    return {
        pending: `${cap(s.pending)} pending`,
        downgrades: d > 0 ? `${cap(d)} ${d === 1 ? 'downgrade' : 'downgrades'}` : null,
        tooltip: `${s.pending} pending proposals uploaded to this release: ${d} lower than the current level, `
            + `${Number(s.pendingUncomparable ?? 0)} not comparable, ${Number(s.pendingAwaitingSbom ?? 0)} waiting for the `
            + 'SBOM to finish processing. Counts use the comparison made when each level was staged.'
    }
}

/** "Accepted" of the accept dialog's current level: who or how, and when. */
export function baselineAcceptedText (baseline: any, detail: string | null | undefined): string {
    const when = safetyInstantLabel(baseline?.acceptedAt)
    if (detail === 'LEVEL_ONLY') return `not visible to you, on ${when}`
    switch (baseline?.acceptedVia) {
    case 'AUTO_SELF': return `automatically (own organization) on ${when}`
    case 'AUTO_VENDOR': return `automatically (vendor) on ${when}`
    default: return `${baseline?.acceptedByName || 'a user no longer in the organization'} on ${when}`
    }
}
