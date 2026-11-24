import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { parse as parseSfc } from '@vue/compiler-sfc'
import { parse, print, validate, type GraphQLSchema } from 'graphql'
import {
    SSO_USERS_WITHOUT_ROLE_OPTIONS, SsoUsersWithoutRole, ssoUsersWithoutRoleInput, ssoUsersWithoutRoleOf
} from '@/utils/licenseSeats'
import { ORGANIZATIONS_CORE, ORGANIZATIONS_FULL } from '@/utils/organizationsQuery'
import { organizationToCommit } from '@/utils/orgSettingsCommit'
import { proSchema } from '@/utils/schemaDriftSupport'

// NodeTypes of @vue/compiler-core, which compiler-sfc does not re-export.
const ELEMENT = 1
const ATTRIBUTE = 6
const DIRECTIVE = 7

/**
 * TPF-168 U3 (TPF-165 4.2, D6 setting control, 6.2): the default organization's
 * "SSO users without a role" radio. The rules (absent reads as REFUSE; sent only on the default
 * organization and only when changed) live in utils/licenseSeats and run here; OrgSettings is
 * never mounted in tests, so its template and save path are parsed and walked (the
 * orgSettingsLicenseWiring.spec.ts rig).
 */
const source = readFileSync(fileURLToPath(new URL('./OrgSettings.vue', import.meta.url)), 'utf8')
const code = source
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1')
const ast: any = parseSfc(source).descriptor.template?.ast

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

function gqlDocument (marker: string): string {
    const at = source.indexOf(marker)
    if (at < 0) return ''
    const start = source.lastIndexOf('gql`', at)
    const end = source.indexOf('`', at)
    return source.slice(start, end + 1)
}

describe('ssoUsersWithoutRole rules', () => {
    it('reads REFUSE when the key is absent, null or unknown, and CREATE_WITHOUT_ROLE only when stored', () => {
        expect(ssoUsersWithoutRoleOf({})).toBe('REFUSE')
        expect(ssoUsersWithoutRoleOf(null)).toBe('REFUSE')
        expect(ssoUsersWithoutRoleOf(undefined)).toBe('REFUSE')
        expect(ssoUsersWithoutRoleOf({ ssoUsersWithoutRole: null })).toBe('REFUSE')
        expect(ssoUsersWithoutRoleOf({ ssoUsersWithoutRole: 'SOMETHING' })).toBe('REFUSE')
        expect(ssoUsersWithoutRoleOf({ ssoUsersWithoutRole: 'REFUSE' })).toBe('REFUSE')
        expect(ssoUsersWithoutRoleOf({ ssoUsersWithoutRole: 'CREATE_WITHOUT_ROLE' })).toBe('CREATE_WITHOUT_ROLE')
    })

    it('sends the field only on the default organization and only when it changed', () => {
        const { REFUSE, CREATE_WITHOUT_ROLE } = SsoUsersWithoutRole
        expect(ssoUsersWithoutRoleInput(true, CREATE_WITHOUT_ROLE, REFUSE)).toEqual({ ssoUsersWithoutRole: 'CREATE_WITHOUT_ROLE' })
        expect(ssoUsersWithoutRoleInput(true, REFUSE, CREATE_WITHOUT_ROLE)).toEqual({ ssoUsersWithoutRole: 'REFUSE' })
        expect(ssoUsersWithoutRoleInput(true, REFUSE, REFUSE)).toEqual({})
        expect(ssoUsersWithoutRoleInput(true, CREATE_WITHOUT_ROLE, CREATE_WITHOUT_ROLE)).toEqual({})
        expect(ssoUsersWithoutRoleInput(false, CREATE_WITHOUT_ROLE, REFUSE)).toEqual({})
    })

    it('offers the two choices, Refuse first and marked default', () => {
        expect(SSO_USERS_WITHOUT_ROLE_OPTIONS).toEqual([
            { label: 'Refuse sign-in (default)', value: 'REFUSE' },
            { label: 'Create account without a role', value: 'CREATE_WITHOUT_ROLE' }
        ])
    })

    it('a saved value survives the store commit of the settings response', () => {
        const stored = { uuid: 'o', type: 'DEFAULT', settings: { justificationMandatory: false } }
        const result = { uuid: 'o', settings: { ssoUsersWithoutRole: 'CREATE_WITHOUT_ROLE' } }
        const committed = organizationToCommit(result, true, false, stored)
        expect(committed.type).toBe('DEFAULT')
        expect(ssoUsersWithoutRoleOf(committed.settings)).toBe('CREATE_WITHOUT_ROLE')
    })
})

describe('OrgSettings: the SSO users without a role control', () => {
    const block = find((n) => attribute(n, 'data-testid') === 'sso-users-without-role-block')

    it('is one block inside the General sub-tab of Admin Settings, shown only on the DEFAULT organization', () => {
        expect(block).toHaveLength(1)
        expect(directive(last(block[0]), 'if')).toBe("myorg?.type === 'DEFAULT'")
        const panes = block[0].filter((n: any) => n.tag === 'n-tab-pane')
        expect(panes.map((p: any) => attribute(p, 'name'))).toEqual(['adminSettings', 'general'])
        expect(directive(panes[0], 'if')).toBe('isOrgAdmin')
    })

    it('is a radio group on ssoUsersWithoutRole with one radio per option', () => {
        const group = find((n) => n.tag === 'n-radio-group', last(block[0]))
        expect(group).toHaveLength(1)
        expect(directive(last(group[0]), 'model', 'value')).toBe('ssoUsersWithoutRole')
        const radio = find((n) => n.tag === 'n-radio', last(group[0]))
        expect(radio).toHaveLength(1)
        expect(directive(last(radio[0]), 'for')).toBe('o in SSO_USERS_WITHOUT_ROLE_OPTIONS')
        expect(directive(last(radio[0]), 'bind', 'value')).toBe('o.value')
        expect(code).toMatch(/import \{ SSO_USERS_WITHOUT_ROLE_OPTIONS, SsoUsersWithoutRole, ssoUsersWithoutRoleInput, ssoUsersWithoutRoleOf \} from '@\/utils\/licenseSeats'/)
    })

    it('loads the value and its baseline from the organization settings', () => {
        expect(code).toMatch(/ssoUsersWithoutRole\.value = ssoUsersWithoutRoleOf\(s\)\s*\n\s*ssoUsersWithoutRoleBaseline\.value = ssoUsersWithoutRole\.value/)
    })

    it('saves through updateOrganizationSettings, sending the field only on DEFAULT and only when changed', () => {
        expect(code).toMatch(/\.\.\.ssoUsersWithoutRoleInput\(myorg\.value\?\.type === 'DEFAULT',\s*ssoUsersWithoutRole\.value, ssoUsersWithoutRoleBaseline\.value\)/)
        const doc = gqlDocument('mutation updateOrganizationSettings(')
        expect(doc).toMatch(/settings \{[\s\S]*\bssoUsersWithoutRole\b[\s\S]*\}/)
        expect(code).toMatch(/ssoUsersWithoutRole\.value = ssoUsersWithoutRoleOf\(savedSettings\)\s*\n\s*ssoUsersWithoutRoleBaseline\.value = ssoUsersWithoutRole\.value/)
    })

    it('the organizations query selects the setting in both shapes', () => {
        expect(print(ORGANIZATIONS_CORE)).toMatch(/\bssoUsersWithoutRole\b/)
        expect(print(ORGANIZATIONS_FULL)).toMatch(/\bssoUsersWithoutRole\b/)
    })

    it.runIf(proSchema)('the settings mutation and the organizations query are valid against Pro', () => {
        const pro = proSchema as GraphQLSchema
        const mutation = gqlDocument('mutation updateOrganizationSettings(').replace(/^gql`/, '').replace(/`$/, '')
        expect(validate(pro, parse(mutation)).map(e => e.message)).toEqual([])
        expect(validate(pro, ORGANIZATIONS_CORE).map(e => e.message)).toEqual([])
        expect(validate(pro, ORGANIZATIONS_FULL).map(e => e.message)).toEqual([])
    })
})
