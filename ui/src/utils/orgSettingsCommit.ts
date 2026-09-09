/**
 * What gets committed to the store after an organization-settings save, and how the toggle
 * is read back out of it.
 *
 * Extracted so the round trip can be TESTED rather than asserted by scanning the component
 * source. The defect these two functions exist to prevent was invisible to every unit test
 * and every source scan: the mutation response is missing a field, the store commit replaces
 * the organization with it, and the next render of the page reads the gap as "off".
 */

/** The support-injection value a settings payload implies. */
export function supportInjectionFromSettings (settings: { supportInjection?: string } | null | undefined): boolean {
    // ENABLED is the only truthy value; anything else -- DISABLED, null, unset, or a state
    // this build does not know -- reads as off, which matches the server's own default rule.
    return settings?.supportInjection === 'ENABLED'
}

/** The two values this settings screen can write. */
export type SupportInjectionState = 'ENABLED' | 'DISABLED'

/**
 * The organization to commit after a settings save.
 *
 * `updateOrganizationSettings` returns a PARTIAL organization: it selects the settings it
 * writes and little else. `UPDATE_ORGANIZATION` REPLACES the stored organization with what it
 * is given, so committing that response raw DELETES every field the mutation did not select.
 * The observed symptom was the support toggle reading OFF against an ENABLED backend, but the
 * cause is general -- `type` and `approvalRoles` are in the store's own organizations query and
 * are not selected here either, so the same commit silently downgraded the org to DEFAULT for
 * the invite-user form and emptied the Approval Roles table until a reload.
 *
 * So this MERGES over the organization already in the store rather than grafting one field
 * onto the response. Anything the mutation returned wins, because it is what the server just
 * accepted; anything it did not mention keeps the value it had, because a field a PATCH did
 * not mention is a field the PATCH did not change.
 *
 * `supportInjection` is then written from the value that was SENT, because the mutation
 * deliberately does not select it back: selecting it makes the whole document invalid against
 * a CE backend that does not declare the field, which would fail every save including the four
 * prose slots. Asserting it here is the same claim the save path already makes when it advances
 * its own baseline -- a mutation that did not throw accepted what it was sent. When the field is
 * unsupported nothing is written: on a CE mirror there is no such setting and inventing one
 * would state a choice nobody made.
 *
 * @param stored the organization currently in the store, or null/undefined if there is none
 */
export function organizationToCommit (
    result: any, supported: boolean, enabled: boolean, stored?: any
): any {
    if (!result) return result
    const merged = { ...(stored ?? {}), ...result }
    // Settings merge one level deeper: the response carries only the settings it wrote.
    merged.settings = { ...(stored?.settings ?? {}), ...(result?.settings ?? {}) }
    if (supported) {
        merged.settings.supportInjection = (enabled ? 'ENABLED' : 'DISABLED') as SupportInjectionState
    }
    return merged
}
