import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
vi.mock('./fetchClient', () => ({ fetchWithAuth: vi.fn(), fetchArrayBufferWithAuth: vi.fn() }))
import graphqlQueries from './graphqlQueries'
import { runTeaPublishFlow } from './teaPublishFlow'
import {
    TEA_ACT_GATEWAY_TEXT, teaActErrorText, teaPartialChildren, teaPublishConfirm, teaPublishSummary, teaShareConfirm,
    teaTurnOnProgress,
} from './teaPublication'
import { TEA_TURN_ON_TEXT } from './teaProfile'

// FDAW-39 architecture-5 section 4, row 50 (l) to (p), (u), (w) and row 51 (a) to (c), (f), (g): the
// release page's publish and share flow, with one log for every stub so that order is compared whole.

const PRODUCT = { release: 'r1', kind: 'PRODUCT_RELEASE', canPublish: true, effectiveVisibility: 'PRIVATE', profileSource: 'ORGANIZATION',
    cascades: true, cascadeChildren: 9, profileRevision: 3, publication: null, profiles: ['default'], publishing: 'ENABLED' }
const COMPONENT = { ...PRODUCT, kind: 'COMPONENT_RELEASE', cascades: false, cascadeChildren: 0 }
const NAMES = { component: 'Pump', version: '1.2.0', perspectives: {} }
const SHARE = { kind: 'FDA_PREMARKET', latestVersion: 2, state: 'FINAL', profileName: 'default' }

const ch = (release: string, componentName: string | null, version: string | null, outcome: string, more: Record<string, any> = {}) =>
    ({ release, componentName, version, outcome, refusal: null, detail: null, ownRow: null, ...more })
const LIST = [
    ch('l1', 'Api', '1.0', 'WILL_PUBLISH'),
    ch('l2', 'Db', '2.0', 'REFUSED', { refusal: 'PUBLISHING_DISABLED', ownRow: 'row-db' }),
    ch('l3', 'Ui', '3.0', 'SKIPPED_DIRECT'),
    ch('l4', 'Fw', '4.0', 'REFUSED', { refusal: 'LIFECYCLE_TOO_LOW', detail: "Release Fw 4.0 is DRAFT; the TEA profile's minimum lifecycle is ASSEMBLED" }),
    ch('l5', 'Kit', '5.0', 'SKIPPED_HIDDEN'),
    ch('l6', 'Lib', '6.0', 'SKIPPED_OTHER_PRODUCT', { detail: 'published with product Suite 9' }),
    ch('3bef7cde-2edb-47cb-aa4e-94487c2cdced', null, null, 'REFUSED', { refusal: 'RELEASE_NOT_FOUND', detail: 'not found' }),
    ch('l8', 'Gw', '8.0', 'REFUSED', { refusal: 'GENERATION_FAILED', detail: 'could not be checked' }),
    ch('l9', 'Web', '9.0', 'WILL_PUBLISH'),
]
const API = LIST[0]
const OFFERING = [LIST[0], LIST[1], LIST[2]]
const ZED = ch('rel-z', 'Zed', '1.0', 'REFUSED', { refusal: 'PUBLISHING_DISABLED', ownRow: 'rz' })
const ADA = ch('rel-a', 'Ada', '2.0', 'REFUSED', { refusal: 'PUBLISHING_DISABLED', ownRow: 'ra' })
const PUB = ch('rel-p', 'Pub', '1.0', 'REFUSED', { refusal: 'PUBLISHING_DISABLED', ownRow: 'rp' })
const COMPONENT_OF: Record<string, string> = { 'rel-z': 'cz', 'rel-a': 'ca', 'rel-p': 'cp', l2: 'c-db' }
const componentOf = (r: string) => COMPONENT_OF[r] ?? 'c-' + r

const ROW = { uuid: 'row-1', scope: 'COMPONENT', object: 'c1', mode: 'OVERRIDE', followedPerspective: null, publishing: 'DISABLED',
    visibility: 'PRIVATE', rawArtifacts: 'NONE', productComponents: 'PUBLISH_WITH_PRODUCT', minimumLifecycle: 'ASSEMBLED',
    tei: 'DISABLED', teiDomain: null, profiles: ['default'], revision: 1, autoPublish: 'DISABLED', autoPublishState: 'OFF' }
const ROWS: Record<string, any> = {
    cz: { ...ROW, uuid: 'rz', autoPublish: 'AT_SHIPPED' },
    ca: { ...ROW, uuid: 'ra' },
    cp: { ...ROW, uuid: 'rp', visibility: 'PUBLIC' },
    'c-db': { ...ROW, uuid: 'row-db' },
}
const viewOf = (row: any) => ({ stored: row,
    effective: { status: 'RESOLVED', source: 'COMPONENT', sourceObject: null, profile: row, conflictingPerspectives: [] },
    parent: { status: 'RESOLVED', source: 'ORGANIZATION', sourceObject: null, profile: { ...ROW, uuid: 'org' }, conflictingPerspectives: [] },
    publishedReleases: 0, publishedVersions: 0, supportInjection: 'ENABLED' })
const NONE = { before: 'PRIVATE', after: 'PRIVATE', path: 'NONE', afterSource: null, afterSourceObject: null,
    publishedReleases: 0, publishedVersions: 0, widened: [], autoPublishStarts: false, autoPublishStartsFor: [] }
const SAME = { before: 'PUBLIC', after: 'PUBLIC', path: 'ROW_PUBLIC', publishedReleases: 3, publishedVersions: 7 }

const EIGHT = { rawArtifacts: 'NONE', productComponents: 'PUBLISH_WITH_PRODUCT', minimumLifecycle: 'ASSEMBLED', tei: 'DISABLED',
    teiDomain: null, profiles: ['default'] }
const P_CZ = { mode: 'OVERRIDE', publishing: 'ENABLED', visibility: 'PRIVATE', ...EIGHT, autoPublish: 'AT_SHIPPED' }
const P_CA = { mode: 'OVERRIDE', publishing: 'ENABLED', visibility: 'PRIVATE', ...EIGHT, autoPublish: 'DISABLED' }
const P_CP = { mode: 'OVERRIDE', publishing: 'ENABLED', visibility: 'PUBLIC', ...EIGHT, autoPublish: 'DISABLED' }
const VARS = (object: string, profile: any) => ({ org: 'o1', scope: 'COMPONENT', object, profile })
const PUBTEXT = 'The TEA settings of Pub are public, with publishing turned off. Turning publishing on makes its published '
    + 'releases readable by anyone who knows the TEA URL, without a key: a release published on its own always, and a '
    + "release published with a product, as Pub 1.0 is here, when that product's TEA settings are public too. 3 published "
    + 'release(s) resolving to this profile, with all 7 of their collection versions, earlier versions included, and every '
    + 'document in them (SBOMs, VEX documents, device support statements, published artifacts), become readable by anyone '
    + 'who knows the TEA URL as soon as you save; visibility is live.'
const NOANSWER = 'No answer arrived for saving the TEA settings of Pub 1.0, so publishing may or may not be turned on for it. Open its TEA settings to check.'
const ANSWERED = (m: string) => Object.assign(new Error(m), { errors: [{ message: m }] })
const REFUSED_RIGHT = () => Object.assign(new Error('Not authorized'), { errors: [{ message: 'Not authorized' },
    { message: 'The field at path ...', extensions: { classification: 'NullValueInNonNullableField' } }] })
const GATEWAY = () => Object.assign(new Error('Response not successful: Received status code 502'), { networkError: { statusCode: 502 } })
const DRIFT = () => new Error("Validation error (FieldUndefined@[teaProfileEditorView/publishedVersions]) : Field 'publishedVersions' in type 'TeaProfileEditorView' is undefined")
const HOSTILE = '<img src=x onerror="window.__teaHit=1"></script><script>window.__teaHit=2</script><b>b</b>'
const RESULT = { outcome: 'PUBLISHED', collection: { version: 1 }, publication: { aggregateArtifacts: [] }, artifacts: [] }
const R = { outcome: 'PUBLISHED', collection: { version: 4 }, publication: { aggregateArtifacts: [] },
    artifacts: [{ status: 'PUBLISHED' }, { status: 'SKIPPED_NO_CHECKSUM' }, { status: 'SKIPPED_SOME_NEW_REASON' }],
    children: [{ ...LIST[0], outcome: 'PUBLISHED' }, LIST[1], LIST[2], LIST[3], LIST[5], LIST[6],
        { release: 'h', componentName: 'Gw', version: '8.0', outcome: 'REFUSED', refusal: 'GENERATION_FAILED',
            detail: 'Release Gw 8.0: publishing on TEA failed: Connection refused' }] }
const TURN_ON_FIRE = { title: 'Turn on publish at shipped?', text: TEA_TURN_ON_TEXT, confirmButtonText: 'Turn on', icon: 'warning',
    showCancelButton: true, cancelButtonText: 'Cancel' }

const opName = (doc: any) => doc?.definitions?.[0]?.name?.value

/** Every `fire` entry of a log has one argument, an object, with no html key (row 51 (g)). */
function expectTextOnly (log: any[]): void {
    for (const e of log.filter(x => x[0] === 'fire')) {
        expect(e.length, JSON.stringify(e)).toBe(2)
        expect(typeof e[1]).toBe('object')
        expect(e[1]).not.toBeNull()
        expect('html' in e[1]).toBe(false)
    }
}
const TITLES = ['TEA', 'Turn on publish at shipped?', 'Publish this component publicly?', 'Make this TEA profile public?']
function expectTitles (log: any[]): void {
    for (const e of log.filter(x => x[0] === 'fire')) {
        if ('title' in e[1]) expect(TITLES, e[1].title).toContain(e[1].title)
        else expect(typeof e[1].titleText, JSON.stringify(e[1])).toBe('string')
    }
}

let logs: any[][] = []
beforeEach(() => { logs = [] })
afterEach(() => {
    for (const l of logs) {
        expectTextOnly(l)
        expectTitles(l)
    }
})

interface Rig {
    children?: any[] | (() => never)
    exposure?: Record<string, any>
    saveError?: any
    publishError?: any
    result?: any
    queue?: any[]
    startAct?: boolean
    isOrgAdmin?: boolean
}

function rig (o: Rig = {}) {
    const log: any[] = []
    logs.push(log)
    const rejections: any[] = []
    const queue = o.queue ?? []
    let at = 0
    const client = {
        query: async (opts: any) => {
            const name = opName(opts.query)
            log.push([name, opts.variables])
            if (name === 'ReleaseTeaPublishChildren') {
                if (typeof o.children === 'function') o.children()
                return { data: { releaseTeaPublicationView: { release: 'r1', children: o.children ?? LIST } } }
            }
            if (name === 'TeaProfileEditorView') return { data: { teaProfileEditorView: viewOf(ROWS[opts.variables.object]) } }
            return { data: { teaProfileExposureChange: o.exposure?.[opts.variables.object] ?? NONE } }
        },
        mutate: async (opts: any) => {
            const name = opName(opts.mutation)
            log.push([name, opts.variables])
            if (name === 'SaveTeaProfile') {
                if (o.saveError) throw o.saveError
                return { data: { saveTeaProfile: { uuid: 'saved' } } }
            }
            if (o.publishError) throw o.publishError
            const field = name === 'RepublishReleaseOnTea' ? 'republishReleaseOnTea' : 'publishReleaseOnTea'
            return { data: { [field]: o.result ?? RESULT } }
        },
    }
    const fire = async (...args: any[]) => {
        log.push(['fire', ...args])
        if (args[0]?.showCancelButton && at < queue.length) return queue[at++]
        return {}
    }
    const deps = {
        client,
        fire,
        progress: (arg: any) => { log.push(['progress', arg]) },
        withProgress: async (act: any, _view: any, run: () => Promise<void>) => {
            log.push(['withProgress', act])
            if (o.startAct === false) return
            try { await run() } catch (err) { rejections.push(err) }
        },
        isOrgAdmin: o.isOrgAdmin ?? true,
        org: 'o1',
        installationType: 'SAAS',
        componentOf,
    }
    const run = (view: any, share: any = null, field = 'publishReleaseOnTea') => runTeaPublishFlow(deps, { view, names: NAMES,
        mutation: field === 'republishReleaseOnTea' ? graphqlQueries.RepublishReleaseOnTeaGql : graphqlQueries.PublishReleaseOnTeaGql,
        field, share })
    return { run, log, rejections, kinds: () => log.map(e => e[0]), fires: () => log.filter(e => e[0] === 'fire') }
}

const OK = { isConfirmed: true }
const DENY = { isConfirmed: false, isDenied: true }

describe('runTeaPublishFlow (row 50)', () => {
    it('row 51 (g): expectTextOnly throws for a positional call and for an html key', () => {
        expect(() => expectTextOnly([['fire', 'Error!', 'x', 'error']])).toThrow()
        expect(() => expectTextOnly([['fire', { html: 'x' }]])).toThrow()
    })

    it('(l) a component, Publish, confirmed', async () => {
        const r = rig({ queue: [OK] })
        await r.run(COMPONENT)
        expect(r.kinds()).toEqual(['fire', 'withProgress', 'PublishReleaseOnTea', 'fire'])
        expect(r.log[0]).toEqual(['fire', teaPublishConfirm(COMPONENT, NAMES)])
        expect(r.log[2][1]).toEqual({ release: 'r1' })
        expect('comment' in r.log[2][1]).toBe(false)
        expect(r.log[1]).toEqual(['withProgress', 'publish'])
        expect(r.log.at(-1)).toEqual(['fire', { title: 'TEA', text: teaPublishSummary(RESULT), icon: 'success' }])
    })

    it('(l) Re-publish runs the act republish', async () => {
        const r = rig({ queue: [OK] })
        await r.run(COMPONENT, null, 'republishReleaseOnTea')
        expect(r.log[1]).toEqual(['withProgress', 'republish'])
        expect(r.kinds()).toEqual(['fire', 'withProgress', 'RepublishReleaseOnTea', 'fire'])
    })

    it('(m) a product: the list is read under the progress dialog, then the confirmation names it', async () => {
        const r = rig({ queue: [OK] })
        await r.run(PRODUCT)
        expect(r.kinds()).toEqual(['progress', 'ReleaseTeaPublishChildren', 'fire', 'withProgress', 'PublishReleaseOnTea', 'fire'])
        expect(r.log[0]).toEqual(['progress', { titleText: 'Checking the component releases…',
            text: 'Reading what a publish would do with each component release of this product. The publish has not started yet.',
            allowOutsideClick: false, allowEscapeKey: false, showConfirmButton: false }])
        expect(r.log[1][1]).toEqual({ release: 'r1' })
        expect(r.log[2]).toEqual(['fire', teaPublishConfirm(PRODUCT, NAMES, teaPartialChildren(LIST, { isOrgAdmin: true, componentOf }))])
    })

    it('(m) a product that does not cascade reads no list', async () => {
        const r = rig({ queue: [OK] })
        await r.run({ ...PRODUCT, cascades: false })
        expect(r.kinds()).toEqual(['fire', 'withProgress', 'PublishReleaseOnTea', 'fire'])
    })

    it('(n) a failed read stops: nothing is published, never the gateway text', async () => {
        const err = new TypeError('Failed to fetch')
        const r = rig({ children: () => { throw err } })
        await r.run(PRODUCT)
        expect(r.kinds()).toEqual(['progress', 'ReleaseTeaPublishChildren', 'fire'])
        expect(r.log[2]).toEqual(['fire', { titleText: 'Not published',
            text: "The list of this product's component releases could not be read, so nothing was published. Try again.", icon: 'error' }])
        expect(teaActErrorText(err)).toBe(TEA_ACT_GATEWAY_TEXT)
        expect(r.fires().some(e => JSON.stringify(e).includes('still working'))).toBe(false)
    })

    it('(n) a refused read names the server\'s answer; a gateway status does not', async () => {
        const r = rig({ children: () => { throw REFUSED_RIGHT() } })
        await r.run(PRODUCT)
        expect(r.kinds()).toEqual(['progress', 'ReleaseTeaPublishChildren', 'fire'])
        expect(r.log[2][1].text).toBe("The list of this product's component releases could not be read, so nothing was published. Try again. The server answered: Not authorized")
        const g = rig({ children: () => { throw GATEWAY() } })
        await g.run(PRODUCT)
        expect(g.log[2][1].text).toBe("The list of this product's component releases could not be read, so nothing was published. Try again.")
    })

    it('(n) a backend without the field: today\'s count sentence', async () => {
        const r = rig({ children: () => { throw DRIFT() }, queue: [OK] })
        await r.run(PRODUCT)
        expect(r.kinds()).toEqual(['progress', 'ReleaseTeaPublishChildren', 'fire', 'withProgress', 'PublishReleaseOnTea', 'fire'])
        expect(r.log[2]).toEqual(['fire', teaPublishConfirm(PRODUCT, NAMES)])
    })

    it('(o) denied with one offered child: the sequence under progress dialogs, then the publish', async () => {
        const r = rig({ children: [API, ZED], queue: [DENY, OK] })
        await r.run(PRODUCT)
        expect(r.kinds()).toEqual(['progress', 'ReleaseTeaPublishChildren', 'fire', 'progress', 'TeaProfileEditorView',
            'TeaProfileExposureChange', 'fire', 'progress', 'SaveTeaProfile', 'withProgress', 'PublishReleaseOnTea', 'fire'])
        const progress = r.log.filter(e => e[0] === 'progress')
        expect(progress[1]).toEqual(['progress', teaTurnOnProgress()])
        expect(progress[2]).toEqual(['progress', teaTurnOnProgress()])
        expect(r.fires()[1]).toEqual(['fire', TURN_ON_FIRE])
        expect(r.log.find(e => e[0] === 'SaveTeaProfile')).toEqual(['SaveTeaProfile', VARS('cz', P_CZ)])
        expect(r.log.find(e => e[0] === 'PublishReleaseOnTea')).toEqual(['PublishReleaseOnTea', { release: 'r1' }])
        expect(r.fires()).toHaveLength(3)
    })

    it('(o) two offered children: one progress after Zed\'s confirmation covers Ada too', async () => {
        const r = rig({ children: [API, ZED, ADA], queue: [DENY, OK] })
        await r.run(PRODUCT)
        const k = r.kinds()
        const from = k.indexOf('fire') + 1
        expect(k.slice(from, k.indexOf('withProgress'))).toEqual(['progress', 'TeaProfileEditorView', 'TeaProfileExposureChange', 'fire',
            'progress', 'SaveTeaProfile', 'TeaProfileEditorView', 'TeaProfileExposureChange', 'SaveTeaProfile'])
        expect(r.log.filter(e => ['TeaProfileEditorView', 'TeaProfileExposureChange', 'SaveTeaProfile'].includes(e[0]))).toEqual([
            ['TeaProfileEditorView', { org: 'o1', scope: 'COMPONENT', object: 'cz' }], ['TeaProfileExposureChange', VARS('cz', P_CZ)],
            ['SaveTeaProfile', VARS('cz', P_CZ)], ['TeaProfileEditorView', { org: 'o1', scope: 'COMPONENT', object: 'ca' }],
            ['TeaProfileExposureChange', VARS('ca', P_CA)], ['SaveTeaProfile', VARS('ca', P_CA)]])
        expect(r.fires()[1]).toEqual(['fire', TURN_ON_FIRE])
    })

    it('(o) the sequence stops: the stop dialog, nothing saved or published', async () => {
        const r = rig({ children: [API, ZED], queue: [DENY, { isConfirmed: false }] })
        await r.run(PRODUCT)
        expect(r.log.at(-1)).toEqual(['fire', { titleText: 'Not published',
            text: 'Stopped at Zed 1.0: its confirmation was declined. Nothing was published.', icon: 'info' }])
        for (const k of ['SaveTeaProfile', 'withProgress', 'PublishReleaseOnTea']) expect(r.kinds()).not.toContain(k)
    })

    it.each([
        ['cancelled on the offering product', OFFERING, { isConfirmed: false, isDenied: false }],
        ['denied on LIST, which makes no offer', LIST, DENY],
        ['no answer on the offering product', OFFERING, undefined],
        ['an empty answer on the offering product', OFFERING, {}],
    ])('(p) %s: nothing after the confirmation', async (_n, children, answer) => {
        const r = rig({ children, queue: [answer] })
        await r.run(PRODUCT)
        expect(r.kinds()).toEqual(['progress', 'ReleaseTeaPublishChildren', 'fire'])
    })

    it('(p) no view: nothing is called', async () => {
        const r = rig({ queue: [OK] })
        await r.run(null)
        expect(r.log).toEqual([])
    })
})

describe('runTeaPublishFlow with a PUBLIC row (row 50 (u), (w))', () => {
    const DECLINED_KINDS = ['progress', 'ReleaseTeaPublishChildren', 'fire', 'progress', 'TeaProfileEditorView', 'TeaProfileExposureChange', 'fire', 'fire']
    const ACCEPTED_KINDS = ['progress', 'ReleaseTeaPublishChildren', 'fire', 'progress', 'TeaProfileEditorView', 'TeaProfileExposureChange',
        'fire', 'progress', 'SaveTeaProfile', 'withProgress', 'PublishReleaseOnTea', 'fire']
    const publicRig = (second: any, more: Rig = {}) => rig({ children: [API, PUB], exposure: { cp: SAME }, queue: [DENY, second], ...more })

    it('(u) declined: the red confirmation by name, then the stop; nothing saved or published', async () => {
        const r = publicRig({ isConfirmed: false })
        await r.run(PRODUCT)
        expect(r.kinds()).toEqual(DECLINED_KINDS)
        expect(r.fires()[1]).toEqual(['fire', { title: 'Publish this component publicly?', text: PUBTEXT, confirmButtonText: 'Publish publicly',
            confirmButtonColor: '#d33', focusCancel: true, icon: 'warning', showCancelButton: true, cancelButtonText: 'Cancel' }])
        expect(r.log.at(-1)).toEqual(['fire', { titleText: 'Not published',
            text: 'Stopped at Pub 1.0: its confirmation was declined. Nothing was published.', icon: 'info' }])
    })

    it.each([
        ['an empty answer', {}],
        ['Escape', { isDismissed: true, dismiss: 'esc' }],
        ['a click outside', { isDismissed: true, dismiss: 'backdrop' }],
        ['no answer', undefined],
        ['a truthy value that is not true', { isConfirmed: 1 }],
    ])('(u) %s gives the declined log, entry for entry', async (_n, answer) => {
        const declined = publicRig({ isConfirmed: false })
        await declined.run(PRODUCT)
        const r = publicRig(answer)
        await r.run(PRODUCT)
        expect(r.log).toEqual(declined.log)
    })

    it('(u) accepted: the save and the publish follow, under the progress dialogs', async () => {
        const r = publicRig(OK)
        await r.run(PRODUCT)
        expect(r.kinds()).toEqual(ACCEPTED_KINDS)
        const progress = r.log.filter(e => e[0] === 'progress')
        expect(progress[1]).toEqual(['progress', teaTurnOnProgress()])
        expect(progress[2]).toEqual(['progress', teaTurnOnProgress()])
        expect(r.log.find(e => e[0] === 'SaveTeaProfile')).toEqual(['SaveTeaProfile', VARS('cp', P_CP)])
        expect(r.log.find(e => e[0] === 'PublishReleaseOnTea')).toEqual(['PublishReleaseOnTea', { release: 'r1' }])
    })

    it('(w) a save with no answer: the unanswered stop, no publish', async () => {
        const r = publicRig(OK, { saveError: new TypeError('Failed to fetch') })
        await r.run(PRODUCT)
        expect(r.kinds()).toEqual([...ACCEPTED_KINDS.slice(0, 9), 'fire'])
        expect(r.log.find(e => e[0] === 'SaveTeaProfile')).toEqual(['SaveTeaProfile', VARS('cp', P_CP)])
        expect(r.log.at(-1)).toEqual(['fire', { titleText: 'Not published',
            text: 'No answer arrived for saving the TEA settings of Pub 1.0, so publishing may or may not be turned on for it. Open its TEA settings to check. Nothing was published.',
            icon: 'info' }])
        expect(r.log.at(-1)[1].text).toBe(NOANSWER + ' Nothing was published.')
    })

    it('(w) a refused save: the server\'s text, no publish', async () => {
        const r = publicRig(OK, { saveError: ANSWERED('boom') })
        await r.run(PRODUCT)
        expect(r.kinds()).toEqual([...ACCEPTED_KINDS.slice(0, 9), 'fire'])
        expect(r.log.at(-1)[1].text).toBe('boom Nothing was published.')
    })

    it('(w) the act is not started: it says so, and which saves stand', async () => {
        const stopped = publicRig(OK, { saveError: new TypeError('Failed to fetch') })
        await stopped.run(PRODUCT)
        expect(stopped.log.at(-1)[1].text).toContain('Nothing was published.')
        const r = publicRig(OK, { startAct: false })
        await r.run(PRODUCT)
        expect(r.kinds()).toEqual([...ACCEPTED_KINDS.slice(0, 10), 'fire'])
        expect(r.log.at(-1)).toEqual(['fire', { titleText: 'Not published',
            text: 'Another TEA act on this release is still running, so this one was not started. Publishing stays turned on for Pub 1.0.', icon: 'info' }])
        expect(r.log.at(-1)[1].text).not.toContain('Nothing was published.')
    })

    it('(w) the act is not started on a component: the plain sentence', async () => {
        const r = rig({ queue: [OK], startAct: false })
        await r.run(COMPONENT)
        expect(r.kinds()).toEqual(['fire', 'withProgress', 'fire'])
        expect(r.log.at(-1)[1].text).toBe('Another TEA act on this release is still running, so this one was not started.')
    })

    it('(w) teaTurnOnProgress, whole', () => {
        expect(teaTurnOnProgress()).toEqual({ titleText: 'Turning publishing on…',
            text: 'Checking and saving the TEA settings of the listed components. The publish of this product release has not started yet.',
            allowOutsideClick: false, allowEscapeKey: false, showConfirmButton: false })
    })
})

describe('Share with customers through the flow (row 51)', () => {
    it('(a) a share of a release not yet published: the share dialog and the constant comment', async () => {
        const r = rig({ queue: [OK] })
        await r.run(COMPONENT, SHARE)
        expect(r.log[0]).toEqual(['fire', teaShareConfirm(COMPONENT, NAMES, SHARE, null)])
        expect(r.log.find(e => e[0] === 'PublishReleaseOnTea')).toEqual(['PublishReleaseOnTea', { release: 'r1', comment: 'Shared with customers' }])
    })

    it('(a) a share of a published release re-publishes with the same variables', async () => {
        const view = { ...COMPONENT, publication: { state: 'PUBLISHED' } }
        const r = rig({ queue: [OK] })
        await r.run(view, SHARE, 'republishReleaseOnTea')
        expect(r.log.find(e => e[0] === 'RepublishReleaseOnTea')).toEqual(['RepublishReleaseOnTea', { release: 'r1', comment: 'Shared with customers' }])
        expect(r.log.find(e => e[0] === 'withProgress')).toEqual(['withProgress', 'republish'])
    })

    it('(b) a cancelled share sends nothing; a denied share on the offering product runs the sequence, then shares', async () => {
        const c = rig({ queue: [{ isConfirmed: false }] })
        await c.run(COMPONENT, SHARE)
        expect(c.kinds()).toEqual(['fire'])
        const r = rig({ children: OFFERING, queue: [DENY] })
        await r.run(PRODUCT, SHARE)
        expect(r.kinds()).toEqual(['progress', 'ReleaseTeaPublishChildren', 'fire', 'progress', 'TeaProfileEditorView',
            'TeaProfileExposureChange', 'SaveTeaProfile', 'withProgress', 'PublishReleaseOnTea', 'fire'])
        expect(r.log.find(e => e[0] === 'PublishReleaseOnTea')).toEqual(['PublishReleaseOnTea', { release: 'r1', comment: 'Shared with customers' }])
    })

    it('(c) a refused right: the missing permission, as text, never through withProgress', async () => {
        const r = rig({ queue: [OK], publishError: REFUSED_RIGHT() })
        await r.run(COMPONENT, SHARE)
        expect(r.log.at(-1)).toEqual(['fire', { titleText: 'Error!',
            text: 'Sharing needs the Publish Externally (TEA) permission on this release. An organization admin can grant it.', icon: 'error' }])
        expect(r.rejections).toEqual([])
    })

    it('(c) a server refusal is shown verbatim, as text', async () => {
        const r = rig({ queue: [OK], publishError: ANSWERED('Release Pump 1.2.0 is archived and cannot be published on TEA') })
        await r.run(COMPONENT, SHARE)
        expect(r.log.at(-1)).toEqual(['fire', { titleText: 'Error!', text: 'Release Pump 1.2.0 is archived and cannot be published on TEA', icon: 'error' }])
        expect(r.rejections).toEqual([])
        const message = 'Component ' + HOSTILE + ' of release 1.0 is archived; its releases can only be hidden from TEA'
        const h = rig({ queue: [OK], publishError: ANSWERED(message) })
        await h.run(COMPONENT, SHARE)
        expect(h.log.at(-1)[1].text).toBe(message)
        expect(Object.keys(h.log.at(-1)[1]).sort()).toEqual(['icon', 'text', 'titleText'])
        const g = rig({ queue: [OK], publishError: GATEWAY() })
        await g.run(COMPONENT, SHARE)
        expect(g.log.at(-1)[1].text).toBe(TEA_ACT_GATEWAY_TEXT)
    })

    it('(c) control: a plain Publish leaves its error to withTeaProgress', async () => {
        const err = REFUSED_RIGHT()
        const r = rig({ queue: [OK], publishError: err })
        await r.run(COMPONENT)
        expect(r.rejections).toHaveLength(1)
        expect(r.rejections[0]).toBe(err)
        expect(r.fires()).toHaveLength(1)
    })

    it('(f) the share\'s result is worded; the plain Publish\'s is today\'s', async () => {
        const s = rig({ queue: [OK], result: R })
        await s.run(COMPONENT, SHARE)
        const p = rig({ queue: [OK], result: R })
        await p.run(COMPONENT)
        expect(s.log.at(-1)).toEqual(['fire', { title: 'TEA', text: teaPublishSummary(R, { worded: true }), icon: 'success' }])
        expect(p.log.at(-1)).toEqual(['fire', { title: 'TEA', text: teaPublishSummary(R), icon: 'success' }])
        expect(s.log.at(-1)[1].text).not.toBe(p.log.at(-1)[1].text)
    })
})
