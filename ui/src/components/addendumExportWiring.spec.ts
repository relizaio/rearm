import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { ADDENDUM_COLUMNS } from '@/utils/addendumDocument'

/**
 * Import and wiring assertions for the FDA addendum export.
 *
 * Same reason as releaseViewBulkWiring.spec.ts and fdaProseWiring.spec.ts: no vue-tsc, so
 * an undefined identifier in <script setup> is a RUNTIME error the build ignores. On the
 * previous PR that cost a working feature -- the build, 427 unit tests and eslint were all
 * green while every save threw in the browser.
 */
const source = readFileSync(
    fileURLToPath(new URL('./ReleaseView.vue', import.meta.url)), 'utf8')

/**
 * The handler's body, sliced to its own closing brace by BRACE DEPTH.
 *
 * An earlier version cut at the next `\nasync function`, which silently produced the wrong
 * text -- or an empty string -- the moment a helper was inserted between the two functions.
 * A spec that reads as protection and checks nothing is worse than no spec.
 */
function handlerBody (): string {
    const start = source.indexOf('async function exportFdaAddendum')
    if (start < 0) throw new Error('no exportFdaAddendum in ReleaseView.vue')
    let depth = 0
    let i = source.indexOf('{', start)
    const open = i
    for (; i < source.length; i++) {
        if (source[i] === '{') depth++
        else if (source[i] === '}' && --depth === 0) break
    }
    return source.slice(open, i + 1)
}

describe('the FDA addendum export is wired into the export modal', () => {
    it.each([
        ['collectAddendumData', '@/utils/addendumData'],
        ['renderAddendumCsv', '@/utils/addendumCsv'],
        ['addendumFileName', '@/utils/addendumCsv'],
        ['renderAddendumPdfBlob', '@/utils/addendumPdf'],
        ['addendumPdfFileName', '@/utils/addendumPdf']
    ])('imports %s from %s', (symbol, module) => {
        expect(source).toMatch(new RegExp(
            `import\\s+\\{[^}]*\\b${symbol}\\b[^}]*\\}\\s+from\\s+'${module.replace(/\//g, '\\/')}'`))
    })

    it('declares the handler the export button routes to', () => {
        expect(source).toMatch(/async function exportFdaAddendum \(/)
    })

    // Its own export TYPE, not a toggle: the addendum is a different document that happens
    // to share the modal, and the BOM-shaping options do not apply to it.
    it('offers BOTH addendum encodings as radio options beside the BOM formats', () => {
        expect(source).toContain('<n-radio-button value="FDA_ADDENDUM">')
        expect(source).toContain('<n-radio-button value="FDA_ADDENDUM_PDF">')
        expect(source).not.toMatch(/n-switch[^>]*addendum/i)
    })

    // Declared, not just referenced: the template uses it in four places, and an undefined
    // identifier in <script setup> is a runtime error this build does not catch.
    it('declares isAddendumExport, which the template gates four controls on', () => {
        expect(source).toMatch(/const isAddendumExport\b/)
        expect((source.match(/isAddendumExport/g) || []).length).toBeGreaterThanOrEqual(5)
    })

    // One collection, one refusal path, branching only at the render step -- a partial
    // document must be impossible in BOTH encodings, not in whichever came first.
    it('routes both encodings through the same collector and refusal', () => {
        const body = handlerBody()
        expect((body.match(/collectAddendumData\(/g) || []).length).toBe(1)
        expect((body.match(/if \(!result\.ok\)/g) || []).length).toBe(1)
        expect(body.indexOf('if (!result.ok)')).toBeLessThan(body.indexOf('renderAddendumPdfBlob'))
    })

    // One handler, so the modal cannot end up with two spinners disagreeing about whether
    // an export is running.
    it('routes both addendum types through the single export button', () => {
        expect(source).toMatch(/if \(mediaType === 'FDA_ADDENDUM'\) return exportFdaAddendum\(false\)/)
        expect(source).toMatch(/if \(mediaType === 'FDA_ADDENDUM_PDF'\) return exportFdaAddendum\(true\)/)
    })

    // The refusal must reach the operator. A silent failure here means they believe they
    // hold a complete document.
    it('surfaces a refusal instead of downloading anything', () => {
        const body = handlerBody()
        expect(body).toMatch(/if \(!result\.ok\)/)
        expect(body.indexOf('if (!result.ok)')).toBeLessThan(body.indexOf('new Blob'))
    })

    it('refuses before querying when the release carries no org', () => {
        const body = handlerBody()
        expect(body.indexOf('if (!orgUuid)')).toBeLessThan(body.indexOf('collectAddendumData('))
    })

    // The BOM-shaping controls do not apply to the addendum, so they are HIDDEN rather than
    // left enabled and silently ignored -- an operator who ticked "Top Level Dependencies
    // Only" and got a full-scope document would have no way to tell.
    it('hides the BOM-shaping controls for EITHER addendum encoding', () => {
        const gates = source.match(/v-if="!isAddendumExport"/g) || []
        expect(gates.length).toBeGreaterThanOrEqual(3)
    })

    it('appends, clicks, removes, then revokes the object URL', () => {
        const body = handlerBody()
        expect(body).toContain('revokeObjectURL')
        expect(body.indexOf('appendChild')).toBeLessThan(body.indexOf('link.click()'))
        expect(body.indexOf('link.click()')).toBeLessThan(body.indexOf('revokeObjectURL'))
    })
})

describe('the addendum column set', () => {
    // Named here so a column added to the renderer without a matching header, or reordered
    // against the row builder, fails rather than shipping a shifted document.
    it('is the eight documented columns, in order', () => {
        expect(ADDENDUM_COLUMNS).toEqual([
            'Component', 'Version', 'PURL', 'Level of support', 'End of support',
            'Justification', 'Attestation state', 'Last assessed'
        ])
    })
})
