import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'

/**
 * Import and wiring assertions for the bulk sweep, comment-aware.
 *
 * Same reason as its siblings: no vue-tsc, so an undefined identifier in <script setup> is a
 * runtime error the build ignores. Behaviour lives in useBulkAttest.spec.ts; this covers the
 * seam, and specifically the two things a sweep must not get wrong.
 */
const source = readFileSync(
    fileURLToPath(new URL('./ReleaseView.vue', import.meta.url)), 'utf8')
const code = source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split('\n')
    .map(l => l.replace(/(^|[^:])\/\/.*$/, '$1'))
    .join('\n')

function functionBody (name: string): string {
    const start = code.indexOf(`async function ${name} (`)
    if (start < 0) return ''
    const rest = code.slice(start + 1)
    const next = rest.search(/\n(async )?function /)
    return next < 0 ? rest : rest.slice(0, next)
}

describe('the bulk sweep is wired into ReleaseView', () => {
    it.each([
        ['useBulkAttest', '@/utils/useBulkAttest'],
        ['validateBulkInput', '@/utils/useBulkAttest']
    ])('imports %s from %s', (symbol, module) => {
        expect(source).toMatch(new RegExp(
            `import\\s+\\{[^}]*\\b${symbol}\\b[^}]*\\}\\s+from\\s+'${module.replace(/\//g, '\\/')}'`))
    })

    it.each(['openBulkAttest', 'collectBulkTargets', 'runBulkAttest'])(
        'references %s in live code', (fn) => {
            const invoked = code.match(new RegExp(`(?<!function\\s{1,10})\\b${fn}\\s*\\(`, 'g')) || []
            const bound = code.match(new RegExp(`="\\s*${fn}\\s*"`, 'g')) || []
            expect(invoked.length + bound.length, `${fn} is dead`).toBeGreaterThan(0)
        })

    /**
     * THE one that matters. The sweep must walk the filter the LIST is showing, not the
     * live input boxes -- during the search debounce those differ, and sweeping what the box
     * says rather than what the operator can see is how the wrong set gets attested.
     */
    it('collects using the APPLIED filter and search, never the live inputs', () => {
        const body = functionBody('collectBulkTargets')
        expect(body).toContain('sbomAppliedFilter')
        expect(body).toContain('sbomAppliedSearch')
        expect(body, 'the live search input must not drive the sweep')
            .not.toContain('sbomSearchQueryInput')
    })

    /** One instant for the sweep, captured at confirmation rather than per write. */
    it('captures a single assessedAt before submitting', () => {
        const body = functionBody('runBulkAttest')
        expect(body).toMatch(/bulkAssessedAt\.value\s*=/)
        expect(body).toMatch(/assessedAt:\s*bulkAssessedAt\.value/)
    })

    /** The sweep moves the gauge; without this it reads as a failure. */
    it('refreshes the list and gauge after the sweep', () => {
        expect(functionBody('runBulkAttest')).toMatch(/loadSbomComponents\s*\(\s*true\s*\)/)
    })

    // A count alone cannot catch a filter that silently reset to All.
    it.each(['sbomAppliedFilter', 'sbomAppliedSearch', 'bulkCollected.sample', 'backlogTotal'])(
        'the confirmation shows %s', (token) => expect(source).toContain(token))

    it('surfaces SKIPPED_ATTESTED rather than hiding it', () => {
        expect(source).toContain('concurrentWriteDetected')
    })
})
