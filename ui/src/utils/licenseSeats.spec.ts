import { describe, expect, it } from 'vitest'
import { GraphQLEnumType, validate, type GraphQLSchema } from 'graphql'
import {
    LICENSE_BANNER_QUERY, LICENSE_SEAT_USERS_QUERY, NO_ORG_PERMISSION_TEXT, NO_ROLE_EMAILS_SHOWN, REMOVE_ACCOUNTS_WITHOUT_ROLE,
    SeatAdmission, SeatRefusalReason, SeatType, SsoUsersWithoutRole, countText, hasNoOrgPermission, mayReadLicense,
    noRoleDeactivationEmails, overLicenseBannerText, removeNoRoleConfirmText, seatRefusalOf, seatRefusalOfError,
    seatUserCompare, sortSeatUsersDefault, withCombinedPermissionsOf
} from './licenseSeats'
import { proSchema, enumValuesOf } from './schemaDriftSupport'

// TPF-168 (TPF-165 4.2 U6, D8, D10): the license seat rules and documents of the UI. The three
// documents are statically parseable, so scripts/validate-graphql checks them as well; this spec
// pins them and the four enums against the Pro schema as merged by TPF-167.

const ORG = '11111111-1111-4111-8111-111111111111'
const GLOBAL_ADMIN = { uuid: 'u1', installationType: 'MANAGED_SERVICE', isGlobalAdmin: true, permissions: { permissions: [] } }
const ORG_ADMIN = { ...GLOBAL_ADMIN, isGlobalAdmin: false,
    permissions: { permissions: [{ org: ORG, object: ORG, scope: 'ORGANIZATION', type: 'ADMIN' }] } }
const READER = { ...GLOBAL_ADMIN, isGlobalAdmin: false,
    permissions: { permissions: [{ org: ORG, object: ORG, scope: 'ORGANIZATION', type: 'READ_ONLY' }] } }

describe('seatRefusalOf', () => {
    const entry = (reason: string, code = 'LICENSE_SEAT_REFUSED') => ({ message: 'text ' + reason, extensions: { code, reason } })

    it('takes the first entry with the seat code and a known reason, message as is', () => {
        expect(seatRefusalOf([{ message: 'other' }, entry('OVER_LIMIT')])).toEqual({ reason: 'OVER_LIMIT', message: 'text OVER_LIMIT' })
        for (const r of ['SEAT_FULL', 'OVER_LIMIT', 'NO_ROLE']) expect(seatRefusalOf([entry(r)])?.reason).toBe(r)
    })

    it('ignores another code, an unknown reason, and nothing at all', () => {
        expect(seatRefusalOf([entry('NO_ROLE', 'BAD_REQUEST')])).toBeNull()
        expect(seatRefusalOf([entry('OTHER')])).toBeNull()
        expect(seatRefusalOf([])).toBeNull()
        expect(seatRefusalOf(null)).toBeNull()
        expect(seatRefusalOf(undefined)).toBeNull()
    })

    it('reads a thrown error the way the rest of the UI does (Apollo 4 errors, Apollo 3 graphQLErrors)', () => {
        expect(seatRefusalOfError({ errors: [entry('SEAT_FULL')] })?.reason).toBe('SEAT_FULL')
        expect(seatRefusalOfError({ graphQLErrors: [entry('NO_ROLE')] })?.reason).toBe('NO_ROLE')
        expect(seatRefusalOfError(new Error('x'))).toBeNull()
    })
})

describe('banner rules', () => {
    it('mayReadLicense: a global admin or an organization ADMIN, never OSS or nobody', () => {
        expect(mayReadLicense(GLOBAL_ADMIN)).toBe(true)
        expect(mayReadLicense(ORG_ADMIN)).toBe(true)
        expect(mayReadLicense(READER)).toBe(false)
        expect(mayReadLicense({ ...GLOBAL_ADMIN, installationType: 'OSS' })).toBe(false)
        expect(mayReadLicense(null)).toBe(false)
        // an ADMIN grant on an object inside the organization is not an organization ADMIN
        expect(mayReadLicense({ ...READER, permissions: { permissions: [{ org: ORG, object: 'x', scope: 'COMPONENT', type: 'ADMIN' }] } })).toBe(false)
    })

    it('overLicenseBannerText: the exact text, null with nobody outside, unlimited for an absent limit', () => {
        expect(overLicenseBannerText({ maxReadUsers: 1, maxWriteUsers: 2, globalUserCounts: { readUsers: 2, writeUsers: 3, outsideLimit: 2 } }))
            .toBe('This instance is over its license: 2 read users of 1, 3 write users of 2. Accounts outside the limit, by creation date, cannot sign in. Please contact support.')
        expect(overLicenseBannerText({ maxReadUsers: 1, maxWriteUsers: 2, globalUserCounts: { readUsers: 1, writeUsers: 2, outsideLimit: 0 } })).toBeNull()
        expect(overLicenseBannerText({ maxReadUsers: 1, maxWriteUsers: 2, globalUserCounts: null })).toBeNull()
        expect(overLicenseBannerText(null)).toBeNull()
        expect(overLicenseBannerText({ maxWriteUsers: 1, maxReadUsers: null, globalUserCounts: { readUsers: 4, writeUsers: 2, outsideLimit: 1 } }))
            .toBe('This instance is over its license: 4 read users of unlimited, 2 write users of 1. Accounts outside the limit, by creation date, cannot sign in. Please contact support.')
    })

    it('overLicenseBannerText: singular for a count of one, plural otherwise (zero included)', () => {
        expect(overLicenseBannerText({ maxReadUsers: 1, maxWriteUsers: 232, globalUserCounts: { readUsers: 2, writeUsers: 233, outsideLimit: 2 } }))
            .toBe('This instance is over its license: 2 read users of 1, 233 write users of 232. Accounts outside the limit, by creation date, cannot sign in. Please contact support.')
        expect(overLicenseBannerText({ maxReadUsers: 1, maxWriteUsers: 0, globalUserCounts: { readUsers: 1, writeUsers: 1, outsideLimit: 1 } }))
            .toBe('This instance is over its license: 1 read user of 1, 1 write user of 0. Accounts outside the limit, by creation date, cannot sign in. Please contact support.')
        expect(overLicenseBannerText({ maxReadUsers: 0, maxWriteUsers: 0, globalUserCounts: { readUsers: 0, writeUsers: 2, outsideLimit: 2 } }))
            .toMatch(/^This instance is over its license: 0 read users of 0, 2 write users of 0\. /)
    })

    it('countText', () => {
        expect(countText(1, 'account')).toBe('1 account')
        expect(countText(0, 'account')).toBe('0 accounts')
        expect(countText(2, 'account')).toBe('2 accounts')
        expect(countText(null, 'read user')).toBe('0 read users')
        expect(countText(3, 'more', 'more')).toBe('3 more')
    })

    it('removeNoRoleConfirmText names the number of accounts and lists their emails', () => {
        const tail = 'Global administrators and your own account are never deactivated. A deactivated account can be reactivated later.'
        expect(removeNoRoleConfirmText(['a@x.io', 'b@x.io', 'c@x.io'])).toBe('Deactivate 3 accounts with no role in any organization? '
            + 'Accounts to deactivate: a@x.io, b@x.io, c@x.io. ' + tail)
        expect(removeNoRoleConfirmText(['a@x.io'])).toBe('Deactivate 1 account with no role in any organization? '
            + 'Account to deactivate: a@x.io. ' + tail)
        expect(removeNoRoleConfirmText([])).toBe('Deactivate 0 accounts with no role in any organization? ' + tail)
        expect(removeNoRoleConfirmText(undefined)).toMatch(/^Deactivate 0 accounts/)
    })

    it('removeNoRoleConfirmText shows the first 20 emails, then "and N more"', () => {
        expect(NO_ROLE_EMAILS_SHOWN).toBe(20)
        const emails = Array.from({ length: 25 }, (_, i) => `u${i + 1}@x.io`)
        const text = removeNoRoleConfirmText(emails)
        expect(text).toMatch(/^Deactivate 25 accounts with no role in any organization\? Accounts to deactivate: u1@x\.io, /)
        expect(text).toContain('u20@x.io and 5 more. Global administrators')
        expect(text).not.toContain('u21@x.io')
        expect(removeNoRoleConfirmText(emails.slice(0, 20))).toContain('u20@x.io. Global administrators')
        expect(removeNoRoleConfirmText(emails.slice(0, 21))).toContain('u20@x.io and 1 more. ')
    })

    it('noRoleDeactivationEmails: seat type NONE, never a global administrator, never the caller (by uuid or email)', () => {
        const row = (email: string, seatType: string, globalAdmin = false, userUuids = [email + '-id']) =>
            ({ email, seatType, globalAdmin, userUuids })
        const rows = [row('w@x.io', 'WRITE'), row('r@x.io', 'READ'), row('n1@x.io', 'NONE'), row('ga@x.io', 'NONE', true),
            row('me@x.io', 'NONE', false, ['me-id']), row('Self@X.io', 'NONE', false, ['other-id']), row('n2@x.io', 'NONE')]
        expect(noRoleDeactivationEmails(rows, { uuid: 'me-id', email: 'self@x.io' })).toEqual(['n1@x.io', 'n2@x.io'])
        expect(noRoleDeactivationEmails(rows, null)).toEqual(['n1@x.io', 'me@x.io', 'Self@X.io', 'n2@x.io'])
        expect(noRoleDeactivationEmails(null, null)).toEqual([])
    })
})

describe('the no-permission banner rule', () => {
    const user = (perms: any[], extra: any = {}) => ({ uuid: 'u1', isGlobalAdmin: false, permissions: { permissions: perms }, ...extra })
    const orgPerm = (type: string, org = ORG) => ({ org, object: org, scope: 'ORGANIZATION', type })

    it('the text', () => {
        expect(NO_ORG_PERMISSION_TEXT).toBe('You were granted no permissions in this organization. Please contact your administrator.')
    })

    it('true with no organization-wide permission above NONE in the current organization', () => {
        expect(hasNoOrgPermission(user([]), ORG)).toBe(true)
        expect(hasNoOrgPermission(user([orgPerm('NONE')]), ORG)).toBe(true)
        // a grant in another organization, or on an object inside this one, is not organization-wide here
        expect(hasNoOrgPermission(user([orgPerm('ADMIN', 'other')]), ORG)).toBe(true)
        expect(hasNoOrgPermission(user([{ org: ORG, object: 'p', scope: 'PERSPECTIVE', type: 'READ_ONLY' }]), ORG)).toBe(true)
    })

    it('false with any organization-wide permission above NONE (the combined permissions include user groups)', () => {
        for (const t of ['ESSENTIAL_READ', 'READ_ONLY', 'READ_WRITE', 'ADMIN']) {
            expect(hasNoOrgPermission(user([orgPerm(t)]), ORG)).toBe(false)
        }
        // the permissions block is a map in the store as well as a list
        expect(hasNoOrgPermission(user({ k: orgPerm('READ_ONLY') } as any), ORG)).toBe(false)
    })

    it('false for a global administrator and for missing data', () => {
        expect(hasNoOrgPermission(user([], { isGlobalAdmin: true }), ORG)).toBe(false)
        expect(hasNoOrgPermission(user([]), null)).toBe(false)
        expect(hasNoOrgPermission(user([]), '')).toBe(false)
        expect(hasNoOrgPermission(null, ORG)).toBe(false)
        expect(hasNoOrgPermission({}, ORG)).toBe(false)
        expect(hasNoOrgPermission({ uuid: 'u1', isGlobalAdmin: false }, ORG)).toBe(false)
    })

    it('withCombinedPermissionsOf keeps the stored combined permissions of the same user', () => {
        const stored = { uuid: 'u1', name: 'Old', permissions: { permissions: [orgPerm('READ_ONLY')] } }
        const returned = { uuid: 'u1', name: 'New', permissions: { permissions: [] } }
        expect(withCombinedPermissionsOf(stored, returned)).toEqual({ uuid: 'u1', name: 'New', permissions: stored.permissions })
        expect(withCombinedPermissionsOf({ ...stored, uuid: 'u2' }, returned)).toBe(returned)
        expect(withCombinedPermissionsOf({}, returned)).toBe(returned)
        expect(withCombinedPermissionsOf(stored, null)).toBeNull()
    })
})

describe('seat view sorting', () => {
    const r = (email: string, admission: string, createdDate: string | null, extra: any = {}) =>
        ({ email, admission, createdDate, name: null, seatType: 'WRITE', lastLoginDate: null, ...extra })
    const emails = (rows: any[]) => rows.map(x => x.email)

    it('default: outside the limit first, then by creation date (oldest first)', () => {
        const rows = [r('a@x.io', 'INSIDE', '2026-01-01T00:00:00Z'), r('b@x.io', 'NOT_COUNTED', '2024-01-01T00:00:00Z'),
            r('c@x.io', 'OUTSIDE', '2026-03-01T00:00:00Z'), r('d@x.io', 'OUTSIDE', '2026-02-01T00:00:00Z'),
            r('e@x.io', 'INSIDE', '2025-01-01T00:00:00Z')]
        expect(emails(sortSeatUsersDefault(rows))).toEqual(['d@x.io', 'c@x.io', 'e@x.io', 'a@x.io', 'b@x.io'])
        expect(emails(rows)).toEqual(['a@x.io', 'b@x.io', 'c@x.io', 'd@x.io', 'e@x.io'])
        expect(sortSeatUsersDefault(null)).toEqual([])
    })

    it('column comparators', () => {
        const rows = [
            r('b@x.io', 'INSIDE', '2025-01-01T00:00:00Z', { name: 'Zed', seatType: 'READ', lastLoginDate: '2026-05-01T00:00:00Z' }),
            r('A@x.io', 'INSIDE', '2024-01-01T00:00:00Z', { name: null, seatType: 'NONE', lastLoginDate: null }),
            r('c@x.io', 'INSIDE', '2026-01-01T00:00:00Z', { name: 'adam', seatType: 'WRITE', lastLoginDate: '2026-01-01T00:00:00Z' }),
        ]
        const by = (cmp: (a: any, b: any) => number) => emails([...rows].sort(cmp))
        expect(by(seatUserCompare.email)).toEqual(['A@x.io', 'b@x.io', 'c@x.io'])
        expect(by(seatUserCompare.name)).toEqual(['c@x.io', 'b@x.io', 'A@x.io'])
        expect(by(seatUserCompare.seatType)).toEqual(['c@x.io', 'b@x.io', 'A@x.io'])
        expect(by(seatUserCompare.createdDate)).toEqual(['A@x.io', 'b@x.io', 'c@x.io'])
        expect(by(seatUserCompare.lastLoginDate)).toEqual(['c@x.io', 'b@x.io', 'A@x.io'])
    })
})

describe('license seat documents and enums vs the Pro schema (skipped without the Pro schema)', () => {
    it.runIf(proSchema).each([
        ['licenseBanner', LICENSE_BANNER_QUERY],
        ['licenseSeatUsers', LICENSE_SEAT_USERS_QUERY],
        ['removeAccountsWithoutRole', REMOVE_ACCOUNTS_WITHOUT_ROLE],
    ])('%s is valid', (_name, doc) => {
        expect(validate(proSchema as GraphQLSchema, doc).map(e => e.message)).toEqual([])
    })

    it.runIf(proSchema).each([
        ['SeatType', SeatType],
        ['SeatAdmission', SeatAdmission],
        ['SsoUsersWithoutRole', SsoUsersWithoutRole],
    ])('the UI members of %s are the schema enum, in order', (name, members) => {
        expect(enumValuesOf(proSchema as GraphQLSchema, name)).toEqual(Object.values(members))
    })

    it.runIf(proSchema)('LICENSE_SEAT_EVENT is a notification event type', () => {
        const t = (proSchema as GraphQLSchema).getType('NotificationEventTypeEnum') as GraphQLEnumType
        expect(t.getValues().map(v => v.name)).toContain('LICENSE_SEAT_EVENT')
    })

    it('the refusal reasons are the three kinds of the error contract', () => {
        expect(Object.values(SeatRefusalReason)).toEqual(['SEAT_FULL', 'OVER_LIMIT', 'NO_ROLE'])
    })
})
