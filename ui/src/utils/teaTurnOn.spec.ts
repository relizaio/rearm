import { describe, it, expect, vi } from 'vitest'
vi.mock('./fetchClient', () => ({ fetchWithAuth: vi.fn(), fetchArrayBufferWithAuth: vi.fn() }))
import { teaTurnOnChildren } from './teaTurnOn'
import { teaServerAnswered } from './teaPublication'
import { TEA_TURN_ON_TEXT } from './teaProfile'

// FDAW-39 architecture-5 section 4, row 50 (f) to (k), (r) to (t) and (v): "Turn on for both", the
// sequence of teaTurnOn.ts, against a stub client that records [operation name, variables].

const ROW = {
    uuid: 'row-1', scope: 'COMPONENT', object: 'c1', mode: 'OVERRIDE', followedPerspective: null,
    publishing: 'ENABLED', visibility: 'PRIVATE', rawArtifacts: 'NONE', productComponents: 'PUBLISH_WITH_PRODUCT',
    minimumLifecycle: 'ASSEMBLED', tei: 'DISABLED', teiDomain: null, profiles: ['default'], revision: 1,
    autoPublish: 'DISABLED', autoPublishState: 'OFF',
}
const EIGHT = { publishing: 'ENABLED', visibility: 'PRIVATE', rawArtifacts: 'NONE', productComponents: 'PUBLISH_WITH_PRODUCT',
    minimumLifecycle: 'ASSEMBLED', tei: 'DISABLED', teiDomain: null, profiles: ['default'] }

const child = (name: string, version: string, ownRow: string, release: string) =>
    ({ release, componentName: name, version, outcome: 'REFUSED', refusal: 'PUBLISHING_DISABLED', detail: null, ownRow })
const ZED = child('Zed', '1.0', 'rz', 'rel-z')
const ADA = child('Ada', '2.0', 'ra', 'rel-a')
const PUB = child('Pub', '1.0', 'rp', 'rel-p')
const COMPONENT_OF: Record<string, string> = { 'rel-z': 'cz', 'rel-a': 'ca', 'rel-p': 'cp' }

const NONE = { before: 'PRIVATE', after: 'PRIVATE', path: 'NONE', afterSource: null, afterSourceObject: null,
    publishedReleases: 0, publishedVersions: 0, widened: [], autoPublishStarts: false, autoPublishStartsFor: [] }
const SAME = { before: 'PUBLIC', after: 'PUBLIC', path: 'ROW_PUBLIC', publishedReleases: 3, publishedVersions: 7 }
const WIDENS = { ...NONE, before: 'PRIVATE', after: 'PUBLIC', path: 'ROW_PUBLIC', publishedReleases: 3, publishedVersions: 7 }

const PUBTEXT = 'The TEA settings of Pub are public, with publishing turned off. Turning publishing on makes its published '
    + 'releases readable by anyone who knows the TEA URL, without a key: a release published on its own always, and a '
    + "release published with a product, as Pub 1.0 is here, when that product's TEA settings are public too. 3 published "
    + 'release(s) resolving to this profile, with all 7 of their collection versions, earlier versions included, and every '
    + 'document in them (SBOMs, VEX documents, device support statements, published artifacts), become readable by anyone '
    + 'who knows the TEA URL as soon as you save; visibility is live.'
const PUBCONFIRM = { title: 'Publish this component publicly?', text: PUBTEXT, confirmButtonText: 'Publish publicly',
    confirmButtonColor: '#d33', focusCancel: true }
const P_CP = { mode: 'OVERRIDE', publishing: 'ENABLED', visibility: 'PUBLIC', rawArtifacts: 'NONE', productComponents: 'PUBLISH_WITH_PRODUCT',
    minimumLifecycle: 'ASSEMBLED', tei: 'DISABLED', teiDomain: null, profiles: ['default'], autoPublish: 'DISABLED' }
const VARS_CP = { org: 'o1', scope: 'COMPONENT', object: 'cp', profile: P_CP }
const P_CZ = { mode: 'OVERRIDE', ...EIGHT, autoPublish: 'AT_SHIPPED' }
const P_CA = { mode: 'OVERRIDE', ...EIGHT, autoPublish: 'DISABLED' }
const NOANSWER = 'No answer arrived for saving the TEA settings of Pub 1.0, so publishing may or may not be turned on for it. Open its TEA settings to check.'
const ANSWERED = (m: string) => Object.assign(new Error(m), { errors: [{ message: m }] })
const DRIFT = () => new Error("Validation error (FieldUndefined@[teaProfileEditorView/publishedVersions]) : Field 'publishedVersions' in type 'TeaProfileEditorView' is undefined")
const NULL_ENTRY = { message: 'The field at path /x was declared as a non null type', extensions: { classification: 'NullValueInNonNullableField' } }

const stored = (changes: Record<string, any>) => ({ ...ROW, publishing: 'DISABLED', ...changes })
const viewOf = (row: any) => ({
    stored: row,
    effective: { status: 'RESOLVED', source: 'COMPONENT', sourceObject: null, profile: row, conflictingPerspectives: [] },
    parent: { status: 'RESOLVED', source: 'ORGANIZATION', sourceObject: null, profile: { ...ROW, uuid: 'org' }, conflictingPerspectives: [] },
    publishedReleases: 0, publishedVersions: 0, supportInjection: 'ENABLED',
})
const ROWS: Record<string, any> = {
    cz: stored({ uuid: 'rz', autoPublish: 'AT_SHIPPED' }),
    ca: stored({ uuid: 'ra' }),
    cp: stored({ uuid: 'rp', visibility: 'PUBLIC' }),
}

const opName = (doc: any) => doc?.definitions?.[0]?.name?.value
const sourceOf = (doc: any): string => doc?.loc?.source?.body ?? ''

interface Rig {
    rows?: Record<string, any>
    exposure?: Record<string, any>
    viewError?: any
    dryRunError?: any
    saveError?: any
    drift?: boolean
    answers?: boolean[]
}

function rig (o: Rig = {}) {
    const calls: any[] = []
    const docs: any[] = []
    const confirms: any[] = []
    const answers = [...(o.answers ?? [])]
    const rows = { ...ROWS, ...(o.rows ?? {}) }
    const record = (doc: any, variables: any) => {
        calls.push([opName(doc), variables])
        docs.push(doc)
        if (o.drift && /autoPublish|publishedVersions/.test(sourceOf(doc))) throw DRIFT()
    }
    const client = {
        query: async (opts: any) => {
            record(opts.query, opts.variables)
            const name = opName(opts.query)
            if (name === 'TeaProfileEditorView') {
                if (o.viewError) throw o.viewError
                const row = rows[opts.variables.object]
                return { data: { teaProfileEditorView: viewOf(row === undefined ? null : row) } }
            }
            if (o.dryRunError) throw o.dryRunError
            return { data: { teaProfileExposureChange: o.exposure?.[opts.variables.object] ?? NONE } }
        },
        mutate: async (opts: any) => {
            record(opts.mutation, opts.variables)
            if (o.saveError) throw o.saveError
            return { data: { saveTeaProfile: { uuid: 'saved' } } }
        },
    }
    const confirm = async (c: any) => {
        confirms.push(c)
        return answers.length ? answers.shift()! : true
    }
    const run = (offer: any[]) => teaTurnOnChildren({ client, org: 'o1', installationType: 'SAAS',
        componentOf: (r: string) => COMPONENT_OF[r] ?? null, names: {}, confirm }, offer)
    return { run, calls, docs, confirms }
}

const kinds = (calls: any[]) => calls.map(c => c[0])
const TURN_ON = { title: 'Turn on publish at shipped?', text: TEA_TURN_ON_TEXT, confirmButtonText: 'Turn on' }

describe('teaTurnOnChildren (row 50, the sequence)', () => {
    it('(f) reads, dry-runs and saves each child in order, the stored row with publishing on, one turn-on confirmation', async () => {
        const r = rig()
        const result = await r.run([ZED, ADA])
        expect(r.calls).toEqual([
            ['TeaProfileEditorView', { org: 'o1', scope: 'COMPONENT', object: 'cz' }],
            ['TeaProfileExposureChange', { org: 'o1', scope: 'COMPONENT', object: 'cz', profile: P_CZ }],
            ['SaveTeaProfile', { org: 'o1', scope: 'COMPONENT', object: 'cz', profile: P_CZ }],
            ['TeaProfileEditorView', { org: 'o1', scope: 'COMPONENT', object: 'ca' }],
            ['TeaProfileExposureChange', { org: 'o1', scope: 'COMPONENT', object: 'ca', profile: P_CA }],
            ['SaveTeaProfile', { org: 'o1', scope: 'COMPONENT', object: 'ca', profile: P_CA }],
        ])
        expect(r.confirms).toEqual([TURN_ON])
        expect(result).toEqual({ saved: ['Zed 1.0', 'Ada 2.0'], stop: null })
    })

    it('(f) the turn-on confirmation is asked between Zed\'s dry-run and Zed\'s save', async () => {
        const order: string[] = []
        const result = await teaTurnOnChildren({
            client: {
                query: async (opts: any) => { order.push(opName(opts.query)); return opName(opts.query) === 'TeaProfileEditorView'
                    ? { data: { teaProfileEditorView: viewOf(ROWS[opts.variables.object]) } } : { data: { teaProfileExposureChange: NONE } } },
                mutate: async (opts: any) => { order.push(opName(opts.mutation)); return { data: {} } },
            },
            org: 'o1', installationType: 'SAAS', componentOf: (x: string) => COMPONENT_OF[x], names: {},
            confirm: async () => { order.push('confirm'); return true },
        }, [ZED, ADA])
        expect(order).toEqual(['TeaProfileEditorView', 'TeaProfileExposureChange', 'confirm', 'SaveTeaProfile',
            'TeaProfileEditorView', 'TeaProfileExposureChange', 'SaveTeaProfile'])
        expect(result.stop).toBeNull()
    })

    it.each([
        ['the stored row\'s uuid is other', stored({ uuid: 'other', autoPublish: 'AT_SHIPPED' })],
        ['there is no stored row', null],
        ['the stored row follows a perspective', stored({ uuid: 'rz', mode: 'FOLLOW_PERSPECTIVE' })],
        ['the stored row already publishes', stored({ uuid: 'rz', publishing: 'ENABLED' })],
    ])('(g) a row that moved: %s, so nothing is dry-run or saved', async (_name, row) => {
        const r = rig({ rows: { cz: row } })
        const result = await r.run([ZED, ADA])
        expect(r.calls).toEqual([['TeaProfileEditorView', { org: 'o1', scope: 'COMPONENT', object: 'cz' }]])
        expect(result).toEqual({ saved: [], stop: { kind: 'changed', child: 'Zed 1.0',
            text: 'The TEA settings of Zed 1.0 changed since this dialog opened, so nothing was saved for it.' } })
    })

    it('(h) a declined public confirmation stops: no save, the second child never read', async () => {
        const r = rig({ exposure: { cz: WIDENS }, answers: [false] })
        const result = await r.run([ZED, ADA])
        expect(kinds(r.calls)).toEqual(['TeaProfileEditorView', 'TeaProfileExposureChange'])
        expect(r.confirms.map(c => c.title)).toEqual(['Make this TEA profile public?'])
        expect(result.stop?.kind).toBe('declined')
        expect(result.saved).toEqual([])
    })

    it('(h) accepted public confirmation, declined turn-on confirmation: no save', async () => {
        const r = rig({ exposure: { cz: WIDENS }, answers: [true, false] })
        const result = await r.run([ZED])
        expect(r.confirms.map(c => c.title)).toEqual(['Make this TEA profile public?', 'Turn on publish at shipped?'])
        expect(kinds(r.calls)).not.toContain('SaveTeaProfile')
        expect(result.stop?.kind).toBe('declined')
    })

    it('(h) the first child saved and the second declined: the save stands, nothing after Ada\'s dry-run', async () => {
        const r = rig({ exposure: { ca: WIDENS }, answers: [true, false] })
        const result = await r.run([ZED, ADA])
        expect(result).toEqual({ saved: ['Zed 1.0'], stop: { kind: 'declined', child: 'Ada 2.0',
            text: 'Stopped at Ada 2.0: its confirmation was declined.' } })
        expect(r.calls.at(-1)).toEqual(['TeaProfileExposureChange', { org: 'o1', scope: 'COMPONENT', object: 'ca', profile: P_CA }])
        expect(kinds(r.calls)).toEqual(['TeaProfileEditorView', 'TeaProfileExposureChange', 'SaveTeaProfile',
            'TeaProfileEditorView', 'TeaProfileExposureChange'])
    })

    it('(i) a refused save stops with the server\'s text; the second child is never read', async () => {
        const r = rig({ saveError: ANSWERED('boom') })
        const result = await r.run([ZED, ADA])
        expect(result.stop).toEqual({ kind: 'refused', child: 'Zed 1.0', text: 'boom' })
        expect(kinds(r.calls)).toEqual(['TeaProfileEditorView', 'TeaProfileExposureChange', 'SaveTeaProfile'])
    })

    it('(i) a refused dry-run stops the same way, with no save', async () => {
        const r = rig({ dryRunError: ANSWERED('boom') })
        const result = await r.run([ZED, ADA])
        expect(result).toEqual({ saved: [], stop: { kind: 'refused', child: 'Zed 1.0', text: 'boom' } })
        expect(kinds(r.calls)).toEqual(['TeaProfileEditorView', 'TeaProfileExposureChange'])
    })

    it('(k) a backend without the fields: core view, no autoPublish in the input, core save document', async () => {
        const r = rig({ drift: true })
        const result = await r.run([ADA])
        expect(result).toEqual({ saved: ['Ada 2.0'], stop: null })
        const views = r.docs.filter((d, i) => r.calls[i][0] === 'TeaProfileEditorView')
        expect(views).toHaveLength(2)
        expect(sourceOf(views[0])).toContain('autoPublish')
        expect(sourceOf(views[1])).not.toContain('autoPublish')
        const saveAt = r.calls.findIndex(c => c[0] === 'SaveTeaProfile')
        expect(saveAt).toBeGreaterThan(0)
        expect('autoPublish' in r.calls[saveAt][1].profile).toBe(false)
        expect(sourceOf(r.docs[saveAt])).not.toContain('autoPublish')
        expect(r.calls.filter(c => c[0] === 'SaveTeaProfile')).toHaveLength(1)
    })
})

describe('teaTurnOnChildren with a PUBLIC row (row 50, F-8)', () => {
    it('(r) accepted: the Public confirmation once, the whole calls in order', async () => {
        const r = rig({ exposure: { cp: SAME } })
        const result = await r.run([PUB])
        expect(r.confirms).toEqual([PUBCONFIRM])
        expect(r.calls).toEqual([['TeaProfileEditorView', { org: 'o1', scope: 'COMPONENT', object: 'cp' }],
            ['TeaProfileExposureChange', VARS_CP], ['SaveTeaProfile', VARS_CP]])
        expect(result).toEqual({ saved: ['Pub 1.0'], stop: null })
    })

    it('(r) declined: nothing is sent after the decline', async () => {
        const r = rig({ exposure: { cp: SAME }, answers: [false] })
        const result = await r.run([PUB])
        expect(result).toEqual({ saved: [], stop: { kind: 'declined', child: 'Pub 1.0', text: 'Stopped at Pub 1.0: its confirmation was declined.' } })
        expect(r.calls).toEqual([['TeaProfileEditorView', { org: 'o1', scope: 'COMPONENT', object: 'cp' }],
            ['TeaProfileExposureChange', VARS_CP]])
    })

    it('(s) both confirmations, in order; a decline of either saves nothing', async () => {
        const rows = { cp: stored({ uuid: 'rp', visibility: 'PUBLIC', autoPublish: 'AT_SHIPPED' }) }
        const both = rig({ rows, exposure: { cp: SAME }, answers: [true, false] })
        const r1 = await both.run([PUB])
        expect(both.confirms.map(c => c.title)).toEqual(['Publish this component publicly?', 'Turn on publish at shipped?'])
        expect(kinds(both.calls)).not.toContain('SaveTeaProfile')
        expect(r1.stop?.kind).toBe('declined')
        const first = rig({ rows, exposure: { cp: SAME }, answers: [false] })
        const r2 = await first.run([PUB])
        expect(first.confirms).toHaveLength(1)
        expect(kinds(first.calls)).not.toContain('SaveTeaProfile')
        expect(r2.stop?.kind).toBe('declined')
    })

    it('(t) never two Public confirmations, also when the server reports the change itself', async () => {
        const r = rig({ exposure: { cp: WIDENS } })
        await r.run([PUB])
        expect(r.confirms).toEqual([PUBCONFIRM])
        // control: a PRIVATE row with the same answer gets the editor's confirmation, once
        const c = rig({ exposure: { ca: WIDENS } })
        await c.run([ADA])
        expect(c.confirms.map(x => x.title)).toEqual(['Make this TEA profile public?'])
    })

    it('(t) declined at PUB, Ada is never read', async () => {
        const r = rig({ exposure: { cp: SAME }, answers: [false] })
        await r.run([PUB, ADA])
        expect(r.calls.some(c => c[1]?.object === 'ca')).toBe(false)
    })
})

describe('teaTurnOnChildren errors (row 50 (v))', () => {
    const three = (calls: any[]) => {
        expect(kinds(calls)).toEqual(['TeaProfileEditorView', 'TeaProfileExposureChange', 'SaveTeaProfile'])
        expect(calls[2]).toEqual(['SaveTeaProfile', VARS_CP])
    }

    it('a save the server refused is a refusal', async () => {
        const r = rig({ exposure: { cp: SAME }, saveError: ANSWERED('boom') })
        const result = await r.run([PUB, ADA])
        three(r.calls)
        expect(result).toEqual({ saved: [], stop: { kind: 'refused', child: 'Pub 1.0', text: 'boom' } })
    })

    it.each([
        ['a failed fetch', () => new TypeError('Failed to fetch')],
        ['a 502 with no error entry', () => Object.assign(new Error('Response not successful: Received status code 502'), { networkError: { statusCode: 502 } })],
        ['an error without an entry', () => new Error('boom')],
    ])('a save with no answer (%s) is unanswered, never refused', async (_n, err) => {
        const r = rig({ exposure: { cp: SAME }, saveError: err() })
        const result = await r.run([PUB, ADA])
        three(r.calls)
        expect(result).toEqual({ saved: [], stop: { kind: 'unanswered', child: 'Pub 1.0', text: NOANSWER } })
    })

    it('a failed view read is a stop; the promise resolves', async () => {
        const r = rig({ viewError: new TypeError('Failed to fetch') })
        const result = await r.run([PUB, ADA])
        expect(r.calls).toEqual([['TeaProfileEditorView', { org: 'o1', scope: 'COMPONENT', object: 'cp' }]])
        expect(result).toEqual({ saved: [], stop: { kind: 'refused', child: 'Pub 1.0', text: 'Failed to fetch' } })
    })

    it('a refused dry-run stops with no save', async () => {
        const r = rig({ dryRunError: ANSWERED('Not authorized') })
        const result = await r.run([PUB, ADA])
        expect(kinds(r.calls)).toEqual(['TeaProfileEditorView', 'TeaProfileExposureChange'])
        expect(result.stop).toEqual({ kind: 'refused', child: 'Pub 1.0', text: 'Not authorized' })
    })

    it('teaServerAnswered: an answered error only', () => {
        expect(teaServerAnswered(ANSWERED('boom'))).toBe(true)
        expect(teaServerAnswered({ errors: [NULL_ENTRY] })).toBe(false)
        expect(teaServerAnswered(ANSWERED('  '))).toBe(false)
        expect(teaServerAnswered(new TypeError('Failed to fetch'))).toBe(false)
        expect(teaServerAnswered(null)).toBe(false)
    })
})
