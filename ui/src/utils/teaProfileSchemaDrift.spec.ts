import { describe, it, expect, vi } from 'vitest'
// notificationsCommon and the submission helpers reach the GraphQL client and keycloak through
// commonFunctions and fetchClient as an import side effect; this spec reads only their data.
vi.mock('@/utils/commonFunctions', () => ({ default: {} }))
vi.mock('./fetchClient', () => ({ fetchWithAuth: vi.fn(), fetchArrayBufferWithAuth: vi.fn() }))
import { validate } from 'graphql'
import graphqlQueries from './graphqlQueries'
import { editionPermissionFunctions, TEA_PERMISSION_FUNCTIONS, teaProfilesAvailable } from './teaProfile'
import { TEA_RIDE_ALONG_SKIP_LABELS, TEA_RIDE_ALONG_STATUSES } from './teaPublication'
import constants from './constants'
import { ceSchema, enumValuesOf, proSchema } from './schemaDriftSupport'
import { outputEventTypeOptionsForEdition, TEA_OUTPUT_EVENT_TYPE_OPTIONS } from './teaOutputEvents'
import profilesQueries from './profilesQueries'
import submissionQueries from './submissionQueries'
import { PACKAGE_SELECTION_OPTIONS, PACKAGE_VEX_FORMAT_OPTIONS, regulatoryPackagesAvailable } from './packageSettings'
import {
    PART_SKIP_REASON_LABELS, SUBMISSION_ACT_LABELS, SUBMISSION_PART_OUTCOME_LABELS, SUBMISSION_STATE_LABELS,
    submissionsAvailable,
} from './submissions'
import { eventTypeOptions } from './notificationsCommon'

/**
 * The TEA profile documents (task TEA-2) against both schemas, and the edition gate that keeps a
 * CE UI from sending them.
 *
 * The operator decided on 2026-10-03 that the 2026-10 TEA work ships no CE backend sync, so the
 * CE schema has none of the TEA profile fields and every one of these documents fails there as a
 * whole. teaProfilesAvailable hides the TEA surfaces on CE for that reason. The canary below fails
 * as soon as the CE schema gains the fields: that is the moment to lift the gate, or the CE UI
 * keeps hiding a feature its backend serves.
 */

const DOCS = {
    TeaProfileEditorViewGql: graphqlQueries.TeaProfileEditorViewGql,
    TeaProfilesOfOrgGql: graphqlQueries.TeaProfilesOfOrgGql,
    TeaOrgDiscoveryGql: graphqlQueries.TeaOrgDiscoveryGql,
    SaveTeaProfileGql: graphqlQueries.SaveTeaProfileGql,
    DeleteTeaProfileGql: graphqlQueries.DeleteTeaProfileGql,
    TeaProfileExposureChangeGql: graphqlQueries.TeaProfileExposureChangeGql,
    // The membership dry-run (TEA-9 round 2): what setPerspectivesOnComponent would do to the visibility.
    TeaMembershipExposureChangeGql: graphqlQueries.TeaMembershipExposureChangeGql,
}

// The release page's TEA publication documents (task TEA-5): the same Pro-only surface, behind the
// same gate (ReleaseView.loadTeaPublication checks teaProfilesAvailable before it sends any).
const PUBLICATION_DOCS = {
    ReleaseTeaPublicationViewGql: graphqlQueries.ReleaseTeaPublicationViewGql,
    PublishReleaseOnTeaGql: graphqlQueries.PublishReleaseOnTeaGql,
    RepublishReleaseOnTeaGql: graphqlQueries.RepublishReleaseOnTeaGql,
    HideReleaseOnTeaGql: graphqlQueries.HideReleaseOnTeaGql,
}

// The org TEA table's name read of an archived component (TEA-9) selects only long-standing
// Component fields, so it validates on CE too; it is sent only from the TEA tab, which the gate hides.
const COMPONENT_NAME_READ = graphqlQueries.TeaComponentNameGql
// The members dialog's stored-perspectives read (TEA-16): long-standing fields only, sent only behind the gate.
const COMPONENT_PERSPECTIVES_READ = graphqlQueries.TeaComponentPerspectivesGql

const ceServesTea = () => Boolean(ceSchema.getQueryType()?.getFields().teaProfileEditorView)

describe('TEA profile documents and the edition gate', () => {
    it('shows the TEA surfaces on CE exactly when the CE schema serves TEA profiles', () => {
        expect(teaProfilesAvailable('OSS')).toBe(ceServesTea())
    })

    it('shows them on every licensed edition, and on an unloaded user as the rest of the UI does', () => {
        for (const t of ['SAAS', 'DEMO', 'MANAGED_SERVICE', undefined, null]) {
            expect(teaProfilesAvailable(t)).toBe(true)
        }
    })

    it('the CE schema answers either every TEA document or none, so the one gate covers them all', () => {
        const valid = Object.values(DOCS).map(d => validate(ceSchema, d).length === 0)
        expect(valid.every(v => v === ceServesTea())).toBe(true)
    })

    it('lists the seven TEA-profile documents beside the two component reads: nine TEA documents', () => {
        expect(Object.keys(DOCS)).toHaveLength(7)
        expect(DOCS.TeaMembershipExposureChangeGql).toBeDefined()
    })

    it('the membership dry-run is one of the documents the CE schema does not serve', () => {
        expect(validate(ceSchema, DOCS.TeaMembershipExposureChangeGql).length > 0).toBe(!ceServesTea())
    })

    it.runIf(proSchema)('every TEA document validates against the Pro schema', () => {
        for (const [name, doc] of Object.entries({ ...DOCS, TeaComponentNameGql: COMPONENT_NAME_READ, TeaComponentPerspectivesGql: COMPONENT_PERSPECTIVES_READ })) {
            expect(validate(proSchema!, doc).map(e => e.message), name).toEqual([])
        }
    })

    it('the component reads of the org TEA table and the members dialog validate on CE as well', () => {
        expect(validate(ceSchema, COMPONENT_NAME_READ).map(e => e.message)).toEqual([])
        expect(validate(ceSchema, COMPONENT_PERSPECTIVES_READ).map(e => e.message)).toEqual([])
    })
})

/**
 * The EXTERNAL key documents (task TEA-3) and the TEA permission functions, under the same gate: the
 * External Keys pane shows only where teaProfilesAvailable, reads these documents itself, and the
 * editors offer PUBLISH_EXTERNALLY only there. The shared apiKeys document of OrgSettings names none
 * of the new fields (teaSurfacesContract.spec.ts), so the CE Programmatic Access tab keeps loading.
 */
const EXTERNAL_KEY_DOCS = {
    ExternalApiKeysGql: graphqlQueries.ExternalApiKeysGql,
    CreateExternalApiKeyGql: graphqlQueries.CreateExternalApiKeyGql,
    SetApiKeyHolderNameGql: graphqlQueries.SetApiKeyHolderNameGql,
}

describe('EXTERNAL key documents, the TEA functions and the edition gate', () => {
    it('the CE schema answers none of the EXTERNAL key documents while it serves no TEA', () => {
        for (const [name, doc] of Object.entries(EXTERNAL_KEY_DOCS)) {
            expect(validate(ceSchema, doc).length === 0, name).toBe(ceServesTea())
        }
    })

    it.runIf(proSchema)('every EXTERNAL key document validates against the Pro schema', () => {
        for (const [name, doc] of Object.entries(EXTERNAL_KEY_DOCS)) {
            expect(validate(proSchema!, doc).map(e => e.message), name).toEqual([])
        }
    })

    it('the CE schema has the TEA permission functions exactly when it serves TEA, so the editors offer them only then', () => {
        const ce = enumValuesOf(ceSchema, 'PermissionFunction')
        for (const f of TEA_PERMISSION_FUNCTIONS) expect(ce.includes(f), f).toBe(ceServesTea())
        expect(editionPermissionFunctions(constants.PermissionFunctions, 'OSS').some(f => TEA_PERMISSION_FUNCTIONS.includes(f)))
            .toBe(ceServesTea())
    })

    it('a licensed edition offers every function, the TEA ones included', () => {
        for (const t of ['SAAS', 'DEMO', 'MANAGED_SERVICE', undefined, null]) {
            expect(editionPermissionFunctions(constants.PermissionFunctions, t)).toEqual(constants.PermissionFunctions)
        }
    })

    it.runIf(proSchema)('the Pro schema has every function the editors offer, TEA_READ, and the EXTERNAL key type', () => {
        const pro = enumValuesOf(proSchema!, 'PermissionFunction')
        for (const f of [...constants.PermissionFunctions, 'TEA_READ']) expect(pro, f).toContain(f)
        expect(enumValuesOf(proSchema!, 'ApiTypeEnum')).toContain('EXTERNAL')
    })
})

// The view's `publishing` switch (the release page's TEA tab) arrives on CE with the next sync of the
// mirror; until then ReleaseView.loadTeaPublication falls back to the CORE view, which leaves it out.
describe('TEA publication documents (TEA-5) and the edition gate', () => {
    it('none of the four validates against the CE schema while CE serves no TEA, so the gate must hold them back', () => {
        expect(Object.keys(PUBLICATION_DOCS)).toHaveLength(4)
        for (const [name, doc] of Object.entries(PUBLICATION_DOCS)) {
            expect(validate(ceSchema, doc).length === 0, name).toBe(ceServesTea())
        }
    })

    it('CE serves TeaPublishPreview.publishing wherever it serves TEA, so the publication view has no CORE fallback', () => {
        const t: any = ceSchema.getType('TeaPublishPreview')
        expect(Boolean(t?.getFields?.().publishing)).toBe(ceServesTea())
        expect(graphqlQueries.ReleaseTeaPublicationViewGql.loc!.source.body).toMatch(/\bpublishing\b/)
        expect((graphqlQueries as any).ReleaseTeaPublicationViewCoreGql).toBeUndefined()
    })

    it.runIf(proSchema)('every TEA publication document validates against the Pro schema', () => {
        for (const [name, doc] of Object.entries(PUBLICATION_DOCS)) {
            expect(validate(proSchema!, doc).map(e => e.message), name).toEqual([])
        }
    })

    it.runIf(proSchema)('the ride-along statuses equal the Pro TeaRideAlongStatus, and every one but PUBLISHED has a summary label', () => {
        const pro = enumValuesOf(proSchema!, 'TeaRideAlongStatus')
        expect([...TEA_RIDE_ALONG_STATUSES].sort()).toEqual([...pro].sort())
        expect(Object.keys(TEA_RIDE_ALONG_SKIP_LABELS).sort()).toEqual(pro.filter(v => v !== 'PUBLISHED').sort())
    })
})

/**
 * The TEA output event types (task TEA-13): the option list names only values the Pro schema's
 * EventType declares, and the CE schema gains them exactly when CE serves TEA, the moment to lift the
 * edition filter of outputEventTypeOptionsForEdition.
 */
describe('TEA output event types (TEA-13) and the edition filter', () => {
    it.runIf(proSchema)('row 15: the Pro EventType enum has every TEA option value', () => {
        const pro = enumValuesOf(proSchema!, 'EventType')
        for (const o of TEA_OUTPUT_EVENT_TYPE_OPTIONS) expect(pro, o.value).toContain(o.value)
    })

    it('row 16: the CE EventType enum has a TEA value exactly when CE serves TEA, and the CE select never offers one', () => {
        const ce = enumValuesOf(ceSchema, 'EventType')
        for (const o of TEA_OUTPUT_EVENT_TYPE_OPTIONS) expect(ce.includes(o.value), o.value).toBe(ceServesTea())
        // The values arrive with the shared schema (SCORE-72 sync), but nothing fires them on CE and the
        // CE backend refuses a component save carrying one (SCORE-72 design 3.3.3): the select offers neither.
        const offered = outputEventTypeOptionsForEdition([], 'OSS').map(o => o.value)
        expect(TEA_OUTPUT_EVENT_TYPE_OPTIONS.some(o => offered.includes(o.value))).toBe(false)
    })
})

/**
 * Named SBOM profiles, package settings and submissions (task TEA-30, design 4.5 rows 23 to 25):
 * Pro-only documents in profilesQueries.ts and submissionQueries.ts, each group behind its gate and
 * tied to the CE schema by a canary, so a CE sync that brings one surface flips exactly its gate.
 */
const PROFILE_DOCS = {
    SbomProfilesGql: profilesQueries.SbomProfilesGql,
    SbomProfilesOfOrgGql: profilesQueries.SbomProfilesOfOrgGql,
    SbomProfileCatalogGql: profilesQueries.SbomProfileCatalogGql,
    SbomProfileEditorViewGql: profilesQueries.SbomProfileEditorViewGql,
    SaveSbomProfileGql: profilesQueries.SaveSbomProfileGql,
    DeleteSbomProfileGql: profilesQueries.DeleteSbomProfileGql,
}
const PACKAGE_DOCS = {
    PackageKindsGql: profilesQueries.PackageKindsGql,
    PackageSettingsOfScopeGql: profilesQueries.PackageSettingsOfScopeGql,
    PackageSettingsOfOrgGql: profilesQueries.PackageSettingsOfOrgGql,
    PackageSettingsEditorViewGql: profilesQueries.PackageSettingsEditorViewGql,
    SavePackageSettingsGql: profilesQueries.SavePackageSettingsGql,
    DeletePackageSettingsGql: profilesQueries.DeletePackageSettingsGql,
}
const SUBMISSION_DOCS = {
    SubmissionsOfComponentGql: submissionQueries.SubmissionsOfComponentGql,
    SubmissionsOfReleaseGql: submissionQueries.SubmissionsOfReleaseGql,
    SubmissionPreviewGql: submissionQueries.SubmissionPreviewGql,
    CreateSubmissionGql: submissionQueries.CreateSubmissionGql,
    ResubmitSubmissionGql: submissionQueries.ResubmitSubmissionGql,
    WithdrawSubmissionGql: submissionQueries.WithdrawSubmissionGql,
}
const RELEASES_OF_BRANCH = submissionQueries.SubmissionReleasesOfBranchGql
// The release page's offered kinds (Release.regulatoryPackageKinds): Pro-only, sent behind submissionsAvailable.
const RELEASE_KINDS = submissionQueries.ReleaseRegulatoryPackageKindsGql

const ceServesSbomProfiles = () => Boolean(ceSchema.getQueryType()?.getFields().sbomProfileCatalog)
const ceServesPackageSettings = () => Boolean(ceSchema.getQueryType()?.getFields().packageKinds)
const ceServesSubmissions = () => Boolean(ceSchema.getQueryType()?.getFields().submissionsOfComponent)
// The Enabled switch (PackageSettings.enabled) and Release.regulatoryPackageKinds arrive on CE with
// the next sync of the mirror; the documents that select them are sent on Pro only.
const ceFieldOf = (type: string, field: string) => {
    const t: any = ceSchema.getType(type)
    return Boolean(t?.getFields?.()[field])
}
const ceHasPackageEnabled = () => ceFieldOf('PackageSettings', 'enabled')
const ceHasReleaseKinds = () => ceFieldOf('Release', 'regulatoryPackageKinds')

describe('SBOM profile, package settings and submission documents (TEA-30) and their gates', () => {
    it('row 23: the TEA editor documents select the channel fields and profiles only (no recipe field on TeaProfile)', () => {
        const editorView = graphqlQueries.TeaProfileEditorViewGql.loc!.source.body
        for (const gone of ['dependencyDepth', 'optionalDependencies', 'excludedCoverage', 'supportMetadata',
            'internalMetadata', 'vulnerabilityDocuments']) expect(editorView, gone).not.toContain(gone)
        expect(editorView).toContain('profiles')
    })

    const GROUPS: [string, Record<string, any>, () => boolean][] = [
        ['profiles', PROFILE_DOCS, ceServesSbomProfiles],
        // Every package settings document selects PackageSettings.enabled except PackageKinds and the delete.
        ['package settings', PACKAGE_DOCS, () => ceServesPackageSettings() && ceHasPackageEnabled()],
        ['submissions', SUBMISSION_DOCS, ceServesSubmissions],
    ]

    it('row 24: six documents per group', () => {
        for (const [group, docs] of GROUPS) expect(Object.keys(docs), group).toHaveLength(6)
    })

    it.runIf(proSchema)('row 24: every document of the three groups validates against the Pro schema', () => {
        for (const [, docs] of GROUPS) {
            for (const [name, doc] of Object.entries(docs)) expect(validate(proSchema!, doc).map(e => e.message), name).toEqual([])
        }
        expect(validate(proSchema!, RELEASES_OF_BRANCH).map(e => e.message)).toEqual([])
    })

    it('row 24: on CE each document validates exactly when its canary says the CE schema serves the surface', () => {
        for (const [group, docs, canary] of GROUPS) {
            for (const [name, doc] of Object.entries(docs)) {
                const served = name === 'PackageKindsGql' || name === 'DeletePackageSettingsGql' ? ceServesPackageSettings() : canary()
                expect(validate(ceSchema, doc).length === 0, group + ' ' + name).toBe(served)
            }
        }
    })

    it('the package settings documents select enabled; the offered-kinds read validates on CE exactly when CE has the field', () => {
        for (const [name, doc] of Object.entries(PACKAGE_DOCS)) {
            if (name === 'PackageKindsGql' || name === 'DeletePackageSettingsGql') continue
            expect(doc.loc!.source.body, name).toMatch(/\benabled\b/)
        }
        expect(validate(ceSchema, RELEASE_KINDS).length === 0).toBe(ceHasReleaseKinds())
    })

    it.runIf(proSchema)('the offered-kinds read validates against the Pro schema, and SubmissionRefusal has KIND_DISABLED', () => {
        expect(validate(proSchema!, RELEASE_KINDS).map(e => e.message)).toEqual([])
        expect(enumValuesOf(proSchema!, 'SubmissionRefusal')).toContain('KIND_DISABLED')
    })

    it('row 24: the feature-set release read validates on CE as well', () => {
        expect(validate(ceSchema, RELEASES_OF_BRANCH).map(e => e.message)).toEqual([])
    })

    it('row 24: each gate is shut on CE exactly while its canary is false', () => {
        expect(teaProfilesAvailable('OSS')).toBe(ceServesSbomProfiles())
        expect(teaProfilesAvailable('OSS')).toBe(ceServesTea())
        // The CE schema declares packageKinds and submissionsOfComponent since the SCORE-72 sync, but the
        // package settings fetcher is Pro's (ws/saas/PackageSettingsDataFetcher) and the shared submission
        // fetcher answers EDITION_UNSUPPORTED without Pro's hook (SCORE-72 design 3.7): both gates stay shut.
        expect(regulatoryPackagesAvailable('OSS')).toBe(false)
        expect(submissionsAvailable('OSS')).toBe(false)
        for (const t of ['SAAS', 'DEMO', 'MANAGED_SERVICE']) {
            expect(regulatoryPackagesAvailable(t)).toBe(true)
            expect(submissionsAvailable(t)).toBe(true)
        }
    })

    it.runIf(proSchema)('row 25: the label tables and option lists equal the Pro enums', () => {
        const keys = (o: Record<string, string>) => Object.keys(o).sort()
        expect(keys(SUBMISSION_STATE_LABELS)).toEqual(enumValuesOf(proSchema!, 'SubmissionState').sort())
        expect(keys(SUBMISSION_ACT_LABELS)).toEqual(enumValuesOf(proSchema!, 'SubmissionActOutcome').sort())
        expect(keys(SUBMISSION_PART_OUTCOME_LABELS)).toEqual(enumValuesOf(proSchema!, 'SubmissionPartOutcome').sort())
        expect(keys(PART_SKIP_REASON_LABELS)).toEqual(enumValuesOf(proSchema!, 'PartSkipReason').sort())
        expect(PACKAGE_SELECTION_OPTIONS.map(o => o.value).sort()).toEqual(enumValuesOf(proSchema!, 'PackagePartSelection').sort())
        expect(PACKAGE_VEX_FORMAT_OPTIONS.map(o => o.value).sort()).toEqual(enumValuesOf(proSchema!, 'VexDocumentFormat').sort())
    })

    const SUBMISSION_EVENTS = ['RELEASE_SUBMISSION_CREATED', 'RELEASE_SUBMISSION_WITHDRAWN']

    it.runIf(proSchema)('row 25: the two notification options are Pro event types', () => {
        const pro = enumValuesOf(proSchema!, 'NotificationEventTypeEnum')
        for (const v of SUBMISSION_EVENTS) {
            expect(pro, v).toContain(v)
            expect((eventTypeOptions as any[]).find(o => o.value === v)?.proOnly, v).toBe(true)
        }
    })

    it('row 25: the CE schema has neither notification type nor the SUBMISSION history scope while it serves no submissions', () => {
        const ce = enumValuesOf(ceSchema, 'NotificationEventTypeEnum')
        for (const v of SUBMISSION_EVENTS) expect(ce.includes(v), v).toBe(ceServesSubmissions())
        expect(enumValuesOf(ceSchema, 'ReleaseUpdateScopeEnum').includes('SUBMISSION')).toBe(ceServesSubmissions())
    })
})
