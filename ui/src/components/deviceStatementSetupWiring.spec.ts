import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'

/**
 * FDAW-81, architecture round 3 section 4 row 8 (REQ-76, REQ-77): the device support statement line is wired
 * into the Medical device tab, and the tab re-reads the setup status after a save or a removal in the
 * Regulatory pane. Source-level, like the other *Wiring.spec.ts files: no vue-tsc runs.
 */
const source = readFileSync(fileURLToPath(new URL('./ComponentView.vue', import.meta.url)), 'utf8')

function count (haystack: string, needle: string): number {
    return haystack.split(needle).length - 1
}

describe('the device support statement line on the Medical device tab (FDAW-81)', () => {
    it('sits after the defaults line and before the Save bar, with its props and Edit', () => {
        expect(source).toContain("import DeviceStatementSetupLine from './DeviceStatementSetupLine.vue'")
        expect(count(source, '<DeviceStatementSetupLine')).toBe(1)
        const at = source.indexOf('<DeviceStatementSetupLine')
        const tag = source.slice(at, source.indexOf('/>', at))
        expect(tag).toContain(':status="setupStatus"')
        expect(tag).toContain(':answer="fdaPremarketEdit"')
        expect(tag).toContain(':component-type="componentData?.type"')
        expect(tag).toContain('@edit="componentSettingsTab = \'regulatory\'"')
        expect(at).toBeGreaterThan(source.indexOf('data-testid="device-fda-defaults"'))
        expect(at).toBeLessThan(source.indexOf('class="deviceSettingsActions"'))
    })

    it('the Regulatory pane re-reads the setup status after a save or a removal', () => {
        const start = source.indexOf('<n-tab-pane name="regulatory"')
        expect(start).toBeGreaterThan(-1)
        const pane = source.slice(start, source.indexOf('</n-tab-pane>', start))
        expect(count(pane, '<PackageSettingsEditor')).toBe(1)
        expect(count(pane, '@saved="loadSetupStatus"')).toBe(1)
        expect(count(pane, '@removed="loadSetupStatus"')).toBe(1)
    })
})
