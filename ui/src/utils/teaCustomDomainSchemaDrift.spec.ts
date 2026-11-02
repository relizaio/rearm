import { describe, expect, it } from 'vitest'
import { execute, validate } from 'graphql'
import graphqlQueries from './graphqlQueries'
import { TEA_SERVING_LABELS, TEA_SERVING_MODES, TEA_WELL_KNOWN_HOSTINGS } from './teaProfile'
import { enumValuesOf, proSchema } from './schemaDriftSupport'

// TEA custom domains (task TPF-144, architecture round 3 section 6.5): the six documents of the
// System Settings section and the organization TEA panel validate on the Pro schema, the component
// specs' fixtures are exactly what those documents select, and the serving and hosting lists are
// the schema's enums. Pro only by design: each row runs when the Pro schema is found (a sibling
// rearm-core checkout, or REARM_PRO_SCHEMA) and reports SKIPPED otherwise.

const ACME = { uuid: '11111111-1111-4111-8111-111111111111', name: 'Acme' }
const BETA = { uuid: '22222222-2222-4222-8222-222222222222', name: 'beta corp' }
const HOSTILE = { uuid: '33333333-3333-4333-8333-333333333333', name: '<img src=x onerror=alert(1)>' }
const ROWS = [
    { host: 'tea.acme.example', org: ACME.uuid, orgName: 'Acme', isBaseuriHost: false, inAllowList: true, teaServing: 'DOMAIN' },
    { host: 'tea.beta.example', org: null, orgName: null, isBaseuriHost: false, inAllowList: true, teaServing: null },
    { host: 'rearm.example.com', org: null, orgName: null, isBaseuriHost: true, inAllowList: true, teaServing: null },
    { host: 'old.gamma.example', org: HOSTILE.uuid, orgName: HOSTILE.name, isBaseuriHost: false, inAllowList: false, teaServing: 'NONE' },
]
const ASSIGNED_ROW = { ...ROWS[1], org: BETA.uuid, orgName: 'beta corp', teaServing: 'NONE' }
const HOST = 'tea.acme.example'
const DOMAIN_API = 'https://' + HOST + '/tea'
const DOMAIN_HOSTED = { org: '44444444-4444-4444-8444-444444444444', teaUuid: '55555555-5555-4555-8555-555555555555',
    apiBase: DOMAIN_API, wellKnownDocument: '{"schemaVersion":1,"endpoints":[{"url":"' + DOMAIN_API + '","versions":["1.0.0"],"priority":1}]}',
    assignedHost: HOST, teaServing: 'DOMAIN', teaWellKnownHosting: 'DOMAIN', wellKnownUrl: 'https://' + HOST + '/.well-known/tea' }

async function run (doc: any, rootValue: any, variableValues: Record<string, any> = {}) {
    const r: any = await execute({ schema: proSchema!, document: doc, rootValue, variableValues })
    return { errors: r.errors, data: JSON.parse(JSON.stringify(r.data)) }
}

describe('TEA custom domain documents on the Pro schema', () => {
    it.runIf(proSchema)('N1: the six documents validate', () => {
        for (const name of ['TeaHostAssignmentsGql', 'AssignTeaHostGql', 'UnassignTeaHostGql', 'AllOrganizationsGql',
            'TeaOrgServingGql', 'SetTeaOrgServingGql']) {
            expect(validate(proSchema!, (graphqlQueries as any)[name]).map(e => e.message), name).toEqual([])
        }
    })

    it.runIf(proSchema)('N2: the System Settings fixtures are exactly what the documents select', async () => {
        let r = await run(graphqlQueries.TeaHostAssignmentsGql, { teaHostAssignments: () => ROWS })
        expect(r.errors).toBeUndefined()
        expect(r.data).toEqual({ teaHostAssignments: ROWS })
        r = await run(graphqlQueries.AllOrganizationsGql, { allOrganizations: () => [BETA, ACME, HOSTILE] })
        expect(r.errors).toBeUndefined()
        expect(r.data).toEqual({ allOrganizations: [BETA, ACME, HOSTILE] })
        r = await run(graphqlQueries.AssignTeaHostGql, { assignTeaHost: () => ASSIGNED_ROW }, { host: 'tea.beta.example', org: BETA.uuid })
        expect(r.errors).toBeUndefined()
        expect(r.data).toEqual({ assignTeaHost: ASSIGNED_ROW })
        r = await run(graphqlQueries.UnassignTeaHostGql, { unassignTeaHost: () => true }, { host: 'old.gamma.example' })
        expect(r.errors).toBeUndefined()
        expect(r.data).toEqual({ unassignTeaHost: true })
    })

    it.runIf(proSchema)('N3: the organization panel fixtures likewise', async () => {
        let r = await run(graphqlQueries.TeaOrgServingGql, { teaOrgDiscovery: () => DOMAIN_HOSTED }, { org: DOMAIN_HOSTED.org })
        expect(r.errors).toBeUndefined()
        expect(r.data).toEqual({ teaOrgDiscovery: DOMAIN_HOSTED })
        r = await run(graphqlQueries.SetTeaOrgServingGql, { setTeaOrgServing: () => DOMAIN_HOSTED },
            { org: DOMAIN_HOSTED.org, teaServing: 'DOMAIN', teaWellKnownHosting: 'DOMAIN' })
        expect(r.errors).toBeUndefined()
        expect(r.data).toEqual({ setTeaOrgServing: DOMAIN_HOSTED })
    })

    it.runIf(proSchema)('N4: the serving and hosting lists are the schema enums', () => {
        expect(enumValuesOf(proSchema!, 'TeaServing')).toEqual([...TEA_SERVING_MODES])
        expect(enumValuesOf(proSchema!, 'TeaWellKnownHosting')).toEqual([...TEA_WELL_KNOWN_HOSTINGS])
        expect(Object.keys(TEA_SERVING_LABELS)).toEqual([...TEA_SERVING_MODES])
    })
})
