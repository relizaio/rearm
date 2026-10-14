import { describe, it, expect } from 'vitest'
import { parse, validate, type GraphQLSchema } from 'graphql'
import { ceSchema, proSchema } from './schemaDriftSupport'

// The key queries (OrgSettings apiKeys, UserProfile and CliLogin myApiKeys, the store's apiKeyRefsOfOrg) select
// deniedReason next to status and notes, so apiKeyDeniedReason can tell a denial's reason from the requester's
// note (task t20261013-182431-31189). The CE mirror declares the field, always null there, so the same queries
// load on a CE install and fall back to reading an older denial off the notes.
const DENIAL_FIELDS = parse(`
    query keyDenials($orgUuid: ID!) {
        apiKeys(orgUuid: $orgUuid) { uuid status notes deniedReason }
        myApiKeys { uuid status notes deniedReason }
    }
`)

describe('key denial fields vs the CE mirror schema (in-repo, always runs)', () => {
    it('the CE mirror declares deniedReason, so the key queries load on CE', () => {
        expect(validate(ceSchema, DENIAL_FIELDS).map(e => e.message)).toEqual([])
    })
})

describe('key denial fields vs the Pro schema (skipped if rearm-core absent)', () => {
    it.runIf(proSchema)('Pro declares deniedReason', () => {
        expect(validate(proSchema as GraphQLSchema, DENIAL_FIELDS).map(e => e.message)).toEqual([])
    })
})
