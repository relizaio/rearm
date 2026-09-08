import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'

/**
 * Import and wiring assertions for the per-release narrative override.
 *
 * Same reason as its three siblings: no vue-tsc, so an undefined identifier in
 * <script setup> is a RUNTIME error the build ignores. That is not hypothetical on this
 * feature -- extracting a helper once deleted the `proseBaseline` declaration along with it,
 * and the build, 427 unit tests and eslint were all green while every save threw in the
 * browser.
 */
const source = readFileSync(
    fileURLToPath(new URL('./ReleaseView.vue', import.meta.url)), 'utf8')

/** Sliced by BRACE DEPTH; cutting at the next `function` breaks silently on insertion. */
function functionBody (name: string): string {
    const start = source.indexOf(`function ${name} (`)
    if (start < 0) throw new Error(`no function ${name} in ReleaseView.vue`)
    let depth = 0
    let i = source.indexOf('{', start)
    const open = i
    for (; i < source.length; i++) {
        if (source[i] === '{') depth++
        else if (source[i] === '}' && --depth === 0) break
    }
    return source.slice(open, i + 1)
}

describe('the release narrative editor is wired into ReleaseView', () => {
    it.each([
        ['releaseNarrativeVariables', '@/utils/releaseNarrativeInput'],
        ['FDA_PROSE_MAX_LENGTH', '@/utils/releaseNarrativeInput'],
        ['formatNarrativeChange', '@/utils/narrativeHistory']
    ])('imports %s from %s', (symbol, module) => {
        expect(source).toMatch(new RegExp(
            `import\\s+\\{[^}]*\\b${symbol}\\b[^}]*\\}\\s+from\\s+'${module.replace(/\//g, '\\/')}'`))
    })

    it.each([
        ['releaseNarrative'], ['releaseNarrativeBaseline'], ['savingReleaseNarrative'],
        ['releaseNarrativeError'], ['releaseNarrativeDirty'], ['releaseNarrativeIsOverridden'],
        ['orgNarrativeDefault'], ['saveReleaseNarrative'], ['seedReleaseNarrative']
    ])('declares %s, which the template references', (symbol) => {
        expect(source).toMatch(new RegExp(`(const|function) ${symbol}\\b`))
    })

    // The editor must be seeded EVERY time the release is loaded, not just on one path. The
    // device window shipped with two load paths and only one seeding call would have blanked
    // it on the other.
    it('seeds the narrative wherever it seeds the device window', () => {
        const windowSeeds = (source.match(/seedDeviceWindow\(\)/g) || []).length
        const narrativeSeeds = (source.match(/seedReleaseNarrative\(\)/g) || []).length
        expect(windowSeeds).toBeGreaterThan(0)
        expect(narrativeSeeds).toBe(windowSeeds)
    })

    // The 8,000 cap must be visible, not just enforced server-side: an uncapped paste fails
    // the whole write and names the wire field rather than the label the author sees.
    it('caps the input at the shared bound and shows the count', () => {
        const tag = source.match(/v-model:value="releaseNarrative"[\s\S]*?\/>/)
        expect(tag).not.toBeNull()
        expect(tag![0]).toContain(':maxlength="FDA_PROSE_MAX_LENGTH"')
        expect(tag![0]).toContain('show-count')
        expect(tag![0]).toContain(':disabled="!isWritable || savingReleaseNarrative"')
    })

    // REGRESSION (Layer 1, org prose form): the baseline must come from the mutation's own
    // response, BEFORE the refetch that can throw. Refreshing it only via the refetch meant a
    // failed refetch reported a committed save as failed and left the baseline stale, so the
    // next clear was silently omitted while the server kept the text.
    it('refreshes the baseline from the mutation response before refetching', () => {
        const body = functionBody('saveReleaseNarrative')
        expect(body).toMatch(/resp\?\.data/)
        expect(body.indexOf('releaseNarrativeBaseline.value =')).toBeLessThan(body.indexOf('await fetchRelease()'))
    })

    it('does not fire a mutation when the form is not dirty', () => {
        const body = functionBody('saveReleaseNarrative')
        expect(body.indexOf('if (!vars)')).toBeLessThan(body.indexOf('graphqlClient.mutate'))
    })

    // The author must see what an override replaces. Without the default on screen,
    // "override" is an instruction to write something without being told what it displaces.
    it('shows the org default read-only beneath the editor', () => {
        expect(source).toMatch(/orgNarrativeDefault/)
        const tag = source.match(/:value="orgNarrativeDefault"[\s\S]{0,120}/)
        expect(source).toMatch(/readonly :value="orgNarrativeDefault"/)
        expect(tag).not.toBeNull()
    })

    // A FDA_NARRATIVE event carries no objectId, so without its own branch the history row
    // renders blank -- a Scope column saying the justification changed beside nothing.
    it('renders FDA_NARRATIVE history rows through the length formatter', () => {
        expect(source).toContain("row.rus === 'FDA_NARRATIVE'")
        expect(source).toMatch(/formatNarrativeChange\(row\.oldValue, row\.newValue\)/)
    })

    // Both release queries must select it: fetchRelease switches documents once the
    // artifacts tab is visited, and null means INHERIT rather than an obvious absence.
    it('is selected by both release queries', () => {
        const queries = readFileSync(
            fileURLToPath(new URL('../utils/graphqlQueries.ts', import.meta.url)), 'utf8')
        const hits = (queries.match(/^\s+fdaAssessmentNarrative$/gm) || []).length
        expect(hits).toBeGreaterThanOrEqual(2)
    })
})
