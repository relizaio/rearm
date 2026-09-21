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
        ['addendumPdfFileName', '@/utils/addendumPdf'],
        ['findUnrenderableText', '@/utils/addendumPdf']
    ])('imports %s from %s', (symbol, module) => {
        expect(source).toMatch(new RegExp(
            `import\\s+\\{[^}]*\\b${symbol}\\b[^}]*\\}\\s+from\\s+'${module.replace(/\//g, '\\/')}'`))
    })

    it('declares the handler the export button routes to', () => {
        expect(source).toMatch(/async function exportFdaAddendum \(/)
    })

    // Its own BOM TYPE since 2026-09-21, not a format of the SBOM: the addendum is a
    // different document that happens to share the modal, and the BOM-shaping options do not
    // apply to it. The formats are now declared in supportExportFormats and rendered by
    // v-for, so the assertion follows them there rather than looking for radio literals that
    // no longer exist in the template.
    it('offers BOTH addendum encodings under the Support bom type', () => {
        expect(source).toMatch(/const supportExportFormats/)
        expect(source).toMatch(/value: 'FDA_ADDENDUM', label: 'Support addendum \(CSV\)'/)
        expect(source).toMatch(/value: 'FDA_ADDENDUM_PDF', label: 'Support addendum \(PDF\)'/)
        expect(source).toContain('<n-radio-button v-if="supportExportAvailable" value="SUPPORT">')
        expect(source).toMatch(/<n-form v-if="exportBomType === 'SUPPORT'">/)
        expect(source).not.toMatch(/n-switch[^>]*addendum/i)
    })

    // The label no longer says FDA. The document is ordinary support disclosure that an FDA
    // submission happens to want, and every radio carrying the agency's name made the whole
    // modal look regulator-specific.
    it('does not label the addendum with the agency name', () => {
        expect(source).not.toContain('FDA support addendum (CSV)')
        expect(source).not.toContain('FDA support addendum (PDF)')
    })

    // Declared, not just referenced: an undefined identifier in <script setup> is a runtime
    // error this build does not catch. It gates the addendum's explanatory alert, which is
    // now the only thing it does -- isFdaDocumentExport is gone, because the controls it used
    // to hide live in a different form entirely.
    it('declares isAddendumExport and uses it for the addendum-specific alert', () => {
        expect(source).toMatch(/const isAddendumExport\b/)
        expect(source).toMatch(/<n-alert v-if="isAddendumExport"/)
    })

    // One collection, one refusal path, branching only at the render step -- a partial
    // document must be impossible in BOTH encodings, not in whichever came first.
    it('routes both encodings through the same collector and refusal', () => {
        const body = handlerBody()
        expect((body.match(/collectAddendumData\(/g) || []).length).toBe(1)
        expect((body.match(/if \(!result\.ok\)/g) || []).length).toBe(1)
        expect(body.indexOf('if (!result.ok)')).toBeLessThan(body.indexOf('renderAddendumPdfBlob'))
    })

    // One handler, so the modal cannot end up with two spinners disagreeing about whether an
    // export is running. The format travels as a VALUE: a boolean here would be the same
    // two-value test that isAddendumExport exists to avoid in the template, and it would have
    // to grow a third state the moment a third encoding lands.
    //
    // The entry point is exportSupportDocument, not exportReleaseSbom. The redirect used to
    // sit at the top of the BOM export, which meant the addendum was reached by handing that
    // function four BOM-shaping arguments it then discarded.
    it('routes both addendum types through the Support export button, carrying the format', () => {
        expect(source).toMatch(/return exportFdaAddendum\(selectedSbomMediaType\.value\)/)
        expect(source).toMatch(/async function exportFdaAddendum \(mediaType: string\)/)
        expect(source).not.toMatch(/exportFdaAddendum\((true|false)\)/)
        expect(source).not.toMatch(/if \(mediaType === 'FDA_ADDENDUM'/)
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

    // The BOM-shaping controls do not apply to the addendum, and since 2026-09-21 that is
    // STRUCTURAL rather than a count of v-ifs. They live in the SBOM form; the Support
    // documents live in their own form; nothing has to stay in step.
    //
    // The count this replaces was protecting a real defect -- the artifact-coverage-type
    // filter was once left ungated while its three siblings were gated, so it stayed
    // toggleable while exportFdaAddendum ignored it. What made that possible was five
    // separate places having to name the FDA documents. The assertion now pins the absence
    // of that mechanism, which is the only way the defect can come back.
    it('keeps the BOM-shaping controls structurally out of the support documents', () => {
        expect(source).not.toContain('isFdaDocumentExport')
        const sbomForm = source.slice(
            source.indexOf(`<n-form v-if="exportBomType === 'SBOM'">`),
            source.indexOf(`<n-form v-if="exportBomType === 'OBOM'">`))
        const supportForm = source.slice(
            source.indexOf(`<n-form v-if="exportBomType === 'SUPPORT'">`),
            source.indexOf(`<n-form v-if="exportBomType === 'CLE'">`))
        for (const control of ['tldOnly', 'ignoreDev', 'filterCoverageType', 'selectedRebomType']) {
            expect(sbomForm, `${control} belongs in the SBOM form`).toContain(control)
            expect(supportForm, `${control} must not reach the Support form`).not.toContain(control)
        }
    })

    // Switching type must leave a format the CURRENT type offers. Without this the modal can
    // display "CycloneDX 1.6 (JSON)" as unselected while Export fires the addendum.
    it('resets the media type when the bom type changes', () => {
        expect(source).toMatch(/watch\(exportBomType/)
        expect(source).toMatch(/selectedSbomMediaType\.value = valid\[0\]/)
        expect(source).toMatch(/selectedSbomMediaType\.value = 'JSON'/)
    })

    // The refusal must come BEFORE any rendering, and must not download anything.
    it('refuses unrenderable text before building the PDF', () => {
        const body = handlerBody()
        expect(body.indexOf('findUnrenderableText')).toBeLessThan(body.indexOf('renderAddendumPdfBlob'))
        expect(body.indexOf('if (unrenderable)')).toBeLessThan(body.indexOf('new Blob'))
    })

    // The stuck-spinner class this feature has already hit once, in pdfmake's callback API.
    it('always clears the export spinner in a finally', () => {
        expect(handlerBody()).toMatch(/finally \{[\s\S]*?bomExportPending\.value = false/)
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
