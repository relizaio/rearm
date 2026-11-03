import commonFunctions from '@/utils/commonFunctions'

/** The name lookups the line builder needs; OrgSettings supplies them over its own lists (permissionNames()). */
export interface PermissionNameResolvers {
    orgUuid: string
    orgName: () => string | undefined
    perspectiveName: (uuid: string) => string | undefined
    boardName: (uuid: string) => string | undefined
    component: (uuid: string) => { name?: string, type?: string } | undefined
}

/**
 * One combined-permission row as the Users tab's eye rendered it (TPF-124; the inline builder of
 * OrgSettings before this task), byte for byte: "<SCOPE>: <object name> - <translated level>", the
 * object name resolved by scope (this organization's ORGANIZATION row -> the org name; PERSPECTIVE,
 * BOARD, COMPONENT -> their names; a COMPONENT of type PRODUCT displays as PRODUCT), the raw object
 * uuid when no name is known.
 */
export function permissionLine (p: any, names: PermissionNameResolvers): string {
    let objectName = p.object
    let scopeDisplay = p.scope
    if (p.scope === 'ORGANIZATION' && p.object === names.orgUuid) {
        objectName = names.orgName() || p.object
    } else if (p.scope === 'PERSPECTIVE' && p.object) {
        objectName = names.perspectiveName(p.object) || p.object
    } else if (p.scope === 'BOARD' && p.object) {
        objectName = names.boardName(p.object) ?? p.object
    } else if (p.scope === 'COMPONENT' && p.object) {
        const comp = names.component(p.object)
        objectName = comp?.name || p.object
        if (comp?.type === 'PRODUCT') {
            scopeDisplay = 'PRODUCT'
        }
    }
    return `${scopeDisplay}: ${objectName} - ${commonFunctions.translatePermissionName(p.type)}`
}

/** One line per row, in the received order; [] for none (the caller supplies its "none" text). */
export function combinedPermissionLines (perms: any[] | null | undefined, names: PermissionNameResolvers): string[] {
    return (perms || []).map((p: any) => permissionLine(p, names))
}

/**
 * One source of a row: "own" or group "<name>" (the uuid when the name is missing), followed by that
 * source's translated level only when it differs from the row's effective level.
 */
export function sourceLabel (s: any, rowType: string | null | undefined): string {
    const who = s?.kind === 'GROUP' ? `group "${s.groupName || s.groupUuid}"` : 'own'
    const level = s?.type && s.type !== rowType ? ' ' + commonFunctions.translatePermissionName(s.type) : ''
    return who + level
}

/** The key tooltip's lines: the users line plus " (<sources>)"; a row without sources has no suffix. */
export function sourcedPermissionLines (rows: any[] | null | undefined, names: PermissionNameResolvers): string[] {
    return (rows || []).map((r: any) => {
        const sources = (r.sources || []).map((s: any) => sourceLabel(s, r.type))
        return permissionLine(r, names) + (sources.length ? ` (${sources.join(', ')})` : '')
    })
}
