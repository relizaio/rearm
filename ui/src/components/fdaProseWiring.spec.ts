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


/**
 * The function body, sliced by BRACE DEPTH.
 *
 * Cutting at the next `\nasync function` is the idiom this family retired twice: it silently
 * yields the wrong text -- or an empty string -- the moment a helper is inserted between two
 * functions.
 */
function functionBody (name: string): string {
    // Both spacings: this file writes `saveOrgSettings()` and `loadOrgSettings()` without a
    // space, and other functions with one. Throwing on a miss rather than returning '' is the
    // point -- a rename must fail loudly, not silently assert against an empty string.
    const start = [`function ${name} (`, `function ${name}(`]
        .map(sig => orgSettings.indexOf(sig)).filter(i => i >= 0).sort((a, b) => a - b)[0]
    if (start === undefined) throw new Error(`no function ${name} in OrgSettings.vue`)
    let depth = 0
    let i = orgSettings.indexOf('{', start)
    const open = i
    for (; i < orgSettings.length; i++) {
        if (orgSettings[i] === '{') depth++
        else if (orgSettings[i] === '}' && --depth === 0) break
    }
    return orgSettings.slice(open, i + 1)
}

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

describe('the export injection toggle is wired into OrgSettings', () => {
    it('declares the switch state and its baseline', () => {
        expect(orgSettings).toMatch(/const supportInjectionEnabled\b/)
        expect(orgSettings).toMatch(/const supportInjectionBaseline\b/)
    })

    it('binds a switch, disabled while saving', () => {
        const tag = orgSettings.match(/<n-switch v-model:value="supportInjectionEnabled"[\s\S]*?\/>/)
        expect(tag).not.toBeNull()
        expect(tag![0]).toContain(':disabled="savingOrgSettings"')
    })

    // A Boolean in the form, the ENUM on the wire. The schema uses an enum so the field can
    // grow a third state without breaking published clients, so the form must never send a
    // bare true/false.
    it('maps the switch to the enum, never to a boolean', () => {
        expect(orgSettings).toMatch(/supportInjection: supportInjectionEnabled\.value \? 'ENABLED' : 'DISABLED'/)
        expect(orgSettings).not.toMatch(/supportInjection: supportInjectionEnabled\.value\s*[,}]/)
    })

    // PATCH: an unchanged toggle has no business in the mutation, exactly as for the prose.
    it('sends the field only when it changed', () => {
        expect(orgSettings).toMatch(/supportInjectionEnabled\.value !== supportInjectionBaseline\.value/)
    })

    // Only ENABLED is on. DISABLED, null, unset, or a value this build does not know all read
    // as off -- which is the server's own default rule (D3).
    //
    // The rule itself moved to utils/orgSettingsCommit.ts when the store round trip had to
    // become testable, so this asserts the component DELEGATES to it rather than re-scanning
    // for the literal. Scanning here for `supportInjection === 'ENABLED'` would now pass on a
    // component that had quietly reimplemented the comparison inline and drifted from the
    // version the unit tests actually cover.
    it('seeds the toggle through the shared rule', () => {
        expect(orgSettings).toMatch(/supportInjectionEnabled\.value = supportInjectionFromSettings\(/)
        expect(orgSettings).toMatch(/from '@\/utils\/orgSettingsCommit'/)
    })

    // The store commit must carry the accepted value, or the next hydration reads the gap as
    // OFF while the backend holds ENABLED. Behaviour is covered in orgSettingsCommit.spec.ts;
    // this pins that the component actually routes its commit through it.
    it('commits the organization through organizationToCommit', () => {
        expect(orgSettings).toMatch(
            /store\.commit\('UPDATE_ORGANIZATION', organizationToCommit\(/)
    })

    // Same rule the prose baseline follows: from the mutation response, before anything that
    // can throw, so a failed re-read cannot leave the form claiming an unsaved state.
    // PRESENCE FIRST, then ordering. The ordering assertion alone passed when the line was
    // ABSENT, because indexOf returns -1 and -1 is less than every real index -- so deleting
    // the baseline refresh entirely left this file green. A spec that reads as protection and
    // checks nothing is worse than no spec.
    it('advances the baseline before the follow-up re-read', () => {
        const body = functionBody('saveOrgSettings')
        expect(body).toContain('supportInjectionBaseline.value = supportInjectionEnabled.value')
        expect(body.indexOf('supportInjectionBaseline.value = supportInjectionEnabled.value'))
            .toBeLessThan(body.indexOf('await loadOrgSettings()'))
    })

    // The field must NOT be selected back from the mutation: adding it to the response
    // selection makes the whole document invalid on a backend without it, so every settings
    // save fails -- prose slots and sid PURL included.
    it('does not select supportInjection in the mutation response', () => {
        const mutation = orgSettings.slice(orgSettings.indexOf('updateOrganizationSettings'))
        const doc = mutation.slice(0, mutation.indexOf('`,'))
        expect(doc).not.toMatch(/^\s+supportInjection$/m)
    })

    // And it is never SENT to a backend that cannot store it.
    it('sends the field only when the backend supports it', () => {
        expect(orgSettings).toMatch(/supportInjectionSupported\.value\s*\n?\s*&&/)
        expect(orgSettings).toMatch(/v-if="supportInjectionSupported"/)
    })

    // The store's FULL document carries it; CORE deliberately does not, so a backend without
    // the field still answers. supportInjectionSchemaDrift.spec.ts validates both documents
    // against both schemas, which is the assertion that actually protects this.
    it('is carried by the store FULL document only', () => {
        const q = readFileSync(
            fileURLToPath(new URL('../utils/organizationsQuery.ts', import.meta.url)), 'utf8')
        expect(q).toContain('supportInjection')
        expect(q).toContain('ORGANIZATIONS_CORE')
        expect(q).toContain('ORGANIZATIONS_FULL')
    })
})
