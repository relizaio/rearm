// Regulatory submissions (task TEA-30, design 4.6 rows 30, 31 and 35): the product page's rows, the
// lifecycle rule, the version and part rows, the reason check, the confirmations with names in text
// only, the act error text and the History text over the four shapes the server writes.
import { describe, expect, it, vi } from 'vitest'

vi.mock('./fetchClient', () => ({ fetchWithAuth: vi.fn(), fetchArrayBufferWithAuth: vi.fn() }))

const {
    canCreateSubmission, manifestDownloadRef, manifestFileName, submissionActErrorText, submissionCreateConfirm,
    submissionHistoryText, submissionOtherRows, submissionPartRows, submissionReasonError, submissionReleaseRows,
    submissionResubmitConfirm, submissionVersionRows, submissionWithdrawConfirm, submissionStateText,
    submissionStateTagType, submissionCancelNote, submissionDateText, SUBMISSION_STATE_LABELS, SUBMISSION_ACT_LABELS,
    submissionActorText, submissionKindDisabledText, submissionProfileLabel,
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
    { uuid: 's1', release: 'r1', kind: 'FDA_PREMARKET', state: 'FINAL', latestVersion: 2 },
    { uuid: 's9', release: 'r-other', releaseVersion: '0.9.0', kind: 'FDA_PREMARKET', state: 'CANCELLED', latestVersion: 1,
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
            kind: 'FDA_PREMARKET', state: 'CANCELLED', latestVersion: 1, lastActDate: '2026-09-01T00:00:00Z' }])
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

describe('the profile label of a named built-in (FDAW-46 row 23)', () => {
    it('reads <name> (built-in) for a built-in ref of any name but default', () => {
        expect(submissionProfileLabel({ name: 'fda', row: null, revision: 0, source: 'DEFAULT', sourceObject: null }))
            .toBe('fda (built-in)')
        expect(submissionProfileLabel({ name: 'fda', row: 'p1', revision: 2, source: null })).toBe('fda (built-in)')
        expect(submissionProfileLabel({ name: 'fda', row: 'p1', revision: 2, source: 'DEFAULT' })).toBe('fda (built-in)')
        expect(submissionProfileLabel({ name: 'fda', row: 'p1', revision: 3, source: 'ORGANIZATION' }))
            .toBe('fda (organization, r3)')
        expect(submissionProfileLabel({ name: 'default', row: null, revision: 0, source: 'DEFAULT' }))
            .toBe('default (built-in defaults)')
        expect(submissionProfileLabel({ name: null, row: null, source: 'DEFAULT' })).toBe(' (built-in defaults)')
    })

    it('a ref naming no stored row reads built-in whatever its source (mutation)', () => {
        expect(submissionProfileLabel({ name: 'fda', row: null, revision: 0, source: 'ORGANIZATION' })).toBe('fda (built-in)')
        expect(submissionProfileLabel({ name: 'default', row: null, revision: 0, source: 'ORGANIZATION' }))
            .toBe('default (built-in defaults)')
    })

    it('a version that applied the built-in fda labels it built-in; the settings label is unchanged', () => {
        const rows = submissionVersionRows({ versions: [version(1, { sbomProfile: { name: 'fda', row: null, revision: 0,
            source: 'DEFAULT', sourceObject: null } })] })
        expect(rows[0].profileLabel).toBe('fda (built-in)')
        expect(rows[0].settingsLabel).toBe('organization, r3')
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
        expect(w.title).toBe('Cancel submission?')
        expect(w.confirmButtonText).toBe('Cancel submission')
        expect(w.text).toContain('Cancels the FDA premarket submission of <b>x</b> 1.2.0 at v2.')
        expect(w.text).toContain('Every version and document is kept; the version is marked cancelled and a resubmission writes a new version.')
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
        // FDAW-8: the shapes written since FINAL and CANCELLED; the rows above stay in older release histories
        expect(submissionHistoryText({ oldValue: 'CANCELLED', newValue: 'FINAL v3' })).toBe('Submission CANCELLED -> FINAL v3')
        expect(submissionHistoryText({ oldValue: 'FINAL v2', newValue: 'CANCELLED' })).toBe('Submission FINAL v2 -> CANCELLED')
        expect(submissionHistoryText({ oldValue: null, newValue: null, objectId: 'x' })).toBe('x')
        expect(submissionHistoryText({ oldValue: '', newValue: 'FDA_PREMARKET v1' })).toBe('Submission FDA_PREMARKET v1')
    })
})

describe('the head state texts (FDAW-8)', () => {
    it('labels the six states and the CANCELLED outcome', () => {
        expect(SUBMISSION_STATE_LABELS).toEqual({ FINAL: 'Final', SENT: 'Sent', AMENDED: 'Amended', SUPERSEDED: 'Superseded',
            WITHDRAWN: 'Withdrawn', CANCELLED: 'Cancelled' })
        expect(SUBMISSION_ACT_LABELS.CANCELLED).toBe('Cancelled')
    })

    it('reads FINAL without a confirmation record, a migrated head as legacy, and any other state as its label', () => {
        expect(submissionStateText({ state: 'FINAL', legacyState: null })).toBe('Final (no confirmation record) - not sent')
        expect(submissionStateText({ state: 'FINAL', legacyState: 'ACTIVE' })).toBe('Final (legacy, no confirmation record) - not sent')
        const d = '2026-09-03T00:00:00Z'
        expect(submissionStateText({ state: 'CANCELLED', legacyState: 'WITHDRAWN', withdrawnDate: d }))
            .toBe('Cancelled (legacy: withdrawn ' + submissionDateText(d) + ')')
        expect(submissionStateText({ state: 'CANCELLED', legacyState: null })).toBe('Cancelled')
        expect(submissionStateText({ state: 'SUPERSEDED' })).toBe('Superseded')
        expect(submissionStateText({ state: 'SOMETHING_NEW' })).toBe('SOMETHING_NEW')
    })

    it('colours FINAL and SENT green, CANCELLED and WITHDRAWN amber, the rest plain', () => {
        for (const s of ['FINAL', 'SENT']) expect(submissionStateTagType(s), s).toBe('success')
        for (const s of ['CANCELLED', 'WITHDRAWN']) expect(submissionStateTagType(s), s).toBe('warning')
        for (const s of ['AMENDED', 'SUPERSEDED', null]) expect(submissionStateTagType(s as any), String(s)).toBe('default')
    })

    it('notes a cancelled head from its newest CANCEL act, a legacy one from the triple, and nothing otherwise', () => {
        const acts = [
            { kind: 'CANCEL', actedAt: '2026-10-01T00:00:00Z', actorName: 'Ann', reasonText: 'older' },
            { kind: 'FINALIZE', actedAt: '2026-10-02T00:00:00Z', actorName: 'Ann', reasonText: 'again' },
            { kind: 'CANCEL', actedAt: '2026-10-03T00:00:00Z', actorName: 'Bob', reasonText: 'newest' },
        ]
        expect(submissionCancelNote({ state: 'CANCELLED', acts })).toEqual({
            lead: 'Cancelled on ' + submissionDateText('2026-10-03T00:00:00Z') + ' by Bob:', reason: 'newest' })
        expect(submissionCancelNote({ state: 'CANCELLED', legacyState: 'WITHDRAWN', acts: [],
            withdrawnDate: '2026-09-03T00:00:00Z', withdrawReason: 'old' }))
            .toEqual({ lead: 'Withdrawn on ' + submissionDateText('2026-09-03T00:00:00Z') + ':', reason: 'old' })
        expect(submissionCancelNote({ state: 'FINAL', acts })).toBeNull()
        expect(submissionCancelNote({ state: 'CANCELLED', acts: [] })).toBeNull()
    })
})

// Task FDAW-4 (T5): the actor as the page shows it (B14) and the KIND_DISABLED explanation (B18).
describe('the actor text and the switched-off kind sentence (FDAW-4)', () => {
    const U = 'd961c662-0000-4000-8000-000000000001'

    it('submissionActorText: the name; removed user for a uuid without one; nothing for neither', () => {
        expect(submissionActorText('Ada Lovelace', U)).toBe('Ada Lovelace')
        expect(submissionActorText(null, U)).toBe('removed user')
        expect(submissionActorText('', U)).toBe('removed user')
        expect(submissionActorText(null, null)).toBe('')
        expect(submissionActorText(undefined, undefined)).toBe('')
    })

    it('submissionKindDisabledText names the kind, product or component, and the setting', () => {
        expect(submissionKindDisabledText('FDA_PREMARKET', 'PRODUCT'))
            .toBe('FDA premarket is switched off for this product; switch it on under Settings > Regulatory and try again.')
        expect(submissionKindDisabledText('FDA_PREMARKET', 'COMPONENT'))
            .toBe('FDA premarket is switched off for this component; switch it on under Settings > Regulatory and try again.')
    })

    // Round 4 (D8, T5) and functional F-3: the cancelled block names the canceller by the same rule.
    it('submissionCancelNote names the canceller through submissionActorText, never the uuid', () => {
        const at = '2026-10-03T00:00:00Z'
        const other = 'aaaaaaaa-0000-4000-8000-000000000002'
        const noName = submissionCancelNote({ state: 'CANCELLED', lastActBy: other, lastActByName: 'Someone Else',
            acts: [{ kind: 'CANCEL', actedAt: at, actor: U, actorName: null, reasonText: 'recalled' }] })
        expect(noName).toEqual({ lead: 'Cancelled on ' + submissionDateText(at) + ' by removed user:', reason: 'recalled' })
        expect(noName!.lead).not.toContain(U)
        const named = submissionCancelNote({ state: 'CANCELLED',
            acts: [{ kind: 'CANCEL', actedAt: at, actor: U, actorName: 'Bob', reasonText: 'r' }] })
        expect(named!.lead).toBe('Cancelled on ' + submissionDateText(at) + ' by Bob:')
        const legacy = submissionCancelNote({ state: 'CANCELLED', legacyState: 'WITHDRAWN', acts: [],
            withdrawnDate: '2026-09-03T00:00:00Z', withdrawReason: 'old' })
        expect(legacy).toEqual({ lead: 'Withdrawn on ' + submissionDateText('2026-09-03T00:00:00Z') + ':', reason: 'old' })
    })

    it('F-3: a canceller with no stored name who is the head\'s last actor reads as the head\'s resolved name', () => {
        const at = '2026-10-03T00:00:00Z'
        const note = submissionCancelNote({ state: 'CANCELLED', lastActBy: U, lastActByName: 'ada@org.example',
            acts: [{ kind: 'CANCEL', actedAt: at, actor: U, actorName: '', reasonText: 'r' }] })
        expect(note!.lead).toBe('Cancelled on ' + submissionDateText(at) + ' by ada@org.example:')
        expect(note!.lead).not.toContain(U)
    })

    it('submissionVersionRows carries createdByName', () => {
        const rows = submissionVersionRows({ versions: [version(1, { createdBy: U, createdByName: 'Ada Lovelace' }),
            version(2, { createdBy: U })] })
        expect(rows.map(r => r.createdByName)).toEqual([null, 'Ada Lovelace'])
        expect(rows.map(r => r.createdBy)).toEqual([U, U])
    })
})
