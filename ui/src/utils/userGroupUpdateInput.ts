// Builds the `updateUserGroup` mutation input.
//
// Extracted from the SFC because three of its rules are OMIT-OR-YOU-DELETE
// rules, and a comment is not a guard. The backend treats a non-null list as a
// REPLACE, so every one of these is a silent data-loss bug if it regresses:
//
//   1. Team fields go out only when this group's team data was actually loaded.
//      Sending [] from an unloaded editor wipes the roles.
//   2. Channels go out only when the channel list loaded. A picker built from a
//      partial list would save that partial list over the real one.
//   3. `externalMembers` is NEVER sent. Its editor was withdrawn, so the form
//      has no idea what the stored value is; omitting it is the only thing
//      keeping a team's stored contacts alive.
//
// Rule 3 in particular has no operator-visible failure mode -- nothing in the
// UI shows external members any more, so a regression would destroy them with
// nobody watching. Hence a test rather than a comment.
import type { TeamMemberRolePayload } from '@/utils/teamMembers'

export interface UserGroupTeamState {
    /** True only once this group's team data has actually been fetched. */
    known: boolean
    /** True when the org channel list failed to load, so the picker is partial. */
    channelsLoadFailed: boolean
    channels: string[]
    memberRoles: TeamMemberRolePayload[]
}

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
    team: UserGroupTeamState,
): Record<string, any> {
    const input: Record<string, any> = {
        groupId: group.uuid,
        name: group.name,
        description: group.description,
        manualUsers: group.manualUsers || [],
        status: group.status,
        connectedSsoGroups: group.connectedSsoGroups || [],
        permissions,
    }
    if (team.known) {
        if (!team.channelsLoadFailed) input.notificationChannels = team.channels
        input.memberRoles = team.memberRoles
    }
    return input
}
