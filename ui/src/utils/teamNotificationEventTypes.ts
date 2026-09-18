/**
 * The event-type picker for a team's owned-component notifications.
 *
 * The record stores an OPT-OUT list -- what the team said no to -- while the
 * picker shows what it will receive. Those are complements, and the conversion
 * between them is the whole behavioural rule of the control, so it lives here
 * rather than in the component: there is no DOM test environment in this repo,
 * and a rule that cannot be tested is a rule that drifts.
 *
 * Why opt-out at all: with a stored inclusion list, an event type that ships
 * later would appear UNSELECTED for every existing team, and those teams would
 * silently stop hearing about a new class of thing happening to what they own.
 * Storing exclusions makes "everything, unless you said otherwise" true for a
 * team saved today and for one saved a year ago.
 */

/**
 * Event types a team can be notified about.
 *
 * Excluded here are the event types whose payload carries no affected component,
 * so an ownership-scoped subscription could never match them -- VEX, and the
 * instance-deployment events (org/instance-scoped, not component-owner scoped).
 * This mirrors NotificationEventType.carriesAffectedComponents on the backend,
 * which drops them from the effective set regardless: offering one here would be
 * a control that provably does nothing, which is what teaches an operator to
 * distrust the ones next to it.
 */
export const EVENT_TYPES_WITHOUT_AFFECTED_COMPONENTS = new Set([
    'VEX_STATE_CHANGED', 'INSTANCE_DEPLOYMENT_CHANGED', 'INSTANCE_DEPLOYMENT_FAILED',
])

export function ownedComponentEventTypes (
    allOptions: Array<{ label: string, value: string, disabled?: boolean }>,
): Array<{ label: string, value: string }> {
    return (allOptions || [])
        .filter(o => o && !EVENT_TYPES_WITHOUT_AFFECTED_COMPONENTS.has(o.value))
        .map(o => ({ label: o.label, value: o.value }))
}

/**
 * What the picker should show as selected, given what the team excluded.
 *
 * An unknown value in the stored exclusion list -- an event type removed from
 * the product since the team was saved -- simply has nothing to hide, so it is
 * ignored rather than treated as an error.
 */
export function selectedFromExcluded (available: string[], excluded: string[] | null | undefined): string[] {
    const out = new Set(excluded || [])
    return (available || []).filter(v => !out.has(v))
}

/**
 * What to store, given what the picker has selected.
 *
 * Derived from the AVAILABLE list rather than by diffing against the previous
 * exclusions, so the stored value can only ever name event types the operator
 * was actually shown. A stale exclusion for a type no longer offered is dropped
 * on the next save, which is the honest outcome: the team cannot have meant to
 * exclude something it was never asked about.
 */
export function excludedFromSelected (available: string[], selected: string[] | null | undefined): string[] {
    const kept = new Set(selected || [])
    return (available || []).filter(v => !kept.has(v))
}

/**
 * The `ownedComponentNotifications` half of an UpdateTeamInput.
 *
 * Extracted for the reason `userGroupUpdateInput.ts` states about its own
 * payload: GraphQL input coercion rejects unknown keys OUTRIGHT and fails the
 * whole mutation, and `scripts/validate-graphql.mjs` validates the document,
 * never the variables. A payload built inline in an SFC is therefore the one
 * part of a mutation nothing in CI can check -- so it lives here, where
 * teamNotificationEventTypes.spec.ts coerces it against the real schema.
 *
 * @param enabled  whether the team wants notifications for what it owns
 * @param available every event type the picker offered
 * @param selected  what the operator left ticked
 */
export function buildOwnedComponentNotificationsInput (
    enabled: boolean,
    available: string[],
    selected: string[] | null | undefined,
): { enabled: boolean, excludedEventTypes: string[] } {
    return {
        enabled: !!enabled,
        excludedEventTypes: excludedFromSelected(available, selected),
    }
}
