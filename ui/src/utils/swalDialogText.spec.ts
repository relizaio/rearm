// @vitest-environment happy-dom
//
// FDAW-95 (design rows 2 to 4): the publish confirmation, the error dialog of a TEA act and one dialog
// per converted class, rendered with the real SweetAlert2. Structure only: happy-dom runs no payload.
import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest'
vi.mock('./fetchClient', () => ({ fetchWithAuth: vi.fn(), fetchArrayBufferWithAuth: vi.fn() }))
vi.mock('@/utils/fetchClient', () => ({ fetchWithAuth: vi.fn(), fetchArrayBufferWithAuth: vi.fn() }))
import Swal from 'sweetalert2'
import { teaActErrorText, teaPublishConfirm } from './teaPublication'
import { escapeHtml } from './escapeHtml'

const HOSTILE = '<img src=x onerror="window.__teaHit=1"></script><script>window.__teaHit=2</script><b>b</b>'
const LONG = 'A'.repeat(5000) + HOSTILE
const BIDI = String.fromCodePoint(0x202e)
const popup = () => document.querySelector('.swal2-popup') as HTMLElement | null
const container = () => document.querySelector('.swal2-html-container') as HTMLElement
const title = () => document.querySelector('.swal2-title') as HTMLElement
const validation = () => document.querySelector('.swal2-validation-message') as HTMLElement

function rendered (args: any[], check: (p: HTMLElement) => void) {
    void (Swal.fire as any)(...args)
    const p = popup()
    expect(p).not.toBeNull()
    try { check(p!) } finally { Swal.close() }
}
function expectTextOnly (p: HTMLElement, titleText: string, text: string) {
    expect(title().children.length).toBe(0)
    expect(title().textContent).toBe(titleText)
    expect(container().children.length).toBe(0)
    expect(container().textContent).toBe(text)
    expect(p.querySelector('img:not(.swal2-image)')).toBeNull()
    expect(p.querySelector('b')).toBeNull()
    expect(p.querySelector('script')).toBeNull()
    expect(p.querySelector('[onerror]')).toBeNull()
    for (const b of Array.from(p.querySelectorAll('.swal2-actions button'))) expect(b.children.length).toBe(0)
}
const VIEW = { release: 'r1', kind: 'COMPONENT_RELEASE', canPublish: true, effectiveVisibility: 'PRIVATE', profileSource: 'ORGANIZATION',
    cascades: false, profileRevision: 3, publication: null }
const REFUSAL = 'Release ' + HOSTILE + ' ' + HOSTILE + ' (3bef7cde-2edb-47cb-aa4e-94487c2cdced) is archived and cannot be published on TEA'

// SweetAlert2 sets a 10 ms timer on every open and reads `window` in it; a timer left when the file
// ends fires after happy-dom is gone and fails the run. Fake timers, run out while the window exists.
beforeEach(() => { vi.useFakeTimers() })
afterEach(() => {
    Swal.close()
    vi.runAllTimers()
    vi.useRealTimers()
})

describe('FDAW-95 dialogs rendered with the real SweetAlert2', () => {
    it('row 2 (a) controls: the forms before the fix build elements from the same strings', () => {
        const d: any = teaPublishConfirm(VIEW, { component: HOSTILE, version: HOSTILE })
        rendered([{ ...d, titleText: undefined, title: d.titleText }], (p) => {
            expect(p.querySelector('.swal2-title img')).not.toBeNull()
            expect(p.querySelector('img:not(.swal2-image)')).not.toBeNull()
            expect(p.querySelector('.swal2-title b')).not.toBeNull()
            expect(p.querySelector('[onerror]')).not.toBeNull()
        })
        rendered(['Error!', teaActErrorText({ errors: [{ message: REFUSAL }] }), 'error'], (p) => {
            expect(p.querySelector('.swal2-html-container img')).not.toBeNull()
            expect(p.querySelector('.swal2-html-container b')).not.toBeNull()
            expect(p.querySelector('[onerror]')).not.toBeNull()
        })
    })

    it('row 2 (b): the publish and the re-publish confirmation with hostile names are text only', () => {
        for (const [view, verb] of [[VIEW, 'Publish'], [{ ...VIEW, effectiveVisibility: 'PUBLIC', publication: { state: 'PUBLISHED' } }, 'Re-publish']] as const) {
            const d: any = teaPublishConfirm(view, { component: HOSTILE, version: HOSTILE })
            expect(d).not.toHaveProperty('title')
            expect(d).not.toHaveProperty('html')
            rendered([d], (p) => expectTextOnly(p, verb + ' ' + HOSTILE + ' ' + HOSTILE + ' on TEA?', d.text))
        }
    })

    it('row 2 (c): the error dialog of a TEA act with a hostile refusal, a long one and a bidi one is text only', () => {
        for (const message of [REFUSAL, LONG, 'Release a' + BIDI + 'b 1.0 is archived']) {
            const text = teaActErrorText({ errors: [{ message }] })
            expect(text).toBe(message)
            rendered([{ titleText: 'Error!', text, icon: 'error' }], (p) => expectTextOnly(p, 'Error!', message))
        }
    })

    it('row 4 (a): a title that names an object is text in titleText and markup in title', () => {
        const t = 'Are you sure you want to archive the ' + HOSTILE + ' component?'
        rendered([{ title: t, text: 'x', icon: 'warning' }], (p) => expect(p.querySelector('.swal2-title img')).not.toBeNull())
        rendered([{ titleText: t, text: 'x', icon: 'warning' }], (p) => expectTextOnly(p, t, 'x'))
    })

    it('row 4 (b): a validation message is text through escapeHtml and markup without it', () => {
        rendered([{ title: 'x', input: 'text' }], () => {
            Swal.showValidationMessage('Failed: ' + HOSTILE)
            expect(validation().querySelector('img')).not.toBeNull()
        })
        rendered([{ title: 'x', input: 'text' }], (p) => {
            Swal.showValidationMessage(escapeHtml('Failed: ' + HOSTILE))
            expect(validation().children.length).toBe(0)
            expect(validation().textContent).toBe('Failed: ' + HOSTILE)
            expect(p.querySelector('[onerror]')).toBeNull()
        })
    })
})
