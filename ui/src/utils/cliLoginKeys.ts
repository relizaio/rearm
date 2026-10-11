// Which of the approver's keys the CLI login page offers (CliLogin.vue, "Use one of my keys").

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
