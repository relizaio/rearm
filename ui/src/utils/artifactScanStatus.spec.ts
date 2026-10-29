import { beforeEach, describe, expect, it } from 'vitest'
import {
    ARTIFACT_SCAN_STATUS_QUERY, ENRICHMENT_FACT_TEXT, ageText, blockerRows, candidateLine, dateCell, enrichmentCell,
    enrichmentFactOf, enrichmentGatedText, enrichmentNote, factRows, formatInstant, loadArtifactScanStatus, reasonLabel,
    resetScanStatusSupportForTests, scanStatusSupported, stateLine, truncatedNote
} from './artifactScanStatus'
import type { ArtifactScanStatus, ScanBlocker } from './artifactScanStatus'
import { isDriftRejected } from './graphqlDriftFallback'

// TPF-122 (architecture-3 section 5, rows 19 and 20): the probing loader and the pure helpers.

// The validationError() fixture of apiKeyGroupsQuery.spec.ts.
function validationError (field = 'artifactScanStatus'): any {
    const err: any = new Error(`Validation error of type FieldUndefined: Field '${field}' is undefined`)
    err.errors = [{
        message: `Validation error of type FieldUndefined: Field '${field}' is undefined`,
        extensions: { classification: 'ValidationError' },
    }]
    return err
}

const rejecting = (e: any) => ({ query: async () => { throw e } })

const NOW = Date.parse('2026-10-29T12:00:00Z')

const base: ArtifactScanStatus = {
    artifactUuid: 'a1',
    type: 'BOM',
    state: 'SCANNED',
    mapped: true,
    canonicalArtifactUuid: 'c0ffee00-0000-4000-8000-000000000001',
    mappedAt: '2026-10-28T09:00:00Z',
    firstScanned: '2026-10-28T10:00:00Z',
    lastScanned: '2026-10-29T10:00:00Z',
    fanOutAt: '2026-10-28T10:00:00.123456789Z',
    fanOutRequeuedAt: null,
    newestIngestedBucketUpdate: '2026-10-29T09:00:00Z',
    ingestedBucketCount: 0,
    enrichmentGated: null,
    inCandidateSet: false,
    candidateRule: 'not a candidate: fanned out at 2026-10-28T10:00:00.123456789Z and no INGESTED bucket holding one of its components moved since',
    matchableComponentCount: 0,
    rootComponentCount: 0,
    terminalComponentCount: 0,
    unmatchableComponentCount: 0,
    noMatchableComponentsReason: null,
    blockerTotal: 0,
    blockersTruncated: false,
    blockers: []
}

const UNMAPPED_SENTENCE = 'no canonical map row yet: the post-upload reconcile has not mapped this artifact; a user with write access to this release can map it now with Reconcile under SBOM Components on this page (BOM Components tab); otherwise the scheduled sweep maps a BOM that every reconcile missed once it is 90 minutes old'

const rowA: ScanBlocker = {
    purl: 'pkg:npm/a@1', enrichment: 'UNENRICHED', terminalReason: null, bucketIndex: null,
    bucketIngestState: null, bucketLastUpdatedDate: null, reason: 'not enriched yet; submitOrg ships only enriched components'
}
const rowB: ScanBlocker = {
    purl: 'pkg:npm/b@2', enrichment: 'ENRICHED', terminalReason: null, bucketIndex: 7,
    bucketIngestState: 'SUBMITTED', bucketLastUpdatedDate: '2026-10-29T09:30:00Z',
    reason: 'bucket 7 is SUBMITTED since 2026-10-29T09:30:00Z; its components are uncovered until it is INGESTED'
}

const fmt = (iso: string) => new Date(iso).toLocaleString('en-CA', { hour12: false })

beforeEach(resetScanStatusSupportForTests)

describe('loadArtifactScanStatus (row 19)', () => {
    it('(a) returns the status and supported true, uncached, and sets the latch', async () => {
        const calls: any[] = []
        const client = { query: async (opts: any) => { calls.push(opts); return { data: { artifactScanStatus: base } } } }
        expect(await loadArtifactScanStatus(client, 'a1')).toEqual({ status: base, supported: true })
        expect(calls).toHaveLength(1)
        expect(calls[0].query).toBe(ARTIFACT_SCAN_STATUS_QUERY)
        expect(calls[0].variables).toEqual({ artifactUuid: 'a1' })
        expect(calls[0].fetchPolicy).toBe('no-cache')
        expect(scanStatusSupported.value).toBe(true)
    })

    it('(b) a null field with no error is supported with no status', async () => {
        const client = { query: async () => ({ data: { artifactScanStatus: null } }) }
        expect(await loadArtifactScanStatus(client, 'a1')).toEqual({ status: null, supported: true })
    })

    it('(c) a drift-shaped error answers supported false, sets the latch false and marks the document', async () => {
        expect(await loadArtifactScanStatus(rejecting(validationError()), 'a1')).toEqual({ status: null, supported: false })
        expect(scanStatusSupported.value).toBe(false)
        expect(isDriftRejected(ARTIFACT_SCAN_STATUS_QUERY)).toBe(true)
    })

    it('(d) rethrows a network error and leaves the latch alone', async () => {
        const e = new Error('Failed to fetch')
        await expect(loadArtifactScanStatus(rejecting(e), 'a1')).rejects.toBe(e)
        expect(scanStatusSupported.value).toBeNull()
    })

    it('(e) rethrows a refusal and leaves the latch alone, also after a success', async () => {
        const e: any = new Error('Not authorized')
        e.errors = [{ message: 'Not authorized', extensions: { classification: 'DataFetchingException' } }]
        await expect(loadArtifactScanStatus(rejecting(e), 'a1')).rejects.toBe(e)
        expect(scanStatusSupported.value).toBeNull()
        await loadArtifactScanStatus({ query: async () => ({ data: { artifactScanStatus: base } }) }, 'a1')
        expect(scanStatusSupported.value).toBe(true)
        await expect(loadArtifactScanStatus(rejecting(e), 'a1')).rejects.toBe(e)
        expect(scanStatusSupported.value).toBe(true)
    })

    it('(f) the reset returns the latch to null', () => {
        scanStatusSupported.value = false
        resetScanStatusSupportForTests()
        expect(scanStatusSupported.value).toBeNull()
    })

    it('(g) the document is the query artifactScanStatus', () => {
        const def: any = ARTIFACT_SCAN_STATUS_QUERY.definitions[0]
        expect(def.name.value).toBe('artifactScanStatus')
        expect(def.operation).toBe('query')
    })
})

describe('pure helpers (row 20)', () => {
    it('(a) stateLine for each of the six states', () => {
        const notABom = stateLine({ ...base, state: 'NOT_A_BOM', type: 'TEST_REPORT' })
        expect(notABom.label).toBe('Not an SBOM')
        expect(notABom.tone).toBe('default')
        expect(notABom.text).toBe('no synthetic scan runs on this artifact type (TEST_REPORT)')

        const unmapped = stateLine({ ...base, state: 'UNMAPPED' })
        expect(unmapped.label).toBe('Not mapped yet')
        expect(unmapped.tone).toBe('warning')
        expect(unmapped.text).toBe(UNMAPPED_SENTENCE)
        expect(unmapped.text).toMatch(/Reconcile/)
        expect(unmapped.text).toMatch(/SBOM Components/)
        expect(unmapped.text).not.toMatch(/Trigger Enrichment/)
        expect(unmapped.text).not.toMatch(/re-upload/i)
        expect(unmapped.text).not.toMatch(/administrator/i)
        expect(unmapped.text).not.toMatch(/nothing on this page/)

        const blocked = stateLine({ ...base, state: 'BLOCKED_ON_COVERAGE', blockerTotal: 3, matchableComponentCount: 12 })
        expect(blocked).toEqual({
            label: 'Blocked on coverage', tone: 'warning',
            text: '3 of 12 matchable components are not covered by an ingested Dependency-Track bucket'
        })

        expect(stateLine({ ...base, state: 'WAITING_FOR_FAN_OUT', inCandidateSet: true }))
            .toEqual({ label: 'Waiting for fan-out', tone: 'info', text: 'coverage complete; qualifies for a fan-out pass' })
        expect(stateLine({ ...base, state: 'WAITING_FOR_FAN_OUT', inCandidateSet: false }).text).toBe('coverage complete; not a candidate')

        const scanned = stateLine(base)
        expect(scanned.label).toBe('Scanned')
        expect(scanned.tone).toBe('success')
        const scannedText = `first scanned ${fmt(base.firstScanned!)}, last scanned ${fmt(base.lastScanned!)}`
        expect(scanned.text).toBe(scannedText)
        expect(stateLine({ ...base, blockerTotal: 2 }).text).toBe(`${scannedText}; 2 components block the next refresh`)
        expect(stateLine({ ...base, noMatchableComponentsReason: 'NO_PURL_OR_CPE' }).text)
            .toBe(`${scannedText}; scan done, but no component was scannable (components carry no purl or cpe)`)
        expect(stateLine({ ...base, firstScanned: null }).text).toMatch(/^first scanned unknown, /)

        const none = stateLine({ ...base, state: 'NO_MATCHABLE_COMPONENTS', noMatchableComponentsReason: 'ONLY_ROOT', rootComponentCount: 1 })
        expect(none.label).toBe('No scannable components')
        expect(none.tone).toBe('warning')
        expect(none.text).toBe("only the BOM's own root component: nothing to scan (1 root, 0 terminal, 0 without purl or cpe)")
    })

    it('(b) an unknown state renders as its raw word', () => {
        expect(stateLine({ ...base, state: 'SOMETHING_NEW' }))
            .toEqual({ label: 'SOMETHING_NEW', tone: 'default', text: 'state SOMETHING_NEW is not known to this UI' })
    })

    it('(c) reasonLabel', () => {
        expect(reasonLabel('NO_COMPONENTS')).toBe('no components')
        expect(reasonLabel('ONLY_ROOT')).toBe("only the BOM's own root component")
        expect(reasonLabel('ALL_TERMINAL')).toBe('every dependency is enrichment-terminal')
        expect(reasonLabel('NO_PURL_OR_CPE')).toBe('components carry no purl or cpe')
        expect(reasonLabel(null)).toBe('')
        expect(reasonLabel('X')).toBe('X')
    })

    it('(d) the enrichment fact over the eight cells, the text map and the notes', () => {
        for (const total of [0, 2]) {
            expect(enrichmentFactOf(true, total)).toBe('GATED')
            expect(enrichmentGatedText(true, total)).toBe('submission waits for BEAR enrichment')
            expect(enrichmentFactOf(false, total)).toBe('NOT_GATED')
            expect(enrichmentGatedText(false, total)).toBe('enrichment not required in this organization')
        }
        for (const gated of [null, undefined]) {
            expect(enrichmentFactOf(gated, 0)).toBe('NOT_EVALUATED')
            expect(enrichmentGatedText(gated, 0)).toBe('not determined (asked only when a component blocks)')
            expect(enrichmentFactOf(gated, 2)).toBe('UNANSWERED')
            expect(enrichmentGatedText(gated, 2)).toBe('unknown (rebom did not answer)')
        }
        expect(Object.keys(ENRICHMENT_FACT_TEXT).sort()).toEqual(['GATED', 'NOT_EVALUATED', 'NOT_GATED', 'UNANSWERED'])
        expect(enrichmentNote(true)).toBe('This organization submits only enriched components; an un-enriched component without a bucket waits for enrichment first.')
        expect(enrichmentNote(false)).toBe('Enrichment is not required in this organization; the bucket columns carry the cause.')
        const unknown = 'Whether this organization requires enrichment could not be determined (rebom did not answer); the reason column is authoritative.'
        expect(enrichmentNote(null)).toBe(unknown)
        expect(enrichmentNote(undefined)).toBe(unknown)
    })

    it('(e) the enrichment cell matrix', () => {
        const row = (enrichment: string, bucketIndex: number | null, terminalReason: string | null = null): ScanBlocker =>
            ({ ...rowA, enrichment, bucketIndex, terminalReason })
        expect(enrichmentCell(row('ENRICHED', null), true)).toBe('enriched')
        expect(enrichmentCell(row('TERMINAL', null, 'OWN_BOM_UNPULLABLE'), null)).toBe('terminal (OWN_BOM_UNPULLABLE)')
        expect(enrichmentCell(row('TERMINAL', null), null)).toBe('terminal')
        expect(enrichmentCell(row('UNENRICHED', null), true)).toBe('not enriched (waiting for enrichment)')
        expect(enrichmentCell(row('UNENRICHED', 3), true)).toBe('not enriched')
        expect(enrichmentCell(row('UNENRICHED', null), false)).toBe('not enriched (not required in this organization)')
        expect(enrichmentCell(row('UNENRICHED', 3), false)).toBe('not enriched (not required in this organization)')
        expect(enrichmentCell(row('UNENRICHED', null), null)).toBe('enrichment state unknown')
        expect(enrichmentCell(row('UNENRICHED', 3), null)).toBe('not enriched')
        expect(enrichmentCell(row('NEW', null), true)).toBe('NEW')
    })

    it('(f) blockerRows keeps the server order and fills the columns', () => {
        const rows = blockerRows({ ...base, enrichmentGated: true, blockerTotal: 2, blockers: [rowA, rowB] }, NOW)
        expect(rows.map(r => r.key)).toEqual([0, 1])
        expect(rows[0]).toMatchObject({
            purl: 'pkg:npm/a@1', bucket: '-', ingestState: '-', bucketUpdated: '-',
            enrichment: 'not enriched (waiting for enrichment)', reason: rowA.reason
        })
        expect(rows[1]).toMatchObject({
            purl: 'pkg:npm/b@2', bucket: '7', ingestState: 'SUBMITTED', enrichment: 'enriched',
            bucketUpdated: formatInstant('2026-10-29T09:30:00Z') + ' (2h ago)', bucketUpdatedIso: '2026-10-29T09:30:00Z',
            reason: rowB.reason
        })
    })

    it('(g) ages, instants and date cells', () => {
        const ago = (ms: number) => new Date(NOW - ms).toISOString()
        expect(ageText(ago(30_000), NOW)).toBe('just now')
        expect(ageText(new Date(NOW + 3_600_000).toISOString(), NOW)).toBe('just now')
        expect(ageText(ago(5 * 60_000), NOW)).toBe('5m ago')
        expect(ageText(ago(3 * 3_600_000), NOW)).toBe('3h ago')
        expect(ageText(ago(2 * 86_400_000), NOW)).toBe('2d ago')
        expect(ageText(ago(45 * 86_400_000), NOW)).toBe('45d ago')
        expect(ageText(null, NOW)).toBe('')
        expect(ageText('garbage', NOW)).toBe('')
        expect(formatInstant(null)).toBe('')
        expect(formatInstant('garbage')).toBe('')
        expect(dateCell(null)).toBe('-')
    })

    it('(h) the not-scannable marker and the disagreement line', () => {
        const nm: ArtifactScanStatus = { ...base, state: 'NO_MATCHABLE_COMPONENTS', noMatchableComponentsReason: 'NO_PURL_OR_CPE' }
        expect(stateLine({ ...nm, notScannableAt: '2026-10-29T08:00:00Z' }).text)
            .toContain('; marked not scannable at ' + formatInstant('2026-10-29T08:00:00Z'))
        expect(stateLine({ ...nm, notScannableAt: undefined }).text).not.toContain('marked not scannable')
        expect(stateLine({ ...nm, notScannableAt: null }).text).not.toContain('marked not scannable')
        expect(stateLine({ ...nm, matchableComponentCount: 2, inCandidateSet: true }).text)
            .toMatch(/; a matchable component appeared since; qualifies for a clearing pass$/)
        expect(stateLine({ ...nm, matchableComponentCount: 2, inCandidateSet: false }).text)
            .toMatch(/; a matchable component appeared since; not a candidate for a clearing pass$/)
        expect(stateLine({ ...nm, matchableComponentCount: 0 }).text).not.toContain('appeared since')
    })

    it('(i) factRows: twelve rows in order', () => {
        const rows = factRows(base, NOW)
        expect(rows.map(r => r.key)).toEqual(['type', 'mapped', 'mappedAt', 'firstScanned', 'lastScanned', 'fanOutAt',
            'fanOutRequeuedAt', 'newestIngestedBucketUpdate', 'ingestedBucketCount', 'components', 'enrichmentGated', 'blockerTotal'])
        expect(rows.map(r => r.label)).toEqual(['Type', 'Mapped', 'Mapped at', 'First scanned', 'Last scanned', 'Fan-out at',
            'Requeued at', 'Newest ingested bucket update', 'Ingested buckets', 'Components', 'Enrichment', 'Blocking components'])
        const byKey = (s: ArtifactScanStatus, k: string) => factRows(s, NOW).find(r => r.key === k)!
        expect(byKey(base, 'ingestedBucketCount').value).toBe('0 (no ingested bucket yet; no fan-out pass runs until one ingests)')
        expect(byKey({ ...base, ingestedBucketCount: 4 }, 'ingestedBucketCount').value).toBe('4')
        expect(byKey({ ...base, matchableComponentCount: 5, rootComponentCount: 1, terminalComponentCount: 2 }, 'components').value)
            .toBe('5 matchable, 1 root, 2 terminal, 0 without purl or cpe')
        expect(byKey(base, 'mapped').value).toBe('yes')
        expect(byKey({ ...base, mapped: false }, 'mapped').value).toBe('no')
        const fanOut = byKey(base, 'fanOutAt')
        expect(fanOut.iso).toBe('2026-10-28T10:00:00.123456789Z')
        expect(fanOut.value).toMatch(/ \(\d+[mhd] ago\)$|\(just now\)$/)
        expect(byKey(base, 'fanOutRequeuedAt').value).toBe('-')
        expect(rows.some(r => r.key === 'canonical')).toBe(false)
        expect(byKey(base, 'enrichmentGated').value).toBe('not determined (asked only when a component blocks)')
        expect(byKey({ ...base, blockerTotal: 2 }, 'enrichmentGated').value).toBe('unknown (rebom did not answer)')
        expect(byKey({ ...base, enrichmentGated: true }, 'enrichmentGated').value).toBe('submission waits for BEAR enrichment')
        expect(byKey({ ...base, enrichmentGated: false }, 'enrichmentGated').value).toBe('enrichment not required in this organization')
    })

    it('(j) no state, flag or rule promises a next tick; the candidate line', () => {
        const states = ['NOT_A_BOM', 'UNMAPPED', 'BLOCKED_ON_COVERAGE', 'WAITING_FOR_FAN_OUT', 'SCANNED', 'NO_MATCHABLE_COMPONENTS']
        for (const state of states) {
            for (const inCandidateSet of [true, false]) {
                const s = { ...base, state, inCandidateSet, matchableComponentCount: 1 }
                const c = candidateLine(s)
                expect(stateLine(s).text + c.flag + c.rule, `${state} ${inCandidateSet}`).not.toMatch(/next tick/i)
            }
        }
        expect(candidateLine(base)).toEqual({ flag: 'not a candidate', rule: base.candidateRule })
        expect(candidateLine({ ...base, inCandidateSet: true }).flag).toBe('qualifies for a fan-out pass')
    })

    it('(k) truncatedNote', () => {
        const fifty = Array.from({ length: 50 }, (_, i) => ({ ...rowA, purl: `pkg:npm/p${i}@1` }))
        expect(truncatedNote({ ...base, blockers: fifty, blockerTotal: 73, blockersTruncated: true })).toBe('showing 50 of 73 blocking components')
        expect(truncatedNote({ ...base, blockers: [rowA], blockerTotal: 1, blockersTruncated: false })).toBeNull()
    })
})
