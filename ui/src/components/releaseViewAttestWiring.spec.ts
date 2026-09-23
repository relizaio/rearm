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

/**
 * The whole body of a top-level function, rather than a fixed number of characters.
 *
 * The first version sliced 1400 chars from the declaration, and two assertions started
 * failing the moment saveAttestation grew -- reporting missing wiring that was present a few
 * lines further down. A guard that fails as the code it guards gets longer is a guard that
 * will be deleted.
 */
function functionBody (name: string): string {
    const start = code.indexOf(`async function ${name} (`)
    if (start < 0) return ''
    const rest = code.slice(start + 1)
    const next = rest.search(/\n(async )?function /)
    return next < 0 ? rest : rest.slice(0, next)
}

describe('the attestation form is wired into ReleaseView', () => {
    it.each([
        ['useAttestationForm', '@/utils/useAttestationForm'],
        ['loadSbomComponentSupportDetail', '@/utils/sbomComponentSupportDetail'],
        ['setSbomComponentSupportVars', '@/utils/setSbomComponentSupport']
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
        const save = functionBody('saveAttestation')
        expect(save, 'saveAttestation must force a reload so the gauge re-reads')
            .toMatch(/loadSbomComponents\s*\(\s*true\s*\)/)
    })

    /**
     * Notes must be rendered per milestone, not as one component-level box. The server
     * stores them against the staged milestone, so a single box discarded a notes-only edit
     * and mislabelled notes saved alongside one date.
     */
    it('renders notes per milestone rather than one component-level box', () => {
        expect(code).toContain('milestoneNotes[m.type]')
        expect(code).not.toContain('form.supportNotes')
    })

    // One supportNotes argument per call means two milestones must be serialised.
    it('serialises the writes rather than issuing one', () => {
        expect(functionBody('saveAttestation'))
            .toMatch(/for\s*\(\s*const\s+vars\s+of\s+sets/)
    })

    // A double-click on OK is the classic way a modal writes twice; under an audit trail
    // that shows up as two revisions for one intended edit.
    it('guards the save against a double submit', () => {
        expect(functionBody('saveAttestation')).toMatch(/attestSaving\.value/)
    })

    /**
     * A serialised save can land call 1 and fail call 2. Reporting that as a plain failure
     * makes the operator resubmit both, re-stamping the milestone that already landed.
     */
    it('folds completed writes into the baseline on a partial failure', () => {
        const save = functionBody('saveAttestation')
        expect(save, 'a partial save must not leave landed writes pending')
            .toMatch(/markSaved\s*\(\s*done\s*\)/)
    })

    // Root components are never attested: the server skips them and the gauge excludes them.
    it('does not offer the action on root components', () => {
        expect(code).toMatch(/isRoot[\s\S]{0,200}openAttestForm|openAttestForm[\s\S]{0,200}isRoot/)
    })
})
