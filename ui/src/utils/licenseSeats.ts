// License seats (task TPF-168, the UI half of TPF-165): the typed sign-in refusal, the over-license
// banner, the seat users view and the default organization's ssoUsersWithoutRole setting.
//
// Its own module, free of the GraphQL client, keycloak and sweetalert2, so the documents validate
// (scripts/validate-graphql) and the rules run in a spec without the app around them.

import gql from 'graphql-tag'
import { graphqlErrorEntries } from './graphqlErrorText'

/** The `extensions.code` the server puts on a typed seat refusal; the UI switches on it, never on the text. */
export const SEAT_REFUSED_CODE = 'LICENSE_SEAT_REFUSED'

/** `extensions.reason` of a seat refusal: the three kinds of the error contract. */
export const SeatRefusalReason = {
    SEAT_FULL: 'SEAT_FULL',
    OVER_LIMIT: 'OVER_LIMIT',
    NO_ROLE: 'NO_ROLE'
} as const
export type SeatRefusalReason = typeof SeatRefusalReason[keyof typeof SeatRefusalReason]

export interface SeatRefusal {
    reason: SeatRefusalReason
    /** The server's end-user text, shown verbatim. */
    message: string
}

/** Title of the blocking dialog every seat refusal opens. */
export const SEAT_REFUSED_TITLE = 'Sign-in refused'
export const SEAT_REFUSED_SIGN_OUT = 'Sign out'

const REASONS = new Set<string>(Object.values(SeatRefusalReason))

/**
 * The first seat refusal among GraphQL error entries, or null. An entry counts only when its
 * code is the seat code and its reason one of the three kinds; the message is taken as is.
 */
export function seatRefusalOf (entries: any[] | null | undefined): SeatRefusal | null {
    for (const e of entries || []) {
        const ext = e?.extensions
        if (ext?.code === SEAT_REFUSED_CODE && REASONS.has(ext?.reason)) {
            return { reason: ext.reason as SeatRefusalReason, message: String(e?.message ?? '') }
        }
    }
    return null
}

/** The seat refusal a failed call carries (Apollo 4 `errors`, Apollo 3 `graphQLErrors`, a network error's body), or null. */
export function seatRefusalOfError (err: any): SeatRefusal | null {
    return seatRefusalOf(graphqlErrorEntries(err))
}

/**
 * Whether the signed-in user may read the instance license (the server's `license` gate: ADMIN of
 * the default organization, or a global administrator). The UI does not know which organization is
 * the default, so any organization ADMIN asks and a `Not authorized` answer shows nothing.
 */
export function mayReadLicense (myUser: any): boolean {
    if (!myUser || myUser.installationType === 'OSS') return false
    if (myUser.isGlobalAdmin) return true
    const perms = myUser.permissions?.permissions
    if (!perms) return false
    return Object.values(perms).some((p: any) => p && p.scope === 'ORGANIZATION' && p.object === p.org && p.type === 'ADMIN')
}

const limitText = (n: any): string => (n === null || n === undefined) ? 'unlimited' : String(n)

/** "1 read user", "2 read users", "0 read users": a count with its noun in the right number. */
export function countText (n: number | null | undefined, singular: string, plural: string = singular + 's'): string {
    const v = Number(n) || 0
    return `${v} ${v === 1 ? singular : plural}`
}

/**
 * The admin banner text when the instance has accounts outside its license, else null.
 * Numbers from `license { maxReadUsers maxWriteUsers globalUserCounts { readUsers writeUsers outsideLimit } }`.
 */
export function overLicenseBannerText (license: any): string | null {
    const counts = license?.globalUserCounts
    if (!counts || !(Number(counts.outsideLimit) > 0)) return null
    return `This instance is over its license: ${countText(counts.readUsers, 'read user')} of ${limitText(license.maxReadUsers)}, `
        + `${countText(counts.writeUsers, 'write user')} of ${limitText(license.maxWriteUsers)}. `
        + 'Accounts outside the limit, by creation date, cannot sign in. Please contact support.'
}

/**
 * A user a mutation returned (updateUserName, acceptUserPolicies), keeping the combined permissions
 * the `user` query stored: those mutations answer the account's direct permissions only, so a
 * user whose role comes from a user group would otherwise read as having no permission.
 */
export function withCombinedPermissionsOf (stored: any, returned: any): any {
    if (!returned || !stored?.permissions || stored.uuid !== returned.uuid) return returned
    return { ...returned, permissions: stored.permissions }
}

/** The banner of a signed-in user with no organization-wide permission in the current organization. */
export const NO_ORG_PERMISSION_TEXT = 'You were granted no permissions in this organization. Please contact your administrator.'

/**
 * Whether the signed-in user holds no organization-wide permission above NONE in `orgUuid`. The
 * `user` query returns the combined permissions (direct and through every user group) of each
 * organization, so the stored myuser is enough. A global administrator never counts; missing data
 * (no user, no organization, no permissions block) never shows the banner.
 */
export function hasNoOrgPermission (myUser: any, orgUuid: string | null | undefined): boolean {
    if (!myUser?.uuid || !orgUuid || myUser.isGlobalAdmin) return false
    const perms = myUser.permissions?.permissions
    if (!perms) return false
    return !Object.values(perms).some((p: any) => p && p.org === orgUuid && p.scope === 'ORGANIZATION'
        && p.object === orgUuid && !!p.type && p.type !== 'NONE')
}

export const LICENSE_BANNER_QUERY = gql`
    query licenseBanner {
        license {
            maxWriteUsers
            maxReadUsers
            globalUserCounts {
                writeUsers
                readUsers
                outsideLimit
            }
        }
    }`

/** The seat users view: every seat holder plus the counts header, both from a fresh count. */
export const LICENSE_SEAT_USERS_QUERY = gql`
    query licenseSeatUsers {
        licenseSeatUsers {
            email
            name
            userUuids
            organizations
            seatType
            admission
            createdDate
            lastLoginDate
            globalAdmin
        }
        license {
            maxWriteUsers
            maxReadUsers
            globalUserCounts {
                writeUsers
                readUsers
                totalUsers
                noRoleUsers
                outsideLimit
                countedAt
            }
        }
    }`

export const REMOVE_ACCOUNTS_WITHOUT_ROLE = gql`
    mutation removeAccountsWithoutRole {
        removeAccountsWithoutRole
    }`

/** Backend SeatType. */
export const SeatType = {
    WRITE: 'WRITE',
    READ: 'READ',
    NONE: 'NONE'
} as const
export type SeatType = typeof SeatType[keyof typeof SeatType]

/** Backend SeatAdmission. */
export const SeatAdmission = {
    INSIDE: 'INSIDE',
    OUTSIDE: 'OUTSIDE',
    NOT_COUNTED: 'NOT_COUNTED'
} as const
export type SeatAdmission = typeof SeatAdmission[keyof typeof SeatAdmission]

// ---------- seat view sorting (UI side, over the rows of one read) ----------

const ADMISSION_RANK: Record<string, number> = { OUTSIDE: 0, INSIDE: 1, NOT_COUNTED: 2 }
const SEAT_TYPE_RANK: Record<string, number> = { WRITE: 0, READ: 1, NONE: 2 }
const rankOf = (ranks: Record<string, number>, v: any): number => ranks[v] ?? Object.keys(ranks).length
const timeOf = (d: any): number => {
    const t = d ? new Date(d).getTime() : NaN
    return Number.isNaN(t) ? Number.POSITIVE_INFINITY : t
}
const textOf = (v: any): string => String(v ?? '').toLowerCase()
const byText = (a: any, b: any): number => {
    const x = textOf(a)
    const y = textOf(b)
    return x < y ? -1 : x > y ? 1 : 0
}

/** Column comparators of the seat view, ascending; each falls back to the email so the order is stable. */
export const seatUserCompare = {
    email: (a: any, b: any): number => byText(a.email, b.email),
    // a missing name sorts after every name
    name: (a: any, b: any): number => (!a.name !== !b.name ? (a.name ? -1 : 1) : byText(a.name, b.name)) || byText(a.email, b.email),
    seatType: (a: any, b: any): number => (rankOf(SEAT_TYPE_RANK, a.seatType) - rankOf(SEAT_TYPE_RANK, b.seatType)) || byText(a.email, b.email),
    createdDate: (a: any, b: any): number => (timeOf(a.createdDate) - timeOf(b.createdDate) || 0) || byText(a.email, b.email),
    // never signed in sorts after every sign-in
    lastLoginDate: (a: any, b: any): number => (timeOf(a.lastLoginDate) - timeOf(b.lastLoginDate) || 0) || byText(a.email, b.email),
    // outside the limit first, then counted inside, then not counted; by creation date within each
    admission: (a: any, b: any): number => (rankOf(ADMISSION_RANK, a.admission) - rankOf(ADMISSION_RANK, b.admission))
        || seatUserCompare.createdDate(a, b)
}

/** The seat view's default order: accounts outside the limit first, then by creation date (oldest first). */
export function sortSeatUsersDefault (rows: any[] | null | undefined): any[] {
    return [...(rows || [])].sort(seatUserCompare.admission)
}

/** How many emails the "Remove accounts with no role" confirmation lists before "and N more". */
export const NO_ROLE_EMAILS_SHOWN = 20

/**
 * The emails "Remove accounts with no role" deactivates, from the loaded seat view rows, by the
 * server's rule (LicenseSeatService.removeAccountsWithoutRole): a holder of seat type NONE that is
 * not a global administrator, never the caller (matched by account uuid or email).
 */
export function noRoleDeactivationEmails (rows: any[] | null | undefined, caller: { uuid?: string, email?: string } | null | undefined): string[] {
    const callerEmail = (caller?.email || '').toLowerCase()
    return (rows || [])
        .filter((r: any) => r && r.seatType === SeatType.NONE && !r.globalAdmin)
        .filter((r: any) => !(caller?.uuid && (r.userUuids || []).includes(caller.uuid)))
        .filter((r: any) => !(callerEmail && String(r.email || '').toLowerCase() === callerEmail))
        .map((r: any) => String(r.email))
}

/** The confirmation text of "Remove accounts with no role", naming the accounts it deactivates. */
export function removeNoRoleConfirmText (emails: string[] | null | undefined): string {
    const list = emails || []
    const n = list.length
    const shown = list.slice(0, NO_ROLE_EMAILS_SHOWN)
    const more = n - shown.length
    const listText = n === 0
        ? ''
        : `${n === 1 ? 'Account' : 'Accounts'} to deactivate: ${shown.join(', ')}`
            + (more > 0 ? ` and ${countText(more, 'more', 'more')}. ` : '. ')
    return `Deactivate ${countText(n, 'account')} with no role in any organization? `
        + listText
        + 'Global administrators and your own account are never deactivated. '
        + 'A deactivated account can be reactivated later.'
}

/** Backend SsoUsersWithoutRole: what a first SSO sign-in with no matching group and no invitation does. */
export const SsoUsersWithoutRole = {
    REFUSE: 'REFUSE',
    CREATE_WITHOUT_ROLE: 'CREATE_WITHOUT_ROLE'
} as const
export type SsoUsersWithoutRole = typeof SsoUsersWithoutRole[keyof typeof SsoUsersWithoutRole]

export const SSO_USERS_WITHOUT_ROLE_OPTIONS = [
    { label: 'Refuse sign-in (default)', value: SsoUsersWithoutRole.REFUSE },
    { label: 'Create account without a role', value: SsoUsersWithoutRole.CREATE_WITHOUT_ROLE }
]

/** The stored setting as the control shows it: absent or unknown reads as REFUSE, the server's default. */
export function ssoUsersWithoutRoleOf (settings: { ssoUsersWithoutRole?: string | null } | null | undefined): SsoUsersWithoutRole {
    return settings?.ssoUsersWithoutRole === SsoUsersWithoutRole.CREATE_WITHOUT_ROLE
        ? SsoUsersWithoutRole.CREATE_WITHOUT_ROLE
        : SsoUsersWithoutRole.REFUSE
}

/**
 * The settings-input fragment for the setting: present only on the default organization and only
 * when the value differs from what was loaded (the mutation is a PATCH; an omitted field is kept).
 */
export function ssoUsersWithoutRoleInput (isDefaultOrg: boolean, value: SsoUsersWithoutRole,
    baseline: SsoUsersWithoutRole): { ssoUsersWithoutRole?: SsoUsersWithoutRole } {
    return isDefaultOrg && value !== baseline ? { ssoUsersWithoutRole: value } : {}
}
