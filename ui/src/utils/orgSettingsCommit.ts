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
export function supportInjectionFromSettings (settings: any): boolean {
    // ENABLED is the only truthy value; anything else -- DISABLED, null, unset, or a state
    // this build does not know -- reads as off, which matches the server's own default rule.
    return settings?.supportInjection === 'ENABLED'
}

/**
 * The organization to commit after a save, carrying the support-injection value the server
 * just ACCEPTED.
 *
 * `updateOrganizationSettings` deliberately does not SELECT supportInjection: adding it makes
 * the whole document invalid against a CE backend that does not declare the field, which would
 * fail every save including the four prose slots. But the store commit REPLACES the stored
 * organization, so committing the raw response drops the field out of the store, and the next
 * hydration of this page reads undefined and renders OFF while the backend holds ENABLED.
 * Worse, the mutation only sends the field when it differs from that baseline, so DISABLED
 * then cannot be sent at all without a hard reload -- the operator turns the disclosure on and
 * this screen tells them it is off.
 *
 * Grafting is the same claim the save path already makes when it advances its baseline: a
 * mutation that did not throw accepted what it was sent. When the field is unsupported the
 * value must stay ABSENT rather than be written as DISABLED -- on a CE mirror there is no such
 * setting, and inventing one would state a choice nobody made.
 */
export function organizationToCommit (result: any, supported: boolean, enabled: boolean): any {
    if (!supported || !result) return result
    return {
        ...result,
        settings: {
            ...(result?.settings ?? {}),
            supportInjection: enabled ? 'ENABLED' : 'DISABLED'
        }
    }
}
