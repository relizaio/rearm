import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'

/**
 * Import and call-site assertions for the attestation form, comment-aware.
 *
 * Same rationale as the coverage wiring spec: this repo has no vue-tsc, so an undefined
 * identifier in <script setup> is a runtime ReferenceError that `vite build` and the whole
 * vitest suite will happily pass -- which is exactly how the coverage gauge shipped a blank
 * tab. Behaviour lives in useAttestationForm.spec.ts; this covers the seam.
 *
 * Honest about the limit: this catches "removed or commented out", not "made conditional".
 */
const source = readFileSync(
    fileURLToPath(new URL('./ReleaseView.vue', import.meta.url)), 'utf8')
const code = source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split('\n')
    .map(l => l.replace(/(^|[^:])\/\/.*$/, '$1'))
    .join('\n')

describe('the attestation form is wired into ReleaseView', () => {
    it.each([
        ['useAttestationForm', '@/utils/useAttestationForm'],
        ['loadSbomComponentSupportDetail', '@/utils/sbomComponentSupportDetail'],
        ['setSbomComponentSupport', '@/utils/setSbomComponentSupport']
    ])('imports %s from %s', (symbol, module) => {
        expect(source, `${symbol} is used but not imported -- the build will not catch this`)
            .toMatch(new RegExp(
                `import\\s+(type\\s+)?\\{[^}]*\\b${symbol}\\b[^}]*\\}\\s+from\\s+'${module.replace(/\//g, '\\/')}'`))
    })

    /**
     * "Used" means invoked OR bound as a template handler. Vue binds handlers by name --
     * `@click="saveAttestation"` with no parentheses -- so a call-site regex alone reports
     * a correctly-wired handler as dead. The first version of this test did exactly that
     * and failed on two working handlers.
     */
    it.each(['openAttestForm', 'saveAttestation', 'applyNothingPublished'])(
        'references %s in live code, not only declaring it', (fn) => {
            const invoked = code.match(new RegExp(`(?<!function\\s{1,10})\\b${fn}\\s*\\(`, 'g')) || []
            const bound = code.match(new RegExp(`="\\s*${fn}\\s*"`, 'g')) || []
            expect(invoked.length + bound.length,
                `${fn} is neither called nor bound to a handler -- it is dead`)
                .toBeGreaterThan(0)
        })

    /**
     * The write must refresh the gauge. The gauge only re-reads with the list, and an
     * attestation is precisely what moves it -- so a save that skipped this would succeed
     * while the number above the table stayed stale, which reads to an operator as a failure.
     */
    it('refreshes the list AND gauge after a successful save', () => {
        const save = code.slice(code.indexOf('async function saveAttestation'))
            .slice(0, 1400)
        expect(save, 'saveAttestation must force a reload so the gauge re-reads')
            .toMatch(/loadSbomComponents\s*\(\s*true\s*\)/)
    })

    // A double-click on OK is the classic way a modal writes twice; under an audit trail
    // that shows up as two revisions for one intended edit.
    it('guards the save against a double submit', () => {
        const save = code.slice(code.indexOf('async function saveAttestation')).slice(0, 400)
        expect(save).toMatch(/attestSaving\.value/)
    })

    // Root components are never attested: the server skips them and the gauge excludes them.
    it('does not offer the action on root components', () => {
        expect(code).toMatch(/isRoot[\s\S]{0,200}openAttestForm|openAttestForm[\s\S]{0,200}isRoot/)
    })
})
