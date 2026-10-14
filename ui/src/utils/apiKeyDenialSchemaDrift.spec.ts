import { describe, it, expect } from 'vitest'
import { parse, validate, type GraphQLSchema } from 'graphql'
import { ceSchema, proSchema } from './schemaDriftSupport'

// The key tables' queries (OrgSettings apiKeys, UserProfile myApiKeys) select deniedReason next to status and notes,
// so apiKeyDeniedReason can tell a denial's reason from the requester's note (task t20261013-182431-31189). The CE
// mirror declares the field, always null there: CE still writes a denial's reason into the notes, so the same
// queries load on a CE install and read every denial off the notes. DeclaredApiKey carries it too, as on Pro.
const DENIAL_FIELDS = parse(`
    query keyDenials($orgUuid: ID!) {
        apiKeys(orgUuid: $orgUuid) { uuid status notes deniedReason }
        myApiKeys { uuid status notes deniedReason }
        apiKeysProgrammatic { uuid status notes deniedReason }
    }
`)

describe('key denial fields vs the CE mirror schema (in-repo, always runs)', () => {
    it('the CE mirror declares deniedReason, so the key queries load on CE', () => {
        expect(validate(ceSchema, DENIAL_FIELDS).map(e => e.message)).toEqual([])
    })
})

describe('key denial fields vs the Pro schema (skipped when it is not checked out alongside)', () => {
    it.runIf(proSchema)('Pro declares deniedReason', () => {
        expect(validate(proSchema as GraphQLSchema, DENIAL_FIELDS).map(e => e.message)).toEqual([])
    })
})
