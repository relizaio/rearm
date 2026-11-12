// @vitest-environment happy-dom
//
// FDAW-78 row 16 (REQ-68): the Medical device tab's setup status, mounted. The intro sentence while a
// step is not done, one line per organization step (done, or what is still to do), each distinct
// server sentence once, the must-fix alert's link, and where the four FDA texts are written.
import { describe, expect, it, vi } from 'vitest'
import { mount, RouterLinkStub } from '@vue/test-utils'

vi.mock('pdfmake/build/pdfmake', () => ({ default: { vfs: null, createPdf: vi.fn() } }))
vi.mock('pdfmake/build/vfs_fonts', () => ({ default: { vfs: {} } }))

const { default: Status } = await import('./MedicalDeviceSetupStatus.vue')
const { expectSwept } = await import('@/utils/__fixtures__/fdaSweep')

const NOT_DONE = { supportExport: 'DISABLED', fdaProfile: null, orgPackageProfile: null }
const ALL_DONE = { supportExport: 'ENABLED', fdaProfile: 'INCLUDES_SUPPORT', orgPackageProfile: 'fda' }
const INTRO = 'Save completes the steps below that your role allows. The organization steps need an organization administrator.'
const MUST_FIX = 'The SBOM profile custom used for FDA premarket packages leaves out support information: include it under'
    + ' Organization Settings > Admin Settings > SBOM Profiles, or choose a profile that includes it'
const PRODUCT_STEP = 'FDA premarket settings for GlucoView need a product administrator: an administrator of GlucoView or of the'
    + ' organization completes this step'
// FDAW-76 C1's literals, before this task
const OLD_LINES = ['Support metadata export: off', 'SBOM profile fda: absent', 'Organization FDA package settings: SBOM profile default']
const OLD_PRODUCT_STEP = 'FDA package settings for GlucoView need a product administrator: an administrator of GlucoView or of the'
    + ' organization completes this step'

function mountWith (props: Record<string, any> = {}) {
    return mount(Status, { props: { orgUuid: 'org-1', fdaPremarket: 'ENABLED', ...props },
        global: { stubs: { RouterLink: RouterLinkStub } } })
}
const lineTexts = (w: any) => w.findAll('[data-testid^="device-setup-line-"]').map((l: any) => l.text())

describe('MedicalDeviceSetupStatus (FDAW-78 row 16)', () => {
    it('nothing done: the intro, then Not done yet: and each todo, in step order; none of the old words', () => {
        const w = mountWith({ status: NOT_DONE })
        expect(w.find('[data-testid="device-setup-intro"]').text()).toBe(INTRO)
        expect(w.findAll('[data-testid^="device-setup-line-"]').map((l: any) => l.attributes('data-testid'))).toEqual([
            'device-setup-line-ORG_SUPPORT_EXPORT', 'device-setup-line-ORG_FDA_PROFILE', 'device-setup-line-ORG_PACKAGE_SETTINGS'])
        expect(lineTexts(w)).toEqual([
            'Not done yet: include support information (level of support, end-of-support dates) in the SBOMs this organization exports',
            'Not done yet: add the FDA recipe for SBOMs (SBOM profile fda)',
            'Not done yet: point the organization\'s FDA premarket defaults at the FDA recipe'])
        for (const t of lineTexts(w)) {
            expect(t).not.toContain('Support metadata export')
            expect(t).not.toContain('SBOM profile fda: ')
            expect(t).not.toContain('package settings')
        }
        // control: the three old literals carry those words
        expect(OLD_LINES[0]).toContain('Support metadata export')
        expect(OLD_LINES[1]).toContain('SBOM profile fda: ')
        expect(OLD_LINES[2]).toContain('package settings')
        expectSwept(w.text(), 'not done')
    })

    it('all done: no intro, three lines starting with a check mark', () => {
        const w = mountWith({ status: ALL_DONE })
        expect(w.find('[data-testid="device-setup-intro"]').exists()).toBe(false)
        expect(lineTexts(w)).toHaveLength(3)
        for (const t of lineTexts(w)) expect(t.startsWith('\u2713 ')).toBe(true)
        expect(lineTexts(w)[2]).toBe('\u2713 The organization\'s FDA premarket defaults use the FDA recipe')
    })

    it('two identical step notes from the parent arrive deduplicated: one Still to do line', async () => {
        const { setupStepLines } = await import('@/utils/medicalDeviceSetup')
        const notes = setupStepLines({ steps: [
            { step: 'ORG_SUPPORT_EXPORT', outcome: 'NOT_AUTHORIZED', text: 'org sentence' },
            { step: 'ORG_FDA_PROFILE', outcome: 'NOT_AUTHORIZED', text: 'org sentence' }] })
        const w = mountWith({ status: NOT_DONE, stepNotes: notes })
        const still = w.findAll('[data-testid^="device-step-not-done-"]')
        expect(still).toHaveLength(1)
        expect(still[0].text()).toBe('Still to do: org sentence')
        expect(w.findAll('div').filter((d: any) => d.text().startsWith('Still to do: ') && !d.find('div').exists())).toHaveLength(1)
    })

    it('the FDA texts block while question 2 is Yes, with its link to Submission and labeling text; absent on No', () => {
        const w = mountWith({ status: ALL_DONE })
        const block = w.find('[data-testid="device-fda-texts"]')
        expect(block.text()).toContain('the assessment justification and the three labeling statements (patches may cease at end of'
            + ' support, risk-transfer process reference, risk increases over time)')
        expect(block.text().replace(/\s+/g, ' ')).toBe('FDA texts: the assessment justification and the three labeling statements'
            + ' (patches may cease at end of support, risk-transfer process reference, risk increases over time) are written once'
            + ' for the organization, under Organization Settings > Admin Settings > General > Submission and labeling text.'
            + ' An organization administrator edits them.')
        const link = w.findAllComponents(RouterLinkStub).find((l: any) => l.attributes('data-testid') === 'device-fda-texts-link')!
        expect(link.text()).toBe('Organization Settings > Admin Settings > General > Submission and labeling text')
        expect(link.props('to')).toEqual({ name: 'OrgSettings', params: { orguuid: 'org-1' },
            query: { tab: 'adminSettings', adminTab: 'general' }, hash: '#submission-labeling-text' })
        expect(mountWith({ status: ALL_DONE, fdaPremarket: 'DISABLED' }).find('[data-testid="device-fda-texts"]').exists()).toBe(false)
    })

    it('the must-fix alert shows the server sentence and links to Admin Settings > SBOM Profiles (F-4)', () => {
        const w = mountWith({ status: { ...ALL_DONE, mustFix: [MUST_FIX] } })
        const alert = w.find('[data-testid="device-setup-must-fix"]')
        expect(alert.text()).toContain(MUST_FIX)
        const link = w.findAllComponents(RouterLinkStub).find((l: any) => l.attributes('data-testid') === 'device-setup-must-fix-link')!
        expect(link.text()).toBe('Organization Settings > Admin Settings > SBOM Profiles')
        expect(link.text()).not.toBe('Organization Settings > SBOM Profiles')
        expect(link.props('to')).toEqual({ name: 'OrgSettings', params: { orguuid: 'org-1' },
            query: { tab: 'adminSettings', adminTab: 'sbomProfiles' } })
    })

    it('a product step the caller could not make reads Still to do with the server sentence; no settings words (F-7)', () => {
        const w = mountWith({ status: NOT_DONE, stepNotes: [{ step: 'PRODUCT_PACKAGE_SETTINGS', text: PRODUCT_STEP }] })
        expect(w.find('[data-testid="device-step-not-done-PRODUCT_PACKAGE_SETTINGS"]').text()).toBe('Still to do: ' + PRODUCT_STEP)
        const block = w.find('[data-testid="device-setup-status"]').text()
        for (const word of ['package settings', 'Support metadata export', 'support metadata']) expect(block).not.toContain(word)
        expect(OLD_PRODUCT_STEP).toContain('package settings')
        expectSwept(block, 'product step')
    })
})
