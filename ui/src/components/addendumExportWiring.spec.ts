import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { ADDENDUM_COLUMNS } from '@/utils/addendumCsv'

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

describe('the FDA addendum export is wired into the export modal', () => {
    it.each([
        ['collectAddendumData', '@/utils/addendumData'],
        ['renderAddendumCsv', '@/utils/addendumCsv'],
        ['addendumFileName', '@/utils/addendumCsv']
    ])('imports %s from %s', (symbol, module) => {
        expect(source).toMatch(new RegExp(
            `import\\s+\\{[^}]*\\b${symbol}\\b[^}]*\\}\\s+from\\s+'${module.replace(/\//g, '\\/')}'`))
    })

    it('declares the handler the export button routes to', () => {
        expect(source).toMatch(/async function exportFdaAddendum \(/)
    })

    // Its own export TYPE, not a toggle: the addendum is a different document that happens
    // to share the modal, and the BOM-shaping options do not apply to it.
    it('offers the addendum as a radio option beside the BOM formats', () => {
        expect(source).toContain('<n-radio-button value="FDA_ADDENDUM">')
        expect(source).not.toMatch(/n-switch[^>]*addendum/i)
    })

    // One handler, so the modal cannot end up with two spinners disagreeing about whether
    // an export is running.
    it('routes the addendum through the single export button', () => {
        expect(source).toMatch(/if \(mediaType === 'FDA_ADDENDUM'\) return exportFdaAddendum\(\)/)
    })

    // The refusal must reach the operator. A silent failure here means they believe they
    // hold a complete document.
    it('surfaces a refusal instead of downloading anything', () => {
        const fn = source.slice(source.indexOf('async function exportFdaAddendum'))
        const body = fn.slice(0, fn.indexOf('\nasync function', 1))
        expect(body).toMatch(/if \(!result\.ok\)/)
        expect(body.indexOf('if (!result.ok)')).toBeLessThan(body.indexOf('new Blob'))
    })

    it('releases the object URL it creates', () => {
        const fn = source.slice(source.indexOf('async function exportFdaAddendum'))
        const body = fn.slice(0, fn.indexOf('\nasync function', 1))
        expect(body).toContain('revokeObjectURL')
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
