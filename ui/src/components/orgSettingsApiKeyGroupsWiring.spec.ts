import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { parse as parseSfc } from '@vue/compiler-sfc'

// NodeTypes of @vue/compiler-core, which compiler-sfc does not re-export.
const ELEMENT = 1
const ATTRIBUTE = 6
const DIRECTIVE = 7

/**
 * TPF-92 (architecture 4.6, test row 16): the API Key Groups tab, the Free Form Keys Groups column
 * and the key modal's groups binding in OrgSettings. OrgSettings is never mounted in tests, so the
 * SFC is parsed and walked (the orgSettingsKeysWiring.spec.ts helpers); the pane runs in
 * ApiKeyGroupsOfOrg.spec.ts and the helpers in apiKeyGroupInput.spec.ts.
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
const onePane = (name: string): any => {
    const panes = find(org.ast, isPane(name))
    expect(panes).toHaveLength(1)
    return last(panes[0])
}

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
/** One branch of loadTabSpecificData, from its condition to the next branch. */
const tabBranch = (code: string, tab: string): string => {
    const body = definition(code, 'async function loadTabSpecificData')
    const at = body.indexOf(`tabName === "${tab}"`)
    if (at < 0) return ''
    const end = body.indexOf('} else if (tabName ===', at)
    return body.slice(at, end < 0 ? undefined : end)
}

describe('OrgSettings: the API Key Groups tab (TPF-92 row 16)', () => {
    it('(a) is an admin-only pane between User Groups and Teams', () => {
        const pane = onePane('apiKeyGroups')
        expect(directive(pane, 'if')).toBe('isOrgAdmin')
        expect(attribute(pane, 'tab')).toBe('API Key Groups')
        expect(offset(pane)).toBeGreaterThan(offset(onePane('userGroups')))
        expect(offset(pane)).toBeLessThan(offset(onePane('teams')))
    })

    it('(b) mounts ApiKeyGroupsOfOrg with the list, the probe flag, the keys, notify and the picker lists', () => {
        const mounts = find(onePane('apiKeyGroups'), (n) => n.tag === 'ApiKeyGroupsOfOrg')
        expect(mounts).toHaveLength(1)
        const m = last(mounts[0])
        expect(directive(m, 'bind', 'orguuid')).toBe('orgResolved')
        expect(directive(m, 'bind', 'groups')).toBe('apiKeyGroups')
        expect(directive(m, 'bind', 'supported')).toBe('apiKeyGroupsSupported')
        expect(directive(m, 'bind', 'keys')).toBe('programmaticAccessKeys')
        expect(directive(m, 'bind', 'notify')).toBe('notify')
        expect(directive(m, 'bind', 'approval-roles')).toBe('myorg.approvalRoles || []')
        expect(directive(m, 'bind', 'perspectives')).toBe('perspectives')
        expect(directive(m, 'bind', 'products')).toBe('orgProducts')
        expect(directive(m, 'bind', 'components')).toBe('orgComponents')
        expect(directive(m, 'bind', 'instances')).toBe('orgInstances')
        expect(directive(m, 'bind', 'clusters')).toBe('orgClusters')
        expect(directive(m, 'bind', 'boards')).toBe('orgBoards')
        expect(directive(m, 'on', 'changed')).toBe('loadApiKeyGroupsOfOrg')
    })

    it('(c) joins adminOnlyTabs and not proOnlyTabs', () => {
        expect(line(org.code, 'const adminOnlyTabs = [')).toContain("'apiKeyGroups'")
        expect(line(org.code, 'const proOnlyTabs = [')).not.toContain("'apiKeyGroups'")
    })

    it('(d) hands the key modal the groups of the key', () => {
        const modals = find(onePane('programmaticAccess'), (n) => n.tag === 'ApiKeyPermissionsModal')
        expect(modals).toHaveLength(1)
        expect(directive(last(modals[0]), 'bind', 'api-key-groups')).toContain('apiKeyGroupsOfKey(apiKeyGroups,')
    })

    it('(e) adds the Groups column to the Free Form Keys table', () => {
        const fields = columnsSlice(org.code, 'const freeFormKeyFields')
        expect(fields).toContain("key: 'apiKeyGroups'")
        expect(fields).toContain('apiKeyGroupsOfKey(')
    })

    it('(f) loads the groups on Programmatic Access, Free Form Keys and API Key Groups', () => {
        for (const tab of ['programmaticAccess', 'freeFormKeys', 'apiKeyGroups']) {
            expect(tabBranch(org.code, tab), tab).toContain('loadApiKeyGroupsOfOrg()')
        }
    })

    it('(g) shows the server reason when the list fails to load', () => {
        expect(definition(org.code, 'async function loadApiKeyGroupsOfOrg')).toContain('extractError(')
    })
})

describe('OrgSettings: key-side group operations (TPF-124)', () => {
    /** The source of an async function, from its opening to its closing brace at column 0. */
    const functionBody = (code: string, opening: string): string => {
        const at = code.indexOf(opening)
        if (at < 0) return ''
        const end = code.indexOf('\n}', at)
        return code.slice(at, end < 0 ? undefined : end + 2)
    }

    it('(a) puts both icons in the Free Form Keys Manage column, for admins on a server with the operations', () => {
        const fields = columnsSlice(org.code, 'const freeFormKeyFields')
        const admin = fields.indexOf('if (isOrgAdmin.value)')
        const guard = fields.indexOf('keyGroupOpsSupported.value === true && apiKeyGroupsSupported.value')
        const edit = fields.indexOf("'data-testid': 'key-groups-edit'")
        const eye = fields.indexOf("'data-testid': 'key-permissions-eye'")
        expect(admin).toBeGreaterThan(-1)
        expect(guard).toBeGreaterThan(admin)
        expect(edit).toBeGreaterThan(guard)
        expect(eye).toBeGreaterThan(guard)
        expect(fields).toContain('openKeyGroups(row)')
        expect(fields).toContain('keyCombinedTooltipText(row)')
    })

    it('(b) probes at the end of every keys load and never from the tab branches', () => {
        const loader = functionBody(org.code, 'async function loadProgrammaticAccessKeys')
        const assigned = loader.indexOf('programmaticAccessKeys.value =')
        expect(assigned).toBeGreaterThan(-1)
        expect(loader.indexOf('probeKeyGroupOps()')).toBeGreaterThan(assigned)
        expect(tabBranch(org.code, 'programmaticAccess')).not.toContain('probeKeyGroupOps')
        expect(tabBranch(org.code, 'freeFormKeys')).not.toContain('probeKeyGroupOps')
    })

    it('(c) mounts one membership modal in the Programmatic Access pane', () => {
        const mounts = find(onePane('programmaticAccess'), (n) => n.tag === 'ApiKeyGroupMembershipModal')
        expect(mounts).toHaveLength(1)
        const m = last(mounts[0])
        expect(directive(m, 'bind', 'groups')).toBe('apiKeyGroups')
        expect(directive(m, 'bind', 'api-key')).toBe('keyGroupsKey')
        expect(directive(m, 'bind', 'sibling-keys')).toBe('programmaticAccessKeys')
        expect(directive(m, 'bind', 'org-uuid')).toBe('orgResolved')
        expect(directive(m, 'bind', 'notify')).toBe('notify')
        expect(directive(m, 'model', 'show')).toBe('showKeyGroupsModal')
        expect(directive(m, 'on', 'saved')).toBe('onKeyGroupsSaved')
    })

    it('(d) builds the users tooltip through the shared line builder only', () => {
        const at = org.code.indexOf("key: 'permission'")
        const users = org.code.slice(at, org.code.indexOf("'No combined permissions'", at))
        expect(users).toContain('combinedPermissionLines(combinedPerms, permissionNames())')
        expect(org.source.split("p.scope === 'PERSPECTIVE' && p.object")).toHaveLength(1)
    })

    it('(e) clears the per-key combined rows before both loaders read', () => {
        for (const opening of ['async function loadApiKeyGroupsOfOrg', 'async function loadProgrammaticAccessKeys']) {
            const body = definition(org.code, opening)
            const clear = body.indexOf('keyCombinedPermissions.value = {}')
            expect(clear, opening).toBeGreaterThan(-1)
            expect(clear, opening).toBeLessThan(body.indexOf('await'))
        }
    })

    it('(f) probes only for an admin, on a FREEFORM key, and releases the in-flight probe', () => {
        const probe = definition(org.code, 'function probeKeyGroupOps')
        expect(probe).toContain('!isOrgAdmin.value')
        expect(probe).toContain("x.type === 'FREEFORM'")
        expect(probe).toContain('keyGroupOpsProbe = null')
    })

    it('(g) leaves adminOnlyTabs as TPF-92 set it', () => {
        expect(line(org.code, 'const adminOnlyTabs = [')).toBe(
            "const adminOnlyTabs = ['integrations', 'audit', 'users', 'programmaticAccess', 'apiKeyGroups', 'freeFormKeys', 'adminSettings']")
    })

    it('(h) loads the name lists the eye resolves against on both keys tabs', () => {
        for (const tab of ['programmaticAccess', 'freeFormKeys']) {
            const branch = tabBranch(org.code, tab)
            expect(branch, tab).toContain('loadPerspectives()')
            expect(branch, tab).toContain('loadBoardNames()')
            expect(branch, tab).toContain("store.dispatch('fetchProducts', orgResolved.value)")
            expect(branch, tab).toContain('loadProgrammaticAccessKeys(true)')
        }
    })
})

