import { describe, it, expect, vi } from 'vitest'

// commonFunctions pulls in the Apollo client and Keycloak; only its message extractor is used here.
vi.mock('./commonFunctions', () => ({
    default: {
        extractGraphQLErrorMessage: (err: any) => (Array.isArray(err?.errors) && err.errors.length
            ? err.errors.map((e: any) => e.message).join('; ') : err?.message || 'Unknown error')
    }
}))

import {
    SBOM_SCORE_TIMEOUT_MESSAGE,
    SbomScoreParseError,
    buildReleaseScoreVariables,
    classifySbomScoreError,
    orderChecks,
    parseSbomScoreReport,
    rawReportFileName,
    summaryLine,
    type ReleaseScoreForm,
} from './sbomScore'
import { GOLDENS, fixtureText } from './__fixtures__/sbomScore/fixtures'

// The goldens come from rearm-cli internal/bomscore/testdata/golden/ at 9ead33a (see fixtures.ts).

function parseError (raw: string): SbomScoreParseError {
    try {
        parseSbomScoreReport(raw)
    } catch (e) {
        return e as SbomScoreParseError
    }
    throw new Error('expected a parse error')
}

describe('parseSbomScoreReport', () => {
    // T-1
    it.each(GOLDENS)('parses the golden %s, without the SCORE-10 fields', (name) => {
        const report = parseSbomScoreReport(fixtureText(name))
        expect(report.reportVersion).toBe(1)
        expect(report.profiles[0].key).toBe(name.split('.')[0])
        expect(report.options).toBeUndefined()
        expect(report.input.componentsSkipped).toBeUndefined()
        expect(Array.isArray(report.structure.checks)).toBe(true)
    })

    // T-2
    it('keeps the SCORE-10 fields', () => {
        const report = parseSbomScoreReport(fixtureText('skip-files'))
        expect(report.options?.skipFiles).toBe(true)
        expect(report.input.componentsSkipped).toBe(3)
    })

    it('keeps unknown extra fields and ignores them', () => {
        const raw = JSON.parse(fixtureText('fda.cdx'))
        raw.futureField = { a: 1 }
        const report: any = parseSbomScoreReport(JSON.stringify(raw))
        expect(report.futureField).toEqual({ a: 1 })
        expect(report.profiles).toHaveLength(1)
    })

    // T-3
    it('refuses a report version it does not know', () => {
        const e = parseError(fixtureText('version-2'))
        expect(e).toBeInstanceOf(SbomScoreParseError)
        expect(e.kind).toBe('UNSUPPORTED_VERSION')
        expect(e.message).toContain('unsupported report version 2')
    })

    it('refuses text that is not JSON', () => {
        expect(parseError('not json').kind).toBe('NOT_JSON')
    })

    it('refuses a version-1 report without profiles or input', () => {
        expect(parseError('{"reportVersion":1}').kind).toBe('MALFORMED')
        expect(parseError('{"reportVersion":1,"profiles":[]}').kind).toBe('MALFORMED')
    })

    it('refuses a report that has its input section but no profiles array (D-1)', () => {
        // Each half of the guard on its own: the report view reads profiles[0] and input.format.
        const input = '"input":{"format":"CycloneDX","specVersion":"1.6","serialization":"json","components":1,"sha256":""}'
        expect(parseError(`{"reportVersion":1,${input}}`).kind).toBe('MALFORMED')
        expect(parseError(`{"reportVersion":1,${input},"profiles":{}}`).kind).toBe('MALFORMED')
        expect(parseError(`{"reportVersion":1,${input},"profiles":null}`).kind).toBe('MALFORMED')
        expect(parseError('{"reportVersion":1,"profiles":[],"input":null}').kind).toBe('MALFORMED')
    })
})

describe('orderChecks', () => {
    // T-4
    it('puts FAIL, ERROR, NOT_ASSESSED, PASS in that order, REQUIRED before INFO, else stable', () => {
        const report = parseSbomScoreReport(fixtureText('two-profiles'))
        expect(orderChecks(report.profiles[0].checks).map(c => c.id)).toEqual([
            'cisa.component.hash',              // FAIL REQUIRED
            'cisa.component.license',           // FAIL INFO
            'cisa.component.supplier',          // ERROR
            'cisa.document.generation-context', // NOT_ASSESSED
            'cisa.component.name',              // PASS REQUIRED
            'cisa.document.author',             // PASS INFO
        ])
    })

    it('keeps the report order inside a status and level, and does not touch its input', () => {
        const report = parseSbomScoreReport(fixtureText('cisa-2026.spdx'))
        const checks = report.profiles[0].checks
        const before = checks.map(c => c.id)
        const ordered = orderChecks(checks)
        expect(checks.map(c => c.id)).toEqual(before)
        const fails = before.filter(id => checks.find(c => c.id === id)!.status === 'FAIL')
        expect(ordered.slice(0, fails.length).map(c => c.id)).toEqual(fails)
        expect(ordered.at(-1)!.status).toBe('PASS')
    })
})

describe('summaryLine', () => {
    const profile = (pass: number, fail: number, notAssessed: number, errors: number): any =>
        ({ summary: { pass, fail, notAssessed, errors } })

    // T-5
    it('leaves zero groups out except passed', () => {
        expect(summaryLine(profile(0, 0, 0, 0))).toBe('0 passed')
        expect(summaryLine(profile(9, 1, 0, 0))).toBe('9 passed, 1 failed')
    })

    it('names all four groups when none is zero', () => {
        expect(summaryLine(profile(2, 2, 1, 1))).toBe('2 passed, 2 failed, 1 not assessed, 1 errors')
    })
})

describe('buildReleaseScoreVariables', () => {
    const defaults: ReleaseScoreForm = {
        release: 'r-1',
        tldOnly: true,
        ignoreDev: false,
        selectedBomStructureType: 'FLAT',
        selectedRebomType: '',
        computedExcludeCoverageTypes: [],
        includeSupportMetadata: false,
        includeInternalMetadata: false,
        orgSupportInjectionEnabled: false,
        exportMetadataArgsUnsupported: true,
    }

    // T-6
    it('maps the default form as exportReleaseSbom does, with both profiles and no mediaType', () => {
        const v = buildReleaseScoreVariables(defaults)
        expect(v).toEqual({
            release: 'r-1',
            tldOnly: true,
            ignoreDev: false,
            structure: 'FLAT',
            belongsTo: null,
            excludeCoverageTypes: null,
            profiles: ['cisa-2026', 'fda'],
        })
        expect('mediaType' in v).toBe(false)
    })

    it('maps a full form, metadata flags included when the server takes them', () => {
        const v = buildReleaseScoreVariables({
            ...defaults,
            tldOnly: false,
            ignoreDev: true,
            selectedBomStructureType: 'HIERARCHICAL',
            selectedRebomType: 'DELIVERABLE',
            computedExcludeCoverageTypes: ['DEV', 'TEST'],
            includeSupportMetadata: true,
            includeInternalMetadata: true,
            orgSupportInjectionEnabled: true,
            exportMetadataArgsUnsupported: false,
        })
        expect(v).toEqual({
            release: 'r-1',
            tldOnly: false,
            ignoreDev: true,
            structure: 'HIERARCHICAL',
            belongsTo: 'DELIVERABLE',
            excludeCoverageTypes: ['DEV', 'TEST'],
            includeSupportMetadata: true,
            includeInternalMetadata: true,
            profiles: ['cisa-2026', 'fda'],
        })
        expect('mediaType' in v).toBe(false)
    })

    it('sends the support flag as null when the org does not offer it, as the export does', () => {
        const v = buildReleaseScoreVariables({ ...defaults, exportMetadataArgsUnsupported: false, includeSupportMetadata: true })
        expect(v.includeSupportMetadata).toBeNull()
        expect(v.includeInternalMetadata).toBe(false)
    })

    it('returns a fresh profiles array each time', () => {
        const a = buildReleaseScoreVariables(defaults)
        a.profiles.push('x')
        expect(buildReleaseScoreVariables(defaults).profiles).toEqual(['cisa-2026', 'fda'])
    })
})

// --- Apollo v4 error shapes, as graphqlDriftFallback.spec.ts builds them ---
function scoreError (reason: string, message = 'server says why'): any {
    const err: any = new Error(message)
    err.errors = [{ message, extensions: { code: 'SBOM_SCORE_ERROR', reason, classification: 'BAD_REQUEST' } }]
    return err
}
function notAuthorized (): any {
    const err: any = new Error('Not authorized')
    err.errors = [{ message: 'Not authorized', extensions: { classification: 'INTERNAL_ERROR' } }]
    return err
}
function driftError (): any {
    const err: any = new Error("Validation error of type FieldUndefined: Field 'releaseSbomScore' in type 'Query' is undefined")
    err.errors = [{ message: err.message, extensions: { classification: 'ValidationError' } }]
    return err
}
function httpError (status: number): any {
    const err: any = new Error(`Response not successful: Received status code ${status}`)
    err.statusCode = status
    return err
}
function abortError (): any {
    const err: any = new Error('The operation was aborted')
    err.name = 'AbortError'
    return err
}

describe('classifySbomScoreError', () => {
    // T-7: every row of design 3.6.
    it.each([
        ['UNKNOWN_PROFILE', 'The server does not know the profiles this UI asked for (cisa-2026, fda). Update the server. server says why', false],
        ['INPUT_TOO_LARGE', 'This SBOM is too large to score on this server. server says why', false],
        ['INPUT_REFUSED', 'The scoring engine cannot score this document: server says why', false],
        ['NOT_SCORABLE', 'This artifact is not a scorable SBOM: server says why', false],
        ['TIMEOUT', SBOM_SCORE_TIMEOUT_MESSAGE, true],
        ['SCORING_UNAVAILABLE', 'SBOM scoring is not available on this server right now (the scoring engine is missing on this server\'s platform, for example arm64, or the scoring service is down).', true],
        ['SCORING_FAILED', 'The scoring engine failed: server says why', true],
    ])('reason %s', (reason, message, retryable) => {
        expect(classifySbomScoreError(scoreError(reason))).toEqual({ kind: reason, reason, message, retryable })
    })

    it('does not append the server message where 3.6 does not say so', () => {
        expect(classifySbomScoreError(scoreError('TIMEOUT')).message).not.toContain('server says why')
        expect(classifySbomScoreError(scoreError('SCORING_UNAVAILABLE')).message).not.toContain('server says why')
    })

    it('Not authorized without a code', () => {
        expect(classifySbomScoreError(notAuthorized())).toEqual({
            kind: 'NOT_AUTHORIZED',
            message: 'You are not authorized to score this SBOM (it needs the permission to download it).',
            retryable: false,
        })
    })

    it('a server without the queries (schema drift)', () => {
        expect(classifySbomScoreError(driftError())).toEqual({
            kind: 'NOT_AVAILABLE', message: 'SBOM scoring is not available on this server.', retryable: false,
        })
    })

    it('an HTTP 504 and an aborted request read as the timeout', () => {
        for (const err of [httpError(504), abortError()]) {
            expect(classifySbomScoreError(err)).toMatchObject({ kind: 'TIMEOUT', message: SBOM_SCORE_TIMEOUT_MESSAGE, retryable: true })
        }
    })

    it('an HTTP 413 (request over the server body limit) reads as too large', () => {
        expect(classifySbomScoreError(httpError(413))).toEqual({
            kind: 'INPUT_TOO_LARGE', reason: 'INPUT_TOO_LARGE', message: 'This SBOM is too large to score on this server.', retryable: false,
        })
    })

    it('a report this UI cannot read', () => {
        expect(classifySbomScoreError(parseError(fixtureText('version-2')))).toEqual({
            kind: 'PARSE', message: 'unsupported report version 2; update ReARM UI', retryable: false,
        })
    })

    it('anything else, with an unknown reason too', () => {
        expect(classifySbomScoreError(httpError(500))).toEqual({
            kind: 'OTHER', message: 'Scoring failed: Response not successful: Received status code 500', retryable: true,
        })
        expect(classifySbomScoreError(scoreError('SOMETHING_NEW', 'odd'))).toMatchObject({ kind: 'OTHER', retryable: true })
        expect(classifySbomScoreError(scoreError('toString', 'odd'))).toMatchObject({ kind: 'OTHER', retryable: true })
    })
})

describe('rawReportFileName', () => {
    // T-8
    it('names the kind, the first 8 characters of the id and the date', () => {
        expect(rawReportFileName('release', '0123456789abcdef-0000', new Date('2026-10-07T12:00:00Z')))
            .toBe('sbom-score-release-01234567-2026-10-07.json')
        expect(rawReportFileName('artifact', 'abcdefgh-ijkl')).toMatch(/^sbom-score-artifact-abcdefgh-\d{4}-\d{2}-\d{2}\.json$/)
    })
})
