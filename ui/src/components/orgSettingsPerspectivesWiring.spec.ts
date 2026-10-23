import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { parse as parseSfc } from '@vue/compiler-sfc'

// NodeTypes of @vue/compiler-core, which compiler-sfc does not re-export.
const ELEMENT = 1
const ATTRIBUTE = 6
const DIRECTIVE = 7

/**
 * TPF-84: how OrgSettings wires the UUID column into the Perspectives table. OrgSettings is never
 * mounted in tests, so the script is read and the template parsed and walked (the
 * orgSettingsLicenseWiring.spec.ts pattern); the column itself runs in utils/uuidCopyColumn.spec.ts.
 */
const source = readFileSync(fileURLToPath(new URL('./OrgSettings.vue', import.meta.url)), 'utf8')
const code = source
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1')
const ast: any = parseSfc(source).descriptor.template?.ast

/** Every element the predicate accepts, with its ancestors (root first). */
function find (pred: (n: any) => boolean, root: any = ast): any[][] {
    const out: any[][] = []
    const walk = (node: any, ancestors: any[]): void => {
        if (node.type === ELEMENT && pred(node)) out.push([...ancestors, node])
        for (const child of node.children || []) walk(child, node.type === ELEMENT ? [...ancestors, node] : ancestors)
    }
    walk(root, [])
    return out
}
const directive = (n: any, name: string, arg?: string): string | undefined => (n.props || []).find((p: any) =>
    p.type === DIRECTIVE && p.name === name && (arg === undefined || p.arg?.content === arg))?.exp?.content
const attribute = (n: any, name: string): string | undefined => (n.props || []).find((p: any) =>
    p.type === ATTRIBUTE && p.name === name)?.value?.content
const last = (path: any[]): any => path[path.length - 1]

/** The gql template literal holding `marker`, found by operation name rather than by line. */
function gqlDocument (marker: string): string {
    const at = source.indexOf(marker)
    if (at < 0) return ''
    const start = source.lastIndexOf('gql`', at)
    const end = source.indexOf('`', at)
    return source.slice(start, end + 1)
}

/** The perspectiveFields array: from its opening line to the first later line that is exactly `]`. */
function perspectiveFieldsSlice (): string {
    const opening = 'const perspectiveFields = ['
    const at = code.indexOf(opening)
    if (at < 0) return ''
    const close = code.slice(at).search(/\n\]\n/)
    return close < 0 ? '' : code.slice(at, at + close + 2)
}

describe('OrgSettings: the Perspectives UUID column', () => {
    it('is the second column, after the name and before Created Date, with Actions still last', () => {
        const fields = perspectiveFieldsSlice()
        expect(fields).not.toBe('')
        expect(fields.split('perspectiveUuidColumn')).toHaveLength(2)
        const name = fields.indexOf("key: 'name'")
        const uuid = fields.indexOf('perspectiveUuidColumn')
        const created = fields.indexOf("key: 'createdDate'")
        const actions = fields.indexOf("key: 'actions'")
        expect(name).toBeGreaterThan(-1)
        expect(uuid).toBeGreaterThan(name)
        expect(created).toBeGreaterThan(uuid)
        expect(actions).toBeGreaterThan(created)
    })

    it('is built by uuidCopyColumn with the Perspective UUID message, its testid and the page notify', () => {
        const opening = 'const perspectiveUuidColumn = uuidCopyColumn({'
        const at = code.indexOf(opening)
        expect(at).toBeGreaterThan(-1)
        const statement = code.slice(at, code.indexOf('})', at) + 2)
        expect(statement).toContain("what: 'Perspective UUID'")
        expect(statement).toContain("testid: 'perspective-uuid'")
        expect(statement).toMatch(/\bnotify\b/)
        const imports = code.match(/import \{[^}]*\buuidCopyColumn\b[^}]*\} from '(\.\.|@)\/utils\/uuidCopyColumn'/g) || []
        expect(imports).toHaveLength(1)
    })

    it('stays in the Pro-only Perspectives pane, bound to perspectiveFields and perspectives', () => {
        const panes = find((n) => n.tag === 'n-tab-pane' && attribute(n, 'name') === 'perspectives')
        expect(panes).toHaveLength(1)
        const pane = last(panes[0])
        expect(directive(pane, 'if')).toBe("myUser.installationType !== 'OSS'")
        // The pane holds a second n-data-table (the components modal), so count by the binding.
        const tables = find((n) => n.tag === 'n-data-table' && directive(n, 'bind', 'columns') === 'perspectiveFields', pane)
        expect(tables).toHaveLength(1)
        expect(directive(last(tables[0]), 'bind', 'data')).toBe('perspectives')
    })

    it('reads the uuid in the perspectives query', () => {
        const doc = gqlDocument('query perspectives')
        expect(doc).not.toBe('')
        expect(doc).toMatch(/\buuid\b/)
    })
})
