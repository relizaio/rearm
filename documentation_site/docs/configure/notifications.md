---
sidebarDepth: 2
---

# Notifications

The security-and-operational notification framework is configured under
**Organization Settings -> Integrations**. It is separate from the Slack/Teams
**release** notifications described on the [Slack](../integrations/slack) and
[Microsoft Teams](../integrations/msteams) pages -- see the callout on the
Microsoft Teams page if you're not sure which one you need.

::: tip What Community Edition includes
Subscriptions, routes, and channel groups are available on **both** editions,
as are the **Slack**, **Microsoft Teams**, and **Webhook** channel types.

Pro adds two channel types -- **Email** and
[**Microsoft Sentinel**](../integrations/sentinel) -- two route targets,
**teams** and **notify the component owner**, and
[subscription filtering](#filters-severity-and-routes).
:::

## How it fits together

1. A **channel** is a named destination: Slack, Microsoft Teams, a generic
   webhook, email, or [Microsoft Sentinel](../integrations/sentinel). Add one
   from the **Integrations -> Catalog** tab -- each channel type is a card
   there; click **Add** on the card to configure a destination.
2. A **subscription** decides which events go where. Add one from the
   **Integrations -> Subscriptions** tab: pick the event types you care about,
   an optional filter, and one or more **routes** -- each route pairs a minimum
   severity with one or more [targets](#route-targets).
3. A **channel group** (Integrations -> Channel groups) is just a named,
   reusable list of channels you can reference from a route instead of
   repeating the same channels on every subscription.

Nothing is delivered until both a channel *and* a subscription routing to it
exist -- adding a channel alone does not send you anything.

## Event types

A subscription's `eventTypes` list controls what it can match:

| Event | Fires when |
|---|---|
| `NEW_VULN_AFFECTS_RELEASES` | A vulnerability record is created for your organization **and** at least one of your releases carries it. The affected releases are resolved at delivery time, not when the record is written, so an event whose set is still empty is retried for a short while and then withheld rather than delivered (see [below](#vulnerability-events-that-affect-nothing)) |
| `VULNERABILITY_RECORD_UPDATED` | An existing vulnerability record changes -- including a CVE newly appearing on the KEV catalog (see [KEV notifications](#kev-known-exploited-vulnerabilities-notifications) below) |
| `VEX_STATE_CHANGED` | Reserved for VEX (exploitability) status changes. No event source emits this yet, so a subscription on it will not fire today -- prefer `VULNERABILITY_RECORD_UPDATED` for vulnerability-state changes |
| `RELEASE_CREATED` | A new release is created |
| `RELEASE_LIFECYCLE_CHANGED` | A release enters `DRAFT`, `ASSEMBLED`, `CANCELLED` or `REJECTED`. **Only those four**, matching what the older Slack/Teams release notifications sent -- later stages such as `READY_TO_SHIP`, `GENERAL_AVAILABILITY` and the end-of-life stages emit nothing |
| `RELEASE_BOM_DIFF` | A release's BOM diff is computed |
| `APPROVAL_REQUESTED` | Someone requests approval on a release -- see [Approval queues](./approval-queues) |
| `APPROVAL_RESOLVED` | An approval request is satisfied or disapproved |

## Filters, severity, and routes

- A subscription can carry an optional filter, built either with the preset
  toggles or as an advanced expression. Filters run under limits (size, nesting
  depth, iteration count, and a 50 ms evaluation budget) so a runaway
  expression can't stall delivery for the rest of the org. A filter that
  exceeds its budget -- or fails to evaluate for any other reason -- causes
  that subscription to be **skipped for that event**; other subscriptions on
  the same event are unaffected.

  Note that a skip is silent: no delivery row is written, so a filter that
  never evaluates shows up in [Delivery History](#delivery-history) as
  *nothing at all*, not as a failure. If a subscription you expect to fire
  produces no rows whatsoever, an unevaluatable filter is a candidate cause.
- Each route on a subscription sets a minimum severity (`CRITICAL` / `HIGH` /
  `MEDIUM` / ...); only events at or above that threshold on that route are
  sent to its targets.
- A route can also carry a **perspectives** list. Left empty it means "any
  perspective". Set, it gates delivery: the event only goes out on that route
  when an affected release's component belongs to one of the named
  perspectives. This gates **delivery only** -- it does not change what appears
  in the in-app inbox or the bell.

::: warning Filters are not applied on Community Edition
Filter evaluation is a Pro capability. On CE the evaluator is absent, and
rather than dropping subscriptions it cannot evaluate, ReARM delivers them
**unfiltered** -- a filter you save is accepted and then ignored, so the
subscription fires on every event of its type.

This applies to the preset toggles as well as advanced expressions, since the
toggles compile down to the same expression. Minimum severity, event types and
route targets are all still enforced on CE; only the filter is not.
:::

### Route targets

A route can deliver to any combination of these. A route with no target at all
delivers nothing, so the editor will not let you save one.

| Target | Delivers to | Edition |
|---|---|---|
| **Channels** | The channels you name explicitly | CE and Pro |
| **Channel groups** | Every channel in the named group | CE and Pro |
| **Teams** | The named team's own notification channels | Pro |
| **Notify the component owner** | The channels of whichever team owns the affected component | Pro |

The last two are what let a route describe *who* should hear about something
rather than *where* to send it. That distinction matters most for owner
routing, described next.

### Notifying the component owner

Tick **notify the component owner** on a route and you do not name a
destination at all. When an event arrives, ReARM works out which components it
affects, resolves each one's owner, and delivers to that owner team's channels.

Because this is resolved at delivery time rather than stored on the
subscription, the route keeps working when ownership changes: reassign a
component to a different team, or change an
[assignment rule](./component-ownership#assignment-rules), and the next event
goes to the new owner with no edit to the subscription.

For a delivery to happen, the affected component's owner must be:

- a **team** (an individual owner has no channels of its own -- see
  [Component ownership](./component-ownership)), and
- in the `OWNED` or `NON_DURABLE` state (a `DEGRADED`, `UNSET`, or `ORPHANED`
  component has no usable owner), and
- a team that has at least one notification channel configured.

If none of those hold, the route contributes no delivery. It does **not** fall
back to sending anywhere else, so an unowned component is silent rather than
noisy -- worth remembering when a test produces nothing (see
[Testing](#testing-channels-and-subscriptions)).

One event can affect several components with different owners; each owner team
is resolved independently and every one of them is notified.

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

## Email digest batching

Email channels do not send one message per event by default. They apply a
**rolling cap**: the first non-actionable event after a quiet period sends
immediately and anchors a window; anything arriving within the configured
interval of that send is held and flushed as a **single digest email** when
the window expires.

Configure it on the Email channel itself (**Integrations -> Catalog ->
Email**):

- **Digest** (the default) -- batch routine notifications into at most one
  email per interval. The interval ranges from **every 5 minutes** to
  **weekly**; the accepted bounds are 5 minutes to 7 days.
- **Immediate** -- disable batching; every event sends its own email.

The channel card shows the current setting as a chip, so you can see at a
glance which mode a channel is in.

Two things always bypass the digest and send immediately regardless of mode:

- **Approval events** (`APPROVAL_REQUESTED`, `APPROVAL_RESOLVED`). These ask a
  specific person to act now, so holding them for a digest window would defeat
  the point.
- **Test and synthetic events** -- see the caveat below.

Only EMAIL channels batch. Slack, Microsoft Teams, webhook, and Sentinel
channels always dispatch as soon as the worker picks the delivery up.

::: warning The default is a 24-hour digest
An email channel created without touching these settings is in **rolling
digest mode with a 24-hour interval**. That means the first event of a window
arrives immediately and everything after it can be held for up to a day.

This surprises people who expect an alerting channel to be chatty by default.
If you want per-event email, set the channel to **Immediate** explicitly, or
shorten the interval.
:::

::: warning A channel test will not show you digest behaviour
Test and synthetic events skip the digest unconditionally, so **Send test**
always produces an immediate email. A channel can therefore pass its test
perfectly while real traffic is being held for up to 24 hours.

To see batching, cause two real events in quick succession and watch the
second land in [Delivery History](#delivery-history) as `BATCHED`.
:::

## Delivery History

**Organization Settings -> Audit -> Delivery History** lists every delivery
ReARM has attempted, with the channel, the subscription that caused it, the
attempt count, and any error.

One event produces **one row per (subscription, channel) pair**. That is worth
internalising, because it explains the most common "why did I get this twice?"
question: two subscriptions that both route to the same channel produce two
rows and two messages. [Duplicate protection](#duplicate-delivery-protection)
is scoped per subscription, so it does not collapse them. This is easy to hit
without noticing when one subscription names a channel directly and another
reaches the same channel indirectly through a team or the
[component owner](#notifying-the-component-owner).

The **Origin** column separates `REAL` traffic from `SYNTHETIC` rows produced
by the test actions, so a history full of test runs stays legible.

Statuses you will see:

| Status | Meaning |
|---|---|
| `PENDING` | Queued; the channel worker has not attempted it yet |
| `SENT` | The channel transport accepted it |
| `BATCHED` | Held in an email channel's [digest window](#email-digest-batching); flushed with the next digest. All rows in one digest flip to `SENT` together, sharing a timestamp |
| `FAILED` | Retries exhausted, or a non-retriable error. Terminal |
| `ACKED` | Acknowledged by a user in the in-app inbox |

An event that produced **no rows at all** is a different situation from a
failed row, and the outbox event's own status tells you which: `FANNED_OUT`
with no deliveries means "offered, and no subscription wanted it", while
`SUPPRESSED` means ReARM withheld it deliberately -- today that means a
[vulnerability event naming no release](#vulnerability-events-that-affect-nothing).

## Vulnerability events that affect nothing

A vulnerability record is written the moment ReARM learns the CVE is new to
your organization, but the link from that CVE to your releases lives in
artifact metrics, which are written slightly later. So at the instant the
event is produced, "which releases does this affect?" genuinely has no answer
yet.

ReARM therefore resolves the affected releases at **delivery** time. If the set
comes back empty, the event is not delivered immediately and not discarded --
it is deferred and retried (roughly 30s, then 60s, then every 2 minutes). Only
if the set is still empty after those attempts is the event **withheld**, on
the grounds that a vulnerability notification naming no release is not
actionable.

Withheld events are recorded as `SUPPRESSED` rather than failed. That is
deliberately distinct from an event that fanned out and simply matched no
subscription: the first means "we chose not to send this", the second means
"nobody asked for it".

## Retention

Notification history (deliveries and inbox rows) is retained for a
configurable number of days per organization -- **90 by default**, and settable
anywhere from **14 to 730**. The floor exists so an org can't set retention
short enough to delete a row that might still be scheduled to send (e.g. one
parked in an email digest).

There is no screen for this yet: retention is set through the
`updateOrganizationSettings` GraphQL mutation (`notificationRetentionDays`),
not from Organization Settings.

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

::: warning Not every event type can be tested
Synthetic scenarios exist only for `NEW_VULN_AFFECTS_RELEASES`,
`VULNERABILITY_RECORD_UPDATED` and `VEX_STATE_CHANGED`. A subscription on any
of the release or approval event types has no scenario to inject, so its
**Test** control is disabled and hovering it explains why.

That is deliberate -- injecting would fire a real org-wide event that could
never match the subscription you are testing -- but it does mean a
release-lifecycle or approval subscription can only be verified by causing the
real event: transition a release, or open an actual approval request.
:::

::: tip A test on an owner-routed subscription can legitimately show nothing
If the only route on the subscription
[notifies the component owner](#notifying-the-component-owner), a test that
reports no delivery usually means the component the test event landed on has no
owner, or its owner team has no channel -- not that your filter or severity
gate is wrong. Check ownership first; the result dialog says so too.
:::

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
