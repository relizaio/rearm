---
sidebarDepth: 2
---

# Manage Users and User Group Permissions

This guide describes administrator actions in **Organization Settings** for managing:

- User permissions
- User group permissions
- Active/inactive user groups
- Scoped permission behavior (organization, perspective, product/component)

## Prerequisites

- You must be an **Organization Admin**.
- Open **Organization Settings** from the left navigation.

## Manage Individual User Permissions

1. Open the **Users** tab.
2. In the users table, click the **edit** (pencil) action for a user.
3. In the permission modal, configure:
   - **Organization-Wide Permission** (`None`, `Essential Read`, `Read Only`, `Read & Write`, `Admin`)
   - **Organization-Wide Functions** (available for `Read Only` and `Read & Write`)
   - **Organization-Wide Approval Permissions**
   - **Per-Perspective** permissions (ReARM Pro only)
   - **Per-Product / Per-Component** permissions
4. Click **Save Permissions**.

If you made changes and want to discard them, click **Reset Changes**.

## Manage User Groups

### Create a User Group

1. Open the **User Groups** tab.
2. Enter group name (and optional description).
3. Click **Create Group**.

### Edit a User Group

1. In the user groups table, open a group’s settings.
2. Update:
   - Group name/description
   - Manually added users
   - Connected SSO groups - those are groups to which users are added in ReARM Keycloak
   - Scoped permissions (same permission editor model as users)
3. Click **Save Changes**.

### Deactivate and Restore a User Group

- Deactivated groups can be shown by enabling **Show Inactive Groups**.
- A new group cannot reuse existing group name, even if it is inactive. Instead, inactive group needs to be restored to preserve the name.
- To restore an inactive group:
  1. Click **Restore** in the table action.
  2. Review group settings and update as needed.
  3. Click **Restore Group**.

## Permission Model and Important Behavior

### 1) Organization-Wide Permission Type

Organization-wide permission defines the baseline access level.

- `None` grants no access. Granular access may be specified in scoped permissions, however `None` is not guaranteed to work and at least `Essential Read` is required for minimal operations.
- `Administrator` grants administrator-level access.
- `Essential Read` grants minimal read access to core org data.
- `Read Only` / `Read & Write` allow selecting organization-wide permission functions.

### 2) Organization-Wide Functions

Functions are managed separately from permission type and can grant specific capabilities.

Important behavior:

- If organization-wide permission is changed **from `Read Only` or `Read & Write` to any other type**, organization-wide functions are automatically cleared.

This prevents stale function grants from being retained after moving to a type where function selection is not applicable.

Currently, following functions are available:

- `Finding Analysis Read` - grants read access to finding analysis
- `Finding Analysis Write` - grants write access to finding analysis (also requires `Finding Analysis Read` for practical work)
- `Artifact Download` - grants delete access to download artifacts, including aggregated artifacts

All functions are always available for `Administrator` permission type.

### 3) Organization-Wide Approval Permissions

If set, allow users to set granted approvals for all objects in the organization. Organization Admins can always set granted approvals.

### 4) Scoped Permissions

Both users and user groups support scoped overrides for:

- `Perspective` (ReARM Pro only)
- `Product`
- `Component`

Each scoped entry can include:

- Permission type
- Functions
- Approval permissions (ReARM Pro only)
