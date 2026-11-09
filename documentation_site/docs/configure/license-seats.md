---
sidebarDepth: 2
---

# License Seats (ReARM Pro)

::: warning ReARM Pro only
:::

A ReARM Pro license sets how many people may use the instance: a number of
**write users** and a number of **read users**. This page explains who takes a
seat, how ReARM decides who may sign in when there are more people than seats,
how single sign-on users without a role are handled, and what to do when an
instance is over its license.

A license without user limits enforces no seat limits: no change is refused for
lack of a seat, and nobody is refused at sign-in or flagged as outside the
limit. The
[SSO users without a role](#sso-users-without-a-role) setting, and its `NO-ROLE`
refusal of a first sign-in, applies on every license, with or without user
limits. API keys and AI agents never take a seat.

## Who holds a seat

A seat belongs to a person, identified by email address. A person holds a seat
when their effective permission, set on them directly or given to them by a
user group, is above `None` in at least one organization.

- **Write seat**: the person has `Admin` or `Read & Write`, the
  `Finding Analysis Write` function, or an approval permission, in any
  organization.
- **Read seat**: the person has any other permission above `None`
  (`Essential Read`, `Read Only`) and no write grant.
- **No seat**: the person has `None` everywhere. Such an account stays, but it is
  not counted.
- **Global administrators always hold a write seat**, whether or not they have a
  role in any organization. On a fresh install, the first administrator therefore
  takes one write seat before anyone else signs in.

A person who belongs to several organizations, or who has accounts under the
same address in different letter case, takes one seat: the highest one they
qualify for. Only active accounts are counted; an inactivated account holds no
seat.

## The limits

With a license of `M` write users and `N` read users:

- write users must be at most `M`, and
- read users plus write users must be at most `N + M`.

Read users may use spare write capacity, and a read user who does is counted
against it.

**Example.** A license for 10 write users and 20 read users allows at most 10
write users and at most 30 users in total.

| Write users | Read users | Total | Fits? |
|---|---|---|---|
| 10 | 20 | 30 | Yes |
| 8 | 22 | 30 | Yes: two read users use spare write capacity |
| 9 | 21 | 30 | Yes: one more write user, one read user fewer |
| 9 | 22 | 31 | No: the total is over 30, even though a write seat is free |
| 11 | 10 | 21 | No: more than 10 write users |

## When ReARM checks the limits

Every change that can give a person a seat, or move them from a read seat to a
write seat, is checked against the counts the change would produce:

- a first sign-in,
- changing a user's permissions,
- adding a user to a user group, or changing a group's permissions,
- the group update ReARM makes from your identity provider's groups at sign-in,
- reactivating an inactivated user,
- joining an organization through an invitation, and creating an organization.

A change that does not fit is refused, and nothing is saved. An administrator
sees a message that starts with:

```
License limit reached: this change needs K more write seat(s); W of M write seats are in use. Free a seat or extend the license.
```

Here `K` is how many write seats the change goes over the limit by, and `W` is
the number of write seats the change would bring. (When the total is the limit
that is reached, the message names the seats in total the change would bring.)

Creating an invitation does not reserve a seat. The seat is checked when the
person joins; if no seat is free then, the join is refused with:

```
ReARM has no free user seat for this membership. Please contact your ReARM administrator. Reference: SEAT-FULL.
```

When single sign-on would add a user to a group that raises their seat (from
no seat to a read or write seat, or from a read seat to a write seat) at sign-in,
and the license has no room for it, ReARM skips that one change instead of
refusing the sign-in: the user keeps the access they had, every other change from
the identity provider (including removals) still applies, and administrators get
an event. When the write limit is the one reached, the event reads:

```
SSO group sync did not add <user> to <group>: no free write seat (W of M). The user keeps read access.
```

The last sentence names read access only for a user who holds a read seat; a
user who had no seat before the change is told instead: `The user keeps their
current access.`

When the total is the limit that is reached, the event reads:

```
SSO group sync did not add <user> to <group>: no free seat (T of X). The user keeps their current access.
```

The total grows only for a user who had no seat, so this form is always about a
user without a seat (added to a read group, or to a write group while write
seats are still free), and always ends with `The user keeps their current
access.`

In both forms, `W` and `T` are the write seats and the seats in total that the
change would have brought, one more than the seats in use when that limit is
exactly full (for example `no free seat (31 of 30)`), and `X` is `N + M`.

## Admission by creation date

An instance can hold more people than its license allows, for example after a
smaller license is uploaded, or after an upgrade that started counting global
administrators. ReARM then decides who may sign in, every time, by the age of
the account:

1. Write users are ordered by the date their account was created. The oldest
   `M` are inside the limit.
2. Read users are ordered the same way. The oldest ones fill the remaining
   total, `N + M` minus the write users admitted in step 1.
3. Everyone else is outside the limit and cannot sign in.

Global administrators count, but they are never refused at sign-in, so an
administrator can always sign in to fix the situation.

Nothing is stored or cleaned up. When a seat becomes free, or a bigger license
is uploaded, the next oldest account outside the limit can sign in again on its
next attempt. A person outside the limit is cut off on their next request, not
only at their next sign-in, and their personal API keys and CLI sessions are
refused too.

**Example.** A license for 2 write users and 1 read user, with these accounts:

| Account | Seat | Created | Result |
|---|---|---|---|
| alice | Write | 2024 | Inside |
| bob | Write | 2025 | Inside |
| carol | Write | 2026 | Outside: the 2 write seats are taken by older accounts |
| dave | Read | 2025 | Inside: 2 + 1 - 2 = 1 read slot |
| erin | Read | 2026 | Outside |

## Messages people see at sign-in

A person who is refused sees one of three messages, with a `Sign out` button.
The messages carry no numbers; give the person the reference when they ask.

| Reference | Message | What the administrator does |
|---|---|---|
| `SEAT-FULL` | You signed in, but ReARM has no free user seat for a new account. Please contact your ReARM administrator. Reference: SEAT-FULL. | A first sign-in would take a seat and none is free. Free a seat (see [over-limit remediation](#an-instance-over-its-license)) or extend the license, then ask the person to sign in again. |
| `OVER-LIMIT` | Your ReARM account is outside the licensed number of users. Please contact your ReARM administrator. Reference: OVER-LIMIT. | The account exists but is outside the limit by creation date. Free a seat or extend the license; the person can sign in once their account is inside the limit again. |
| `NO-ROLE` | You signed in, but no ReARM role is assigned to your account. Ask your ReARM administrator to add you to a ReARM user group or assign you the application role in your identity provider. Reference: NO-ROLE. | See [SSO users without a role](#sso-users-without-a-role) below. |

## SSO users without a role

When a person signs in for the first time through single sign-on, ReARM adds
them to every user group of the default organization whose connected SSO groups
appear in their groups claim (see [Microsoft Entra ID](/integrations/identityProviders/microsoft.md)
for setting this up). The setting **SSO users without a role** decides what
happens when no group matches and the person holds no pending invitation to any
organization:

- **Refuse sign-in (default)**: no account is created and the person sees the
  `NO-ROLE` message. Administrators of the default organization get an event
  naming the setting.
- **Create account without a role**: the account is created with permission
  `None`. It holds no seat until an administrator gives it a role.

The setting is in **Organization Settings** > **Admin Settings** > **General**,
and is shown only on the default organization. An organization that has never
saved it uses **Refuse sign-in**. The very first account of a new instance (the
one that sets it up and becomes an administrator) is never refused.

To let a refused person in, do one of:

- assign them the application role (or a group) in your identity provider that
  maps to a ReARM user group, and ask them to sign in again;
- invite them to an organization, and ask them to sign in again;
- switch the setting to **Create account without a role**, then give them a role
  in ReARM after they sign in.

With Microsoft Entra ID, also set **Assignment required?** to **Yes** on the
enterprise application, so that people who are not assigned to the application
are stopped by Microsoft before they reach ReARM. See
[Microsoft Entra ID](/integrations/identityProviders/microsoft.md).

## Disabling a user in the identity provider does not free a seat

Disabling or deleting a user in Keycloak or in your identity provider (for
example, removing their Entra assignment) stops them from signing in, but their
ReARM account stays active and **keeps its seat**. Group changes made in the
identity provider also reach ReARM only through the person's own sign-in, which
no longer happens.

To free the seat, inactivate the user in ReARM (in the default organization,
Organization Settings > Users, edit the user, **Inactivate User**), or lower their permissions to `None` and
remove them from any user group that grants more.

## Seat view and administrator tools

Global administrators see the seats in **System Settings** > **License**:

- the counts: write users and read users against the limits, the **Total** line
  (`Total: T / (maxWrite + maxRead)`: read users may use spare write capacity),
  users without a role (not counted), the number of accounts outside the limit,
  and when they were counted. Global administrators count as write users here,
  whatever their role in each organization;
- **License Seats**: one row per person with email, seat type (`WRITE`, `READ` or
  `NONE`), account creation date, last login, and an `outside the limit` flag for
  everyone who would be refused at sign-in. Global administrators are marked;
- **Refresh**, which counts again;
- **Remove accounts with no role**, which inactivates every account with no role
  in any organization, after a confirmation that names how many. Global
  administrators and your own account are never inactivated. These accounts
  hold no seat, so this tidies the list but frees no seat.

Each organization's **Statistics** page shows the organization's own **Users Without a Role** beside its write and read-only users: members whose effective permission in that organization is NONE; they hold no seat there. A global administrator with no role in the organization is counted there too, while System Settings counts them as a write user.

When accounts are outside the limit, global administrators and administrators of
the default organization see a banner at the top of every page:

```
This instance is over its license: R read users of N, W write users of M. Accounts outside the limit, by creation date, cannot sign in.
```

### Events

Administrators of the organization concerned get these events in their in-app
inbox, under the event type `License seats` (`LICENSE_SEAT_EVENT`). A
[notification subscription](./notifications) can also route them to Slack,
Microsoft Teams, email or another channel. Each kind of event is sent at most
once an hour for the same person (and group).

- a first sign-in refused because no seat is free (`SEAT-FULL`);
- a sign-in refused because the account is outside the limit (`OVER-LIMIT`);
- a first sign-in refused because the person has no role (`NO-ROLE`), naming
  the **SSO users without a role** setting;
- a group change from single sign-on skipped because no seat is free.

## An instance over its license

When the banner shows, some people cannot sign in. To fix it:

1. Open **System Settings** > **License** and look at **License Seats**. The
   accounts flagged `outside the limit` are the ones being refused.
2. Free seats, starting with accounts that no longer need access:
   - **inactivate** people who have left or no longer use ReARM;
   - **lower permissions**: move people who only read from a write permission to
     `Read Only`, or remove them from a user group that grants write access;
   - **remove** a person from an organization they do not need.

   Changes that reduce the counts, or leave them as they are, are always
   allowed on an over-limit instance. Changes that would grow a count that is
   already over the limit are refused until it fits.
3. Or upload a license with more users (System Settings > License >
   **Update License**).

As soon as the counts fit, the oldest accounts outside the limit can sign in
again; no other action is needed. Removing accounts with no role does not help
here, since they hold no seat.

To give an inactivated person access again, **reactivate** their account
(Organization Settings > Users > Inactive Users, **Reactivate User**). Do not
ask them to sign in again instead: signing in does not reactivate an inactivated
account, and the sign-in fails. A reactivation is checked like any other change
and is refused if no seat is free.
