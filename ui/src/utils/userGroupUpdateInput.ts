// Builds the `updateUserGroup` mutation input.
//
// This used to carry the team fields -- member roles and notification channels
// -- under omit-or-you-delete rules, because the backend treats a non-null list
// as a REPLACE and a partially-loaded editor could wipe a roster. Those fields
// now live on Team, so the whole hazard is gone from this path and the builder
// is a plain projection.
//
// It stays extracted rather than being inlined back into the SFC: the same
// REPLACE semantics still govern `manualUsers`, `connectedSsoGroups` and
// `permissions`, and a testable seam on the payload is what caught the last
// regression here.
export interface UserGroupCore {
    uuid: string
    name?: string
    description?: string
    manualUsers?: string[]
    status?: string
    connectedSsoGroups?: string[]
}

export function buildUserGroupUpdateInput (
    group: UserGroupCore,
    permissions: any[],
): Record<string, any> {
    return {
        groupId: group.uuid,
        name: group.name,
        description: group.description,
        manualUsers: group.manualUsers || [],
        status: group.status,
        connectedSsoGroups: group.connectedSsoGroups || [],
        permissions,
    }
}
