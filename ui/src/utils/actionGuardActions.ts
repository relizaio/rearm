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

/**
 * The action.* keys each guarded action is evaluated with -- the server's GuardedAction.actionKeys.
 * A guard reading another action's key errors on every call and so refuses everything; the server
 * refuses it when it is saved (task 3204c981, round 2).
 */
export const GUARD_ACTION_KEYS: Record<string, string[]> = {
    RELEASE_PROMOTION: ['targetLifecycle', 'actor'],
    RELEASE_APPROVAL: ['approvals', 'actor'],
}

/** The action.* keys an expression reads, as the server finds them: not inside has(), which is safe. */
export function actionKeysRead (cel: string | null | undefined): string[] {
    const out: string[] = []
    const re = /(?<!has\()(?<![.\w])action\s*(?:\.\s*([A-Za-z_][A-Za-z0-9_]*)|\[\s*['"]([A-Za-z_][A-Za-z0-9_]*)['"]\s*\])/g
    let m: RegExpExecArray | null
    while ((m = re.exec(cel ?? '')) !== null) {
        const key = m[1] ?? m[2]
        if (!out.includes(key)) out.push(key)
    }
    return out
}

/** The keys an expression reads that the given action does not give it; empty when it fits. */
export function foreignActionKeys (cel: string | null | undefined, action: string | null | undefined): string[] {
    const allowed = GUARD_ACTION_KEYS[action ?? ''] ?? []
    return actionKeysRead(cel).filter(k => !allowed.includes(k))
}

export type GuardSample = { label: string, cel: string, help: string, action: string }

/**
 * The editor's samples. Each carries the action it is written for, and "Use" sets that action too:
 * a sample used under the other action would read keys it is not given and refuse everything.
 */
export const GUARD_SAMPLES: GuardSample[] = [
    {
        label: 'Every dependency has reached Ready to Ship',
        cel: 'release.dependencies.all(d, d.maturity >= 3)',
        help: 'Refuses every forward move — including Draft to Assembled — while any dependency is'
            + ' still at Pending, Draft or Assembled (or Rejected/Cancelled). A dependency that has'
            + ' moved on to Shipped still satisfies it, because the rank asks for "at least Ready to'
            + ' Ship". A release with no dependencies passes: all() over an empty list is true.',
        action: 'RELEASE_PROMOTION',
    },
    {
        label: 'Documents held to a higher standard than code',
        cel: 'release.dependencies.all(d, d.specification == "TEST_PLAN" ? d.maturity >= 3 : d.maturity >= 2)',
        help: 'Dependencies whose component carries the TEST_PLAN specification identifier must have'
            + ' reached Ready to Ship; everything else only has to be Assembled. The rule a single'
            + ' setting cannot express.',
        action: 'RELEASE_PROMOTION',
    },
    {
        label: 'Only the move to Shipped is governed',
        cel: 'action.targetLifecycle != "GENERAL_AVAILABILITY" || release.dependencies.all(d, d.maturity >= 3)',
        help: 'Moves to Assembled and to Ready to Ship are allowed whatever the dependencies are'
            + ' doing; the condition only applies when the promotion target is Shipped. This is how'
            + ' you narrow a guard to one transition instead of all of them.',
        action: 'RELEASE_PROMOTION',
    },
    {
        label: 'Every dependency has Shipped and is still supported',
        cel: 'release.dependencies.all(d, d.maturity >= 4 && d.supported)',
        help: 'Rank 4 is Shipped or later. supported is false at End of Support, End of Life,'
            + ' Rejected and Cancelled, so a dependency that shipped and was later withdrawn fails'
            + ' this — which the rank alone cannot express, since End of Life still ranks 4.',
        action: 'RELEASE_PROMOTION',
    },
    {
        label: 'Every dependency is at least Assembled',
        cel: 'release.dependencies.all(d, d.maturity >= 2)',
        help: 'Nothing still at Pending or Draft underneath this release. The loosest useful bar,'
            + ' and a reasonable first rule to adopt in Warn mode.',
        action: 'RELEASE_PROMOTION',
    },
    {
        label: 'Third-party dependencies are exempt',
        cel: 'release.dependencies.all(d, d.external || d.maturity >= 3)',
        help: 'external is true for releases held against the external-components org. They are not'
            + ' yours to promote, so holding your release to their lifecycle would be a rule nobody'
            + ' in your organization can satisfy.',
        action: 'RELEASE_PROMOTION',
    },
    {
        label: 'A software requirements specification exists and has reached Ready to Ship',
        cel: 'release.dependencies.exists(d, d.specification == "SRS" && d.maturity >= 3)',
        help: 'exists(), not all(): this one demands that such a dependency is actually there, so a'
            + ' release with no dependencies at all is refused rather than passing vacuously.',
        action: 'RELEASE_PROMOTION',
    },
    {
        label: 'A test plan is at exactly Ready to Ship — not still in Draft, not already Shipped',
        cel: 'release.dependencies.all(d, d.specification != "TEST_PLAN" || d.lifecycle == "READY_TO_SHIP")',
        help: 'Lifecycle equality rather than a rank, for the case where one exact state is the rule.'
            + ' A test plan that has moved on to Shipped fails this, which is the point — but it is'
            + ' also why a rank is the better default for most rules.',
        action: 'RELEASE_PROMOTION',
    },
    {
        label: 'Only a person moves a release to Ready to Ship or beyond',
        cel: 'action.targetLifecycle in ["READY_TO_SHIP", "GENERAL_AVAILABILITY"] ? action.actor.kind == "USER" : true',
        help: 'A release promotion guard. An API key, a trigger or the scheduler may still move a release to'
            + ' Assembled; Ready to Ship and Shipped need a signed-in person. action.actor.kind is USER,'
            + ' API_KEY or SYSTEM.',
        action: 'RELEASE_PROMOTION',
    },
    {
        label: 'No key approves a baseline entry',
        cel: '!action.approvals.exists(a, a.entry == "baseline") || action.actor.kind == "USER"',
        help: 'A release approval guard: set Guarded action to Release approval. An approval call that'
            + ' sets the entry named baseline must come from a person; keys may still approve other entries.',
        action: 'RELEASE_APPROVAL',
    },
    {
        label: 'A FREEFORM key may move a release to Assembled and nothing further',
        cel: 'action.actor.keyType != "FREEFORM" || action.targetLifecycle == "ASSEMBLED"',
        help: 'A release promotion guard on what agents\' keys may do: people, other kinds of key and the'
            + ' system are not affected.',
        action: 'RELEASE_PROMOTION',
    },
    {
        label: 'Nothing promotes with an open critical or known-exploited finding',
        cel: 'release.criticalVulns == 0 && release.kevCount == 0',
        help: 'About this release rather than its dependencies. Findings come from scans, so at'
            + ' creation time — before anything has been scanned — the counts are 0 and this passes'
            + ' vacuously; it bites on the promotions that follow.',
        action: 'RELEASE_PROMOTION',
    },
]
