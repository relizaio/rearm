// Edition (CE vs Pro) availability policy for the Integrations page: which
// notification channel types this edition may create, and which sub-tabs it may
// open. Both are edition questions asked by OrgIntegrations.vue; keeping them
// together is what lets the component hold no edition logic of its own.
//
// WHAT THE UI ACTUALLY KNOWS. The backend's licence gate is
// LicensingConstants.isOssEdition(), a COMPILE-TIME constant baked into the
// build (the CE jar returns literal `true`; the Pro jar compares an embedded
// PEM). That value is never served to the browser. The only edition signals on
// the GraphQL User type are `installationType` and `isLicenseValid`, so this
// file uses `installationType` as a PROXY for the edition.
//
// The two signals are independent and CAN disagree -- a Pro build configured
// with RELIZAPROP_INSTALLATION_TYPE=OSS reports OSS to the UI while still
// accepting Pro-only channels. That is safe in practice because the CE Helm
// chart hardcodes `RELIZAPROP_INSTALLATION_TYPE: "OSS"` (not templated, so a CE
// deployment cannot report anything else), and a mismatch fails in the harmless
// direction: the UI hides a capability the backend would have allowed. Do not
// rely on this to be exact; a served capability field would be the real fix.
//
// WHY IT IS CENTRALISED. The backend rejects EMAIL and SENTINEL on CE in
// NotificationChannelService.validateSeed:
//
//     // CE/Pro split: EMAIL and SENTINEL destinations are Pro-only.
//     // Slack / Teams / Webhook are available on the CE (OSS) edition.
//     if (LicensingConstants.isOssEdition()
//             && (seed.getType() == IntegrationType.EMAIL
//                 || seed.getType() == IntegrationType.SENTINEL)) { throw ... }
//
// Drift in EITHER direction is a bug the UI alone can produce:
//   - UI stricter than backend -> a capability the customer already has is
//     invisible. That is the complaint that prompted this file: CE shipped
//     working Slack/Teams/Webhook dispatchers behind a "Pro" pill.
//   - UI looser than backend -> the card is enabled, the operator fills in the
//     whole form, and the save fails with a licence error. Worse than hiding it.
//
// Keeping the policy in one tested module is what makes the next channel type a
// one-line decision instead of a grep across template conditionals.

/** Channel types the backend rejects on the CE (OSS) edition. */
export const PRO_ONLY_CHANNEL_TYPES: readonly string[] = ['EMAIL', 'SENTINEL']

/**
 * `NotificationRouteInput` fields that exist ONLY in the Pro schema.
 *
 * A different failure mode from the channel list above, and a nastier one:
 * GraphQL input coercion rejects unknown keys outright, so sending one of these
 * from a CE UI fails the WHOLE subscription mutation -- including saves that do
 * not use the field at all. See buildNotificationRouteInput in
 * notificationsCommon, which strips them, and routeInputSchemaDrift.spec.ts,
 * which coerces the result against both real schemas.
 */
export const PRO_ONLY_ROUTE_FIELDS: readonly string[] = ['teams']

/**
 * Pro (licensed) edition, as far as the UI can tell. Every non-OSS
 * installationType -- SAAS, DEMO, MANAGED_SERVICE -- is licensed; only the
 * literal 'OSS' value marks CE. (Those four are the whole InstallationType
 * enum, CommonVariables.InstallationType.)
 *
 * An absent installationType is treated as Pro, matching the
 * `installationType !== 'OSS'` idiom used throughout this UI. That is the
 * PERMISSIVE default -- it shows paid affordances rather than hiding them -- so
 * it is only safe where the caller guarantees the user record is loaded. Every
 * current caller does: OrgSettings mounts these surfaces behind `isOrgAdmin`,
 * which already requires a loaded user.
 */
export function isProEdition (installationType: string | undefined | null): boolean {
    return installationType !== 'OSS'
}

/**
 * Is this notification channel type creatable on this edition?
 *
 * @param channelType the BACKEND type name (IntegrationType), not the catalog
 *                    card id -- they differ for Teams (card 'MSTEAMS' vs type
 *                    'MS_TEAMS'). Callers must map through channelTypeForCard.
 */
export function isChannelTypeAvailable (channelType: string, installationType: string | undefined | null): boolean {
    if (isProEdition(installationType)) return true
    return !PRO_ONLY_CHANNEL_TYPES.includes(channelType)
}

// ---- Integrations sub-tabs -------------------------------------------------

export type IntegrationsSubTab =
    'catalog' | 'webhooks' | 'pr-validation' | 'subscriptions' | 'channel-groups'

export const INTEGRATIONS_SUBTABS: readonly IntegrationsSubTab[] =
    ['catalog', 'webhooks', 'pr-validation', 'subscriptions', 'channel-groups']

/**
 * Sub-tabs that need the CI/SCM surface (inbound PR webhooks, PR validation
 * rules). Everything else -- including subscriptions and channel groups -- is
 * available on CE, because a channel with no subscription routing events to it
 * delivers nothing.
 */
export const CI_ONLY_SUBTABS: readonly IntegrationsSubTab[] = ['webhooks', 'pr-validation']

/**
 * Accepts a sub-tab only if it is REAL and this edition can render it.
 *
 * Both halves matter, and for the same reason: anything else leaves a blank
 * body. An unknown value (`?integrationsTab=bogus`) matches no pane's `v-if`
 * and highlights no pill; so does a CI tab on CE. vue-router yields an ARRAY
 * for a repeated query param, so this tests membership rather than trusting a
 * cast.
 */
export function isIntegrationsSubTabAvailable (
    tab: unknown,
    installationType: string | undefined | null,
): tab is IntegrationsSubTab {
    if (!INTEGRATIONS_SUBTABS.includes(tab as IntegrationsSubTab)) return false
    if (!CI_ONLY_SUBTABS.includes(tab as IntegrationsSubTab)) return true
    return isProEdition(installationType)
}
