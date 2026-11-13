// The acts of the draft flow (task FDAW-32 architecture-2 section 4 row 4, after TEA-30 row 34): Start and its
// automatic first rebuild, Rebuild, Discard, Confirm, Return to draft, Finalize and Cancel. Each sends exactly
// what the person confirmed (the revision of the card read before the dialog, the trimmed reason, the ticked
// statement); a dismissed dialog sends nothing; a refused act shows the server's sentence, no success and no
// further mutation, and the page reads again; one act at a time. Every dialog reaches Swal as exactly one
// object argument with a constant title in titleText and no title, html or footer key.
import { afterAll, beforeEach, describe, expect, it, vi } from 'vitest'

const fire = vi.fn()
// Every call of the file, kept across the resets of the cells, for (j).
const allFires: any[][] = []
vi.mock('sweetalert2', () => ({ default: { fire: (...a: any[]) => { allFires.push(a); return fire(...a) } } }))
vi.mock('./fetchClient', () => ({ fetchWithAuth: vi.fn(), fetchArrayBufferWithAuth: vi.fn() }))

const { useSubmissionActs } = await import('./submissionActs')
const { TEA_ACT_GATEWAY_TEXT } = await import('./teaPublication')
const { default: Q } = await import('./submissionQueries')
const D_ = await import('./submissionDrafts')

const HOSTILE = '<img src=x onerror="window.__hit=1"><b>b</b>'
const NAMES = { componentName: 'Pump', releaseVersion: '1.2.0' }
const NULL_TEXT = "The field at path '/startDraft' was declared as a non null type, but the code involved in "
    + 'retrieving data has wrongly returned a null value.  The graphql specification requires that the parent '
    + 'field be set to null, or if that is non nullable that it bubble up null to its parent and so on. The '
    + "non-nullable type is 'SubmissionActResult' within parent type 'Mutation'"
const TITLES = ['Error!', 'Submission', 'Finalize draft?', 'Confirm draft?', 'Discard draft?', 'Return to draft?', 'Cancel submission?']
const T14 = 'I reviewed the contents and the accepted items and confirm this package is ready for submission.'
const T15 = 'Tick the statement to continue'

const D = () => ({ uuid: 'd1', org: 'o1', release: 'r1', component: 'c1', kind: 'FDA_PREMARKET', submission: null,
    state: 'EDITING', revision: 7, plannedVersion: 2, startedAt: '2026-11-13T05:00:00Z', startedBy: { actor: 'u1', actorName: 'Ann' },
    reasonCategory: 'CORRECTION', reasonText: 'fix', replaces: null, job: { state: 'BUILT', attempt: 1 },
    build: { builtAt: '2026-11-13T06:07:08.123Z', releaseSbom: { version: 3 }, parts: [], diff: null },
    confirmation: null as any, changesSinceBuild: { count: 0, fingerprintEqual: true, movedLines: [] } })
const AWAITING = (actorName: string) => ({ ...D(), state: 'AWAITING_CONFIRMATION',
    confirmation: { actor: 'u1', actorName, capacity: 'AUTHOR', confirmedAt: '2026-11-13T06:09:10Z' } })
const HELD = () => AWAITING('Bo\u202E\nB')
const HEAD = { uuid: 's1', kind: 'FDA_PREMARKET', latestVersion: 2 }
const ACT_ANSWER = { draft: { uuid: 'd9', state: 'EDITING', revision: 1 }, submission: null, version: { version: 2 } }

const rootField = (opts: any) => opts.mutation?.definitions?.[0]?.selectionSet?.selections?.[0]?.name?.value
const mutate = vi.fn()
const reload = vi.fn()
const query = vi.fn()

function acts () {
    return useSubmissionActs({ client: { query, mutate }, reload })
}

const STATEMENT = { icon: 'question', input: 'checkbox', inputValue: 0, inputPlaceholder: T14, inputValidator: expect.any(Function),
    showCancelButton: true, cancelButtonText: 'Cancel' }
const REASON = { input: 'textarea', inputLabel: 'Reason (required)', inputAttributes: { maxlength: '2048' },
    inputValidator: expect.any(Function), showCancelButton: true, confirmButtonColor: '#d33' }

describe('useSubmissionActs (FDAW-32 row 4)', () => {
    beforeEach(() => {
        fire.mockReset()
        mutate.mockReset()
        reload.mockReset()
        query.mockReset()
        mutate.mockImplementation(async (opts: any) => ({ data: { [rootField(opts)]: ACT_ANSWER } }))
    })

    // (j) over every fire call of this file: one object argument, titleText and text, no title, html or footer.
    afterAll(() => {
        expect(allFires.length).toBeGreaterThan(20)
        for (const c of allFires) {
            expect(c.length).toBe(1)
            expect(typeof c[0]).toBe('object')
            expect(c[0]).not.toBeNull()
            expect(Object.keys(c[0])).toEqual(expect.arrayContaining(['titleText', 'text']))
            for (const k of ['title', 'html', 'footer']) expect(c[0]).not.toHaveProperty(k)
            expect(TITLES).toContain(c[0].titleText)
        }
    })

    it('(a) finalize sends the revision read before the dialog, then the success dialog; the statement is required', async () => {
        const d = D()
        fire.mockImplementationOnce(async () => {
            d.revision = 8
            return { isConfirmed: true, value: 1 }
        })
        await acts().finalize(d, NAMES, false)
        expect(mutate.mock.calls).toEqual([[{ mutation: Q.FinalizeDraftGql, variables: { draft: 'd1', expectedRevision: 7 } }]])
        expect(fire.mock.calls).toEqual([
            [{ ...D_.draftFinalizeConfirm(D(), NAMES, false), ...STATEMENT }],
            [{ titleText: 'Submission', text: 'Version 2 created.', icon: 'success' }],
        ])
        const validator = fire.mock.calls[0][0].inputValidator
        expect(validator(0)).toBe(T15)
        expect(validator(1)).toBeUndefined()
        expect(reload).toHaveBeenCalledTimes(1)
    })

    it('(b) a dismissed Finalize sends nothing and reloads nothing', async () => {
        fire.mockResolvedValueOnce({ isConfirmed: false })
        await acts().finalize(D(), NAMES, false)
        expect(mutate.mock.calls).toEqual([])
        expect(reload).not.toHaveBeenCalled()
        expect(fire).toHaveBeenCalledTimes(1)
    })

    it('(c) confirm sends the revision of the card', async () => {
        fire.mockResolvedValueOnce({ isConfirmed: true, value: 1 })
        await acts().confirm(D(), NAMES)
        expect(mutate.mock.calls).toEqual([[{ mutation: Q.ConfirmDraftGql, variables: { draft: 'd1', expectedRevision: 7 } }]])
        expect(fire.mock.calls).toEqual([[{ ...D_.draftConfirmConfirm(D(), NAMES), ...STATEMENT }]])
        expect(reload).toHaveBeenCalledTimes(1)
    })

    it('(d) discard requires a reason and sends it trimmed; dismissed, nothing', async () => {
        fire.mockResolvedValueOnce({ isConfirmed: true, value: '  why  ' })
        await acts().discard(D(), NAMES)
        expect(mutate.mock.calls).toEqual([[{ mutation: Q.DiscardDraftGql, variables: { draft: 'd1', reason: 'why' } }]])
        expect(fire.mock.calls).toEqual([[{ ...D_.draftDiscardConfirm(D(), NAMES), icon: 'warning', ...REASON, cancelButtonText: 'Keep draft' }]])
        const validator = fire.mock.calls[0][0].inputValidator
        expect(validator('   ')).toBe('A reason is required')
        expect(validator('x')).toBeNull()
        expect(reload).toHaveBeenCalledTimes(1)

        mutate.mockClear()
        reload.mockClear()
        fire.mockResolvedValueOnce({ isConfirmed: false })
        await acts().discard(D(), NAMES)
        expect(mutate.mock.calls).toEqual([])
        expect(reload).not.toHaveBeenCalled()
    })

    it('(e) return to draft sends the reason; the dialog names the confirmer', async () => {
        const d = AWAITING('Bo')
        fire.mockResolvedValueOnce({ isConfirmed: true, value: 'why' })
        await acts().returnToEditing(d, NAMES)
        expect(mutate.mock.calls).toEqual([[{ mutation: Q.ReturnDraftToEditingGql, variables: { draft: 'd1', reason: 'why' } }]])
        expect(fire.mock.calls[0][0].text).toBe('Returns the draft of v2 of the FDA premarket submission of Pump 1.2.0 to editing. '
            + 'The confirmation by Bo is dropped.')
        expect(fire.mock.calls[0][0].cancelButtonText).toBe('Cancel')
    })

    it('(f) cancel calls cancelSubmission, then Cancelled.', async () => {
        fire.mockResolvedValueOnce({ isConfirmed: true, value: 'why' })
        await acts().cancel(HEAD, NAMES)
        expect(mutate.mock.calls).toEqual([[{ mutation: Q.CancelSubmissionGql, variables: { submission: 's1', reason: 'why' } }]])
        expect(fire.mock.calls[0][0].cancelButtonText).toBe('Keep submission')
        expect(fire.mock.calls[1][0]).toEqual({ titleText: 'Submission', text: 'Cancelled.', icon: 'success' })
    })

    it('(g) start sends the form, then the first rebuild; a refused start sends no rebuild; a refused rebuild still answers true', async () => {
        const form = { reasonCategory: 'CORRECTION', reasonText: ' fix ', replaces: 's3:2' }
        expect(await acts().start('r1', 'FDA_PREMARKET', form)).toBe(true)
        expect(mutate.mock.calls).toEqual([
            [{ mutation: Q.StartDraftGql, variables: { release: 'r1', kind: 'FDA_PREMARKET', reasonCategory: 'CORRECTION', reasonText: 'fix',
                replacesSubmission: 's3', replacesVersion: 2 } }],
            [{ mutation: Q.RebuildDraftGql, variables: { draft: 'd9' } }],
        ])
        expect(fire).not.toHaveBeenCalled()
        expect(reload).toHaveBeenCalledTimes(1)

        mutate.mockReset()
        reload.mockReset()
        const refusal = 'Release Pump 1.2.0: A draft already exists for this release and kind'
        mutate.mockRejectedValueOnce({ errors: [{ message: refusal },
            { message: NULL_TEXT, extensions: { classification: 'NullValueInNonNullableField' } }] })
        expect(await acts().start('r1', 'FDA_PREMARKET', form)).toBe(false)
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(fire.mock.calls).toEqual([[{ titleText: 'Error!', text: refusal, icon: 'error' }]])
        expect(reload).toHaveBeenCalledTimes(1)

        mutate.mockReset()
        fire.mockReset()
        const limit = 'Rebuild limit: this organization already has 2 rebuilds in progress; wait for one to finish'
        mutate.mockImplementation(async (opts: any) => {
            if (rootField(opts) === 'rebuildDraft') throw { errors: [{ message: limit }] }
            return { data: { [rootField(opts)]: ACT_ANSWER } }
        })
        expect(await acts().start('r1', 'FDA_PREMARKET', form)).toBe(true)
        expect(mutate).toHaveBeenCalledTimes(2)
        expect(fire.mock.calls).toEqual([[{ titleText: 'Error!', text: limit, icon: 'error' }]])
    })

    it('(h) rebuild sends the draft and opens no dialog; a 503 reads the gateway sentence', async () => {
        await acts().rebuild(D())
        expect(mutate.mock.calls).toEqual([[{ mutation: Q.RebuildDraftGql, variables: { draft: 'd1' } }]])
        expect(fire).not.toHaveBeenCalled()
        expect(reload).toHaveBeenCalledTimes(1)
        mutate.mockRejectedValueOnce({ networkError: { statusCode: 503 }, message: 'Response not successful: Received status code 503' })
        await acts().rebuild(D())
        expect(fire).toHaveBeenCalledTimes(1)
        expect(fire.mock.calls[0][0].text).toBe(TEA_ACT_GATEWAY_TEXT)
    })

    it('(i) one act at a time: while Finalize waits on its dialog, every act sends nothing and opens nothing', async () => {
        let answer!: (v: any) => void
        fire.mockImplementationOnce(() => new Promise(r => { answer = r }))
        const a = acts()
        const first = a.finalize(D(), NAMES, false)
        expect(a.busy.value).toBe(true)
        expect(await a.start('r1', 'FDA_PREMARKET', { reasonCategory: 'FIRST_VERSION' })).toBe(false)
        await a.rebuild(D())
        await a.discard(D(), NAMES)
        await a.confirm(D(), NAMES)
        await a.returnToEditing(HELD(), NAMES)
        await a.finalize(D(), NAMES, false)
        await a.cancel(HEAD, NAMES)
        expect(mutate.mock.calls).toEqual([])
        expect(fire).toHaveBeenCalledTimes(1)
        answer({ isConfirmed: false })
        await first
        expect(a.busy.value).toBe(false)
    })

    it('(k) a refused act goes no further: one mutation, the server sentence, no success, one reload', async () => {
        const sentence = 'Release Pump 1.2.0: ' + HOSTILE
        const cases: [string, (a: any) => Promise<any>, any][] = [
            ['finalize', a => a.finalize(D(), NAMES, false), { isConfirmed: true, value: 1 }],
            ['confirm', a => a.confirm(D(), NAMES), { isConfirmed: true, value: 1 }],
            ['discard', a => a.discard(D(), NAMES), { isConfirmed: true, value: 'why' }],
            ['returnToEditing', a => a.returnToEditing(HELD(), NAMES), { isConfirmed: true, value: 'why' }],
            ['cancel', a => a.cancel(HEAD, NAMES), { isConfirmed: true, value: 'why' }],
            ['rebuild', a => a.rebuild(D()), null],
        ]
        for (const [name, run, dialog] of cases) {
            fire.mockReset()
            mutate.mockReset()
            reload.mockReset()
            if (dialog) fire.mockResolvedValueOnce(dialog)
            mutate.mockRejectedValueOnce({ errors: [{ message: sentence }] })
            await run(acts())
            expect(mutate, name).toHaveBeenCalledTimes(1)
            expect(fire.mock.calls[fire.mock.calls.length - 1], name).toEqual([{ titleText: 'Error!', text: sentence, icon: 'error' }])
            expect(fire.mock.calls.some(c => c[0]?.icon === 'success'), name).toBe(false)
            expect(reload, name).toHaveBeenCalledTimes(1)
        }
    })

    it('(l) the Finalize dialog says what the setting does to a held confirmation', async () => {
        const ends: [boolean | null, string][] = [
            [true, ' The confirmation by Bo B is recorded first, then yours.'],
            [false, ' The organization no longer requires a second confirmer: the confirmation by Bo B will not be recorded, and you are recorded alone.'],
            [null, ' The second-confirmer setting could not be read: whether the confirmation by Bo B is recorded follows the setting as it is now.'],
        ]
        for (const [s, end] of ends) {
            fire.mockReset()
            fire.mockResolvedValueOnce({ isConfirmed: false })
            await acts().finalize(HELD(), NAMES, s)
            expect(fire.mock.calls[0][0].text.endsWith(end), String(s)).toBe(true)
        }
    })
})
