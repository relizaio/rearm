// TEA profiles (task TEA-2): the editor's option lists and its pure logic -- what the form starts
// from, what a save sends, and the copy of the source line, the banner and the PUBLIC confirmation.
import { describe, expect, it } from 'vitest'
import {
    TEA_DEPENDENCY_DEPTH_OPTIONS, TEA_EXCLUDED_COVERAGE_OPTIONS, TEA_INTERNAL_METADATA_OPTIONS, TEA_MINIMUM_LIFECYCLE_OPTIONS,
    TEA_MODE_OPTIONS, TEA_OPTIONAL_DEPENDENCIES_OPTIONS, TEA_PRODUCT_COMPONENTS_OPTIONS, TEA_PROFILE_FIELDS,
    TEA_PUBLISHING_OPTIONS, TEA_RAW_ARTIFACTS_OPTIONS, TEA_SOURCES_OPTIONS, TEA_STRUCTURE_OPTIONS,
    TEA_SUPPORT_METADATA_OPTIONS, TEA_TEI_OPTIONS, TEA_VISIBILITY_OPTIONS, teaBuiltInDefaults, teaFormFromView,
    teaNeedsPublicConfirm, teaProfileInput, teaPublicBanner, teaPublicConfirmText, teaSourceLine,
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

    it('states how many published releases a PUBLIC save exposes, and handles none', () => {
        expect(teaPublicConfirmText(3)).toContain('3 published release(s)')
        expect(teaPublicConfirmText(0)).toBe('No release is published under this profile yet; every future publication under it will be public.')
    })

    it('asks only when the save makes the row PUBLIC', () => {
        const pub = { ...teaBuiltInDefaults(), visibility: 'PUBLIC' }
        expect(teaNeedsPublicConfirm(pub, 'ORGANIZATION', { stored: null })).toBe(true)
        expect(teaNeedsPublicConfirm(pub, 'ORGANIZATION', { stored: { visibility: 'PUBLIC' } })).toBe(false)
        expect(teaNeedsPublicConfirm({ ...pub, mode: 'FOLLOW_PERSPECTIVE' }, 'COMPONENT', { stored: null })).toBe(false)
    })
})
