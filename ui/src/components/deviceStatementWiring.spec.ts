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
// The collect/refuse/render/download sequence moved here when the shipment entry point
// landed (plan 7h); these pins moved with it rather than being deleted.
const helper = readFileSync(
    fileURLToPath(new URL('../utils/deviceSupportStatementExport.ts', import.meta.url)), 'utf8')

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
    ])('the shared helper imports %s from utils/deviceSupportStatement', (symbol) => {
        expect(helper).toMatch(new RegExp(
            `import\\s+\\{[\\s\\S]*?\\b${symbol}\\b[\\s\\S]*?\\}\\s+from\\s+'\\.\\/deviceSupportStatement'`))
    })

    it('the view calls the shared helper rather than repeating the sequence', () => {
        expect(source).toMatch(
            /import \{ generateDeviceSupportStatement \} from '@\/utils\/deviceSupportStatementExport'/)
        expect(functionBody('exportDeviceSupportStatement')).toContain('generateDeviceSupportStatement(')
        // One copy of the sequence, in the helper. A second would be a second chance to skip
        // the block check, which is the thing that keeps an empty-section PDF from existing.
        expect(source).not.toMatch(/renderDeviceSupportStatementBlob/)
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
        // Scoped to the exported function: `download` is DEFINED above it, so a file-wide
        // index comparison against createElement would compare a definition to a call.
        const fn = helper.slice(helper.indexOf('export async function generateDeviceSupportStatement'))
        expect(fn.indexOf('statementBlockReason')).toBeLessThan(fn.indexOf('renderDeviceSupportStatementBlob'))
        expect(fn.indexOf('if (blocked)')).toBeLessThan(fn.indexOf('download('))
        expect(fn).toMatch(/if \(blocked\) return \{ ok: false, kind: 'BLOCKED'/)
    })

    it('reuses the same collector as the addendum', () => {
        expect(helper).toContain('collectAddendumData')
    })

    it('always clears the export spinner in a finally', () => {
        expect(functionBody('exportDeviceSupportStatement'))
            .toMatch(/finally \{[\s\S]*?bomExportPending\.value = false/)
    })

    it('appends, clicks, removes, then revokes the object URL', () => {
        expect(helper.indexOf('appendChild')).toBeLessThan(helper.indexOf('link.click()'))
        expect(helper.indexOf('link.click()')).toBeLessThan(helper.indexOf('revokeObjectURL'))
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
    it('leaves the data-dependent refusals as modals, and keeps blocked distinct from failed', () => {
        expect(source).toMatch(
            /Swal\.fire\('Statement not generated', outcome\.message, outcome\.kind === 'BLOCKED' \? 'warning' : 'error'\)/)
    })
})

/**
 * The SHIPMENT entry point on the Distribution page (plan 7h).
 *
 * Same file family, same reason: no vue-tsc, so a column that renders an undefined handler
 * is a runtime error every unit test passes over.
 */
const dist = readFileSync(
    fileURLToPath(new URL('./DistributionOfOrg.vue', import.meta.url)), 'utf8')
const shipmentExport = (() => {
    const start = dist.indexOf('async function exportShipmentStatement (')
    if (start < 0) throw new Error('no exportShipmentStatement in DistributionOfOrg.vue')
    let depth = 0
    let i = dist.indexOf('{', start)
    const open = i
    for (; i < dist.length; i++) {
        if (dist[i] === '{') depth++
        else if (dist[i] === '}' && --depth === 0) break
    }
    return dist.slice(open, i + 1)
})()

describe('a statement can be generated from a shipment', () => {
    it('goes through the same helper as the release view, not a second copy', () => {
        expect(dist).toMatch(
            /import \{ generateDeviceSupportStatement \} from '@\/utils\/deviceSupportStatementExport'/)
        expect(shipmentExport).toContain('generateDeviceSupportStatement(')
        expect(dist).not.toMatch(/renderDeviceSupportStatementBlob|statementBlockReason/)
    })

    /**
     * The provenance and the dates come from the SAME row, in the same object. A call that
     * passed the batch without its window would print the model's dates under a line naming
     * the batch -- a statement contradicting itself about which units it describes.
     */
    it('passes the delivery provenance and the delivery window together', () => {
        for (const field of ['siteName:', 'shipDate:', 'batchIdentifier:', 'eos:', 'eol:']) {
            expect(shipmentExport, `${field} must be in the shipment context`).toContain(field)
        }
        expect(shipmentExport).toMatch(/eos: w\?\.eos \|\| null/)
        expect(shipmentExport).toMatch(/eol: w\?\.eol \|\| null/)
        expect(shipmentExport).toMatch(/const w = r\.effectiveDeviceSupportWindow/)
        // named by what a reader can match against a delivery note, not by uuid
        expect(shipmentExport).toMatch(/batchIdentifier: summarizeIds\(r\.identifiers\)/)
        expect(shipmentExport).not.toMatch(/r\.uuid/)
    })

    it('offers the action only where a window is in force', () => {
        const col = dist.slice(dist.indexOf('const statementColumn'), dist.indexOf('const editShipmentColumn'))
        expect(col).toMatch(/const has = !!\(w && \(w\.eos \|\| w\.eol\)\)/)
        expect(col).toMatch(/if \(has\) exportShipmentStatement\(r\)/)
        // and says why, rather than answering the click with a dialog
        expect(col).toMatch(/No device support window is in force for this delivery/)
        // The gate is readable from outside, so the live probe asserts the same fact the
        // unit tests do rather than inferring it from a cursor style.
        expect(col).toMatch(/'data-testid': 'shipment-statement-action'/)
        expect(col).toMatch(/'data-window-in-force': String\(has\)/)
    })

    it('reads the effective window from the shipment query', () => {
        expect(dist).toMatch(/effectiveDeviceSupportWindow \{ eos eol source \}/)
    })

    /**
     * EVERY shipment table offers it, because the gate is the window in force and not the
     * shipment's classification: a plain-software delivery of a device model reached units
     * that run under a window too. Both tables read the SAME column object -- the software
     * table used to pick the edit icon up as `shipmentColumns[length - 1]`, which would have
     * silently handed it whichever column was appended last instead.
     */
    it('is offered by both shipment tables, from one column definition', () => {
        for (const table of ['const shipmentColumns', 'const softwareShipmentColumns']) {
            const cols = dist.slice(dist.indexOf(table))
            expect(cols.slice(0, cols.indexOf('\n])')), `${table} must carry the statement action`)
                .toMatch(/\n    statementColumn,/)
        }
        expect(dist).not.toMatch(/shipmentColumns\.value as any\[\]\)\[\(shipmentColumns/)
    })

    it('reports a refusal as a refusal and a failure as a failure', () => {
        expect(shipmentExport).toMatch(
            /notify\(outcome\.kind === 'BLOCKED' \? 'warning' : 'error', 'Statement not generated'/)
    })
})
