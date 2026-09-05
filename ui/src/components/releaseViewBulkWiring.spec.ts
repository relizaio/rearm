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

/**
 * Throws rather than returning '' on a miss. The first version matched only `async
 * function`, so every assertion about the one plain function here passed against an empty
 * string -- a spec that reads as protection and checks nothing. A rename must fail loudly.
 */
function functionBody (name: string): string {
    const start = code.search(new RegExp(`\\n(async )?function ${name} \\(`))
    if (start < 0) throw new Error(`no function ${name} in ReleaseView.vue`)
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

    /**
     * The walk is awaited while the modal stays closable, so a late collect can land on a
     * reopened form -- jumping to confirm with the previous search's ids while the screen
     * shows the current filter. The per-component modal in this same file already carries
     * this guard; it was not carried across.
     */
    it('fences the collect against a reopened modal', () => {
        expect(functionBody('collectBulkTargets')).toMatch(/gen\s*!==\s*bulkGen/)
    })

    /**
     * The confirmation must quote the filter the WALK used, in the operator's words --
     * "Not disclosed", not the wire value UNATTESTED. Read live instead, it can move
     * between collect and confirm: the search debounce reassigns the applied filter behind
     * the open modal, and a degraded page resets it to ALL.
     */
    it('pins the walked filter and search onto the selection itself', () => {
        const collect = functionBody('collectBulkTargets')
        expect(collect).toMatch(/filter:\s*sbomAppliedFilter\.value/)
        expect(collect).toMatch(/search:\s*sbomAppliedSearch\.value/)
        expect(source).toContain('bulkAppliedLabel')
        const confirm = source.slice(source.indexOf('components will be attested'), 600
            + source.indexOf('components will be attested'))
        expect(confirm, 'the raw enum must not reach operator-facing copy')
            .not.toContain('{{ sbomAppliedFilter }}')
    })

    /**
     * concurrentWriteDetected is only sound when sweptFilter is the filter the walk ran
     * under. Passing the live ref instead can both suppress a genuine colleague race and
     * fire the "somebody else recorded them" accusation about a walk that was never
     * UNATTESTED.
     */
    it('submits under the filter the ids were collected with', () => {
        const body = functionBody('runBulkAttest')
        expect(body).toContain('bulkCollected.value.filter')
        expect(body, 'the live ref would be a different set').not.toContain('sbomAppliedFilter.value')
    })

    /**
     * The compose stage stays mounted for the whole walk, so the values validated before
     * "Review selection" are not necessarily the values sent. reason is a UI-only rule --
     * the server accepts a null reason -- so an unvalidated submit writes thousands of
     * attestations with no audit reason at all.
     */
    it('freezes the form during the walk and re-validates at the write', () => {
        const form = source.slice(source.indexOf('<n-form label-placement="top" '))
        expect(form.slice(0, 120)).toContain(':disabled="bulk.collecting.value"')
        expect(functionBody('runBulkAttest')).toMatch(/bulkErrors\.value\.length/)
    })

    /**
     * naive-ui checks closeOnEsc independently of closable and maskClosable, so locking
     * those two does not stop Esc from dismissing the modal mid-sweep -- discarding the
     * only report of what a no-undo write did. SignUpFlow.vue already sets this.
     */
    it('cannot be dismissed with Esc while writing', () => {
        expect(source).toContain(':close-on-esc="!bulk.submitting.value"')
    })

    /**
     * A refresh failure is not a write failure. Unguarded, it pops a bare red "Error" over
     * "800 attested." and the operator cannot tell which half went wrong. The per-component
     * path in this same file already separates them.
     */
    it('reports a failed post-sweep refresh separately from the write', () => {
        const body = functionBody('runBulkAttest')
        expect(body).toMatch(/try\s*\{[\s\S]*loadSbomComponents\(true\)[\s\S]*\}\s*catch/)
    })

    /**
     * unknownOutcomes exists so the four counters cannot silently stop summing. If nothing
     * renders it, a server that renames an outcome reports "0 attested." over components
     * that were written -- the exact failure the counter was added to catch.
     */
    it('renders unknownOutcomes rather than computing it into a void', () => {
        const done = source.slice(source.indexOf('<!-- DONE:'))
        expect(done, 'it must be rendered, not merely mentioned')
            .toContain('v-if="bulkResult.unknownOutcomes"')
        expect(done).toContain('({{ bulkResult.unknownOutcomes }} of them)')
    })

    /**
     * An aborted sweep that wrote nothing must not render as a green success, and the
     * "re-running is safe" advice must not be attached to a drifted server, where re-running
     * can never work.
     */
    it('does not paint an aborted sweep green, or promise a retry that cannot work', () => {
        const done = source.slice(source.indexOf('<!-- DONE:'))
        expect(done.slice(0, 400), 'the banner type must consult aborted').toContain('bulkResult.aborted')
        const errAlert = done.slice(done.indexOf('{{ bulkResult.error }}'))
        expect(errAlert.slice(0, 300)).toContain('v-if="bulkResult.retryable"')
    })

    /**
     * The composable owns the refusal message ("more than the 5000 a browser sweep will
     * attempt") and clears it only inside collect/submit -- so reopening after narrowing
     * the filter would show a fresh empty form still carrying the old refusal.
     */
    it('clears the composable error when the modal reopens', () => {
        expect(functionBody('openBulkAttest')).toContain('bulk.error.value = null')
    })

    /** party is exported on the BOM; a mis-set attribution is invisible without this. */
    it('shows party and the audit reason at the last checkpoint', () => {
        const confirm = source.slice(source.indexOf('<!-- CONFIRM'), source.indexOf('<!-- DONE:'))
        expect(confirm).toContain('partyLabel(bulkForm.party)')
        expect(confirm).toContain('bulkForm.reason')
    })

    /**
     * "Stop after this batch" says re-running completes the remainder, but the only way back
     * in is openBulkAttest. Wiping the form there would make the operator retype the whole
     * attestation, so half of one sweep could carry a different justification than the other.
     */
    it('keeps the form when reopening after a deliberate stop', () => {
        const body = functionBody('openBulkAttest')
        expect(body).toMatch(/stoppedEarly/)
        expect(body).toMatch(/if \(!resuming\) Object\.assign\(bulkForm/)
    })

    it('surfaces SKIPPED_ATTESTED rather than hiding it', () => {
        expect(source).toContain('concurrentWriteDetected')
    })
})
