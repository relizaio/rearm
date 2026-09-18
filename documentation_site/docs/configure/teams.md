---
sidebarDepth: 2
---

# Teams

A **team** is the unit ReARM addresses when it needs to reach *people* rather
than a destination: the owner of a component, or the target of a notification
route. Teams live under **Organization Settings -> Teams**.

Teams are deliberately **not** about access. A team grants no permissions, and
holding permissions does not put you on a team. Access is configured with
[user groups](./user-and-user-group-permissions); a team says who is
*accountable*, a group says who can *do* things.

::: tip Teams are a ReARM Pro capability
On Community Edition the Teams tab reports that teams are not available on this
server version, and the rest of Organization Settings works normally. Notification
routes on CE address [channels and channel groups](./notifications#route-targets)
directly, both of which are available on either edition.
:::

Only **organization admins** can see or edit teams.

## What a team holds

| Field | What it does |
|---|---|
| **Name** and description | How the team is identified in pickers and reports |
| **Members** | People added to this team individually |
| **User groups** | Permission groups whose members are *also* on this team. Members arrive through the group, so a team can track an SSO-managed roster without anyone re-entering names |
| **Notification channels** | Where this team is reachable -- its Slack channel, an email channel, and so on. This is what a [route targeting the team](./notifications#route-targets) and [owner routing](./notifications#notifying-the-component-owner) deliver to |
| **Leads** | People who administer the team. **Recorded only today** -- leads grant no ability to edit the team yet |
| **Notifications for owned components** | A toggle, plus the event types the team wants. See [Notifying a team about what it owns](#notifying-a-team-about-what-it-owns) |

A team's **roster** is its individual members plus everyone contributed by its
user groups. The roster is what durability is measured against, and it is
recomputed on read, so a person joining an SSO group joins every team that
contains it without anyone touching the team.

Only people on the roster can be named as leads, and that is enforced against
the roster the team will have *after* your edit. In the editor, dropping someone
from members or removing a group drops them from the leads picker as you go; the
same edit sent through the API is rejected rather than silently keeping them.
A lead who is no longer on the team is a stale grant waiting to matter once
leads carry authority.

## Creating a team

1. Open **Organization Settings -> Teams** and click **Add team**.
2. Give it a name (and optionally a description).
3. Save, then edit it to add members, user groups, notification channels, and
   leads. Creation deliberately takes a name and description only: everything
   else arrives through the same update path, so each reference is validated by
   exactly one piece of code rather than two that can drift.

A team name must be unique within the organization, and archived teams keep
their names. Try to reuse one and ReARM says so explicitly -- restore the
archived team rather than working around the name. Archived teams stay listed
on the Teams tab with status `INACTIVE`, so there is nothing hidden to hunt for.

## Why contain a user group instead of listing people

Both work, and most teams use a mix. The distinction is where the roster is
maintained:

- **Members** are the right answer for people who belong to this team
  specifically -- the two engineers who actually carry the pager for it.
- **A contained user group** is the right answer when the roster is already
  maintained somewhere else, usually an SSO group. Then joining and leaving is
  handled by whoever manages that group, and the team follows.

A group contained by a team still grants exactly the permissions it always did.
Containing it changes nothing about access; it only adds its members to the
roster.

## Notifying a team about what it owns

A team can subscribe itself to everything that happens to the components it
owns, without anyone opening the Subscriptions tab. In the team editor, tick
**Notify this team about the components it owns** and pick the event types.
It delivers to the team's own **notification channels**, for components it owns
either directly or through an
[assignment rule](./component-ownership#assignment-rules).

Every event type is selected by default, and what ReARM stores is the
*deselected* set -- so an event type added to the product later arrives selected
and the team keeps hearing about whatever happens to what it owns. VEX and
instance-deployment events are not offered at all: their payload carries no
affected component, so an ownership-scoped subscription could never match them.

Two things to know before relying on it:

- **A team with no notification channels delivers nothing.** The toggle saves
  happily; there is simply nowhere to send. The editor warns when this is the
  case.
- **It stacks with owner routing.** If a subscription already
  [notifies the component owner](./notifications#notifying-the-component-owner)
  for an overlapping event type, both deliver -- duplicate suppression is
  per subscription, so the team's channel gets two messages for one event. The
  editor names the offending subscription when it spots this.

Behind the toggle, ReARM maintains a subscription for the team. It appears in
**Integrations -> Subscriptions** badged **Managed by** the team's name, and its edit,
pause and delete actions are disabled -- the team editor is the only place it
can be changed. It is visible rather than hidden precisely because a
subscription that delivers but appears nowhere is the harder thing to debug.

## Archiving a team

Use the **Archive** action on the team's row rather than deleting it -- there is
no delete -- and the record and its history stay intact, with the team's status
shown as `INACTIVE`. Archiving has real routing consequences, which is the point
of doing it explicitly:

- Notification routes that target the team **stop delivering**. The team
  contributes no channels, and a route left with nothing else produces no
  delivery at all -- silently, since nothing failed.
- A component owned by the team reports its ownership as `DEGRADED` -- the
  owner still resolves, but is no longer a usable notification target. See
  [Component ownership](./component-ownership#ownership-status).
- Its own owned-component subscription, if it had one, is **disabled** rather
  than deleted -- an archived team resolves to no channels, so leaving the row
  active would make a subscription that delivers nothing look like one that
  works. Restoring the team re-enables it.
- The team is no longer offered when picking a *new* owner or route target,
  while everywhere it is already stored keeps pointing at it. Archiving stops
  the team being chosen again; it does not detach it from what it already owns
  or serves, which is why the two consequences above are yours to clean up.

::: warning Archiving a team is a quiet way to stop notifications
Nothing errors when a targeted team is archived: the subscription stays active,
the event fans out, and zero deliveries are written. If notifications from a
subscription stop for no apparent reason, check whether a team it targets was
archived -- and note that a
[subscription test](./notifications#testing-channels-and-subscriptions) will
report no delivery without necessarily blaming the right thing.
:::

## Where teams are used

- **[Notification routes](./notifications#route-targets)** can name a team as a
  target, delivering to whatever channels that team currently has.
- **[Component ownership](./component-ownership)** points at a team, directly or
  through an assignment rule, which is what makes "notify the component owner"
  resolvable.
- **Its own components' events** -- a team can
  [notify itself about what it owns](#notifying-a-team-about-what-it-owns),
  which needs no subscription of your making.
- **Durability** -- a team owning a component counts as durable when its roster
  has at least two people, or when one of its contained groups is SSO-backed.
  See [why durability is its own state](./component-ownership#why-durable-is-its-own-state).
