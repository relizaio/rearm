import { describe, it, expect } from 'vitest'
import { readFileSync, existsSync } from 'fs'
import { fileURLToPath } from 'url'
import { buildSchema, validate, print, type GraphQLSchema } from 'graphql'
import {
    FLEET_RISK_QUERY_CORE, FLEET_RISK_QUERY_FULL, FLEET_RISK_ENRICHMENT_ROW_FIELDS,
    FLEET_RISK_CORE_ROW_FIELDS, FLEET_RISK_UNSUPPORTED, loadDevicesAtSupportRisk,
    fleetRiskTag, summarizeFleetRiskPage, fleetRiskHeadline, type FleetRiskRow
} from './fleetSupportRisk'

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
        // The verdict is CORE: a page without it is not a fleet-risk page.
        expect(FLEET_RISK_CORE_ROW_FIELDS).toContain('risk')
        expect(FLEET_RISK_CORE_ROW_FIELDS).toContain('device')
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
    it('serves FULL when the backend accepts it', async () => {
        const c = scriptedClient(() => ({ total: 1, rows: [row({ componentsDrivingRisk: 2 })] }), () => { throw new Error('unreachable') })
        const r = await loadDevicesAtSupportRisk(c, { orgUuid: 'o' })
        expect(r).toMatchObject({ supported: true, degraded: false, total: 1 })
        expect(r.rows[0].componentsDrivingRisk).toBe(2)
        expect(c.calls).toEqual(['FULL'])
    })

    it('degrades to CORE when only an enrichment field is rejected', async () => {
        const c = scriptedClient(() => { throw validationError() }, () => ({ total: 1, rows: [row()] }))
        const r = await loadDevicesAtSupportRisk(c, { orgUuid: 'o' })
        expect(r).toMatchObject({ supported: true, degraded: true, total: 1 })
        expect('componentsDrivingRisk' in r.rows[0]).toBe(false)
        expect(c.calls).toEqual(['FULL', 'CORE'])
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
        expect(fleetRiskTag('UNKNOWN').type).not.toBe('success')
        expect(fleetRiskTag(undefined).type).not.toBe('success')
    })

    it('counts a page by verdict', () => {
        const s = summarizeFleetRiskPage([row(), row({ risk: 'EOS_BEFORE_DEVICE' }), row({ risk: 'UNKNOWN' }), row({ risk: 'EOS_BEFORE_DEVICE' })])
        expect(s).toEqual({ atRisk: 2, unknown: 1, ok: 1 })
    })

    /**
     * A partial page must never read as a fleet total: the server sorts by unit, not by
     * verdict, so "0 at risk" on page 1 says nothing about page 2.
     */
    it('labels counts as page counts unless the page is the whole population', () => {
        expect(fleetRiskHeadline(0, [])).toBe('No in-field units to evaluate')
        expect(fleetRiskHeadline(2, [row(), row({ risk: 'EOS_BEFORE_DEVICE' })]))
            .toBe('2 in-field units evaluated: 1 at risk, 0 not assessed, 1 OK')
        expect(fleetRiskHeadline(57, [row()]))
            .toBe('57 in-field units evaluated; this page: 0 at risk, 0 not assessed, 1 OK')
        expect(fleetRiskHeadline(1, [row()])).toMatch(/^1 in-field unit evaluated:/)
    })
})
