import { describe, it, expect } from 'vitest'
import { parse, validate, type GraphQLSchema } from 'graphql'
import { proSchema } from './schemaDriftSupport'

// A split child's carried design and the proposed children's designFromParent (task TPF-164, T-6): what the task page
// reads is declared on Pro, with live and standing as the server resolves them. No CE row: the CE mirror lacks the
// fields until its sync.
const CARRIED_DESIGN_READS = parse(`
    query carriedDesignReads($uuid: ID!) {
        agentTask(uuid: $uuid) {
            carriedDesign { parentTask parentKey rounds { specification release round path } roles { role roleUuid signedOffAt session reviewedBy { kind uuid name } decidedOver live } at by { kind uuid name } standing }
            splitRequest { proposedChildren { title designFromParent } }
        }
    }
`)

describe('the carried design vs the Pro schema (skipped when it is not checked out alongside)', () => {
    it.runIf(proSchema)('Pro declares the carried design record and the proposed child flag', () => {
        expect(validate(proSchema as GraphQLSchema, CARRIED_DESIGN_READS).map(e => e.message)).toEqual([])
    })
})
