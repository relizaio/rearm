import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'

/**
 * Wiring of the Org Settings perspective members dialog (TPF-41).
 *
 * OrgSettings.vue is never mounted in a spec, so the two Add handlers are pinned at source level:
 * both call addPerspectiveToComponent from utils/perspectiveMembership (whose spec pins the document
 * and variables sent) and keep their notice, error handling and members reload. The component page
 * keeps its whole-set save through setPerspectivesOnComponent on purpose.
 */
const orgSettings = readFileSync(
    fileURLToPath(new URL('./OrgSettings.vue', import.meta.url)), 'utf8')
const componentView = readFileSync(
    fileURLToPath(new URL('./ComponentView.vue', import.meta.url)), 'utf8')

/** The function body sliced by brace depth; throws on a missing function, never returns ''. */
function functionBody (name: string): string {
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

const REPLACE = /setPerspectivesOnComponent|perspectiveUuids/

describe('the perspective members dialog adds one membership', () => {
    it('imports the add from utils/perspectiveMembership', () => {
        expect(orgSettings).toContain("import { addPerspectiveToComponent } from '@/utils/perspectiveMembership'")
    })

    it.each([
        ['addComponentToPerspective', 'selectedComponentToAdd', 'Component added to perspective successfully', 'showAddComponentToPerspectiveModal'],
        ['addProductToPerspective', 'selectedProductToAdd', 'Product added to perspective successfully', 'showAddProductToPerspectiveModal']
    ])('%s sends the add and keeps its notice, error handling and reload', (fn, selected, notice, modal) => {
        const body = functionBody(fn)
        for (const expected of [
            `await addPerspectiveToComponent(graphqlClient, ${selected}.value, selectedPerspectiveUuid.value)`,
            `'${notice}'`,
            `${modal}.value = false`,
            `${selected}.value = ''`,
            'await showPerspectiveComponentsModalFn(selectedPerspectiveUuid.value, selectedPerspectiveName.value, selectedPerspectiveType.value)',
            'commonFunctions.parseGraphQLError(error.toString())',
            'processingMode.value = false'
        ]) expect(body).toContain(expected)
        expect(body).not.toMatch(REPLACE)
        expect(body).not.toMatch(/gql`/)
    })

    it('leaves no replacing call in OrgSettings, and the component page keeps its whole-set save', () => {
        expect(orgSettings).not.toMatch(/setPerspectivesOnComponent/)
        const pageSave = componentView.match(
            /mutation setPerspectivesOnComponent\(\$componentUuid: ID!, \$perspectiveUuids: \[ID!\]!\)/g) ?? []
        expect(pageSave).toHaveLength(1)
    })
})
