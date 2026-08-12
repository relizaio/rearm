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
server version, and the rest of Organization Settings works normally. Routes and
components on CE address channels directly instead.
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
   leads. Those are only offered once the team exists, since each one is a
   reference that has to resolve against a saved record.

A team name must be unique within the organization, and archived teams still
hold their names -- so if a name is refused and you cannot see the team using
it, an archived team has it. Restore that team rather than working around the
name.

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

## Archiving a team

Set a team to **inactive** rather than deleting it, and the record and its
history stay intact. Archiving has real routing consequences, which is the point
of doing it explicitly:

- Notification routes that target the team **stop delivering**. The team
  contributes no channels, and a route left with nothing else produces no
  delivery at all -- silently, since nothing failed.
- A component owned by the team reports its ownership as `DEGRADED` -- the
  owner still resolves, but is no longer a usable notification target. See
  [Component ownership](./component-ownership#ownership-status).
- The team is no longer offered when picking a *new* owner or route target.
  Where it is already stored it stays visible, labelled as archived, so you can
  see it and replace it rather than finding an empty field.

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
- **Durability** -- a team owning a component counts as durable when its roster
  has at least two people, or when one of its contained groups is SSO-backed.
  See [why durability is its own state](./component-ownership#why-durable-is-its-own-state).
