import { describe, expect, it } from 'vitest'
import { GraphQLEnumType, validate, type GraphQLSchema } from 'graphql'
import {
    LICENSE_BANNER_QUERY, LICENSE_SEAT_USERS_QUERY, REMOVE_ACCOUNTS_WITHOUT_ROLE, SeatAdmission, SeatRefusalReason,
    SeatType, SsoUsersWithoutRole, mayReadLicense, overLicenseBannerText, removeNoRoleConfirmText, seatRefusalOf,
    seatRefusalOfError
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
            .toBe('This instance is over its license: 2 read users of 1, 3 write users of 2. Accounts outside the limit, by creation date, cannot sign in.')
        expect(overLicenseBannerText({ maxReadUsers: 1, maxWriteUsers: 2, globalUserCounts: { readUsers: 1, writeUsers: 2, outsideLimit: 0 } })).toBeNull()
        expect(overLicenseBannerText({ maxReadUsers: 1, maxWriteUsers: 2, globalUserCounts: null })).toBeNull()
        expect(overLicenseBannerText(null)).toBeNull()
        expect(overLicenseBannerText({ maxWriteUsers: 1, maxReadUsers: null, globalUserCounts: { readUsers: 4, writeUsers: 2, outsideLimit: 1 } }))
            .toBe('This instance is over its license: 4 read users of unlimited, 2 write users of 1. Accounts outside the limit, by creation date, cannot sign in.')
    })

    it('removeNoRoleConfirmText names the number of accounts', () => {
        expect(removeNoRoleConfirmText(3)).toMatch(/^Deactivate 3 accounts with no role in any organization\?/)
        expect(removeNoRoleConfirmText(1)).toMatch(/^Deactivate 1 account with no role/)
        expect(removeNoRoleConfirmText(undefined)).toMatch(/^Deactivate 0 accounts/)
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
