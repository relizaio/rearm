import { editorKeepsScope } from './boardPermissions'
import { apiKeyLabel, apiKeyLabelOf } from './apiKeyLabel'

/**
 * TPF-92 (architecture 4.5): the pure parts of the API Key Groups editor. A group is a permission
 * set that FREEFORM keys of one organization join; its permissions are added to a member key's own
 * at call time.
 */

const byText = (a: string, b: string): number => (a || '').localeCompare(b || '', undefined, { sensitivity: 'base' })

/**
 * The updateApiKeyGroup input. apiKeys REPLACES the member list on the server, so it is always the
 * whole list the editor holds (decision 3). approvals is not sent: the organization-wide approvals
 * travel inside the ORGANIZATION permission row, as the user-group save sends them. status passes
 * through when the editor carries one.
 */
export function buildApiKeyGroupUpdateInput (group: { uuid: string, name?: string, description?: string, apiKeys?: string[], status?: string },
    permissions: any[]): any {
    return {
        groupId: group.uuid,
        name: group.name,
        description: group.description,
        apiKeys: group.apiKeys || [],
        permissions,
        status: group.status
    }
}

/** The ScopedPermissions model as UserGroupPermissionInput rows: the ORGANIZATION row first, NONE rows dropped. */
export function permissionsFromScopedModel (scoped: { orgPermission?: any, scopedPermissions?: any[] } | null | undefined, orgUuid: string): any[] {
    const permissions: any[] = []
    const orgPermission = scoped?.orgPermission
    if (orgPermission?.type && orgPermission.type !== 'NONE') {
        permissions.push({
            scope: 'ORGANIZATION',
            objectId: orgUuid,
            type: orgPermission.type,
            functions: orgPermission.functions || [],
            approvals: orgPermission.approvals || []
        })
    }
    for (const sp of (scoped?.scopedPermissions || [])) {
        if (sp?.type && sp.type !== 'NONE') {
            permissions.push({
                scope: sp.scope,
                objectId: sp.objectId,
                type: sp.type,
                functions: sp.functions || [],
                approvals: sp.approvals || []
            })
        }
    }
    return permissions
}

/**
 * A group's stored permissions as the ScopedPermissions model: the ORGANIZATION row of this
 * organization becomes orgPermission, every row of a scope the editor keeps (BOARD among them)
 * becomes a named scoped row; anything else is ignored.
 */
export async function scopedModelFromPermissions (permissions: any[] | null | undefined, orgUuid: string,
    nameOf: (scope: string, objectId: string) => Promise<string>): Promise<{ orgPermission: any, scopedPermissions: any[] }> {
    let orgPermission: any = { type: 'NONE', functions: [], approvals: [] }
    const scopedPermissions: any[] = []
    for (const p of (permissions || [])) {
        if (!p || p.org !== orgUuid) continue
        if (p.scope === 'ORGANIZATION' && p.object === orgUuid) {
            orgPermission = { type: p.type || 'NONE', functions: p.functions || [], approvals: p.approvals || [] }
        } else if (editorKeepsScope(p.scope)) {
            scopedPermissions.push({
                scope: p.scope,
                objectId: p.object,
                objectName: await nameOf(p.scope, p.object),
                type: p.type,
                functions: p.functions || [],
                approvals: p.approvals || []
            })
        }
    }
    return { orgPermission, scopedPermissions }
}

/**
 * The member picker's options: the organization's FREEFORM keys (no other type may join), each with
 * its status so the picker can tag a key that cannot authenticate yet, sorted by label. Then a
 * fallback option for every selected uuid that is not among them, so a save never drops a member
 * the administrator did not remove: labelled from the group's apiKeyDetails, or as an unknown key.
 */
export function freeformKeyOptions (keys: any[], siblings: any[], selected: string[] = [], details: any[] = []): { label: string, value: string, status?: string }[] {
    const options = (keys || [])
        .filter((k: any) => k?.type === 'FREEFORM')
        .map((k: any) => ({ label: apiKeyLabel(k, { siblings }), value: k.uuid, status: k.status }))
        .sort((a, b) => byText(a.label, b.label))
    const have = new Set(options.map(o => o.value))
    for (const uuid of (selected || [])) {
        if (have.has(uuid)) continue
        const ref = (details || []).find((d: any) => d?.uuid === uuid)
        options.push(ref
            ? { label: apiKeyLabel(ref, { siblings }), value: uuid, status: ref.status }
            : { label: apiKeyLabelOf(uuid, [], 'unknown key'), value: uuid })
        have.add(uuid)
    }
    return options
}

/** The ACTIVE groups holding this key, as { uuid, name }, sorted by name; an inactive group's permissions are not in force. */
export function apiKeyGroupsOfKey (groups: any[] | null | undefined, keyUuid: string | null | undefined): { uuid: string, name: string }[] {
    if (!keyUuid) return []
    return (groups || [])
        .filter((g: any) => g?.status === 'ACTIVE' && (g.apiKeys || []).includes(keyUuid))
        .map((g: any) => ({ uuid: g.uuid, name: g.name }))
        .sort((a, b) => byText(a.name, b.name))
}
