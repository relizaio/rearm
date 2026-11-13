// @vitest-environment happy-dom
//
// The draft flow's dialogs rendered by the real SweetAlert2 (task FDAW-32 architecture-2 section 4 row 5):
// hostile names and a hostile server sentence stay text in every dialog, with the control that the same
// string in `title` does build elements; the statement cannot be skipped; a blank reason is refused.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
vi.mock('./fetchClient', () => ({ fetchWithAuth: vi.fn(), fetchArrayBufferWithAuth: vi.fn() }))
import Swal from 'sweetalert2'
import * as D from './submissionDrafts'
import { submissionCancelConfirm, submissionReasonError } from './submissions'

const HOSTILE = '<img src=x onerror="window.__hit=1"><b>b</b>'
const HN = { componentName: HOSTILE, releaseVersion: HOSTILE }
const draft = (c: any = {}) => ({ uuid: 'd1', kind: 'FDA_PREMARKET', state: 'EDITING', revision: 7, plannedVersion: 2,
    job: { state: 'BUILT' }, build: { builtAt: '2026-11-13T06:07:08.123Z' }, confirmation: null, ...c })
const HELD = draft({ state: 'AWAITING_CONFIRMATION', confirmation: { actor: 'u1', actorName: HOSTILE, confirmedAt: '2026-11-13T06:09:10Z' } })
const popup = () => document.querySelector('.swal2-popup') as HTMLElement
const container = () => document.querySelector('.swal2-html-container') as HTMLElement
const title = () => document.querySelector('.swal2-title') as HTMLElement
const reason = { input: 'textarea', inputLabel: 'Reason (required)', inputAttributes: { maxlength: '2048' },
    inputValidator: (v: any) => submissionReasonError(v, true), showCancelButton: true, confirmButtonColor: '#d33' }
const statement = { icon: 'question', input: 'checkbox', inputValue: 0, inputPlaceholder: D.CONFIRMATION_STATEMENT,
    inputValidator: (v: any) => (v ? undefined : D.STATEMENT_REQUIRED), showCancelButton: true, cancelButtonText: 'Cancel' }

describe('row 5', () => {
    beforeEach(() => { vi.useFakeTimers(); delete (window as any).__hit })
    afterEach(() => { Swal.close(); vi.runAllTimers(); vi.useRealTimers() })
    it('(a) control: the hostile name in title builds elements', () => {
        void Swal.fire({ title: HOSTILE, text: 'x' })
        expect(title().querySelector('img')).not.toBeNull()
        expect(title().querySelector('b')).not.toBeNull()
    })
    it('(b) every dialog: text present, no injected element', () => {
        const dialogs: any[] = [
            { ...D.draftFinalizeConfirm(HELD, HN, true), ...statement },
            { ...D.draftFinalizeConfirm(HELD, HN, false), ...statement },
            { ...D.draftFinalizeConfirm(HELD, HN, null), ...statement },
            { ...D.draftConfirmConfirm(draft(), HN), ...statement },
            { ...D.draftDiscardConfirm(draft(), HN), icon: 'warning', ...reason, cancelButtonText: 'Keep draft' },
            { ...D.draftReturnConfirm(HELD, HN), icon: 'warning', ...reason, cancelButtonText: 'Cancel' },
            { ...submissionCancelConfirm({ kind: 'FDA_PREMARKET', latestVersion: 2 }, HN), icon: 'warning', ...reason, cancelButtonText: 'Keep submission' },
            { titleText: 'Error!', text: HOSTILE, icon: 'error' },
            { titleText: 'Submission', text: 'Version 2 created.', icon: 'success' },
        ]
        for (const d of dialogs) {
            void Swal.fire(d)
            expect(container().children.length).toBe(0)
            expect(container().textContent).toBe(d.text)
            expect(title().children.length).toBe(0)
            expect(title().textContent).toBe(d.titleText)
            expect(popup().querySelector('img:not(.swal2-image)')).toBeNull()
            expect(popup().querySelector('b')).toBeNull()
            expect(popup().querySelector('script')).toBeNull()
            expect(popup().querySelector('[onerror]')).toBeNull()
            Swal.close()
            vi.runAllTimers()
        }
        // the name is present, as text
        expect(dialogs.slice(0, 7).every(d => d.text.includes(HOSTILE))).toBe(true)
        expect(dialogs.slice(0, 3).every(d => d.text.split(HOSTILE).length === 4)).toBe(true)
        expect((window as any).__hit).toBeUndefined()
    })
    it('(c) the statement cannot be skipped', async () => {
        const d = D.draftFinalizeConfirm(draft(), HN, false)
        const p = Swal.fire({ ...d, ...statement } as any)
        const box = popup().querySelector('.swal2-checkbox input[type="checkbox"]') as HTMLInputElement
        expect(box.checked).toBe(false)
        expect((popup().querySelector('.swal2-checkbox .swal2-label') as HTMLElement).textContent).toBe(D.CONFIRMATION_STATEMENT)
        expect(popup().querySelector('.swal2-checkbox')!.querySelectorAll('*').length).toBe(2)
        Swal.clickConfirm()
        await vi.advanceTimersByTimeAsync(50)
        expect(Swal.isVisible()).toBe(true)
        expect((popup().querySelector('.swal2-validation-message') as HTMLElement).textContent).toBe('Tick the statement to continue')
        box.checked = true
        Swal.clickConfirm()
        await vi.advanceTimersByTimeAsync(50)
        const answer = await p
        expect(answer.isConfirmed).toBe(true)
        expect(answer.value).toBe(1)
    })
    it('(d) the reason dialog refuses a blank reason with the constant message', async () => {
        void Swal.fire({ ...D.draftDiscardConfirm(draft(), HN), icon: 'warning', ...reason, cancelButtonText: 'Keep draft' } as any)
        const area = popup().querySelector('textarea.swal2-textarea') as HTMLTextAreaElement
        expect(area.getAttribute('maxlength')).toBe('2048')
        area.value = '   '
        Swal.clickConfirm()
        await vi.advanceTimersByTimeAsync(50)
        expect(Swal.isVisible()).toBe(true)
        expect((popup().querySelector('.swal2-validation-message') as HTMLElement).textContent).toBe('A reason is required')
        expect((popup().querySelector('.swal2-cancel') as HTMLElement).textContent).toBe('Keep draft')
        expect((popup().querySelector('.swal2-confirm') as HTMLElement).textContent).toBe('Discard draft')
    })
})
