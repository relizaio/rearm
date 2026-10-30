import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { parse as parseSfc } from '@vue/compiler-sfc'

// NodeTypes of @vue/compiler-core, which compiler-sfc does not re-export.
const ELEMENT = 1
const DIRECTIVE = 7

/**
 * TPF-101 test 3 (TPF-74 child 2, ARCHITECTURE round 2 2.6): OrgSettings' perspective members
 * dialog already fetches its own document, so the org lists reading componentRefs (no
 * perspectiveDetails, no createdDate) leave it whole. OrgSettings is never mounted in tests; the
 * source and the template are read (the orgSettingsLicenseWiring.spec.ts helpers).
 */
const source = readFileSync(fileURLToPath(new URL('./OrgSettings.vue', import.meta.url)), 'utf8')
const code = source
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1')
const ast: any = parseSfc(source).descriptor.template?.ast

/** Every element the predicate accepts. */
function find (pred: (n: any) => boolean, root: any = ast): any[] {
    const out: any[] = []
    const walk = (node: any): void => {
        if (node.type === ELEMENT && pred(node)) out.push(node)
        for (const child of node.children || []) walk(child)
    }
    walk(root)
    return out
}
const directive = (n: any, name: string, arg?: string): string | undefined => (n.props || []).find((p: any) =>
    p.type === DIRECTIVE && p.name === name && (arg === undefined || p.arg?.content === arg))?.exp?.content

/** The source of one top-level script statement, from its opening line to the next blank line. */
const definition = (opening: string): string => {
    const at = code.indexOf(opening)
    if (at < 0) return ''
    const end = code.indexOf('\n\n', at)
    return code.slice(at, end < 0 ? undefined : end)
}
/** A top-level function or array, from its opening line to its first line that is exactly `close`. */
const block = (opening: string, close: string): string => {
    const at = code.indexOf(opening)
    if (at < 0) return ''
    const end = code.indexOf('\n' + close + '\n', at)
    return code.slice(at, end < 0 ? undefined : end + close.length + 1)
}
/** Every gql template literal of the source that contains all of the markers. */
function gqlDocuments (...markers: string[]): string[] {
    const out: string[] = []
    let at = source.indexOf('gql`')
    while (at > -1) {
        const end = source.indexOf('`', at + 4)
        const doc = source.slice(at, end + 1)
        if (markers.every(m => doc.includes(m))) out.push(doc)
        at = source.indexOf('gql`', end + 1)
    }
    return out
}

describe('OrgSettings: the perspective members dialog fetches its own rows (TPF-101)', () => {
    const fn = block('async function showPerspectiveComponentsModalFn', '}')

    it('(a) its own componentsOfPerspective document selects perspectiveDetails, no-cache, after the list dispatches', () => {
        expect(fn.length).toBeGreaterThan(0)
        const docs = gqlDocuments('componentsOfPerspective(perspectiveUuid: $perspectiveUuid')
        expect(docs).toHaveLength(1)
        const doc = docs[0]
        expect(fn).toContain(doc)
        expect(doc).toContain('perspectiveDetails {')
        for (const f of ['uuid', 'name', 'org', 'type']) expect(doc).toMatch(new RegExp(`^\\s*${f}\\s*$`, 'm'))
        expect(fn).toContain("fetchPolicy: 'no-cache'")
        const query = fn.indexOf('graphqlClient.query')
        const comps = fn.indexOf("store.dispatch('fetchComponents', orgResolved.value)")
        const prods = fn.indexOf("store.dispatch('fetchProducts', orgResolved.value)")
        expect(query).toBeGreaterThan(-1)
        expect(comps).toBeGreaterThan(-1)
        expect(prods).toBeGreaterThan(-1)
        expect(comps).toBeLessThan(query)
        expect(prods).toBeLessThan(query)
        expect(fn).toContain('perspectiveComponents.value = components.sort(')
    })

    it('(b) the members table renders the rows of that document', () => {
        const tables = find(n => n.tag === 'n-data-table' && directive(n, 'bind', 'columns') === 'perspectiveComponentColumns')
        expect(tables).toHaveLength(1)
        expect(directive(tables[0], 'bind', 'data')).toBe('perspectiveComponents')
    })

    it('(c) perspectiveDetails is read only off the dialog rows and the dialog own reads, never off a list row', () => {
        const columns = block('const perspectiveComponentColumns', ']')
        expect(columns).toContain('row.perspectiveDetails')
        const confirm = block('async function confirmTeaMembershipAdd', '}')
        expect(confirm).toContain('perspectiveDetails')
        // every perspectiveDetails read of the script sits in the column, the dialog function or the TEA confirmation
        const script = code.slice(code.indexOf('<script'))
        let at = script.indexOf('perspectiveDetails')
        let n = 0
        while (at > -1) {
            n++
            const line = script.slice(script.lastIndexOf('\n', at) + 1, script.indexOf('\n', at))
            expect(columns.includes(line) || fn.includes(line) || confirm.includes(line), line).toBe(true)
            at = script.indexOf('perspectiveDetails', at + 1)
        }
        expect(n).toBeGreaterThan(0)
        for (const d of ['const orgComponents', 'const teaNamedObjects', 'const orgProducts', 'const allComponents = computed',
            'const availableComponentsOptions', 'const availableProductsOptions']) {
            expect(definition(d).length, d).toBeGreaterThan(0)
            expect(definition(d), d).not.toContain('perspectiveDetails')
        }
    })

    it('(d) the add selects read only name and uuid off the list rows', () => {
        for (const [d, v] of [['const availableComponentsOptions', 'c'], ['const availableProductsOptions', 'p']]) {
            const def = definition(d)
            expect(def).toMatch(/componentsOfOrg\(|productsOfOrg\(/)
            const members = [...def.matchAll(new RegExp(`\\b${v}\\.(\\w+)`, 'g'))].map(m => m[1])
            expect(members.length).toBeGreaterThan(0)
            expect(new Set(members)).toEqual(new Set(['uuid', 'name']))
        }
    })

    it('(e) createdDate is a perspective column, fed by loadPerspectives', () => {
        expect(definition('const perspectiveFields')).toContain("key: 'createdDate'")
        expect(block('const perspectiveComponentColumns', ']')).not.toContain('createdDate')
        const tables = find(n => n.tag === 'n-data-table' && directive(n, 'bind', 'columns') === 'perspectiveFields')
        expect(tables).toHaveLength(1)
        expect(directive(tables[0], 'bind', 'data')).toBe('perspectives')
        const load = block('async function loadPerspectives', '}')
        expect(load).toContain('perspectives.value = response.data.perspectives')
        const docs = gqlDocuments('perspectives(org: $org)', 'sidPurlOverride')
        expect(docs).toHaveLength(1)
        expect(load).toContain(docs[0])
        expect(docs[0]).toMatch(/^\s*createdDate\s*$/m)
    })
})
