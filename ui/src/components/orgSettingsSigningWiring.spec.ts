import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { parse as parseSfc } from '@vue/compiler-sfc'
import { UNSUPPORTED_TEXT } from '@/utils/sbomSigningKeys'

// NodeTypes of @vue/compiler-core, which compiler-sfc does not re-export.
const ELEMENT = 1
const TEXT = 2
const ATTRIBUTE = 6
const DIRECTIVE = 7

/**
 * T-25 (SCORE-35, design 3.2 and 3.4): where OrgSettings mounts the "SBOM export signing"
 * section and how it latches the schema-drift flag. OrgSettings is never mounted in tests, so
 * the template is parsed and walked and the script read for its wiring (the
 * releaseViewExportWiring.spec.ts pattern); the section itself is run in
 * SbomSigningKeysPanel.spec.ts.
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
/** Every element in document order. */
function documentOrder (): any[] {
    const out: any[] = []
    const walk = (node: any): void => {
        if (node.type === ELEMENT) out.push(node)
        for (const child of node.children || []) walk(child)
    }
    walk(ast)
    return out
}

const directive = (n: any, name: string, arg?: string): string | undefined => (n.props || []).find((p: any) =>
    p.type === DIRECTIVE && p.name === name && (arg === undefined || p.arg?.content === arg))?.exp?.content
const hasDirective = (n: any, name: string): boolean => (n.props || []).some((p: any) => p.type === DIRECTIVE && p.name === name)
const attribute = (n: any, name: string): string | undefined => (n.props || []).find((p: any) =>
    p.type === ATTRIBUTE && p.name === name)?.value?.content
/** The element's own text, not its descendants'. */
const text = (n: any): string => (n.children || []).filter((c: any) => c.type === TEXT).map((c: any) => c.content)
    .join(' ').replace(/\s+/g, ' ').trim()
const elementChildren = (n: any): any[] => (n.children || []).filter((c: any) => c.type === ELEMENT)
const byTestId = (id: string) => find((n) => attribute(n, 'data-testid') === id)

/** The source of one top-level script statement, from its opening line to the next blank line. */
const definition = (opening: string): string => {
    const at = code.indexOf(opening)
    if (at < 0) return ''
    const end = code.indexOf('\n\n', at)
    return code.slice(at, end < 0 ? undefined : end)
}

describe('OrgSettings: the SBOM export signing section', () => {
    const block = byTestId('sbom-signing-block')
    const unsupported = byTestId('sbom-signing-unsupported')

    it('is one block inside the General sub-tab of the Admin Settings tab, gated on the drift flag', () => {
        expect(block).toHaveLength(1)
        const path = block[0]
        const node = path[path.length - 1]
        expect(directive(node, 'if')).toBe('sbomSigningSupported')
        const panes = path.filter((n: any) => n.tag === 'n-tab-pane')
        expect(panes).toHaveLength(2)
        expect(attribute(panes[0], 'name')).toBe('adminSettings')
        expect(directive(panes[0], 'if')).toBe('isOrgAdmin')
        expect(attribute(panes[1], 'name')).toBe('general')
    })

    it('follows the Support disclosure export block and the declared licence default after the settings form', () => {
        const order = documentOrder()
        const node = block[0][block[0].length - 1]
        const support = find((n) => text(n).includes('Support disclosure export'))
        expect(support).toHaveLength(1)
        expect(order.indexOf(node)).toBeGreaterThan(order.indexOf(support[0][support[0].length - 1]))

        // The block holding the form whose button runs saveOrgSettings, and the element after it.
        const save = find((n) => directive(n, 'on', 'click') === 'saveOrgSettings')
        expect(save).toHaveLength(1)
        const formAt = save[0].findIndex((n: any) => n.tag === 'n-form')
        const formBlock = save[0][formAt - 1]
        const siblings = elementChildren(save[0][formAt - 2])
        // SCORE-42 put the declared licence default (its block and its v-else note) between the
        // form and this section: the form, the licence default, signing, the default view.
        const license = byTestId('org-default-license-block')[0]
        const licenseNote = byTestId('org-default-license-unsupported')[0]
        expect(siblings[siblings.indexOf(formBlock) + 1]).toBe(license[license.length - 1])
        expect(siblings[siblings.indexOf(formBlock) + 2]).toBe(licenseNote[licenseNote.length - 1])
        expect(siblings[siblings.indexOf(formBlock) + 3]).toBe(node)
        // Not inside the form: it has its own mutations and no Save.
        expect(block[0].some((n: any) => n.tag === 'n-form')).toBe(false)
    })

    it('mounts the panel on the organization, the admin gate and notify, and listens for unsupported', () => {
        const node = block[0][block[0].length - 1]
        const panel = elementChildren(node)
        expect(panel).toHaveLength(1)
        expect(panel[0].tag).toBe('SbomSigningKeysPanel')
        expect(directive(panel[0], 'bind', 'org-uuid')).toBe('orgResolved')
        expect(directive(panel[0], 'bind', 'can-manage')).toBe('isOrgAdmin')
        expect(directive(panel[0], 'bind', 'notify')).toBe('notify')
        expect(directive(panel[0], 'on', 'unsupported')).toBe('markSbomSigningUnsupported')
        expect(code).toMatch(/import SbomSigningKeysPanel from '\.\/SbomSigningKeysPanel\.vue'/)
    })

    it('shows the sync-lag note instead, right after it', () => {
        expect(unsupported).toHaveLength(1)
        const note = unsupported[0][unsupported[0].length - 1]
        expect(hasDirective(note, 'else')).toBe(true)
        expect(text(note)).toBe(UNSUPPORTED_TEXT)
        const siblings = elementChildren(block[0][block[0].length - 2])
        expect(siblings[siblings.indexOf(block[0][block[0].length - 1]) + 1]).toBe(note)
    })

    it('reads the flag from the store and latches it false on unsupported', () => {
        expect(definition('const sbomSigningSupported')).toMatch(
            /computed\(\(\): boolean =>\s*store\.state\.sbomSigningSupported !== false\)$/)
        expect(definition('function markSbomSigningUnsupported')).toMatch(
            /store\.commit\('SET_SBOM_SIGNING_SUPPORTED', false\)/)
    })

    it('declares the state, true by default, and its mutation in the store', () => {
        expect(store).toMatch(/\n\s+sbomSigningSupported: true,\n/)
        expect(store).toMatch(/SET_SBOM_SIGNING_SUPPORTED \(state : any, supported : boolean\) \{\s*state\.sbomSigningSupported = supported\s*\}/)
    })
})
