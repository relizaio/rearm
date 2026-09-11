import gql from 'graphql-tag'
import type { DocumentNode } from 'graphql'
import { isSchemaDriftError, loadWithSchemaDriftFallback, type DriftFallbackClient } from './graphqlDriftFallback'
import {
    DEVICE_RISK_LABEL, UNRECOGNISED_TAG, isDeviceRiskFlagged,
    type FlaggedDeviceRisk, type SupportTagType
} from './supportStatusTag'

/**
 * THE FLEET QUESTION (D7), read-only: which in-field units outlive their software.
 *
 * <p>`devicesAtSupportRisk` is Pro-only today -- CE declares the Distribution surface but not
 * this query -- so the panel that renders it must be able to HIDE, not just degrade. The
 * document is still split CORE/FULL the way every other Pro-leading read is: CORE selects the
 * verdict and the ids, FULL adds the evidence (which release was judged, the window in force,
 * the soonest component EOS, how many components drive the verdict) and the labels (the
 * unit's identifiers, the site and client names) plus the fleet-wide at-risk count. When CE
 * gains the query it will gain the CORE shape first; the panel then renders rows with the
 * evidence and label columns blank instead of blanking outright.
 *
 * <p>Enrichment fields are read behind a presence guard by the caller, same rule as
 * notificationInboxQuery.ts: on a CORE-served page they are absent, not null.
 *
 * <p>Rows arrive at-risk-first (flagged, then not assessed, then OK; unit id as tie-break)
 * from the server, across the WHOLE filtered fleet, not just the page: the panel renders
 * them in the order served and must not re-sort.
 */
export const FLEET_RISK_CORE_ROW_FIELDS: string[] = ['device', 'shippedProduct', 'site', 'release', 'risk']

// `window` deliberately selects no `source`: Pro's resolver serialises it as null for this
// query (the level a window was declared at lives on the unit, not the row), and a label
// that attributes a null source to "the product component" is a false provenance claim.
export const FLEET_RISK_ENRICHMENT_ROW_FIELDS: string[] = [
    'releaseSource', 'window { eos eol }', 'earliestComponentEos', 'componentsDrivingRisk',
    'identifiers { idType idValue }', 'siteName', 'clientName'
]

/** Page-level fields. CORE has the population size only; FULL adds the fleet-wide at-risk count. */
export const FLEET_RISK_CORE_PAGE_FIELDS: string[] = ['total']
export const FLEET_RISK_ENRICHMENT_PAGE_FIELDS: string[] = ['atRiskTotal']

function buildFleetRiskQuery (pageFields: string[], rowFields: string[]): DocumentNode {
    return gql`
        query devicesAtSupportRisk($orgUuid: ID!, $clientUuid: ID, $siteUuid: ID, $page: Int, $size: Int) {
            devicesAtSupportRisk(orgUuid: $orgUuid, clientUuid: $clientUuid, siteUuid: $siteUuid, page: $page, size: $size) {
                ${pageFields.join(' ')}
                rows { ${rowFields.join(' ')} }
            }
        }`
}

export const FLEET_RISK_QUERY_CORE: DocumentNode = buildFleetRiskQuery(FLEET_RISK_CORE_PAGE_FIELDS, FLEET_RISK_CORE_ROW_FIELDS)
export const FLEET_RISK_QUERY_FULL: DocumentNode = buildFleetRiskQuery(
    [...FLEET_RISK_CORE_PAGE_FIELDS, ...FLEET_RISK_ENRICHMENT_PAGE_FIELDS],
    [...FLEET_RISK_CORE_ROW_FIELDS, ...FLEET_RISK_ENRICHMENT_ROW_FIELDS])

/** The server's page size cap; asking for more is silently clamped, so do not pretend otherwise. */
export const FLEET_RISK_MAX_PAGE_SIZE = 200
export const FLEET_RISK_DEFAULT_PAGE_SIZE = 20

// Derived from the flagged set, not re-listed: supportStatusTag.ts owns which verdicts flag.
export type DeviceSupportRisk = FlaggedDeviceRisk | 'OK' | 'UNKNOWN'
export type EvaluatedRelease = 'REPORTED' | 'EXPECTED'

/**
 * Which release the verdict was computed against, as a tag. Keyed on the union so the
 * comparison never happens on a string literal in an un-type-checked .vue render.
 */
export const RELEASE_SOURCE_TAG: Record<EvaluatedRelease, { type: SupportTagType, label: string }> = {
    REPORTED: { type: 'success', label: 'reported' },
    EXPECTED: { type: 'default', label: 'expected' }
}
export function releaseSourceTag (source: unknown): { type: SupportTagType, label: string } | null {
    if (typeof source === 'string' && Object.prototype.hasOwnProperty.call(RELEASE_SOURCE_TAG, source)) {
        return RELEASE_SOURCE_TAG[source as EvaluatedRelease]
    }
    if (source != null) console.error('unrecognised EvaluatedRelease from the server:', source)
    return null
}

export interface FleetRiskRow {
    device: string
    shippedProduct: string | null
    site: string | null
    release: string | null
    risk: DeviceSupportRisk
    // Enrichment (FULL only): absent on a CORE-served page.
    releaseSource?: EvaluatedRelease | null
    window?: { eos?: string | null, eol?: string | null } | null
    earliestComponentEos?: string | null
    componentsDrivingRisk?: number | null
    // Labels (FULL only): the unit's own identifiers, and the names of the site / client the
    // unit is placed at. Name fields are null when the unit has none OR when it is archived.
    identifiers?: FleetRiskIdentifier[] | null
    siteName?: string | null
    clientName?: string | null
}

/**
 * The wire enum `IdentifierType`, mirrored as a union for the same reason as
 * `DeviceSupportRisk` above: a `.vue` render comparing `idType` to the DISPLAY spelling
 * ('UDI-DI', as the shipment form labels it) instead of the wire spelling ('UDI_DI') is a
 * silent no-match with no compiler and no linter to catch it. Pinned against both schemas
 * by fleetSupportRiskSchemaDrift.spec.ts, so a new identifier type cannot land unmirrored.
 */
export type IdentifierType =
    | 'PURL' | 'CPE' | 'TEI' | 'COMPLIANCE_DOCUMENT'
    | 'UDI' | 'UDI_DI' | 'UDI_PI' | 'SERIAL' | 'LOT'
    | 'SWID' | 'SWHID' | 'OMNIBORID' | 'GTIN' | 'GMN' | 'MPN'
    | 'PART_NUMBER' | 'MODEL_NUMBER' | 'SKU' | 'ASSET_TAG'
    | 'FCC_ID' | 'IMEI' | 'MAC_ADDRESS'
export const IDENTIFIER_TYPES: IdentifierType[] = [
    'PURL', 'CPE', 'TEI', 'COMPLIANCE_DOCUMENT',
    'UDI', 'UDI_DI', 'UDI_PI', 'SERIAL', 'LOT',
    'SWID', 'SWHID', 'OMNIBORID', 'GTIN', 'GMN', 'MPN',
    'PART_NUMBER', 'MODEL_NUMBER', 'SKU', 'ASSET_TAG',
    'FCC_ID', 'IMEI', 'MAC_ADDRESS'
]

export interface FleetRiskIdentifier {
    idType: IdentifierType
    idValue: string
}

export interface FleetRiskFilter {
    orgUuid: string
    clientUuid?: string | null
    siteUuid?: string | null
    /** Zero-based, as the server counts. */
    page?: number
    size?: number
}

export interface FleetRiskResult {
    /** False on a backend whose schema lacks the query -- the panel must hide, not error. */
    supported: boolean
    /** True when only the CORE selection was served: rows render, evidence columns are blank. */
    degraded: boolean
    rows: FleetRiskRow[]
    /** The whole in-field fleet matching the filter, not this page. */
    total: number
    /**
     * Flagged units across the whole filtered fleet, not this page. Null when unknown: a
     * CORE-served page, or a server that omitted it. Never 0 by default -- an unknown count
     * rendered as "0 at risk" is the panel asserting a fleet fact it was not told.
     */
    atRiskTotal: number | null
}

export const FLEET_RISK_UNSUPPORTED: FleetRiskResult = { supported: false, degraded: false, rows: [], total: 0, atRiskTotal: null }

/**
 * Load one page, tolerating a backend that trails the schema.
 *
 * FULL first; a validation rejection retries CORE (flagged `degraded`); a validation
 * rejection of CORE too means the query itself is absent and the result is `unsupported`.
 * Transport, auth and server errors are NOT swallowed -- a 403 on a fleet roster is a fact
 * the operator must see, not a reason to quietly show an empty panel.
 */
export async function loadDevicesAtSupportRisk (
    client: DriftFallbackClient,
    filter: FleetRiskFilter,
    opts: { skipFull?: boolean } = {}
): Promise<FleetRiskResult> {
    const size = Math.min(filter.size ?? FLEET_RISK_DEFAULT_PAGE_SIZE, FLEET_RISK_MAX_PAGE_SIZE)
    const variables = {
        orgUuid: filter.orgUuid,
        clientUuid: filter.clientUuid || null,
        siteUuid: filter.siteUuid || null,
        page: Math.max(0, filter.page ?? 0),
        size
    }
    try {
        const { data, degraded } = await loadWithSchemaDriftFallback(client, {
            fullQuery: FLEET_RISK_QUERY_FULL,
            coreQuery: FLEET_RISK_QUERY_CORE,
            variables,
            extractPath: d => d?.devicesAtSupportRisk,
            skipFull: opts.skipFull
        })
        return {
            supported: true,
            degraded,
            rows: (data?.rows ?? []).filter(Boolean),
            total: typeof data?.total === 'number' ? data.total : 0,
            atRiskTotal: !degraded && typeof data?.atRiskTotal === 'number' ? data.atRiskTotal : null
        }
    } catch (e: any) {
        if (isSchemaDriftError(e)) return FLEET_RISK_UNSUPPORTED
        throw e
    }
}

/**
 * The tag for one unit's verdict. Unlike the per-component badge on the release page, a fleet
 * roster shows EVERY verdict: an operator scanning a page of units needs OK and "not assessed"
 * to be visibly different, because a unit with no window declared is not a safe unit -- it is
 * one nobody has looked at.
 *
 * A verdict this build does not know is NOT "not assessed" either -- same rule as supportTag:
 * the server stated something, and rendering it as the absence of a statement is the UI
 * asserting a disclosure fact. Loud tag, console error, the other rows still render.
 */
export function fleetRiskTag (risk: unknown): { type: SupportTagType, label: string } {
    if (isDeviceRiskFlagged(risk)) return { type: 'error', label: DEVICE_RISK_LABEL[risk] }
    if (risk === 'OK') return { type: 'success', label: 'OK' }
    if (risk === 'UNKNOWN') return { type: 'warning', label: 'Not assessed' }
    console.error('unrecognised DeviceSupportRisk from the server:', risk,
        '- rendering it as "Not assessed" would assert nobody looked.')
    return UNRECOGNISED_TAG
}

/**
 * Why a UNIT is flagged. DEVICE_RISK_DETAIL is written for the per-component badge ("this
 * component stops receiving support..."); on a roster row there is no single component in
 * view, so the sentence has to speak of the release the unit runs. Keyed on the same union
 * so a second flagged verdict cannot ship without roster wording.
 */
export const FLEET_RISK_DETAIL: Record<FlaggedDeviceRisk, string> = {
    EOS_BEFORE_DEVICE: 'At least one attested component in the release this unit runs stops'
        + ' receiving support before the unit\'s declared support window ends. "Components'
        + ' driving risk" counts them; "Earliest component EOS" is the date that decided it.'
}

/**
 * The window that applied to a unit, WITHOUT a provenance clause: this query does not say
 * where the window came from, and effectiveWindowLabel would attribute it to the product
 * component by default. See FLEET_RISK_ENRICHMENT_ROW_FIELDS.
 */
export function fleetWindowLabel (w: { eos?: string | null, eol?: string | null } | null | undefined): string {
    if (!w || (!w.eos && !w.eol)) return ''
    return `EOS ${w.eos || 'not declared'}, EOL ${w.eol || 'not declared'}`
}

export interface FleetRiskPageSummary {
    atRisk: number
    unknown: number
    ok: number
    /** Verdicts this build does not know; never folded into `unknown` (see fleetRiskTag). */
    unrecognised: number
}

/** Counts over the rows in hand -- one PAGE, which the caller must label as such. */
export function summarizeFleetRiskPage (rows: FleetRiskRow[]): FleetRiskPageSummary {
    const s: FleetRiskPageSummary = { atRisk: 0, unknown: 0, ok: 0, unrecognised: 0 }
    for (const r of rows) {
        if (isDeviceRiskFlagged(r.risk)) s.atRisk++
        else if (r.risk === 'OK') s.ok++
        else if (r.risk === 'UNKNOWN') s.unknown++
        else s.unrecognised++
    }
    return s
}

/**
 * The one-line headline above the table: "K at risk of M in-field units", where K is the
 * server's count over the WHOLE filtered fleet (it evaluates every unit in scope on each
 * request and serves them at-risk-first, so K is a fleet fact, not a page fact).
 *
 * When K is unknown -- a CORE-served page -- falls back to page counts: says how many units
 * are in scope and, when the page is the whole population, the exact verdict counts;
 * otherwise names the counts as page counts so a partial view never reads as a fleet total.
 */
export function fleetRiskHeadline (total: number, rows: FleetRiskRow[], atRiskTotal: number | null = null): string {
    if (total === 0) return 'No in-field units to evaluate'
    const s = summarizeFleetRiskPage(rows)
    const units = `${total} in-field unit${total === 1 ? '' : 's'} in scope`
    if (typeof atRiskTotal === 'number') {
        // "0 at risk" is not "clean": a unit with no declared window is one nobody has
        // looked at (see fleetRiskTag). The server does not count those fleet-wide, so they
        // are named as the page fact they are -- and the page is at-risk-first, so a page
        // carrying unassessed units is a page that has run out of flagged ones.
        const caveats = [
            s.unknown ? `${s.unknown} not assessed` : '',
            s.unrecognised ? `${s.unrecognised} unrecognised` : ''
        ].filter(Boolean)
        return `${atRiskTotal} at risk of ${units}`
            + (caveats.length ? `; ${caveats.join(', ')} on this page` : '')
    }
    const verdicts = `${s.atRisk} at risk, ${s.unknown} not assessed, ${s.ok} OK`
        + (s.unrecognised ? `, ${s.unrecognised} unrecognised` : '')
    return rows.length >= total
        ? `${units}: ${verdicts}`
        : `${units}; this page: ${verdicts}`
}
