import { describe, it, expect, vi } from 'vitest'
import { resolveNarrative, toAddendumComponent, isLiveAttestation, collectAddendumData,
    ADDENDUM_PAGE_QUERY, ADDENDUM_RELEASE_QUERY, ADDENDUM_ORG_QUERY,
    ADDENDUM_MAX_COMPONENTS } from './addendumData'
import { BULK_WALK_LIMIT } from './useBulkAttest'

describe('resolveNarrative', () => {
    // The seam every document reads through. Reading either stored field directly is the
    // rework it exists to prevent -- both failure modes produce a plausible document.
    it('prefers the per-release override', () => {
        expect(resolveNarrative({ fdaAssessmentNarrative: 'device' }, { fdaAssessmentNarrative: 'org' }))
            .toEqual({ narrative: 'device', perRelease: true })
    })

    it('falls back to the org default', () => {
        expect(resolveNarrative({ fdaAssessmentNarrative: null }, { fdaAssessmentNarrative: 'org' }))
            .toEqual({ narrative: 'org', perRelease: false })
    })

    it('treats a blank override as absent, so it cannot shadow a real default', () => {
        expect(resolveNarrative({ fdaAssessmentNarrative: '   ' }, { fdaAssessmentNarrative: 'org' }))
            .toEqual({ narrative: 'org', perRelease: false })
    })

    it('returns null when neither level has one, so callers can refuse to render', () => {
        expect(resolveNarrative({}, {})).toEqual({ narrative: null, perRelease: false })
        expect(resolveNarrative(null, null)).toEqual({ narrative: null, perRelease: false })
    })

    it('reports the SCOPE, which the document states beside the text', () => {
        expect(resolveNarrative({ fdaAssessmentNarrative: 'x' }, null).perRelease).toBe(true)
        expect(resolveNarrative(null, { fdaAssessmentNarrative: 'x' }).perRelease).toBe(false)
    })
})

describe('toAddendumComponent', () => {
    it('flattens a page row without rendering anything', () => {
        expect(toAddendumComponent({
            sbomComponentUuid: 'sc-1',
            component: {
                name: 'log4j-core', group: 'org.apache', version: '2.14.1',
                canonicalPurl: 'pkg:maven/org.apache/log4j-core@2.14.1',
                attestationState: 'ATTESTED', attestedLevelOfSupport: 'ACTIVELY_MAINTAINED',
                attestedLevelOfSupportText: 'actively maintained',
                endOfSupportDate: '2030-01-01', justification: null, assessedAt: '2026-09-01T00:00:00Z'
            }
        })).toEqual({
            sbomComponentUuid: 'sc-1', name: 'log4j-core', group: 'org.apache', version: '2.14.1',
            purl: 'pkg:maven/org.apache/log4j-core@2.14.1', attestationState: 'ATTESTED',
            levelOfSupport: 'actively maintained', levelOfSupportEnum: 'ACTIVELY_MAINTAINED',
            endOfSupportDate: '2030-01-01',
            justification: null, assessedAt: '2026-09-01T00:00:00Z'
        })
    })

    it('normalises blank strings to null so a renderer can tell absent from empty', () => {
        const c = toAddendumComponent({ component: { name: '  ', justification: '' } })
        expect(c.name).toBeNull()
        expect(c.justification).toBeNull()
    })

    it('survives a row with no component object at all', () => {
        expect(toAddendumComponent({}).name).toBeNull()
        expect(toAddendumComponent(null).purl).toBeNull()
    })
})

describe('isLiveAttestation', () => {
    // WITHDRAWN keeps its history but is not injected into exports, so counting it as
    // assessed would make the addendum disagree with the BOM it accompanies.
    it('counts ATTESTED only', () => {
        expect(isLiveAttestation({ attestationState: 'ATTESTED' } as any)).toBe(true)
        expect(isLiveAttestation({ attestationState: 'WITHDRAWN' } as any)).toBe(false)
        expect(isLiveAttestation({ attestationState: null } as any)).toBe(false)
    })
})

// ---- the walk ----

function componentRow (i: number, state = 'ATTESTED') {
    return { sbomComponentUuid: `sc-${i}`, component: {
        name: `c${i}`, version: '1.0.0', canonicalPurl: `pkg:generic/c${i}@1.0.0`,
        attestationState: state, attestedLevelOfSupport: 'ACTIVELY_MAINTAINED',
        attestedLevelOfSupportText: 'actively maintained',
        endOfSupportDate: '2030-01-01', justification: null, assessedAt: '2026-09-01T00:00:00Z'
    } }
}

/**
 * A client that answers each document. `pages` is consumed in order for the paged walk.
 */
function fakeClient (opts: {
    pages?: any[], release?: any, org?: any, coverage?: any, throwOn?: string
}) {
    const pages = [...(opts.pages || [])]
    return {
        query: vi.fn(async ({ query, variables }: any) => {
            if (opts.throwOn && query === (({ page: ADDENDUM_PAGE_QUERY, release: ADDENDUM_RELEASE_QUERY,
                org: ADDENDUM_ORG_QUERY } as any)[opts.throwOn])) {
                throw new Error('boom')
            }
            if (query === ADDENDUM_RELEASE_QUERY) return { data: { release: 'release' in opts ? opts.release : { uuid: 'r1' } } }
            if (query === ADDENDUM_ORG_QUERY) {
                const o = 'org' in opts ? opts.org : { uuid: 'o1' }
                return { data: { organizations: o ? [o] : [] } }
            }
            if (query === ADDENDUM_PAGE_QUERY) {
                const page = pages.shift()
                if (page === undefined) throw new Error('walked past the end of the fixture')
                return { data: { getReleaseSbomComponentsPage: page } }
            }
            // the coverage document, reached through loadReleaseSupportCoverage
            return { data: { sbomComponentSupportCoverage: opts.coverage ?? { total: 0, attested: 0, supportExportState: 'FULL' } } }
        })
    }
}

const okRelease = { uuid: 'r1', version: '1.4.5', eos: '2030-06-30', eol: '2033-01-01',
    fdaAssessmentNarrative: null, componentDetails: { name: 'Pump', type: 'PRODUCT' } }
const okOrg = { uuid: 'o1', name: 'Acme', settings: { fdaAssessmentNarrative: 'org words' } }

describe('collectAddendumData', () => {
    it('collects one page and reports the facts unrendered', async () => {
        const client = fakeClient({
            release: okRelease, org: okOrg, coverage: { total: 2, attested: 2 },
            pages: [{ items: [componentRow(1), componentRow(2)], totalCount: 2, endCursor: null, hasMore: false }]
        })
        const res = await collectAddendumData(client as any, 'r1', 'o1')
        expect(res.ok).toBe(true)
        if (!res.ok) return
        expect(res.data.components).toHaveLength(2)
        expect(res.data.deviceEos).toBe('2030-06-30')
        expect(res.data.deviceEol).toBe('2033-01-01')
        expect(res.data.narrative).toBe('org words')
        expect(res.data.narrativeIsPerRelease).toBe(false)
        // Fetched by the query and previously discarded. The Device Support Statement is one
        // document per PRODUCT release and must refuse on a component release.
        expect(res.data.componentType).toBe('PRODUCT')
        expect(res.data.unassessedComponents).toBe(0)
    })

    it('follows the cursor across pages', async () => {
        const client = fakeClient({
            release: okRelease, org: okOrg, coverage: { total: 3, attested: 3 },
            pages: [
                { items: [componentRow(1), componentRow(2)], totalCount: 3, endCursor: 'sc-2', hasMore: true },
                { items: [componentRow(3)], totalCount: 3, endCursor: null, hasMore: false }
            ]
        })
        const res = await collectAddendumData(client as any, 'r1', 'o1')
        expect(res.ok).toBe(true)
        if (!res.ok) return
        expect(res.data.components.map(c => c.sbomComponentUuid)).toEqual(['sc-1', 'sc-2', 'sc-3'])
    })

    it('walks with the same page size the bulk sweep uses', async () => {
        const client = fakeClient({
            release: okRelease, org: okOrg, coverage: { total: 1, attested: 1 },
            pages: [{ items: [componentRow(1)], totalCount: 1, endCursor: null, hasMore: false }]
        })
        await collectAddendumData(client as any, 'r1', 'o1')
        const pageCall = client.query.mock.calls.find((c: any) => c[0].query === ADDENDUM_PAGE_QUERY)
        expect(pageCall[0].variables.limit).toBe(BULK_WALK_LIMIT)
    })

    // A truncated addendum is the dangerous output: a document that looks complete and
    // under-reports how many components are unassessed.
    it('REFUSES above the sweep ceiling rather than truncating', async () => {
        const client = fakeClient({
            release: okRelease, org: okOrg, coverage: { total: ADDENDUM_MAX_COMPONENTS + 1, attested: 0 },
            pages: [{ items: [componentRow(1)], totalCount: ADDENDUM_MAX_COMPONENTS + 1, endCursor: 'sc-1', hasMore: true }]
        })
        const res = await collectAddendumData(client as any, 'r1', 'o1')
        expect(res.ok).toBe(false)
        if (res.ok) return
        expect(res.error).toContain(String(ADDENDUM_MAX_COMPONENTS))
    })

    it('REFUSES on an empty page that claims more, rather than looping forever', async () => {
        const client = fakeClient({
            release: okRelease, org: okOrg, coverage: { total: 5, attested: 0 },
            pages: [{ items: [], totalCount: 5, endCursor: 'x', hasMore: true }]
        })
        const res = await collectAddendumData(client as any, 'r1', 'o1')
        expect(res.ok).toBe(false)
    })

    // The counts are what a reviewer actually reads. A document stating a number its own
    // rows contradict is worse than no document.
    it('REFUSES when the gauge total and the collected rows disagree', async () => {
        const client = fakeClient({
            release: okRelease, org: okOrg, coverage: { total: 9, attested: 1 },
            pages: [{ items: [componentRow(1)], totalCount: 9, endCursor: null, hasMore: false }]
        })
        const res = await collectAddendumData(client as any, 'r1', 'o1')
        expect(res.ok).toBe(false)
        if (res.ok) return
        expect(res.error).toContain('1 rows collected, 9 reported')
    })

    // A schema-drift error means the server could not serve the support selection. There is
    // deliberately NO narrower fallback: a degraded document would be missing exactly the
    // columns the addendum exists to carry, and would look like an unassessed release.
    it('REFUSES when a query throws, returning no data at all', async () => {
        const client = fakeClient({ release: okRelease, org: okOrg, coverage: { total: 1, attested: 1 },
            throwOn: 'page',
            pages: [{ items: [componentRow(1)], totalCount: 1, endCursor: null, hasMore: false }] })
        const res = await collectAddendumData(client as any, 'r1', 'o1')
        expect(res.ok).toBe(false)
        expect((res as any).data).toBeUndefined()
    })

    it('REFUSES when the release cannot be loaded', async () => {
        const client = fakeClient({ release: null, org: okOrg, coverage: { total: 0, attested: 0 }, pages: [] })
        const res = await collectAddendumData(client as any, 'r1', 'o1')
        expect(res.ok).toBe(false)
    })

    it('prefers a per-release narrative and says so', async () => {
        const client = fakeClient({
            release: { ...okRelease, fdaAssessmentNarrative: 'device words' },
            org: okOrg, coverage: { total: 0, attested: 0 },
            pages: [{ items: [], totalCount: 0, endCursor: null, hasMore: false }]
        })
        const res = await collectAddendumData(client as any, 'r1', 'o1')
        expect(res.ok).toBe(true)
        if (!res.ok) return
        expect(res.data.narrative).toBe('device words')
        expect(res.data.narrativeIsPerRelease).toBe(true)
    })

    it('reports unassessed as total minus attested, from the gauge', async () => {
        const client = fakeClient({
            release: okRelease, org: okOrg, coverage: { total: 2, attested: 1 },
            pages: [{ items: [componentRow(1), componentRow(2, 'WITHDRAWN')], totalCount: 2, endCursor: null, hasMore: false }]
        })
        const res = await collectAddendumData(client as any, 'r1', 'o1')
        expect(res.ok).toBe(true)
        if (!res.ok) return
        expect(res.data.unassessedComponents).toBe(1)
    })

    // PDF-READY: the next PR consumes this unchanged, so nothing here may be CSV-shaped.
    it('returns raw facts, never rendered text', async () => {
        const client = fakeClient({
            release: { ...okRelease, eos: null, eol: null }, org: okOrg,
            coverage: { total: 0, attested: 0 },
            pages: [{ items: [], totalCount: 0, endCursor: null, hasMore: false }]
        })
        const res = await collectAddendumData(client as any, 'r1', 'o1')
        expect(res.ok).toBe(true)
        if (!res.ok) return
        expect(res.data.deviceEos).toBeNull()
        expect(res.data.deviceEol).toBeNull()
        // No renderer-specific vocabulary may leak into the collected data: 'not declared'
        // and 'not assessed' are things the CSV SAYS, and a PDF may well word them
        // differently. If they appear here, the second document inherits a spreadsheet's
        // phrasing or re-derives them and the two drift.
        const serialised = JSON.stringify(res.data)
        expect(serialised).not.toContain('not declared')
        expect(serialised).not.toContain('not assessed')
        expect(serialised).not.toContain('\r\n')
    })

    // LAYER 1 HIGH, proved with a throwaway spec before it was fixed: a cursor that does not
    // advance was walked 200 times, and because the duplicated row count happened to equal
    // the gauge total, the reconciliation PASSED -- ok:true, a regulatory document listing
    // one component 200 times. With a non-matching total the same input never terminated at
    // all: frozen tab, spinner stuck, finally never reached.
    it('REFUSES a cursor that does not advance, rather than looping', async () => {
        // DISTINCT rows each time, so the dedupe and empty-page guards do not fire first and
        // this actually exercises the cursor check.
        let n = 0
        const client = fakeClient({
            release: okRelease, org: okOrg, coverage: { total: 200, attested: 200 },
            pages: Array.from({ length: 50 }, () => ({
                get items () { return [componentRow(++n)] },
                totalCount: 200, endCursor: 'same', hasMore: true
            }))
        })
        const res = await collectAddendumData(client as any, 'r1', 'o1')
        expect(res.ok).toBe(false)
        if (res.ok) return
        expect(res.error).toContain('same page cursor twice')
    })

    // The identical-rows form of the same failure, which the dedupe catches one guard
    // earlier. Asserted separately because BOTH inputs must refuse -- before the fix this
    // one produced ok:true with the same component listed 200 times, because the duplicated
    // row count happened to equal the gauge total.
    it('REFUSES a stuck cursor that repeats the same row', async () => {
        const client = fakeClient({
            release: okRelease, org: okOrg, coverage: { total: 200, attested: 200 },
            pages: Array.from({ length: 50 }, () => ({
                items: [componentRow(1)], totalCount: 200, endCursor: 'same', hasMore: true
            }))
        })
        const res = await collectAddendumData(client as any, 'r1', 'o1')
        expect(res.ok).toBe(false)
    })

    it('dedupes a component repeated across pages rather than inflating the count', async () => {
        const client = fakeClient({
            release: okRelease, org: okOrg, coverage: { total: 2, attested: 2 },
            pages: [
                { items: [componentRow(1), componentRow(2)], totalCount: 2, endCursor: 'a', hasMore: true },
                { items: [componentRow(1)], totalCount: 2, endCursor: null, hasMore: false }
            ]
        })
        const res = await collectAddendumData(client as any, 'r1', 'o1')
        expect(res.ok).toBe(true)
        if (!res.ok) return
        expect(res.data.components).toHaveLength(2)
    })

    // The ceiling now bounds rows actually held, not just the server's self-reported total.
    it('REFUSES when the rows collected exceed the ceiling regardless of totalCount', async () => {
        const many = Array.from({ length: 600 }, (_, i) => componentRow(i))
        const client = fakeClient({
            release: okRelease, org: okOrg, coverage: { total: 1, attested: 1 },
            pages: Array.from({ length: 12 }, (_, p) => ({
                items: many.map((r, i) => componentRow(p * 600 + i)),
                totalCount: 1, endCursor: `cur-${p}`, hasMore: true
            }))
        })
        const res = await collectAddendumData(client as any, 'r1', 'o1')
        expect(res.ok).toBe(false)
        if (res.ok) return
        expect(res.error).toContain(String(ADDENDUM_MAX_COMPONENTS))
    })

    // isLiveAttestation reads attestationState; the gauge additionally requires a MANUAL
    // source. They agree today, and this makes the day they stop agreeing loud.
    it('REFUSES when the attested count disagrees with the live rows', async () => {
        const client = fakeClient({
            release: okRelease, org: okOrg, coverage: { total: 2, attested: 2 },
            pages: [{ items: [componentRow(1), componentRow(2, 'WITHDRAWN')], totalCount: 2,
                endCursor: null, hasMore: false }]
        })
        const res = await collectAddendumData(client as any, 'r1', 'o1')
        expect(res.ok).toBe(false)
        if (res.ok) return
        expect(res.error).toContain('attestation counts do not agree')
    })

    // The org carries three of the four labeling statements; tolerating a null organization
    // would emit a document silently missing what it exists to carry.
    // REGRESSION: this used organization(orgUuid:), which is declared in the schema but has
    // NO RESOLVER and always returns null. Nothing static caught it -- the field exists, so
    // validate-graphql and the drift spec both passed. Only the live probe did, by refusing.
    it('reads the org from the organizations LIST, which has a resolver', async () => {
        const client = fakeClient({
            release: okRelease, org: okOrg, coverage: { total: 0, attested: 0 },
            pages: [{ items: [], totalCount: 0, endCursor: null, hasMore: false }]
        })
        const res = await collectAddendumData(client as any, 'r1', 'o1')
        expect(res.ok).toBe(true)
        if (!res.ok) return
        expect(res.data.orgName).toBe('Acme')
    })

    it('picks the right org out of a multi-org list', async () => {
        const client = {
            query: async ({ query }: any) => {
                if (query === ADDENDUM_RELEASE_QUERY) return { data: { release: okRelease } }
                if (query === ADDENDUM_ORG_QUERY) return { data: { organizations: [
                    { uuid: 'other', name: 'Wrong Org', settings: { fdaAssessmentNarrative: 'wrong' } },
                    okOrg
                ] } }
                if (query === ADDENDUM_PAGE_QUERY) return { data: { getReleaseSbomComponentsPage: { items: [], totalCount: 0, endCursor: null, hasMore: false } } }
                return { data: { sbomComponentSupportCoverage: { total: 0, attested: 0, supportExportState: 'FULL' } } }
            }
        }
        const res = await collectAddendumData(client as any, 'r1', 'o1')
        expect(res.ok).toBe(true)
        if (!res.ok) return
        expect(res.data.orgName).toBe('Acme')
        expect(res.data.narrative).toBe('org words')
    })

    it('REFUSES when the organization cannot be loaded', async () => {
        const client = fakeClient({ release: okRelease, org: null,
            coverage: { total: 0, attested: 0 },
            pages: [{ items: [], totalCount: 0, endCursor: null, hasMore: false }] })
        const res = await collectAddendumData(client as any, 'r1', 'o1')
        expect(res.ok).toBe(false)
    })

    // A null SETTINGS is different from a null org: nobody has authored the prose yet.
    it('accepts an organization with no settings authored', async () => {
        const client = fakeClient({ release: okRelease, org: { uuid: 'o1', name: 'Acme' },
            coverage: { total: 0, attested: 0 },
            pages: [{ items: [], totalCount: 0, endCursor: null, hasMore: false }] })
        const res = await collectAddendumData(client as any, 'r1', 'o1')
        expect(res.ok).toBe(true)
        if (!res.ok) return
        expect(res.data.narrative).toBeNull()
        expect(res.data.patchesMayCeaseStatement).toBeNull()
    })

    // loadReleaseSupportCoverage throws rather than returning null, so this is the path a
    // real coverage failure takes.
    it('REFUSES when the coverage query throws', async () => {
        const client = {
            query: async ({ query }: any) => {
                if (query === ADDENDUM_RELEASE_QUERY) return { data: { release: okRelease } }
                if (query === ADDENDUM_ORG_QUERY) return { data: { organization: okOrg } }
                if (query === ADDENDUM_PAGE_QUERY) {
                    return { data: { getReleaseSbomComponentsPage: { items: [], totalCount: 0, endCursor: null, hasMore: false } } }
                }
                return { data: { sbomComponentSupportCoverage: null } }
            }
        }
        const res = await collectAddendumData(client as any, 'r1', 'o1')
        expect(res.ok).toBe(false)
    })

    // PDF-readiness: the FDA text cannot be mapped back to the enum (the schema says so
    // explicitly), so a renderer wanting to group or style by level needs the token.
    it('carries the level as an enum token alongside the FDA phrase', async () => {
        const client = fakeClient({
            release: okRelease, org: okOrg, coverage: { total: 1, attested: 1 },
            pages: [{ items: [componentRow(1)], totalCount: 1, endCursor: null, hasMore: false }]
        })
        const res = await collectAddendumData(client as any, 'r1', 'o1')
        expect(res.ok).toBe(true)
        if (!res.ok) return
        expect(res.data.components[0].levelOfSupportEnum).toBe('ACTIVELY_MAINTAINED')
        expect(res.data.components[0].levelOfSupport).toBe('actively maintained')
    })
})
