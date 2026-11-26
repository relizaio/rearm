import { describe, expect, it } from 'vitest'
import { GraphQLEnumType, type GraphQLSchema } from 'graphql'
import { UPSTREAM_ALERT_EVENT_TYPE, upstreamInboxLinksOf } from './upstreamInboxLinks'
import { proSchema } from './schemaDriftSupport'

// T-542 (TPF-185 design 3.13): the links of an upstream alert are built from uuids of the payload
// only, behind the edition gate; anything else gives no link and never an error.
const ORG = '11111111-2222-3333-4444-555555555555'
const FOLLOW = 'cccccccc-0000-4000-8000-000000000001'
const SOURCE = 'aaaaaaaa-0000-4000-8000-000000000001'
const HOSTILE = '<img src=x onerror=alert(1)>'
const PRO = 'SAAS'

function payload (over: any = {}) {
    return { kind: 'DRIFT_HELD', org: ORG, source: SOURCE, sourceName: 'Acme TEA', follow: FOLLOW, run: null, sync: null, ...over }
}

function row (p: any, eventType = 'UPSTREAM_ALERT') {
    return { eventType, payloadJson: p }
}

const NONE = { follow: null, source: null }

describe('T-542 upstreamInboxLinksOf', () => {
    it('gives the follow link and no source link for a DRIFT_HELD payload as an object and as a JSON string', () => {
        const fromObject = upstreamInboxLinksOf(row(payload()), PRO)
        const fromString = upstreamInboxLinksOf(row(JSON.stringify(payload())), PRO)
        expect(fromObject).toEqual({ follow: `/upstreamFollow/${ORG}/${FOLLOW}`, source: null })
        expect(fromString).toEqual(fromObject)
    })

    it('gives the same follow link for the other kinds that name a follow', () => {
        for (const kind of ['DRIFT_ADAPTED', 'NEW_COMPONENT', 'LINEAGE_HELD_ELSEWHERE']) {
            expect(upstreamInboxLinksOf(row(payload({ kind })), PRO), kind).toEqual({ follow: `/upstreamFollow/${ORG}/${FOLLOW}`, source: null })
        }
    })

    it('gives the Sources tab and no follow link for SOURCE_FAILING with a null follow', () => {
        expect(upstreamInboxLinksOf(row(payload({ kind: 'SOURCE_FAILING', follow: null })), PRO))
            .toEqual({ follow: null, source: `/orgSettings/${ORG}?tab=upstreamSources` })
    })

    it('gives the Sources tab for a missing follow with a uuid source', () => {
        const p: any = payload({ kind: 'NEW_COMPONENT' })
        delete p.follow
        expect(upstreamInboxLinksOf(row(p), PRO)).toEqual({ follow: null, source: `/orgSettings/${ORG}?tab=upstreamSources` })
        expect(upstreamInboxLinksOf(row(payload({ kind: 'NEW_COMPONENT', follow: null, source: '../x' })), PRO)).toEqual(NONE)
    })

    it('gives no link at all for an org that is a path', () => {
        expect(upstreamInboxLinksOf(row(payload({ org: '../../admin' })), PRO)).toEqual(NONE)
        expect(upstreamInboxLinksOf(row(payload({ org: '../../admin', kind: 'SOURCE_FAILING', follow: null })), PRO)).toEqual(NONE)
        expect(upstreamInboxLinksOf(row(payload({ org: undefined })), PRO)).toEqual(NONE)
    })

    it('gives no follow link for a follow that is a path', () => {
        const links = upstreamInboxLinksOf(row(payload({ follow: 'x/../../orgSettings' })), PRO)
        expect(links.follow).toBeNull()
        expect(JSON.stringify(links)).not.toContain('x/../../orgSettings')
    })

    it('puts no text of the payload into a link', () => {
        for (const p of [payload({ sourceName: HOSTILE }), payload({ kind: 'SOURCE_FAILING', follow: null, sourceName: HOSTILE })]) {
            const links = upstreamInboxLinksOf(row(p), PRO)
            expect(links.follow ?? links.source).toBeTruthy()
            expect(JSON.stringify(links)).not.toContain(HOSTILE)
            expect(JSON.stringify(links)).not.toContain('Acme')
        }
    })

    it('gives no link and does not throw for a payload that is not JSON, null, an array or a number', () => {
        for (const p of ['{not json', null, undefined, '', '[1,2]', '42', 42, '"text"']) {
            expect(() => upstreamInboxLinksOf(row(p), PRO)).not.toThrow()
            expect(upstreamInboxLinksOf(row(p), PRO), String(p)).toEqual(NONE)
        }
        expect(upstreamInboxLinksOf(null, PRO)).toEqual(NONE)
        expect(upstreamInboxLinksOf(undefined, PRO)).toEqual(NONE)
    })

    it('gives no link for another event type with a valid payload', () => {
        for (const eventType of ['AGENT_BOARD_ALERT', 'RELEASE_CREATED', 'upstream_alert', undefined]) {
            expect(upstreamInboxLinksOf({ eventType, payloadJson: payload() }, PRO), String(eventType)).toEqual(NONE)
        }
    })

    it('gives no link when the gate is false: CE, and a user that has not loaded', () => {
        for (const installationType of ['OSS', undefined, null, '']) {
            expect(upstreamInboxLinksOf(row(payload()), installationType as any), String(installationType)).toEqual(NONE)
            expect(upstreamInboxLinksOf(row(payload({ kind: 'SOURCE_FAILING', follow: null })), installationType as any)).toEqual(NONE)
        }
    })
})

describe('the event type of the upstream alert', () => {
    it.runIf(proSchema)('is a notification event type of the Pro schema', () => {
        const t = (proSchema as GraphQLSchema).getType('NotificationEventTypeEnum') as GraphQLEnumType
        expect(t.getValues().map(v => v.name)).toContain(UPSTREAM_ALERT_EVENT_TYPE)
    })
})
