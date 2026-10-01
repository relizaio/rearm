import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'

/**
 * Asserts ReleaseView.vue IMPORTS what it uses. Nothing else.
 *
 * This is a deliberately narrow file, and the narrowing is the point. Its predecessor also
 * claimed to guard the wiring -- that loadSbomCoverage is called, that the release change
 * clears the gauge, that the number is not derived from the list -- by scanning the SFC
 * source. Review demonstrated three mutations that left all of it green, including
 * commenting the call out, which is a verbatim restoration of a bug that had already
 * shipped once. A source scan cannot tell code from a comment. My own probe of that spec
 * passed only because it DELETED the line instead of commenting it, removing the very text
 * the scan looks for; I picked the one mutation that happened to work.
 *
 * Those behavioural claims now live in useReleaseSupportCoverage.spec.ts, where the code
 * actually runs. What survives here is the one assertion a running test cannot make and the
 * build cannot either: that the symbols are imported. This repo has no vue-tsc, so an
 * undefined identifier in <script setup> is a RUNTIME ReferenceError -- `vite build` and the
 * whole vitest suite passed on a bundle where none of these were imported, and the SBOM tab
 * rendered blank with "coverageDisplay is not defined" in the console.
 */
const source = readFileSync(
    fileURLToPath(new URL('./ReleaseView.vue', import.meta.url)), 'utf8')

/**
 * Source with comments removed, so "is this called" cannot be satisfied by a commented-out
 * call. That is not a hypothetical distinction: commenting the call out is precisely the
 * mutation that defeated the previous version of this file, and it is a verbatim
 * restoration of the bug that shipped.
 *
 * Honest about the limit: this closes "the call was removed or commented out". It does NOT
 * close "the call was made conditional" -- `if (forceRefresh) await loadSbomCoverage()`
 * still reads as a call site here. Only running the component would catch that, and this
 * repo has neither @vue/test-utils nor a DOM environment. The browser probe at ~/scratch/e2e
 * is what covers it, and it is manual.
 */
const code = source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split('\n')
    .map(line => line.replace(/(^|[^:])\/\/.*$/, '$1'))
    .join('\n')

describe('ReleaseView imports the coverage symbols it uses', () => {
    it.each([
        ['coverageDisplay', '@/utils/supportCoverageDisplay'],
        ['CoverageDisplay', '@/utils/supportCoverageDisplay'],
        ['useReleaseSupportCoverage', '@/utils/useReleaseSupportCoverage']
    ])('imports %s from %s', (symbol, module) => {
        const imported = new RegExp(
            `import\\s+(type\\s+)?\\{[^}]*\\b${symbol}\\b[^}]*\\}\\s+from\\s+'${module.replace(/\//g, '\\/')}'`)
        expect(source, `${symbol} is used but not imported -- vite build will not catch this`)
            .toMatch(imported)
    })

    /**
     * The call site itself, checked against comment-stripped source.
     *
     * The bug this feature already shipped once was a function defined and never invoked:
     * the gauge rendered "not available from this server" on every release, on every server,
     * while the build and the whole suite stayed green.
     */
    it('calls loadSbomCoverage in live code, not in a comment', () => {
        const calls = code.match(/(?<!function\s{1,10})\bloadSbomCoverage\s*\(/g) || []
        expect(calls.length,
            'loadSbomCoverage is never invoked in live code -- the gauge will render'
            + ' "not available from this server" everywhere')
            .toBeGreaterThan(0)
    })

    /**
     * The release-change reset. Its predecessor asserted `toContain('sbomCoverage.value =
     * null')`, which was satisfied by an unrelated line in a catch block -- permanently
     * green regardless of what goToRelease did.
     */
    it('resets the coverage state in live code when the release changes', () => {
        expect(code, 'nothing resets the gauge on release change -- the next release will be'
            + " captioned with the previous one's disclosure count")
            .toMatch(/sbomCoverageState\s*\.\s*reset\s*\(/)
    })

    // Every symbol referenced from those two modules must appear in an import. Catches the
    // next one added without an import, not just today's three.
    it('has no coverage symbol used without a matching import', () => {
        const used = ['coverageDisplay', 'useReleaseSupportCoverage']
            .filter(sym => new RegExp(`\\b${sym}\\s*\\(`).test(source))
        for (const sym of used) {
            expect(source, `${sym} is called but never imported`)
                .toMatch(new RegExp(`import[^;]*\\b${sym}\\b[^;]*from`))
        }
        expect(used.length).toBeGreaterThan(0)
    })
})
