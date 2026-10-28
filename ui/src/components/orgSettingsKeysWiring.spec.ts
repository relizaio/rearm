import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { parse as parseSfc } from '@vue/compiler-sfc'

// NodeTypes of @vue/compiler-core, which compiler-sfc does not re-export.
const ELEMENT = 1
const ATTRIBUTE = 6
const DIRECTIVE = 7

/**
 * TPF-88 (design 4.3, rows 24-30): the search boxes, the filtered table data, the sortable and
 * UUID columns of the Programmatic Access key tables and of the Federated Identities table.
 * OrgSettings and FederatedTrustRulesPanel are never mounted in tests, so both SFCs are parsed
 * and walked (the orgSettingsLicenseWiring.spec.ts pattern); the helpers they call run in
 * apiKeyControls.spec.ts and tableSearch.spec.ts.
 */
function load (file: string) {
    const source = readFileSync(fileURLToPath(new URL(file, import.meta.url)), 'utf8')
    const code = source
        .replace(/<!--[\s\S]*?-->/g, '')
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .replace(/(^|[^:])\/\/.*$/gm, '$1')
    const ast: any = parseSfc(source).descriptor.template?.ast
    return { source, code, ast }
}
const org = load('./OrgSettings.vue')
const panel = load('./FederatedTrustRulesPanel.vue')

/** Every element the predicate accepts, with its ancestors (root first). */
function find (root: any, pred: (n: any) => boolean): any[][] {
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
const offset = (n: any): number => n.loc.start.offset
const isPane = (name: string) => (n: any) => n.tag === 'n-tab-pane' && attribute(n, 'name') === name

/** The source of one top-level script statement, from its opening to the next blank line. */
const definition = (code: string, opening: string): string => {
    const at = code.indexOf(opening)
    if (at < 0) return ''
    const end = code.indexOf('\n\n', at)
    return code.slice(at, end < 0 ? undefined : end)
}
/** The one source line starting with opening. */
const line = (code: string, opening: string): string => {
    const at = code.indexOf(opening)
    if (at < 0) return ''
    const end = code.indexOf('\n', at)
    return code.slice(at, end < 0 ? undefined : end)
}
/** A column array, from its declaration to its closing ]). */
const columnsSlice = (code: string, opening: string): string => {
    const at = code.indexOf(opening)
    if (at < 0) return ''
    const end = code.indexOf('\n])', at)
    return code.slice(at, end < 0 ? undefined : end + 3)
}
/** The gql template literal holding `marker`, found by operation name rather than by line. */
function gqlDocument (source: string, marker: string): string {
    const at = source.indexOf(marker)
    if (at < 0) return ''
    const start = source.lastIndexOf('gql`', at)
    const end = source.indexOf('`', at)
    return source.slice(start, end + 1)
}
/** The n-empty inside the table's #empty slot, if any. */
function emptySlot (table: any): any {
    const slot = (table.children || []).find((c: any) => c.type === ELEMENT && c.tag === 'template'
        && (c.props || []).some((p: any) => p.type === DIRECTIVE && p.name === 'slot' && p.arg?.content === 'empty'))
    return slot && (slot.children || []).find((c: any) => c.type === ELEMENT && c.tag === 'n-empty')
}

const programmaticPane = last(find(org.ast, isPane('programmaticAccess'))[0])

describe('TPF-88 OrgSettings: the key search boxes', () => {
    const boxes: [string, string, string][] = [
        ['keys-search-freeFormKeys', 'freeFormKeys', 'freeFormKeysSearch'],
        ['keys-search-userKeys', 'userKeys', 'userKeysSearch'],
        ['keys-search-scopedKeys', 'scopedKeys', 'scopedKeysSearch']
    ]
    it.each(boxes)('row 24: %s is one n-input in its sub-tab, bound to its term, before the first table', (testid, pane, model) => {
        const found = find(org.ast, (n) => attribute(n, 'data-testid') === testid)
        expect(found).toHaveLength(1)
        const path = found[0]
        const box = last(path)
        expect(box.tag).toBe('n-input')
        expect(path.some(isPane(pane))).toBe(true)
        expect(path.some(isPane('programmaticAccess'))).toBe(true)
        expect(directive(box, 'model', 'value')).toBe(model)
        const paneNode = path.find(isPane(pane))
        const firstTable = last(find(paneNode, (n) => n.tag === 'n-data-table')[0])
        expect(offset(box)).toBeLessThan(offset(firstTable))
    })
})

describe('TPF-88 OrgSettings: the key tables show the filtered lists', () => {
    const tables = find(programmaticPane, (n) => n.tag === 'n-data-table').map(last)
    const byColumns = (cols: string) => tables.filter((t) => directive(t, 'bind', 'columns') === cols)
    const expected: [string, string, string, string][] = [
        ['keyRequestFields', 'shownKeyRequests', 'keys-empty-keyRequests', 'keysEmptyText(freeFormKeysSearch)'],
        ['freeFormKeyFields', 'shownFreeFormKeys', 'keys-empty-freeFormKeys', 'keysEmptyText(freeFormKeysSearch)'],
        ['userKeyFields', 'shownUserKeys', 'keys-empty-userKeys', 'keysEmptyText(userKeysSearch)'],
        ['programmaticAccessFields', 'shownScopedKeys', 'keys-empty-scopedKeys', 'keysEmptyText(scopedKeysSearch)']
    ]
    it.each(expected)('row 25: %s binds %s and an empty slot %s', (cols, data, emptyId, description) => {
        const t = byColumns(cols)
        expect(t).toHaveLength(1)
        expect(directive(t[0], 'bind', 'data')).toBe(data)
        const empty = emptySlot(t[0])
        expect(empty).toBeTruthy()
        expect(attribute(empty, 'data-testid')).toBe(emptyId)
        expect(directive(empty, 'bind', 'description')).toBe(description)
    })
    it('row 25: no element of the pane binds an unfiltered list as table data; the picker and the gate stay unfiltered', () => {
        const unfiltered = ['computedKeyRequests', 'computedFreeFormKeys', 'computedUserKeys', 'computedProgrammaticAccessKeys']
        expect(find(programmaticPane, (n) => unfiltered.includes(directive(n, 'bind', 'data') || ''))).toHaveLength(0)
        expect(find(programmaticPane, (n) => directive(n, 'if') === 'computedKeyRequests.length')).toHaveLength(1)
        const fed = find(programmaticPane, (n) => n.tag === 'FederatedTrustRulesPanel').map(last)
        expect(fed).toHaveLength(1)
        expect(directive(fed[0], 'bind', 'free-form-keys')).toBe('computedFreeFormKeys')
        expect(directive(fed[0], 'bind', 'identities')).toBe('computedFederatedIdentities')
    })
    it('row 27: the four tables scroll wider by the added columns', () => {
        expect(byColumns('keyRequestFields').map((t) => directive(t, 'bind', 'scroll-x'))).toEqual(['1520'])
        // 1840 at TPF-88, plus the 160 of the TPF-92 Groups column
        expect(byColumns('freeFormKeyFields').map((t) => directive(t, 'bind', 'scroll-x'))).toEqual(['2000'])
        expect(byColumns('userKeyFields').map((t) => directive(t, 'bind', 'scroll-x'))).toEqual(['1730'])
        expect(byColumns('programmaticAccessFields').map((t) => directive(t, 'bind', 'scroll-x'))).toEqual(['1820'])
    })
})

describe('TPF-88 OrgSettings: script wiring', () => {
    it('row 26: each shown list filters its computed list by its own term', () => {
        expect(line(org.code, 'const shownKeyRequests')).toContain('filterApiKeysBySearch(computedKeyRequests.value, freeFormKeysSearch.value)')
        expect(line(org.code, 'const shownFreeFormKeys')).toContain('filterApiKeysBySearch(computedFreeFormKeys.value, freeFormKeysSearch.value)')
        expect(line(org.code, 'const shownUserKeys')).toContain('filterApiKeysBySearch(computedUserKeys.value, userKeysSearch.value)')
        expect(line(org.code, 'const shownScopedKeys')).toContain('filterApiKeysBySearch(computedProgrammaticAccessKeys.value, scopedKeysSearch.value)')
    })
    it('row 26: imports the shared helpers, drops the stacked column, keeps the raw access instant', () => {
        const imp = org.code.match(/import \{([^}]*)\} from '(?:\.\.|@)\/utils\/apiKeyControls'/)
        expect(imp).toBeTruthy()
        const names = imp![1].split(',').map((s) => s.trim())
        for (const n of ['filterApiKeysBySearch', 'apiKeyActivityColumns', 'apiKeyNotesColumn', 'apiKeySorters']) expect(names).toContain(n)
        expect(org.code).not.toContain('apiKeyActivityColumn(')
        expect(org.code).toMatch(/import \{[^}]*\bnormalizeSearchTerm\b[^}]*\} from '(?:\.\.|@)\/utils\/tableSearch'/)
        expect(definition(org.code, 'function formatValuesForApiKeys')).toContain('accessDateIso')
    })
    it('row 27: each column list has the UUID lead column, the notes builder and its sorters', () => {
        const lists: [string, string, string][] = [
            ['const programmaticAccessFields', 'scoped', 'scopedKeys-key-uuid'],
            ['const freeFormKeyFields', 'freeForm', 'freeFormKeys-key-uuid'],
            ['const userKeyFields', 'user', 'userKeys-key-uuid'],
            ['const keyRequestFields', 'keyRequests', 'keyRequests-key-uuid']
        ]
        for (const [opening, kind, testid] of lists) {
            const slice = columnsSlice(org.code, opening)
            expect(slice, opening).toContain(`apiKeyLeadColumns('${kind}', () => programmaticAccessKeys.value, { testid: '${testid}', notify })`)
            expect(slice, opening).toContain('apiKeyNotesColumn(')
        }
        const scoped = columnsSlice(org.code, 'const programmaticAccessFields')
        const freeForm = columnsSlice(org.code, 'const freeFormKeyFields')
        const user = columnsSlice(org.code, 'const userKeyFields')
        const requests = columnsSlice(org.code, 'const keyRequestFields')
        expect(scoped).toContain('sorter: apiKeySorters.object')
        expect(freeForm).toContain('sorter: apiKeySorters.holder')
        expect(requests).toContain('sorter: apiKeySorters.holder')
        expect(user).toContain('sorter: apiKeySorters.owner')
        expect(requests).toContain("sorter: apiKeySorters.created, defaultSortOrder: 'descend'")
        for (const s of [scoped, freeForm, user]) expect(s).toContain('...apiKeyActivityColumns()')
        expect(definition(org.code, 'const apiKeyDeclaredColumn')).toContain('sorter: apiKeySorters.declaredName')
    })
    it('row 30: the apiKeys query selects every field the search and the sorters read; the term never reaches the URL', () => {
        const doc = gqlDocument(org.source, 'query apiKeys(')
        const under = doc.slice(doc.indexOf('apiKeys(orgUuid'))
        for (const f of ['uuid', 'type', 'object', 'keyOrder', 'declaredName', 'notes', 'createdDate', 'accessDate']) expect(under).toMatch(new RegExp(`\\b${f}\\b`))
        const federation = under.slice(under.indexOf('federation {'))
        expect(under).toContain('federation {')
        expect(federation.slice(0, federation.indexOf('}'))).toMatch(/\brepository\b/)
        expect(org.code).toContain('watch(() => [route.query.tab, route.query.auditTab, route.query.policyTab, route.query.adminTab]')
        expect(org.code).not.toContain('query.keysSearch')
        let at = org.code.indexOf('router.push(')
        let pushes = 0
        while (at >= 0) {
            let depth = 0
            let end = at + 'router.push'.length
            for (; end < org.code.length; end++) {
                if (org.code[end] === '(') depth++
                else if (org.code[end] === ')' && --depth === 0) break
            }
            const call = org.code.slice(at, end + 1)
            for (const term of ['freeFormKeysSearch', 'userKeysSearch', 'scopedKeysSearch']) expect(call).not.toContain(term)
            pushes++
            at = org.code.indexOf('router.push(', end)
        }
        expect(pushes).toBeGreaterThan(0)
    })
})

describe('TPF-88 FederatedTrustRulesPanel: identities search and columns', () => {
    it('row 28: one search box before the identities table, which shows the filtered list', () => {
        const boxes = find(panel.ast, (n) => attribute(n, 'data-testid') === 'keys-search-federatedIdentities').map(last)
        expect(boxes).toHaveLength(1)
        expect(boxes[0].tag).toBe('n-input')
        expect(directive(boxes[0], 'model', 'value')).toBe('identitiesSearch')
        const identities = find(panel.ast, (n) => n.tag === 'n-data-table' && directive(n, 'bind', 'columns') === 'identityFields').map(last)
        expect(identities).toHaveLength(1)
        expect(offset(boxes[0])).toBeLessThan(offset(identities[0]))
        expect(directive(identities[0], 'bind', 'data')).toBe('shownIdentities')
        expect(directive(identities[0], 'bind', 'scroll-x')).toBe('2220')
        expect(find(panel.ast, (n) => directive(n, 'bind', 'data') === 'identities')).toHaveLength(0)
        expect(find(panel.ast, (n) => n.tag === 'n-data-table' && directive(n, 'bind', 'data') === 'rules')).toHaveLength(1)
        const empty = emptySlot(identities[0])
        expect(empty).toBeTruthy()
        expect(attribute(empty, 'data-testid')).toBe('keys-empty-federatedIdentities')
    })
    it('row 29: the panel filters with the shared helper and uses the shared sortable and UUID columns', () => {
        expect(definition(panel.code, 'const shownIdentities')).toContain('filterApiKeysBySearch(props.identities, identitiesSearch.value)')
        const fields = columnsSlice(panel.code, 'const identityFields')
        expect(fields).toContain('sorter: apiKeySorters.repository')
        expect(fields).toContain("uuidCopyColumn({ what: 'Key UUID', testid: 'federatedIdentities-key-uuid', notify: props.notify })")
        expect(fields).toContain('apiKeyLastAccessColumn(')
        expect(fields).not.toContain("key: 'accessDate'")
        const imp = panel.code.match(/import \{([^}]*)\} from '(?:\.\.|@)\/utils\/apiKeyControls'/)
        expect(imp).toBeTruthy()
        const names = imp![1].split(',').map((s) => s.trim())
        for (const n of ['filterApiKeysBySearch', 'apiKeySorters', 'apiKeyLastAccessColumn']) expect(names).toContain(n)
        expect(panel.code).toMatch(/import \{\s*uuidCopyColumn\s*\} from '(?:\.\.|@)\/utils\/uuidCopyColumn'/)
    })
})
