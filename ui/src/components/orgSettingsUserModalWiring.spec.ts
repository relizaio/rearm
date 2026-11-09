import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { parse as parseSfc } from '@vue/compiler-sfc'

// License seats review follow-up: Organization Settings > Users. The user permissions modal is split
// into a Permissions tab and a Danger zone tab holding Inactivate User, the latter shown only when
// inactivating the selected user is an option; your own row has no edit icon. OrgSettings is never
// mounted in tests, so the template is parsed and walked (the orgSettingsUsersSearchWiring pattern).
const ELEMENT = 1
const ATTRIBUTE = 6
const DIRECTIVE = 7

const source = readFileSync(fileURLToPath(new URL('./OrgSettings.vue', import.meta.url)), 'utf8')
const code = source
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1')
const ast: any = parseSfc(source).descriptor.template?.ast

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
const attribute = (n: any, name: string): string | undefined => (n.props || []).find((p: any) =>
    p.type === ATTRIBUTE && p.name === name)?.value?.content
const text = (n: any): string => source.slice(n.loc.start.offset, n.loc.end.offset)

const modal = find((n) => n.tag === 'n-modal' && directive(n, 'bind', 'show') === 'showOrgSettingsUserPermissionsModal')

describe('OrgSettings: the user permissions modal', () => {
    it('holds one n-tabs with a Permissions tab and a Danger zone tab, in that order', () => {
        expect(modal).toHaveLength(1)
        const tabs = find((n) => n.tag === 'n-tabs', modal[0])
        expect(tabs).toHaveLength(1)
        expect(directive(tabs[0], 'model', 'value')).toBe('userModalTab')
        const panes = find((n) => n.tag === 'n-tab-pane', tabs[0])
        expect(panes.map((p) => [attribute(p, 'name'), attribute(p, 'tab')])).toEqual([
            ['permissions', 'Permissions'],
            ['danger', 'Danger zone']
        ])
    })

    it('the Permissions tab holds the permissions editor and save; Inactivate User is only in the Danger zone', () => {
        const panes = find((n) => n.tag === 'n-tab-pane', modal[0])
        const [permissions, danger] = panes
        expect(find((n) => n.tag === 'ScopedPermissions', permissions)).toHaveLength(1)
        expect(text(permissions)).toContain('Save Permissions')
        expect(text(permissions)).not.toContain('inactivateUser(')
        expect(text(danger)).toContain('@click="inactivateUser(selectedUser.uuid)"')
        expect(text(danger)).toContain('Inactivate User')
        expect(text(modal[0]).split('inactivateUser(').length - 1).toBe(1)
    })

    it('the Danger zone tab shows only when inactivation is an option, never for yourself', () => {
        const danger = find((n) => n.tag === 'n-tab-pane' && attribute(n, 'name') === 'danger', modal[0])
        expect(directive(danger[0], 'if')).toBe('showUserDangerZone')
        const def = code.slice(code.indexOf('const showUserDangerZone'), code.indexOf('\n\n', code.indexOf('const showUserDangerZone')))
        expect(def).toContain('canInactivateUsers.value')
        expect(def).toContain('selectedUser.value.uuid !== myUser.value?.uuid')
    })

    it('opens on the Permissions tab every time', () => {
        const at = code.indexOf('async function editUser(email: string) {')
        const body = code.slice(at, code.indexOf('\n}\n', at))
        expect(body).toMatch(/userModalTab\.value = 'permissions'\s*showOrgSettingsUserPermissionsModal\.value = true/)
        expect(code).toContain("const userModalTab = ref('permissions')")
    })
})

describe('OrgSettings: the users table controls', () => {
    it('your own row has no edit icon (and no remove)', () => {
        const at = code.indexOf('const userFields = [')
        const fields = code.slice(at, code.indexOf('\n]\n', at))
        expect(fields).toContain('if (isOrgAdmin.value && row.uuid !== myUser.value.uuid) {')
        expect(fields.split('onClick: () => editUser(row.email)').length - 1).toBe(1)
        expect(fields).not.toContain('Modify my approval roles')
    })
})
