// Regulatory submissions (task TEA-30, design 4.6 rows 30, 31 and 35): the product page's rows, the
// lifecycle rule, the version and part rows, the reason check, the confirmations with names in text
// only, the act error text and the History text over the four shapes the server writes.
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest'

vi.mock('./fetchClient', () => ({ fetchWithAuth: vi.fn(), fetchArrayBufferWithAuth: vi.fn() }))

const {
    canCreateSubmission, manifestDownloadRef, manifestFileName, submissionActErrorText,
    submissionHistoryText, submissionOtherRows, submissionPartRows, submissionReasonError, submissionReleaseRows,
    submissionVersionRows, submissionStateText,
    submissionStateTagType, submissionStateNote, submissionDateText, SUBMISSION_STATE_LABELS,
    submissionActorText, submissionProfileLabel, submissionInStateFilter,
    SUBMISSION_FILTERED_OUT_TEXT, submissionRefLabel, submissionWrittenText, CREATE_LIFECYCLE_REASON,
    SUBMISSION_ACT_KIND_LABELS, NEW_VERSION_REASON_LABELS, DRAFT_STATE_LABELS, DRAFT_JOB_STATE_LABELS,
    submissionLastActWord, submissionCancelConfirm, submissionReleaseLabel,
} = await import('./submissions')
const submissionsModule = await import('./submissions')
const { historyActionLabel } = await import('./fdaLabels')
const { expectSwept, UUID_RE } = await import('./__fixtures__/fdaSweep')
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

// FDAW-63 row 1 (REQ-1): the product panel's client-side state filter and its marker text.
describe('the product panel state filter (FDAW-63)', () => {
    it('passes every head with no filter, and a head in the filtered state only', () => {
        const s1 = heads[0]
        expect(submissionInStateFilter(s1, null)).toBe(true)
        expect(submissionInStateFilter(s1, '')).toBe(true)
        expect(submissionInStateFilter(s1, undefined)).toBe(true)
        expect(submissionInStateFilter(s1, 'FINAL')).toBe(true)
        expect(submissionInStateFilter(s1, 'CANCELLED')).toBe(false)
        expect(submissionInStateFilter(null, 'FINAL')).toBe(false)
    })

    it('names the marker Not in filter', () => {
        expect(SUBMISSION_FILTERED_OUT_TEXT).toBe('Not in filter')
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
        expect(rows[0].settingsLabel).toBe('Organization settings, revision 3')
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
            .toBe('fda (organization settings, revision 3)')
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
        expect(rows[0].settingsLabel).toBe('Organization settings, revision 3')
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

    it('answers the gateway sentence for a 503 and the parsed message otherwise', () => {
        expect(submissionActErrorText({ networkError: { statusCode: 503 }, message: 'x' })).toBe(TEA_ACT_GATEWAY_TEXT)
        expect(submissionActErrorText(new Error('Release widget 1.2.0 (u): the submission is at v3, not v2; reload it before resubmitting')))
            .toBe('Release widget 1.2.0 (u): the submission is at v3, not v2; reload it before resubmitting')
    })

    it('answers the refusal alone for the two-entry rejection of a non-null act (round 4, 3.7.1)', () => {
        const refusal = 'Release widget 1.2.0 (u): the submission is at v3, not v2; reload it before resubmitting'
        const NULL_TEXT = "The field at path '/finalizeDraft' was declared as a non null type, but the code involved "
            + 'in retrieving data has wrongly returned a null value.  The graphql specification requires that the parent '
            + 'field be set to null, or if that is non nullable that it bubble up null to its parent and so on. The '
            + "non-nullable type is 'SubmissionActResult' within parent type 'Mutation'"
        const err = Object.assign(new Error(refusal + '\n' + NULL_TEXT), { errors: [
            { message: refusal, extensions: { classification: 'BAD_REQUEST' } },
            { message: NULL_TEXT, path: ['finalizeDraft'], extensions: { classification: 'NullValueInNonNullableField' } },
        ] })
        expect(submissionActErrorText(err)).toBe(refusal)
    })
})

describe('the History text (row 35)', () => {
    // FDAW-78 row 12 (REQ-66): the Action word and the details in words, from the unchanged stored values.
    const row = (rua: string | null, oldValue: string | null, newValue: string | null, objectId = A1) =>
        ({ rus: 'SUBMISSION', rua, oldValue, newValue, objectId })
    const both = (r: any) => [historyActionLabel(r), submissionHistoryText(r)]

    it('the eight shapes of the table in 3.4, in order', () => {
        const outs = [
            both(row('ADDED', null, 'FDA_PREMARKET v1')),
            both(row('CHANGED', 'FDA_PREMARKET v1', 'FDA_PREMARKET v2')),
            both(row('CHANGED', 'CANCELLED', 'FINAL v3')),
            both(row('REMOVED', 'FINAL v2', 'CANCELLED')),
            both(row('REMOVED', 'ACTIVE v1', 'WITHDRAWN')),
            both(row('ADDED', null, 'FDA_PREMARKET draft')),
            both(row('REMOVED', 'FDA_PREMARKET draft', null)),
            both(row('CHANGED', 'x y z', 'q')),
        ]
        expect(outs).toEqual([
            ['Created', 'FDA premarket submission: version 1 created'],
            ['Created', 'FDA premarket submission: version 2 created, replacing version 1'],
            ['Created', 'Regulatory submission: version 3 created after a cancellation'],
            ['Cancelled', 'Regulatory submission: version 2 cancelled'],
            ['Withdrawn', 'Regulatory submission: version 1 withdrawn'],
            ['Draft started', 'FDA premarket submission: draft started'],
            ['Draft discarded', 'FDA premarket submission: draft discarded'],
            ['Changed', 'Regulatory submission updated'],
        ])
        expectSwept(outs.flat().join('\n'))
    })

    it('an unknown kind humanized; an empty value never the uuid; null rows do not throw', () => {
        expect(submissionHistoryText(row('ADDED', null, 'EU_MDR v1'))).toBe('Eu mdr submission: version 1 created')
        const empty = row('ADDED', null, '', '3bef7cde-2edb-47cb-aa4e-94487c2cdced')
        expect(both(empty)).toEqual(['Added', 'Regulatory submission updated'])
        expect(submissionHistoryText({ rua: 'CHANGED', oldValue: 'x y z', newValue: 'q' })).toBe('Regulatory submission updated')
        expect(historyActionLabel(null)).toBe('')
        expect(historyActionLabel(undefined)).toBe('')
        expect(submissionHistoryText(null)).toBe('Regulatory submission updated')
        expect(submissionHistoryText(undefined)).toBe('Regulatory submission updated')
        expect(submissionHistoryText(empty)).not.toMatch(UUID_RE)
        expect(empty.objectId).toMatch(UUID_RE)
    })

    // FDAW-10 row 20 (REQ-200): a version recorded as sent, superseded by a replacement, withdrawn at FDA.
    it('FDAW-10 row 20: Sent, Superseded and Withdrawn in words; an unknown shape still falls back', () => {
        const outs = [
            both(row('CHANGED', 'FINAL v1', 'SENT v1')),
            both(row('CHANGED', 'SENT v1', 'SUPERSEDED v1')),
            both(row('REMOVED', 'SENT v1', 'WITHDRAWN')),
        ]
        expect(outs).toEqual([
            ['Sent', 'Regulatory submission: version 1 sent'],
            ['Superseded', 'Regulatory submission: version 1 superseded'],
            ['Withdrawn', 'Regulatory submission: version 1 withdrawn'],
        ])
        expectSwept(outs.flat().join('\n'))
        // two states of different versions is not this shape
        expect(both(row('CHANGED', 'SENT v1', 'SUPERSEDED v2'))).toEqual(['Changed', 'Regulatory submission updated'])
        expect(both(row('CHANGED', 'x y z', 'q'))).toEqual(['Changed', 'Regulatory submission updated'])
    })

    it('other scopes: the action label, an unknown action humanized', () => {
        expect(historyActionLabel({ rus: 'LIFECYCLE', rua: 'CHANGED' })).toBe('Changed')
        expect(historyActionLabel({ rus: 'ARTIFACT', rua: 'UPDATED' })).toBe('Updated')
        expect(historyActionLabel({ rus: 'NOTES', rua: 'ARCHIVED' })).toBe('Archived')
        expectSwept(['Changed', 'Updated', 'Archived', submissionHistoryText(row('ADDED', null, 'EU_MDR v1'))].join('\n'))
    })
})

/** FDAW-78 row 14 (REQ-67): the settings reference, the act result and the lifecycle reason in words. */
describe('the Create dialogs in words (FDAW-78 row 14)', () => {
    it('submissionRefLabel names the source and the revision in words', () => {
        expect(submissionRefLabel({ source: 'COMPONENT', row: 'x', revision: 0 }, {}, 'product')).toBe('this product\'s own settings, revision 0')
        expect(submissionRefLabel(null)).toBe('built-in defaults')
        expect(submissionProfileLabel({ name: 'fda', row: 'p1', revision: 2, source: 'ORGANIZATION' })).toBe('fda (organization settings, revision 2)')
    })

    it('the act result, the lifecycle reason and the Settings cell', () => {
        expect(submissionWrittenText({ version: { version: 1 }, outcome: 'CREATED' })).toBe('Version 1 created.')
        expect(submissionWrittenText({ version: { version: 2 }, outcome: 'RESUBMITTED' })).toBe('Version 2 created.')
        expect(submissionWrittenText({})).toBe('Submission created.')
        expect(submissionWrittenText(null)).toBe('Submission created.')
        expect(CREATE_LIFECYCLE_REASON).toBe('The release must be Assembled or later')
        const rows = submissionVersionRows({ versions: [version(1, { settings: { row: 'r', revision: 0, source: 'COMPONENT' } })] }, {}, 'product')
        expect(rows[0].settingsLabel).toBe('This product\'s own settings, revision 0')
    })
})

describe('the head state texts (FDAW-8)', () => {
    it('labels the six states and the CANCELLED outcome', () => {
        expect(SUBMISSION_STATE_LABELS).toEqual({ FINAL: 'Final', SENT: 'Sent', AMENDED: 'Amended', SUPERSEDED: 'Superseded',
            WITHDRAWN: 'Withdrawn', CANCELLED: 'Cancelled' })
        // FDAW-32 row 3 (a): the four label tables of the draft flow, as whole objects
        expect(SUBMISSION_ACT_KIND_LABELS).toEqual({ START_DRAFT: 'Draft started', DISCARD_DRAFT: 'Draft discarded',
            RETURN_TO_DRAFT: 'Returned to draft', CONFIRM: 'Confirmed', FINALIZE: 'Finalized', RECORD_SENT: 'Recorded as sent',
            FDA_NUMBER_ADDED: 'FDA number added', TIMELINE_ENTRY: 'Timeline entry', AMENDED: 'Amended', SUPERSEDED: 'Superseded',
            WITHDRAW: 'Withdrawn', CANCEL: 'Cancelled', MIGRATED: 'Migrated' })
        expect(NEW_VERSION_REASON_LABELS).toEqual({ FIRST_VERSION: 'First version', FDA_REQUEST_RESPONSE: 'Response to an FDA request',
            CORRECTION: 'Correction', OTHER: 'Other' })
        expect(DRAFT_STATE_LABELS).toEqual({ EDITING: 'Editing', AWAITING_CONFIRMATION: 'Awaiting second confirmation' })
        expect(DRAFT_JOB_STATE_LABELS).toEqual({ NONE: 'Not built yet', REQUESTED: 'Rebuild requested', RUNNING: 'Rebuilding',
            BUILT: 'Built', FAILED: 'Rebuild failed' })
    })

    it('reads FINAL without a confirmation record, a migrated head as legacy, and any other state as its label', () => {
        expect(submissionStateText({ state: 'FINAL', legacyState: null })).toBe('Final (no confirmation record) - not sent')
        expect(submissionStateText({ state: 'FINAL', legacyState: 'ACTIVE' })).toBe('Final (legacy, no confirmation record) - not sent')
        const d = '2026-09-03T00:00:00Z'
        expect(submissionStateText({ state: 'CANCELLED', legacyState: 'WITHDRAWN', withdrawnDate: d }))
            .toBe('Cancelled (legacy: withdrawn ' + submissionDateText(d) + ')')
        expect(submissionStateText({ state: 'CANCELLED', legacyState: null })).toBe('Cancelled')
        expect(submissionStateText({ state: 'SUPERSEDED' })).toBe('Superseded')
        // FDAW-78 X1: an unknown state reads humanized
        expect(submissionStateText({ state: 'SOMETHING_NEW' })).toBe('Something new')
        // FDAW-32 row 3 (b): Final - not sent from the newest version's confirmations only
        expect(submissionStateText({ state: 'FINAL', latestVersion: 2, versions: [{ version: 1, confirmations: [] },
            { version: 2, confirmations: [{ confirmedAt: 'x' }] }] })).toBe('Final - not sent')
        expect(submissionStateText({ state: 'FINAL', latestVersion: 2, versions: [{ version: 1, confirmations: [{ confirmedAt: 'x' }] },
            { version: 2, confirmations: [] }] })).toBe('Final (no confirmation record) - not sent')
        const latest = { version: 2, confirmations: [{ confirmedAt: 'x' }] }
        expect(submissionStateText({ state: 'FINAL', latestVersion: 2, latest })).toBe('Final - not sent')
        expect(submissionStateText({ state: 'FINAL', latestVersion: 2, legacyState: 'ACTIVE', latest })).toBe('Final - not sent')
        expect(submissionStateText({ state: 'FINAL', latestVersion: 2, legacyState: 'ACTIVE', latest: { version: 2, confirmations: [] } }))
            .toBe('Final (legacy, no confirmation record) - not sent')
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
        expect(submissionStateNote({ state: 'CANCELLED', acts })).toEqual({
            lead: 'Cancelled on ' + submissionDateText('2026-10-03T00:00:00Z') + ' by Bob:', reason: 'newest' })
        expect(submissionStateNote({ state: 'CANCELLED', legacyState: 'WITHDRAWN', acts: [],
            withdrawnDate: '2026-09-03T00:00:00Z', withdrawReason: 'old' }))
            .toEqual({ lead: 'Withdrawn on ' + submissionDateText('2026-09-03T00:00:00Z') + ':', reason: 'old' })
        expect(submissionStateNote({ state: 'FINAL', acts })).toBeNull()
        expect(submissionStateNote({ state: 'CANCELLED', acts: [] })).toBeNull()
        // a WITHDRAWN head never reads the legacy triple: V107 moved every legacy withdrawal to CANCELLED
        expect(submissionStateNote({ state: 'WITHDRAWN', acts: [], withdrawnDate: '2026-09-03T00:00:00Z', withdrawReason: null }))
            .toBeNull()
        expect(submissionStateNote({ state: 'WITHDRAWN', acts: [], withdrawnDate: null, withdrawReason: 'old, no date' }))
            .toBeNull()
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

    // Round 4 (D8, T5) and functional F-3: the cancelled block names the canceller by the same rule.
    it('submissionStateNote names the canceller through submissionActorText, never the uuid', () => {
        const at = '2026-10-03T00:00:00Z'
        const other = 'aaaaaaaa-0000-4000-8000-000000000002'
        const noName = submissionStateNote({ state: 'CANCELLED', lastActBy: other, lastActByName: 'Someone Else',
            acts: [{ kind: 'CANCEL', actedAt: at, actor: U, actorName: null, reasonText: 'recalled' }] })
        expect(noName).toEqual({ lead: 'Cancelled on ' + submissionDateText(at) + ' by removed user:', reason: 'recalled' })
        expect(noName!.lead).not.toContain(U)
        const named = submissionStateNote({ state: 'CANCELLED',
            acts: [{ kind: 'CANCEL', actedAt: at, actor: U, actorName: 'Bob', reasonText: 'r' }] })
        expect(named!.lead).toBe('Cancelled on ' + submissionDateText(at) + ' by Bob:')
        const legacy = submissionStateNote({ state: 'CANCELLED', legacyState: 'WITHDRAWN', acts: [],
            withdrawnDate: '2026-09-03T00:00:00Z', withdrawReason: 'old' })
        expect(legacy).toEqual({ lead: 'Withdrawn on ' + submissionDateText('2026-09-03T00:00:00Z') + ':', reason: 'old' })
    })

    it('F-3: a canceller with no stored name who is the head\'s last actor reads as the head\'s resolved name', () => {
        const at = '2026-10-03T00:00:00Z'
        const note = submissionStateNote({ state: 'CANCELLED', lastActBy: U, lastActByName: 'ada@org.example',
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

// FDAW-32 row 3 (c) to (e): the last act's word, the Cancel dialog and the release label with every name on
// one line, and the one-click names gone from the module.
describe('the draft flow words (FDAW-32 row 3)', () => {
    const NAMES = { componentName: 'Pump', releaseVersion: '1.2.0' }
    const BAD = { componentName: 'Pu\nmp\u202E', releaseVersion: ' 1.2.0\t' }

    it('(c) submissionLastActWord from lastAct, Last act without one, an unknown kind humanized', () => {
        expect(submissionLastActWord({ lastAct: { kind: 'FINALIZE' } })).toBe('Finalized')
        expect(submissionLastActWord({ lastAct: { kind: 'CANCEL' } })).toBe('Cancelled')
        expect(submissionLastActWord({ lastAct: null })).toBe('Last act')
        expect(submissionLastActWord({})).toBe('Last act')
        expect(submissionLastActWord({ lastAct: { kind: 'SOMETHING_NEW' } })).toBe('Something new')
    })

    it('(d) submissionCancelConfirm and submissionReleaseLabel, names on one line', () => {
        expect(submissionCancelConfirm({ kind: 'FDA_PREMARKET', latestVersion: 2 }, NAMES)).toEqual({ titleText: 'Cancel submission?',
            text: 'Cancels the FDA premarket submission of Pump 1.2.0 at v2. Every version and document is kept; the version is '
                + 'marked cancelled, and a new draft writes the next version.', confirmButtonText: 'Cancel submission' })
        expect(submissionCancelConfirm({ kind: 'FDA_PREMARKET', latestVersion: 2 }, BAD).text)
            .toMatch(/^Cancels the FDA premarket submission of Pu mp 1\.2\.0 at v2\./)
        expect(submissionReleaseLabel({ componentName: null, releaseVersion: '1.2.0' })).toBe('1.2.0')
    })

    it('(e) the one-click names are not exported', () => {
        const keys = Object.keys(submissionsModule)
        expect(keys).toContain('submissionCancelConfirm')
        for (const k of ['SUBMISSION_ACT_LABELS', 'submissionCreateConfirm', 'submissionResubmitConfirm', 'submissionWithdrawConfirm',
            'submissionKindDisabledText']) expect(keys, k).not.toContain(k)
    })
})

// FDAW-10 row 17 (REQ-200): the Regulatory card's texts after Record as sent, the label maps of the four
// acts, the note under a head and the line of an earlier Sent version.
describe('the Regulatory card texts (FDAW-10 row 17)', () => {
    const {
        FDA_SUBMISSION_TYPE_LABELS, TIMELINE_ENTRY_KIND_LABELS, FDA_DECISION_LABELS, submissionDayText, localDayOf,
        submissionSentLine, submissionVersionStateText,
    } = submissionsModule
    const W = 'aaaaaaaa-0000-4000-8000-00000000000a'
    const sent = (changes: Record<string, any> = {}) => ({ state: 'SENT', latestVersion: 1, currentSentVersion: 1,
        versionStates: [{ version: 1, state: 'SENT', sentDate: '2026-11-15' }], ...changes })

    it('the three label maps cover their Pro enums exactly, in order', async () => {
        const { enumValuesOf, proSchema } = await import('./schemaDriftSupport')
        expect(FDA_SUBMISSION_TYPE_LABELS).toEqual({ K510_TRADITIONAL: '510(k) Traditional', K510_SPECIAL: '510(k) Special',
            K510_ABBREVIATED: '510(k) Abbreviated', DE_NOVO: 'De Novo', PMA: 'PMA', PMA_SUPPLEMENT: 'PMA supplement', PDP: 'PDP',
            HDE: 'HDE', PRE_SUBMISSION: 'Pre-Submission' })
        expect(TIMELINE_ENTRY_KIND_LABELS).toEqual({ AI_REQUEST_RECEIVED: 'AI request received', RESPONSE_SENT: 'Response sent',
            DECISION: 'Decision' })
        expect(FDA_DECISION_LABELS).toEqual({ CLEARED: 'Cleared', NOT_CLEARED: 'Not cleared' })
        if (proSchema) {
            expect(Object.keys(FDA_SUBMISSION_TYPE_LABELS)).toEqual(enumValuesOf(proSchema, 'FdaSubmissionType'))
            expect(Object.keys(TIMELINE_ENTRY_KIND_LABELS)).toEqual(enumValuesOf(proSchema, 'TimelineEntryKind'))
            expect(Object.keys(FDA_DECISION_LABELS)).toEqual(enumValuesOf(proSchema, 'FdaDecision'))
        }
    })

    it('each state text from the entry of latestVersion: SENT with and without a number, WITHDRAWN, AMENDED, SUPERSEDED', () => {
        expect(submissionStateText(sent())).toBe('Sent to FDA 2026-11-15')
        expect(submissionStateText(sent({ fdaNumber: 'K263456' }))).toBe('Sent to FDA 2026-11-15 (K263456)')
        expect(submissionStateText({ state: 'WITHDRAWN', latestVersion: 1,
            versionStates: [{ version: 1, state: 'WITHDRAWN', sentDate: '2026-11-15', withdrawnDate: '2026-11-20' }] }))
            .toBe('Withdrawn at FDA 2026-11-20')
        expect(submissionStateText({ state: 'AMENDED', latestVersion: 1, versionStates: [{ version: 1, state: 'AMENDED', byVersion: 2 }] }))
            .toBe('Amended by v2')
        expect(submissionStateText({ state: 'SUPERSEDED', latestVersion: 1,
            versionStates: [{ version: 1, state: 'SUPERSEDED', replacedBy: { releaseVersion: '2.4.1', version: 1 } }] }))
            .toBe('Superseded by 2.4.1 v1')
        expect(submissionStateText({ state: 'SUPERSEDED', latestVersion: 1, versionStates: [{ version: 1, state: 'SUPERSEDED', byVersion: 3 }] }))
            .toBe('Superseded by v3')
        // the dates of another version are never the head's
        expect(submissionStateText({ state: 'SENT', latestVersion: 2, currentSentVersion: 2,
            versionStates: [{ version: 1, state: 'AMENDED', sentDate: '2026-11-01', byVersion: 2 }, { version: 2, state: 'SENT', sentDate: '2026-11-15' }] }))
            .toBe('Sent to FDA 2026-11-15')
        for (const s of ['AMENDED', 'SUPERSEDED']) expect(submissionStateTagType(s), s).toBe('default')
    })

    it('the version history State cell: each entry by the texts of the tag, FINAL by that version\'s confirmations', () => {
        const head = { state: 'FINAL', latestVersion: 2, currentSentVersion: 1, fdaNumber: 'K263456',
            versionStates: [{ version: 1, state: 'SENT', sentDate: '2026-11-15' }, { version: 2, state: 'FINAL' }] }
        expect(submissionVersionStateText(head, { version: 1 })).toBe('Sent to FDA 2026-11-15 (K263456)')
        expect(submissionVersionStateText(head, { version: 2, confirmations: [{ confirmedAt: 'x' }] })).toBe('Final - not sent')
        expect(submissionVersionStateText(head, { version: 3 })).toBe('')
    })

    it('F-4: the line of an earlier Sent version, on a FINAL and on a CANCELLED head; none on a SENT head or without one', () => {
        const states = [{ version: 1, state: 'SENT', sentDate: '2026-11-15' }, { version: 2, state: 'FINAL' }]
        expect(submissionSentLine({ state: 'FINAL', latestVersion: 2, currentSentVersion: 1, fdaNumber: 'K263456', versionStates: states }))
            .toBe('v1 sent to FDA 2026-11-15 (K263456)')
        expect(submissionSentLine({ state: 'CANCELLED', latestVersion: 2, currentSentVersion: 1, fdaNumber: 'K263456',
            versionStates: [states[0], { version: 2, state: 'CANCELLED' }] })).toBe('v1 sent to FDA 2026-11-15 (K263456)')
        expect(submissionSentLine(sent({ fdaNumber: 'K263456' }))).toBeNull()
        expect(submissionSentLine({ state: 'FINAL', latestVersion: 2, currentSentVersion: null, versionStates: states })).toBeNull()
    })

    it('F-2: the note is the act that made the state, on the latest version only', () => {
        const withdraw = { uuid: 'w1', kind: 'WITHDRAW', cause: 'USER', version: 1, declaredDate: '2026-11-20', actor: W, actorName: 'Ann',
            actedAt: '2026-11-21T10:00:00Z', reasonText: 'pulled' }
        const plain = { state: 'WITHDRAWN', latestVersion: 1, withdrawnDate: '2026-01-01T00:00:00Z', withdrawReason: 'legacy',
            acts: [{ uuid: 's1', kind: 'RECORD_SENT', cause: 'USER', version: 1 }, withdraw] }
        expect(submissionStateNote(plain)).toEqual({ lead: 'Withdrawn at FDA on 2026-11-20 by Ann:', reason: 'pulled' })
        // v1 cancelled, v2 finalized, sent and withdrawn: the WITHDRAW of v2, never v1's CANCEL
        const twice = { state: 'WITHDRAWN', latestVersion: 2, acts: [
            { uuid: 'c1', kind: 'CANCEL', cause: 'USER', version: 1, actedAt: '2026-11-02T00:00:00Z', actorName: 'Bob', reasonText: 'v1 off' },
            { ...withdraw, version: 2 }] }
        expect(submissionStateNote(twice)).toEqual({ lead: 'Withdrawn at FDA on 2026-11-20 by Ann:', reason: 'pulled' })
        // a CANCELLED head: the CANCEL of latestVersion, the older CANCEL of v1 ignored though it is newer in the list
        const cancelled = { state: 'CANCELLED', latestVersion: 2, acts: [
            { uuid: 'c2', kind: 'CANCEL', cause: 'USER', version: 2, actedAt: '2026-11-03T00:00:00Z', actorName: 'Cy', reasonText: 'v2 off' },
            { uuid: 'c1', kind: 'CANCEL', cause: 'USER', version: 1, actedAt: '2026-11-04T00:00:00Z', actorName: 'Bob', reasonText: 'v1 off' }] }
        expect(submissionStateNote(cancelled)).toEqual({ lead: 'Cancelled on ' + submissionDateText('2026-11-03T00:00:00Z') + ' by Cy:',
            reason: 'v2 off' })
        // a legacy CANCELLED head with no CANCEL act: the triple, as today
        expect(submissionStateNote({ state: 'CANCELLED', latestVersion: 1, legacyState: 'WITHDRAWN', acts: [],
            withdrawnDate: '2026-09-03T00:00:00Z', withdrawReason: 'old' }))
            .toEqual({ lead: 'Withdrawn on ' + submissionDateText('2026-09-03T00:00:00Z') + ':', reason: 'old' })
        expect(submissionStateNote(sent({ acts: [withdraw] }))).toBeNull()
        expect(submissionStateNote({ state: 'FINAL', latestVersion: 1, acts: [withdraw] })).toBeNull()
    })

    it('F-12: submissionCancelNote is no longer exported', () => {
        const keys = Object.keys(submissionsModule)
        expect(keys).toContain('submissionStateNote')
        expect(keys).not.toContain('submissionCancelNote')
    })

    describe('in America/New_York, west of UTC (F-1)', () => {
        let zone: string | undefined
        beforeAll(() => {
            zone = process.env.TZ
            process.env.TZ = 'America/New_York'
        })
        afterAll(() => {
            if (zone === undefined) delete process.env.TZ
            else process.env.TZ = zone
            vi.useRealTimers()
        })

        it('the zone took effect: a date-only string reads as the day before', () => {
            expect(new Date('2026-11-15').getDate()).toBe(14)
        })

        it('a declared day prints as itself, never through Date', () => {
            expect(submissionStateText(sent())).toBe('Sent to FDA 2026-11-15')
            expect(submissionDayText('2026-11-15')).toBe('2026-11-15')
            for (const d of ['2026-11-15T00:00:00Z', '', null, '15/11/2026']) expect(submissionDayText(d as any), String(d)).toBe('')
        })

        it('localDayOf is the local day at the fake clock, not the UTC day', () => {
            vi.useFakeTimers()
            vi.setSystemTime(new Date('2026-11-16T03:30:00Z'))
            expect(localDayOf(Date.now())).toBe('2026-11-15')
            vi.useRealTimers()
        })
    })
})
