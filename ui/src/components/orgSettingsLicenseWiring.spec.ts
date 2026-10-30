import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { parse as parseSfc } from '@vue/compiler-sfc'

// NodeTypes of @vue/compiler-core, which compiler-sfc does not re-export.
const ELEMENT = 1
const INTERPOLATION = 5
const ATTRIBUTE = 6
const DIRECTIVE = 7

/**
 * T-28 (SCORE-42, design ADR-2): where OrgSettings mounts the "Default declared licence" block,
 * how it latches the schema-drift flag, and that neither settings mutation of the page names the
 * two fields (the CE rule: a field the mirror lacks must not join a shared response selection).
 * OrgSettings is never mounted in tests, so the template is parsed and walked (the
 * orgSettingsSigningWiring.spec.ts pattern); the panel itself runs in
 * OrgDeclaredLicensePanel.spec.ts.
 */
const source = readFileSync(fileURLToPath(new URL('./OrgSettings.vue', import.meta.url)), 'utf8')
const code = source
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1')
const store = readFileSync(fileURLToPath(new URL('../store.ts', import.meta.url)), 'utf8')
const commit = readFileSync(fileURLToPath(new URL('../utils/orgSettingsCommit.ts', import.meta.url)), 'utf8')
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
const hasDirective = (n: any, name: string): boolean => (n.props || []).some((p: any) => p.type === DIRECTIVE && p.name === name)
const attribute = (n: any, name: string): string | undefined => (n.props || []).find((p: any) =>
    p.type === ATTRIBUTE && p.name === name)?.value?.content
const interpolations = (n: any): string[] => (n.children || []).filter((c: any) => c.type === INTERPOLATION)
    .map((c: any) => c.content?.content?.trim())
const elementChildren = (n: any): any[] => (n.children || []).filter((c: any) => c.type === ELEMENT)
const last = (path: any[]): any => path[path.length - 1]
const byTestId = (id: string) => find((n) => attribute(n, 'data-testid') === id)

/** The source of one top-level script statement, from its opening line to the next blank line. */
const definition = (opening: string): string => {
    const at = code.indexOf(opening)
    if (at < 0) return ''
    const end = code.indexOf('\n\n', at)
    return code.slice(at, end < 0 ? undefined : end)
}
/** The gql template literal holding `marker`, found by operation name rather than by line. */
function gqlDocument (marker: string): string {
    const at = source.indexOf(marker)
    if (at < 0) return ''
    const start = source.lastIndexOf('gql`', at)
    const end = source.indexOf('`', at)
    return source.slice(start, end + 1)
}

describe('OrgSettings: the default declared licence block', () => {
    const block = byTestId('org-default-license-block')
    const unsupported = byTestId('org-default-license-unsupported')

    it('is one block inside the General sub-tab of the Admin Settings tab, gated on the drift flag', () => {
        expect(block).toHaveLength(1)
        const node = last(block[0])
        expect(directive(node, 'if')).toBe('declaredLicenseSupported')
        const panes = block[0].filter((n: any) => n.tag === 'n-tab-pane')
        expect(panes).toHaveLength(2)
        expect(attribute(panes[0], 'name')).toBe('adminSettings')
        expect(directive(panes[0], 'if')).toBe('isOrgAdmin')
        expect(attribute(panes[1], 'name')).toBe('general')
        expect(directive(panes[1], 'if')).toBeUndefined()
    })

    it('is the next sibling after the settings form block, before SBOM export signing, and not inside the form', () => {
        const node = last(block[0])
        const save = find((n) => directive(n, 'on', 'click') === 'saveOrgSettings')
        expect(save).toHaveLength(1)
        const formAt = save[0].findIndex((n: any) => n.tag === 'n-form')
        const formBlock = save[0][formAt - 1]
        const siblings = elementChildren(save[0][formAt - 2])
        expect(siblings[siblings.indexOf(formBlock) + 1]).toBe(node)
        expect(block[0].some((n: any) => n.tag === 'n-form')).toBe(false)
        const signing = last(byTestId('sbom-signing-block')[0])
        expect(siblings.indexOf(signing)).toBeGreaterThan(siblings.indexOf(node))
    })

    it('mounts the panel on the organization, the admin gate and notify, and listens for unsupported', () => {
        const panel = elementChildren(last(block[0]))
        expect(panel).toHaveLength(1)
        expect(panel[0].tag).toBe('OrgDeclaredLicensePanel')
        expect(directive(panel[0], 'bind', 'org-uuid')).toBe('orgResolved')
        expect(directive(panel[0], 'bind', 'can-manage')).toBe('isOrgAdmin')
        expect(directive(panel[0], 'bind', 'notify')).toBe('notify')
        expect(directive(panel[0], 'on', 'unsupported')).toBe('markDeclaredLicenseUnsupported')
        expect(code).toMatch(/import OrgDeclaredLicensePanel from '\.\/OrgDeclaredLicensePanel\.vue'/)
    })

    it('shows the unsupported note instead, right after it', () => {
        expect(unsupported).toHaveLength(1)
        const note = last(unsupported[0])
        expect(hasDirective(note, 'else')).toBe(true)
        expect(interpolations(note)).toEqual(['UNSUPPORTED_NOTE'])
        expect(code).toMatch(/import \{ UNSUPPORTED_NOTE \} from '@\/utils\/declaredLicense'/)
        const siblings = elementChildren(block[0][block[0].length - 2])
        expect(siblings[siblings.indexOf(last(block[0])) + 1]).toBe(note)
    })

    it('reads the flag from the store and latches it false on unsupported', () => {
        expect(definition('const declaredLicenseSupported')).toMatch(
            /computed\(\(\): boolean =>\s*store\.state\.declaredLicenseSupported !== false\)$/)
        expect(definition('function markDeclaredLicenseUnsupported')).toMatch(
            /store\.commit\('SET_DECLARED_LICENSE_SUPPORTED', false\)/)
    })

    it('keeps both settings mutations and the store commit free of the two fields', () => {
        for (const marker of ['mutation updateOrganizationSettings(', 'mutation updateOrganizationDefaultView(']) {
            const doc = gqlDocument(marker)
            expect(doc).toContain(marker)
            expect(doc).not.toMatch(/defaultLicenseExpression|defaultLicenseUrl/)
        }
        expect(commit).toMatch(/export function organizationToCommit/)
        expect(commit).not.toMatch(/defaultLicenseExpression|defaultLicenseUrl/)
    })

    it('declares the state, true by default, and its mutation in the store', () => {
        expect(store).toMatch(/\n\s+declaredLicenseSupported: true,\n/)
        expect(store).toMatch(/SET_DECLARED_LICENSE_SUPPORTED \(state : any, supported : boolean\) \{\s*state\.declaredLicenseSupported = supported\s*\}/)
    })
})
