// @vitest-environment happy-dom
//
// T-586: names, versions and server sentences that quote them can be text a supplier wrote (an
// upstream source stores its product and component names and its release versions as they are,
// markup included). The views below open or act on such a component, release or branch, so their
// SweetAlert2 dialogs keep that text in the text positions (titleText, text) and never in the HTML
// positions (title, html, footer, the positional arguments). The rule is by file, not by proven
// call: a positional call in one of these files is a defect whatever it shows today.
//
// Part 1 reads the seven views as text. Part 2 renders every object-literal call of those views
// with the real SweetAlert2, each value the call takes from its scope replaced by hostile markup,
// and shows that no element is built from it; a control renders the old positional form to show
// the rig does see markup when a dialog is handed some. happy-dom runs neither the onerror handler
// nor a script, so the assertions are on what was built, not on what ran.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import Swal from 'sweetalert2'
import { teaActProgress, teaHideConfirm, teaPublishConfirm } from '@/utils/teaPublication'
import { teaConfirmBeforeMembership } from '@/utils/teaProfile'
import { teaConfirmBeforeAutoPublish } from '@/utils/teaOutputEvents'

vi.mock('@/utils/fetchClient', () => ({ fetchWithAuth: vi.fn(), fetchArrayBufferWithAuth: vi.fn() }))

const HOSTILE = '<img src=x onerror=alert(1)>'
const MARKUP = HOSTILE + '<script>window.__ingestedHit=1</script><b>b</b>'

const VIEWS = [
    'ComponentView.vue',
    'ReleaseView.vue',
    'BranchView.vue',
    'CreateRelease.vue',
    'CreateArtifact.vue',
    'CreateDeliverable.vue',
    'MarketingReleaseView.vue',
]

const sourceOf = (file: string) => readFileSync(fileURLToPath(new URL('./' + file, import.meta.url)), 'utf8')

/** Index just past the string or template literal that starts at i; a template's ${} is skipped whole. */
function skipLiteral (s: string, i: number): number {
    const quote = s[i]
    i++
    while (i < s.length) {
        const c = s[i]
        if (c === '\\') { i += 2; continue }
        if (quote === '`' && c === '$' && s[i + 1] === '{') { i = matchingClose(s, i + 1) + 1; continue }
        if (c === quote) return i + 1
        i++
    }
    throw new Error('unterminated literal at ' + i)
}

/** Index of the bracket that closes the one at i; string and template literals are skipped while counting. */
function matchingClose (s: string, i: number): number {
    let depth = 0
    while (i < s.length) {
        const c = s[i]
        if (c === '\'' || c === '"' || c === '`') { i = skipLiteral(s, i); continue }
        if (c === '/' && s[i + 1] === '/') { i = s.indexOf('\n', i); continue }
        if (c === '(' || c === '[' || c === '{') depth++
        else if (c === ')' || c === ']' || c === '}') {
            depth--
            if (depth === 0) return i
        }
        i++
    }
    throw new Error('unbalanced bracket at ' + i)
}

interface SwalCall { file: string, line: number, args: string }

/** The argument text of every Swal.fire( call of a source, from its opening bracket to the matching one. */
function swalCalls (file: string, source: string): SwalCall[] {
    const calls: SwalCall[] = []
    const re = /Swal\.fire\(/g
    let m: RegExpExecArray | null
    while ((m = re.exec(source)) !== null) {
        const open = m.index + m[0].length - 1
        const close = matchingClose(source, open)
        calls.push({ file, line: source.slice(0, m.index).split('\n').length, args: source.slice(open + 1, close) })
        re.lastIndex = close
    }
    return calls
}

const CALLS: SwalCall[] = VIEWS.flatMap(f => swalCalls(f, sourceOf(f)))
const where = (c: SwalCall) => c.file + ':' + c.line

/** The value text after every key `name:` of an argument text (the key itself, not titleText). */
function valuesOfKey (args: string, name: string): string[] {
    const re = new RegExp('(^|[{,\\s])' + name + '\\s*:\\s*', 'g')
    const out: string[] = []
    let m: RegExpExecArray | null
    while ((m = re.exec(args)) !== null) out.push(args.slice(m.index + m[0].length))
    return out
}

describe('T-586: the dialogs of the seven views keep ingested text out of HTML positions', () => {
    it('(e) finds every file and at least one call in each, so it cannot pass by reading nothing', () => {
        for (const f of VIEWS) {
            expect(CALLS.filter(c => c.file === f).length, f).toBeGreaterThan(0)
        }
    })

    it('(a) has no positional call: no argument text starts with a string or template literal', () => {
        const positional = CALLS.filter(c => /^\s*['"`]/.test(c.args)).map(where)
        expect(positional).toEqual([])
    })

    it('(b) every title key is a quoted string without interpolation, there is no footer, and html occurs once', () => {
        const badTitles = CALLS.flatMap(c => valuesOfKey(c.args, 'title')
            .filter(v => !/^('[^'\\\n]*'|"[^"\\\n]*")\s*[,}\n]/.test(v))
            .map(v => where(c) + ' title: ' + v.split('\n')[0]))
        expect(badTitles).toEqual([])
        expect(CALLS.filter(c => valuesOfKey(c.args, 'footer').length > 0).map(where)).toEqual([])
        const html = CALLS.flatMap(c => valuesOfKey(c.args, 'html').map(v => ({ c, v })))
        expect(html).toHaveLength(1)
        expect(html[0].c.file).toBe('ComponentView.vue')
        expect(html[0].v.startsWith('newKeyMessage')).toBe(true)
    })

    it('(c) the archive dialogs of ComponentView carry the name under titleText and text', () => {
        const confirm = CALLS.filter(c => c.file === 'ComponentView.vue' && c.args.includes('Are you sure you want to archive the'))
        expect(confirm).toHaveLength(1)
        expect(confirm[0].args).toMatch(/titleText: `Are you sure you want to archive the \$\{updatedComponent\.value\.name\} \$\{words\.value\.component\}\?`/)
        const done = CALLS.filter(c => c.file === 'ComponentView.vue' && c.args.includes('has been archived successfully'))
        expect(done).toHaveLength(1)
        expect(done[0].args).toMatch(/text: `\$\{words\.value\.componentFirstUpper\} "\$\{componentName\}" has been archived successfully\.`/)
        expect(done[0].args).toMatch(/^\{ titleText: 'Archived!', text: /)
    })

    it('(d) the TEA publish confirmation has no title and no html, and carries hostile names unchanged in titleText', () => {
        const names = { component: HOSTILE, version: HOSTILE, perspectives: {} }
        const base = { release: 'r-1', kind: 'COMPONENT_RELEASE', canPublish: true, effectiveVisibility: 'PRIVATE',
            profileSource: 'ORGANIZATION', profileRevision: 3, cascades: false, cascadeChildren: 0, collections: [] }
        const withoutPublication: any = teaPublishConfirm({ ...base, publication: null }, names)
        const withPublication: any = teaPublishConfirm({ ...base, publication: { uuid: 'p-1', state: 'PUBLISHED' } }, names)
        for (const c of [withoutPublication, withPublication]) {
            expect(c).not.toHaveProperty('title')
            expect(c).not.toHaveProperty('html')
            expect(c.titleText).toContain(HOSTILE + ' ' + HOSTILE)
        }
        expect(withoutPublication.titleText).toBe('Publish ' + HOSTILE + ' ' + HOSTILE + ' on TEA?')
        expect(withPublication.titleText).toBe('Re-publish ' + HOSTILE + ' ' + HOSTILE + ' on TEA?')
    })
})

// ---- Part 2: rendered with the real SweetAlert2 ----

const popup = () => document.querySelector('.swal2-popup') as HTMLElement | null
const titleEl = () => document.querySelector('.swal2-title') as HTMLElement
const bodyEl = () => document.querySelector('.swal2-html-container') as HTMLElement

/** Opens the dialog (not awaited), runs the check on the rendered popup and closes it. */
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

/** Nothing of the title or the body is an element: what they show is text. */
function expectTextOnly (p: HTMLElement) {
    expect(titleEl().children.length).toBe(0)
    expect(bodyEl().children.length).toBe(0)
    expect(p.querySelector('img:not(.swal2-image)')).toBeNull()
    expect(p.querySelector('script')).toBeNull()
    expect(p.querySelector('b')).toBeNull()
    expect(p.querySelector('[onerror]')).toBeNull()
}

const HOSTILE_VALUES = new WeakSet<object>()

/** A value that is markup whatever is asked of it: a property, a call, a conversion to text. */
function hostileValue (): any {
    const target = function () { /* callable */ }
    const proxy: any = new Proxy(target, {
        get (_t, key) {
            if (key === Symbol.toPrimitive) return () => MARKUP
            if (key === 'toString' || key === 'valueOf') return () => MARKUP
            if (typeof key === 'symbol') return undefined
            return hostileValue()
        },
        apply () { return hostileValue() },
    })
    HOSTILE_VALUES.add(proxy)
    return proxy
}

/** Every free name of an argument text resolves to a hostile value (with-scope over a proxy). */
const HOSTILE_SCOPE = new Proxy({}, {
    has: (_t, key) => typeof key === 'string',
    get: (_t, key) => (typeof key === 'symbol' ? undefined : hostileValue()),
})

const ICONS = ['success', 'error', 'warning', 'info', 'question']

/** The options object a call builds when everything it reads from its scope is hostile markup. */
function hostileOptions (call: SwalCall): Record<string, any> {
    // eslint-disable-next-line no-new-func
    const build = new Function('scope', 'with (scope) { return (' + call.args + ') }')
    const options: Record<string, any> = { ...build(HOSTILE_SCOPE) }
    for (const [k, v] of Object.entries(options)) {
        if (HOSTILE_VALUES.has(v as object)) options[k] = String(v)
    }
    if (!ICONS.includes(options.icon)) delete options.icon
    return options
}

/** Calls whose argument text is one object literal without a spread; the others hand over a builder's object. */
const LITERAL_CALLS = CALLS.filter(c => /^\s*\{/.test(c.args) && !/\.\.\./.test(c.args))
const BUILDER_CALLS = CALLS.filter(c => !LITERAL_CALLS.includes(c))
const isApiKeyDialog = (c: SwalCall) => valuesOfKey(c.args, 'html').length > 0

// SweetAlert2 sets a short timer on every open and reads `window` in it; a timer left when the file
// ends fires after happy-dom is gone. Fake timers, run out while the window exists.
beforeEach(() => { vi.useFakeTimers() })
afterEach(() => {
    Swal.close()
    vi.runAllTimers()
    vi.useRealTimers()
})

describe('T-586: the dialogs rendered with hostile values show them as text', () => {
    it('the rig sees markup: the old positional form builds the element from the same text', () => {
        rendered([MARKUP, MARKUP, 'error'], p => {
            expect(p.querySelector('.swal2-title img')).not.toBeNull()
            expect(p.querySelector('.swal2-html-container img')).not.toBeNull()
        })
    })

    it('reads the object-literal calls of all seven views, and hands the rest to a builder named below', () => {
        expect(LITERAL_CALLS.length).toBeGreaterThan(30)
        for (const f of VIEWS) expect(LITERAL_CALLS.some(c => c.file === f), f).toBe(true)
        // Each of these hands over an object a builder made; the builders are checked in the last case
        // of this block. The last two forms are the spreads a conversion of the whole UI writes for
        // the same calls, so the row holds before and after it.
        const known = [
            '{ ...c, icon: \'warning\', showCancelButton: true, cancelButtonText: \'Cancel\' }',
            '{ ...confirm, icon: \'warning\', showCancelButton: true, cancelButtonText: \'Cancel\' }',
            '{ ...teaActProgress(act, view), didOpen: () => Swal.showLoading() }',
            'confirm',
            'teaHideConfirm(view)',
            '{ ...teaHideConfirm(view) }',
            '{ ...o, didOpen: () => Swal.showLoading() }',
        ]
        const unknown = BUILDER_CALLS.map(c => c.args.replace(/\s+/g, ' ').trim()).filter(a => !known.includes(a))
        expect(unknown).toEqual([])
    })

    it('every object-literal call, with every value it reads replaced by markup, renders no element from it', () => {
        const checked: string[] = []
        for (const call of LITERAL_CALLS.filter(c => !isApiKeyDialog(c))) {
            const options = hostileOptions(call)
            rendered([options], p => {
                expectTextOnly(p)
                checked.push(where(call))
            })
        }
        expect(checked.length).toBe(LITERAL_CALLS.length - 1)
    })

    it('the archive confirmation shows a hostile product name literally in its title', () => {
        const call = LITERAL_CALLS.find(c => c.file === 'ComponentView.vue' && c.args.includes('Are you sure you want to archive the'))!
        rendered([hostileOptions(call)], p => {
            expectTextOnly(p)
            expect(titleEl().textContent).toBe('Are you sure you want to archive the ' + MARKUP + ' ' + MARKUP + '?')
        })
    })

    it('a server sentence that quotes a hostile name shows literally in the error dialog', () => {
        const call = LITERAL_CALLS.find(c => c.file === 'ReleaseView.vue' && /text: commonFunctions\.parseGraphQLError/.test(c.args))!
        rendered([hostileOptions(call)], p => {
            expectTextOnly(p)
            expect(titleEl().textContent).toBe('Error!')
            expect(bodyEl().textContent).toBe(MARKUP)
        })
    })

    it('the TEA publish confirmation with a hostile name and version shows both literally', () => {
        const confirm = teaPublishConfirm({ release: 'r-1', kind: 'COMPONENT_RELEASE', effectiveVisibility: 'PRIVATE',
            profileSource: 'ORGANIZATION', profileRevision: 3, publication: null }, { component: MARKUP, version: MARKUP, perspectives: {} })
        rendered([confirm], p => {
            expectTextOnly(p)
            expect(titleEl().textContent).toBe('Publish ' + MARKUP + ' ' + MARKUP + ' on TEA?')
        })
    })

    it('the other builders the release view hands to SweetAlert2 have a fixed title', () => {
        expect(teaHideConfirm({ kind: 'PRODUCT_RELEASE' }).title).toBe('Hide from TEA?')
        expect(teaActProgress('publish', { kind: 'PRODUCT_RELEASE', cascades: true }).title).toBe('Publishing on TEA\u2026')
        expect(teaActProgress('republish', null).title).toBe('Re-publishing on TEA\u2026')
        expect(teaActProgress('hide', null).title).toBe('Hiding from TEA\u2026')
    })

    it('the two builders the component view spreads into its confirmations keep hostile names in the text', () => {
        const membership = teaConfirmBeforeMembership({ before: 'PRIVATE', after: 'PUBLIC', path: 'MEMBERSHIP_RESOLVES_PUBLIC',
            afterSource: 'PERSPECTIVE', afterSourceObject: 'p1', publishedReleases: 1 } as any, [MARKUP], [MARKUP], { p1: MARKUP }, 'product')!
        const autoPublish = teaConfirmBeforeAutoPublish({ effective: { status: 'RESOLVED', source: 'COMPONENT', sourceObject: 'c1',
            profile: { publishing: 'ENABLED', visibility: 'PUBLIC' } }, publishedReleases: 2 } as any, { c1: MARKUP }, 'component', [MARKUP])!
        for (const built of [membership, autoPublish]) {
            expect(built.title).not.toContain('<')
            expect(built.text).toContain(MARKUP)
            rendered([{ ...built, icon: 'warning', showCancelButton: true, cancelButtonText: 'Cancel' }], expectTextOnly)
        }
        expect(membership.title).toBe('Make this product public?')
        expect(autoPublish.title).toBe('Publish automatically to the public?')
    })
})
