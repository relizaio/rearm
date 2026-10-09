// Branding presets (task WL-3): the UI side of the branding settings the ReARM backend serves at
// GET /api/branding/v1/settings. The UI reads them once at start (AppWrapper) and every branded
// surface (nav, footer, browser title and favicon, sign-up pages, support messages) reads
// brandingState. Anything but a 200 JSON object leaves the default (Reliza) branding in place, which
// is what a backend without the endpoint (ReARM CE) answers.
//
// This module imports nothing from the app (no keycloak, store or GraphQL client), so plain utilities
// and components can read it without triggering a login.
import { reactive, ref } from 'vue'

export type BrandingMarketingConsent = 'SHOWN' | 'HIDDEN'

export interface BrandingSettings {
    preset: string
    isDefault: boolean
    titleText: string
    organizationText: string
    navProductName: string
    supportName: string
    supportEmail: string
    documentationUrl: string
    termsOfServiceUrl: string
    privacyPolicyUrl: string
    legalUrl: string
    marketingConsent: BrandingMarketingConsent
    marketingConsentText: string
    logoUrl: string
    faviconUrl: string
    signUpBackgroundUrl: string
}

export const DEFAULT_BRANDING: Readonly<BrandingSettings> = Object.freeze({
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

export const SETTINGS_PATH = '/api/branding/v1/settings'

export const brandingState: BrandingSettings = reactive<BrandingSettings>({ ...DEFAULT_BRANDING })

export type NavLogoLayout = 'wide' | 'stacked'

export const navLogoLayout = ref<'unknown' | NavLogoLayout>('unknown')

type FieldRule = (v: unknown) => string | undefined

const text: FieldRule = v => (typeof v === 'string' && v.trim() ? v.trim() : undefined)
const email: FieldRule = v => {
    const t = text(v)
    return t && t.includes('@') && !/[\s"<>]/.test(t) ? t : undefined
}
const href: FieldRule = v => (typeof v === 'string' && /^https?:\/\//.test(v) ? v : undefined)
const samePath: FieldRule = v => (typeof v === 'string' && v.startsWith('/') && !v.startsWith('//') ? v : undefined)
const consent: FieldRule = v => (v === 'SHOWN' || v === 'HIDDEN' ? v : undefined)

const FIELD_RULES: Record<Exclude<keyof BrandingSettings, 'isDefault'>, FieldRule> = {
    preset: text,
    titleText: text,
    organizationText: text,
    navProductName: text,
    supportName: text,
    supportEmail: email,
    documentationUrl: href,
    termsOfServiceUrl: href,
    privacyPolicyUrl: href,
    legalUrl: href,
    marketingConsent: consent,
    marketingConsentText: text,
    logoUrl: samePath,
    faviconUrl: samePath,
    signUpBackgroundUrl: samePath
}

function isPlainObject (v: unknown): v is Record<string, unknown> {
    return typeof v === 'object' && v !== null && !Array.isArray(v)
}

// Per field: a value that fails its rule keeps the default's, so one odd value never drops the preset.
export function parseBrandingSettings (body: unknown): BrandingSettings {
    const result: BrandingSettings = { ...DEFAULT_BRANDING }
    const src = isPlainObject(body) ? body : {}
    for (const [key, rule] of Object.entries(FIELD_RULES) as [keyof typeof FIELD_RULES, FieldRule][]) {
        const value = rule(src[key])
        if (value !== undefined) (result as any)[key] = value
    }
    result.isDefault = typeof src.isDefault === 'boolean' ? src.isDefault : result.preset === 'default'
    return result
}

export function applyDocumentBranding (doc: Document = document): void {
    doc.title = brandingState.titleText
    let icon = doc.head.querySelector<HTMLLinkElement>('link[rel~="icon"]')
    if (!icon) {
        icon = doc.createElement('link')
        icon.rel = 'icon'
        doc.head.appendChild(icon)
    }
    icon.href = brandingState.faviconUrl
    // The preset favicon is a PNG, the default an ICO: browsers sniff, a stale type would mislead them.
    icon.removeAttribute('type')
}

// Status and parsed body of the settings call; a body that is not JSON reads as undefined.
async function readSettings (fetchFn: typeof fetch, signal: AbortSignal): Promise<{ status: number, body: unknown }> {
    const response = await fetchFn(SETTINGS_PATH, {
        headers: { Accept: 'application/json' },
        credentials: 'same-origin',
        signal
    })
    if (response.status !== 200) return { status: response.status, body: undefined }
    try {
        return { status: 200, body: await response.json() }
    } catch {
        return { status: 200, body: undefined }
    }
}

// Never throws and never shows anything to the user: on any failure the state stays as it was
// (the default on the first load) and one line goes to the console. A hung backend costs at most
// timeoutMs, even when the fetch ignores the abort.
export async function loadBranding (fetchFn: typeof fetch = globalThis.fetch, doc: Document = document,
    timeoutMs = 5000): Promise<void> {
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout> | undefined
    const timedOut = new Promise<never>((_resolve, reject) => {
        timer = setTimeout(() => {
            controller.abort()
            reject(new Error(`timed out after ${timeoutMs} ms`))
        }, timeoutMs)
    })
    try {
        const { status, body } = await Promise.race([readSettings(fetchFn, controller.signal), timedOut])
        if (status === 401 || status === 404) {
            console.info(`branding: endpoint not available (${status}), using default`)
        } else if (status !== 200) {
            console.warn(`branding: endpoint not available (${status}), using default`)
        } else if (!isPlainObject(body)) {
            console.warn('branding: settings are not a JSON object, using default')
        } else {
            Object.assign(brandingState, parseBrandingSettings(body))
        }
    } catch (err: any) {
        console.warn(`branding: settings could not be read (${err?.message || err}), using default`)
    } finally {
        clearTimeout(timer)
    }
    applyDocumentBranding(doc)
}

export function applySignUpBackground (body: HTMLElement | null = document.body): void {
    if (!body) return
    body.style.background = 'DimGrey none'
    body.style.backgroundImage = `url("${brandingState.signUpBackgroundUrl}")`
    body.style.backgroundSize = 'cover'
}

export function offeredBy (): string {
    const org = brandingState.organizationText
    return `${brandingState.titleText} is offered by ${org}${/[.!?]$/.test(org) ? '' : '.'}`
}

export function legalTooltipText (): string {
    return `ReARM™ is a trademark of Reliza Incorporated, used under license. ${offeredBy()}`
}

function esc (s: string): string {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

export function supportContactHtml (): string {
    const mail = esc(brandingState.supportEmail)
    return `${esc(brandingState.supportName)} at <a href="mailto:${mail}">${mail}</a>`
}

// A logo whose natural width is at least twice its height carries its own wordmark and stands alone in
// the nav; a stacked or square one (or one whose size is unknown) is shown as a mark beside the title.
export function navLogoLayoutFor (naturalWidth: number, naturalHeight: number): NavLogoLayout {
    return naturalHeight > 0 && naturalWidth / naturalHeight >= 2 ? 'wide' : 'stacked'
}
