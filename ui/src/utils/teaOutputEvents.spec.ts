// TEA output events (task TEA-13): the two option entries, the PUBLIC predicate, the publish wiring the
// save-time warning compares, and the copy of the confirmation and the badge.
import { describe, expect, it } from 'vitest'
import {
    outputEventTypeLabel, outputEventTypeOptionsForEdition, TEA_OUTPUT_EVENT_TYPE_OPTIONS, teaActionNames,
    teaConfirmBeforeAutoPublish, teaConfirmBeforeGlobalAutoPublish, teaOrgPublicBadgeTitle, teaOrgPublicSources,
    teaPublicBadgeTitle, teaPublishWiring, teaPublishWiringAdded, teaScopeIsPublic,
} from './teaOutputEvents'

const BASE = [
    { label: 'Release Lifecycle Change', value: 'RELEASE_LIFECYCLE_CHANGE' },
    { label: 'Email Notification', value: 'EMAIL_NOTIFICATION' },
    { label: 'VDR Snapshot Artifact', value: 'VDR_SNAPSHOT_ARTIFACT' },
    { label: 'Add Approved Environment', value: 'ADD_APPROVED_ENVIRONMENT' },
    { label: 'Lock', value: 'LOCK' },
]

const view = (effective: any, publishedReleases = 0) => ({ effective, publishedReleases })
const resolved = (publishing: string, visibility: string, source = 'COMPONENT', sourceObject: string | null = 'c1') =>
    ({ status: 'RESOLVED', source, sourceObject, profile: { publishing, visibility } })
const PUBLIC_OWN = (n = 2) => view(resolved('ENABLED', 'PUBLIC'), n)
const PRIVATE_OWN = view(resolved('ENABLED', 'PRIVATE'))

describe('TEA output event options (rows 1 to 3)', () => {
    it('row 1: the two entries are exactly TEA Publish and TEA Hide', () => {
        expect(TEA_OUTPUT_EVENT_TYPE_OPTIONS).toEqual([
            { label: 'TEA Publish', value: 'TEA_PUBLISH' },
            { label: 'TEA Hide', value: 'TEA_HIDE' },
        ])
    })

    it('row 2: inserted right after VDR_SNAPSHOT_ARTIFACT on a Pro edition and an unloaded user, never on CE', () => {
        for (const t of ['SAAS', undefined, null]) {
            const opts = outputEventTypeOptionsForEdition(BASE, t)
            const at = opts.findIndex(o => o.value === 'VDR_SNAPSHOT_ARTIFACT')
            expect(opts.slice(at + 1, at + 3)).toEqual(TEA_OUTPUT_EVENT_TYPE_OPTIONS)
            expect(opts.filter(o => !o.value.startsWith('TEA_'))).toEqual(BASE)
            expect(opts.length).toBe(BASE.length + 2)
            for (const o of opts) expect(Object.keys(o).sort()).toEqual(['label', 'value'])
        }
        expect(outputEventTypeOptionsForEdition(BASE, 'OSS')).toEqual(BASE)
    })

    it('row 3: the label of a stored type, whatever the edition; an unknown type reads as itself', () => {
        expect(outputEventTypeLabel(BASE, 'TEA_PUBLISH')).toBe('TEA Publish')
        expect(outputEventTypeLabel(BASE, 'TEA_HIDE')).toBe('TEA Hide')
        expect(outputEventTypeLabel(BASE, 'LOCK')).toBe('Lock')
        expect(outputEventTypeLabel(BASE, 'NO_SUCH_TYPE')).toBe('NO_SUCH_TYPE')
    })
})

describe('teaScopeIsPublic (row 4)', () => {
    it('is true only for a RESOLVED profile with publishing ENABLED and visibility PUBLIC', () => {
        expect(teaScopeIsPublic(view({ status: 'RESOLVED', profile: { publishing: 'ENABLED', visibility: 'PUBLIC' } }))).toBe(true)
        expect(teaScopeIsPublic(view(resolved('ENABLED', 'PRIVATE')))).toBe(false)
        expect(teaScopeIsPublic(view(resolved('DISABLED', 'PUBLIC')))).toBe(false)
        expect(teaScopeIsPublic(view({ status: 'CONFLICT', profile: { publishing: 'ENABLED', visibility: 'PUBLIC' } }))).toBe(false)
        expect(teaScopeIsPublic(null)).toBe(false)
        expect(teaScopeIsPublic({ publishedReleases: 1 })).toBe(false)
    })
})

describe('teaOrgPublicSources (row 5)', () => {
    const orgPrivate = view(resolved('ENABLED', 'PRIVATE', 'ORGANIZATION', null))
    const orgPublic = view(resolved('ENABLED', 'PUBLIC', 'ORGANIZATION', null), 3)

    it('is null when nothing resolves PUBLIC', () => {
        expect(teaOrgPublicSources(orgPrivate, [
            { scope: 'PERSPECTIVE', mode: 'OVERRIDE', publishing: 'ENABLED', visibility: 'PRIVATE' },
            { scope: 'COMPONENT', mode: 'FOLLOW_PERSPECTIVE', publishing: 'ENABLED', visibility: 'PUBLIC' },
        ])).toBeNull()
        expect(teaOrgPublicSources(null, null)).toBeNull()
    })

    it('names the organization and its count', () => {
        expect(teaOrgPublicSources(orgPublic, [])).toEqual({ organization: true, perspectives: 0, components: 0, publishedReleases: 3 })
    })

    it('counts PUBLIC ENABLED rows with their own fields, by scope', () => {
        expect(teaOrgPublicSources(orgPrivate, [
            { scope: 'PERSPECTIVE', mode: 'OVERRIDE', publishing: 'ENABLED', visibility: 'PUBLIC' },
            { scope: 'PERSPECTIVE', mode: null, publishing: 'ENABLED', visibility: 'PUBLIC' },
            { scope: 'COMPONENT', mode: 'OVERRIDE', publishing: 'ENABLED', visibility: 'PUBLIC' },
            { scope: 'COMPONENT', mode: 'OVERRIDE', publishing: 'DISABLED', visibility: 'PUBLIC' },
            { scope: 'COMPONENT', mode: 'FOLLOW_PERSPECTIVE', publishing: 'ENABLED', visibility: 'PUBLIC' },
        ])).toEqual({ organization: false, perspectives: 2, components: 1, publishedReleases: 0 })
    })
})

describe('teaPublishWiring and the delta (row 6)', () => {
    const outputTriggers = [
        { uuid: 'a', name: 'Publish it', type: 'TEA_PUBLISH' },
        { uuid: 'h', name: 'Hide it', type: 'TEA_HIDE' },
    ]
    const globals = [
        { uuid: 'g', name: 'Global publish', type: 'TEA_PUBLISH' },
        { uuid: 'm', name: 'Mail', type: 'EMAIL_NOTIFICATION' },
    ]
    const rules = [
        { uuid: 'g1', enabled: true, outputEvents: ['g'] },
        { uuid: 'g2', enabled: true, outputEvents: ['g'], outputEventsOnFalse: ['m'] },
        { uuid: 'g3', enabled: false, outputEvents: ['g'] },
        { uuid: 'g4', enabled: true, outputEvents: ['m'], outputEventsOnFalse: ['g'] },
        { uuid: 'g5', enabled: true, outputEvents: ['m'] },
        { uuid: 'g6', enabled: true, outputEvents: ['g'] },
    ]
    const baseline = () => ({
        outputTriggers: outputTriggers.map(o => ({ ...o })),
        releaseInputTriggers: [
            { uuid: 'r', name: 'r', enabled: true, outputEvents: ['a', 'h'], outputEventsOnFalse: ['h'] },
            { uuid: 'd', name: 'd', enabled: false, outputEvents: ['a'] },
        ],
        globalInputEventRefs: [
            { uuid: 'g1', disabled: true },
            { uuid: 'g2', overrideOutputEventsLocally: true, outputEventsOverride: ['m'] },
            { uuid: 'g4', overrideOutputEventsLocally: false, outputEventsOverride: ['g'] },
            { uuid: 'g5', overrideOutputEventsLocally: true, outputEventsOverride: ['a'] },
            { uuid: 'g6', overrideOutputEventsLocally: true, outputEventsOverride: [] },
        ] as any[],
    })
    const BASELINE_PIECES = ['action:a', 'policy:g4:false:g', 'policy:g5:true:a', 'rule:r:true:a']

    it('selects the pieces as the engine selects outputs', () => {
        const pieces = teaPublishWiring(baseline(), rules, globals)
        expect(pieces).toEqual(BASELINE_PIECES)
        const joined = pieces.join(' ')
        for (const absent of ['action:h', ':h', 'rule:d:', 'policy:g1:', 'policy:g2:', 'policy:g3:', 'policy:g6:']) {
            expect(joined).not.toContain(absent)
        }
    })

    it('answers the same array whatever the order of every list', () => {
        const c: any = baseline()
        c.outputTriggers.reverse()
        c.releaseInputTriggers.reverse()
        c.releaseInputTriggers.forEach((r: any) => r.outputEvents.reverse())
        c.globalInputEventRefs.reverse()
        expect(teaPublishWiring(c, [...rules].reverse(), [...globals].reverse())).toEqual(BASELINE_PIECES)
    })

    const added = (change: (c: any) => void) => {
        const changed: any = baseline()
        change(changed)
        return teaPublishWiringAdded(baseline(), changed, rules, globals)
    }
    const ref = (c: any, uuid: string) => c.globalInputEventRefs.find((r: any) => r.uuid === uuid)
    const dropRef = (c: any, uuid: string) => { c.globalInputEventRefs = c.globalInputEventRefs.filter((r: any) => r.uuid !== uuid) }

    it('a local rule switched back on adds its piece', () => {
        expect(added(c => { c.releaseInputTriggers[1].enabled = true })).toEqual(['rule:d:true:a'])
    })

    it('a ref un-disabled, or removed, adds the rule own list', () => {
        expect(added(c => { ref(c, 'g1').disabled = false })).toEqual(['policy:g1:true:g'])
        expect(added(c => dropRef(c, 'g1'))).toEqual(['policy:g1:true:g'])
    })

    it('an override reset, or the ref removed, adds the rule own list', () => {
        expect(added(c => { ref(c, 'g2').overrideOutputEventsLocally = false; ref(c, 'g2').outputEventsOverride = [] })).toEqual(['policy:g2:true:g'])
        expect(added(c => dropRef(c, 'g2'))).toEqual(['policy:g2:true:g'])
    })

    it('a ref on a rule disabled on the policy adds nothing; a rename adds nothing', () => {
        expect(added(c => { c.globalInputEventRefs.push({ uuid: 'g3', overrideOutputEventsLocally: true, outputEventsOverride: ['g'] }) })).toEqual([])
        expect(added(c => { c.releaseInputTriggers[1].name = 'renamed' })).toEqual([])
        expect(added(c => { c.outputTriggers[0].name = 'Publish it again' })).toEqual([])
    })

    it('a new TEA_PUBLISH action adds its piece; a new hide adds nothing', () => {
        expect(added(c => { c.outputTriggers.push({ uuid: 'b', name: 'Another', type: 'TEA_PUBLISH' }) })).toEqual(['action:b'])
        expect(added(c => { c.outputTriggers.push({ uuid: 'x', name: 'Hide too', type: 'TEA_HIDE' }) })).toEqual([])
    })

    it('a null stored component is compared on the same policy: what the policy fires without refs is not added', () => {
        expect(teaPublishWiringAdded(null, baseline(), rules, globals)).toEqual(['action:a', 'policy:g5:true:a', 'rule:r:true:a'])
    })

    it('teaActionNames: distinct names in piece order, local from outputTriggers, policy-wide from globals', () => {
        expect(teaActionNames(['policy:g1:true:g', 'policy:g5:true:a', 'rule:d:true:a'], outputTriggers, globals))
            .toEqual(['Global publish', 'Publish it'])
        expect(teaActionNames(['action:zz'], outputTriggers, globals)).toEqual(['zz'])
    })
})

describe('teaConfirmBeforeAutoPublish (row 7)', () => {
    it('is null when the scope is not PUBLIC', () => {
        expect(teaConfirmBeforeAutoPublish(PRIVATE_OWN, {}, 'component', ['Publish it'])).toBeNull()
        expect(teaConfirmBeforeAutoPublish(null, {}, 'component', ['Publish it'])).toBeNull()
        expect(teaConfirmBeforeAutoPublish(view({ status: 'CONFLICT', profile: { publishing: 'ENABLED', visibility: 'PUBLIC' } }), {}, 'component', ['x'])).toBeNull()
    })

    it('on an own PUBLIC row names the actions, the source and the count', () => {
        const c = teaConfirmBeforeAutoPublish(PUBLIC_OWN(2), {}, 'component', ['Publish it'])!
        expect(c.title).toBe('Publish automatically to the public?')
        expect(c.confirmButtonText).toBe('Publish publicly')
        expect(c.text).toContain('Action(s) Publish it publish releases of this component')
        expect(c.text).toContain('the PUBLIC TEA profile of component')
        expect(c.text).toContain('2 release(s) are already published under this profile')
    })

    it('on an inherited PUBLIC profile names the perspective and gives no count', () => {
        const c = teaConfirmBeforeAutoPublish(view(resolved('ENABLED', 'PUBLIC', 'PERSPECTIVE', 'p1'), 7), { p1: 'Field' }, 'product', ['Publish it'])!
        expect(c.text).toContain('perspective Field')
        expect(c.text).toContain('releases of this product')
        expect(c.text).not.toContain('published under this profile')
    })

    it('at 0 own-row releases says none is published yet', () => {
        expect(teaConfirmBeforeAutoPublish(PUBLIC_OWN(0), {}, 'component', ['Publish it'])!.text)
            .toContain('No release is published under this profile yet')
    })

    it('lists five names, then and N more', () => {
        const c = teaConfirmBeforeAutoPublish(PUBLIC_OWN(1), {}, 'component', ['n1', 'n2', 'n3', 'n4', 'n5', 'n6'])!
        expect(c.text).toContain('Action(s) n1, n2, n3, n4, n5, and 1 more publish')
        expect(c.text).not.toContain('n6')
    })

    it('keeps every name in text only: title and button stay constant (security review F-4)', () => {
        const c = teaConfirmBeforeAutoPublish(view(resolved('ENABLED', 'PUBLIC', 'PERSPECTIVE', 'p1')), { p1: '<i>p</i>' }, 'component', ['<b>x</b>'])!
        expect(c.text).toContain('Action(s) <b>x</b> publish')
        expect(c.text).toContain('perspective <i>p</i>')
        expect(c.title).toBe('Publish automatically to the public?')
        expect(c.confirmButtonText).toBe('Publish publicly')
        expect(Object.keys(c).sort()).toEqual(['confirmButtonText', 'text', 'title'])
    })
})

describe('teaConfirmBeforeGlobalAutoPublish (row 8)', () => {
    const ENDING = 'Components with their own PRIVATE profile are not affected. Components assigned to this policy later, including by an organization rule on their name, are enrolled too.'

    it('is null on null sources', () => {
        expect(teaConfirmBeforeGlobalAutoPublish(null, 'x')).toBeNull()
    })

    it('names the PUBLIC organization profile and its count', () => {
        const c = teaConfirmBeforeGlobalAutoPublish({ organization: true, perspectives: 0, components: 0, publishedReleases: 4 }, 'Global publish')!
        expect(c.title).toBe('Publish automatically to the public?')
        expect(c.confirmButtonText).toBe('Publish publicly')
        expect(c.text).toContain("Policy-wide action 'Global publish' publishes releases on TEA")
        expect(c.text).toContain("The organization's TEA profile is PUBLIC")
        expect(c.text).toContain('4 published release(s)')
        expect(c.text.endsWith(ENDING)).toBe(true)
    })

    it('at 0 organization releases says none is published yet', () => {
        const c = teaConfirmBeforeGlobalAutoPublish({ organization: true, perspectives: 0, components: 0, publishedReleases: 0 }, 'g')!
        expect(c.text).toContain('no release is published under the organization profile yet')
    })

    it('names PUBLIC perspective and component profiles by count, without a release count', () => {
        const c = teaConfirmBeforeGlobalAutoPublish({ organization: false, perspectives: 2, components: 0, publishedReleases: 9 }, 'g')!
        expect(c.text).toContain('2 perspective profile(s) are PUBLIC')
        expect(c.text).not.toContain('release(s)')
        expect(c.text).not.toContain('component profile(s)')
        expect(c.text.endsWith(ENDING)).toBe(true)
        const d = teaConfirmBeforeGlobalAutoPublish({ organization: false, perspectives: 0, components: 3, publishedReleases: 0 }, 'g')!
        expect(d.text).toContain('3 component profile(s) are PUBLIC. ')
        expect(d.text.endsWith(ENDING)).toBe(true)
    })

    it('keeps the action name in text only (security review F-4)', () => {
        const c = teaConfirmBeforeGlobalAutoPublish({ organization: true, perspectives: 0, components: 0, publishedReleases: 0 }, '<b>x</b>')!
        expect(c.text).toContain("'<b>x</b>'")
        expect(c.title).toBe('Publish automatically to the public?')
        expect(c.confirmButtonText).toBe('Publish publicly')
    })
})

describe('the badge titles (row 9)', () => {
    it('component page: null unless PUBLIC, the source named', () => {
        expect(teaPublicBadgeTitle(PRIVATE_OWN, {}, 'component')).toBeNull()
        expect(teaPublicBadgeTitle(null, {}, 'component')).toBeNull()
        expect(teaPublicBadgeTitle(PUBLIC_OWN(), {}, 'component'))
            .toBe('Publishes to the public: this component resolves to the PUBLIC TEA profile of component.')
        expect(teaPublicBadgeTitle(view(resolved('ENABLED', 'PUBLIC', 'PERSPECTIVE', 'p1')), { p1: 'Field' }, 'product'))
            .toBe('Publishes to the public: this product resolves to the PUBLIC TEA profile of perspective Field.')
    })

    it('policy page: null on null sources, the parts joined by ; in order', () => {
        expect(teaOrgPublicBadgeTitle(null)).toBeNull()
        expect(teaOrgPublicBadgeTitle({ organization: true, perspectives: 1, components: 0, publishedReleases: 0 }))
            .toBe('Publishes to the public: the organization profile is PUBLIC; 1 perspective profile(s) are PUBLIC.')
        expect(teaOrgPublicBadgeTitle({ organization: false, perspectives: 0, components: 2, publishedReleases: 0 }))
            .toBe('Publishes to the public: 2 component profile(s) are PUBLIC.')
    })
})
