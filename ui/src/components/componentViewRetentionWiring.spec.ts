import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'

/**
 * FDAW-12 4.1 row 9 (REQ-314): the archive catches show the retention refusal as text, the Danger
 * Zone says when a component can be archived, and the organization archive no longer promises a
 * deletion. Read from the sources, as componentViewBulkArchiveWiring.spec.ts reads ComponentView.
 */
const read = (name: string): string => readFileSync(fileURLToPath(new URL(name, import.meta.url)), 'utf8')
const componentView = read('./ComponentView.vue')
const userProfile = read('./UserProfile.vue')

/** The text of the function declared as `const <name> = async function` or `async function <name>`. */
function functionText (source: string, name: string): string {
    const starts = [source.indexOf(`const ${name} = async function`), source.indexOf(`async function ${name}(`)]
        .filter(i => i >= 0)
    expect(starts.length).toBe(1)
    const open = source.indexOf('{', starts[0])
    let depth = 0
    for (let i = open; i < source.length; i++) {
        if (source[i] === '{') depth++
        if (source[i] === '}' && --depth === 0) return source.substring(open, i + 1)
    }
    throw new Error('unbalanced ' + name)
}

describe('the retention refusal in the archive dialogs', () => {
    for (const [file, source, fn] of [['ComponentView.vue', componentView, 'archiveComponent'],
        ['UserProfile.vue', userProfile, 'archiveOrganization']]) {
        it(`${file}: the catch passes retentionRefusal's value as text, and no html`, () => {
            const body = functionText(source, fn)
            expect(body).toMatch(/const retained = retentionRefusal\(err\)/)
            expect(body).toMatch(/titleText: 'Not archived', text: retained, icon: 'warning'/)
            expect(body).not.toMatch(/html\s*:/)
            expect(source).toMatch(/import \{ retentionRefusal \} from '\.\.\/utils\/packageRetention'/)
        })

        // A mutant that swaps the `!==`/`===` leaves both literal snippets above in the function body, so
        // that assertion alone cannot catch it; pin the condition guards the warning branch, not the error one.
        it(`${file}: the retained warning answers a non-null refusal, not a null one`, () => {
            const body = functionText(source, fn)
            expect(body).toMatch(/if \(retained !== null\) \{\s*Swal\.fire\(\{ titleText: 'Not archived', text: retained, icon: 'warning' \}\)\s*\} else \{/)
        })
    }

    it('the Danger Zone sentence is there once', () => {
        const flat = componentView.replace(/\s+/g, ' ')
        const sentence = 'A {{ words.component }} that holds package versions can be archived only after the zip of '
            + 'each version has been downloaded.'
        expect(flat.split(sentence).length - 1).toBe(1)
    })

    it('UserProfile no longer says the organization is permanently deleted', () => {
        expect(userProfile).not.toMatch(/permanently deleted/)
    })
})
