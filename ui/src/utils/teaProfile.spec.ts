// TEA profiles (task TEA-2): the editor's option lists and its pure logic -- what the form starts
// from, what a save sends, and the copy of the source line, the banner and the PUBLIC confirmation.
import { describe, expect, it } from 'vitest'
import * as teaProfileModule from './teaProfile'
import {
    TEA_FIELD_HELP, TEA_MINIMUM_LIFECYCLE_OPTIONS,
    TEA_INHERIT, TEA_MODE_OPTIONS, TEA_PERSPECTIVE_MODE_OPTIONS, TEA_PRODUCT_COMPONENTS_OPTIONS, TEA_PROFILE_FIELDS,
    TEA_PUBLISHING_OPTIONS, TEA_RAW_ARTIFACTS_OPTIONS,
    TEA_TEI_OPTIONS, TEA_VISIBILITY_OPTIONS, teaBuiltInDefaults, teaFormFromView,
    teaConfirmBeforeMembership, teaConfirmBeforeRemoval, teaConfirmBeforeSave, TeaExposureChange, teaMembershipDelta,
    teaNeedsPublicConfirm, teaProfileInput,
    teaPublicBanner, teaPublicConfirmText, teaRowObject, teaSourceLine,
    teaInheritConfirm, teaInheritedForm, teaInitialMode, teaModeOptions, teaRowLink, teaInheritConflictHint,
} from './teaProfile'
import {
    TEA_AUTO_PUBLISH_OPTIONS, TEA_DOCUMENT_KINDS, TEA_PUBLIC_CONFIRM_COUNTED, TEA_PUBLIC_CONFIRM_NONE_YET,
    TEA_TURN_ON_TEXT, teaAutoPublishBanner, teaAutoPublishHelp, teaShownAutoPublishState, teaStartsAutoPublish,
    teaTurnOnConfirm, teaWouldStartAutoPublish,
} from './teaProfile'

const values = (opts: { label: string, value: string }[]) => opts.map(o => o.value)

describe('TEA profile option lists', () => {
    it('every list is {label, value} pairs carrying exactly the schema enum values', () => {
        const lists: [any[], string[]][] = [
            [TEA_MODE_OPTIONS, ['INHERIT', 'OVERRIDE', 'FOLLOW_PERSPECTIVE']],
            [TEA_PERSPECTIVE_MODE_OPTIONS, ['INHERIT', 'OVERRIDE']],
            [TEA_PUBLISHING_OPTIONS, ['ENABLED', 'DISABLED']],
            [TEA_VISIBILITY_OPTIONS, ['PRIVATE', 'PUBLIC']],
            [TEA_RAW_ARTIFACTS_OPTIONS, ['NONE', 'NON_BOM', 'ALL']],
            [TEA_PRODUCT_COMPONENTS_OPTIONS, ['PUBLISH_WITH_PRODUCT', 'PRODUCT_ONLY']],
            [TEA_TEI_OPTIONS, ['DISABLED', 'UUID']],
        ]
        for (const [list, expected] of lists) {
            expect(values(list)).toEqual(expected)
            for (const o of list) {
                expect(Object.keys(o).sort()).toEqual(['label', 'value'])
                expect(typeof o.label).toBe('string')
                expect(o.label.length).toBeGreaterThan(0)
            }
        }
    })

    it('the mode radio per scope: Inherit first; none at the organization, no Follow at a perspective or on CE', () => {
        expect(TEA_INHERIT).toBe('INHERIT')
        expect(teaModeOptions('ORGANIZATION', 'SAAS')).toEqual([])
        expect(values(teaModeOptions('PERSPECTIVE', 'SAAS'))).toEqual(['INHERIT', 'OVERRIDE'])
        expect(values(teaModeOptions('COMPONENT', 'SAAS'))).toEqual(['INHERIT', 'OVERRIDE', 'FOLLOW_PERSPECTIVE'])
        expect(values(teaModeOptions('COMPONENT', 'OSS'))).toEqual(['INHERIT', 'OVERRIDE'])
        expect(teaModeOptions('COMPONENT', 'SAAS')[0].label).toBe('Inherit: use the perspective or organization profile')
        expect(teaModeOptions('PERSPECTIVE', 'SAAS')[0].label).toBe('Inherit: use the organization profile')
        expect(teaModeOptions('PERSPECTIVE', 'SAAS')[1].label).toBe('Override: this perspective carries its own profile')
        expect(teaModeOptions('COMPONENT', 'OSS')[0].label).toBe('Inherit: use the organization profile')
    })

    it('offers only the minimum lifecycles the server accepts', () => {
        expect(values(TEA_MINIMUM_LIFECYCLE_OPTIONS)).toEqual(['ASSEMBLED', 'READY_TO_SHIP', 'GENERAL_AVAILABILITY'])
    })
})

// TEA-30 rows 9 and 10: the recipe left the TEA row for named SBOM profiles; the row names them.
describe('the TEA row names SBOM profiles (TEA-30)', () => {
    it('row 9: the fields are the eight channel names ending in profiles, and no recipe option list is left', () => {
        expect([...TEA_PROFILE_FIELDS]).toEqual(['publishing', 'visibility', 'rawArtifacts', 'productComponents',
            'minimumLifecycle', 'tei', 'teiDomain', 'profiles'])
        const exported = Object.keys(teaProfileModule)
        for (const gone of ['TEA_DEPENDENCY_DEPTH_OPTIONS', 'TEA_OPTIONAL_DEPENDENCIES_OPTIONS', 'TEA_STRUCTURE_OPTIONS',
            'TEA_SOURCES_OPTIONS', 'TEA_EXCLUDED_COVERAGE_OPTIONS', 'TEA_SUPPORT_METADATA_OPTIONS',
            'TEA_INTERNAL_METADATA_OPTIONS', 'TEA_VULNERABILITY_DOCUMENTS_OPTIONS']) {
            expect(exported, gone).not.toContain(gone)
        }
        for (const f of ['dependencyDepth', 'optionalDependencies', 'structure', 'sources', 'excludedCoverage',
            'supportMetadata', 'internalMetadata', 'vulnerabilityDocuments']) {
            expect(TEA_FIELD_HELP, f).not.toHaveProperty(f)
            expect(teaBuiltInDefaults(), f).not.toHaveProperty(f)
        }
        expect(teaBuiltInDefaults().profiles).toEqual(['default'])
        expect(TEA_FIELD_HELP.profiles).toContain('one aggregated SBOM each, in this order')
    })

    it('row 10: the form copies profiles from the stored row, else the parent, else default', () => {
        const stored = { ...teaBuiltInDefaults(), mode: 'OVERRIDE', profiles: ['top', 'default'] }
        const parent = { ...teaBuiltInDefaults(), profiles: ['parent-one'] }
        const fromStored = teaFormFromView({ stored, parent: { profile: parent } }, 'COMPONENT')
        expect(fromStored.profiles).toEqual(['top', 'default'])
        expect(fromStored.profiles).not.toBe(stored.profiles)
        expect(teaFormFromView({ stored: null, parent: { profile: parent } }, 'COMPONENT').profiles).toEqual(['parent-one'])
        expect(teaFormFromView({ stored: null, parent: { status: 'CONFLICT', profile: null } }, 'COMPONENT').profiles)
            .toEqual(['default'])
        // A FOLLOW row stores no names: pre-filled from the parent.
        expect(teaFormFromView({ stored: { mode: 'FOLLOW_PERSPECTIVE', followedPerspective: 'p1', profiles: null },
            parent: { profile: parent } }, 'COMPONENT').profiles).toEqual(['parent-one'])
    })

    it('row 10: a save sends profiles as a copy in order on Pro, default only on CE, none on FOLLOW', () => {
        const form = { ...teaBuiltInDefaults(), mode: 'OVERRIDE', profiles: ['top', 'default'] }
        const pro = teaProfileInput(form, 'COMPONENT')
        expect(pro.profiles).toEqual(['top', 'default'])
        expect(pro.profiles).not.toBe(form.profiles)
        expect(teaProfileInput(form, 'COMPONENT', 'SAAS').profiles).toEqual(['top', 'default'])
        // CE stores the one `default` SBOM profile whatever the form holds.
        expect(teaProfileInput(form, 'ORGANIZATION', 'OSS').profiles).toEqual(['default'])
        expect(teaProfileInput({ ...form, profiles: [] }, 'COMPONENT', 'OSS').profiles).toEqual(['default'])
        expect(teaProfileInput({ ...form, mode: 'FOLLOW_PERSPECTIVE', followedPerspective: 'p1' }, 'COMPONENT'))
            .toEqual({ mode: 'FOLLOW_PERSPECTIVE', followedPerspective: 'p1' })
        expect(Object.keys(pro).sort()).toEqual([...TEA_PROFILE_FIELDS, 'mode'].sort())
    })
})

const PARENT = { ...teaBuiltInDefaults(), publishing: 'ENABLED', minimumLifecycle: 'READY_TO_SHIP', uuid: 'org-row' }

describe('the form and the save', () => {
    it('starts from the stored row, else the parent, else the defaults', () => {
        const stored = { ...teaBuiltInDefaults(), mode: 'OVERRIDE', visibility: 'PUBLIC' }
        expect(teaFormFromView({ stored, parent: { profile: PARENT } }, 'COMPONENT').visibility).toBe('PUBLIC')
        const fromParent = teaFormFromView({ stored: null, parent: { status: 'RESOLVED', profile: PARENT } }, 'COMPONENT')
        expect(fromParent.minimumLifecycle).toBe('READY_TO_SHIP')
        expect(fromParent.mode).toBe('INHERIT')
        const conflict = teaFormFromView({ stored: null, parent: { status: 'CONFLICT', profile: null } }, 'COMPONENT')
        expect(conflict.publishing).toBe('DISABLED')
        expect(teaFormFromView({ stored: null, parent: { profile: PARENT } }, 'ORGANIZATION').mode).toBeNull()
    })

    it('opens in INHERIT with no stored row, in the stored mode otherwise, with no mode at the organization', () => {
        expect(teaInitialMode(null, 'COMPONENT')).toBe('INHERIT')
        expect(teaInitialMode(null, 'PERSPECTIVE')).toBe('INHERIT')
        expect(teaInitialMode(null, 'ORGANIZATION')).toBeNull()
        expect(teaInitialMode({ uuid: 'r', mode: 'OVERRIDE' }, 'COMPONENT')).toBe('OVERRIDE')
        expect(teaInitialMode({ uuid: 'r', mode: 'FOLLOW_PERSPECTIVE' }, 'COMPONENT')).toBe('FOLLOW_PERSPECTIVE')
        expect(teaInitialMode({ uuid: 'r', mode: null }, 'PERSPECTIVE')).toBe('OVERRIDE')
        expect(teaInitialMode({ uuid: 'r' }, 'ORGANIZATION')).toBeNull()
        expect(teaFormFromView({ stored: null, parent: { profile: PARENT } }, 'PERSPECTIVE').mode).toBe('INHERIT')
        expect(teaFormFromView({ stored: { ...PARENT }, parent: { profile: PARENT } }, 'PERSPECTIVE').mode).toBe('OVERRIDE')
    })

    it('an INHERIT form shows the parent values, else the built-in defaults, whatever is stored', () => {
        const stored = { ...teaBuiltInDefaults(), mode: 'OVERRIDE', minimumLifecycle: 'GENERAL_AVAILABILITY' }
        const f = teaInheritedForm({ stored, parent: { status: 'RESOLVED', profile: PARENT } })
        expect(f.mode).toBe('INHERIT')
        expect(f.minimumLifecycle).toBe('READY_TO_SHIP')
        expect(f.publishing).toBe('ENABLED')
        expect(teaInheritedForm({ stored: null, parent: { status: 'CONFLICT', profile: null } }).publishing).toBe('DISABLED')
    })

    it('never builds a save from INHERIT: inheriting again removes the row instead', () => {
        const form = { ...teaBuiltInDefaults(), mode: 'INHERIT' }
        for (const scope of ['COMPONENT', 'PERSPECTIVE', 'ORGANIZATION'] as const) {
            expect(() => teaProfileInput(form, scope), scope).toThrow('INHERIT is not stored')
        }
    })

    it('a FOLLOW row keeps its perspective and pre-fills the fields from the parent', () => {
        const f = teaFormFromView({ stored: { mode: 'FOLLOW_PERSPECTIVE', followedPerspective: 'p1', publishing: null },
            parent: { profile: PARENT } }, 'COMPONENT')
        expect(f.mode).toBe('FOLLOW_PERSPECTIVE')
        expect(f.followedPerspective).toBe('p1')
        expect(f.publishing).toBe('ENABLED')
    })

    it('sends every field on an override, the mode and perspective only on a FOLLOW', () => {
        const form = { ...teaBuiltInDefaults(), mode: 'OVERRIDE', teiDomain: 'stale.example.com' }
        const input = teaProfileInput(form, 'COMPONENT')
        expect(input.mode).toBe('OVERRIDE')
        for (const f of TEA_PROFILE_FIELDS) expect(input).toHaveProperty(f)
        expect(input.teiDomain).toBeNull()
        expect(teaProfileInput({ ...form, tei: 'UUID', teiDomain: 'products.example.com' }, 'ORGANIZATION'))
            .toMatchObject({ tei: 'UUID', teiDomain: 'products.example.com' })
        expect(teaProfileInput(form, 'ORGANIZATION')).not.toHaveProperty('mode')
        expect(teaProfileInput({ ...form, mode: 'FOLLOW_PERSPECTIVE', followedPerspective: 'p2' }, 'COMPONENT'))
            .toEqual({ mode: 'FOLLOW_PERSPECTIVE', followedPerspective: 'p2' })
    })
})

describe('the copy', () => {
    it('says where an unsaved form comes from', () => {
        expect(teaSourceLine({ stored: { uuid: 'x' } })).toBe('Stored override')
        expect(teaSourceLine({ stored: null, parent: { status: 'RESOLVED', source: 'ORGANIZATION', profile: PARENT } }))
            .toBe('No override: pre-filled from organization')
        expect(teaSourceLine({ stored: null, parent: { status: 'RESOLVED', source: 'PERSPECTIVE', sourceObject: 'p1', profile: PARENT } },
            { p1: 'Payments' })).toBe('No override: pre-filled from perspective Payments')
        expect(teaSourceLine({ stored: null, parent: { status: 'RESOLVED', source: 'DEFAULT', profile: PARENT } }))
            .toBe('No override: pre-filled from built-in defaults')
    })

    it('in INHERIT, says where the inherited values come from, and that Save removes a stored row', () => {
        const org = { status: 'RESOLVED', source: 'ORGANIZATION', profile: PARENT }
        expect(teaSourceLine({ stored: null, parent: org }, {}, 'INHERIT')).toBe('Inherited from organization')
        expect(teaSourceLine({ stored: null, parent: { ...org, source: 'PERSPECTIVE', sourceObject: 'p1' } }, { p1: 'Payments' }, 'INHERIT'))
            .toBe('Inherited from perspective Payments')
        expect(teaSourceLine({ stored: null, parent: { status: 'RESOLVED', source: 'DEFAULT', profile: PARENT } }, {}, 'INHERIT'))
            .toBe('Inherited from built-in defaults')
        expect(teaSourceLine({ stored: null, parent: null }, {}, 'INHERIT')).toBe('Inherited from built-in defaults')
        expect(teaSourceLine({ stored: { uuid: 'x' }, parent: org }, {}, 'INHERIT'))
            .toBe('Inherited from organization: Save removes the stored override')
        expect(teaSourceLine({ stored: null, parent: { status: 'CONFLICT', profile: null } }, {}, 'INHERIT'))
            .toBe('Cannot inherit: its perspectives each carry a TEA profile')
        // Override over no row keeps the pre-fill wording.
        expect(teaSourceLine({ stored: null, parent: org }, {}, 'OVERRIDE')).toBe('No override: pre-filled from organization')
    })

    it('the "Inherit again?" confirmation names the scope and its parents', () => {
        expect(teaInheritConfirm('COMPONENT', 'component')).toEqual({ title: 'Inherit again?',
            content: "Remove this component's override and inherit the perspective or organization profile again?" })
        expect(teaInheritConfirm('COMPONENT', 'product').content)
            .toBe("Remove this product's override and inherit the perspective or organization profile again?")
        expect(teaInheritConfirm('PERSPECTIVE', 'perspective').content)
            .toBe("Remove this perspective's override and inherit the organization profile again?")
    })

    it('banners a stored PUBLIC row and an inherited PUBLIC profile, nothing else', () => {
        expect(teaPublicBanner({ stored: { visibility: 'PUBLIC' } })).toContain('This TEA profile is PUBLIC')
        expect(teaPublicBanner({ stored: { visibility: 'PRIVATE' }, effective: { profile: { visibility: 'PUBLIC' } } })).toBeNull()
        expect(teaPublicBanner({ stored: null, effective: { source: 'PERSPECTIVE', sourceObject: 'p1', profile: { visibility: 'PUBLIC' } } },
            { p1: 'Payments' })).toContain('PUBLIC, inherited from perspective Payments')
        expect(teaPublicBanner({ stored: null, effective: { profile: { visibility: 'PRIVATE' } } })).toBeNull()
    })

    it('banners a FOLLOW_PERSPECTIVE row by the profile it resolves to, since the row stores no visibility', () => {
        const follow = { mode: 'FOLLOW_PERSPECTIVE', followedPerspective: 'p1', visibility: null }
        expect(teaPublicBanner({ stored: follow, effective: { source: 'FOLLOWED_PERSPECTIVE', sourceObject: 'p1',
            profile: { visibility: 'PUBLIC' } } }, { p1: 'zeta-p1' })).toBe('This TEA profile is PUBLIC, inherited from '
            + 'perspective zeta-p1: releases resolving to it are readable without authentication.')
        expect(teaPublicBanner({ stored: follow, effective: { source: 'FOLLOWED_PERSPECTIVE', sourceObject: 'p1',
            profile: { visibility: 'PRIVATE' } } })).toBeNull()
        // A dangling FOLLOW resolves through the candidates; a conflict there has no profile and no banner.
        expect(teaPublicBanner({ stored: follow, effective: { source: 'ORGANIZATION', profile: { visibility: 'PUBLIC' } } }))
            .toContain('PUBLIC, inherited from organization')
        expect(teaPublicBanner({ stored: follow, effective: { status: 'CONFLICT', profile: null } })).toBeNull()
    })

    it('states how many published releases a PUBLIC save exposes, and handles none', () => {
        expect(teaPublicConfirmText(3)).toContain('3 published release(s)')
        expect(teaPublicConfirmText(0)).toBe('No release is published under this profile yet; every future publication under it will be public.')
    })

})

// Task TEA-9, design 4.5 cases 21-24: the confirmations follow the server's exposure answer, and the
// org table names a row by name with an archived tag.
const exposure = (before: string, after: string, path: string, changes: Record<string, any> = {}): TeaExposureChange =>
    ({ before, after, path, afterSource: null, afterSourceObject: null, publishedReleases: 0, ...changes } as any)

describe('the exposure confirmations (TEA-9)', () => {
    const names = { p1: 'Payments' }

    it('asks exactly when the object becomes PUBLIC', () => {
        expect(teaNeedsPublicConfirm(exposure('PRIVATE', 'PUBLIC', 'ROW_PUBLIC'))).toBe(true)
        expect(teaNeedsPublicConfirm(exposure('UNRESOLVED', 'PUBLIC', 'FOLLOW_RESOLVES_PUBLIC'))).toBe(true)
        expect(teaNeedsPublicConfirm(exposure('PUBLIC', 'PUBLIC', 'ROW_PUBLIC'))).toBe(false)
        expect(teaNeedsPublicConfirm(exposure('PRIVATE', 'PRIVATE', 'NONE'))).toBe(false)
        expect(teaNeedsPublicConfirm(exposure('PUBLIC', 'PRIVATE', 'NONE'))).toBe(false)
        expect(teaNeedsPublicConfirm(null)).toBe(false)
    })

    it('confirms a save on a PUBLIC row with the count, and on a FOLLOW naming the perspective and the source', () => {
        expect(teaConfirmBeforeSave(exposure('PUBLIC', 'PUBLIC', 'NONE'), 'component', names)).toBeNull()
        const three = teaConfirmBeforeSave(exposure('PRIVATE', 'PUBLIC', 'ROW_PUBLIC', { publishedReleases: 3 }), 'component')!
        expect(three.title).toBe('Make this TEA profile public?')
        expect(three.confirmButtonText).toBe('Make it public')
        expect(three.text).toBe(teaPublicConfirmText(3))
        expect(three.text).toContain('3 published release(s)')
        expect(teaConfirmBeforeSave(exposure('PRIVATE', 'PUBLIC', 'ROW_PUBLIC'), 'perspective')!.text)
            .toContain('No release is published under this profile yet')

        const follow = teaConfirmBeforeSave(exposure('PRIVATE', 'PUBLIC', 'FOLLOW_RESOLVES_PUBLIC',
            { afterSource: 'FOLLOWED_PERSPECTIVE', afterSourceObject: 'p1' }), 'component', names, 'p1')!
        expect(follow.text).toBe('Following perspective Payments resolves this component to the PUBLIC profile of '
            + 'perspective Payments. ' + teaPublicConfirmText(0))
        const throughOrg = teaConfirmBeforeSave(exposure('PRIVATE', 'PUBLIC', 'FOLLOW_RESOLVES_PUBLIC',
            { afterSource: 'ORGANIZATION' }), 'product', names, 'p1')!
        expect(throughOrg.text).toContain('Following perspective Payments resolves this product to the PUBLIC profile of organization. ')
    })

    it('confirms a removal plainly, or as a PUBLIC change naming the source and the count', () => {
        const plain = teaConfirmBeforeRemoval(exposure('PRIVATE', 'PRIVATE', 'NONE'), 'component', names)
        expect(plain).toEqual({ title: 'Remove this TEA profile override?',
            text: 'The component then resolves its TEA profile from its parent again.', confirmButtonText: 'Remove' })
        const widening = teaConfirmBeforeRemoval(exposure('PRIVATE', 'PUBLIC', 'REMOVAL_RESOLVES_PUBLIC',
            { afterSource: 'PERSPECTIVE', afterSourceObject: 'p1', publishedReleases: 2 }), 'component', names)
        expect(widening.title).toBe('Make this TEA profile public?')
        expect(widening.confirmButtonText).toBe('Remove and make it public')
        expect(widening.text).toBe('Removing the override lets this component resolve to the PUBLIC profile of perspective '
            + 'Payments. ' + teaPublicConfirmText(2))
    })

    it('names an org table row by name, tags an archived one, and falls back to the uuid only for an unknown object', () => {
        const objects = { c1: { name: 'widget', status: 'ACTIVE' }, c2: { name: 'old-widget', status: 'ARCHIVED' },
            p1: { name: 'Payments' } }
        expect(teaRowObject({ object: 'c1' }, objects)).toEqual({ name: 'widget', archived: false })
        expect(teaRowObject({ object: 'c2' }, objects)).toEqual({ name: 'old-widget', archived: true })
        expect(teaRowObject({ object: 'p1' }, objects)).toEqual({ name: 'Payments', archived: false })
        expect(teaRowObject({ object: 'c9' }, objects)).toEqual({ name: 'c9', archived: false })
    })
})

// Task TEA-9 round 2, design 4.5 cases 51-53: membership changes and perspective-row removals that
// make other components PUBLIC.
describe('the membership and member confirmations (TEA-9 round 2)', () => {
    const names = { p1: 'Payments', p2: 'Internal' }
    const member = (n: number) => ({ component: 'c' + n, name: 'comp-' + n, afterSource: 'PERSPECTIVE', afterSourceObject: 'p1' })
    const members = (k: number) => Array.from({ length: k }, (_, i) => member(i + 1))

    it('asks when a member widens although the object itself stays PRIVATE; a missing widened list is empty', () => {
        expect(teaNeedsPublicConfirm(exposure('PRIVATE', 'PRIVATE', 'REMOVAL_RESOLVES_PUBLIC', { widened: [member(1)] }))).toBe(true)
        expect(teaNeedsPublicConfirm(exposure('PRIVATE', 'PRIVATE', 'NONE', { widened: [] }))).toBe(false)
        expect(teaNeedsPublicConfirm(exposure('PRIVATE', 'PRIVATE', 'NONE', { widened: null }))).toBe(false)
        expect(teaNeedsPublicConfirm(exposure('PRIVATE', 'PRIVATE', 'NONE'))).toBe(false)
        expect(teaNeedsPublicConfirm(exposure('PRIVATE', 'PUBLIC', 'MEMBERSHIP_RESOLVES_PUBLIC', { widened: [] }))).toBe(true)
    })

    it('confirms a membership change naming what it adds and removes, the source and the count; null when nothing widens', () => {
        expect(teaConfirmBeforeMembership(exposure('PUBLIC', 'PUBLIC', 'NONE'), ['Payments'], [], names)).toBeNull()
        expect(teaConfirmBeforeMembership(exposure('PRIVATE', 'PRIVATE', 'NONE'), ['Internal'], [], names)).toBeNull()
        expect(teaConfirmBeforeMembership(null, ['Payments'], [], names)).toBeNull()

        const both = teaConfirmBeforeMembership(exposure('PRIVATE', 'PUBLIC', 'MEMBERSHIP_RESOLVES_PUBLIC',
            { afterSource: 'PERSPECTIVE', afterSourceObject: 'p1', publishedReleases: 4 }), ['Payments'], ['Internal'], names)!
        expect(both).toEqual({ title: 'Make this component public?',
            text: "Changing this component's perspectives (adding Payments; removing Internal) resolves it to the PUBLIC "
                + 'profile of perspective Payments. ' + teaPublicConfirmText(4),
            confirmButtonText: 'Make it public' })
        expect(both.text).toContain('4 published release(s)')

        const addedOnly = teaConfirmBeforeMembership(exposure('PRIVATE', 'PUBLIC', 'MEMBERSHIP_RESOLVES_PUBLIC',
            { afterSource: 'PERSPECTIVE', afterSourceObject: 'p1' }), ['Payments'], [], names)!
        expect(addedOnly.text).toContain('(adding Payments)')
        expect(addedOnly.text).not.toContain('removing')
        expect(addedOnly.text).toContain(teaPublicConfirmText(0))
    })

    it('says product for a product, and drops the adding clause when the change only removes', () => {
        const removedOnly = teaConfirmBeforeMembership(exposure('UNRESOLVED', 'PUBLIC', 'MEMBERSHIP_RESOLVES_PUBLIC',
            { afterSource: 'ORGANIZATION' }), [], ['Internal'], names, 'product')!
        expect(removedOnly.title).toBe('Make this product public?')
        expect(removedOnly.text).toBe("Changing this product's perspectives (removing Internal) resolves it to the PUBLIC profile "
            + 'of organization. ' + teaPublicConfirmText(0))
    })

    it('confirms a perspective-row removal that widens members, naming up to five', () => {
        const two = teaConfirmBeforeRemoval(exposure('PRIVATE', 'PRIVATE', 'REMOVAL_RESOLVES_PUBLIC',
            { widened: members(2) }), 'perspective', names)
        expect(two.title).toBe('Make this TEA profile public?')
        expect(two.confirmButtonText).toBe('Remove and make them public')
        expect(two.text).toBe('2 component(s) of this perspective then resolve to a PUBLIC profile: comp-1, comp-2. '
            + teaPublicConfirmText(0))

        const seven = teaConfirmBeforeRemoval(exposure('PRIVATE', 'PRIVATE', 'REMOVAL_RESOLVES_PUBLIC',
            { widened: members(7) }), 'perspective', names)
        expect(seven.text).toContain('7 component(s) of this perspective then resolve to a PUBLIC profile: '
            + 'comp-1, comp-2, comp-3, comp-4, comp-5, and 2 more. ')
        expect(seven.text).not.toContain('comp-6')

        const both = teaConfirmBeforeRemoval(exposure('PRIVATE', 'PUBLIC', 'REMOVAL_RESOLVES_PUBLIC',
            { afterSource: 'ORGANIZATION', widened: members(1), publishedReleases: 1 }), 'perspective', names)
        expect(both.text).toBe('1 component(s) of this perspective then resolve to a PUBLIC profile: comp-1. '
            + 'Removing the override lets this perspective resolve to the PUBLIC profile of organization. ' + teaPublicConfirmText(1))
        expect(both.confirmButtonText).toBe('Remove and make it public')
    })

    it('computes what a membership write adds and removes by name, from the list it sends and the stored set', () => {
        const org = [{ uuid: 'p1', name: 'Payments' }, { uuid: 'p2', name: 'Internal' }, { uuid: 'p3', name: 'Retail' }]
        expect(teaMembershipDelta(['p1', 'p3'], [{ uuid: 'p2', name: 'Internal' }, { uuid: 'p3', name: 'Retail' }], org))
            .toEqual({ added: ['Payments'], removed: ['Internal'],
                names: { p1: 'Payments', p2: 'Internal', p3: 'Retail' } })
        // The members modal sends one perspective and does not know the stored set: nothing reads as removed.
        expect(teaMembershipDelta(['p1'], undefined, org)).toEqual({ added: ['Payments'], removed: [],
            names: { p1: 'Payments', p2: 'Internal', p3: 'Retail' } })
        expect(teaMembershipDelta([], [{ uuid: 'p1' }], org).removed).toEqual(['Payments'])
        // A stored perspective the org list no longer holds keeps its own name; an unknown uuid reads as itself.
        expect(teaMembershipDelta(['px'], [{ uuid: 'old', name: 'Legacy' }], null)).toEqual({ added: ['px'], removed: ['Legacy'],
            names: { old: 'Legacy' } })
        expect(teaMembershipDelta(['p1'], [{ uuid: 'p1', name: 'Payments' }], org)).toMatchObject({ added: [], removed: [] })
    })
})

describe('the org table links each override to where it is edited', () => {
    it('a component or product to its settings TEA tab, a perspective to the Perspectives tab, nothing else', () => {
        expect(teaRowLink({ scope: 'COMPONENT', object: 'c1' }, 'o1'))
            .toBe('/componentsOfOrg/o1/c1?componentSettingsView=true&componentSettingsTab=tea')
        expect(teaRowLink({ scope: 'COMPONENT', object: 'c1' }, 'o1', 'COMPONENT'))
            .toBe('/componentsOfOrg/o1/c1?componentSettingsView=true&componentSettingsTab=tea')
        expect(teaRowLink({ scope: 'COMPONENT', object: 'pr1' }, 'o1', 'PRODUCT'))
            .toBe('/productsOfOrg/o1/pr1?componentSettingsView=true&componentSettingsTab=tea')
        expect(teaRowLink({ scope: 'PERSPECTIVE', object: 'p1' }, 'o1')).toBe('/orgSettings/o1?tab=perspectives')
        expect(teaRowLink({ scope: 'ORGANIZATION', object: null }, 'o1')).toBeNull()
        expect(teaRowLink({ scope: 'COMPONENT', object: null }, 'o1')).toBeNull()
    })
})

describe('the INHERIT conflict hint', () => {
    const resolved = { parent: { status: 'RESOLVED', source: 'PERSPECTIVE', sourceObject: 'p1', profile: {} } }

    it('says how inheriting resolves at COMPONENT scope in INHERIT, for a component with perspectives', () => {
        expect(teaInheritConflictHint('COMPONENT', TEA_INHERIT, 1, resolved)).toBe("Inheriting resolves through this "
            + "component's perspectives: if more than one of them carries its own TEA profile, inheriting stops with a "
            + 'conflict \u2014 use Follow one of its perspectives to pin one.')
        expect(teaInheritConflictHint('COMPONENT', TEA_INHERIT, 3, null)).not.toBeNull()
    })

    it('is null in Override or Follow, at another scope, without perspectives, and under a CONFLICT', () => {
        expect(teaInheritConflictHint('COMPONENT', 'OVERRIDE', 1, resolved)).toBeNull()
        expect(teaInheritConflictHint('COMPONENT', 'FOLLOW_PERSPECTIVE', 1, resolved)).toBeNull()
        expect(teaInheritConflictHint('PERSPECTIVE', TEA_INHERIT, 1, resolved)).toBeNull()
        expect(teaInheritConflictHint('ORGANIZATION', TEA_INHERIT, 1, resolved)).toBeNull()
        expect(teaInheritConflictHint('COMPONENT', TEA_INHERIT, 0, resolved)).toBeNull()
        expect(teaInheritConflictHint('COMPONENT', TEA_INHERIT, 2, { parent: { status: 'CONFLICT' } })).toBeNull()
    })
})

// FDAW-39 (FDAW-25 D2f), architecture-5 section 4. INPUT is the design's fixture: the stored row's
// settings with publishing ENABLED, as the editor's input carries them.
const INPUT = (autoPublish?: string) => ({ mode: 'OVERRIDE', publishing: 'ENABLED', visibility: 'PRIVATE', rawArtifacts: 'NONE',
    productComponents: 'PUBLISH_WITH_PRODUCT', minimumLifecycle: 'ASSEMBLED', tei: 'DISABLED', teiDomain: null,
    profiles: ['default'], ...(autoPublish === undefined ? {} : { autoPublish }) })
const COUNTED = (n: number, m: number) => n + ' published release(s) resolving to this profile, with all ' + m + ' of their '
    + 'collection versions, earlier versions included, and every document in them (SBOMs, VEX documents, device support '
    + 'statements, published artifacts), become readable by anyone who knows the TEA URL as soon as you save; visibility is live.'
const TODAY_FOR = (n: number) => n + ' published release(s) resolving to this profile become readable by anyone who knows '
    + 'the TEA URL as soon as you save; visibility is live.'
const TODAY_NONE = 'No release is published under this profile yet; every future publication under it will be public.'
const NONE_YET = 'No release is published under this profile yet; every future publication under it will be public. Every '
    + 'version published under it later will be public, with every document in it (SBOMs, VEX documents, device support '
    + 'statements, published artifacts).'
const HELP = 'When on, a release is published on TEA when it reaches Shipped and published again each time it changes after '
    + 'that. When off, publishing is left to the Publish button and to approval-policy actions. Turning it on needs the '
    + 'Publish Externally (TEA) permission.'
const TURN_ON = 'Saving publishes nothing by itself. A release already at Shipped or later under this profile is published '
    + 'the next time it changes (it is saved, its lifecycle changes or its vulnerability findings change), unless it is '
    + 'hidden on TEA. After that, every such change publishes it again; a change that leaves the published documents as they '
    + 'are adds no version. Component releases resolving to this profile are published on their own when they ship; a '
    + 'product published later lists them as already published.'
const PAUSED_OWN = (w: string) => 'Publish at shipped is paused: these settings changed since a holder confirmed them. Save '
    + 'them as a holder of Publish Externally (TEA) on this ' + w + ', or as an organization admin, to resume.'
const PAUSED_INHERITED = (source: string, w: string) => 'Publish at shipped is paused in the TEA profile of ' + source
    + ' that this ' + w + ' resolves to. A holder of Publish Externally (TEA), or an organization admin, saves that profile to resume.'
const exposureOf = (changes: Record<string, any> = {}): TeaExposureChange =>
    ({ before: 'PRIVATE', after: 'PRIVATE', path: 'NONE', afterSource: null, afterSourceObject: null, publishedReleases: 0, ...changes } as any)
const occurrences = (text: string, part: string) => text.split(part).length - 1

describe('FDAW-39 row 22 (a)-(f): publish at shipped in the form and the counted Public confirmation', () => {
    it('(a) the defaults turn it on, the two options in order, and TEA_PROFILE_FIELDS keeps its eight names', () => {
        expect(teaBuiltInDefaults().autoPublish).toBe('AT_SHIPPED')
        expect(TEA_AUTO_PUBLISH_OPTIONS).toEqual([
            { value: 'DISABLED', label: 'Off: publish by hand or by an approval-policy action' },
            { value: 'AT_SHIPPED', label: 'On: publish when the release reaches Shipped' },
        ])
        expect([...TEA_PROFILE_FIELDS]).toEqual(['publishing', 'visibility', 'rawArtifacts', 'productComponents',
            'minimumLifecycle', 'tei', 'teiDomain', 'profiles'])
    })

    it('(b) the form pre-fills autoPublish from the stored row, else the parent, else the defaults', () => {
        const parent = (autoPublish?: string) => ({ status: 'RESOLVED', source: 'ORGANIZATION', sourceObject: null,
            profile: { ...teaBuiltInDefaults(), ...(autoPublish ? { autoPublish } : {}) } })
        const row = (autoPublish: string, mode = 'OVERRIDE') => ({ ...teaBuiltInDefaults(), uuid: 'r', mode, autoPublish })
        expect(teaFormFromView({ stored: row('DISABLED'), parent: parent('AT_SHIPPED') }, 'COMPONENT').autoPublish).toBe('DISABLED')
        expect(teaFormFromView({ stored: row('AT_SHIPPED'), parent: parent('DISABLED') }, 'COMPONENT').autoPublish).toBe('AT_SHIPPED')
        expect(teaFormFromView({ stored: null, parent: parent('DISABLED') }, 'COMPONENT').autoPublish).toBe('DISABLED')
        expect(teaFormFromView({ stored: null, parent: { status: 'RESOLVED', profile: null } }, 'COMPONENT').autoPublish).toBe('AT_SHIPPED')
        expect(teaFormFromView({ stored: { uuid: 'f', mode: 'FOLLOW_PERSPECTIVE', followedPerspective: 'p1' }, parent: parent('DISABLED') },
            'COMPONENT').autoPublish).toBe('DISABLED')
        expect(teaInheritedForm({ parent: parent('DISABLED') }).autoPublish).toBe('DISABLED')
    })

    it('(c) teaProfileInput sends autoPublish only with additive true and a mode that stores fields', () => {
        const form = (autoPublish: string) => ({ ...teaBuiltInDefaults(), mode: 'OVERRIDE', publishing: 'ENABLED', autoPublish })
        expect(teaProfileInput(form('DISABLED'), 'COMPONENT', 'SAAS', true)).toEqual(INPUT('DISABLED'))
        expect(teaProfileInput(form('AT_SHIPPED'), 'COMPONENT', 'SAAS', true)).toEqual(INPUT('AT_SHIPPED'))
        const without = teaProfileInput(form('AT_SHIPPED'), 'COMPONENT', 'SAAS', false)
        expect(without).toEqual(INPUT())
        expect('autoPublish' in without).toBe(false)
        const leftOut = teaProfileInput(form('AT_SHIPPED'), 'COMPONENT', 'SAAS')
        expect(leftOut).toEqual(INPUT())
        expect('autoPublish' in leftOut).toBe(false)
        expect(teaProfileInput({ ...form('AT_SHIPPED'), mode: 'FOLLOW_PERSPECTIVE', followedPerspective: 'p1' }, 'COMPONENT', 'SAAS', true))
            .toEqual({ mode: 'FOLLOW_PERSPECTIVE', followedPerspective: 'p1' })
        const org = teaProfileInput({ ...form('AT_SHIPPED'), mode: null }, 'ORGANIZATION', 'SAAS', true)
        expect(org.autoPublish).toBe('AT_SHIPPED')
        expect('mode' in org).toBe(false)
    })

    it('(d) the help, with the CE suffix on OSS only, and the package profile help', () => {
        expect(TEA_FIELD_HELP.autoPublish).toBe(HELP)
        expect(teaAutoPublishHelp('SAAS')).toBe(HELP)
        expect(teaAutoPublishHelp('OSS')).toBe(HELP + ' Takes effect on ReARM Pro.')
        expect(teaAutoPublishHelp(undefined)).toBe(HELP)
        expect(TEA_FIELD_HELP.packageProfile).toBe("A device with a regulatory package also publishes under its package's "
            + 'SBOM profile, so customers can pin the serial the package carries.')
    })

    it('(e) teaPublicConfirmText counts versions where the backend serves them, today\'s words elsewhere', () => {
        expect(teaPublicConfirmText(3, 7)).toBe(COUNTED(3, 7))
        expect(teaPublicConfirmText(1, 1)).toBe(COUNTED(1, 1))
        for (const m of [undefined, null, 0]) expect(teaPublicConfirmText(3, m as any), String(m)).toBe(TODAY_FOR(3))
        expect(teaPublicConfirmText(0, 0)).toBe(NONE_YET)
        expect(teaPublicConfirmText(0, 5)).toBe(NONE_YET)
        expect(TEA_PUBLIC_CONFIRM_NONE_YET).toBe(NONE_YET)
        expect(NONE_YET.endsWith(TEA_DOCUMENT_KINDS + '.')).toBe(true)
        expect(teaPublicConfirmText(0, undefined)).toBe(TODAY_NONE)
        expect(teaPublicConfirmText(0, null)).toBe(TODAY_NONE)
    })

    it('(f) the save, removal and membership confirmations end with the counted literal', () => {
        const x = exposureOf({ before: 'PRIVATE', after: 'PUBLIC', path: 'ROW_PUBLIC', publishedReleases: 3, publishedVersions: 7 })
        expect(teaConfirmBeforeSave(x, 'component')!.text.endsWith(COUNTED(3, 7))).toBe(true)
        expect(teaConfirmBeforeRemoval({ ...x, path: 'REMOVAL_RESOLVES_PUBLIC', afterSource: 'ORGANIZATION' }, 'component')
            .text.endsWith(COUNTED(3, 7))).toBe(true)
        expect(teaConfirmBeforeMembership({ ...x, path: 'MEMBERSHIP_RESOLVES_PUBLIC', afterSource: 'ORGANIZATION' }, ['Payments'], [])!
            .text.endsWith(COUNTED(3, 7))).toBe(true)
    })

    it('(g, the settings half) TEA_DOCUMENT_KINDS equals its literal and each counted and none-yet text carries it once', () => {
        expect(TEA_DOCUMENT_KINDS).toBe('(SBOMs, VEX documents, device support statements, published artifacts)')
        expect(occurrences(TEA_PUBLIC_CONFIRM_COUNTED(3, 7), TEA_DOCUMENT_KINDS)).toBe(1)
        expect(occurrences(TEA_PUBLIC_CONFIRM_NONE_YET, TEA_DOCUMENT_KINDS)).toBe(1)
        expect(TEA_PUBLIC_CONFIRM_COUNTED(3, 7)).toBe(COUNTED(3, 7))
    })
})

describe('FDAW-39 row 36: the paused banner, the turn-on confirmation and the start sentences', () => {
    const resolvedP = (state: string | undefined, source = 'ORGANIZATION', sourceObject: string | null = null) =>
        ({ status: 'RESOLVED', source, sourceObject, profile: { ...teaBuiltInDefaults(), ...(state ? { autoPublishState: state } : {}) } })
    const storedRow = (changes: Record<string, any>) => ({ ...teaBuiltInDefaults(), uuid: 'row-1', mode: 'OVERRIDE', ...changes })

    it('(a) teaShownAutoPublishState and teaAutoPublishBanner', () => {
        let v: any = { stored: storedRow({ autoPublishState: 'NEEDS_CONFIRMATION' }), effective: resolvedP('ARMED') }
        expect(teaShownAutoPublishState(v)).toBe('NEEDS_CONFIRMATION')
        expect(teaAutoPublishBanner(v, 'component')).toBe(PAUSED_OWN('component'))
        expect(teaAutoPublishBanner(v, 'product')).toBe(PAUSED_OWN('product'))
        v = { stored: storedRow({ autoPublishState: 'ARMED' }), effective: resolvedP('NEEDS_CONFIRMATION') }
        expect(teaAutoPublishBanner(v, 'component')).toBeNull()
        expect(teaAutoPublishBanner({ stored: storedRow({ autoPublishState: 'OFF' }), effective: resolvedP('OFF') }, 'component')).toBeNull()
        v = { stored: null, effective: resolvedP('NEEDS_CONFIRMATION') }
        expect(teaAutoPublishBanner(v, 'component')).toBe(PAUSED_INHERITED('organization', 'component'))
        v = { stored: { uuid: 'f', mode: 'FOLLOW_PERSPECTIVE', followedPerspective: 'p1', autoPublishState: 'OFF' },
            effective: resolvedP('NEEDS_CONFIRMATION', 'FOLLOWED_PERSPECTIVE', 'p1') }
        expect(teaAutoPublishBanner(v, 'component', { p1: 'Payments' })).toBe(PAUSED_INHERITED('perspective Payments', 'component'))
        expect(teaAutoPublishBanner({ stored: null, effective: resolvedP('ARMED') }, 'component')).toBeNull()
        const noState = { stored: storedRow({}), effective: resolvedP(undefined) }
        expect(teaShownAutoPublishState(noState)).toBeUndefined()
        expect(teaAutoPublishBanner(noState, 'component')).toBeNull()
    })

    it('(b) teaWouldStartAutoPublish: publishing on with AT_SHIPPED on a row that does not fire yet', () => {
        const v = (state: string) => ({ stored: storedRow({ publishing: 'DISABLED', autoPublish: 'AT_SHIPPED', autoPublishState: state }) })
        expect(teaWouldStartAutoPublish(v('OFF'), INPUT('AT_SHIPPED'))).toBe(true)
        expect(teaWouldStartAutoPublish(v('ARMED'), INPUT('AT_SHIPPED'))).toBe(false)
        expect(teaWouldStartAutoPublish(v('NEEDS_CONFIRMATION'), INPUT('AT_SHIPPED'))).toBe(true)
        expect(teaWouldStartAutoPublish(v('OFF'), INPUT('DISABLED'))).toBe(false)
        expect(teaWouldStartAutoPublish(v('OFF'), { ...INPUT('AT_SHIPPED'), publishing: 'DISABLED' })).toBe(false)
        expect(teaWouldStartAutoPublish(v('OFF'), INPUT())).toBe(false)
        expect(teaWouldStartAutoPublish({ stored: null, effective: resolvedP('ARMED') }, INPUT('AT_SHIPPED'))).toBe(false)
        expect(teaWouldStartAutoPublish({ stored: null, effective: resolvedP('OFF') }, INPUT('AT_SHIPPED'))).toBe(true)
    })

    it('(c) teaTurnOnConfirm: OVERRIDE on teaWouldStartAutoPublish, FOLLOW on the dry-run only', () => {
        const off = { stored: storedRow({ publishing: 'DISABLED', autoPublishState: 'OFF' }) }
        expect(TEA_TURN_ON_TEXT).toBe(TURN_ON)
        expect(teaTurnOnConfirm(off, INPUT('AT_SHIPPED'), exposureOf(), 'component'))
            .toEqual({ title: 'Turn on publish at shipped?', text: TURN_ON, confirmButtonText: 'Turn on' })
        expect(teaTurnOnConfirm(off, INPUT('DISABLED'), exposureOf({ autoPublishStarts: true }), 'component')).toBeNull()
        const follow = { mode: 'FOLLOW_PERSPECTIVE', followedPerspective: 'p1' }
        const starts = exposureOf({ autoPublishStarts: true, afterSource: 'PERSPECTIVE', afterSourceObject: 'p1' })
        expect(teaTurnOnConfirm(off, follow, starts, 'component', { p1: 'Payments' })).toEqual({
            title: 'Turn on publish at shipped?',
            text: 'Following perspective Payments turns on publish at shipped for this component through the TEA profile of '
                + 'perspective Payments. ' + TURN_ON,
            confirmButtonText: 'Turn on',
        })
        expect(teaTurnOnConfirm(off, follow, { ...starts, autoPublishStarts: false }, 'component', { p1: 'Payments' })).toBeNull()
        const absent: any = { ...starts }
        delete absent.autoPublishStarts
        expect(teaTurnOnConfirm(off, follow, absent, 'component', { p1: 'Payments' })).toBeNull()
    })

    it('(d) teaStartsAutoPublish on the object flag or a member list', () => {
        const member = { component: 'c', name: 'n' }
        expect(teaStartsAutoPublish(exposureOf({ autoPublishStarts: true, autoPublishStartsFor: [] }))).toBe(true)
        expect(teaStartsAutoPublish(exposureOf({ autoPublishStarts: false, autoPublishStartsFor: [member] }))).toBe(true)
        expect(teaStartsAutoPublish(exposureOf({ autoPublishStarts: false, autoPublishStartsFor: [] }))).toBe(false)
        expect(teaStartsAutoPublish(exposureOf())).toBe(false)
        expect(teaStartsAutoPublish(null)).toBe(false)
    })

    it('(e) teaConfirmBeforeRemoval says when the removal starts publish at shipped', () => {
        expect(teaConfirmBeforeRemoval(exposureOf({ autoPublishStarts: true, afterSource: 'ORGANIZATION' }), 'component')).toEqual({
            title: 'Remove this TEA profile override?',
            text: 'The component then resolves its TEA profile from its parent again. Removing this override turns on '
                + 'publish at shipped for this component through the TEA profile of organization; its releases at Shipped '
                + 'or later are published the next time they change.',
            confirmButtonText: 'Remove',
        })
        const members = (names: string[]) => names.map(n => ({ component: 'c-' + n, name: n }))
        expect(teaConfirmBeforeRemoval(exposureOf({ autoPublishStartsFor: members(['zeta', 'alpha']) }), 'perspective').text
            .endsWith('Removing this override turns on publish at shipped for 2 component(s) of this perspective: zeta, alpha; '
                + 'their releases at Shipped or later are published the next time they change.')).toBe(true)
        expect(teaConfirmBeforeRemoval(exposureOf({ autoPublishStartsFor: members(['m1', 'm2', 'm3', 'm4', 'm5', 'm6']) }), 'perspective')
            .text).toContain('for 6 component(s) of this perspective: m1, m2, m3, m4, m5, and 1 more;')
        // Exactly LISTED_NAMES (5): every name is listed, with no "and <n> more" suffix (the boundary of listedNames).
        expect(teaConfirmBeforeRemoval(exposureOf({ autoPublishStartsFor: members(['m1', 'm2', 'm3', 'm4', 'm5']) }), 'perspective')
            .text).toContain('for 5 component(s) of this perspective: m1, m2, m3, m4, m5;')
        const pub = exposureOf({ before: 'PRIVATE', after: 'PUBLIC', path: 'REMOVAL_RESOLVES_PUBLIC', afterSource: 'ORGANIZATION', publishedReleases: 2 })
        const today = teaConfirmBeforeRemoval(pub, 'component')
        const both = teaConfirmBeforeRemoval({ ...pub, autoPublishStarts: true }, 'component')
        expect(both.title).toBe(today.title)
        expect(both.text).toBe(today.text + ' Removing this override turns on publish at shipped for this component through '
            + 'the TEA profile of organization; its releases at Shipped or later are published the next time they change.')
        expect(teaConfirmBeforeRemoval(exposureOf(), 'component')).toEqual({ title: 'Remove this TEA profile override?',
            text: 'The component then resolves its TEA profile from its parent again.', confirmButtonText: 'Remove' })
    })

    it('(f) teaConfirmBeforeMembership asks for a start, a public change, or both', () => {
        expect(teaConfirmBeforeMembership(exposureOf(), ['Payments'], [])).toBeNull()
        const start = exposureOf({ autoPublishStarts: true, afterSource: 'PERSPECTIVE', afterSourceObject: 'p1' })
        expect(teaConfirmBeforeMembership(start, ['Payments'], [], { p1: 'Payments' })).toEqual({
            title: 'Turn on publish at shipped?',
            text: "Changing this component's perspectives (adding Payments) turns on publish at shipped for it through the TEA "
                + 'profile of perspective Payments; its releases at Shipped or later are published the next time they change.',
            confirmButtonText: 'Change perspectives',
        })
        const pub = exposureOf({ before: 'PRIVATE', after: 'PUBLIC', path: 'MEMBERSHIP_RESOLVES_PUBLIC', afterSource: 'PERSPECTIVE',
            afterSourceObject: 'p1', publishedReleases: 1 })
        const today = teaConfirmBeforeMembership(pub, ['Payments'], [], { p1: 'Payments' })!
        const both = teaConfirmBeforeMembership({ ...pub, autoPublishStarts: true }, ['Payments'], [], { p1: 'Payments' })!
        expect(both.title).toBe('Make this component public?')
        expect(both.text).toBe(today.text + " Changing this component's perspectives (adding Payments) turns on publish at "
            + 'shipped for it through the TEA profile of perspective Payments; its releases at Shipped or later are published '
            + 'the next time they change.')
        expect(teaConfirmBeforeMembership({ ...pub, autoPublishStarts: false }, ['Payments'], [], { p1: 'Payments' })).toEqual(today)
    })
})
