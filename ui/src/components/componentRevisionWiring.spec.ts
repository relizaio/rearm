import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'

/**
 * The revision handling is actually WIRED into the component settings page (TPF-62).
 *
 * Source-level, like deviceWindowPanelWiring.spec.ts: this repo has no vue-tsc and the page is
 * too large to mount, so a renamed ref or a missing handler builds clean and fails only in a
 * browser. These pin the bindings whose absence would make the check silently do nothing.
 */
const source = readFileSync(fileURLToPath(new URL('./ComponentView.vue', import.meta.url)), 'utf8')
const store = readFileSync(fileURLToPath(new URL('../store.ts', import.meta.url)), 'utf8')

/** The body of a function in the page, from its declaration to the next top-level declaration. */
function body (src: string, decl: string): string {
    const start = src.indexOf(decl)
    expect(start, decl).toBeGreaterThanOrEqual(0)
    const rest = src.slice(start + decl.length)
    const end = rest.search(/\n(async function |function |const |let )/)
    return end < 0 ? rest : rest.slice(0, end)
}

describe('the component settings page sends and keeps the revision', () => {
    it('shows the refusal panel inside the settings modal, above its tabs, with Force for writers only', () => {
        const modal = source.indexOf('v-model:show="showComponentSettingsModal"')
        const panel = source.indexOf('<ComponentRevisionConflict')
        const tabs = source.indexOf('<n-tabs', modal)
        expect(modal).toBeGreaterThanOrEqual(0)
        expect(panel).toBeGreaterThan(modal)
        expect(panel).toBeLessThan(tabs)
        const tag = source.slice(panel, source.indexOf('/>', panel))
        expect(tag).toMatch(/v-if="revisionConflict"/)
        expect(tag).toMatch(/:can-force="isWritable"/)
    })

    it('reads the form and its revision from one fresh read', () => {
        const init = body(source, 'async function initLoad()')
        expect(init).toMatch(/loadComponentForForm\(/)
        expect(init).toMatch(/revisionBaseline\(/)
        expect(init).toMatch(/updatedComponent\.value = commonFunctions\.deepCopy\(storeComponent\)/)
    })

    it('sends the revision on the form, device-class and window saves', () => {
        expect(body(source, 'async function save ()')).toMatch(/submitComponentSave\(formRevision\.value\)/)
        expect(body(source, 'async function submitDeviceClass (')).toMatch(/withExpectedRevision\(/)
        expect(body(source, 'async function submitDeviceWindow (')).toMatch(/withExpectedRevision\(/)
        const action = store.slice(store.indexOf('async updateComponent (context'), store.indexOf('async updateComponentResourceGroup ('))
        expect(action.length).toBeGreaterThan(0)
        expect(action).toMatch(/withExpectedRevision\(compUpdObject,/)
    })

    it('counts the page own writes of the component', () => {
        expect(body(source, 'async function savePerspectives()')).toMatch(/ownComponentWrite\(\)/)
        for (const child of ['<ComponentLocks', '<ActionGuards']) {
            const at = source.indexOf(child)
            expect(at, child).toBeGreaterThanOrEqual(0)
            expect(source.slice(at, source.indexOf('/>', at))).toMatch(/@component-written="ownComponentWrite"/)
        }
    })

    it('drops the refusal when the modal closes, and the Save button carries its test id', () => {
        expect(body(source, 'const handleComponentSettingsClose = async function(show: boolean)'))
            .toMatch(/revisionConflict\.value = null/)
        expect(source).toMatch(/<n-button type="success" data-testid="component-settings-save" @click="save">/)
    })
})
