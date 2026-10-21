// Task TEA-30 round 4, design 3.7.1 and row 43 (decision D19): the text of a failed GraphQL call is
// the refusal entry, not Apollo's message that joins it with graphql-java's null-propagation entry.
import { describe, it, expect } from 'vitest'
import {
    NULL_PROPAGATION_CLASSIFICATION, graphqlErrorEntries, graphqlRefusalText, parseGraphQLError,
} from './graphqlErrorText'

const R = 'SBOM profile top of the organization cannot be deleted: the FDA_PREMARKET package settings of '
    + 'component <img src=x onerror=alert(1)> name it and it would no longer resolve there; point them at '
    + 'another profile first'
const NULL_TEXT = "The field at path '/deleteSbomProfile' was declared as a non null type, but the code involved in "
    + 'retrieving data has wrongly returned a null value.  The graphql specification requires that the parent '
    + 'field be set to null, or if that is non nullable that it bubble up null to its parent and so on. The '
    + "non-nullable type is 'Boolean' within parent type 'Mutation'"
const N = { message: NULL_TEXT, path: ['deleteSbomProfile'], extensions: { classification: 'NullValueInNonNullableField' } }
const REFUSAL = { message: R, extensions: { classification: 'BAD_REQUEST' } }

describe('graphqlRefusalText (row 43)', () => {
    it('the classification constant is graphql-java\'s', () => {
        expect(NULL_PROPAGATION_CLASSIFICATION).toBe('NullValueInNonNullableField')
    })

    it('(a) the Apollo 4 shape answers the refusal alone', () => {
        const err = { message: R + '\n' + NULL_TEXT, errors: [REFUSAL, N] }
        expect(graphqlRefusalText(err)).toBe(R)
        expect(graphqlRefusalText(Object.assign(new Error(R + '\n' + NULL_TEXT), { errors: [REFUSAL, N] }))).toBe(R)
    })

    it('(b) the filter is by classification, not position', () => {
        expect(graphqlRefusalText({ message: NULL_TEXT + '\n' + R, errors: [N, REFUSAL] })).toBe(R)
    })

    it('(c) the Apollo 3 and the network-result shapes answer the refusal', () => {
        expect(graphqlRefusalText({ message: R + '\n' + NULL_TEXT, graphQLErrors: [REFUSAL, N] })).toBe(R)
        expect(graphqlRefusalText({ message: 'Response not successful', networkError: { result: { errors: [N, REFUSAL] } } }))
            .toBe(R)
        expect(graphqlErrorEntries({ graphQLErrors: [N] })).toEqual([N])
        expect(graphqlErrorEntries({ errors: 'no', graphQLErrors: [REFUSAL] })).toEqual([REFUSAL])
        expect(graphqlErrorEntries({ message: 'x' })).toEqual([])
        expect(graphqlErrorEntries(null)).toEqual([])
    })

    it('(d) a one-entry answer passes through unchanged', () => {
        expect(graphqlRefusalText({ message: R, errors: [REFUSAL] })).toBe(R)
    })

    it('(e) entries of every other classification, or none, are kept', () => {
        const kept = [
            { message: 'Not authorized', extensions: { classification: 'DataFetchingException' } },
            { message: 'Instance not found', extensions: { classification: 'NOT_FOUND' } },
            { message: 'Internal server error (ref 1a2b3c4d)', extensions: { classification: 'INTERNAL_ERROR', ref: '1a2b3c4d' } },
            { message: 'no extensions at all' },
        ]
        for (const e of kept) {
            expect(graphqlRefusalText({ message: e.message + '\n' + NULL_TEXT, errors: [e, N] }), e.message).toBe(e.message)
            expect(graphqlRefusalText({ message: NULL_TEXT + '\n' + e.message, errors: [N, e] }), e.message).toBe(e.message)
        }
    })

    it('(f) an entry with a blank or missing message is skipped and the next one shown', () => {
        expect(graphqlRefusalText({ message: '\n' + R, errors: [{ message: '', extensions: { classification: 'BAD_REQUEST' } }, REFUSAL, N] }))
            .toBe(R)
        expect(graphqlRefusalText({ message: R, errors: [{ message: '   ' }, { extensions: {} }, REFUSAL] })).toBe(R)
    })

    it('(g) only the null-propagation entry falls back to the message, keeping a server bug visible', () => {
        expect(graphqlRefusalText({ message: NULL_TEXT, errors: [N] })).toBe(NULL_TEXT)
    })

    it('(h) without entries the message is shown as before, through parseGraphQLError', () => {
        expect(graphqlRefusalText(new Error('Rebom error: merge failed'))).toBe('merge failed')
        expect(graphqlRefusalText('x')).toBe('x')
        expect(graphqlRefusalText(undefined)).toBe('')
        expect(graphqlRefusalText(null)).toBe('')
        expect(graphqlRefusalText({ message: 'BOM validation failed: y', errors: [] })).toBe('y')
        expect(graphqlRefusalText({ errors: [{ message: 'Rebom error: z', extensions: { classification: 'BAD_REQUEST' } }, N] }))
            .toBe('z')
    })

    it('(i) the refusal comes back character for character, markup included', () => {
        const out = graphqlRefusalText({ message: R + '\n' + NULL_TEXT, errors: [REFUSAL, N] })
        expect(out).toBe(R)
        expect(out).toContain('<img src=x onerror=alert(1)>')
        expect(out).not.toContain('\n')
        expect(out).not.toContain('declared as a non null type')
    })

    it('(j) parseGraphQLError is still exported and unchanged', () => {
        expect(parseGraphQLError('BOM processing failed: x')).toBe('x')
        expect(parseGraphQLError('Rebom error: a')).toBe('a')
        expect(parseGraphQLError('plain')).toBe('plain')
    })
})
