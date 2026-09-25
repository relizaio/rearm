// The actions a guard can govern, and what an expression may ask about each. Kept in a util with a
// spec: an option list built inline in a component renders blank when it breaks, and nothing else
// notices (see rearm#389).

export type Option = { label: string, value: string }

export const GUARDED_ACTION_OPTIONS: Option[] = [
    { label: 'Release promotion — moving a release forward through its lifecycle', value: 'RELEASE_PROMOTION' },
    { label: 'Release approval — recording approvals, from a person or an API key', value: 'RELEASE_APPROVAL' },
]

export function guardedActionLabel (action: string | null | undefined): string {
    if (action === 'RELEASE_PROMOTION') return 'Release promotion'
    if (action === 'RELEASE_APPROVAL') return 'Release approval'
    return action ?? ''
}

export type VariableDoc = { name: string, snippet: string, display: string, desc: string }

// action.* exists only where a guard is being written, so it is passed in rather than living in
// the shared variable list the release rules also use.
export const GUARD_VARIABLE_DOCS: VariableDoc[] = [
    {
        name: 'action.targetLifecycle',
        snippet: 'action.targetLifecycle == "READY_TO_SHIP"',
        display: 'action.targetLifecycle',
        desc: 'string — release promotion only: the lifecycle being moved to. release.lifecycle is still the one being left, which is what lets a guard govern one transition and leave the rest alone.'
    },
    {
        name: 'action.approvals',
        snippet: 'action.approvals.exists(a, a.entry == "baseline")',
        display: 'action.approvals',
        desc: 'list — release approval only: the entries set in this call, each {entry, entryUuid, role, state}; entry is the approval entry\'s name.'
    },
    {
        name: 'action.actor.kind',
        snippet: 'action.actor.kind == "USER"',
        display: 'action.actor.kind',
        desc: 'string — who is acting: USER (a signed-in person), API_KEY, or SYSTEM (a trigger, the scheduler, anything automated).'
    },
    {
        name: 'action.actor.keyType',
        snippet: 'action.actor.keyType != "FREEFORM"',
        display: 'action.actor.keyType',
        desc: 'string — for an API key, its type (FREEFORM, USER, FEDERATED, CLUSTER, COMPONENT, …); empty otherwise.'
    },
    {
        name: 'action.actor.id',
        snippet: 'action.actor.id',
        display: 'action.actor.id',
        desc: 'string — the user\'s or the key\'s uuid; empty for SYSTEM.'
    },
    {
        name: 'action.actor.ip',
        snippet: 'action.actor.ip',
        display: 'action.actor.ip',
        desc: 'string — the caller\'s address, or empty.'
    },
]
