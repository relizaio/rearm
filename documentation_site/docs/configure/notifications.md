---
sidebarDepth: 2
---

# Notifications

::: warning ReARM Pro only
Everything on this page -- channels, subscriptions, routes, and KEV alerts --
is part of ReARM Pro's security-and-operational notification framework,
configured under **Organization Settings -> Integrations -> Notifications**.
This is separate from the CE-level Slack/Teams **release** notifications
described on the [Slack](../integrations/slack) and [Microsoft
Teams](../integrations/msteams) pages -- see the callout on the Microsoft
Teams page if you're not sure which one you need.
:::

## How it fits together

1. A **channel** is a named destination: Slack, Microsoft Teams, a generic
   webhook, email, or [Microsoft Sentinel](../integrations/sentinel). Add one
   from the **Integrations -> Catalog** tab -- each channel type is a card
   there; click **Add** on the card to configure a destination.
2. A **subscription** decides which events go to which channels. Add one from
   the **Notifications -> Subscriptions** tab: pick the event types you care
   about, an optional filter, and one or more **routes** -- each route pairs a
   minimum severity with a set of channels (or channel groups).
3. A **channel group** (Notifications -> Channel Groups) is just a named,
   reusable list of channels you can reference from a route instead of
   repeating the same channels on every subscription.

Nothing is delivered until both a channel *and* a subscription routing to it
exist -- adding a channel alone does not send you anything.

## Event types

A subscription's `eventTypes` list controls what it can match:

| Event | Fires when |
|---|---|
| `NEW_VULN_AFFECTS_RELEASES` | A vulnerability is newly linked to one of your releases |
| `VULNERABILITY_RECORD_UPDATED` | An existing vulnerability record changes -- including a CVE newly appearing on the KEV catalog (see [KEV notifications](#kev-known-exploited-vulnerabilities-notifications) below) |
| `VEX_STATE_CHANGED` | Reserved for VEX (exploitability) status changes. No event source emits this yet, so a subscription on it will not fire today -- prefer `VULNERABILITY_RECORD_UPDATED` for vulnerability-state changes |
| `RELEASE_CREATED` | A new release is created |
| `RELEASE_LIFECYCLE_CHANGED` | A release moves lifecycle stage |
| `RELEASE_BOM_DIFF` | A release's BOM diff is computed |
| `APPROVAL_REQUESTED` | Someone requests approval on a release -- see [Approval queues](./approval-queues) |
| `APPROVAL_RESOLVED` | An approval request is satisfied or disapproved |

## Filters, severity, and routes

- A subscription can carry an optional filter, built either with the preset
  toggles or as an advanced expression. Advanced filters run under limits
  (size, nesting depth, iteration count, and a short evaluation timeout) so a
  runaway expression can't stall delivery for the rest of the org -- a filter
  that exceeds its budget fails that one delivery rather than blocking others.
- Each route on a subscription sets a minimum severity (`CRITICAL` / `HIGH` /
  `MEDIUM` / ...); only events at or above that threshold on that route are
  sent to its channels.

## Duplicate delivery protection

ReARM deduplicates deliveries to the same channel from the same subscription
within a rolling window (configurable per subscription; 24 hours by
default), so a flapping upstream signal won't repeat-fire the same alert to
the same channel over and over. Test/synthetic events (see
[Testing](#testing-channels-and-subscriptions) below) intentionally bypass
dedup, so a test always produces a visible delivery.

::: warning Per-subscription rate limit is not enforced yet
The subscription rate-limit fields (`maxPerWindow` / `windowMinutes`) are
present and saved, but do not currently throttle delivery volume at
fan-out -- setting them has no runtime effect. This is a known, deliberate
gap, not a bug you need to work around; if you're relying on a specific
delivery cap today, use your destination's own rate limiting (e.g. a Slack
app's built-in limits) in the meantime.
:::

## Retention

Notification history (deliveries and inbox rows) is retained for a
configurable number of days per organization, with an enforced 14-day floor --
an org can't set retention short enough to delete a row that might still be
scheduled to send (e.g. one parked in an email digest).

## Testing channels and subscriptions

You can confirm a channel or subscription works without waiting for a real
event, straight from the UI:

- **Test a channel** -- on the channel's card in **Integrations -> Catalog**,
  use the **Send test** (paper-plane) action on the configured instance. This
  pings that one channel directly, bypassing subscription matching, filters,
  and severity gates, so it always produces a visible delivery -- the fastest
  way to confirm a webhook URL is reachable and authorized.
- **Test a subscription** -- in **Integrations -> Subscriptions**, use the
  **Test** action on a subscription row and pick a synthetic scenario (e.g. a
  critical vulnerability on a single shipped release, a newly-KEV-listed CVE).
  The event is injected through the normal subscription-matching path, so you
  can confirm your filters, severity gates, and routes behave the way you
  expect. Note this injects a real synthetic event for the whole organization,
  so any *other* active subscription matching the same event type will also
  fire -- the UI asks you to confirm before sending.

Both bypass the dedup window described above, so a test always produces a
delivery you can see in **Delivery History**.

## KEV (Known Exploited Vulnerabilities) notifications

ReARM can alert you when a vulnerability affecting your releases is added to
a Known Exploited Vulnerabilities catalog. Two sources are supported:

- **CISA KEV** -- the public CISA catalog, enabled by default for every
  organization, no credential required.
- **VulnCheck KEV** -- opt-in, requires your own VulnCheck API token
  (Organization Settings -> Integrations).

When a CVE is newly added to an enabled KEV source **and** it affects one of
your existing vulnerability records, ReARM emits a `VULNERABILITY_RECORD_UPDATED`
event your subscriptions can route on.

::: tip You won't get flooded with historical KEV alerts on day one
The *first* KEV sync for a newly enabled source treats every existing catalog
entry as a baseline rather than "newly added," so enabling CISA KEV (or
VulnCheck KEV) doesn't fire years of historical listings at you all at once.
Only CVEs that become KEV-listed *after* that first sync trigger a
notification.
:::

A CVE dropping off a KEV catalog does not currently un-list it on ReARM's
side -- KEV status is treated as sticky once observed, so a temporary upstream
catalog issue can't make your org's KEV picture shrink unexpectedly.
