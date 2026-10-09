// @vitest-environment happy-dom
//
// Branding presets, the UI state (task WL-3, tests 24 to 28c): the default the UI starts from and falls
// back to, the one settings call it makes at start, the per-field checks on what it takes, and the helpers
// every branded surface reads. No spec fetches anything: loadBranding takes the fetch function.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
    DEFAULT_BRANDING, applySignUpBackground, brandingState, legalTooltipText, loadBranding, navLogoLayoutFor,
    parseBrandingSettings, supportContactHtml
} from './branding'
import { CONSENT_SHOWN_SETTINGS, EXAMPLE_SETTINGS, MEDWARE_SETTINGS } from './branding.fixtures'

const MEDWARE_LEGAL = 'ReARM™ is a trademark of Reliza Incorporated, used under license. MedWare Cyber Dossier is offered by MedWare Cyber, LLC.'

function reply (status: number, body: string): typeof fetch {
    return vi.fn(async () => new Response(body, { status, headers: { 'Content-Type': 'application/json' } })) as unknown as typeof fetch
}

function freshDocument (withIcon = true): Document {
    const doc = document.implementation.createHTMLDocument('ReARM')
    if (withIcon) {
        const link = doc.createElement('link')
        link.setAttribute('rel', 'icon')
        link.setAttribute('type', 'image/x-icon')
        link.setAttribute('href', '/favicon.ico')
        doc.head.appendChild(link)
    }
    return doc
}

const iconOf = (doc: Document) => doc.head.querySelector('link[rel~="icon"]')

let info: ReturnType<typeof vi.spyOn>
let warn: ReturnType<typeof vi.spyOn>

beforeEach(() => {
    Object.assign(brandingState, DEFAULT_BRANDING)
    info = vi.spyOn(console, 'info').mockImplementation(() => {})
    warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
})

afterEach(() => {
    vi.restoreAllMocks()
    vi.useRealTimers()
})

describe('branding: the default', () => {
    it('24: DEFAULT_BRANDING is the 16 default values, and the state starts from it', () => {
        expect(DEFAULT_BRANDING).toEqual({
            preset: 'default',
            isDefault: true,
            titleText: 'ReARM',
            organizationText: 'Reliza Incorporated',
            navProductName: 'ReARM',
            supportName: 'Reliza Support',
            supportEmail: 'info@reliza.io',
            documentationUrl: 'https://docs.rearmhq.com',
            termsOfServiceUrl: 'https://rearmhq.com/tos.html',
            privacyPolicyUrl: 'https://rearmhq.com/privacy.html',
            legalUrl: 'https://rearmhq.com/tos.html',
            marketingConsent: 'SHOWN',
            marketingConsentText: 'Agree to receive news and promotions from Reliza by email (Optional).',
            logoUrl: '/logo_svg_no_tag_3.svg',
            faviconUrl: '/favicon.ico',
            signUpBackgroundUrl: '/reliza_in_sand_right_corner.jpg'
        })
        expect(Object.keys(DEFAULT_BRANDING)).toHaveLength(16)
        expect({ ...brandingState }).toEqual(DEFAULT_BRANDING)
    })
})

describe('branding: loadBranding', () => {
    it('25: takes the example settings, and sets the title and the favicon', async () => {
        const doc = freshDocument()
        await loadBranding(reply(200, JSON.stringify(EXAMPLE_SETTINGS)), doc)
        expect({ ...brandingState }).toEqual(EXAMPLE_SETTINGS)
        expect(doc.title).toBe('Example Release Hub')
        expect(iconOf(doc)?.getAttribute('href')).toBe('/api/branding/v1/asset/favicon?v=ba9876543210')
        expect(iconOf(doc)?.hasAttribute('type')).toBe(false)
        expect(doc.head.querySelectorAll('link[rel~="icon"]')).toHaveLength(1)
    })

    it('25: takes the medware settings', async () => {
        const doc = freshDocument()
        await loadBranding(reply(200, JSON.stringify(MEDWARE_SETTINGS)), doc)
        expect({ ...brandingState }).toEqual(MEDWARE_SETTINGS)
        expect(doc.title).toBe('MedWare Cyber Dossier')
        expect(iconOf(doc)?.getAttribute('href')).toBe('/api/branding/v1/asset/favicon?v=652d6f562b4d')
    })

    it('25: adds an icon link to a document that has none', async () => {
        const doc = freshDocument(false)
        await loadBranding(reply(200, JSON.stringify(EXAMPLE_SETTINGS)), doc)
        expect(doc.head.querySelectorAll('link[rel~="icon"]')).toHaveLength(1)
        expect(iconOf(doc)?.getAttribute('href')).toBe('/api/branding/v1/asset/favicon?v=ba9876543210')
    })

    const failures: [string, () => typeof fetch, 'info' | 'warn'][] = [
        ['401 (a backend without the endpoint, through nginx)', () => reply(401, '<html>401</html>'), 'info'],
        ['404', () => reply(404, '<html>404</html>'), 'info'],
        ['500', () => reply(500, '<html>500</html>'), 'warn'],
        ['a network error', () => vi.fn(async () => { throw new TypeError('Failed to fetch') }) as unknown as typeof fetch, 'warn'],
        ['200 with an HTML body (the nginx error page)', () => reply(200, '<!DOCTYPE html><html><body>error</body></html>'), 'warn'],
        ['200 with a JSON array', () => reply(200, '[]'), 'warn'],
        ['200 with null', () => reply(200, 'null'), 'warn']
    ]
    for (const [name, fetchFn, level] of failures) {
        it(`26: ${name} keeps the default and logs one ${level} line`, async () => {
            const doc = freshDocument()
            await loadBranding(fetchFn(), doc)
            expect({ ...brandingState }).toEqual(DEFAULT_BRANDING)
            expect(doc.title).toBe('ReARM')
            expect(iconOf(doc)?.getAttribute('href')).toBe('/favicon.ico')
            expect(info.mock.calls.length + warn.mock.calls.length).toBe(1)
            expect((level === 'info' ? info : warn)).toHaveBeenCalledTimes(1)
        })
    }

    it('26: the 401 line says the endpoint is not available', async () => {
        await loadBranding(reply(401, ''), freshDocument())
        expect(info).toHaveBeenCalledWith('branding: endpoint not available (401), using default')
    })

    it('28c: one GET of the settings, without a token, same-origin, with an abort signal', async () => {
        const fetchFn = reply(200, JSON.stringify(EXAMPLE_SETTINGS))
        await loadBranding(fetchFn, freshDocument())
        const calls = (fetchFn as any).mock.calls
        expect(calls).toHaveLength(1)
        const [url, init] = calls[0]
        expect(url).toBe('/api/branding/v1/settings')
        expect(init.credentials).toBe('same-origin')
        expect(init.headers).toEqual({ Accept: 'application/json' })
        expect(Object.keys(init.headers).map((k: string) => k.toLowerCase())).not.toContain('authorization')
        expect(init.signal).toBeInstanceOf(AbortSignal)
    })

    it('28c: a fetch that never settles is given up after the timeout, with the default and the signal aborted', async () => {
        vi.useFakeTimers()
        let signal: AbortSignal | undefined
        const hung = vi.fn((_url: string, init: RequestInit) => {
            signal = init.signal as AbortSignal
            return new Promise<Response>(() => {})
        }) as unknown as typeof fetch
        const doc = freshDocument()
        let done = false
        const loading = loadBranding(hung, doc, 5000).then(() => { done = true })
        await vi.advanceTimersByTimeAsync(4999)
        expect(done).toBe(false)
        await vi.advanceTimersByTimeAsync(1)
        await loading
        expect(done).toBe(true)
        expect(signal?.aborted).toBe(true)
        expect({ ...brandingState }).toEqual(DEFAULT_BRANDING)
        expect(doc.title).toBe('ReARM')
        expect(warn).toHaveBeenCalledTimes(1)
    })

    it('28c: a fetch that settles clears the timer', async () => {
        vi.useFakeTimers()
        await loadBranding(reply(200, JSON.stringify(EXAMPLE_SETTINGS)), freshDocument(), 5000)
        expect(vi.getTimerCount()).toBe(0)
    })
})

describe('branding: parseBrandingSettings', () => {
    const cases: [keyof typeof EXAMPLE_SETTINGS, unknown][] = [
        ['titleText', ''], ['titleText', '   '], ['titleText', 42], ['titleText', null],
        ['marketingConsent', 'MAYBE'], ['marketingConsent', 'hidden'],
        ['documentationUrl', 'docs.example.com'], ['documentationUrl', 'javascript:alert(1)'],
        ['logoUrl', 'https://evil.example/x.svg'], ['logoUrl', '//evil.example/x.svg'],
        ['supportEmail', 'nope'], ['supportEmail', 'a b@example.com']
    ]
    for (const [field, value] of cases) {
        it(`27: ${field} = ${JSON.stringify(value)} keeps the default for that field only`, () => {
            const parsed = parseBrandingSettings({ ...EXAMPLE_SETTINGS, [field]: value })
            expect(parsed).toEqual({ ...EXAMPLE_SETTINGS, [field]: DEFAULT_BRANDING[field] })
        })
    }

    it('27: an unknown key is ignored', () => {
        const parsed = parseBrandingSettings({ ...EXAMPLE_SETTINGS, banner: 'hello' })
        expect(parsed).toEqual(EXAMPLE_SETTINGS)
        expect(Object.keys(parsed)).not.toContain('banner')
    })

    it('27: text is stored trimmed', () => {
        expect(parseBrandingSettings({ ...EXAMPLE_SETTINGS, titleText: '  Hub  ' }).titleText).toBe('Hub')
    })

    it('27: a malformed isDefault follows the preset name', () => {
        expect(parseBrandingSettings({ ...EXAMPLE_SETTINGS, isDefault: 'no' }).isDefault).toBe(false)
        expect(parseBrandingSettings({ ...EXAMPLE_SETTINGS, preset: 'default', isDefault: 'no' }).isDefault).toBe(true)
    })

    it('27: a body with only a title takes the title and stays default otherwise', () => {
        expect(parseBrandingSettings({ titleText: 'X' })).toEqual({ ...DEFAULT_BRANDING, titleText: 'X' })
    })

    it('27: the consent-shown fixture is taken whole', () => {
        expect(parseBrandingSettings(CONSENT_SHOWN_SETTINGS)).toEqual(CONSENT_SHOWN_SETTINGS)
    })
})

describe('branding: helpers', () => {
    it('28: applySignUpBackground sets the three body styles, default and preset', async () => {
        // A plain style object records the three assignments as written (a DOM would expand the shorthand).
        const body = { style: {} } as unknown as HTMLElement
        applySignUpBackground(body)
        expect(body.style).toEqual({
            background: 'DimGrey none',
            backgroundImage: 'url("/reliza_in_sand_right_corner.jpg")',
            backgroundSize: 'cover'
        })

        const bg = '/api/branding/v1/asset/signUpBackground?v=abcdefabcdef'
        await loadBranding(reply(200, JSON.stringify({ ...EXAMPLE_SETTINGS, signUpBackgroundUrl: bg })), freshDocument())
        const branded = document.createElement('body')
        applySignUpBackground(branded)
        expect(branded.style.backgroundImage).toBe(`url("${bg}")`)
        expect(branded.style.backgroundSize).toBe('cover')
    })

    it('28a: navLogoLayoutFor: wide from a 2:1 ratio up, stacked below and for unknown sizes', () => {
        expect(navLogoLayoutFor(140, 60)).toBe('wide')
        expect(navLogoLayoutFor(200, 100)).toBe('wide')
        expect(navLogoLayoutFor(430, 512)).toBe('stacked')
        expect(navLogoLayoutFor(100, 100)).toBe('stacked')
        expect(navLogoLayoutFor(0, 0)).toBe('stacked')
    })

    it('28b: the Legal hover text, without a doubled full stop', () => {
        Object.assign(brandingState, MEDWARE_SETTINGS)
        expect(legalTooltipText()).toBe(MEDWARE_LEGAL)
        Object.assign(brandingState, EXAMPLE_SETTINGS)
        expect(legalTooltipText().endsWith('Example Release Hub is offered by Example Organization.')).toBe(true)
    })

    it('28b: supportContactHtml, default and escaped', () => {
        expect(supportContactHtml()).toBe('Reliza Support at <a href="mailto:info@reliza.io">info@reliza.io</a>')
        Object.assign(brandingState, { supportName: 'Help <Desk> & Co' })
        expect(supportContactHtml()).toBe('Help &lt;Desk&gt; &amp; Co at <a href="mailto:info@reliza.io">info@reliza.io</a>')
    })
})
