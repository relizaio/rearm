// TEA profiles (task TEA-2): the editor's option lists and its pure logic -- what the form starts
// from, what a save sends, and the copy of the source line, the banner and the PUBLIC confirmation.
import { describe, expect, it } from 'vitest'
import {
    TEA_DEPENDENCY_DEPTH_OPTIONS, TEA_EXCLUDED_COVERAGE_OPTIONS, TEA_INTERNAL_METADATA_OPTIONS, TEA_MINIMUM_LIFECYCLE_OPTIONS,
    TEA_MODE_OPTIONS, TEA_OPTIONAL_DEPENDENCIES_OPTIONS, TEA_PRODUCT_COMPONENTS_OPTIONS, TEA_PROFILE_FIELDS,
    TEA_PUBLISHING_OPTIONS, TEA_RAW_ARTIFACTS_OPTIONS, TEA_SOURCES_OPTIONS, TEA_STRUCTURE_OPTIONS,
    TEA_SUPPORT_METADATA_OPTIONS, TEA_TEI_OPTIONS, TEA_VISIBILITY_OPTIONS, teaBuiltInDefaults, teaFormFromView,
    teaConfirmBeforeMembership, teaConfirmBeforeRemoval, teaConfirmBeforeSave, TeaExposureChange, teaMembershipDelta,
    teaNeedsPublicConfirm, teaProfileInput,
    teaPublicBanner, teaPublicConfirmText, teaRowObject, teaSourceLine,
} from './teaProfile'

const values = (opts: { label: string, value: string }[]) => opts.map(o => o.value)

describe('TEA profile option lists', () => {
    it('every list is {label, value} pairs carrying exactly the schema enum values', () => {
        const lists: [any[], string[]][] = [
            [TEA_MODE_OPTIONS, ['OVERRIDE', 'FOLLOW_PERSPECTIVE']],
            [TEA_PUBLISHING_OPTIONS, ['ENABLED', 'DISABLED']],
            [TEA_VISIBILITY_OPTIONS, ['PRIVATE', 'PUBLIC']],
            [TEA_DEPENDENCY_DEPTH_OPTIONS, ['FULL', 'TOP_LEVEL_ONLY']],
            [TEA_OPTIONAL_DEPENDENCIES_OPTIONS, ['INCLUDE', 'EXCLUDE']],
            [TEA_STRUCTURE_OPTIONS, ['FLAT', 'HIERARCHICAL']],
            [TEA_SOURCES_OPTIONS, ['DELIVERABLE', 'RELEASE', 'SOURCE_CODE']],
            [TEA_EXCLUDED_COVERAGE_OPTIONS, ['DEV', 'TEST', 'BUILD_TIME']],
            [TEA_SUPPORT_METADATA_OPTIONS, ['INCLUDE', 'EXCLUDE']],
            [TEA_INTERNAL_METADATA_OPTIONS, ['INCLUDE', 'EXCLUDE']],
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

    it('offers only the minimum lifecycles the server accepts', () => {
        expect(values(TEA_MINIMUM_LIFECYCLE_OPTIONS)).toEqual(['ASSEMBLED', 'READY_TO_SHIP', 'GENERAL_AVAILABILITY'])
    })
})

const PARENT = { ...teaBuiltInDefaults(), publishing: 'ENABLED', dependencyDepth: 'TOP_LEVEL_ONLY', uuid: 'org-row' }

describe('the form and the save', () => {
    it('starts from the stored row, else the parent, else the defaults', () => {
        const stored = { ...teaBuiltInDefaults(), mode: 'OVERRIDE', visibility: 'PUBLIC' }
        expect(teaFormFromView({ stored, parent: { profile: PARENT } }, 'COMPONENT').visibility).toBe('PUBLIC')
        const fromParent = teaFormFromView({ stored: null, parent: { status: 'RESOLVED', profile: PARENT } }, 'COMPONENT')
        expect(fromParent.dependencyDepth).toBe('TOP_LEVEL_ONLY')
        expect(fromParent.mode).toBe('OVERRIDE')
        const conflict = teaFormFromView({ stored: null, parent: { status: 'CONFLICT', profile: null } }, 'COMPONENT')
        expect(conflict.publishing).toBe('DISABLED')
        expect(teaFormFromView({ stored: null, parent: { profile: PARENT } }, 'ORGANIZATION').mode).toBeNull()
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
