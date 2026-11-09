import { describe, it, expect } from 'vitest'
import { parse, validate, type GraphQLSchema } from 'graphql'
import { proSchema } from './schemaDriftSupport'

// The architect's design checkpoint waiver (task TPF-153, T-14): what the task page, the hop log and the board
// form read and save is declared on Pro. No CE row: the CE mirror lacks the fields until its sync.
const WAIVER_READS = parse(`
    query waiverReads($uuid: ID!, $orgUuid: ID!) {
        agentTask(uuid: $uuid) {
            designCheckpointWaiver { bySession byAgent role roleUuid at designRound note standing }
            signOffs { noSplit }
        }
        agentBoardsOfOrg(orgUuid: $orgUuid) { architectCheckpointWaiver effectiveArchitectCheckpointWaiver }
    }
`)
const WAIVER_SWITCH = parse(`
    mutation waiverSwitch($boardUuid: ID!) {
        agentBoardUpdate(boardUuid: $boardUuid, input: { settings: { architectCheckpointWaiver: false } }) { uuid }
    }
`)

describe('the design checkpoint waiver vs the Pro schema (skipped when it is not checked out alongside)', () => {
    it.runIf(proSchema)('Pro declares the waiver, the sign-off noSplit and the board switch', () => {
        expect(validate(proSchema as GraphQLSchema, WAIVER_READS).map(e => e.message)).toEqual([])
        expect(validate(proSchema as GraphQLSchema, WAIVER_SWITCH).map(e => e.message)).toEqual([])
    })
})
