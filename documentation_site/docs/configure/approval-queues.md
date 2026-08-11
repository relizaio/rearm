---
sidebarDepth: 2
---

# Approval Queues

::: warning ReARM Pro only
:::

An **approval policy** on a release can require a minimum number of approvals
(and set a maximum number of disapprovals before the release is vetoed) from
users holding specific **approval roles**, before the release can be
considered fully approved. This page covers where those pending approvals
show up and who gets notified about them -- see [Manage Users and User Group
Permissions](./user-and-user-group-permissions) for how approval roles and
permissions are granted in the first place.

## Where to find pending approvals

Open **Notification Inbox -> Needs my approval**. This tab lists every
release that is currently waiting on *your* vote -- it's computed live from
each release's approval policy and current votes, not a saved list, so it's
always current.

This is different from **Notification History**, which is an org-admin audit
log of every notification ReARM has sent -- the approval-queue tab only shows
what's still actionable for you specifically.

## Casting a vote vs. being asked to vote

These are two different things, and they don't always go together:

- **Casting a vote (approve/disapprove) on a release:** any user holding an
  approval role that the release's policy accepts can vote. **Organization
  Admins can always vote, on any release, regardless of role** -- this is the
  existing rule described in [Manage Users and User Group
  Permissions](./user-and-user-group-permissions#3-organization-wide-approval-permissions).
- **Showing up in "Needs my approval" / getting an `APPROVAL_REQUESTED`
  notification:** this is driven **only** by whether you hold an explicit
  approval role that the release's policy accepts -- the Organization Admin
  override does **not** put releases in your queue or trigger a notification
  to you.

::: warning An Org Admin with no approval role has a quiet inbox
Being an Organization Admin gives you the *capability* to approve anything,
but not *visibility* into what's waiting. If you want an admin to be
proactively notified and see items in their approval queue, grant them an
explicit approval role in addition to Admin -- Admin alone is not enough for
that. This is intentional (it keeps an admin's queue from filling up with
every pending release org-wide), but it surprises people who expect "Admin"
to imply "sees everything."
:::

## Requesting an approval

Requesting an approval on a release notifies the release's eligible approvers
(the same role-based set described above -- Organization Admins without an
approval role are not included in the notification, for the same reason they
don't appear in the queue). A request is advisory: it doesn't change what's
required to approve the release, and a release can still be approved or
disapproved without ever having a request opened on it.

Approval-related notifications (`APPROVAL_REQUESTED` and `APPROVAL_RESOLVED`,
see [Notifications](./notifications#event-types)) always deliver immediately,
even on channels configured for digest/batched delivery elsewhere.
