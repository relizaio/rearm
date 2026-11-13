// The pure helpers of the draft card, the Start form and the draft dialogs (task FDAW-32 architecture-2
// section 4 row 2): the moved-line labels, the stale banner, the five buttons and the reasons they are
// disabled for, the poll delays, the diff, the parts and their file names, the Start form helpers, the
// record and confirmation lines, and the dialog objects with every name on one line.
import { describe, expect, it, vi } from 'vitest'
vi.mock('./fetchClient', () => ({ fetchWithAuth: vi.fn(), fetchArrayBufferWithAuth: vi.fn() }))
import * as D from './submissionDrafts'
import { expectSwept } from './__fixtures__/fdaSweep'

const U1 = '11111111-1111-4111-8111-111111111111'
const HOSTILE = '<img src=x onerror="window.__hit=1"><b>b</b>'
const A64 = 'a'.repeat(64)
const draft = (c: any = {}) => ({ uuid: 'd1', org: 'o1', release: 'r1', component: 'c1', kind: 'FDA_PREMARKET', submission: null,
    state: 'EDITING', revision: 7, plannedVersion: 2, startedAt: '2026-11-13T05:00:00Z', startedBy: { actor: 'u1', actorName: 'Ann' },
    reasonCategory: 'CORRECTION', reasonText: 'fix', replaces: null, job: { state: 'BUILT', attempt: 1 },
    build: { builtAt: '2026-11-13T06:07:08.123Z', releaseSbom: { version: 3 }, parts: [
        { part: 'AGGREGATE_SBOM', artifact: U1, sha256: A64, sizeBytes: 10, documentVersion: 3 },
        { part: 'VDR', artifact: 'not-a-uuid', sha256: null, sizeBytes: null, documentVersion: null }], diff: null },
    confirmation: null, changesSinceBuild: { count: 0, fingerprintEqual: true, movedLines: [] }, ...c })
const CHANGED = { count: 3, fingerprintEqual: false, movedLines: ['narrative:' + U1] }
const AWAIT = { state: 'AWAITING_CONFIRMATION', confirmation: { actor: 'u1', actorName: 'Ann', capacity: 'AUTHOR', confirmedAt: '2026-11-13T06:09:10Z' } }
const b = (d: any, o: any) => {
    const r: any = D.draftButtons(d, { writable: true, busy: false, lifecycle: 'ASSEMBLED', ...o })
    return ['rebuild', 'discard', 'confirm', 'returnToEditing', 'finalize']
        .map(k => (r[k].shown ? (r[k].disabled ? 'off' : 'on') : '-') + (r[k].title ? '(' + r[k].title + ')' : '')).join(' ')
}
const T16 = 'Rebuild first'
const T27 = 'A rebuild of this draft is in progress; wait for it to finish'
const T29 = 'The second confirmation must come from a different person than the first; ask another member to finalize'
const T28 = 'Return the draft to editing and rebuild; the inputs changed since this build'
const LIFE = 'The release must be Assembled or later'
const PLAN = 'This draft was planned as version 2, but the package already has version 2; discard this draft and start a new one'

describe('row 2 (c) draftButtons', () => {
    it('round 1 cells', () => {
        expect(b(draft(), { secondConfirmer: false })).toBe('on on - - on')
        expect(b(draft(), { secondConfirmer: null })).toBe('on on - - on')
        expect(b(draft(), { secondConfirmer: true })).toBe('on on on - -')
        expect(b(draft(AWAIT), { secondConfirmer: true })).toBe('- - - on on')
        expect(b(draft(AWAIT), { secondConfirmer: false })).toBe('- - - on on')
        expect(b(draft({ job: { state: 'FAILED' } }), { secondConfirmer: true })).toBe('on on off(' + T16 + ') - -')
        expect(b(draft({ job: { state: 'NONE' }, build: null }), { secondConfirmer: false })).toBe('on on - - off(' + T16 + ')')
        expect(b(draft(), { busy: true, secondConfirmer: false })).toBe('off off - - off')
        expect(b(draft(), { writable: false, secondConfirmer: true })).toBe('- - - - -')
        expect(b(draft({ state: 'SOMETHING_NEW' }), { secondConfirmer: true })).toBe('- - - - -')
    })
    it('a job in flight (F-6)', () => {
        expect(b(draft({ job: { state: 'REQUESTED' } }), { secondConfirmer: false })).toBe('on off(' + T27 + ') - - off(' + T27 + ')')
        expect(b(draft({ job: { state: 'RUNNING' } }), { secondConfirmer: false })).toBe('on on - - off(' + T27 + ')')
        expect(b(draft({ job: { state: 'RUNNING' } }), { secondConfirmer: true })).toBe('on on off(' + T27 + ') - -')
        expect(b(draft({ job: { state: 'REQUESTED' }, build: null }), { secondConfirmer: true })).toBe('on off(' + T27 + ') off(' + T27 + ') - -')
    })
    it('a changed banner (F-4)', () => {
        expect(b(draft({ changesSinceBuild: CHANGED }), { secondConfirmer: false })).toBe('on on - - off(' + T16 + ')')
        expect(b(draft({ changesSinceBuild: CHANGED }), { secondConfirmer: true })).toBe('on on off(' + T16 + ') - -')
        expect(b(draft({ ...AWAIT, changesSinceBuild: CHANGED }), { secondConfirmer: true, currentUser: 'u2' })).toBe('- - - on off(' + T28 + ')')
        expect(b(draft({ ...AWAIT, changesSinceBuild: CHANGED }), { secondConfirmer: false })).toBe('- - - on off(' + T28 + ')')
        // equal fingerprint with a count is not a change
        expect(b(draft({ changesSinceBuild: { count: 4, fingerprintEqual: true, movedLines: [] } }), { secondConfirmer: false })).toBe('on on - - on')
    })
    it('the confirmer (F-7)', () => {
        expect(b(draft(AWAIT), { secondConfirmer: true, currentUser: 'u1' })).toBe('- - - on off(' + T29 + ')')
        expect(b(draft(AWAIT), { secondConfirmer: true, currentUser: 'u2' })).toBe('- - - on on')
        expect(b(draft(AWAIT), { secondConfirmer: true, currentUser: null })).toBe('- - - on on')
        expect(b(draft(AWAIT), { secondConfirmer: false, currentUser: 'u1' })).toBe('- - - on on')
        expect(b(draft(AWAIT), { secondConfirmer: null, currentUser: 'u1' })).toBe('- - - on on')
        expect(b(draft({ ...AWAIT, confirmation: { actor: null, actorName: null } }), { secondConfirmer: true, currentUser: null })).toBe('- - - on on')
    })
    it('lifecycle and plan (self-found)', () => {
        expect(b(draft(), { secondConfirmer: false, lifecycle: 'DRAFT' })).toBe('on on - - off(' + LIFE + ')')
        expect(b(draft(), { secondConfirmer: true, lifecycle: 'DRAFT' })).toBe('on on on - -')
        expect(b(draft(), { secondConfirmer: false, head: { uuid: 's1', latestVersion: 2 } })).toBe('on on - - off(' + PLAN + ')')
        expect(b(draft({ submission: 's1' }), { secondConfirmer: false, head: { uuid: 's1', latestVersion: 1 } })).toBe('on on - - on')
        expect(b(draft({ submission: 's1' }), { secondConfirmer: false, head: { uuid: 's1', latestVersion: 2 } })).toBe('on on - - off(' + PLAN + ')')
        expect(b(draft({ submission: 's1', plannedVersion: 4 }), { secondConfirmer: false, head: { uuid: 's1', latestVersion: 1 } })).toBe('on on - - on')
        expect(b(draft({ submission: 's0' }), { secondConfirmer: false, head: { uuid: 's1', latestVersion: 1 } }))
            .toBe('on on - - off(This draft was planned as version 2, but the package already has version 1; discard this draft and start a new one)')
        expect(b(draft({ submission: 's1' }), { secondConfirmer: false, head: null })).toBe('on on - - on')
        expect(b(draft(), { secondConfirmer: false, head: { uuid: 's1', latestVersion: 1 } }))
            .toBe('on on - - off(This draft was planned as version 2, but the package already has version 1; discard this draft and start a new one)')
    })
    it('order of the reasons', () => {
        // job, then confirmer, then lifecycle, then plan, then changed
        const all = draft({ ...AWAIT, job: { state: 'RUNNING' }, changesSinceBuild: CHANGED })
        const facts = { secondConfirmer: true, currentUser: 'u1', lifecycle: 'DRAFT', head: { uuid: 's1', latestVersion: 2 } }
        expect(b(all, facts)).toBe('- - - on off(' + T27 + ')')
        expect(b({ ...all, job: { state: 'BUILT' } }, facts)).toBe('- - - on off(' + T29 + ')')
        expect(b({ ...all, job: { state: 'BUILT' } }, { ...facts, currentUser: 'u2' })).toBe('- - - on off(' + LIFE + ')')
        expect(b({ ...all, job: { state: 'BUILT' } }, { ...facts, currentUser: 'u2', lifecycle: 'ASSEMBLED' })).toBe('- - - on off(' + PLAN + ')')
        expect(b({ ...all, job: { state: 'BUILT' } }, { ...facts, currentUser: 'u2', lifecycle: 'ASSEMBLED', head: null })).toBe('- - - on off(' + T28 + ')')
    })
})

describe('row 2 others', () => {
    it('(a) moved lines', () => {
        const lines = ['support:pkg:npm/' + HOSTILE + '@1?a=b', 'disclosure:' + U1, 'artifact:' + U1 + ':' + U1,
            'support:pkg:npm/x@2', 'overlay:pkg:npm/x@2', 'merge:version', 'assessments:' + U1, 'zzz']
        expect(D.movedLineLabels(lines)).toEqual(['files of a release changed', 'support data of a component changed',
            'manual or rule overlay changed', 'SBOM merge version changed', 'support disclosure setting changed', 'another input changed'])
        expect(D.movedLineLabels(D.MOVED_LINE_LABELS.map(([p]) => p + ':x'))).toEqual(D.MOVED_LINE_LABELS.map(([, l]) => l))
        expect(D.movedLineLabels([])).toEqual([])
        expect(D.movedLineLabels(null)).toEqual([])
        expectSwept(D.movedLineLabels(lines).join('\n'))
        expect(D.movedLineLabels(lines).join('\n')).not.toContain('img')
    })
    it('(b) banner', () => {
        expect(D.draftStaleBanner(draft({ build: null }))).toEqual({ kind: 'not-built', text: 'Not built yet', labels: [] })
        expect(D.draftStaleBanner(draft())).toEqual({ kind: 'unchanged', text: 'Inputs unchanged', labels: [] })
        expect(D.draftStaleBanner(draft({ changesSinceBuild: { count: 4, fingerprintEqual: true, movedLines: [] } }))).toEqual({ kind: 'unchanged', text: 'Inputs unchanged', labels: [] })
        expect(D.draftStaleBanner(draft({ changesSinceBuild: { count: 3, fingerprintEqual: false, movedLines: ['narrative:' + U1, 'displayed:' + U1] } })))
            .toEqual({ kind: 'changed', text: '3 changes since this build', labels: ['assessment narrative changed', 'displayed version changed'] })
        expect(D.draftStaleBanner(draft({ changesSinceBuild: { count: 1, fingerprintEqual: false, movedLines: [] } })))
            .toEqual({ kind: 'changed', text: '1 changes since this build', labels: [] })
        expect(D.draftStaleBanner(draft({ changesSinceBuild: null })).kind).toBe('changed')
    })
    it('(d) poll', () => {
        expect(D.draftPollDelay(draft({ job: { state: 'REQUESTED' } }), 0)).toBe(5000)
        expect(D.draftPollDelay(draft({ job: { state: 'RUNNING' } }), 179)).toBe(5000)
        expect(D.draftPollDelay(draft({ job: { state: 'RUNNING' } }), 180)).toBe(30000)
        expect(D.draftPollDelay(draft(), 0)).toBe(30000)
        expect(D.draftPollDelay(draft({ job: { state: 'FAILED' } }), 0)).toBe(30000)
        expect(D.draftStillRunning(draft({ job: { state: 'RUNNING' } }), 179)).toBe(false)
        expect(D.draftStillRunning(draft({ job: { state: 'RUNNING' } }), 180)).toBe(true)
        expect(D.draftStillRunning(draft(), 180)).toBe(false)
    })
    it('(e) diff', () => {
        expect(D.draftDiffSummary(null)).toBe('No earlier version to compare with')
        const DIFF = { baseline: { submission: 's0', version: 1 }, added: [{ identity: 'pkg:npm/a', name: 'a', version: '1.0' }, { identity: 'pkg:npm/' + HOSTILE, name: null, version: '2' }],
            addedCount: 600, removed: [], removedCount: 0, changed: [{ identity: 'pkg:npm/c', name: 'c', fromVersion: '1', toVersion: '2' }], changedCount: 1,
            supportChanged: [{ key: 'pkg:npm/c@2', property: 'reliza:support:level' }], supportChangedCount: 1,
            findingsCompared: true, findingsAdded: [{ vulnerabilityId: 'CVE-2026-0001', affectedRef: 'pkg:npm/c@2' }, { vulnerabilityId: 'CVE-2026-0002', affectedRef: null }],
            findingsAddedCount: 2, findingsRemoved: [], findingsRemovedCount: 3 }
        expect(D.draftDiffSummary(DIFF)).toBe('Compared with version 1: 600 added, 0 removed, 1 with a changed version, 1 support changes, 5 findings changed')
        expect(D.draftDiffSummary({ ...DIFF, findingsCompared: false })).toBe('Compared with version 1: 600 added, 0 removed, 1 with a changed version, 1 support changes, findings not compared')
        expect(D.draftDiffSections(DIFF)).toEqual([
            { key: 'added', title: 'Added', count: 600, rows: ['a 1.0', 'pkg:npm/' + HOSTILE + ' 2'], more: 598 },
            { key: 'removed', title: 'Removed', count: 0, rows: [], more: 0 },
            { key: 'changed', title: 'Changed version', count: 1, rows: ['c: 1 to 2'], more: 0 },
            { key: 'support', title: 'Support changes', count: 1, rows: ['pkg:npm/c@2: reliza:support:level'], more: 0 },
            { key: 'findings-added', title: 'Findings added', count: 2, rows: ['CVE-2026-0001 in pkg:npm/c@2', 'CVE-2026-0002'], more: 0 },
            { key: 'findings-removed', title: 'Findings removed', count: 3, rows: [], more: 3 },
        ])
        expect(D.draftDiffSections({ ...DIFF, findingsCompared: false }).map(s => s.key)).toEqual(['added', 'removed', 'changed', 'support'])
        // F-9: more rows served than the count says
        expect(D.draftDiffSections({ ...DIFF, removed: [{ identity: 'pkg:npm/r', name: 'r', version: '9' }], removedCount: 0 })[1])
            .toEqual({ key: 'removed', title: 'Removed', count: 0, rows: ['r 9'], more: 0 })
        expect(D.draftDiffSections(null)).toEqual([])
    })
    it('(f) parts', () => {
        expect(D.draftPartRows(draft())).toEqual([
            { key: 'AGGREGATE_SBOM', label: 'Aggregate SBOM', size: 10, sha256: A64, sha256Short: 'aaaaaaaaaaaa', download: { uuid: U1, fallbackName: 'draft-v2-aggregate-sbom' } },
            { key: 'VDR', label: 'VDR', size: null, sha256: '', sha256Short: '', download: null }])
        expect(D.contentDispositionFileName('attachment; filename="widget-sbom.cdx.json"', 'f')).toBe('widget-sbom.cdx.json')
        expect(D.contentDispositionFileName('attachment; filename="../..\\a/b\u0001.json"', 'f')).toBe('....ab.json')
        for (const h of ['attachment', null, 'attachment; filename=""']) expect(D.contentDispositionFileName(h, 'draft-v2-vdr')).toBe('draft-v2-vdr')
    })
    it('(g) start form', () => {
        expect(D.draftReasonOptions(false)).toEqual([{ label: 'First version', value: 'FIRST_VERSION' }])
        expect(D.draftReasonOptions(true)).toEqual([{ label: 'Response to an FDA request', value: 'FDA_REQUEST_RESPONSE' }, { label: 'Correction', value: 'CORRECTION' }, { label: 'Other', value: 'OTHER' }])
        expect(D.draftStartProblem(null, 'x')).toBe('Choose a reason')
        expect(D.draftStartProblem('CORRECTION', '  ')).toBe('A reason is required')
        expect(D.draftStartProblem('FIRST_VERSION', '')).toBeNull()
        expect(D.draftStartProblem('CORRECTION', 'x')).toBeNull()
        const heads = [
            { uuid: 's2', release: 'r2', releaseVersion: '2.0', versionStates: [{ version: 1, state: 'SUPERSEDED' }, { version: 2, state: 'FINAL' }, { version: 3, state: 'CANCELLED' }] },
            { uuid: 's1', release: 'r1', releaseVersion: '1.0', versionStates: [{ version: 1, state: 'FINAL' }] },
            { uuid: 's3', release: 'r3', releaseVersion: HOSTILE, versionStates: [{ version: 1, state: 'SENT' }, { version: 2, state: 'FINAL' }] },
            { uuid: 's4', release: 'r4', releaseVersion: null, versionStates: [{ version: 1, state: 'AMENDED' }] }]
        expect(D.draftReplacesOptions(heads, 'r1')).toEqual([
            { value: 's2:2', label: 'Release 2.0, version 2 (Final)' },
            { value: 's3:2', label: 'Release ' + HOSTILE + ', version 2 (Final)' },
            { value: 's3:1', label: 'Release ' + HOSTILE + ', version 1 (Sent)' }])
        expect(D.draftStartVariables('r1', 'FDA_PREMARKET', { reasonCategory: 'CORRECTION', reasonText: '  fix \n', replaces: 's3:2' }))
            .toEqual({ release: 'r1', kind: 'FDA_PREMARKET', reasonCategory: 'CORRECTION', reasonText: 'fix', replacesSubmission: 's3', replacesVersion: 2 })
        expect(D.draftStartVariables('r1', 'FDA_PREMARKET', { reasonCategory: 'FIRST_VERSION', reasonText: '   ', replaces: null }))
            .toEqual({ release: 'r1', kind: 'FDA_PREMARKET', reasonCategory: 'FIRST_VERSION', reasonText: null, replacesSubmission: null, replacesVersion: null })
    })
    it('(h) record and confirmation lines (F-5)', () => {
        const c = { actor: U1, actorName: 'Ann', confirmedAt: '2026-11-13T06:07:59.999Z' }
        expect(D.confirmationRecordLine(c)).toBe('Reviewed and confirmed ready for submission by Ann on 2026-11-13 06:07 UTC')
        expect(D.confirmationRecordLine({ ...c, actorName: null })).toBe('Reviewed and confirmed ready for submission by removed user on 2026-11-13 06:07 UTC')
        expect(D.draftConfirmationLine(draft({ confirmation: c }), true)).toBe('Confirmed by Ann on 2026-11-13 06:07 UTC; a different person finalizes')
        expect(D.draftConfirmationLine(draft({ confirmation: c }), false)).toBe('Confirmed by Ann on 2026-11-13 06:07 UTC. The organization no longer requires a second confirmer: this confirmation will not be recorded, and anyone who may write can finalize')
        expect(D.draftConfirmationLine(draft({ confirmation: c }), null)).toBe('Confirmed by Ann on 2026-11-13 06:07 UTC')
        expect(D.draftConfirmationLine(draft(), true)).toBeNull()
    })
    it('(i) dialog objects, names one line', () => {
        const N = { componentName: 'Pump', releaseVersion: '1.2.0' }
        const BAD = { componentName: 'Pu\nmp\u202E', releaseVersion: ' 1.2.0\t' }
        const held = draft({ ...AWAIT, confirmation: { actor: 'u1', actorName: 'Bo\u202E\nB', confirmedAt: '2026-11-13T06:09:10Z' } })
        const base = 'Writes v2 of the FDA premarket submission of Pump 1.2.0 from the build of 2026-11-13 06:07 UTC. Nothing is generated again, and a Final version cannot be changed.'
        expect(D.draftFinalizeConfirm(draft(), N, false)).toEqual({ titleText: 'Finalize draft?', text: base, confirmButtonText: 'Finalize' })
        expect(D.draftFinalizeConfirm(draft(), N, true).text).toBe(base)
        expect(D.draftFinalizeConfirm(held, N, true).text).toBe(base + ' The confirmation by Bo B is recorded first, then yours.')
        expect(D.draftFinalizeConfirm(held, N, false).text).toBe(base + ' The organization no longer requires a second confirmer: the confirmation by Bo B will not be recorded, and you are recorded alone.')
        expect(D.draftFinalizeConfirm(held, N, null).text).toBe(base + ' The second-confirmer setting could not be read: whether the confirmation by Bo B is recorded follows the setting as it is now.')
        expect(D.draftFinalizeConfirm(draft(), BAD, false).text).toBe('Writes v2 of the FDA premarket submission of Pu mp 1.2.0 from the build of 2026-11-13 06:07 UTC. Nothing is generated again, and a Final version cannot be changed.')
        expect(D.draftConfirmConfirm(draft(), N)).toEqual({ titleText: 'Confirm draft?', text: 'Confirms the draft of v2 of the FDA premarket submission of Pump 1.2.0 as built on 2026-11-13 06:07 UTC. A different person then finalizes it.', confirmButtonText: 'Confirm' })
        expect(D.draftDiscardConfirm(draft(), N)).toEqual({ titleText: 'Discard draft?', text: 'Discards the draft of v2 of the FDA premarket submission of Pump 1.2.0. Its generated documents are archived; the reason stays in the release history.', confirmButtonText: 'Discard draft' })
        expect(D.draftReturnConfirm(held, N)).toEqual({ titleText: 'Return to draft?', text: 'Returns the draft of v2 of the FDA premarket submission of Pump 1.2.0 to editing. The confirmation by Bo B is dropped.', confirmButtonText: 'Return to draft' })
        expect(D.draftReturnConfirm(draft({ confirmation: { actor: 'u1', actorName: null } }), N).text).toContain('The confirmation by removed user is dropped.')
        const long = D.draftDiscardConfirm(draft(), { componentName: 'x'.repeat(150), releaseVersion: '1.2.0' }).text
        expect(long).toContain('of ' + 'x'.repeat(99) + '\u2026 1.2.0.')
    })
})
