---
sidebarDepth: 2
---

# Component Ownership

Every component can have an **owner**: the team accountable for it. ReARM uses
the owner to answer "who should hear about this?" without anyone having to
maintain that list by hand in each place it matters --
[notification routes](./notifications#notifying-the-component-owner) can
address the owner directly, and the ownership report tells you which components
have nobody durable behind them.

Ownership is deliberately **separate from access**. Owning a component does not
grant permissions on it, and holding permissions does not make you the owner.
Access is configured under
[Users and User Groups Permissions](./user-and-user-group-permissions).

## Setting an owner

Open a component, go to **Settings**, and use the **Owner** picker. You can
name a team or an individual user.

Prefer a **team**. An individual owner is valid and ReARM records it, but a
person leaves, and only a team can be a notification target -- a user has no
channels of their own, so a route that notifies the component owner will not
deliver for a user-owned component.

## Ownership status

ReARM recomputes a component's ownership status on read; it is never stored, so
it always reflects the current state of the owner it points at.

| Status | Meaning |
|---|---|
| `OWNED` | A stored owner that is valid and durable. The healthy state. |
| `NON_DURABLE` | Valid, but resting on a single point of failure: an individual owner, or a team with fewer than two direct members that is not SSO-backed. |
| `DEGRADED` | The owner still resolves but has weakened -- typically the owner team has been archived or made inactive. |
| `UNSET` | No owner has been chosen, but ReARM can suggest a candidate team. |
| `ORPHANED` | No owner and nothing to suggest, or a stored owner that no longer resolves. Needs a human. |

A component is **addressable for notifications** when it is owned by a team and
its status is `OWNED` or `NON_DURABLE`. The other three states have no usable
owner, so an owner-routed notification simply produces no delivery for that
component.

::: tip Ownership never blocks a release
None of these states gate a release, a build, or an approval. They drive a
non-blocking banner on the component and the at-risk report -- nothing else.
:::

### Why "durable" is its own state

A one-person team is a real owner, not a mistake, so ReARM does not report it
as an error. But it is a weak one: when that person leaves, the component
becomes orphaned silently. `NON_DURABLE` names that risk without treating it as
a failure, which is why those components still receive notifications while
still showing up on the at-risk report.

A team counts as durable when it has at least **two** direct members, or when
it is backed by an SSO group.

## Assignment rules

Setting an owner component by component does not scale. **Team assignment
rules** (Organization Settings -> User Groups, org admins only) assign
ownership in bulk: each rule pairs a regular expression against the component
**name** with an owner team, and can optionally restrict itself to components
only or products only.

For example, a rule matching `Payments.*` pointing at the Payments Platform
team makes every current and future component whose name starts with
`Payments` owned by that team, with nothing to set per component.

Notes on how rules resolve:

- The pattern must match the **entire** component name, not just part of it. So
  the pattern `Payments` matches only a component called exactly `Payments`; to
  catch `Payments API` as well you need `Payments.*`.
- Rules are evaluated in the order they appear, and the **first** match wins.
- A rule-assigned owner is a real owner and is treated exactly like a stored
  one for notifications and durability -- a rule pointing at a one-person team
  still reports `NON_DURABLE`.

### Precedence

When more than one source could decide an owner, ReARM resolves in this order:

1. **A stored owner** set on the component. A rule never overrides a deliberate
   human choice.
2. **The first matching assignment rule.**
3. **A suggested candidate**, if one is derivable. This is a suggestion only:
   the component reports `UNSET` until somebody accepts it or a rule covers it.

## Finding components at risk

On an individual component, the **Settings** panel shows the computed status
next to the owner picker, along with the reason behind it -- for example that
the owner team has fewer than two members, or that it was assigned by a
particular rule rather than chosen directly.

Organization-wide, an **ownership report** returns every component that is not
cleanly `OWNED`, together with its computed status. This is the practical
starting point after enabling owner-routed notifications: a component missing
from your alerts is usually a component missing an owner. The report is
currently available through the GraphQL API (`componentOwnershipReport`,
org-admin only) rather than as a page in the UI.
