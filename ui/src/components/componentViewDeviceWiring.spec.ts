import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { parse as parseSfc } from '@vue/compiler-sfc'

/**
 * FDAW-78 row 17 (REQ-68): the Medical device tab's wording, wired (source level, comments stripped, as
 * componentViewLicenseWiring.spec.ts). ComponentView is never mounted in tests: the No help text of
 * question 1 and the status component are read from its template; the anchor the FDA texts link opens is
 * read from OrgSettings.vue. The status block's own texts are mounted in medicalDeviceSetupStatus.spec.ts.
 */
const read = (f: string) => readFileSync(fileURLToPath(new URL(f, import.meta.url)), 'utf8')
const strip = (s: string) => s
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1')
const componentView = read('./ComponentView.vue')
const template = strip(parseSfc(componentView).descriptor.template?.content ?? '')
const orgSettings = strip(read('./OrgSettings.vue'))

describe('the Medical device tab in words (FDAW-78 row 17)', () => {
    it('question 1\'s No help says what Answering No removes, in device words', () => {
        expect(template.length).toBeGreaterThan(1000)
        expect(template.replace(/\s+/g, ' '))
            .toContain('Answering No <strong>removes the device support dates</strong> entered below: only a medical device has them.')
        expect(template).not.toContain('medical profile')
        expect(template).not.toContain('retracts any declared window')
    })

    it('renders the status block as its own component', () => {
        expect(template).toContain('<MedicalDeviceSetupStatus')
        expect(strip(componentView)).toMatch(/import MedicalDeviceSetupStatus from '\.\/MedicalDeviceSetupStatus\.vue'/)
    })

    it('the organization page carries the anchor and scrolls to it once, for that hash only', () => {
        const heading = orgSettings.match(/<h6[^>]*id="submission-labeling-text"[^>]*>\s*Submission and labeling text/)
        expect(heading).not.toBeNull()
        const guard = orgSettings.indexOf("route.hash === '#submission-labeling-text'")
        expect(guard).toBeGreaterThan(-1)
        const after = orgSettings.slice(guard, guard + 400)
        expect(after).toContain('scrollIntoView')
        expect(after).toContain("getElementById('submission-labeling-text')")
        // called once the settings have loaded
        const load = orgSettings.slice(orgSettings.indexOf('async function loadOrgSettings'))
        expect(load.slice(0, load.indexOf('\n}\n'))).toContain('scrollToLabelingTextOnce()')
    })
})
