// Regulatory submissions (task TEA-30, design 4.6 rows 30, 31 and 35): the product page's rows, the
// lifecycle rule, the version and part rows, the reason check, the confirmations with names in text
// only, the act error text and the History text over the four shapes the server writes.
import { describe, expect, it, vi } from 'vitest'

vi.mock('./fetchClient', () => ({ fetchWithAuth: vi.fn(), fetchArrayBufferWithAuth: vi.fn() }))

const {
    canCreateSubmission, manifestDownloadRef, manifestFileName, submissionActErrorText, submissionCreateConfirm,
    submissionHistoryText, submissionOtherRows, submissionPartRows, submissionReasonError, submissionReleaseRows,
    submissionResubmitConfirm, submissionVersionRows, submissionWithdrawConfirm,
} = await import('./submissions')
const { TEA_ACT_GATEWAY_TEXT } = await import('./teaPublication')

const A1 = '11111111-1111-4111-8111-111111111111'
const A2 = '22222222-2222-4222-8222-222222222222'
const M1 = '33333333-3333-4333-8333-333333333333'
const releases = [
    { uuid: 'r1', version: '1.0.0', lifecycle: 'ASSEMBLED', createdDate: '2026-10-01T00:00:00Z' },
    { uuid: 'r2', version: '1.1.0', lifecycle: 'DRAFT', createdDate: '2026-10-05T00:00:00Z' },
]
const heads = [
    { uuid: 's1', release: 'r1', kind: 'FDA_PREMARKET', state: 'ACTIVE', latestVersion: 2 },
    { uuid: 's9', release: 'r-other', releaseVersion: '0.9.0', kind: 'FDA_PREMARKET', state: 'WITHDRAWN', latestVersion: 1,
        lastActDate: '2026-09-01T00:00:00Z' },
]

describe('the product page rows (row 30)', () => {
    it('joins releases and heads per kind, newest release first, null where none', () => {
        const rows = submissionReleaseRows(releases, heads, ['FDA_PREMARKET', 'TEST_KIND'])
        expect(rows.map(r => r.release)).toEqual(['r2', 'r1'])
        expect(rows[1].byKind.FDA_PREMARKET.uuid).toBe('s1')
        expect(rows[1].byKind.TEST_KIND).toBeNull()
        expect(rows[0].byKind.FDA_PREMARKET).toBeNull()
        expect(rows[1]).toMatchObject({ version: '1.0.0', lifecycle: 'ASSEMBLED' })
    })

    it('lists heads of releases outside the list with their releaseVersion', () => {
        expect(submissionOtherRows(releases, heads)).toEqual([{ uuid: 's9', release: 'r-other', releaseVersion: '0.9.0',
            kind: 'FDA_PREMARKET', state: 'WITHDRAWN', latestVersion: 1, lastActDate: '2026-09-01T00:00:00Z' }])
    })

    it('allows a create at ASSEMBLED and above only', () => {
        for (const l of ['ASSEMBLED', 'READY_TO_SHIP', 'GENERAL_AVAILABILITY', 'END_OF_SUPPORT']) expect(canCreateSubmission(l), l).toBe(true)
        for (const l of ['DRAFT', 'PENDING', 'REJECTED', 'CANCELLED', null]) expect(canCreateSubmission(l as any), String(l)).toBe(false)
    })
})

const version = (n: number, changes: Record<string, any> = {}) => ({
    submission: 's1', version: n, createdDate: '2026-10-0' + n + 'T00:00:00Z', createdBy: 'u1', reason: null,
    settings: { row: 'set1', revision: 3, source: 'ORGANIZATION', sourceObject: null },
    sbomProfile: { name: 'default', row: null, revision: 0, source: 'DEFAULT', sourceObject: null },
    manifestArtifact: M1, manifestArtifactVersion: '1', manifestSha256: 'a'.repeat(64), manifest: '{}',
    parts: [
        { part: 'AGGREGATE_SBOM', requirement: 'REQUIRED', outcome: 'PRODUCED', artifact: A1, artifactVersion: '1',
            fileName: 'widget-1.0.0-sbom.cdx.json', mediaType: 'application/json', size: 10, sha256: 'b'.repeat(64) },
        { part: 'VEX', requirement: 'OPTIONAL', outcome: 'SKIPPED', skipReason: 'EXCLUDED_BY_SETTINGS', detail: 'VEX excluded' },
        { part: 'RAW_ARTIFACTS', requirement: 'OPTIONAL', outcome: 'REFERENCED', artifact: A2, artifactVersion: null,
            fileName: 'doc.pdf' },
    ],
    ...changes,
})

describe('the version and part rows (row 30)', () => {
    it('versions newest first with their labels and counts', () => {
        const rows = submissionVersionRows({ versions: [version(1), version(2, { reason: 'second' })] })
        expect(rows.map(r => r.version)).toEqual([2, 1])
        expect(rows[0].reason).toBe('second')
        expect(rows[0].settingsLabel).toBe('organization, r3')
        expect(rows[0].profileLabel).toBe('default (built-in defaults)')
        expect([rows[0].partCount, rows[0].producedCount, rows[0].skippedCount]).toEqual([3, 2, 1])
        expect(rows[0].manifestSha256Short).toBe('a'.repeat(12))
    })

    it('parts in manifest order; a SKIPPED part has its reason and detail and no download; PRODUCED has a ref', () => {
        const parts = submissionPartRows(version(1))
        expect(parts.map(p => p.part)).toEqual(['AGGREGATE_SBOM', 'VEX', 'RAW_ARTIFACTS'])
        expect(parts[0].download).toEqual({ uuid: A1, version: 1, fileName: 'widget-1.0.0-sbom.cdx.json' })
        expect(parts[0].outcomeLabel).toBe('Generated')
        expect(parts[1].download).toBeNull()
        expect(parts[1].skipText).toBe('excluded by settings: VEX excluded')
        expect(parts[2].download).toBeNull()
    })

    it('names the manifest file after the submission and version (D7)', () => {
        expect(manifestFileName(version(2))).toBe('submission-s1-v2-manifest.json')
        expect(manifestDownloadRef(version(2))).toEqual({ uuid: M1, version: 1, fileName: 'submission-s1-v2-manifest.json' })
        expect(manifestDownloadRef(version(2, { manifestArtifactVersion: null }))).toBeNull()
    })
})

describe('the reason check (row 30)', () => {
    it('requires a reason where asked, bounds it at 2048, and refuses control characters but TAB, LF and CR', () => {
        expect(submissionReasonError('', true)).toBe('A reason is required')
        expect(submissionReasonError('   ', true)).toBe('A reason is required')
        expect(submissionReasonError('', false)).toBeNull()
        expect(submissionReasonError('x'.repeat(2049), false)).toBe('At most 2048 characters')
        expect(submissionReasonError('x'.repeat(2048), true)).toBeNull()
        expect(submissionReasonError('a\u0001b', false)).toBe('Control characters are not allowed')
        expect(submissionReasonError('a\tb\nc\rd', true)).toBeNull()
    })
})

describe('the confirmations and the act error text (row 31)', () => {
    const preview = { nextVersion: 3, plan: [{ part: 'AGGREGATE_SBOM', selection: 'INCLUDE' }, { part: 'VEX', selection: 'EXCLUDE' }],
        sbomProfile: { name: 'top', row: 'p', revision: 1, source: 'ORGANIZATION' },
        settings: { row: 'set1', revision: 3, source: 'ORGANIZATION' } }
    const names = { componentName: '<b>x</b>', releaseVersion: '1.2.0' }

    it('names the component, the version, the next version, the plan and the profile in text only', () => {
        const c = submissionCreateConfirm(preview, 'FDA_PREMARKET', names)
        expect(c.title).toBe('Create submission?')
        expect(c.confirmButtonText).toBe('Create v3')
        expect(c.text).toBe('Writes v3 of the FDA premarket submission of <b>x</b> 1.2.0. Parts: Aggregate SBOM included, '
            + 'VEX excluded; SBOM profile top (organization, r1); settings organization, r3.')
        const r = submissionResubmitConfirm(preview, 'FDA_PREMARKET', names)
        expect(r.title).toBe('Resubmit?')
        expect(r.text).toContain('<b>x</b> 1.2.0')
        expect(r.text).toContain('A resubmission always writes a new version, equal content or not.')
        const w = submissionWithdrawConfirm({ kind: 'FDA_PREMARKET', latestVersion: 2 }, names)
        expect(w.title).toBe('Withdraw submission?')
        expect(w.confirmButtonText).toBe('Withdraw')
        expect(w.text).toContain('<b>x</b> 1.2.0 at v2')
        expect(w.text).toContain('Every version and document is kept; the submission is marked withdrawn and can be resubmitted later.')
        for (const t of [c.title, r.title, w.title]) expect(t).not.toContain('<b>')
    })

    it('answers the gateway sentence for a 503 and the parsed message otherwise', () => {
        expect(submissionActErrorText({ networkError: { statusCode: 503 }, message: 'x' })).toBe(TEA_ACT_GATEWAY_TEXT)
        expect(submissionActErrorText(new Error('Release widget 1.2.0 (u): the submission is at v3, not v2; reload it before resubmitting')))
            .toBe('Release widget 1.2.0 (u): the submission is at v3, not v2; reload it before resubmitting')
    })

    it('answers the refusal alone for the two-entry rejection of a non-null act (round 4, 3.7.1)', () => {
        const refusal = 'Release widget 1.2.0 (u): the submission is at v3, not v2; reload it before resubmitting'
        const NULL_TEXT = "The field at path '/resubmitSubmission' was declared as a non null type, but the code involved "
            + 'in retrieving data has wrongly returned a null value.  The graphql specification requires that the parent '
            + 'field be set to null, or if that is non nullable that it bubble up null to its parent and so on. The '
            + "non-nullable type is 'SubmissionActResult' within parent type 'Mutation'"
        const err = Object.assign(new Error(refusal + '\n' + NULL_TEXT), { errors: [
            { message: refusal, extensions: { classification: 'BAD_REQUEST' } },
            { message: NULL_TEXT, path: ['resubmitSubmission'], extensions: { classification: 'NullValueInNonNullableField' } },
        ] })
        expect(submissionActErrorText(err)).toBe(refusal)
    })
})

describe('the History text (row 35)', () => {
    it('renders the four shapes the server writes, and the objectId when both values are blank', () => {
        expect(submissionHistoryText({ oldValue: null, newValue: 'FDA_PREMARKET v1' })).toBe('Submission FDA_PREMARKET v1')
        expect(submissionHistoryText({ oldValue: 'FDA_PREMARKET v1', newValue: 'FDA_PREMARKET v2' }))
            .toBe('Submission FDA_PREMARKET v1 -> FDA_PREMARKET v2')
        expect(submissionHistoryText({ oldValue: 'WITHDRAWN', newValue: 'ACTIVE v3' })).toBe('Submission WITHDRAWN -> ACTIVE v3')
        expect(submissionHistoryText({ oldValue: 'ACTIVE v2', newValue: 'WITHDRAWN' })).toBe('Submission ACTIVE v2 -> WITHDRAWN')
        expect(submissionHistoryText({ oldValue: null, newValue: null, objectId: 'x' })).toBe('x')
        expect(submissionHistoryText({ oldValue: '', newValue: 'FDA_PREMARKET v1' })).toBe('Submission FDA_PREMARKET v1')
    })
})
