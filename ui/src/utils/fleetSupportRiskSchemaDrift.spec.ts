import { describe, it, expect, vi } from 'vitest'
import { readFileSync, existsSync } from 'fs'
import { fileURLToPath } from 'url'
import { buildSchema, validate, print, type GraphQLSchema } from 'graphql'
import {
    FLEET_RISK_QUERY_CORE, FLEET_RISK_QUERY_FULL, FLEET_RISK_ENRICHMENT_ROW_FIELDS,
    FLEET_RISK_CORE_ROW_FIELDS, FLEET_RISK_CORE_PAGE_FIELDS, FLEET_RISK_ENRICHMENT_PAGE_FIELDS,
    FLEET_RISK_UNSUPPORTED, loadDevicesAtSupportRisk,
    fleetRiskTag, releaseSourceTag, fleetWindowLabel, summarizeFleetRiskPage, fleetRiskHeadline,
    type FleetRiskRow
} from './fleetSupportRisk'
import { UNRECOGNISED_TAG } from './supportStatusTag'

/**
 * The fleet-risk documents against BOTH schemas (D7).
 *
 * Unlike the other drift specs, the honest CE assertion here is not "CORE validates": CE has
 * no `devicesAtSupportRisk` at all, so BOTH documents fail on CE today and the panel hides.
 * What this pins is the shape of that failure -- the query is what is missing, not a field
 * inside it -- because that is the fact the runtime relies on to tell "hide" (query absent,
 * CORE rejected too) from "degrade" (query present, an enrichment field rejected). The day
 * CE syncs the query, the CE test below flips and must be rewritten as the usual
 * CORE-validates / FULL-does-not pair.
 */
const CE_SCHEMA_PATH = fileURLToPath(new URL(
    '../../../backend/src/main/resources/schema/schema.graphqls', import.meta.url))
const PRO_SCHEMA_PATH = fileURLToPath(new URL(
    '../../../../rearm-core/backend/src/main/resources/schema/schema.graphqls', import.meta.url))

const ceSchema = buildSchema(readFileSync(CE_SCHEMA_PATH, 'utf8'))
const proSchema: GraphQLSchema | null = existsSync(PRO_SCHEMA_PATH)
    ? buildSchema(readFileSync(PRO_SCHEMA_PATH, 'utf8'))
    : null

const errorsAgainst = (schema: GraphQLSchema, doc: any) => validate(schema, doc).map(e => e.message)

describe('the devicesAtSupportRisk documents', () => {
    it('CE does not declare the query at all, and that is why the panel hides', () => {
        const ceText = readFileSync(CE_SCHEMA_PATH, 'utf8')
        expect(ceText).not.toMatch(/devicesAtSupportRisk/)
        for (const doc of [FLEET_RISK_QUERY_CORE, FLEET_RISK_QUERY_FULL]) {
            const errs = errorsAgainst(ceSchema, doc)
            expect(errs.length).toBeGreaterThan(0)
            // The QUERY is what CE lacks. If this ever fails on a row field instead, CE has
            // gained the query and this spec must become the CORE-validates / FULL-fails pair.
            expect(errs.join(' ')).toMatch(/Cannot query field "devicesAtSupportRisk"/)
        }
    })

    it('FULL selects every enrichment field and CORE none of them', () => {
        const full = print(FLEET_RISK_QUERY_FULL)
        const core = print(FLEET_RISK_QUERY_CORE)
        for (const f of FLEET_RISK_ENRICHMENT_ROW_FIELDS) {
            const name = f.split(' ')[0]
            expect(full).toMatch(new RegExp(`\\b${name}\\b`))
            expect(core).not.toMatch(new RegExp(`\\b${name}\\b`))
        }
        for (const f of FLEET_RISK_CORE_ROW_FIELDS) {
            expect(core).toMatch(new RegExp(`\\b${f}\\b`))
        }
        // Same split at page level: the fleet-wide at-risk count is FULL-only (#524), the
        // population size is CORE. And the #524 labels ride with the evidence, not the verdict.
        for (const f of FLEET_RISK_ENRICHMENT_PAGE_FIELDS) {
            expect(full).toMatch(new RegExp(`\\b${f}\\b`))
            expect(core).not.toMatch(new RegExp(`\\b${f}\\b`))
        }
        for (const f of FLEET_RISK_CORE_PAGE_FIELDS) expect(core).toMatch(new RegExp(`\\b${f}\\b`))
        expect(FLEET_RISK_ENRICHMENT_PAGE_FIELDS).toContain('atRiskTotal')
        expect(FLEET_RISK_ENRICHMENT_ROW_FIELDS).toContain('identifiers { idType idValue }')
        expect(FLEET_RISK_ENRICHMENT_ROW_FIELDS).toContain('siteName')
        expect(FLEET_RISK_ENRICHMENT_ROW_FIELDS).toContain('clientName')
        // The verdict is CORE: a page without it is not a fleet-risk page.
        expect(FLEET_RISK_CORE_ROW_FIELDS).toContain('risk')
        expect(FLEET_RISK_CORE_ROW_FIELDS).toContain('device')
        // The backend serialises window.source as null for this query; selecting it would
        // only feed a false "(from the product component)" clause. Pinned so nobody adds it.
        expect(full).not.toMatch(/\bsource\b/)
    })

    // it.runIf, not an early return: a silent green when rearm-core is absent is how a drift
    // spec stops covering anything without anyone noticing. Skipped is reported; green is not.
    it.runIf(proSchema)('both validate against the Pro schema', () => {
        expect(errorsAgainst(proSchema as GraphQLSchema, FLEET_RISK_QUERY_CORE)).toEqual([])
        expect(errorsAgainst(proSchema as GraphQLSchema, FLEET_RISK_QUERY_FULL)).toEqual([])
    })
})

// A client whose FULL and CORE outcomes are scripted, so each branch of the loader is
// exercised without a backend.
function scriptedClient (full: () => any, core: () => any) {
    const calls: string[] = []
    return {
        calls,
        query: async ({ query }: any) => {
            const isFull = print(query).includes('componentsDrivingRisk')
            calls.push(isFull ? 'FULL' : 'CORE')
            const out = isFull ? full() : core()
            return { data: { devicesAtSupportRisk: out } }
        }
    }
}
const validationError = () => {
    const e: any = new Error('Validation error (FieldUndefined@[devicesAtSupportRisk]) : Field \'devicesAtSupportRisk\' in type \'Query\' is undefined')
    e.errors = [{ message: e.message, extensions: { classification: 'ValidationError' } }]
    return e
}
const row = (over: Partial<FleetRiskRow> = {}): FleetRiskRow => ({
    device: 'd1', shippedProduct: 's1', site: 'site1', release: 'r1', risk: 'OK', ...over
})

describe('loadDevicesAtSupportRisk', () => {
    it('serves FULL when the backend accepts it, with the fleet-wide at-risk count and labels', async () => {
        const full = row({ componentsDrivingRisk: 2, identifiers: [{ idType: 'SERIAL', idValue: 'SN-1' }], siteName: 'Ward 3', clientName: 'Mercy' })
        const c = scriptedClient(() => ({ total: 1, atRiskTotal: 1, rows: [full] }), () => { throw new Error('unreachable') })
        const r = await loadDevicesAtSupportRisk(c, { orgUuid: 'o' })
        expect(r).toMatchObject({ supported: true, degraded: false, total: 1, atRiskTotal: 1 })
        expect(r.rows[0].componentsDrivingRisk).toBe(2)
        expect(r.rows[0]).toMatchObject({ identifiers: [{ idType: 'SERIAL', idValue: 'SN-1' }], siteName: 'Ward 3', clientName: 'Mercy' })
        expect(c.calls).toEqual(['FULL'])
    })

    /**
     * A FULL server that omits the count (or serves it as null) leaves it UNKNOWN. Coercing
     * to 0 would put "0 at risk" over a fleet the panel was never told about.
     */
    it('keeps atRiskTotal null when the server did not state it', async () => {
        const c = scriptedClient(() => ({ total: 3, rows: [row()] }), () => { throw new Error('unreachable') })
        expect((await loadDevicesAtSupportRisk(c, { orgUuid: 'o' })).atRiskTotal).toBeNull()
        const nulled = scriptedClient(() => ({ total: 3, atRiskTotal: null, rows: [row()] }), () => { throw new Error('unreachable') })
        expect((await loadDevicesAtSupportRisk(nulled, { orgUuid: 'o' })).atRiskTotal).toBeNull()
    })

    it('degrades to CORE when only an enrichment field is rejected', async () => {
        const c = scriptedClient(() => { throw validationError() }, () => ({ total: 1, rows: [row()] }))
        const r = await loadDevicesAtSupportRisk(c, { orgUuid: 'o' })
        expect(r).toMatchObject({ supported: true, degraded: true, total: 1, atRiskTotal: null })
        expect('componentsDrivingRisk' in r.rows[0]).toBe(false)
        expect('identifiers' in r.rows[0]).toBe(false)
        expect(c.calls).toEqual(['FULL', 'CORE'])
    })

    /** CORE never selects atRiskTotal; a server that echoes one anyway is not believed on a degraded page. */
    it('ignores an atRiskTotal on a degraded page', async () => {
        const c = scriptedClient(() => { throw validationError() }, () => ({ total: 1, atRiskTotal: 1, rows: [row()] }))
        expect((await loadDevicesAtSupportRisk(c, { orgUuid: 'o' })).atRiskTotal).toBeNull()
    })

    it('reports unsupported, not empty, when CORE is rejected too', async () => {
        const c = scriptedClient(() => { throw validationError() }, () => { throw validationError() })
        const r = await loadDevicesAtSupportRisk(c, { orgUuid: 'o' })
        expect(r).toEqual(FLEET_RISK_UNSUPPORTED)
        expect(r.supported).toBe(false)
    })

    /**
     * A 403 on a fleet roster is a fact the operator must see. Swallowing it into an empty
     * panel is how the fleet-drift panel above it already behaves, and it is the wrong call
     * for a report whose absence reads as "no risk".
     */
    it('surfaces auth and server errors unchanged', async () => {
        const forbidden: any = new Error('Forbidden'); forbidden.statusCode = 403
        const c = scriptedClient(() => { throw forbidden }, () => { throw new Error('unreachable') })
        await expect(loadDevicesAtSupportRisk(c, { orgUuid: 'o' })).rejects.toBe(forbidden)
        expect(c.calls).toEqual(['FULL'])
    })

    it('skips FULL once told the backend rejected it, and sends zero-based paging', async () => {
        let seen: any = null
        const c = {
            query: async ({ query, variables }: any) => { seen = { doc: print(query), variables }; return { data: { devicesAtSupportRisk: { total: 0, rows: [] } } } }
        }
        await loadDevicesAtSupportRisk(c, { orgUuid: 'o', clientUuid: '', siteUuid: 'x', page: 3, size: 999 }, { skipFull: true })
        expect(seen.doc).not.toContain('componentsDrivingRisk')
        expect(seen.variables).toEqual({ orgUuid: 'o', clientUuid: null, siteUuid: 'x', page: 3, size: 200 })
    })
})

describe('fleet-risk presentation helpers', () => {
    it('gives every verdict a visibly different tag, and never renders UNKNOWN as OK', () => {
        expect(fleetRiskTag('EOS_BEFORE_DEVICE')).toEqual({ type: 'error', label: 'EOS before device' })
        expect(fleetRiskTag('OK')).toEqual({ type: 'success', label: 'OK' })
        expect(fleetRiskTag('UNKNOWN')).toEqual({ type: 'warning', label: 'Not assessed' })
    })

    /**
     * A verdict this build does not know is a statement the server made; "Not assessed"
     * would say nobody looked. Same rule as supportTag: loud tag, console error.
     */
    it('renders an unrecognised verdict as unrecognised, not as "not assessed"', () => {
        const err = vi.spyOn(console, 'error').mockImplementation(() => {})
        try {
            for (const v of ['EOL_BEFORE_DEVICE', '', undefined, null, 42]) {
                expect(fleetRiskTag(v)).toEqual(UNRECOGNISED_TAG)
                expect(fleetRiskTag(v).label).not.toBe('Not assessed')
            }
            expect(err).toHaveBeenCalled()
        } finally { err.mockRestore() }
    })

    it('tags the release source off the enum, and rejects what it does not know', () => {
        expect(releaseSourceTag('REPORTED')).toEqual({ type: 'success', label: 'reported' })
        expect(releaseSourceTag('EXPECTED')).toEqual({ type: 'default', label: 'expected' })
        expect(releaseSourceTag(undefined)).toBeNull()
        const err = vi.spyOn(console, 'error').mockImplementation(() => {})
        try {
            expect(releaseSourceTag('PLANNED')).toBeNull()
            expect(err).toHaveBeenCalledOnce()
        } finally { err.mockRestore() }
    })

    it('labels the window without saying where it came from', () => {
        expect(fleetWindowLabel({ eos: '2031-01-31', eol: '2033-06-30' })).toBe('EOS 2031-01-31, EOL 2033-06-30')
        expect(fleetWindowLabel({ eos: '2031-01-31', eol: null })).toBe('EOS 2031-01-31, EOL not declared')
        expect(fleetWindowLabel({ eos: null, eol: null })).toBe('')
        expect(fleetWindowLabel(null)).toBe('')
        expect(fleetWindowLabel(undefined)).toBe('')
        expect(fleetWindowLabel({ eos: '2031-01-31', eol: '2033-06-30' })).not.toMatch(/from the/)
    })

    it('counts a page by verdict, keeping unrecognised verdicts out of "not assessed"', () => {
        const err = vi.spyOn(console, 'error').mockImplementation(() => {})
        try {
            const s = summarizeFleetRiskPage([row(), row({ risk: 'EOS_BEFORE_DEVICE' }), row({ risk: 'UNKNOWN' }), row({ risk: 'EOS_BEFORE_DEVICE' }), row({ risk: 'WHAT' as any })])
            expect(s).toEqual({ atRisk: 2, unknown: 1, ok: 1, unrecognised: 1 })
        } finally { err.mockRestore() }
    })

    /**
     * With the server's fleet-wide count (#524) the headline is "K at risk of M" -- K counts
     * the whole filtered fleet, whatever page is showing, so the page's own verdicts are not
     * repeated as if they were news. Unrecognised verdicts still get named, as a page fact.
     */
    it('says "K at risk of M" from the fleet-wide count when the server gave one', () => {
        expect(fleetRiskHeadline(57, [row()], 3)).toBe('3 at risk of 57 in-field units in scope')
        expect(fleetRiskHeadline(1, [row({ risk: 'EOS_BEFORE_DEVICE' })], 1)).toBe('1 at risk of 1 in-field unit in scope')
        expect(fleetRiskHeadline(57, [row()], 0)).toBe('0 at risk of 57 in-field units in scope')
        expect(fleetRiskHeadline(0, [], 0)).toBe('No in-field units to evaluate')
        // the fleet count wins over what this page happens to show
        expect(fleetRiskHeadline(57, [row({ risk: 'EOS_BEFORE_DEVICE' })], 0)).toMatch(/^0 at risk of 57/)
        const err = vi.spyOn(console, 'error').mockImplementation(() => {})
        try {
            expect(fleetRiskHeadline(2, [row({ risk: 'WHAT' as any })], 1))
                .toBe('1 at risk of 2 in-field units in scope; 1 unrecognised on this page')
        } finally { err.mockRestore() }
    })

    /**
     * Without the fleet-wide count (a CORE-served page) a partial page must never read as a
     * fleet total, so the counts are named as page counts. (Pre-#524 servers also sorted by
     * unit, not verdict, so "0 at risk" on page 1 said nothing about page 2.)
     */
    it('falls back to page counts, labelled as such, when the fleet-wide count is unknown', () => {
        expect(fleetRiskHeadline(0, [])).toBe('No in-field units to evaluate')
        expect(fleetRiskHeadline(57, [row()], null)).toMatch(/this page:/)
        expect(fleetRiskHeadline(2, [row(), row({ risk: 'EOS_BEFORE_DEVICE' })]))
            .toBe('2 in-field units in scope: 1 at risk, 0 not assessed, 1 OK')
        expect(fleetRiskHeadline(57, [row()]))
            .toBe('57 in-field units in scope; this page: 0 at risk, 0 not assessed, 1 OK')
        expect(fleetRiskHeadline(1, [row()])).toMatch(/^1 in-field unit in scope:/)
        // "in scope", never "evaluated": the server evaluates only the page it returns
        expect(fleetRiskHeadline(57, [row()])).not.toMatch(/evaluated/)
    })

    it('names unrecognised verdicts in the headline only when there are any', () => {
        expect(fleetRiskHeadline(1, [row()])).not.toMatch(/unrecognised/)
        const err = vi.spyOn(console, 'error').mockImplementation(() => {})
        try {
            expect(fleetRiskHeadline(1, [row({ risk: 'WHAT' as any })]))
                .toBe('1 in-field unit in scope: 0 at risk, 0 not assessed, 0 OK, 1 unrecognised')
        } finally { err.mockRestore() }
    })
})
