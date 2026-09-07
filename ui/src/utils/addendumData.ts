// One client-side assembly of everything the FDA documents are made of.
//
// DELIBERATELY DOCUMENT-AGNOSTIC. The CSV addendum is the first consumer; the PDF and the
// Device Support Statement follow and consume this UNCHANGED. That is why nothing here
// formats, rounds, joins or localises: the moment this module returns a rendered string,
// the second document either re-derives it from the raw values -- two sources of truth for
// one regulatory claim -- or inherits a shape chosen for a spreadsheet.
//
// The rule for adding to this file: collect FACTS, and let each renderer decide how to say
// them. `deviceEos` is a date string or null, never "not declared".

import gql from 'graphql-tag'
import type { DriftFallbackClient } from './graphqlDriftFallback'
// PAGE SIZE only. That reuse is uncontroversial: it is one server, one resolver, and the
// same round-trip economics. The CEILING below is deliberately NOT borrowed -- see it.
import { BULK_WALK_LIMIT } from './useBulkAttest'
// The counts come from the GAUGE's own loader, not a second copy of its query. The header
// block states "N of M assessed" and the screen states the same thing; two queries would be
// two chances for the document to disagree with the page the operator generated it from.
import { loadReleaseSupportCoverage } from './releaseSupportCoverage'

/**
 * The per-component selection the addendum needs, built by APPENDING to the fields the
 * component list already selects.
 *
 * Appending rather than text-substituting is the lesson sbomComponentsQuery.ts records in
 * its own comment: a `.replace()` keyed on an indentation match silently no-ops when the
 * source is reformatted, and the fields simply stop being requested. Here that would mean
 * an addendum whose support columns were all blank, which reads exactly like a release
 * nobody has assessed.
 */
const ADDENDUM_COMPONENT_SELECTION = `
                uuid
                name
                group
                version
                canonicalPurl
                attestationState
                attestedLevelOfSupport
                attestedLevelOfSupportText
                endOfSupportDate
                justification
                assessedAt`

export const ADDENDUM_PAGE_QUERY = gql`
    query getAddendumComponentsPage($releaseUuid: ID!, $limit: Int, $after: ID) {
        getReleaseSbomComponentsPage(releaseUuid: $releaseUuid, attestation: ALL, limit: $limit, after: $after) {
            items {
                uuid
                sbomComponentUuid
                component {${ADDENDUM_COMPONENT_SELECTION}
                }
            }
            totalCount
            endCursor
            hasMore
        }
    }`

export const ADDENDUM_RELEASE_QUERY = gql`
    query getAddendumRelease($releaseUuid: ID!, $orgUuid: ID) {
        release(releaseUuid: $releaseUuid, orgUuid: $orgUuid) {
            uuid
            version
            eos
            eol
            fdaAssessmentNarrative
            componentDetails { name type }
        }
    }`

export const ADDENDUM_ORG_QUERY = gql`
    query getAddendumOrg($orgUuid: ID!) {
        organization(orgUuid: $orgUuid) {
            uuid
            name
            settings {
                fdaAssessmentNarrative
                fdaPatchesMayCeaseStatement
                fdaRiskTransferProcessRef
                fdaRiskIncreasesNotice
            }
        }
    }`

/**
 * The most components this will assemble into a document.
 *
 * Its OWN constant, not the bulk sweep's. An earlier revision borrowed BULK_MAX_IDS and
 * justified it as "the same scope, so a release the sweep refuses this must refuse too" --
 * which is simply false. The sweep walks the operator's current FILTER and search; this
 * always walks `attestation: ALL`. On a 6,000-component release with 4,000 unattested the
 * sweep proceeds and this refuses, the opposite of what that claim asserted.
 *
 * The number bounds a different thing for a different reason: every row is held in memory
 * and rendered into a single string the browser must also hold. It is set equal to the
 * sweep's ceiling today because both are "about as much as a browser should be asked to do
 * in one go", not because either derives from the other.
 */
export const ADDENDUM_MAX_COMPONENTS = 5000

/** One component's support facts, exactly as stored. No rendering. */
export interface AddendumComponent {
    sbomComponentUuid: string | null
    name: string | null
    group: string | null
    version: string | null
    purl: string | null
    /** ATTESTED or WITHDRAWN. A WITHDRAWN attestation reads as unassessed, not as a claim. */
    attestationState: string | null
    /** FDA's phrase, verbatim and lowercase, or null when no level was attested. */
    levelOfSupport: string | null
    /**
     * The same claim as an ENUM member. Carried alongside the text because the mapping is
     * one-way by design -- schema.graphqls warns against reversing text back to the enum --
     * and a PDF renderer that wants to style or group by level needs the stable token, not
     * the sentence.
     */
    levelOfSupportEnum: string | null
    endOfSupportDate: string | null
    justification: string | null
    assessedAt: string | null
}

export interface AddendumData {
    releaseUuid: string
    releaseVersion: string | null
    componentName: string | null
    /**
     * The two device facts, SEPARATE. End of support and end of sale answer different
     * questions -- when patching stops, and when selling stops -- and a single "support
     * window" cell forces a reader to guess which one a lone date is.
     */
    deviceEos: string | null
    deviceEol: string | null
    /** Resolved through resolveNarrative: release override else org default, else null. */
    narrative: string | null
    /** True when the narrative came from the release rather than the org. */
    narrativeIsPerRelease: boolean
    orgName: string | null
    patchesMayCeaseStatement: string | null
    riskTransferProcessRef: string | null
    riskIncreasesNotice: string | null
    /** From the gauge resolver, NOT counted from the rows -- see collectAddendumData. */
    totalComponents: number
    attestedComponents: number
    unassessedComponents: number
    components: AddendumComponent[]
    generatedAt: string
}

export interface AddendumRefusal {
    ok: false
    error: string
}

export type AddendumResult = { ok: true, data: AddendumData } | AddendumRefusal

/**
 * THE narrative resolution seam, mirroring ReleaseData.resolveFdaAssessmentNarrative on the
 * server.
 *
 * Release override else org default. Every document goes through here rather than reading
 * either field: a generator reading only the org field silently ignores every override, and
 * one reading only the release field renders a blank justification section for the
 * overwhelming majority of releases, which never set one. Both produce a document that looks
 * complete.
 *
 * Blank counts as absent on both sides, matching the server, so a value that somehow got
 * stored as whitespace does not shadow a real default.
 */
export function resolveNarrative (
    release: { fdaAssessmentNarrative?: string | null } | null | undefined,
    orgSettings: { fdaAssessmentNarrative?: string | null } | null | undefined
): { narrative: string | null, perRelease: boolean } {
    const override = release?.fdaAssessmentNarrative
    if (override && override.trim()) return { narrative: override, perRelease: true }
    const orgDefault = orgSettings?.fdaAssessmentNarrative
    if (orgDefault && orgDefault.trim()) return { narrative: orgDefault, perRelease: false }
    return { narrative: null, perRelease: false }
}

function blankToNull (v: unknown): string | null {
    if (null === v || undefined === v) return null
    const s = String(v)
    return s.trim() ? s : null
}

/** Map one page row to the flat, render-free shape the documents consume. */
export function toAddendumComponent (row: any): AddendumComponent {
    const c = row?.component || {}
    return {
        sbomComponentUuid: row?.sbomComponentUuid || null,
        name: blankToNull(c.name),
        group: blankToNull(c.group),
        version: blankToNull(c.version),
        purl: blankToNull(c.canonicalPurl),
        attestationState: blankToNull(c.attestationState),
        levelOfSupport: blankToNull(c.attestedLevelOfSupportText),
        levelOfSupportEnum: blankToNull(c.attestedLevelOfSupport),
        endOfSupportDate: blankToNull(c.endOfSupportDate),
        justification: blankToNull(c.justification),
        assessedAt: blankToNull(c.assessedAt)
    }
}

/**
 * True when this component carries a LIVE attestation.
 *
 * WITHDRAWN is deliberately not live: a retracted attestation keeps its history but is not
 * injected into exports, so treating it as assessed here would make the addendum disagree
 * with the BOM the addendum accompanies.
 */
export function isLiveAttestation (c: AddendumComponent): boolean {
    return c.attestationState === 'ATTESTED'
}

/**
 * Walk the WHOLE release scope and assemble every fact the documents need.
 *
 * REFUSES rather than returning a partial. A truncated addendum is the dangerous output: it
 * is a regulatory document that looks complete and under-reports the number of components
 * whose support status is unknown, which is the single number a reviewer is looking for.
 * Every refusal path returns no data at all rather than data plus a flag, for the same
 * reason the bulk sweep does -- a caller who destructures the data and ignores the flag
 * must not be able to ship a partial document.
 *
 * Same walk limit and same ceiling as the bulk sweep, deliberately: they walk the same
 * scope with the same server, so a release the sweep refuses is one this must refuse too.
 */
export async function collectAddendumData (
    client: DriftFallbackClient,
    releaseUuid: string,
    orgUuid: string
): Promise<AddendumResult> {
    try {
        const [releaseResp, orgResp, coverageResp] = await Promise.all([
            client.query({ query: ADDENDUM_RELEASE_QUERY, variables: { releaseUuid, orgUuid }, fetchPolicy: 'network-only' }),
            client.query({ query: ADDENDUM_ORG_QUERY, variables: { orgUuid }, fetchPolicy: 'network-only' }),
            loadReleaseSupportCoverage(client, orgUuid, releaseUuid)
        ])
        const release = (releaseResp.data as any)?.release
        if (!release) return { ok: false, error: 'The release could not be loaded. No addendum was generated.' }
        const org = (orgResp.data as any)?.organization
        // Refused, symmetrically with the release above. The org carries three of the four
        // labeling statements; tolerating a null organization would emit a document that is
        // silently missing exactly what it exists to carry. A null SETTINGS is different and
        // is fine -- that just means nobody has authored the prose yet.
        if (!org) {
            return { ok: false, error: 'The organization could not be loaded, so the labeling'
                + ' statements cannot be included. No addendum was generated.' }
        }
        // loadReleaseSupportCoverage THROWS rather than returning null when the response is
        // malformed, so there is no null branch to write here -- a coverage failure lands in
        // the catch below and refuses there.
        const coverage = coverageResp

        const components: AddendumComponent[] = []
        const seen = new Set<string>()
        let after: string | null = null
        let totalCount = 0
        for (;;) {
            const resp: any = await client.query({
                query: ADDENDUM_PAGE_QUERY,
                variables: { releaseUuid, limit: BULK_WALK_LIMIT, after },
                fetchPolicy: 'network-only'
            })
            const page = resp?.data?.getReleaseSbomComponentsPage
            if (!page) return { ok: false, error: 'The component list could not be loaded. No addendum was generated.' }
            if (!components.length) totalCount = page.totalCount || 0
            if (totalCount > ADDENDUM_MAX_COMPONENTS) {
                return { ok: false, error: `This release has ${totalCount} components, more than the`
                    + ` ${ADDENDUM_MAX_COMPONENTS} a browser-side document will assemble. Generate it from a`
                    + ' narrower release, or ask for the server-rendered report.' }
            }
            const before = components.length
            for (const row of page.items || []) {
                const c = toAddendumComponent(row)
                // Deduped as we go, as the bulk sweep does. A duplicate is not a cosmetic
                // problem here: a component listed twice inflates the row count, and the
                // count is what a reviewer reads.
                if (c.sbomComponentUuid && seen.has(c.sbomComponentUuid)) continue
                if (c.sbomComponentUuid) seen.add(c.sbomComponentUuid)
                components.push(c)
            }
            // The REAL bound, on rows actually held rather than on the number the server
            // reported. totalCount is latched from the first page and is the server's own
            // claim; a walk that keeps yielding rows past it must stop regardless.
            if (components.length > ADDENDUM_MAX_COMPONENTS) {
                return { ok: false, error: `This release yielded more than the`
                    + ` ${ADDENDUM_MAX_COMPONENTS} components a browser-side document will`
                    + ' assemble. No addendum was generated.' }
            }
            if (!page.hasMore || !page.endCursor) break
            // A page that adds nothing while claiming more would loop forever. The current
            // server cannot do that; the loop must not depend on a server invariant.
            if (components.length === before) {
                return { ok: false, error: 'The server returned an empty page while reporting more'
                    + ' results. The addendum was not generated rather than being truncated.' }
            }
            // A cursor that does not ADVANCE is the same failure wearing a different mask,
            // and the empty-page guard above does not catch it: a stuck cursor that keeps
            // returning the same row appends every time, so the walk runs forever -- or, if
            // the duplicated total happens to match the gauge, produces a document listing
            // one component thousands of times that passes every other check here.
            if (page.endCursor === after) {
                return { ok: false, error: 'The server returned the same page cursor twice.'
                    + ' The addendum was not generated rather than looping.' }
            }
            after = page.endCursor
        }

        // The row count and the gauge must AGREE. They are two reads of the same scope taken
        // moments apart, so a mismatch means the BOM changed underneath the walk -- and the
        // resulting document would state a count its own rows contradict. That is worth
        // refusing over: the counts are the part a reviewer actually reads.
        // The ATTESTED count is reconciled as well, and for a sharper reason than the total.
        // isLiveAttestation reads attestationState; the gauge's numerator additionally
        // requires a MANUAL assessment source. Those agree today because MANUAL is the only
        // write path that exists -- the moment a SUPPLIER or ENRICHED writer lands they
        // diverge, and the document would say "0 attested" above a table showing levels and
        // dates. Comparing them here makes that divergence refuse instead of print.
        const liveRows = components.filter(isLiveAttestation).length
        if (coverage.attested !== liveRows) {
            return { ok: false, error: `The attestation counts do not agree (${liveRows} rows`
                + ` carry a live attestation, ${coverage.attested} reported). No addendum was`
                + ' generated.' }
        }
        if (coverage.total !== components.length) {
            return { ok: false, error: `The component list changed while the addendum was being`
                + ` assembled (${components.length} rows collected, ${coverage.total} reported).`
                + ' No addendum was generated. Try again.' }
        }

        const settings = org?.settings || null
        const resolved = resolveNarrative(release, settings)
        return {
            ok: true,
            data: {
                releaseUuid,
                releaseVersion: blankToNull(release.version),
                componentName: blankToNull(release.componentDetails?.name),
                deviceEos: blankToNull(release.eos),
                deviceEol: blankToNull(release.eol),
                narrative: resolved.narrative,
                narrativeIsPerRelease: resolved.perRelease,
                orgName: blankToNull(org?.name),
                patchesMayCeaseStatement: blankToNull(settings?.fdaPatchesMayCeaseStatement),
                riskTransferProcessRef: blankToNull(settings?.fdaRiskTransferProcessRef),
                riskIncreasesNotice: blankToNull(settings?.fdaRiskIncreasesNotice),
                totalComponents: coverage.total,
                attestedComponents: coverage.attested,
                unassessedComponents: coverage.total - coverage.attested,
                components,
                generatedAt: new Date().toISOString()
            }
        }
    } catch (err: any) {
        return { ok: false, error: err?.message || String(err) }
    }
}
