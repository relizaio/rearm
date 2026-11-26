import { describe, it, expect } from 'vitest'
import { coerceInputValue, GraphQLError, type GraphQLInputType, type GraphQLSchema } from 'graphql'
import { assessmentInputOf, emptyAssessmentForm } from './assessmentFields'
import { proSchema } from './schemaDriftSupport'

/**
 * FDAW-17c (FDAW-73 row 16): the assessment keys the triage dialogs merge into their mutation
 * input coerce against the Pro schema's CreateVulnAnalysisInput and UpdateVulnAnalysisInput.
 * Document validation cannot see an enum value passed as a variable; coercion can (the pattern
 * of attestationVariablesCoercion.spec.ts). CE has none of the keys (CE catch-up deferred), and
 * the dialogs send none there, so only Pro is checked; absent, the cells report SKIPPED.
 */
function inputType (schema: GraphQLSchema, mutation: string): GraphQLInputType {
    const field = schema.getMutationType()!.getFields()[mutation]
    const arg = field.args.find(a => a.name === 'analysis')
    if (!arg) throw new Error(`${mutation} has no argument analysis`)
    return arg.type
}

function coerceErrors (schema: GraphQLSchema, mutation: string, value: unknown): string[] {
    const errors: string[] = []
    coerceInputValue(value, inputType(schema, mutation), (_p, _v, err: GraphQLError) => errors.push(err.message))
    return errors
}

const ALL_THREE = assessmentInputOf(emptyAssessmentForm(), {
    discoveryMethod: 'SECURITY_TESTING',
    riskLevel: 'NEGLIGIBLE',
    controls: [{ type: 'COMPENSATING', description: 'network isolated' }, { type: 'PLANNED_UPDATE', description: 'next release' }]
})

const CREATE = {
    org: '00000000-0000-0000-0000-000000000001',
    location: 'pkg:npm/vite@5.4.8',
    locationType: 'PURL',
    findingId: 'CVE-2025-31125',
    findingType: 'VULNERABILITY',
    scope: 'ORG',
    scopeUuid: '00000000-0000-0000-0000-000000000001',
    state: 'EXPLOITABLE'
}

const UPDATE = {
    analysisUuid: '00000000-0000-0000-0000-0000000000aa',
    state: 'EXPLOITABLE'
}

describe.runIf(proSchema)('assessment input coerces against the Pro schema', () => {
    const schema = proSchema as GraphQLSchema

    it('the built keys are the three fields', () => {
        expect(Object.keys(ALL_THREE).sort()).toEqual(['controls', 'discoveryMethod', 'riskLevel'])
    })

    it('CreateVulnAnalysisInput with all three fields', () => {
        expect(coerceErrors(schema, 'createVulnAnalysis', { ...CREATE, ...ALL_THREE })).toEqual([])
    })

    it('UpdateVulnAnalysisInput with all three fields', () => {
        expect(coerceErrors(schema, 'updateVulnAnalysis', { ...UPDATE, ...ALL_THREE })).toEqual([])
    })

    it('an emptied controls list coerces (it clears the stored controls)', () => {
        const cleared = assessmentInputOf({ ...emptyAssessmentForm(), controls: [{ type: 'DESIGN', description: 'd' }] }, emptyAssessmentForm())
        expect(cleared).toEqual({ controls: [] })
        expect(coerceErrors(schema, 'updateVulnAnalysis', { ...UPDATE, ...cleared })).toEqual([])
    })

    it('a control of type OTHER does not coerce (the control of the test)', () => {
        const controls = [{ type: 'OTHER', description: 'x' }]
        expect(coerceErrors(schema, 'updateVulnAnalysis', { ...UPDATE, controls }).length).toBeGreaterThan(0)
        expect(coerceErrors(schema, 'createVulnAnalysis', { ...CREATE, controls }).length).toBeGreaterThan(0)
    })
})
