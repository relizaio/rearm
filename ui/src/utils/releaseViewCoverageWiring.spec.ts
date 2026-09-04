import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'

/**
 * Guards the SEAM between the coverage modules and the component that renders them.
 *
 * This file exists because of a real bug, not a hypothetical one. The gauge shipped with
 * `loadSbomCoverage` DEFINED and never CALLED: `sbomCoverage` stayed null, and the alert
 * rendered "Support coverage is not available from this server" on every release, including
 * a Pro server that would have answered perfectly well. Nineteen specs passed, because every
 * one of them exercised a pure module -- and the single integration point had no coverage at
 * all. The end-to-end validation that was run went through the GraphQL API directly, so it
 * confirmed the field worked while saying nothing about whether the UI asked for it.
 *
 * Mounting ReleaseView.vue is impractical (a 6,000-line SFC with router, Apollo and
 * notification-provider dependencies), so this asserts the wiring at the source level. It is
 * crude, and it is precisely targeted at the failure that happened: a function defined and
 * not invoked.
 */
const SFC = fileURLToPath(new URL('../components/ReleaseView.vue', import.meta.url))
const source = readFileSync(SFC, 'utf8')

/**
 * Occurrences of `name(...)` that are not the declaration.
 *
 * Counted with one regex over both forms rather than by subtracting a separately-counted
 * declaration: the first version of this helper matched calls as `name(` but declarations as
 * `function name (` -- with a space -- so it subtracted a declaration it had never counted
 * and reported zero call sites for correctly-wired code. The test failed on its own arithmetic
 * before it could judge anything, which is at least the right direction for a guard to fail in.
 */
function callSites (name: string): number {
    const matches = source.match(new RegExp(`(?<!function\\s{1,10})\\b${name}\\s*\\(`, 'g'))
    return matches ? matches.length : 0
}

describe('the coverage gauge is actually wired into ReleaseView', () => {
    /**
     * Catches "used but never imported", which this repo's build CANNOT.
     *
     * There is no vue-tsc here, so `vite build` compiles an SFC whose <script setup> refers
     * to an undefined identifier without complaint -- it is a RUNTIME ReferenceError, not a
     * build error. That is not hypothetical: the gauge shipped a build-clean, test-clean
     * bundle where none of these four symbols were imported, and the whole SBOM tab rendered
     * blank in the browser with "coverageDisplay is not defined" in the console.
     */
    it.each([
        ['coverageDisplay', '@/utils/supportCoverageDisplay'],
        ['CoverageDisplay', '@/utils/supportCoverageDisplay'],
        ['loadReleaseSupportCoverage', '@/utils/releaseSupportCoverage'],
        ['ReleaseSupportCoverage', '@/utils/releaseSupportCoverage']
    ])('imports %s from %s', (symbol, module) => {
        const imported = new RegExp(
            `import\\s+(type\\s+)?\\{[^}]*\\b${symbol}\\b[^}]*\\}\\s+from\\s+'${module.replace('/', '\\/')}'`)
        expect(source, `${symbol} is used but not imported -- vite build will not catch this`)
            .toMatch(imported)
    })

    it('defines loadSbomCoverage', () => {
        expect(source).toMatch(/function loadSbomCoverage\s*\(/)
    })

    // The assertion that would have caught the shipped bug.
    it('CALLS loadSbomCoverage, not merely defines it', () => {
        expect(callSites('loadSbomCoverage'),
            'loadSbomCoverage is defined but never invoked -- the gauge will render'
            + ' "not available from this server" on every release, on every server')
            .toBeGreaterThan(0)
    })

    /**
     * Loading and unavailable are different states and must not share a rendering. Before
     * this gate, the null-before-response window painted a false "unavailable" on every
     * load, which is worse than a spinner: it is a definite claim about the server.
     */
    it('does not render the gauge while the coverage request is still in flight', () => {
        expect(source).toContain('!sbomCoverageLoading')
    })

    /** The gauge belongs to the release that was showing when it was fetched. */
    it('clears the coverage when the release changes', () => {
        expect(source).toContain('sbomCoverage.value = null')
    })

    /**
     * The rule the gauge exists to honour: its number comes from the coverage query, never
     * from the component list. They agree for the unfiltered case, which is exactly what
     * makes substituting one for the other tempting and invisible.
     */
    it('does not derive the gauge from the component list', () => {
        const display = source.slice(source.indexOf('sbomCoverageDisplay'))
            .slice(0, 400)
        expect(display).not.toContain('sbomComponents.length')
        expect(display).not.toContain('totalCount')
    })
})
