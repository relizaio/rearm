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

/**
 * The admin banner text when the instance has accounts outside its license, else null.
 * Numbers from `license { maxReadUsers maxWriteUsers globalUserCounts { readUsers writeUsers outsideLimit } }`.
 */
export function overLicenseBannerText (license: any): string | null {
    const counts = license?.globalUserCounts
    if (!counts || !(Number(counts.outsideLimit) > 0)) return null
    return `This instance is over its license: ${counts.readUsers ?? 0} read users of ${limitText(license.maxReadUsers)}, `
        + `${counts.writeUsers ?? 0} write users of ${limitText(license.maxWriteUsers)}. `
        + 'Accounts outside the limit, by creation date, cannot sign in.'
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

/** The confirmation text of "Remove accounts with no role", naming how many accounts hold no role. */
export function removeNoRoleConfirmText (noRoleUsers: number | null | undefined): string {
    const n = Number(noRoleUsers) || 0
    return `Deactivate ${n} account${n === 1 ? '' : 's'} with no role in any organization? `
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
