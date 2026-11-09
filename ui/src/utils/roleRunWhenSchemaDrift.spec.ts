import { describe, it, expect } from 'vitest'
import { parse, validate, type GraphQLSchema } from 'graphql'
import { proSchema } from './schemaDriftSupport'

// A role's runWhen condition and the pass-over record (task TPF-156, T-115): what the task page, the role form and the
// roles table read and save is declared on Pro. No CE row: the CE mirror lacks the fields until its sync.
const RUN_WHEN_READS = parse(`
    query runWhenReads($uuid: ID!, $boardUuid: ID!) {
        agentTask(uuid: $uuid) {
            rolesSkippedByCondition { role roleUuid reason at by { kind uuid name } waives live }
        }
        agentTaskRoleConfigsOfBoard(boardUuid: $boardUuid) { runWhen { taggedAny taggedNone } }
    }
`)
const RUN_WHEN_SET = parse(`
    mutation runWhenSet($boardUuid: ID!) {
        agentTaskRoleConfigSet(boardUuid: $boardUuid, input: { name: "x", runWhen: { taggedAny: ["mutation"], taggedNone: [] } }) { uuid }
    }
`)

describe('the runWhen condition vs the Pro schema (skipped when it is not checked out alongside)', () => {
    it.runIf(proSchema)('Pro declares the pass-over record, the role read and the role input', () => {
        expect(validate(proSchema as GraphQLSchema, RUN_WHEN_READS).map(e => e.message)).toEqual([])
        expect(validate(proSchema as GraphQLSchema, RUN_WHEN_SET).map(e => e.message)).toEqual([])
    })
})
