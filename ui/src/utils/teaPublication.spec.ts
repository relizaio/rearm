import { describe, it, expect, vi } from 'vitest'
import {
    teaPublishConfirm, teaHideConfirm, teaPublicUrlText, teaPublishSummary, teaCollectionRows, teaEntryRows,
    teaPublicationBanner, teaPublicationHistoryText, teaConcealedText, teaPublishedWithText,
    teaActErrorText, TEA_ACT_GATEWAY_TEXT,
    teaActLabel, teaActProgress, teaEntryChecksums, teaEntryFileName, teaEntryDownloadRef, teaMediaTypeExtension,
} from './teaPublication'

vi.mock('./fetchClient', () => ({ fetchWithAuth: vi.fn(), fetchArrayBufferWithAuth: vi.fn() }))

// TEA publications (task TEA-5, design 4.10 tests 54 to 57): the release page's pure helpers.

const API = 'https://rearm.example.com/tea/11111111-1111-1111-1111-111111111111'
const NAMES = { component: 'shop-api', version: '1.4.0', perspectives: { 'p-1': 'Payments' } }

function view (over: Record<string, any> = {}): any {
    return {
        release: 'r-1',
        kind: 'COMPONENT_RELEASE',
        canPublish: true,
        refusal: null,
        refusalMessage: null,
        effectiveVisibility: 'PRIVATE',
        profileSource: 'ORGANIZATION',
        profileSourceObject: null,
        profileRevision: 3,
        apiBase: API,
        publicUrl: null,
        cascades: false,
        cascadeChildren: 0,
        publication: null,
        collections: [],
        ...over,
    }
}

function publication (over: Record<string, any> = {}): any {
    return {
        uuid: 'pub-1',
        state: 'PUBLISHED',
        exposure: 'PRIVATE',
        latestVersion: 2,
        aggregateArtifact: 'agg-1',
        publicUrl: API + '/v1.0.0/componentRelease/pub-1',
        ...over,
    }
}

describe('teaPublishConfirm and teaHideConfirm (54)', () => {
    it('PRIVATE answers a plain dialog naming the source and the revision', () => {
        const c = teaPublishConfirm(view(), NAMES)
        expect(c.title).toBe('Publish shop-api 1.4.0 on TEA?')
        expect(c.text).toBe('Published as a TEA component release under the organization TEA profile (revision 3). '
            + 'Visibility: PRIVATE: readable with an external key that covers this component.')
        expect(c.showCancelButton).toBe(true)
        expect(c.icon).toBeUndefined()
        expect(c.confirmButtonColor).toBeUndefined()
        expect(c.confirmButtonText).toBe('Publish')
    })

    it('names a perspective source by name, and asks to Re-publish once a publication exists', () => {
        const c = teaPublishConfirm(view({ profileSource: 'FOLLOWED_PERSPECTIVE', profileSourceObject: 'p-1',
            publication: publication({ state: 'HIDDEN' }) }), NAMES)
        expect(c.title).toBe('Re-publish shop-api 1.4.0 on TEA?')
        expect(c.text).toContain('under the perspective Payments TEA profile (revision 3).')
        expect(c.confirmButtonText).toBe('Re-publish')
    })

    it('PUBLIC answers the warning icon, the red button and the public URL', () => {
        const c = teaPublishConfirm(view({ effectiveVisibility: 'PUBLIC', publication: publication(),
            publicUrl: API + '/v1.0.0/componentRelease/pub-1' }), NAMES)
        expect(c.icon).toBe('warning')
        expect(c.confirmButtonColor).toBe('#d33')
        expect(c.text).toContain('Visibility is PUBLIC: anyone who knows the URL can read it without authentication: '
            + API + '/v1.0.0/componentRelease/pub-1')
        expect(c.text).not.toContain('PRIVATE')
    })

    it('PUBLIC before the first publish names the base path with the id assigned on publish', () => {
        const c = teaPublishConfirm(view({ effectiveVisibility: 'PUBLIC', kind: 'PRODUCT_RELEASE' }), NAMES)
        expect(c.text).toContain(API + '/v1.0.0/productRelease/<id assigned on publish>')
        expect(teaPublicUrlText(view({ apiBase: API + '/' }))).toBe(API + '/v1.0.0/componentRelease/<id assigned on publish>')
        expect(teaPublicUrlText(view({ apiBase: null }))).toBe('no TEA URL yet: the organization has no TEA id')
    })

    it('a product with PUBLISH_WITH_PRODUCT adds the children sentence; PRODUCT_ONLY does not', () => {
        const cascading = teaPublishConfirm(view({ kind: 'PRODUCT_RELEASE', cascades: true, cascadeChildren: 4 }), NAMES)
        expect(cascading.text).toContain('Published as a TEA product release')
        expect(cascading.text).toContain('Its 4 component releases are published with it unless their own TEA profile '
            + 'forbids it.')
        const one = teaPublishConfirm(view({ kind: 'PRODUCT_RELEASE', cascades: true, cascadeChildren: 1 }), NAMES)
        expect(one.text).toContain('Its 1 component release is published with it unless its own TEA profile forbids it.')
        const productOnly = teaPublishConfirm(view({ kind: 'PRODUCT_RELEASE', cascades: false, cascadeChildren: 0 }), NAMES)
        expect(productOnly.text).not.toContain('component releases are published')
    })

    it('the built-in default profile has no revision', () => {
        const c = teaPublishConfirm(view({ profileSource: 'DEFAULT', profileRevision: null }), NAMES)
        expect(c.text).toContain('under the built-in default TEA profile.')
    })

    it('teaHideConfirm carries the kept collections sentence and the red button', () => {
        const c = teaHideConfirm(view({ publication: publication() }))
        expect(c.title).toBe('Hide from TEA?')
        expect(c.text).toContain('The release stops being served on TEA; its published collections are kept and '
            + 're-publish continues their numbering.')
        expect(c.showCancelButton).toBe(true)
        expect(c.confirmButtonColor).toBe('#d33')
    })
})

describe('teaPublishSummary (55)', () => {
    it('PUBLISHED with a skipped ride-along and a refused child names version, revision, counts and reasons', () => {
        const text = teaPublishSummary({
            outcome: 'PUBLISHED',
            publication: publication({ latestVersion: 1 }),
            collection: { version: 1, entries: [
                { artifact: 'agg-1', teaUuid: 't-agg', revision: 5, origin: 'SYNTHETIC' },
                { artifact: 'a-2', teaUuid: 't-2', revision: 1, origin: 'REAL' },
            ] },
            artifacts: [
                { artifact: 'a-2', status: 'PUBLISHED' },
                { artifact: 'a-3', status: 'SKIPPED_NO_RAW_BYTES', detail: 'predates raw retention' },
            ],
            children: [
                { release: 'c-1', componentName: 'web', version: '2.0', outcome: 'PUBLISHED' },
                { release: 'c-2', componentName: 'db', version: '0.9', outcome: 'REFUSED', refusal: 'LIFECYCLE_TOO_LOW',
                    detail: 'db 0.9 is DRAFT' },
            ],
        })
        expect(text).toBe('Published: collection version 1; aggregate SBOM revision 5; '
            + '1 artifacts published, 1 skipped (1 no retained raw bytes); '
            + '1 component releases published, 0 skipped, 1 refused. '
            + 'db 0.9: refused (LIFECYCLE_TOO_LOW): db 0.9 is DRAFT')
    })

    it('REPUBLISHED leads with Re-published and leaves out the children part for a component release', () => {
        const text = teaPublishSummary({ outcome: 'REPUBLISHED', publication: publication(),
            collection: { version: 2, entries: [{ artifact: 'agg-1', revision: 2, origin: 'SYNTHETIC' }] },
            artifacts: [], children: [] })
        expect(text).toBe('Re-published: collection version 2; aggregate SBOM revision 2; 0 artifacts published, 0 skipped')
    })

    it('SKIPPED_OTHER_PRODUCT is skipped and names the owning product from its detail (round 2, 80)', () => {
        const text = teaPublishSummary({
            outcome: 'PUBLISHED',
            publication: publication({ latestVersion: 1 }),
            collection: { version: 1, entries: [{ artifact: 'agg-1', revision: 1, origin: 'SYNTHETIC' }] },
            artifacts: [],
            children: [
                { release: 'c-1', componentName: 'web', version: '2.0', outcome: 'SKIPPED_OTHER_PRODUCT',
                    detail: 'published with product platform 3.1.0' },
                { release: 'c-2', componentName: 'db', version: '0.9', outcome: 'REFUSED', refusal: 'PUBLISHING_DISABLED',
                    detail: 'publishing is DISABLED by the component c-2 TEA profile' },
                { release: 'c-3', outcome: 'REFUSED', refusal: 'RELEASE_NOT_FOUND', detail: 'not found' },
            ],
        })
        expect(text).toBe('Published: collection version 1; aggregate SBOM revision 1; 0 artifacts published, 0 skipped; '
            + '0 component releases published, 1 skipped, 2 refused. '
            + 'web 2.0: skipped, published with product platform 3.1.0; '
            + 'db 0.9: refused (PUBLISHING_DISABLED): publishing is DISABLED by the component c-2 TEA profile; '
            + 'c-3: refused (RELEASE_NOT_FOUND): not found')
    })

    it('SKIPPED_LINK_NOT_HTTP_URL (TEA-19) shows its label, not the raw status', () => {
        const text = teaPublishSummary({
            outcome: 'PUBLISHED',
            publication: publication({ latestVersion: 1 }),
            collection: { version: 1, entries: [{ artifact: 'agg-1', revision: 1, origin: 'SYNTHETIC' }] },
            artifacts: [
                { artifact: 'a-2', status: 'SKIPPED_LINK_NOT_HTTP_URL', detail: 'ftp://files.example.com/a.zip' },
                { artifact: 'a-3', status: 'SKIPPED_NO_DOWNLOAD_LINK' },
            ],
            children: [],
        })
        expect(text).toBe('Published: collection version 1; aggregate SBOM revision 1; 0 artifacts published, 2 skipped '
            + '(1 download link is not an absolute http(s) URL, 1 no download link)')
        expect(text).not.toContain('SKIPPED_')
    })

    it('SKIPPED_VDR_SNAPSHOT (FDAW-80) shows its label, not the raw status', () => {
        const text = teaPublishSummary({
            outcome: 'PUBLISHED',
            publication: publication({ latestVersion: 1 }),
            collection: { version: 1, entries: [{ artifact: 'agg-1', revision: 1, origin: 'SYNTHETIC' }] },
            artifacts: [
                { artifact: 'a-2', status: 'PUBLISHED' },
                { artifact: 'a-3', status: 'SKIPPED_VDR_SNAPSHOT', detail: 'a VDR snapshot stored by a release action is not published on TEA' },
                { artifact: 'a-4', status: 'SKIPPED_NO_DOWNLOAD_LINK' },
            ],
            children: [],
        })
        expect(text).toBe('Published: collection version 1; aggregate SBOM revision 1; 1 artifacts published, 2 skipped '
            + '(1 VDR snapshot, never published on TEA, 1 no download link)')
        expect(text).not.toContain('SKIPPED_')
    })

    it('UNCHANGED names the version', () => {
        expect(teaPublishSummary({ outcome: 'UNCHANGED', publication: publication({ latestVersion: 3 }),
            collection: { version: 3, entries: [] }, artifacts: [], children: [] }))
            .toBe('Nothing changed since collection version 3')
    })
})

describe('teaConcealedText and teaPublishedWithText (round 2, 80)', () => {
    const concealed = (reason: string, over: Record<string, any> = {}) =>
        teaConcealedText(publication({ exposure: 'CONCEALED', concealedBecause: reason, ...over }), 'CANCELLED')

    it('renders every TeaConcealReason', () => {
        expect(concealed('HIDDEN')).toBe('Concealed: hidden')
        expect(concealed('RELEASE_MISSING')).toBe('Concealed: the release is missing')
        expect(concealed('RELEASE_ARCHIVED')).toBe('Concealed: the release is archived')
        expect(concealed('LIFECYCLE_BELOW_ASSEMBLED')).toBe('Concealed: the release is CANCELLED')
        expect(concealed('COMPONENT_MISSING')).toBe('Concealed: the component is missing')
        expect(concealed('PROFILE_CONFLICT')).toBe('Concealed: the component\'s TEA profiles conflict')
        expect(concealed('PUBLISHING_DISABLED')).toBe('Concealed: publishing is disabled by the component\'s own TEA profile')
        expect(concealed('PRODUCT_MISSING')).toBe('Concealed: the product it was published with is missing')
        expect(concealed('PRODUCT_CONCEALED', { cascadeOf: 'pub-0', cascadeOfLabel: 'platform 3.1.0' }))
            .toBe('Concealed: with its product platform 3.1.0')
    })

    it('answers null while the publication is served, and a lifecycle-less fallback', () => {
        expect(teaConcealedText(publication({ exposure: 'PRIVATE' }))).toBeNull()
        expect(teaConcealedText(null)).toBeNull()
        expect(teaConcealedText(publication({ exposure: 'CONCEALED', concealedBecause: 'LIFECYCLE_BELOW_ASSEMBLED' })))
            .toBe('Concealed: the release is below ASSEMBLED')
    })

    it('names the product a cascade child is published with, and nothing for a direct publication', () => {
        expect(teaPublishedWithText(publication({ cascadeOf: 'pub-0', cascadeOfLabel: 'platform 3.1.0' })))
            .toBe('Published with product platform 3.1.0')
        expect(teaPublishedWithText(publication())).toBeNull()
    })
})

describe('teaPublicationBanner (56)', () => {
    it('PUBLISHED and PUBLIC answers the text with the URL', () => {
        expect(teaPublicationBanner(view({ publication: publication({ exposure: 'PUBLIC' }) })))
            .toBe('Public on TEA: this release is readable by anyone who knows its TEA URL: '
                + API + '/v1.0.0/componentRelease/pub-1')
    })

    it('PUBLISHED and PRIVATE, HIDDEN, or no publication answers null', () => {
        expect(teaPublicationBanner(view({ publication: publication({ exposure: 'PRIVATE' }) }))).toBeNull()
        expect(teaPublicationBanner(view({ publication: publication({ state: 'HIDDEN', exposure: 'CONCEALED' }) }))).toBeNull()
        expect(teaPublicationBanner(view())).toBeNull()
        expect(teaPublicationBanner(null)).toBeNull()
    })
})

describe('teaCollectionRows, teaEntryRows and teaPublicationHistoryText (57)', () => {
    const v1 = { version: 1, createdDate: '2026-10-01T10:00:00Z', updateReasonType: 'INITIAL_RELEASE',
        updateReasonComment: null, documentSha256: 'a'.repeat(64), entries: [{}] }
    const v2 = { version: 2, createdDate: '2026-10-02T10:00:00Z', updateReasonType: 'ARTIFACT_ADDED',
        updateReasonComment: 'added: notes.pdf', documentSha256: 'b'.repeat(64), entries: [{}, {}] }

    it('rows come in version order with the reason, the comment, the sha256 and the artifact count', () => {
        const rows = teaCollectionRows([v2, v1])
        expect(rows.map(r => r.version)).toEqual([1, 2])
        expect(rows[0].reason).toBe('Initial release')
        expect(rows[1].reason).toBe('Artifact added')
        expect(rows[1].comment).toBe('added: notes.pdf')
        expect(rows[1].sha256).toBe('b'.repeat(64))
        expect(rows[1].sha256Short).toBe('b'.repeat(12))
        expect(rows[1].artifactCount).toBe(2)
        expect(teaCollectionRows(null)).toEqual([])
    })

    it('entries read Generated for SYNTHETIC and Uploaded for REAL, with the artifact URL built from apiBase', () => {
        const rows = teaEntryRows({ entries: [
            { teaUuid: 't-agg', revision: 5, origin: 'SYNTHETIC', type: 'BOM', name: 'shop-api-1.4.0-tea-aggregate.cdx.json',
                mediaType: 'application/vnd.cyclonedx+json', checksums: [{ algType: 'SHA-256', algValue: 'c'.repeat(64) }] },
            { teaUuid: 't-2', revision: 1, origin: 'REAL', type: 'RELEASE_NOTES', name: 'notes.pdf',
                url: 'https://cdn.example.com/notes.pdf', checksums: [] },
        ] }, API + '/')
        expect(rows.map(r => r.origin)).toEqual(['Generated', 'Uploaded'])
        expect(rows[0].teaUrl).toBe(API + '/v1.0.0/artifact/t-agg/5')
        expect(rows[0].checksum).toBe('SHA-256:' + 'c'.repeat(64))
        expect(rows[1].teaUrl).toBe(API + '/v1.0.0/artifact/t-2/1')
        expect(rows[1].externalUrl).toBe('https://cdn.example.com/notes.pdf')
        expect(teaEntryRows({ entries: [{ teaUuid: 't', revision: 1, origin: 'REAL' }] }, null)[0].teaUrl).toBe('')
    })

    it('teaPublicationHistoryText renders ADDED, CHANGED and REMOVED', () => {
        expect(teaPublicationHistoryText({ rua: 'ADDED', rus: 'TEA_PUBLICATION', newValue: 'v1' })).toBe('Published on TEA: v1')
        expect(teaPublicationHistoryText({ rua: 'CHANGED', oldValue: 'v1', newValue: 'v2' })).toBe('Re-published on TEA: v1 -> v2')
        expect(teaPublicationHistoryText({ rua: 'CHANGED', oldValue: 'HIDDEN', newValue: 'PUBLISHED v3' }))
            .toBe('Re-published on TEA: HIDDEN -> PUBLISHED v3')
        expect(teaPublicationHistoryText({ rua: 'REMOVED', newValue: 'HIDDEN' })).toBe('Hidden from TEA')
        expect(teaPublicationHistoryText({ rua: 'CHANGED', oldValue: 'with left 1.0.0', newValue: 'with right 2.0.0' }))
            .toBe('Published on TEA with another product: with left 1.0.0 -> with right 2.0.0')
    })
})

describe('the collection artifact rows: checksums and the manual download', () => {
    const ART = '0f8fad5b-d9cb-469f-a165-70867728950e'

    it('lists every checksum of an entry, one per algorithm, and none for an entry with no checksum', () => {
        const entry = { teaUuid: 't', revision: 2, checksums: [
            { algType: 'SHA-256', algValue: 'a'.repeat(64) }, { algType: 'SHA-512', algValue: 'b'.repeat(128) },
            { algType: 'MD5', algValue: '' }] }
        expect(teaEntryChecksums(entry)).toEqual([{ alg: 'SHA-256', value: 'a'.repeat(64) }, { alg: 'SHA-512', value: 'b'.repeat(128) }])
        expect(teaEntryChecksums({ teaUuid: 't', revision: 1 })).toEqual([])
        expect(teaEntryRows({ entries: [entry] }, API)[0].checksums).toHaveLength(2)
    })

    it('names the file as TEA serves it: <teaUuid>-r<revision> and the media type extension, never the artifact name', () => {
        expect(teaEntryFileName({ teaUuid: 't-1', revision: 3, origin: 'SYNTHETIC', name: 'default', sbomProfileName: 'default',
            mediaType: 'application/vnd.cyclonedx+json' })).toBe('t-1-r3.cdx.json')
        expect(teaEntryFileName({ teaUuid: 't-1', revision: 1, origin: 'REAL', name: 'notes.pdf', mediaType: 'application/pdf' }))
            .toBe('t-1-r1.pdf')
        expect(teaEntryFileName({ teaUuid: 't-1', revision: 2, origin: 'REAL', name: 'sbom', mediaType: 'application/spdx+json' }))
            .toBe('t-1-r2.spdx.json')
        expect(teaEntryFileName({ teaUuid: 't-1', revision: 5, origin: 'REAL', mediaType: 'application/octet-stream' })).toBe('t-1-r5')
        expect(teaEntryFileName({ teaUuid: 't-1', revision: 6, origin: 'REAL' })).toBe('t-1-r6')
    })

    it('maps media types to extensions as the backend TEA file name rule does', () => {
        expect(teaMediaTypeExtension('application/vnd.cyclonedx+xml')).toBe('.cdx.xml')
        expect(teaMediaTypeExtension('text/spdx')).toBe('.spdx')
        expect(teaMediaTypeExtension('application/json')).toBe('.json')
        expect(teaMediaTypeExtension('text/plain;charset=utf-8')).toBe('.txt')
        expect(teaMediaTypeExtension('Application/VND.CycloneDX+JSON; version=1.6')).toBe('.cdx.json')
        expect(teaMediaTypeExtension('application/vnd.openvex+json')).toBe('.json')
        expect(teaMediaTypeExtension('application/vnd.example+xml')).toBe('.xml')
        expect(teaMediaTypeExtension('application/x-unknown')).toBe('')
        expect(teaMediaTypeExtension(null)).toBe('')
    })

    it('downloads the artifact row at the frozen revision, and nothing for an external or malformed entry', () => {
        expect(teaEntryDownloadRef({ artifact: ART, teaUuid: 't-1', revision: 4, origin: 'REAL', name: 'sbom.json',
            mediaType: 'application/json' })).toEqual({ uuid: ART, version: 4, fileName: 't-1-r4.json' })
        expect(teaEntryDownloadRef({ artifact: ART, teaUuid: 't-1', revision: 1, origin: 'REAL', name: 'notes.pdf',
            url: 'https://cdn.example.com/notes.pdf' })).toBeNull()
        expect(teaEntryDownloadRef({ artifact: 'not-a-uuid', teaUuid: 't-1', revision: 1 })).toBeNull()
        expect(teaEntryDownloadRef({ artifact: ART, teaUuid: 't-1', revision: null })).toBeNull()
        const rows = teaEntryRows({ entries: [{ artifact: ART, teaUuid: 't-1', revision: 7, origin: 'SYNTHETIC',
            name: 'top', sbomProfileName: 'top', mediaType: 'application/vnd.cyclonedx+json' }] }, API)
        expect(rows[0].download).toEqual({ uuid: ART, version: 7, fileName: 't-1-r7.cdx.json' })
    })
})

describe('editionPermissionFunctions (PUBLISH_EXTERNALLY only where TEA is served)', () => {
    it('offers PUBLISH_EXTERNALLY on Pro and on CE (served since the SCORE-72 sync), keeping every other function', async () => {
        const { editionPermissionFunctions } = await import('./teaProfile')
        const all = ['RESOURCE', 'ARTIFACT_DOWNLOAD', 'PUBLISH_EXTERNALLY', 'AGENT']
        expect(editionPermissionFunctions(all, 'SAAS')).toEqual(all)
        expect(editionPermissionFunctions(all, 'OSS')).toEqual(all)
    })
})

describe('teaActErrorText (round 4, 95)', () => {
    const IN_PROGRESS = 'Release shop-api 1.4.0 (r-1): another publish or hide of this release is in progress on this '
        + 'or another ReARM instance; it did not finish within 30 seconds; repeat the act once it has finished'

    it('a proxy that gave up answers the gateway text', () => {
        for (const statusCode of [502, 503, 504]) {
            expect(teaActErrorText({ message: 'whatever', networkError: { statusCode } }), String(statusCode))
                .toBe(TEA_ACT_GATEWAY_TEXT)
        }
        expect(teaActErrorText({ message: 'Response not successful: Received status code 504' })).toBe(TEA_ACT_GATEWAY_TEXT)
        expect(teaActErrorText({ message: 'Response not successful: Received status code 502' })).toBe(TEA_ACT_GATEWAY_TEXT)
        expect(teaActErrorText({ message: 'Failed to fetch' })).toBe(TEA_ACT_GATEWAY_TEXT)
        expect(TEA_ACT_GATEWAY_TEXT).toContain('Reload the release page in a minute to see the result; repeating the act is safe.')
    })

    it('a GraphQL error keeps the server message: PUBLISH_IN_PROGRESS unchanged, a known prefix stripped', () => {
        expect(teaActErrorText({ message: IN_PROGRESS, graphQLErrors: [{ message: IN_PROGRESS }] })).toBe(IN_PROGRESS)
        expect(teaActErrorText({ message: 'Rebom error: merge failed' })).toBe('merge failed')
        expect(teaActErrorText({ message: 'Response not successful: Received status code 500' }))
            .toBe('Response not successful: Received status code 500')
        expect(teaActErrorText({ message: 'x', networkError: { statusCode: 400 } })).toBe('x')
        expect(teaActErrorText({ message: 'server error', networkError: { statusCode: 500 } })).toBe('server error')
    })

    it('a refusal of a non-null act answers the refusal entry alone (TEA-30 round 4, 3.7.1)', () => {
        const NULL_TEXT = "The field at path '/publishReleaseOnTea' was declared as a non null type, but the code involved in "
            + 'retrieving data has wrongly returned a null value.  The graphql specification requires that the parent '
            + 'field be set to null, or if that is non nullable that it bubble up null to its parent and so on. The '
            + "non-nullable type is 'TeaPublishResult' within parent type 'Mutation'"
        const err = Object.assign(new Error(IN_PROGRESS + '\n' + NULL_TEXT), { errors: [
            { message: IN_PROGRESS, extensions: { classification: 'BAD_REQUEST' } },
            { message: NULL_TEXT, path: ['publishReleaseOnTea'], extensions: { classification: 'NullValueInNonNullableField' } },
        ] })
        expect(teaActErrorText(err)).toBe(IN_PROGRESS)
    })
})

// TEA-30 (design 3.3.7, D13): the publication names one aggregate per SBOM profile (TEA-28); the
// summary lists them with their revisions, and the entries carry the profile name.
describe('the aggregate list and the profile column (TEA-30)', () => {
    it('lists one aggregate per profile in the publication order, the deprecated sentence only without a list', () => {
        const text = teaPublishSummary({ outcome: 'PUBLISHED',
            publication: publication({ aggregateArtifacts: [{ profileName: 'default', artifact: 'agg-1' },
                { profileName: 'top', artifact: 'agg-2' }] }),
            collection: { version: 3, entries: [{ artifact: 'agg-1', revision: 2, origin: 'SYNTHETIC' },
                { artifact: 'agg-2', revision: 1, origin: 'SYNTHETIC' }] },
            artifacts: [], children: [] })
        expect(text).toBe('Published: collection version 3; aggregate SBOMs: default r2, top r1; 0 artifacts published, 0 skipped')
        const legacy = teaPublishSummary({ outcome: 'PUBLISHED', publication: publication({ aggregateArtifacts: [] }),
            collection: { version: 1, entries: [{ artifact: 'agg-1', revision: 4, origin: 'SYNTHETIC' }] }, artifacts: [], children: [] })
        expect(legacy).toBe('Published: collection version 1; aggregate SBOM revision 4; 0 artifacts published, 0 skipped')
    })

    it('an entry carries its SBOM profile name, empty for a legacy entry', () => {
        const rows = teaEntryRows({ entries: [
            { teaUuid: 't-1', revision: 1, origin: 'SYNTHETIC', name: 'top.cdx.json', sbomProfileName: 'top' },
            { teaUuid: 't-2', revision: 1, origin: 'SYNTHETIC', name: 'shop-api-1.4.0-tea-aggregate.cdx.json' },
        ] }, API)
        expect(rows.map(r => r.profile)).toEqual(['top', ''])
    })
})

describe('the TEA act progress (the server publishes within the request)', () => {
    it('cannot be dismissed, names the act and says to keep the page open', () => {
        for (const [act, title] of [['publish', 'Publishing on TEA…'], ['republish', 'Re-publishing on TEA…'], ['hide', 'Hiding from TEA…']] as const) {
            const p = teaActProgress(act, { kind: 'COMPONENT_RELEASE' })
            expect(p.title).toBe(title)
            expect(teaActLabel(act)).toBe(title)
            expect(p.allowOutsideClick).toBe(false)
            expect(p.allowEscapeKey).toBe(false)
            expect(p.showConfirmButton).toBe(false)
            expect(p.text).toContain('Keep this page open until it finishes.')
        }
        expect(teaActProgress('publish', {}).text).toContain('Generating the aggregated SBOM')
        expect(teaActProgress('hide', {}).text).not.toContain('Generating')
    })

    it('says a cascading product publishes its component releases with it', () => {
        expect(teaActProgress('publish', { kind: 'PRODUCT_RELEASE', cascades: true }).text)
            .toContain('Its component releases are published with it.')
        expect(teaActProgress('hide', { kind: 'PRODUCT_RELEASE', cascades: true }).text)
            .not.toContain('component releases are published')
    })
})
