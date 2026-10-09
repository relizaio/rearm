// Branding presets, the surfaces wired in the sources (task WL-3, tests 32 to 34e): the components are too
// large to mount here, so these are the lines the design names, plus the rules that keep the default
// assets in one place and the public repository free of private names.
import { describe, expect, it } from 'vitest'
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative, resolve } from 'node:path'

const SRC = resolve(__dirname, '..')
const DOCS = resolve(__dirname, '../../../documentation_site/docs')
const src = (f: string) => readFileSync(resolve(SRC, f), 'utf8')
// The source without its comments, for the lines that must be live: a commented-out call or class gate
// still contains the text. Line comments need a non-colon before the slashes so URLs stay intact.
const code = (f: string) => src(f)
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1')

function files (dir: string): string[] {
    return readdirSync(dir).flatMap(f => {
        const p = join(dir, f)
        return statSync(p).isDirectory() ? files(p) : [p]
    })
}

describe('branding surfaces contract', () => {
    it('32: TopNavBar uses NavBrand, the branded grid, and the preset nav name', () => {
        const nav = code('components/TopNavBar.vue')
        expect(nav).toContain('<nav-brand')
        // The gate itself: the style block names the class too, so the bare word would pass without it.
        expect(nav).toContain('<div class="topNavBar" :class="{ topNavBarBranded: !brandingState.isDefault }">')
        expect(nav).toContain('&.topNavBarBranded {')
        expect(nav).not.toContain('relizaLogo')
        expect(nav).not.toContain('logo_svg_no_tag_3.svg')
        expect(nav).toContain('<b>{{ brandingState.navProductName }}</b>')
        expect(nav).not.toContain('<b>ReARM</b>')
    })

    it('33: SignUpFlow reads its links, consent, product name and background from the branding', () => {
        const s = code('components/SignUpFlow.vue')
        for (const line of [':href="brandingState.termsOfServiceUrl"', ':href="brandingState.privacyPolicyUrl"',
            'v-if="brandingState.marketingConsent === \'SHOWN\'"', '{{ brandingState.marketingConsentText }}',
            'to proceed with {{ brandingState.titleText }}', 'applySignUpBackground()', 'marketingAccepted: false']) {
            expect(s).toContain(line)
        }
        const raw = src('components/SignUpFlow.vue')
        for (const literal of ['rearmhq.com', 'reliza_in_sand', 'promotions from Reliza', 'with ReARM']) {
            expect(raw).not.toContain(literal)
        }
    })

    it('34: VerifyEmail and JoinOrganization take the background and the support address from the branding', () => {
        for (const f of ['components/VerifyEmail.vue', 'components/JoinOrganization.vue']) {
            const s = code(f)
            expect(s).toContain('applySignUpBackground()')
            expect(s).toContain('brandingState.supportEmail')
            const raw = src(f)
            expect(raw).not.toContain('info@reliza.io')
            expect(raw).not.toContain('<style')
        }
    })

    it('34a: UserProfile gates the marketing item and column on the consent, and keeps the Reliza words for the default only', () => {
        const s = src('components/UserProfile.vue')
        expect(s).toMatch(/<n-form-item v-if="brandingState\.marketingConsent === 'SHOWN'"\s+:label="brandingState\.isDefault \? 'Receive Reliza News and Promotions\?' :/)
        expect(s).toContain("(installationType === 'SAAS' || installationType === 'DEMO') && brandingState.marketingConsent === 'SHOWN'")
        for (const literal of ['Receive Reliza News and Promotions?', 'Receive Reliza Info?']) {
            const at = [...s.matchAll(new RegExp(literal.replace(/[?]/g, '\\?'), 'g'))].map(m => m.index as number)
            expect(at.length).toBeGreaterThan(0)
            for (const i of at) expect(s.slice(i - 'brandingState.isDefault ? \''.length, i)).toBe('brandingState.isDefault ? \'')
        }
    })

    it('34b: AppWrapper loads the branding before the user and leaves the footer to AppFooter; no Reliza support literals', () => {
        const s = code('components/AppWrapper.vue')
        expect(s).toContain('<app-footer')
        const load = s.indexOf('Promise.all([loadBranding(), fetchCsrfToken()])')
        expect(load).toBeGreaterThan(-1)
        expect(load).toBeLessThan(s.indexOf('fetchMyUser'))
        expect(s).toContain('supportContactHtml()')
        const raw = src('components/AppWrapper.vue')
        for (const literal of ['docs.rearmhq.com', 'info@reliza.io', 'Reliza Support', 'footer-links']) {
            expect(raw).not.toContain(literal)
        }
        for (const f of ['utils/commonFunctions.ts', 'components/DownloadTeaArtifactView.vue']) {
            const t = src(f)
            for (const literal of ['info@reliza.io', 'Reliza Support', 'ReARM Home']) expect(t).not.toContain(literal)
        }
    })

    it('34c: the default assets are named in branding.ts only (and the nav logo in NavBrand)', () => {
        const allowed: Record<string, string[]> = {
            'reliza_in_sand_right_corner.jpg': ['utils/branding.ts'],
            '/favicon.ico': ['utils/branding.ts'],
            'logo_svg_no_tag_3.svg': ['utils/branding.ts', 'components/NavBrand.vue']
        }
        const sources = files(SRC).filter(f => !f.endsWith('.spec.ts') && !f.endsWith('.fixtures.ts'))
        for (const [asset, where] of Object.entries(allowed)) {
            const found = sources.filter(f => readFileSync(f, 'utf8').includes(asset)).map(f => relative(SRC, f)).sort()
            expect(found).toEqual([...where].sort())
        }
    })

    it('34d: the files this feature adds name no private repository', () => {
        const added = ['utils/branding.ts', 'utils/branding.fixtures.ts', 'components/NavBrand.vue',
            'components/AppFooter.vue', 'utils/branding.spec.ts', 'components/NavBrand.spec.ts',
            'components/AppFooter.spec.ts', 'components/brandingSurfacesContract.spec.ts']
            .map(f => resolve(SRC, f)).concat(resolve(DOCS, 'configure/branding.md'))
        // Built from parts, so this spec does not name them either.
        const names = [['rearm', 'saas'], ['rearm', 'core']].map(p => p.join('-')).concat(['backend', 'ai-plans'].join('/'))
        for (const f of added) {
            const s = readFileSync(f, 'utf8')
            for (const n of names) expect(s, `${relative(SRC, f)} names ${n}`).not.toContain(n)
        }
    })

    it('34e: the docs page exists, is in the sidebar, and names the settings', () => {
        expect(readFileSync(resolve(DOCS, '.vitepress/config.mjs'), 'utf8')).toContain("'/configure/branding'")
        const page = resolve(DOCS, 'configure/branding.md')
        expect(existsSync(page)).toBe(true)
        const md = readFileSync(page, 'utf8')
        for (const s of ['brandingPreset', 'BRANDING_PRESET', 'RELIZAPROP_BRANDING_PRESET', 'REARM_BRANDING_PRESET',
            'medware', 'Community Edition']) {
            expect(md).toContain(s)
        }
        expect(md).not.toContain('example')
    })
})
