import { describe, it, expect, vi } from 'vitest'

vi.mock('./fetchClient', () => ({ fetchWithAuth: vi.fn(), fetchArrayBufferWithAuth: vi.fn() }))
import * as D from './submissionDrafts'

/**
 * FDAW-17 / FDAW-73 row 18: the Assess findings verb of the three finding rows. Its button needs the Finding Analysis
 * Write permission (the server's gate, FINDING_ANALYSIS_WRITE on the organization), not organization write, and
 * says so in the sentence of 3.10; a row without the verb (a catalog that fails with nothing listed, or findings
 * without a package identifier only) has no button.
 */
describe('FDAW-17 row 18: the Assess findings verb', () => {
    const SENTENCE = 'Assessing findings needs the Finding Analysis Write permission on the organization; an organization'
        + ' administrator grants it.'
    const KEV = { rule: 'VULN_KEV_UNASSESSED', severity: 'MUST_FIX', outcome: 'FAIL', count: 1, justified: null,
        text: '1 known exploited (KEV) finding has no complete assessment and explanation', verb: 'ASSESS_FINDINGS' }
    const REVIEW = { ...KEV, rule: 'VULN_KEV_AFFECTS_DEVICE', severity: 'REVIEW' }
    const one = (r: any, facts: D.ReadinessFacts) =>
        D.readinessRows({ readiness: { ruleSetVersion: '2', rows: [r] } }, facts)[0]

    it('a writer with the permission gets the button, enabled, whatever organization write says', () => {
        const v = one(KEV, { writable: true, canWriteOrg: false, canWriteFindingAnalysis: true })
        expect([v.verb, v.verbLabel, v.verbDisabled, v.sentence, v.tag]).toEqual(['ASSESS_FINDINGS', 'Assess findings', false, null, 'Must fix'])
    })
    it('a writer without it gets the button disabled with the sentence, even with organization write', () => {
        const v = one(KEV, { writable: true, canWriteOrg: true, canWriteFindingAnalysis: false })
        expect([v.verb, v.verbDisabled, v.sentence]).toEqual(['ASSESS_FINDINGS', true, SENTENCE])
        expect(D.FINDING_ANALYSIS_WRITE_SENTENCE).toBe(SENTENCE)
        expect(D.ORG_WIDE_VERBS).not.toContain('ASSESS_FINDINGS')
    })
    it('a review row carries it the same; a reader, a PASS row and a row without the verb get none', () => {
        const r = one(REVIEW, { writable: true, canWriteFindingAnalysis: true })
        expect([r.verb, r.tag]).toEqual(['ASSESS_FINDINGS', 'Review'])
        expect(one(KEV, { writable: false, canWriteFindingAnalysis: true }).verb).toBeNull()
        expect(one({ ...KEV, outcome: 'PASS' }, { writable: true, canWriteFindingAnalysis: true }).verb).toBeNull()
        const noVerb = one({ ...KEV, verb: null }, { writable: true, canWriteFindingAnalysis: false })
        expect([noVerb.verb, noVerb.verbDisabled, noVerb.sentence, noVerb.tag]).toEqual([null, false, null, 'Must fix'])
    })
    it('the fix text names other places (3.10)', () => {
        expect(D.READINESS_FIX_TEXT).toBe('Fix these items, then rebuild the draft. A must-fix item without a button, or an entry a'
            + ' button does not list, is corrected in the SBOM uploaded to the release, or to the component release that carries the'
            + ' entry, unless its text names another place.')
    })
})
