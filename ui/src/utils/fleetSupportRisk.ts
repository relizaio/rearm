import gql from 'graphql-tag'
import type { DocumentNode } from 'graphql'
import { isSchemaDriftError, loadWithSchemaDriftFallback, type DriftFallbackClient } from './graphqlDriftFallback'
import { DEVICE_RISK_LABEL, isDeviceRiskFlagged, type SupportTagType } from './supportStatusTag'

/**
 * THE FLEET QUESTION (D7), read-only: which in-field units outlive their software.
 *
 * <p>`devicesAtSupportRisk` is Pro-only today -- CE declares the Distribution surface but not
 * this query -- so the panel that renders it must be able to HIDE, not just degrade. The
 * document is still split CORE/FULL the way every other Pro-leading read is: CORE selects the
 * verdict and the ids, FULL adds the evidence (which release was judged, the window in force,
 * the soonest component EOS, how many components drive the verdict). When CE gains the query
 * it will gain the CORE shape first; the panel then renders rows with the evidence columns
 * blank instead of blanking outright.
 *
 * <p>Enrichment fields are read behind a presence guard by the caller, same rule as
 * notificationInboxQuery.ts: on a CORE-served page they are absent, not null.
 */
export const FLEET_RISK_CORE_ROW_FIELDS = ['device', 'shippedProduct', 'site', 'release', 'risk']

export const FLEET_RISK_ENRICHMENT_ROW_FIELDS = [
    'releaseSource', 'window { eos eol source }', 'earliestComponentEos', 'componentsDrivingRisk'
]

function buildFleetRiskQuery (rowFields: string[]): DocumentNode {
    return gql`
        query devicesAtSupportRisk($orgUuid: ID!, $clientUuid: ID, $siteUuid: ID, $page: Int, $size: Int) {
            devicesAtSupportRisk(orgUuid: $orgUuid, clientUuid: $clientUuid, siteUuid: $siteUuid, page: $page, size: $size) {
                total
                rows { ${rowFields.join(' ')} }
            }
        }`
}

export const FLEET_RISK_QUERY_CORE = buildFleetRiskQuery(FLEET_RISK_CORE_ROW_FIELDS)
export const FLEET_RISK_QUERY_FULL = buildFleetRiskQuery([...FLEET_RISK_CORE_ROW_FIELDS, ...FLEET_RISK_ENRICHMENT_ROW_FIELDS])

/** The server's page size cap; asking for more is silently clamped, so do not pretend otherwise. */
export const FLEET_RISK_MAX_PAGE_SIZE = 200
export const FLEET_RISK_DEFAULT_PAGE_SIZE = 20

export type DeviceSupportRisk = 'OK' | 'EOS_BEFORE_DEVICE' | 'UNKNOWN'
export type EvaluatedRelease = 'REPORTED' | 'EXPECTED'

export interface FleetRiskRow {
    device: string
    shippedProduct: string | null
    site: string | null
    release: string | null
    risk: DeviceSupportRisk
    // Enrichment (FULL only): absent on a CORE-served page.
    releaseSource?: EvaluatedRelease | null
    window?: { eos?: string | null, eol?: string | null, source?: string | null } | null
    earliestComponentEos?: string | null
    componentsDrivingRisk?: number | null
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
}

export const FLEET_RISK_UNSUPPORTED: FleetRiskResult = { supported: false, degraded: false, rows: [], total: 0 }

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
            total: typeof data?.total === 'number' ? data.total : 0
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
 */
export function fleetRiskTag (risk: unknown): { type: SupportTagType, label: string } {
    if (isDeviceRiskFlagged(risk)) return { type: 'error', label: DEVICE_RISK_LABEL[risk] }
    if (risk === 'OK') return { type: 'success', label: 'OK' }
    return { type: 'warning', label: 'Not assessed' }
}

export interface FleetRiskPageSummary {
    atRisk: number
    unknown: number
    ok: number
}

/** Counts over the rows in hand -- one PAGE, which the caller must label as such. */
export function summarizeFleetRiskPage (rows: FleetRiskRow[]): FleetRiskPageSummary {
    const s: FleetRiskPageSummary = { atRisk: 0, unknown: 0, ok: 0 }
    for (const r of rows) {
        if (isDeviceRiskFlagged(r.risk)) s.atRisk++
        else if (r.risk === 'OK') s.ok++
        else s.unknown++
    }
    return s
}

/**
 * The one-line headline above the table. Says how many units were evaluated in total and,
 * when the page is the whole population, the exact at-risk count; otherwise it names the
 * count as a page count so a partial view never reads as a fleet total.
 */
export function fleetRiskHeadline (total: number, rows: FleetRiskRow[]): string {
    if (total === 0) return 'No in-field units to evaluate'
    const s = summarizeFleetRiskPage(rows)
    const units = `${total} in-field unit${total === 1 ? '' : 's'} evaluated`
    const verdicts = `${s.atRisk} at risk, ${s.unknown} not assessed, ${s.ok} OK`
    return rows.length >= total
        ? `${units}: ${verdicts}`
        : `${units}; this page: ${verdicts}`
}
