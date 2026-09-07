import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { FDA_PROSE_FIELDS } from '@/utils/fdaProseInput'

/**
 * Import and wiring assertions for the FDA prose form and the device support window.
 *
 * Same reason as releaseViewBulkWiring.spec.ts: no vue-tsc, so an undefined identifier in
 * <script setup> is a RUNTIME error the build ignores. That is not hypothetical here --
 * extracting proseDiff into utils/ deleted the `proseBaseline` declaration along with it,
 * and the build passed, 427 unit tests passed, and every prose save threw
 * "proseBaseline is not defined" in the browser. Only the live probe caught it.
 *
 * eslint does not flag it either (no-undef is off for <script setup>), so a source-text
 * assertion is the cheapest guard that runs in the suite.
 */
const orgSettings = readFileSync(
    fileURLToPath(new URL('./OrgSettings.vue', import.meta.url)), 'utf8')
const releaseView = readFileSync(
    fileURLToPath(new URL('./ReleaseView.vue', import.meta.url)), 'utf8')

describe('the FDA prose form is wired into OrgSettings', () => {
    it.each([
        ['FDA_PROSE_FIELDS'], ['FDA_PROSE_MAX_LENGTH'], ['proseDiff'], ['proseBaselineFrom']
    ])('imports %s from utils/fdaProseInput', (symbol) => {
        expect(orgSettings).toMatch(new RegExp(
            `import\\s+\\{[^}]*\\b${symbol}\\b[^}]*\\}\\s+from\\s+'@\\/utils\\/fdaProseInput'`))
    })

    // The declaration whose loss the header describes. Referenced only from the script, so
    // no template-wiring assertion would have caught it.
    it('declares proseBaseline, which proseDiff is called with', () => {
        expect(orgSettings).toMatch(/const proseBaseline\s*:/)
        expect(orgSettings).toMatch(/proseDiff\(orgSettings,\s*proseBaseline\)/)
    })

    it.each([...FDA_PROSE_FIELDS])('binds %s to an input', (field) => {
        expect(orgSettings).toContain(`v-model:value="orgSettings.${field}"`)
    })

    // Every prose box must be capped and frozen during a save. Uncapped, a paste over the
    // server's limit fails the WHOLE settings mutation and takes unrelated edits with it;
    // editable during a save, post-click keystrokes are overwritten by the refreshed
    // baseline under a green success toast.
    it('caps and disables all four inputs, none forgotten', () => {
        const bound = orgSettings.match(/v-model:value="orgSettings\.fda\w+"[\s\S]*?\/>/g) || []
        expect(bound).toHaveLength(FDA_PROSE_FIELDS.length)
        for (const tag of bound) {
            expect(tag).toContain(':maxlength="FDA_PROSE_MAX_LENGTH"')
            expect(tag).toContain(':disabled="savingOrgSettings"')
        }
    })

    // REGRESSION, Layer 1 HIGH: the baseline must come from the mutation's own response.
    // Refreshing it only via the re-read nested in the try meant a failed re-read reported a
    // COMMITTED save as failed AND left the baseline stale, so the operator's next clear was
    // silently omitted while the server kept the text.
    it('refreshes the baseline from the mutation response, not only from a re-read', () => {
        const save = orgSettings.slice(orgSettings.indexOf('async function saveOrgSettings'))
        const body = save.slice(0, save.indexOf('\nasync function', 1))
        expect(body).toMatch(/proseBaselineFrom\(/)
        expect(body.indexOf('proseBaselineFrom(')).toBeLessThan(body.indexOf('await loadOrgSettings()'))
    })
})

describe('the device support window is wired into ReleaseView', () => {
    it.each([
        ['deviceWindowVariables', '@/utils/deviceSupportWindowInput'],
        ['formatSupportWindow', '@/utils/supportWindowDisplay']
    ])('imports %s from %s', (symbol, module) => {
        expect(releaseView).toMatch(new RegExp(
            `import\\s+\\{[^}]*\\b${symbol}\\b[^}]*\\}\\s+from\\s+'${module.replace(/\//g, '\\/')}'`))
    })

    it.each([
        ['deviceWindow'], ['deviceWindowBaseline'], ['savingDeviceWindow'],
        ['deviceWindowError'], ['deviceWindowDirty'], ['saveDeviceWindow'], ['seedDeviceWindow']
    ])('declares %s, which the template references', (symbol) => {
        expect(releaseView).toMatch(new RegExp(`(const|function) ${symbol}\\b`))
    })

    // The mutation response is the baseline source here too, and fetchRelease -- which can
    // throw -- must not sit between the write and the baseline refresh.
    it('seeds the baseline from the mutation response before refetching', () => {
        const save = releaseView.slice(releaseView.indexOf('async function saveDeviceWindow'))
        const body = save.slice(0, save.indexOf('\nfunction ', 1))
        expect(body).toMatch(/resp\?\.data/)
        expect(body.indexOf('deviceWindowBaseline.eos =')).toBeLessThan(body.indexOf('await fetchRelease()'))
    })
})
