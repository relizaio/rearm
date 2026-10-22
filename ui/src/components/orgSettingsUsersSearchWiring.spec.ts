import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { parse as parseSfc } from '@vue/compiler-sfc'

// NodeTypes of @vue/compiler-core, which compiler-sfc does not re-export.
const ELEMENT = 1
const ATTRIBUTE = 6
const DIRECTIVE = 7

/**
 * TPF-85 design 4.5, rows 14-17: how OrgSettings wires the Users tab search box and the UUID
 * columns. OrgSettings is never mounted in tests, so the script is read and the template parsed
 * and walked (the orgSettingsLicenseWiring.spec.ts pattern); the filter runs in
 * utils/orgUsersSearch.spec.ts and the columns in utils/uuidCopyColumn.users.spec.ts.
 */
const source = readFileSync(fileURLToPath(new URL('./OrgSettings.vue', import.meta.url)), 'utf8')
const code = source
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1')
const store = readFileSync(fileURLToPath(new URL('../store.ts', import.meta.url)), 'utf8')
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
const hasSlot = (n: any, slot: string): boolean => (n.props || []).some((p: any) =>
    p.type === DIRECTIVE && p.name === 'slot' && p.arg?.content === slot)
const attribute = (n: any, name: string): string | undefined => (n.props || []).find((p: any) =>
    p.type === ATTRIBUTE && p.name === name)?.value?.content
const elementChildren = (n: any): any[] => (n.children || []).filter((c: any) => c.type === ELEMENT)
const last = (path: any[]): any => path[path.length - 1]
const byTestId = (id: string, root: any = ast) => find((n) => attribute(n, 'data-testid') === id, root)

/** The source of one top-level script statement, from its opening line to the next blank line. */
const definition = (opening: string): string => {
    const at = code.indexOf(opening)
    if (at < 0) return ''
    const end = code.indexOf('\n\n', at)
    return code.slice(at, end < 0 ? undefined : end)
}
/** code from the opening marker to (not including) the closing marker. */
const between = (opening: string, closing: string): string => {
    const at = code.indexOf(opening)
    if (at < 0) return ''
    const end = code.indexOf(closing, at + opening.length)
    return end < 0 ? '' : code.slice(at, end)
}
/** The userFields array: from its opening line to the first later line that is exactly `]`. */
function userFieldsSlice (): string {
    const start = code.indexOf('const userFields = [')
    if (start < 0) return ''
    const end = code.indexOf('\n]\n', start)
    return end < 0 ? '' : code.slice(start, end + 2)
}
const occurrences = (text: string, needle: string): number => text.split(needle).length - 1

const usersPanes = find((n) => n.tag === 'n-tab-pane' && attribute(n, 'name') === 'users')
const usersPane = usersPanes.length ? last(usersPanes[0]) : null
const paneTables = usersPane ? find((n) => n.tag === 'n-data-table', usersPane).map(last) : []

describe('OrgSettings: the Users tab search box', () => {
    it('row 14: is one n-input in the users pane bound to usersSearch, before the active users table', () => {
        expect(usersPanes).toHaveLength(1)
        const box = byTestId('users-search')
        expect(box).toHaveLength(1)
        const node = last(box[0])
        expect(node.tag).toBe('n-input')
        expect(box[0]).toContain(usersPane)
        expect(directive(node, 'model', 'value')).toBe('usersSearch')
        expect(attribute(node, 'placeholder')).toBe('Search by uuid, name or email')
        const active = paneTables.find((t) => directive(t, 'bind', 'columns') === 'userFields')
        expect(active).toBeTruthy()
        expect(node.loc.start.offset).toBeLessThan(active.loc.start.offset)
        const heading = find((n) => n.tag === 'h5', usersPane).map(last)
            .find((h: any) => source.slice(h.loc.start.offset, h.loc.end.offset).includes('Users ({{ activeUsers.length }})'))
        expect(heading).toBeTruthy()
        expect(node.loc.start.offset).toBeLessThan(heading.loc.start.offset)
    })
})

describe('OrgSettings: the users tables', () => {
    it('row 15: the two user tables show the filtered lists with a search empty state; invitees stay unfiltered', () => {
        expect(paneTables).toHaveLength(3)
        const bindings = paneTables.map((t) => [directive(t, 'bind', 'columns'), directive(t, 'bind', 'data')])
        expect(bindings).toEqual([
            ['userFields', 'shownActiveUsers'],
            ['inviteeFields', 'invitees'],
            ['inactiveUserFields', 'shownInactiveUsers']
        ])
        for (const table of [paneTables[0], paneTables[2]]) {
            const slots = elementChildren(table).filter((c) => c.tag === 'template' && hasSlot(c, 'empty'))
            expect(slots).toHaveLength(1)
            const empties = find((n) => n.tag === 'n-empty', slots[0]).map(last)
            expect(empties).toHaveLength(1)
            expect(attribute(empties[0], 'data-testid')).toBe('users-empty')
            expect(directive(empties[0], 'bind', 'description')).toBe('usersEmptyText')
        }
        expect(elementChildren(paneTables[1]).some((c) => c.tag === 'template' && hasSlot(c, 'empty'))).toBe(false)
    })
})

describe('OrgSettings: the users search script', () => {
    it('row 16: the computeds, the two UUID columns and the imports', () => {
        expect(definition('const usersSearch')).not.toBe('')
        for (const name of ['const shownActiveUsers', 'const shownInactiveUsers']) {
            const def = definition(name)
            expect(def).not.toBe('')
            expect(def).toContain('filterUsersBySearch(')
            expect(def).toContain('usersSearch.value')
        }
        const empty = definition('const usersEmptyText')
        expect(empty).toContain('usersSearch.value.trim()')
        expect(empty).toContain('No Data')

        expect(code).toContain("const userUuidColumn = uuidCopyColumn({ what: 'User UUID', testid: 'user-uuid', notify })")
        expect(code).toContain("const inactiveUserUuidColumn = uuidCopyColumn({ what: 'User UUID', testid: 'inactive-user-uuid', notify })")

        const fields = userFieldsSlice()
        expect(fields).not.toBe('')
        expect(occurrences(fields, 'userUuidColumn,')).toBe(1)
        const name = fields.indexOf("key: 'name'")
        const uuid = fields.indexOf('userUuidColumn,')
        const permission = fields.indexOf("key: 'permission'")
        expect(name).toBeGreaterThan(-1)
        expect(uuid).toBeGreaterThan(name)
        expect(permission).toBeGreaterThan(uuid)
        expect(fields).not.toContain('inactiveUserUuidColumn')
        expect(fields).not.toContain('const userUuidColumn')

        const inactive = between('const inactiveUserFields = [', 'const invitees')
        expect(inactive).not.toBe('')
        expect(occurrences(inactive, 'inactiveUserUuidColumn,')).toBe(1)
        const iName = inactive.indexOf("key: 'name'")
        const iUuid = inactive.indexOf('inactiveUserUuidColumn,')
        const iControls = inactive.indexOf("key: 'controls'")
        expect(iName).toBeGreaterThan(-1)
        expect(iUuid).toBeGreaterThan(iName)
        expect(iControls).toBeGreaterThan(iUuid)

        const invitees = between('const inviteeFields = [', '\n\n')
        expect(invitees).not.toBe('')
        expect(invitees).not.toContain('userUuidColumn')
        expect(invitees).not.toContain('inactiveUserUuidColumn')
        expect(invitees).not.toContain('uuidCopyColumn')

        expect(code).toMatch(/import \{ uuidCopyColumn \} from '(\.\.|@)\/utils\/uuidCopyColumn'/)
        expect(code).not.toMatch(/import \{[^}]*\bcopyToClipboard\b[^}]*\} from/)
        expect(code).toMatch(/import \{[^}]*\bfilterUsersBySearch\b[^}]*\} from '(\.\.|@)\/utils\/orgUsersSearch'/)
        expect(code).toMatch(/import \{[^}]*\bnormalizeSearchTerm\b[^}]*\} from '(\.\.|@)\/utils\/tableSearch'/)
    })
})

describe('OrgSettings: the rows carry what the filter reads', () => {
    it('row 17: FetchUsers selects uuid, name and email on users, and the pane renders no unfiltered list', () => {
        const at = store.indexOf('query FetchUsers')
        expect(at).toBeGreaterThan(-1)
        const doc = store.slice(store.lastIndexOf('gql`', at), store.indexOf('`', at))
        const usersAt = doc.indexOf('users(')
        expect(usersAt).toBeGreaterThan(-1)
        const open = doc.indexOf('{', usersAt)
        const nested = doc.indexOf('{', open + 1)
        const direct = doc.slice(open + 1, nested).split('\n').map((l) => l.trim())
        expect(direct).toContain('uuid')
        expect(direct).toContain('name')
        expect(direct).toContain('email')
        const unfiltered = find((n) => ['activeUsers', 'inactiveUsers'].includes(directive(n, 'bind', 'data') || ''), usersPane)
        expect(unfiltered).toHaveLength(0)
    })
})
