import { describe, it, expect } from 'vitest'
import { readFileSync, existsSync } from 'fs'
import { fileURLToPath } from 'url'
import { buildSchema, coerceInputValue, GraphQLError, type GraphQLSchema } from 'graphql'
import { attestationVariables, emptyAttestationForm } from './supportAttestationInput'

/**
 * Coerces the built variables against the REAL schema's argument types.
 *
 * Document validation -- which the other drift specs do -- checks structure. It cannot see an
 * enum VALUE passed as a variable, which is how two invented SupportParty members
 * (MANUFACTURER, SUPPLIER, borrowed from unrelated enums) reached the browser. They failed at
 * coercion, which is a validation error, which isSchemaDriftError reports as drift -- so the
 * operator was told to upgrade a backend that was perfectly capable.
 *
 * routeInputSchemaDrift.spec.ts already establishes coerceInputValue as the tool for this in
 * this repo. This applies it to the attestation write.
 */
const PRO = fileURLToPath(new URL(
    '../../../../rearm-core/backend/src/main/resources/schema/schema.graphqls', import.meta.url))
const schema: GraphQLSchema | null = existsSync(PRO) ? buildSchema(readFileSync(PRO, 'utf8')) : null

function argType (name: string) {
    const field = schema!.getMutationType()!.getFields()['setSbomComponentSupport']
    const arg = field.args.find(a => a.name === name)
    if (!arg) throw new Error(`setSbomComponentSupport has no argument ${name}`)
    return arg.type
}

function coerce (name: string, value: unknown): string[] {
    const errors: string[] = []
    coerceInputValue(value, argType(name), (_p, _v, err: GraphQLError) => errors.push(err.message))
    return errors
}

describe.runIf(schema)('attestation variables coerce against the real mutation', () => {
    it('every argument the builder emits exists on the mutation', () => {
        const form = emptyAttestationForm()
        form.levelOfSupport = 'ACTIVELY_MAINTAINED'
        form.party = 'FIRST_PARTY'
        form.justification = 'basis'
        form.supportNotes = 'note'
        form.reason = 'why'
        form.endOfSupportDate = '2030-01-01'
        form.clearMilestones = ['END_OF_LIFE']
        form.state = 'ATTESTED'
        const vars = attestationVariables('c-1', form)
        for (const key of Object.keys(vars)) {
            expect(() => argType(key), `${key} is not an argument of setSbomComponentSupport`)
                .not.toThrow()
        }
    })

    // The assertion that would have caught the invented party values before the browser did.
    it.each(['FIRST_PARTY', 'THIRD_PARTY'])('supportParty %s is accepted', (value) => {
        expect(coerce('supportParty', value)).toEqual([])
    })

    it.each(['MANUFACTURER', 'SUPPLIER'])('rejects %s, which is not a SupportParty', (value) => {
        expect(coerce('supportParty', value).length).toBeGreaterThan(0)
    })

    it.each(['ACTIVELY_MAINTAINED', 'NO_LONGER_MAINTAINED', 'ABANDONED'])(
        'levelOfSupport %s is accepted', (v) => expect(coerce('levelOfSupport', v)).toEqual([]))

    it.each(['END_OF_GUARANTEED_SUPPORT', 'END_OF_SUPPORT', 'END_OF_LIFE'])(
        'clearMilestones accepts %s', (v) => expect(coerce('clearMilestones', [v])).toEqual([]))

    it('state ATTESTED is accepted', () => expect(coerce('state', 'ATTESTED')).toEqual([]))
})
