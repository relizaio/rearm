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
import { loadWithSchemaDriftFallback } from './graphqlDriftFallback'
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

/**
 * The addendum's release query, in two shapes (decision D7).
 *
 * The device support window is declared on the PRODUCT COMPONENT now, not on the release, so
 * FULL reaches through `componentDetails` to read it. `release.eos`/`eol` are still selected
 * and still returned -- they are release lifecycle for TEA/CLE -- but they are NO LONGER the
 * source of `deviceEos`/`deviceEol`.
 *
 * **Split because CE cannot answer FULL.** CE's schema declares `Component.medicalProfile` but
 * NOT `deviceSupportWindow` inside it, so adding the subfield to a `medicalProfile` selection
 * makes the WHOLE DOCUMENT invalid there -- not just that field null. A CE build issuing FULL
 * gets a validation error and renders nothing, which is the #339 defect exactly. CORE is what
 * every backend can answer; on it the device window is simply not available and the addendum
 * reports "not declared", which is honest rather than wrong.
 */
const ADDENDUM_RELEASE_CORE_SELECTION = `
            uuid
            version
            eos
            eol
            fdaAssessmentNarrative
            componentDetails { uuid name type }`

const addendumReleaseDocument = (selection: string) => `
    query getAddendumRelease($releaseUuid: ID!, $orgUuid: ID) {
        release(releaseUuid: $releaseUuid, orgUuid: $orgUuid) {${selection}
        }
    }`

export const ADDENDUM_RELEASE_QUERY_CORE = gql`${addendumReleaseDocument(ADDENDUM_RELEASE_CORE_SELECTION)}`

export const ADDENDUM_RELEASE_QUERY_FULL = gql`${addendumReleaseDocument(
    ADDENDUM_RELEASE_CORE_SELECTION + `
            componentDetails {
                medicalProfile { deviceSupportWindow { eos eol } }
            }`)}`

/** Back-compat alias: the FULL shape is what a Pro build wants. */
export const ADDENDUM_RELEASE_QUERY = ADDENDUM_RELEASE_QUERY_FULL

/**
 * The ORGANIZATIONS LIST, filtered client-side -- not organization(orgUuid:).
 *
 * That single-organization query is declared in the schema but HAS NO RESOLVER and always
 * returns null; it appears under "Unmapped fields" in the DGS schema report at startup.
 * graphqlQueries.ts records the same trap in the comment above ORG_DEFAULT_VIEW_GQL, which
 * is where this should have been read from the first time.
 *
 * Nothing static catches it: the field exists, so validate-graphql passes and the schema
 * drift spec passes. It surfaced only when the live probe refused with "the organization
 * could not be loaded" -- which is the refusal working exactly as intended, on a bug of ours
 * rather than a server's.
 */
export const ADDENDUM_ORG_QUERY = gql`
    query getAddendumOrg {
        organizations {
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

/**
 * Where a device support window was declared, in the terms a lay reader can act on.
 *
 * The SHIPMENT case names the batch by things a person can match against a delivery note --
 * site and ship date, plus the batch identifier when one was recorded -- rather than by the
 * word "override" or a uuid. "Override" is our vocabulary, not theirs.
 */
/**
 * The refusal when a delivery has no window in force. Exported because the caller gates the
 * action on the same fact and must say the same thing when it slips through -- a row whose
 * window was retracted in another session since the page loaded.
 */
export const NO_WINDOW_IN_FORCE = 'This delivery has no device support window in force, so a'
    + ' statement generated for it would have no dates to state. Declare the window on the'
    + ' device model, or record an override on the shipment.'

export type DeviceWindowProvenance =
    | { level: 'COMPONENT', productName: string | null }
    | { level: 'SHIPMENT', siteName: string | null, shipDate: string | null, batchIdentifier: string | null }

/** The statement's provenance line, or null when no window is declared. */
export function deviceWindowProvenanceLine (
    p: DeviceWindowProvenance | null | undefined
): string | null {
    // Optional on AddendumData, so undefined reaches here from any fixture or caller that
    // predates D7. Same answer as null: no window declared, so no provenance to state.
    if (!p) return null
    if (p.level === 'COMPONENT') {
        return p.productName
            ? `These dates are declared on ${p.productName} and apply to every unit of it.`
            : 'These dates are declared on the product and apply to every unit of it.'
    }
    const where = p.siteName ? ` delivered to ${p.siteName}` : ''
    const when = p.shipDate ? ` on ${p.shipDate}` : ''
    const batch = p.batchIdentifier ? ` (batch ${p.batchIdentifier})` : ''
    return `These dates apply to the units${where}${when}${batch}, and may differ from other`
        + ' deliveries of the same device.'
}

/**
 * The shipment a statement is being generated FOR, when it is generated from a shipment
 * rather than from the release (plan 7h).
 *
 * PROVENANCE AND DATES IN ONE OBJECT, deliberately. A caller that could pass the batch
 * without its window would produce a statement that names a batch and then prints the dates
 * that batch overrode -- the one failure mode this shape exists to make unrepresentable.
 * `eos`/`eol` are the shipment's EFFECTIVE window (the override where there is one, the
 * model default where there is not), which is what the units in that delivery actually run
 * under -- and `windowSource` says which of those two it was, because the provenance LINE
 * must follow the dates. A delivery that inherited the model window is not batch-specific,
 * and saying "these dates apply to the units delivered to X, and may differ from other
 * deliveries" over inherited dates asserts a batch-specificity that does not exist -- while
 * the release-generated statement for the same device says the opposite.
 */
export interface ShipmentStatementContext {
    siteName: string | null
    shipDate: string | null
    /**
     * The batch identifiers as recorded on the shipment, joined for a reader.
     *
     * A joined string, in a module whose header forbids joining -- deliberately, and this is
     * the one place it is right: the field it feeds, DeviceWindowProvenance.batchIdentifier,
     * is already a single reader-facing string (see 7f), and re-deriving it downstream would
     * make the document's own provenance line the second source of truth for it. What the
     * header forbids is formatting facts COLLECTED FROM THE SERVER; this is a caller's
     * statement about its own row.
     */
    batchIdentifier: string | null
    eos: string | null
    eol: string | null
    /** Where the effective window was declared, from the server's resolver. */
    windowSource: DeviceWindowLevel | null
}

/** The levels a device window can be declared at, as the server's resolver reports them. */
export type DeviceWindowLevel = 'COMPONENT' | 'SHIPMENT'

export interface AddendumData {
    releaseUuid: string
    releaseVersion: string | null
    componentName: string | null
    /**
     * PRODUCT or COMPONENT.
     *
     * Already fetched by the release query and previously discarded. Kept because the Device
     * Support Statement is defined as ONE DOCUMENT PER PRODUCT RELEASE and must refuse on a
     * component release -- and a renderer that had to be told its own release's type by the
     * call site would be a second source of truth for something this collector already holds.
     */
    componentType: string | null
    /**
     * The two device facts, SEPARATE. End of support and end of sale answer different
     * questions -- when patching stops, and when selling stops -- and a single "support
     * window" cell forces a reader to guess which one a lone date is.
     */
    deviceEos: string | null
    deviceEol: string | null
    /**
     * WHERE the device window was declared, for the statement's one-line provenance (D7).
     *
     * A reader comparing two statements for the same device model needs to know why the dates
     * differ, and "a different batch declared its own" is the answer. Null when no window is
     * declared at all -- there is no provenance for a fact that does not exist.
     */
    deviceWindowSource?: DeviceWindowProvenance | null
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
    orgUuid: string,
    shipment?: ShipmentStatementContext | null
): Promise<AddendumResult> {
    // Generated from a SHIPMENT: the dates AND the provenance come from the delivery, or
    // neither does. A delivery with no effective window refuses BEFORE the walk rather than
    // falling through to the model default, which would print the model's dates under a line
    // naming a batch -- a statement that contradicts itself about which units it covers. The
    // caller offers the action only where a window resolved; this is the guard, not a
    // courtesy, and nothing about it needs the server.
    if (shipment && !shipment.eos && !shipment.eol) {
        return { ok: false, error: NO_WINDOW_IN_FORCE }
    }
    const shipmentEos = shipment ? blankToNull(shipment.eos) : null
    const shipmentEol = shipment ? blankToNull(shipment.eol) : null
    try {
        // FULL, falling back to CORE on a drift error -- not FULL alone. CE declares
        // Component.medicalProfile WITHOUT deviceSupportWindow inside it, so asking for the
        // subfield makes the WHOLE document invalid there rather than merely null: the
        // addendum would fail to generate at all on a CE build, which is precisely what the
        // CORE/FULL split exists to prevent. Issuing FULL directly made CORE dead code
        // outside its own spec, so the protection was documented but not present.
        const [releaseResp, orgResp, coverageResp] = await Promise.all([
            loadWithSchemaDriftFallback(client, {
                fullQuery: ADDENDUM_RELEASE_QUERY_FULL,
                coreQuery: ADDENDUM_RELEASE_QUERY_CORE,
                variables: { releaseUuid, orgUuid },
                extractPath: (d: any) => d
            }).then(r => ({ data: r.data })),
            client.query({ query: ADDENDUM_ORG_QUERY, variables: {}, fetchPolicy: 'network-only' }),
            loadReleaseSupportCoverage(client, orgUuid, releaseUuid)
        ])
        const release = (releaseResp.data as any)?.release
        if (!release) return { ok: false, error: 'The release could not be loaded. No addendum was generated.' }
        const orgs = (orgResp.data as any)?.organizations || []
        const org = orgs.find((o: any) => o?.uuid === orgUuid) || null
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
        // Absent on a CORE response (a CE backend cannot answer for it), which correctly
        // reports the window as "not declared" rather than inventing one.
        const deviceWindow = release.componentDetails?.medicalProfile?.deviceSupportWindow ?? null
        return {
            ok: true,
            data: {
                releaseUuid,
                releaseVersion: blankToNull(release.version),
                componentName: blankToNull(release.componentDetails?.name),
                componentType: blankToNull(release.componentDetails?.type),
                // D7: the DEVICE window comes from the product component, never from the
                // release. release.eos/eol are release lifecycle for TEA/CLE -- reading them
                // here is what made one physical device describable by a dozen different
                // end-of-support dates depending on which firmware it happened to run.
                deviceEos: shipment ? shipmentEos : blankToNull(deviceWindow?.eos),
                deviceEol: shipment ? shipmentEol : blankToNull(deviceWindow?.eol),
                // Generated from a release, so the window is the product component's -- unless
                // a shipment was named, in which case both the dates above and this line come
                // from that delivery. They move together or not at all.
                // The provenance follows the DATES, not the entry point. A delivery that
                // inherited the model window gets the model's line -- the same sentence the
                // release-generated statement carries, because it is the same fact.
                deviceWindowSource: (shipment && shipment.windowSource === 'SHIPMENT')
                    ? {
                        level: 'SHIPMENT',
                        siteName: blankToNull(shipment.siteName),
                        shipDate: blankToNull(shipment.shipDate),
                        batchIdentifier: blankToNull(shipment.batchIdentifier)
                    }
                    : (shipment || deviceWindow?.eos || deviceWindow?.eol)
                        ? { level: 'COMPONENT', productName: blankToNull(release.componentDetails?.name) }
                        : null,
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
