// @vitest-environment happy-dom
//
// The footer (task WL-3, tests 29 to 30a): the Reliza footer exactly as before on the default branding,
// and on a preset its product name, the Built on ReARM line, its links and the Legal hover text.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { DEFAULT_BRANDING, brandingState, legalTooltipText } from '@/utils/branding'
import { EXAMPLE_SETTINGS, MEDWARE_SETTINGS } from '@/utils/branding.fixtures'

const getters: { myuser: any } = { myuser: { installationType: 'SAAS' } }
vi.mock('vuex', () => ({ useStore: () => ({ getters }) }))
const { default: AppFooter } = await import('./AppFooter.vue')

const tooltip = { template: '<span class="tt"><slot name="trigger"/><span class="tt__body"><slot/></span></span>' }
const divider = { template: '<hr/>' }
const stubs = { NTooltip: tooltip, Tooltip: tooltip, NDivider: divider, Divider: divider }
const mounted = () => mount(AppFooter, { global: { stubs } })
const byId = (w: ReturnType<typeof mounted>, id: string) => w.find(`[data-testid="${id}"]`)

const MEDWARE_LEGAL = 'ReARM™ is a trademark of Reliza Incorporated, used under license. MedWare Cyber Dossier is offered by MedWare Cyber, LLC.'

beforeEach(() => {
    Object.assign(brandingState, DEFAULT_BRANDING)
    getters.myuser = { installationType: 'SAAS' }
})

describe('AppFooter: default branding', () => {
    it('29: the Reliza footer, Pro and CE, with Documentation and Support only', () => {
        let w = mounted()
        expect(byId(w, 'footer-product').text()).toBe('ReARM Pro')
        expect(byId(w, 'footer-copyright').text()).toBe('© Reliza Incorporated, 2019-2026')
        expect(byId(w, 'footer-docs').attributes('href')).toBe('https://docs.rearmhq.com')
        expect(byId(w, 'footer-docs').attributes('target')).toBe('_blank')
        expect(byId(w, 'footer-support').attributes('href')).toBe('mailto:info@reliza.io')
        expect(byId(w, 'footer-legal').exists()).toBe(false)
        expect(byId(w, 'footer-legal-tooltip').exists()).toBe(false)

        getters.myuser = { installationType: 'OSS' }
        w = mounted()
        expect(byId(w, 'footer-product').text()).toBe('ReARM CE')
    })
})

describe('AppFooter: a preset', () => {
    it('30: medware', () => {
        Object.assign(brandingState, MEDWARE_SETTINGS)
        const w = mounted()
        expect(byId(w, 'footer-product').text()).toBe('MedWare Cyber Dossier')
        expect(byId(w, 'footer-copyright').text()).toBe('Built on ReARM™ · © 2019–2026 Reliza Incorporated.')
        expect(byId(w, 'footer-docs').attributes('href')).toBe('https://docs.rearmhq.com')
        expect(byId(w, 'footer-support').attributes('href')).toBe('mailto:info@reliza.io')
        const legal = byId(w, 'footer-legal')
        expect(legal.attributes('href')).toBe('https://rearmhq.com/tos.html')
        expect(legal.attributes('target')).toBe('_blank')
        expect(legal.attributes('rel')).toContain('noopener')
        expect(legal.attributes('title')).toBe(legalTooltipText())
        expect(byId(w, 'footer-legal-tooltip').text()).toBe(MEDWARE_LEGAL)
    })

    it('30: example takes every link from the preset', () => {
        Object.assign(brandingState, EXAMPLE_SETTINGS)
        const w = mounted()
        expect(byId(w, 'footer-product').text()).toBe('Example Release Hub')
        expect(byId(w, 'footer-docs').attributes('href')).toBe('https://docs.example.com')
        expect(byId(w, 'footer-support').attributes('href')).toBe('mailto:support@example.com')
        expect(byId(w, 'footer-legal').attributes('href')).toBe('https://example.com/legal')
    })

    for (const installationType of ['SAAS', 'OSS', 'DEMO']) {
        it(`30a: a preset footer names neither the Reliza copyright nor the edition (${installationType})`, () => {
            getters.myuser = { installationType }
            Object.assign(brandingState, MEDWARE_SETTINGS)
            const text = mounted().text()
            expect(text).not.toContain('Reliza Incorporated, 2019-2026')
            expect(text).not.toContain('ReARM Pro')
            expect(text).not.toContain('ReARM CE')
        })
    }
})
