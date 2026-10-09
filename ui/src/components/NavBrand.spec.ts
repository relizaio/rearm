// @vitest-environment happy-dom
//
// The nav logo (task WL-3, tests 31 to 31b): the Reliza logo as before on the default branding; on a
// preset, its logo, with the product name beside it only once the loaded logo turns out stacked.
import { beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import NavBrand from './NavBrand.vue'
import { DEFAULT_BRANDING, brandingState, navLogoLayout } from '@/utils/branding'
import { MEDWARE_SETTINGS } from '@/utils/branding.fixtures'

const RouterLink = { props: ['to'], template: '<a :href="to"><slot/></a>' }
const mounted = () => mount(NavBrand, { global: { stubs: { RouterLink } } })

async function loadLogo (w: ReturnType<typeof mounted>, naturalWidth: number, naturalHeight: number) {
    const img = w.find('img.brandLogoCustom')
    Object.defineProperty(img.element, 'naturalWidth', { configurable: true, value: naturalWidth })
    Object.defineProperty(img.element, 'naturalHeight', { configurable: true, value: naturalHeight })
    await img.trigger('load')
}

beforeEach(() => {
    Object.assign(brandingState, DEFAULT_BRANDING)
    navLogoLayout.value = 'unknown'
})

describe('NavBrand', () => {
    it('31: the default shows the Reliza logo and nothing else', () => {
        const w = mounted()
        const logos = w.findAll('[data-testid="nav-logo"]')
        expect(logos).toHaveLength(1)
        expect(logos[0].element.tagName).toBe('IMG')
        expect(logos[0].attributes('id')).toBe('relizaLogo')
        expect(logos[0].attributes('src')).toBe('/logo_svg_no_tag_3.svg')
        expect(w.find('.brandLogoCustom').exists()).toBe(false)
        expect(w.find('[data-testid="nav-title"]').exists()).toBe(false)
    })

    it('31a: medware shows its logo, and the title beside it once the logo loads stacked', async () => {
        Object.assign(brandingState, MEDWARE_SETTINGS)
        const w = mounted()
        const img = w.find('img.brandLogoCustom')
        expect(img.attributes('src')).toBe(MEDWARE_SETTINGS.logoUrl)
        expect(img.attributes('alt')).toBe('MedWare Cyber Dossier')
        expect(img.attributes('data-testid')).toBe('nav-logo')
        expect(w.find('#relizaLogo').exists()).toBe(false)
        expect(w.find('[data-testid="nav-title"]').exists()).toBe(false)
        await loadLogo(w, 430, 512)
        expect(w.find('[data-testid="nav-title"]').text()).toBe('MedWare Cyber Dossier')
    })

    it('31a: a wide logo stands alone', async () => {
        Object.assign(brandingState, MEDWARE_SETTINGS)
        const w = mounted()
        await loadLogo(w, 1400, 600)
        expect(w.find('[data-testid="nav-title"]').exists()).toBe(false)
    })

    it('31b: back to the default, the default markup returns', async () => {
        Object.assign(brandingState, MEDWARE_SETTINGS)
        const w = mounted()
        await loadLogo(w, 430, 512)
        expect(w.find('[data-testid="nav-title"]').exists()).toBe(true)
        Object.assign(brandingState, DEFAULT_BRANDING)
        await nextTick()
        expect(w.find('#relizaLogo').attributes('src')).toBe('/logo_svg_no_tag_3.svg')
        expect(w.find('.brandLogoCustom').exists()).toBe(false)
        expect(w.find('[data-testid="nav-title"]').exists()).toBe(false)
    })
})
