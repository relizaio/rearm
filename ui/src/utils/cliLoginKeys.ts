// Which of the approver's keys the CLI login page offers (CliLogin.vue, "Use one of my keys").
import constants from '@/utils/constants'

/** Agents an ApiKey is bound to: the root agents that have signed in with it (ApiKey.boundAgents). */
export function boundAgentsOf (key: any): any[] {
    return Array.isArray(key?.boundAgents) ? key.boundAgents : []
}

/** A key an agent already signs in with: approving a new session on it would act as that agent. */
export function isBoundToAgent (key: any): boolean {
    return boundAgentsOf(key).length > 0
}

/**
 * The keys a CLI session may act as: active personal keys and held Free Form keys, in the order given.
 * Keys already bound to an agent are left out unless showBound, so the list a person sees first is the
 * keys that are theirs to hand to a new session.
 */
export function keysToOffer (keys: any[], showBound: boolean): any[] {
    const eligible = (keys || []).filter(k => k?.status === 'ACTIVE' && (k.type === 'USER' || k.type === 'FREEFORM'))
    return showBound ? eligible : eligible.filter(k => !isBoundToAgent(k))
}

/** "bound to builder, reviewer": the agents' names, for the label of a bound key. */
export function boundAgentsLabel (key: any): string {
    const names = boundAgentsOf(key).map(a => a?.name || a?.uuid).filter(Boolean)
    return names.length ? 'bound to ' + names.join(', ') : ''
}

/** A permission's rank: its index in the shared PermissionType order (NONE lowest); a type not listed ranks below NONE. */
const strengthOf = (type: string): number => constants.PermissionTypesWithAdmin.indexOf(type)

/**
 * "1 permission, READ_WRITE" or "3 permissions, highest ADMIN": how much a key grants, in one phrase. When the
 * highest grant sits on one object rather than the organization, say so ("ADMIN on one component"), so a
 * component admin does not read as an org admin on the approval screen.
 */
export function permissionSummary (key: any): string {
    const perms: any[] = key?.permissions?.permissions ?? []
    if (!perms.length) return 'no permissions'
    const top = perms.reduce((best, p) => {
        const d = strengthOf(String(p?.type ?? '')) - strengthOf(String(best?.type ?? ''))
        return d > 0 || (d === 0 && p?.scope === 'ORGANIZATION' && best?.scope !== 'ORGANIZATION') ? p : best
    })
    const where = top?.scope && top.scope !== 'ORGANIZATION' ? ` on one ${String(top.scope).toLowerCase()}` : ''
    const highest = String(top?.type ?? '') + where
    return perms.length === 1 ? `1 permission, ${highest}` : `${perms.length} permissions, highest ${highest}`
}
