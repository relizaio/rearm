import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { withoutHtmlComments } from '@/utils/specText'

/**
 * Wiring guard for the TEA output events (task TEA-13, design rows 10 to 14): the two option entries
 * and the hint line in both editors, the public warning on every component save and on a new
 * policy-wide TEA_PUBLISH (fresh read, fail closed), and the PUBLIC badge. The logic is pinned in
 * utils/teaOutputEvents.spec.ts; this checks the components use it.
 */
function source (file: string): string {
    return withoutHtmlComments(readFileSync(fileURLToPath(new URL(file, import.meta.url)), 'utf8'))
}

/** The text of a top-level function: from its declaration to the next top-level declaration. */
function body (src: string, decl: string): string {
    const start = src.indexOf(decl)
    expect(start, decl).toBeGreaterThanOrEqual(0)
    const rest = src.slice(start + decl.length)
    const end = rest.search(/\n(async function |function |const |let |\/\*\*)/)
    return end < 0 ? rest : rest.slice(0, end)
}

/** Every needle occurs in `text`, in this order. */
function expectInOrder (text: string, needles: string[]) {
    let at = -1
    for (const n of needles) {
        const next = text.indexOf(n, at + 1)
        expect(next, n).toBeGreaterThan(at)
        at = next
    }
}

const count = (text: string, needle: string) => text.split(needle).length - 1

const HINT = 'the effective TEA profile at firing time decides what is published or hidden'

describe('ComponentView: the Actions tab', () => {
    const code = source('./ComponentView.vue')

    it('row 10: the select takes the edition-filtered options and both type renders the shared label', () => {
        expect(code).toContain(':options="outputTriggerTypeOptionsForSelect"')
        expect(code).not.toContain(':options="outputTriggerTypeOptions"')
        expect(code).toContain('outputEventTypeOptionsForEdition(outputTriggerTypeOptions, myUser.installationType)')
        expect(count(code, 'outputEventTypeLabel(outputTriggerTypeOptions, row.type)')).toBe(2)
        expect(code).not.toContain('outputTriggerTypeOptions.find(')
    })

    it('row 11: the one hint item under the select and no other form item on the two types', () => {
        expect(count(code, HINT)).toBe(1)
        expect(code).toContain(`<n-form-item v-if="outputTrigger.type === 'TEA_PUBLISH' || outputTrigger.type === 'TEA_HIDE'" label="Settings">`)
        const items = code.match(/<n-form-item[^>]*(=== 'TEA_PUBLISH'|=== 'TEA_HIDE')[^>]*>/g) ?? []
        expect(items.length).toBe(1)
    })

    it('row 12: the gate sits in save(), before the only save call, and fails closed', () => {
        const save = body(code, 'async function save (): Promise<boolean> {')
        expectInOrder(save, ['confirmTeaPublishWiring()', 'return false', 'submitComponentSave(', 'return true'])
        // declaration, the call in save, the revision-refusal retry: no caller bypasses save()
        expect(code.match(/submitComponentSave\s*\(/g)?.length).toBe(3)
        expect(code).toContain('revisionRefused(err, (rev) => submitComponentSave(rev))')
        // the settings modal Save and the environment-to-branch select both reach save()
        expect(count(code, '@click="save"')).toBe(1)
        const settingsSave = code.split('\n').find(l => l.includes('@click="save"'))!
        expect(settingsSave).toContain('data-testid="component-settings-save"')
        expect(count(code, '; save()}')).toBe(1)

        const confirm = body(code, 'async function confirmTeaPublishWiring (): Promise<boolean> {')
        expectInOrder(confirm, ['teaProfilesAvailable(', 'teaPublishWiringAdded(', 'graphqlQueries.TeaProfileEditorViewGql',
            "fetchPolicy: 'no-cache'", 'catch', 'return false', 'teaConfirmBeforeAutoPublish(', 'Swal.fire('])
        // a failed read returns before any save: the catch block ends in return false, ahead of the builder
        const catchBlock = confirm.slice(confirm.indexOf('catch'), confirm.indexOf('teaConfirmBeforeAutoPublish('))
        expect(catchBlock).toContain('return false')
        expect(confirm).not.toContain('submitComponentSave')

        const triggers = body(code, 'async function saveTriggers() {')
        expectInOrder(triggers, ['const sent = await save()', 'if (sent)', 'componentData.value =', 'loadTeaBanner()'])
        expect(code).toContain(`<n-tab-pane name="outputTriggers" tab="Actions" v-if="myUser.installationType !== 'OSS' && !isDocument">`)
    })

    it('row 14: the PUBLIC badge on both tables and on the policy rule header row', () => {
        expect(count(code, 'tea-public-badge')).toBe(3)
        expect(count(code, "'data-testid': 'tea-public-badge'")).toBe(2)
        expect(count(code, 'data-testid="tea-public-badge"')).toBe(1)
        const header = code.split('\n').find(l => l.includes('data-testid="tea-public-badge"'))!
        expect(header).toContain('teaPolicyRuleBadgeTitle(gie.uuid)')
        expect(header).toContain('type="error"')
        // the header badge follows the staged wiring as the engine selects it
        expect(code).toContain('teaPublishWiring(updatedComponent.value,')
        // the view loadTeaBanner reads is kept whole for the badge
        expect(body(code, 'async function loadTeaBanner () {')).toContain('teaView.value = resp.data.teaProfileEditorView')
    })
})

describe('OrgSettings: Policy-Wide Actions', () => {
    const code = source('./OrgSettings.vue')

    it('row 10: the select takes the edition-filtered options and the type render the shared label', () => {
        expect(code).toContain('<n-select v-model:value="globalOutputEvent.type" required :options="outputTriggerTypeOptionsForSelect" />')
        expect(code).toContain('outputEventTypeOptionsForEdition(outputTriggerTypeOptions, myUser.value?.installationType)')
        expect(count(code, 'outputEventTypeLabel(outputTriggerTypeOptions, row.type)')).toBe(1)
        expect(code).not.toContain('outputTriggerTypeOptions.find(')
    })

    it('row 11: the one hint item and no other form item on the two types', () => {
        expect(count(code, HINT)).toBe(1)
        const items = code.match(/<n-form-item[^>]*(=== 'TEA_PUBLISH'|=== 'TEA_HIDE')[^>]*>/g) ?? []
        expect(items.length).toBe(1)
        expect(items[0]).toContain("globalOutputEvent.type === 'TEA_PUBLISH' || globalOutputEvent.type === 'TEA_HIDE'")
    })

    it('row 13: a new policy-wide TEA_PUBLISH is confirmed on a fresh read before it is saved', () => {
        const add = body(code, 'async function addGlobalOutputEvent () {')
        expectInOrder(add, ['validateOutputTrigger(', "eventToPush.type === 'TEA_PUBLISH'", 'loadTeaOrgExposure()',
            'return', 'teaOrgPublicSources(', 'Swal.fire(', 'globalOutputEvents.value.push', 'saveGlobalOutputEvents()'])
        const load = body(code, 'async function loadTeaOrgExposure (): Promise<TeaOrgExposureRead | null> {')
        expectInOrder(load, ['teaProfilesAvailable(', 'isWritable', 'graphqlQueries.TeaProfileEditorViewGql', "scope: 'ORGANIZATION'",
            'graphqlQueries.TeaProfilesOfOrgGql', 'teaOrgPublicSources('])
        expect(load).toContain("fetchPolicy: 'no-cache'")
        // the badge follows a policy select and every saved list
        expect(body(code, 'function selectPolicyForGlobalEvents(')).toContain('loadTeaOrgExposure()')
        expect(body(code, 'async function saveGlobalOutputEvents () {')).toContain('loadTeaOrgExposure()')
        expect(code).toContain(`<n-tab-pane name="policies" tab="Policies" v-if="myUser.installationType !== 'OSS'">`)
    })

    it('row 14: the PUBLIC badge on the policy table render', () => {
        expect(count(code, 'tea-public-badge')).toBe(1)
        expect(count(code, "'data-testid': 'tea-public-badge'")).toBe(1)
        expect(code).toContain('teaOrgPublicBadgeTitle(teaOrgSources.value)')
    })
})
