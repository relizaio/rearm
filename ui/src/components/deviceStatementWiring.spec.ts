import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'

/**
 * Import and wiring assertions for the Device Support Statement export.
 *
 * Fourth in the *Wiring.spec.ts family, for the same reason as the others: no vue-tsc, so an
 * undefined identifier in <script setup> is a runtime error the build ignores.
 */
const source = readFileSync(
    fileURLToPath(new URL('./ReleaseView.vue', import.meta.url)), 'utf8')

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

describe('the device support statement is wired into the export modal', () => {
    it.each([
        ['renderDeviceSupportStatementBlob'],
        ['deviceSupportStatementFileName'],
        ['statementBlockReason']
    ])('imports %s from utils/deviceSupportStatement', (symbol) => {
        expect(source).toMatch(new RegExp(
            `import\\s+\\{[^}]*\\b${symbol}\\b[^}]*\\}\\s+from\\s+'@\\/utils\\/deviceSupportStatement'`))
    })

    it('offers it as its own export type', () => {
        expect(source).toContain('<n-radio-button value="DEVICE_STATEMENT">')
    })

    it('routes it through the single export button', () => {
        expect(source).toMatch(/if \(mediaType === 'DEVICE_STATEMENT'\) return exportDeviceSupportStatement\(\)/)
    })

    // The BOM-shaping options apply to no FDA document, so the gate covers all three types.
    it('hides the BOM-shaping controls for every FDA document, not just the addendum', () => {
        expect((source.match(/v-if="!isFdaDocumentExport"/g) || []).length).toBe(4)
        expect(source).toMatch(/const isFdaDocumentExport\b/)
        expect(source).toMatch(/selectedSbomMediaType\.value === 'DEVICE_STATEMENT'/)
    })

    // Every refusal is checked BEFORE anything is built: a component release, an unauthored
    // prose slot, and text the font cannot draw all block generation rather than degrade it.
    it('blocks before rendering, and downloads nothing when blocked', () => {
        const body = functionBody('exportDeviceSupportStatement')
        expect(body.indexOf('statementBlockReason')).toBeLessThan(body.indexOf('renderDeviceSupportStatementBlob'))
        expect(body.indexOf('if (blocked)')).toBeLessThan(body.indexOf('createElement'))
    })

    it('reuses the same collector as the addendum', () => {
        expect(functionBody('exportDeviceSupportStatement')).toContain('collectAddendumData')
    })

    it('always clears the export spinner in a finally', () => {
        expect(functionBody('exportDeviceSupportStatement'))
            .toMatch(/finally \{[\s\S]*?bomExportPending\.value = false/)
    })

    it('appends, clicks, removes, then revokes the object URL', () => {
        const body = functionBody('exportDeviceSupportStatement')
        expect(body.indexOf('appendChild')).toBeLessThan(body.indexOf('link.click()'))
        expect(body.indexOf('link.click()')).toBeLessThan(body.indexOf('revokeObjectURL'))
    })
})

/**
 * The Export button is DISABLED under the product gate, not enabled into a modal.
 *
 * The panel already tells the operator the statement cannot be generated on a component
 * release. Leaving Export clickable there answered the click with a dialog repeating that
 * sentence -- found by an operator running the walkthrough (board t20260909-061338-23148,
 * step 6d), not by any test, because every unit here passed: the gate text rendered, the
 * refusal fired, the modal said the right thing.
 */
describe('the statement Export button respects the product gate', () => {
    it('disables Export when the panel is already refusing', () => {
        expect(source).toMatch(/:disabled="bomExportPending \|\| statementBlockedHere"/)
    })

    it('derives that only from the statement type and the product gate', () => {
        expect(source).toMatch(
            /statementBlockedHere[\s\S]{0,200}selectedSbomMediaType\.value === 'DEVICE_STATEMENT'/)
        expect(source).toMatch(
            /statementBlockedHere[\s\S]{0,240}!isProductReleaseForStatement\.value/)
    })

    /**
     * The refusals that need data keep their modal. Disabling the button for a reason we have
     * not checked yet would be a worse lie than the redundant dialog this replaced.
     */
    it('leaves the data-dependent refusals as modals', () => {
        expect(source).toMatch(/Swal\.fire\('Statement not generated', blocked, 'warning'\)/)
    })
})
