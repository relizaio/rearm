// Pure payload/validation helpers for the Team editor (member roles + external
// members). Kept out of the SFC so they are unit-testable, and so the DIRTY
// comparison and the SAVE payload can be built by the same code -- comparing a
// raw editor array against a filtered/trimmed payload is what makes a modal
// look dirty while having nothing to send.
import constants from '@/utils/constants'

export interface TeamMemberRolePayload {
    userRef: string
    role: string
    customRole: string | null
}

export interface ExternalTeamMemberPayload {
    name: string
    contact: string
    role: string
    customRole: string | null
}

/** Editor state for roles is keyed by user uuid: the backend rejects two roles
 *  for the same member, and a map cannot express that. */
export type TeamRoleMap = Record<string, { role?: string | null, customRole?: string | null }>

function customLabel (role: string | null | undefined, customRole: string | null | undefined): string | null {
    return role === constants.TeamRoleCustom ? ((customRole || '').trim() || null) : null
}

/**
 * Roles for members still on the roster. Anyone dropped from the group in this
 * editing session is excluded -- the backend rejects a role whose userRef is not
 * on the post-merge roster, so emitting one would fail the entire mutation.
 */
export function buildMemberRolesPayload (roles: TeamRoleMap, rosterUuids: string[]): TeamMemberRolePayload[] {
    const roster = new Set(rosterUuids)
    return Object.entries(roles || {})
        .filter(([uuid, v]) => !!v && !!v.role && roster.has(uuid))
        .map(([uuid, v]) => ({
            userRef: uuid,
            role: v.role as string,
            customRole: customLabel(v.role, v.customRole)
        }))
}

/** Rows where BOTH name and contact are blank are treated as untouched scaffolding
 *  and dropped; a half-filled row is kept so validation can flag it. */
export function buildExternalMembersPayload (rows: any[]): ExternalTeamMemberPayload[] {
    return (rows || [])
        .filter(r => (r.name || '').trim() || (r.contact || '').trim())
        .map(r => ({
            name: (r.name || '').trim(),
            contact: (r.contact || '').trim(),
            role: r.role,
            customRole: customLabel(r.role, r.customRole)
        }))
}

/**
 * Mirrors the backend's rejection rules so the operator gets a specific,
 * pre-submit message naming the offending member -- instead of a generic
 * server error that also aborts unrelated permission/SSO edits made in the
 * same modal. Returns null when the payload is submittable.
 */
export function validateTeamMembers (
    roles: TeamMemberRolePayload[],
    externals: ExternalTeamMemberPayload[],
    labelFor: (uuid: string) => string,
): string | null {
    for (const r of roles) {
        if (r.role === constants.TeamRoleCustom && !r.customRole) {
            return `Enter a custom role label for ${labelFor(r.userRef)}, or pick a listed role.`
        }
    }
    for (const e of externals) {
        if (!e.name || !e.contact) {
            return `External member "${e.name || e.contact}" needs both a name and a contact.`
        }
        if (e.role === constants.TeamRoleCustom && !e.customRole) {
            return `Enter a custom role label for external member "${e.name}", or pick a listed role.`
        }
    }
    return null
}
