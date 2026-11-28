import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { parse as parseSfc } from '@vue/compiler-sfc'

/**
 * FDAW-157 T-3: on Create component the Issuing agency select reads the shared options, and the help line
 * sits in the form item's #feedback slot (under the select), not beside it. Source level, comments stripped,
 * as componentViewDeviceWiring.spec.ts.
 */
const read = (f: string) => readFileSync(fileURLToPath(new URL(f, import.meta.url)), 'utf8')
const strip = (s: string) => s
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1')
const source = read('./CreateComponent.vue')
const sfc = parseSfc(source).descriptor
const template = strip(sfc.template?.content ?? '')
const script = strip(sfc.scriptSetup?.content ?? sfc.script?.content ?? '')

function issuingAgencyItem (): string {
    const start = template.indexOf('<n-form-item label="Issuing agency">')
    expect(start).toBeGreaterThan(-1)
    const end = template.indexOf('</n-form-item>', start)
    expect(end).toBeGreaterThan(start)
    return template.slice(start, end)
}

describe('Create component: Issuing agency help (FDAW-157 T-3)', () => {
    it('binds the shared options on the select', () => {
        expect(issuingAgencyItem()).toMatch(/<n-select[^>]*:options="ISSUING_AGENCY_OPTIONS"/)
    })

    it('renders the help line in the #feedback slot, not beside the select', () => {
        const item = issuingAgencyItem()
        const select = item.indexOf('<n-select')
        const feedback = item.indexOf('<template #feedback>')
        expect(select).toBeGreaterThan(-1)
        expect(feedback).toBeGreaterThan(select)
        expect(item.slice(select, feedback)).not.toContain('{{ ISSUING_AGENCY_HELP }}')
        expect(item.slice(feedback)).toMatch(
            /<template #feedback>\s*<span[^>]*data-testid="create-issuing-agency-help"[^>]*>\s*\{\{ ISSUING_AGENCY_HELP \}\}\s*<\/span>\s*<\/template>/)
        expect(template).not.toMatch(/class="hintText"[^>]*>\s*\{\{ ISSUING_AGENCY_HELP \}\}/)
    })

    it('keeps no inline agency array and imports both constants', () => {
        expect(source.replace(/\s+/g, '')).not.toContain("{label:'GS1',value:'GS1'}")
        const imp = script.match(/import \{([^}]*)\} from '@\/utils\/medicalDeviceSetup'/)
        expect(imp).not.toBeNull()
        expect(imp![1]).toMatch(/\bISSUING_AGENCY_HELP\b/)
        expect(imp![1]).toMatch(/\bISSUING_AGENCY_OPTIONS\b/)
    })
})
