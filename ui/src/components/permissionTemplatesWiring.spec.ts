import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { parse as parseSfc } from '@vue/compiler-sfc'

// NodeTypes of @vue/compiler-core, which compiler-sfc does not re-export.
const ELEMENT = 1
const ATTRIBUTE = 6
const DIRECTIVE = 7

/**
 * TPF-113 (architecture 5, test row 13): where the permission template picker and panel are mounted
 * and what they are bound to. CliLogin, the key modal's request mode and UserProfile are parsed and
 * walked (the orgSettingsKeysWiring.spec.ts helpers); the components themselves run in
 * PermissionTemplatePicker.spec.ts and PermissionTemplatesPanel.spec.ts.
 */
function load (file: string) {
    const source = readFileSync(fileURLToPath(new URL(file, import.meta.url)), 'utf8')
    const ast: any = parseSfc(source).descriptor.template?.ast
    return { source, ast }
}
const cli = load('./CliLogin.vue')
const modal = load('./ApiKeyPermissionsModal.vue')
const profile = load('./UserProfile.vue')

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
const hasProp = (n: any, name: string): boolean => (n.props || []).some((p: any) =>
    (p.type === ATTRIBUTE && p.name === name) || (p.type === DIRECTIVE && p.arg?.content === name))
const attribute = (n: any, name: string): string | undefined => (n.props || []).find((p: any) =>
    p.type === ATTRIBUTE && p.name === name)?.value?.content
const last = (path: any[]): any => path[path.length - 1]
const offset = (n: any): number => n.loc.start.offset
const pickers = (ast: any) => find(ast, n => n.tag === 'PermissionTemplatePicker')

describe('permission templates wiring (TPF-113 row 13)', () => {
    it('CliLogin mounts the picker inside the new-key panel, bounded and disabled while the bounds load', () => {
        const found = pickers(cli.ast)
        expect(found).toHaveLength(1)
        const path = found[0]
        expect(path.some((a: any) => directive(a, 'if') === "choice === 'new'")).toBe(true)
        const p = last(path)
        expect(directive(p, 'bind', 'org-uuid')).toBe('newKeyOrg')
        expect(directive(p, 'bind', 'model')).toBe('scoped')
        expect(directive(p, 'bind', 'max-org-type')).toBe('ownerOrgType')
        expect(directive(p, 'bind', 'allowed-functions')).toBe('ownerFunctions')
        expect(directive(p, 'bind', 'org-functions')).toBe('ownerOrgFunctions')
        expect(directive(p, 'bind', 'disabled')).toBe('loadingBounds')
        expect(directive(p, 'bind', 'notify')).toBe('notify')
        expect(directive(p, 'on', 'apply')).toBe('onTemplateApply')
        // the reductions block sits under the picker, in the same panel, before the editor
        const block = find(cli.ast, n => attribute(n, 'data-testid') === 'template-reductions')
        expect(block).toHaveLength(1)
        expect(block[0].some((a: any) => directive(a, 'if') === "choice === 'new'")).toBe(true)
        const editor = find(cli.ast, n => n.tag === 'ScopedPermissions')
        expect(offset(last(block[0]))).toBeGreaterThan(offset(p))
        expect(offset(last(block[0]))).toBeLessThan(offset(last(editor[0])))
        expect(cli.source).toContain('<div>{{ REDUCTIONS_SENTENCE }}</div>')
        expect(cli.source).toContain('return ownerOrgPerm.value?.functions || []')
    })

    it('the key modal mounts the picker in request mode only, unbounded and disabled while loading', () => {
        const found = pickers(modal.ast)
        expect(found).toHaveLength(1)
        expect(found[0].some((a: any) => directive(a, 'else-if') === 'isRequest')).toBe(true)
        const p = last(found[0])
        expect(hasProp(p, 'max-org-type')).toBe(false)
        expect(hasProp(p, 'allowed-functions')).toBe(false)
        expect(hasProp(p, 'org-functions')).toBe(false)
        expect(directive(p, 'bind', 'disabled')).toBe('loading')
        expect(directive(p, 'bind', 'model')).toBe('scoped')
        expect(directive(p, 'bind', 'name-of')).toBe('resolveScopedObjectName')
        expect(directive(p, 'on', 'apply')).toBe('onTemplateApply')
        // no new editor mount: boardPermissions.spec.ts still counts two
        expect(modal.source.match(/:boards="orgBoards"/g)).toHaveLength(2)
    })

    it('UserProfile mounts the panel in the API Keys pane after the CLI sessions table, and reloads it when the request modal closes', () => {
        const found = find(profile.ast, n => n.tag === 'PermissionTemplatesPanel')
        expect(found).toHaveLength(1)
        expect(found[0].some((a: any) => a.tag === 'n-tab-pane' && attribute(a, 'name') === 'apiKeys')).toBe(true)
        const p = last(found[0])
        expect(attribute(p, 'ref')).toBe('templatesPanel')
        expect(directive(p, 'bind', 'org-uuid')).toBe('newKeyOrg')
        expect(directive(p, 'bind', 'notify')).toBe('notify')
        const sessions = find(profile.ast, n => n.tag === 'n-data-table' && directive(n, 'bind', 'data') === 'cliSessions')
        expect(offset(p)).toBeGreaterThan(offset(last(sessions[0])))
        expect(profile.source).toContain('<h4 class="mt-4">Permission templates</h4>')
        expect(profile.source).toContain('watch(showRequestModal, (show) => { if (!show) templatesPanel.value?.reload() })')
    })

    it('the inventory spec lists the panel as a reader and OrgSettings keeps its three board bindings', () => {
        const inventory = readFileSync(fileURLToPath(new URL('./componentRefConsumers.spec.ts', import.meta.url)), 'utf8')
        expect(inventory).toContain("'components/PermissionTemplatesPanel.vue': BOTH,")
        const org = readFileSync(fileURLToPath(new URL('./OrgSettings.vue', import.meta.url)), 'utf8')
        expect(org.match(/:boards="orgBoards"/g)).toHaveLength(3)
    })

    it('no template uuid travels to an approval or a request', () => {
        for (const src of [cli.source, modal.source]) expect(src).not.toMatch(/templateUuid|template_uuid/)
    })
})
