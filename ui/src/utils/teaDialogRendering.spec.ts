// @vitest-environment happy-dom
//
// FDAW-39 (design row 56): every dialog this child builds, rendered with the real SweetAlert2 and names
// that carry markup. It asserts what was built, not what ran: happy-dom runs neither the `onerror`
// handler nor the `script` of HOSTILE, also in the controls that build the `img`, so no assertion on
// execution is made. The controls show that the rig sees markup when the dialog is handed some.
import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest'
vi.mock('./fetchClient', () => ({ fetchWithAuth: vi.fn(), fetchArrayBufferWithAuth: vi.fn() }))
vi.mock('@/utils/fetchClient', () => ({ fetchWithAuth: vi.fn(), fetchArrayBufferWithAuth: vi.fn() }))
import Swal from 'sweetalert2'
import {
    teaChildrenReadErrorText, teaNotStartedText, teaPartialChildren, teaPublishConfirm, teaPublishSummary, teaShareConfirm,
    teaShareErrorText, teaTurnOnProgress, teaTurnOnPublicConfirm, teaTurnOnStopSentence, teaTurnOnStopText,
} from './teaPublication'

const HOSTILE = '<img src=x onerror="window.__teaHit=1"></script><script>window.__teaHit=2</script><b>b</b>'

const popup = () => document.querySelector('.swal2-popup') as HTMLElement | null
const container = () => document.querySelector('.swal2-html-container') as HTMLElement
const title = () => document.querySelector('.swal2-title') as HTMLElement

/** Opens the dialog (not awaited), runs the checks on the rendered popup, and closes it. */
function rendered (args: any[], check: (p: HTMLElement) => void) {
    void (Swal.fire as any)(...args)
    const p = popup()
    expect(p).not.toBeNull()
    try {
        check(p!)
    } finally {
        Swal.close()
    }
}

/** "Text only": nothing of the dialog is an element built from a name. */
function expectTextOnly (p: HTMLElement, text: string) {
    expect(container().children.length).toBe(0)
    expect(container().textContent).toBe(text)
    expect(title().children.length).toBe(0)
    expect(p.querySelector('script')).toBeNull()
    expect(p.querySelector('[onerror]')).toBeNull()
    for (const b of Array.from(p.querySelectorAll('.swal2-actions button'))) expect(b.children.length).toBe(0)
}

const PRODUCT = { release: 'r1', kind: 'PRODUCT_RELEASE', canPublish: true, effectiveVisibility: 'PRIVATE', profileSource: 'ORGANIZATION',
    cascades: true, cascadeChildren: 2, profileRevision: 3, publication: null, profiles: [HOSTILE], publishing: 'ENABLED' }
const HOSTILE_LIST = [
    { release: 'c1', componentName: HOSTILE, version: HOSTILE, outcome: 'REFUSED', refusal: 'PUBLISHING_DISABLED', ownRow: 'row-1' },
    { release: 'c2', componentName: HOSTILE, version: '1', outcome: 'SKIPPED_OTHER_PRODUCT', detail: 'published with product ' + HOSTILE },
]
const PARTIAL = teaPartialChildren(HOSTILE_LIST, { isOrgAdmin: true, componentOf: () => 'comp' })
const SHARE = { kind: 'FDA_PREMARKET', latestVersion: 2, state: 'FINAL', profileName: 'fda' }
const SHARE_DIALOG = teaShareConfirm(PRODUCT, { component: HOSTILE, version: HOSTILE, perspectives: {} }, SHARE, PARTIAL)

const PUB = { release: 'pub', componentName: HOSTILE, version: '1.0', outcome: 'REFUSED', refusal: 'PUBLISHING_DISABLED', ownRow: 'rp' }
const SAME = { before: 'PUBLIC', after: 'PUBLIC', path: 'ROW_PUBLIC', publishedReleases: 3, publishedVersions: 7 }

const R_HOSTILE = {
    outcome: 'PUBLISHED', collection: { version: 4 }, publication: { aggregateArtifacts: [] },
    artifacts: [{ status: 'PUBLISHED' }, { status: 'SKIPPED_NO_CHECKSUM' }],
    children: [
        { release: 'a', componentName: HOSTILE, version: '1.0', outcome: 'PUBLISHED' },
        { release: 'b', componentName: HOSTILE, version: '2.0', outcome: 'REFUSED', refusal: 'PUBLISHING_DISABLED' },
        { release: 'c', componentName: HOSTILE, version: '3.0', outcome: 'SKIPPED_DIRECT' },
        { release: 'd', componentName: HOSTILE, version: '6.0', outcome: 'SKIPPED_OTHER_PRODUCT', detail: 'published with product ' + HOSTILE },
        { release: 'h', componentName: HOSTILE, version: '8.0', outcome: 'REFUSED', refusal: 'GENERATION_FAILED', detail: 'x' },
    ],
}

// SweetAlert2 sets a 10 ms timer on every open and reads `window` in it; a timer left when the file
// ends fires after happy-dom is gone and fails the run. Fake timers, run out while the window exists.
beforeEach(() => { vi.useFakeTimers() })
afterEach(() => {
    Swal.close()
    vi.runAllTimers()
    vi.useRealTimers()
})

describe('FDAW-39 dialogs rendered with the real SweetAlert2 (row 56)', () => {
    it('HOSTILE has 90 characters, so teaOneLine leaves it whole', () => {
        expect(HOSTILE).toHaveLength(90)
    })

    it('(a) controls: the positional form, title and a button text build elements; showCancelButton focuses confirm', () => {
        rendered(['Error!', HOSTILE, 'error'], (p) => {
            expect(p.querySelector('.swal2-html-container img')).not.toBeNull()
            expect(p.querySelector('.swal2-html-container b')).not.toBeNull()
            expect(p.querySelector('[onerror]')).not.toBeNull()
        })
        rendered([{ title: HOSTILE, text: 'x' }], (p) => {
            expect(p.querySelector('.swal2-title img')).not.toBeNull()
        })
        rendered([{ title: 'x', text: 'y', confirmButtonText: HOSTILE }], (p) => {
            expect((p.querySelector('.swal2-confirm') as HTMLElement).children.length).toBeGreaterThan(0)
        })
        rendered([{ title: 'x', text: 'y', showCancelButton: true }], (p) => {
            expect(document.activeElement).toBe(p.querySelector('.swal2-confirm'))
        })
    })

    it('(b) the share dialog with hostile names, profiles, an unlisted profile and the partial list is text only', () => {
        expect(SHARE_DIALOG.text).toContain(HOSTILE)
        rendered([SHARE_DIALOG], (p) => {
            expectTextOnly(p, SHARE_DIALOG.text)
            expect(title().textContent).toBe('Share with customers?')
        })
    })

    it('(b) the red Public confirmation of "Turn on for both" is text only, with the focus on Cancel', () => {
        const c: any = { ...teaTurnOnPublicConfirm(PUB, { visibility: 'PUBLIC' }, SAME), icon: 'warning', showCancelButton: true, cancelButtonText: 'Cancel' }
        expect(c.text).toContain(HOSTILE)
        rendered([c], (p) => {
            expectTextOnly(p, c.text)
            expect(title().textContent).toBe('Publish this component publicly?')
            expect(document.activeElement).toBe(p.querySelector('.swal2-cancel'))
        })
    })

    it('(b) the stop, not-started, share error, read error and refused stop dialogs are text only', () => {
        const dialogs = [
            { titleText: 'Not published', text: teaTurnOnStopText({ saved: [HOSTILE + ' 1'],
                stop: { kind: 'unanswered', child: HOSTILE, text: teaTurnOnStopSentence('unanswered', HOSTILE) } }), icon: 'info' },
            { titleText: 'Not published', text: teaNotStartedText([HOSTILE + ' 1']), icon: 'info' },
            { titleText: 'Error!', text: teaShareErrorText(new Error(HOSTILE)), icon: 'error' },
            { titleText: 'Not published', text: teaChildrenReadErrorText({ errors: [{ message: HOSTILE }] }), icon: 'error' },
            { titleText: 'Not published', text: teaTurnOnStopText({ saved: [HOSTILE + ' 1'], stop: { kind: 'refused', child: 'x', text: HOSTILE } }), icon: 'info' },
        ]
        for (const d of dialogs) {
            expect(d.text).toContain(HOSTILE)
            rendered([d], (p) => {
                expectTextOnly(p, d.text)
                expect(title().textContent).toBe(d.titleText)
            })
        }
    })

    it('(b) the share result with hostile children is text only', () => {
        const d = { title: 'TEA', text: teaPublishSummary(R_HOSTILE, { worded: true }), icon: 'success' }
        expect(d.text).toContain(HOSTILE)
        rendered([d], (p) => expectTextOnly(p, d.text))
    })

    it('(b) the plain publish dialog with the partial list is text only, its title too (FDAW-95)', () => {
        const d = teaPublishConfirm(PRODUCT, { component: HOSTILE, version: HOSTILE, perspectives: {} }, PARTIAL)
        expect(d.text).toContain(HOSTILE)
        rendered([d], (p) => {
            expectTextOnly(p, d.text)
            expect(title().textContent).toBe('Publish ' + HOSTILE + ' ' + HOSTILE + ' on TEA?')
        })
    })

    it('(c) the share dialog with the offer: the deny button, the list class and the confirm text', () => {
        expect(SHARE_DIALOG.showDenyButton).toBe(true)
        rendered([SHARE_DIALOG], (p) => {
            const deny = p.querySelector('.swal2-deny') as HTMLElement
            expect(deny.classList.contains('tea-turn-on-both')).toBe(true)
            expect(deny.children.length).toBe(0)
            expect(deny.textContent).toBe('Turn on for both')
            expect(container().classList.contains('tea-partial-children')).toBe(true)
            expect((p.querySelector('.swal2-confirm') as HTMLElement).textContent).toBe('Share')
            expect((p.querySelector('.swal2-confirm') as HTMLElement).style.display).not.toBe('none')
        })
    })

    it('(d) the progress dialog of "Turn on for both" shows its title and text and no button', () => {
        const o = teaTurnOnProgress()
        rendered([{ ...o, didOpen: () => Swal.showLoading() }], (p) => {
            expect(title().textContent).toBe('Turning publishing on…')
            expect(container().textContent).toBe(o.text)
            const buttons = Array.from(p.querySelectorAll('.swal2-actions button')) as HTMLElement[]
            expect(buttons.length).toBeGreaterThan(0)
            for (const b of buttons) expect(b.style.display).toBe('none')
        })
    })
})
