import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { parse as parseSfc } from '@vue/compiler-sfc'

// The key lists of Programmatic Access put their create icon right of the list's heading, as the component view's
// Branch heading does (a flex row, the label in a span, a 20px CirclePlus beside it), not below the table.
const ELEMENT = 1
const ATTRIBUTE = 6
const DIRECTIVE = 7
const ast = (file: string): any =>
    parseSfc(readFileSync(fileURLToPath(new URL(file, import.meta.url)), 'utf8')).descriptor.template?.ast

function find (root: any, pred: (n: any) => boolean): any[][] {
    const out: any[][] = []
    const walk = (node: any, ancestors: any[]): void => {
        if (node.type === ELEMENT && pred(node)) out.push([...ancestors, node])
        for (const child of node.children || []) walk(child, node.type === ELEMENT ? [...ancestors, node] : ancestors)
    }
    walk(root, [])
    return out
}
const attr = (n: any, name: string) => (n.props || []).find((p: any) => p.type === ATTRIBUTE && p.name === name)?.value?.content
const dir = (n: any, name: string, arg?: string) => (n.props || []).find((p: any) =>
    p.type === DIRECTIVE && p.name === name && (arg === undefined || p.arg?.content === arg))?.exp?.content
const text = (n: any): string => (n.children || []).map((c: any) => c.type === 2 ? c.content : text(c)).join('').trim()

const LISTS = [
    { file: './OrgSettings.vue', label: 'Free Form Keys', click: 'genFreeFormApiKey', title: 'Create Free Form Key', guard: 'isOrgAdmin' },
    { file: './ExternalKeysPanel.vue', label: 'External Keys', click: 'createExternalKey', title: 'Create External Key', guard: 'isOrgAdmin',
        testid: 'external-key-create' },
    { file: './FederatedTrustRulesPanel.vue', label: 'Trust Rules', click: 'openCreate', title: 'Add Trust Rule', guard: 'canManage' },
    { file: './ApiKeyGroupsOfOrg.vue', label: 'API Key Groups', click: 'openCreate', title: 'Add group', guard: 'isWritable && supported',
        testid: 'add-api-key-group' },
]

describe('key lists: the create icon sits in the list heading', () => {
    for (const l of LISTS) {
        it(`${l.label}: right of its heading, with its handler, guard, title and testid, and not below the table`, () => {
            const root = ast(l.file)
            const icons = find(root, (n) => n.tag === 'n-icon' && dir(n, 'on', 'click') === l.click
                && find(n, (c) => c.tag === 'CirclePlus').length > 0)
            expect(icons, 'one create icon').toHaveLength(1)
            const path = icons[0]
            const icon = path[path.length - 1]
            const head = path[path.length - 2]
            expect(head.tag).toBe('h5')
            expect(attr(head, 'style')).toBe('display: flex; align-items: center; gap: 8px;')
            const kids = head.children.filter((c: any) => c.type === ELEMENT)
            expect(kids[0].tag).toBe('span')
            expect(text(kids[0])).toBe(l.label)
            expect(kids[1]).toBe(icon)
            expect(attr(icon, 'size')).toBe('20')
            expect(attr(icon, 'class')).toContain('clickable')
            expect(attr(icon, 'title')).toBe(l.title)
            expect(dir(icon, 'if')).toBe(l.guard)
            if (l.testid) expect(attr(icon, 'data-testid')).toBe(l.testid)
            // nothing creates from below a key table any more (a dialog's own controls aside)
            const scope = l.file === './OrgSettings.vue'
                ? find(root, (n) => n.tag === 'n-tab-pane' && attr(n, 'name') === 'programmaticAccess')[0].at(-1) : root
            for (const table of find(scope, (n) => n.tag === 'n-data-table')) {
                const parent = table[table.length - 2]
                const after = parent.children.slice(parent.children.indexOf(table[table.length - 1]) + 1)
                expect(after.some((n: any) => n.type === ELEMENT && n.tag !== 'n-modal' && find(n, (c) => c.tag === 'CirclePlus').length)).toBe(false)
            }
        })
    }
})
