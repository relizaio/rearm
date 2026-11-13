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
        expect(c.titleText).toBe('Publish shop-api 1.4.0 on TEA?')
        expect(c).not.toHaveProperty('title')
        expect(c.text).toBe('Published as a TEA component release under the organization TEA profile (revision 3). '
            + 'Visibility: PRIVATE: readable with an external key that covers this component.')
        expect(c.showCancelButton).toBe(true)
        expect(c.icon).toBeUndefined()
        expect(c.confirmButtonColor).toBeUndefined()
        expect(c.confirmButtonText).toBe('Publish')
    })

    it('FDAW-95 row 1: the names are in titleText only, one line each, and no title or html key exists', () => {
        const H = '<img src=x onerror="window.__teaHit=1"></script><script>window.__teaHit=2</script><b>b</b>'
        const c: any = teaPublishConfirm(view(), { component: H, version: H })
        expect(c.titleText).toBe('Publish ' + H + ' ' + H + ' on TEA?')
        expect(Object.keys(c).sort()).toEqual(['confirmButtonText', 'showCancelButton', 'text', 'titleText'])
        expect(Object.entries(c).filter(([, v]) => typeof v === 'string' && v.includes('<img')).map(([k]) => k)).toEqual(['titleText'])
        const BIDI = String.fromCodePoint(0x202e)
        expect(teaPublishConfirm(view(), { component: 'a\nb', version: BIDI + '1.0' }).titleText).toBe('Publish a b 1.0 on TEA?')
        expect(teaPublishConfirm(view(), { component: 'x'.repeat(150), version: '1.0' }).titleText)
            .toBe('Publish ' + 'x'.repeat(99) + '… 1.0 on TEA?')
        expect(teaPublishConfirm(view(), { component: null, version: undefined } as any).titleText).toBe('Publish   on TEA?')
        const pub: any = teaPublishConfirm(view({ effectiveVisibility: 'PUBLIC', publication: publication() }), { component: H, version: H })
        expect(pub.titleText).toBe('Re-publish ' + H + ' ' + H + ' on TEA?')
        expect(pub).not.toHaveProperty('title')
        expect(teaHideConfirm(view()).title).toBe('Hide from TEA?')
    })

    it('names a perspective source by name, and asks to Re-publish once a publication exists', () => {
        const c = teaPublishConfirm(view({ profileSource: 'FOLLOWED_PERSPECTIVE', profileSourceObject: 'p-1',
            publication: publication({ state: 'HIDDEN' }) }), NAMES)
        expect(c.titleText).toBe('Re-publish shop-api 1.4.0 on TEA?')
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

// ---------------------------------------------------------------------------------------------
// FDAW-39 (FDAW-25 D2f), architecture round 5 section 4: rows 22 (h) to (l), 50 (a) to (e), (j),
// (q), (v), and 53 (a) to (d), (f). Fixtures as the design names them.
import {
    teaOneLine, TEA_NAME_MAX, teaChildName, teaChildReason, teaPartialChildren, teaPartialOfferText,
    teaShareConfirm, teaShareButtonState, teaShareRefusalText, teaShareErrorText, teaTurnOnPublicConfirm,
    teaTurnOnStopText, teaTurnOnStopSentence, teaServerAnswered, teaChildrenReadProgress, teaTurnOnProgress,
    teaNotStartedText, TEA_CHILD_PUBLISH_FAILED, TEA_TURN_ON_PUBLIC_TITLE, TEA_TURN_ON_PUBLIC_BUTTON,
    TEA_SHARE_NOT_AUTHORIZED, TEA_PARTIAL_HINT, TEA_CHILDREN_READ_FAILED,
} from './teaPublication'
import {
    TEA_AUTO_PUBLISH_OPTIONS, TEA_AUTO_PUBLISH_PAUSED_OWN, TEA_AUTO_PUBLISH_PAUSED_INHERITED, teaAutoPublishHelp,
    TEA_TURN_ON_TEXT, TEA_START_REMOVAL_OBJECT, TEA_START_REMOVAL_MEMBERS, TEA_START_MEMBERSHIP,
    TEA_PUBLIC_CONFIRM_COUNTED, TEA_PUBLIC_CONFIRM_NONE_YET,
} from './teaProfile'
import { TEA_ACTION_COUNTED, TEA_ACTION_NONE_YET, TEA_GLOBAL_ACTION_COUNTED, TEA_GLOBAL_ACTION_NONE_YET } from './teaOutputEvents'
import { FDA_PROSE_TEA_NOTE } from './fdaProseInput'
import { PACKAGE_PART_LABELS } from './packageSettings'
import { ENUM_RE, UUID_RE, WORD_RE, expectSwept } from './__fixtures__/fdaSweep'

const HOSTILE = '<img src=x onerror="window.__teaHit=1"></script><script>window.__teaHit=2</script><b>b</b>'
const BREAK = (a: string, b: string) => a + '\n' + b
const BIDI = String.fromCodePoint(0x202E)
const UUID7 = '3bef7cde-2edb-47cb-aa4e-94487c2cdced'

const child = (release: string, componentName: string | null, version: string | null, outcome: string,
    more: Record<string, any> = {}) => ({ release, componentName, version, outcome, refusal: null, detail: null, ownRow: null, ...more })

const LIST = [
    child('r-api', 'Api', '1.0', 'WILL_PUBLISH'),
    child('r-db', 'Db', '2.0', 'REFUSED', { refusal: 'PUBLISHING_DISABLED', ownRow: 'row-db' }),
    child('r-ui', 'Ui', '3.0', 'SKIPPED_DIRECT'),
    child('r-fw', 'Fw', '4.0', 'REFUSED', { refusal: 'LIFECYCLE_TOO_LOW',
        detail: "Release Fw 4.0 is DRAFT; the TEA profile's minimum lifecycle is ASSEMBLED" }),
    child('r-kit', 'Kit', '5.0', 'SKIPPED_HIDDEN'),
    child('r-lib', 'Lib', '6.0', 'SKIPPED_OTHER_PRODUCT', { detail: 'published with product Suite 9' }),
    child(UUID7, null, null, 'REFUSED', { refusal: 'RELEASE_NOT_FOUND', detail: 'not found' }),
    child('r-gw', 'Gw', '8.0', 'REFUSED', { refusal: 'GENERATION_FAILED', detail: 'could not be checked' }),
    child('r-web', 'Web', '9.0', 'WILL_PUBLISH'),
]
const LIST_LINES = ['Db 2.0: its TEA profile has publishing disabled', 'Ui 3.0: published on its own already',
    'Fw 4.0: its lifecycle is below the minimum for publishing', 'Kit 5.0: hidden on TEA by a person',
    'Lib 6.0: published with product Suite 9',
    'A component release that could not be read: it could not be found in this organization', 'Gw 8.0: it could not be checked']
const OFFERING = [LIST[0], LIST[1], LIST[2]]
const PRODUCT = { release: 'r1', kind: 'PRODUCT_RELEASE', canPublish: true, effectiveVisibility: 'PRIVATE',
    profileSource: 'ORGANIZATION', cascades: true, cascadeChildren: 9, profileRevision: 3, publication: null,
    profiles: ['default'], publishing: 'ENABLED' }
const COMPONENT = { ...PRODUCT, kind: 'COMPONENT_RELEASE', cascades: false, cascadeChildren: 0 }
const PNAMES = { component: 'Pump', version: '1.2.0', perspectives: {} }
const HEAD = 'Published as a TEA product release under the organization TEA profile (revision 3). Visibility: PRIVATE: '
    + 'readable with an external key that covers this product. '
const componentOf = (release: string) => 'comp-' + release
const ADMIN = { isOrgAdmin: true, componentOf }
const PUB = child('r-pub', 'Pub', '1.0', 'REFUSED', { refusal: 'PUBLISHING_DISABLED', ownRow: 'rp' })
const SAME = { before: 'PUBLIC', after: 'PUBLIC', path: 'ROW_PUBLIC', publishedReleases: 3, publishedVersions: 7 }
const PUBTEXT = 'The TEA settings of Pub are public, with publishing turned off. Turning publishing on makes its published '
    + 'releases readable by anyone who knows the TEA URL, without a key: a release published on its own always, and a '
    + "release published with a product, as Pub 1.0 is here, when that product's TEA settings are public too. 3 published "
    + 'release(s) resolving to this profile, with all 7 of their collection versions, earlier versions included, and every '
    + 'document in them (SBOMs, VEX documents, device support statements, published artifacts), become readable by anyone '
    + 'who knows the TEA URL as soon as you save; visibility is live.'
const PUBCONFIRM = { title: 'Publish this component publicly?', text: PUBTEXT, confirmButtonText: 'Publish publicly',
    confirmButtonColor: '#d33', focusCancel: true }
const PCP = { mode: 'OVERRIDE', publishing: 'ENABLED', visibility: 'PUBLIC', rawArtifacts: 'NONE',
    productComponents: 'PUBLISH_WITH_PRODUCT', minimumLifecycle: 'ASSEMBLED', tei: 'DISABLED', teiDomain: null,
    profiles: ['default'], autoPublish: 'DISABLED' }
const NOANSWER = 'No answer arrived for saving the TEA settings of Pub 1.0, so publishing may or may not be turned on for '
    + 'it. Open its TEA settings to check.'
const FAILED = 'publishing it on TEA failed (the server log has the cause)'
const GW = { release: 'h', componentName: 'Gw', version: '8.0', outcome: 'REFUSED', refusal: 'GENERATION_FAILED',
    detail: 'Release Gw 8.0: publishing on TEA failed: Connection refused' }
const REFUSED_RIGHT = { message: 'Not authorized', errors: [{ message: 'Not authorized' },
    { message: 'The field at path ...', extensions: { classification: 'NullValueInNonNullableField' } }] }
const ANSWERED = (m: string) => ({ errors: [{ message: m }] })
const GATEWAY = 'The server is still working on this act and will finish it on its own. Reload the release page in a '
    + 'minute to see the result; repeating the act is safe.'
const OWN_WORDS = /\b(PUBLIC|PRIVATE|HIDDEN|PUBLISHED|REFUSED|SKIPPED)\b/
const swept = (text: string, label = '') => {
    expectSwept(text, label)
    expect('Visibility is PUBLIC').toMatch(OWN_WORDS)
    expect(text, label + ' own words').not.toMatch(OWN_WORDS)
}

const SHARE = { kind: 'FDA_PREMARKET', latestVersion: 2, state: 'FINAL', profileName: 'fda' }
const PRIV_COMPONENT = { ...COMPONENT, profiles: ['default', 'fda'] }
const LEAD = 'Sharing Pump 1.2.0. Customers get, on TEA: the release SBOM per profile (default, fda), its living VEX, and '
    + 'the device support statement when the organization text is authored. They never get the regulatory package: its '
    + 'Narrative, Support addendum and VDR are not published, and the package cannot be downloaded from TEA. Package v2 is Final.'
const T = LEAD + ' Its TEA settings are private: only a customer whose key covers this component can read it.'

describe('FDAW-39 row 22 (h): teaShareConfirm', () => {
    it('a private component: the whole object, the part labels, no cover summary or zip', () => {
        expect(teaShareConfirm(PRIV_COMPONENT, PNAMES, SHARE)).toEqual({ titleText: 'Share with customers?', text: T,
            showCancelButton: true, confirmButtonText: 'Share' })
        expect([PACKAGE_PART_LABELS.NARRATIVE, PACKAGE_PART_LABELS.SUPPORT_ADDENDUM, PACKAGE_PART_LABELS.VDR])
            .toEqual(['Narrative', 'Support addendum', 'VDR'])
        expect(T).toContain('its Narrative, Support addendum and VDR are not published')
        expect(T).not.toContain('cover summary')
        expect(T).not.toContain('zip')
    })

    it('the package state reads through the state labels, an unknown one humanized', () => {
        const text = (state: string) => teaShareConfirm(PRIV_COMPONENT, PNAMES, { ...SHARE, state }).text
        expect(text('SENT')).toContain('Package v2 is Sent.')
        expect(text('AMENDED')).toContain('Package v2 is Amended.')
        expect(text('SOME_NEW_STATE')).toContain('Package v2 is Some new state.')
    })

    it('a public product that does not cascade: warning, red, the URL sentence', () => {
        const v = { ...PRODUCT, cascades: false, effectiveVisibility: 'PUBLIC', profiles: ['default', 'fda'],
            publicUrl: 'https://tea.example/v1.0.0/productRelease/abc' }
        const c = teaShareConfirm(v, PNAMES, SHARE)
        expect(c.icon).toBe('warning')
        expect(c.confirmButtonColor).toBe('#d33')
        expect(c.text).toBe(LEAD + ' Its TEA settings are public: anyone who knows the URL can read it without a key: '
            + 'https://tea.example/v1.0.0/productRelease/abc')
    })

    it('the package profile not on the list, a hidden publication, no profiles', () => {
        const notOnList = 'Customers get the default SBOM; the package\'s fda SBOM is not on your TEA list, so it has a '
            + 'different serial from the one the package carries. Add fda in TEA settings to share the package\'s serial.'
        expect(teaShareConfirm({ ...COMPONENT, profiles: ['default'] }, PNAMES, SHARE).text).toContain(notOnList)
        for (const [profiles, profileName] of [[['default', 'fda'], 'fda'], [['default'], null], [['default'], '']] as const) {
            expect(teaShareConfirm({ ...COMPONENT, profiles: [...profiles] }, PNAMES, { ...SHARE, profileName }).text)
                .not.toContain('is not on your TEA list')
        }
        const hidden = 'This release is hidden on TEA now; sharing makes it readable again.'
        expect(teaShareConfirm({ ...PRIV_COMPONENT, publication: { state: 'HIDDEN' } }, PNAMES, SHARE).text).toContain(hidden)
        expect(teaShareConfirm({ ...PRIV_COMPONENT, publication: { state: 'PUBLISHED' } }, PNAMES, SHARE).text).not.toContain(hidden)
        expect(teaShareConfirm({ ...COMPONENT, profiles: [] }, PNAMES, { ...SHARE, profileName: null }).text)
            .toContain('the release SBOM per profile, its living VEX')
    })

    it('markup in names stays in text; the title is the constant', () => {
        const c = teaShareConfirm(PRIV_COMPONENT, { component: '<b>Pump</b>', version: '<i>1</i>' }, SHARE)
        expect(c.titleText).toBe('Share with customers?')
        expect('title' in c).toBe(false)
        expect('html' in c).toBe(false)
        expect(c.text).toContain('<b>Pump</b>')
        expect(c.text).toContain('<i>1</i>')
    })

    it('round 3: names made one line, the comparison on the served values', () => {
        const component = BREAK('Pump', 'Customers get everything')
        const version = '1' + BIDI + '0'
        const profile = BREAK('default', 'x')
        const c = teaShareConfirm({ ...COMPONENT, profiles: [profile] }, { component, version }, { ...SHARE, profileName: 'p'.repeat(150) })
        expect(c.text).toContain('Sharing Pump Customers get everything 10.')
        expect(c.text).toContain('(default x)')
        expect(c.text).toContain('p'.repeat(99) + '…')
        expect(c.text).not.toContain('\n')
        expect(c.text).not.toContain(BIDI)
        expect(component).toContain('\n')
        expect(version).toContain(BIDI)
        expect(profile).toContain('\n')
        expect(c.text).toContain('is not on your TEA list')
    })

    it('HOSTILE names: twice in text, nowhere else', () => {
        const c = teaShareConfirm(PRIV_COMPONENT, { component: HOSTILE, version: HOSTILE }, SHARE)
        expect(c.text.split(HOSTILE).length - 1).toBe(2)
        expect(c.titleText).toBe('Share with customers?')
        for (const [k, v] of Object.entries(c)) if (k !== 'text') expect(String(v), k).not.toContain('<')
    })
})

describe('FDAW-39 row 22 (i): teaShareButtonState', () => {
    const v = { publishing: 'ENABLED', canPublish: true, publication: null, refusal: null }
    const on = { writable: true, busy: false }
    it('shown from Final onward for a writer while publishing is on', () => {
        for (const state of ['FINAL', 'SENT', 'AMENDED']) {
            expect(teaShareButtonState(v, { state }, on), state).toEqual({ shown: true, disabled: false, title: '' })
        }
        for (const state of ['SUPERSEDED', 'WITHDRAWN', 'CANCELLED', 'SOME_NEW_STATE']) {
            expect(teaShareButtonState(v, { state }, on).shown, state).toBe(false)
        }
        expect(teaShareButtonState(v, null, on).shown).toBe(false)
        expect(teaShareButtonState({ ...v, publishing: 'DISABLED' }, { state: 'FINAL' }, on).shown).toBe(false)
        expect(teaShareButtonState({ ...v, publishing: null }, { state: 'FINAL' }, on).shown).toBe(false)
        expect(teaShareButtonState(null, { state: 'FINAL' }, on).shown).toBe(false)
        expect(teaShareButtonState(v, { state: 'FINAL' }, { writable: false, busy: false }).shown).toBe(false)
    })
    it('disabled with the reason when nothing is published and it cannot be; busy disables', () => {
        expect(teaShareButtonState({ ...v, canPublish: false, refusal: 'LIFECYCLE_TOO_LOW' }, { state: 'FINAL' }, on))
            .toEqual({ shown: true, disabled: true, title: 'This release has not reached the lifecycle its TEA settings require.' })
        expect(teaShareButtonState({ ...v, canPublish: false, refusal: 'ALREADY_PUBLISHED', publication: { state: 'PUBLISHED' } },
            { state: 'FINAL' }, on)).toEqual({ shown: true, disabled: false, title: '' })
        const busy = teaShareButtonState(v, { state: 'FINAL' }, { writable: true, busy: true })
        expect(busy.disabled).toBe(true)
        expect(busy.title).toBe('')
    })
})

describe('FDAW-39 row 22 (j): teaShareRefusalText', () => {
    const TEXTS: Record<string, string> = {
        LIFECYCLE_TOO_LOW: 'This release has not reached the lifecycle its TEA settings require.',
        PROFILE_CONFLICT: "This release's TEA settings conflict between perspectives.",
        PROFILE_NOT_FOUND: 'An SBOM profile named in the TEA settings is not defined for this release.',
        RELEASE_ARCHIVED: 'This release is archived.',
        COMPONENT_ARCHIVED: "This release's component is archived.",
    }
    it('words each refusal, never the server message', () => {
        const msg = 'Release ' + UUID7 + ' is DRAFT'
        for (const [refusal, text] of Object.entries(TEXTS)) {
            expect(teaShareRefusalText({ refusal }), refusal).toBe(text)
            expect(teaShareRefusalText({ refusal, refusalMessage: msg })).toBe(text)
        }
        expect(teaShareRefusalText({ refusal: 'NO_SBOM_ARTIFACTS' })).toBe('Cannot be shared: no sbom artifacts.')
        expect(teaShareRefusalText({ refusal: null })).toBe('Cannot be shared now.')
        for (const refusal of [...Object.keys(TEXTS), 'NO_SBOM_ARTIFACTS', null]) {
            const r = teaShareRefusalText({ refusal, refusalMessage: msg })
            expect(r).not.toContain('3bef7cde')
            expect(r).not.toContain('DRAFT')
        }
    })
})

describe('FDAW-39 row 22 (k): teaShareErrorText', () => {
    it('names the missing permission for the refused right only', () => {
        expect(teaShareErrorText(REFUSED_RIGHT)).toBe('Sharing needs the Publish Externally (TEA) permission on this '
            + 'release. An organization admin can grant it.')
        expect(teaShareErrorText({ errors: [{ message: 'Not authorized to read this release' }] }))
            .toBe('Not authorized to read this release')
        expect(teaShareErrorText({ networkError: { statusCode: 502 } })).toBe(GATEWAY)
        expect(teaActErrorText(REFUSED_RIGHT)).toBe('Not authorized')
    })
})

describe('FDAW-39 row 22 (l): teaOneLine', () => {
    const cp = (...c: number[]) => String.fromCodePoint(...c)
    it('one line, no bidi controls, cut at the maximum', () => {
        expect(teaOneLine('Db')).toBe('Db')
        expect(teaOneLine('  Db \n Evil 1.0: published on its own already\r\n')).toBe('Db Evil 1.0: published on its own already')
        expect(teaOneLine('a' + cp(0x202E) + 'gnp.exe' + cp(0x2066) + 'b' + cp(0x200F) + cp(0x061C))).toBe('agnp.exeb')
        expect(teaOneLine('a' + cp(0) + 'b' + cp(7) + 'c' + cp(0x2028) + 'd\te' + cp(0x2029) + 'f' + cp(0x85) + 'g'))
            .toBe('a b c d e f g')
        expect(teaOneLine('x'.repeat(100))).toBe('x'.repeat(100))
        expect(teaOneLine('x'.repeat(101))).toBe('x'.repeat(99) + '…')
        const emoji = Array.from(teaOneLine(cp(0x1F600).repeat(150)))
        expect(emoji.length).toBe(100)
        expect(emoji[99]).toBe('…')
        expect(Array.from(teaOneLine('x'.repeat(250), 200)).length).toBe(200)
        for (const v of [null, undefined, 7, ' \n ']) expect(teaOneLine(v)).toBe('')
        expect(teaOneLine(HOSTILE)).toBe(HOSTILE)
        expect(TEA_NAME_MAX).toBe(100)
    })
})

describe('FDAW-39 row 50 (a) to (e): the list before the click', () => {
    it('(a) the lines of LIST, in order', () => {
        const p = teaPartialChildren(LIST, ADMIN)
        expect(p.loaded).toBe(true)
        expect(p.total).toBe(9)
        expect(p.notPublished).toBe(7)
        expect(p.lines).toEqual(LIST_LINES)
    })

    it('(b) teaChildReason, every line of the table', () => {
        const r = (outcome: string, more: Record<string, any> = {}) => teaChildReason(child('x', 'X', '1', outcome, more))
        expect(r('SKIPPED_DIRECT')).toBe('published on its own already')
        expect(r('SKIPPED_HIDDEN')).toBe('hidden on TEA by a person')
        expect(r('SKIPPED_OTHER_PRODUCT', { detail: 'published with product Suite 9' })).toBe('published with product Suite 9')
        expect(r('SKIPPED_OTHER_PRODUCT', { detail: null })).toBe('published with another product')
        expect(r('REFUSED', { refusal: 'PUBLISHING_DISABLED' })).toBe('its TEA profile has publishing disabled')
        expect(r('REFUSED', { refusal: 'LIFECYCLE_TOO_LOW' })).toBe('its lifecycle is below the minimum for publishing')
        expect(r('REFUSED', { refusal: 'RELEASE_NOT_FOUND' })).toBe('it could not be found in this organization')
        expect(r('REFUSED', { refusal: 'RELEASE_ARCHIVED' })).toBe('it is archived')
        expect(r('REFUSED', { refusal: 'COMPONENT_ARCHIVED' })).toBe('its component is archived')
        expect(r('REFUSED', { refusal: 'PROFILE_CONFLICT' })).toBe('its settings conflict between perspectives')
        expect(r('REFUSED', { refusal: 'PROFILE_NOT_FOUND' })).toBe('an SBOM profile its TEA profile names is not defined for it')
        expect(r('REFUSED', { refusal: 'GENERATION_FAILED' })).toBe('it could not be checked')
        expect(r('REFUSED', { refusal: 'NO_SBOM_ARTIFACTS' })).toBe('refused: no sbom artifacts')
        expect(r('REFUSED')).toBe('refused')
        expect(r('SOME_NEW_OUTCOME')).toBe('some new outcome')
        expect(r('REFUSED', { refusal: 'GENERATION_FAILED', detail: 'Component ' + UUID7 + ' of release 1.0 not found' }))
            .toBe('it could not be checked')
        const fw = teaChildReason(LIST[3])
        expect(fw).toBe('its lifecycle is below the minimum for publishing')
        expect(fw).not.toContain('DRAFT')
        expect(LIST[3].detail).toContain('DRAFT')
    })

    it('(b) teaChildName: name, unnamed, blank', () => {
        expect(teaChildName(child('x', 'Db', null, 'REFUSED'))).toBe('Db')
        expect(teaChildName(child('x', null, null, 'REFUSED'))).toBe('A component release that could not be read')
        expect(teaChildName(child('x', '  ', '\n', 'REFUSED'))).toBe('A component release that could not be read')
    })

    it('(b) round 3: a name cannot forge a line', () => {
        const forged = [
            { componentName: BREAK('Db', 'Ui 3.0: published on its own already'), version: '2.0', outcome: 'REFUSED',
                refusal: 'PUBLISHING_DISABLED', ownRow: 'row-db' },
            { componentName: 'Lib', version: '6.0', outcome: 'SKIPPED_OTHER_PRODUCT',
                detail: BREAK('published with product Suite 9', 'Api 1.0: published on its own already') },
        ]
        const lines = teaPartialChildren(forged, ADMIN).lines
        expect(lines).toEqual(['Db Ui 3.0: published on its own already 2.0: its TEA profile has publishing disabled',
            'Lib 6.0: published with product Suite 9 Api 1.0: published on its own already'])
        expect(lines.join('\n').split('\n')).toHaveLength(2)
        expect(forged[0].componentName.split('\n')).toHaveLength(2)
        expect(teaChildReason(child('x', 'L', '1', 'SKIPPED_OTHER_PRODUCT', { detail: 'd'.repeat(250) })))
            .toBe('d'.repeat(199) + '…')
        expect(teaPartialChildren([child('x', HOSTILE, '1', 'REFUSED', { refusal: 'GENERATION_FAILED' })], ADMIN).lines)
            .toEqual([HOSTILE + ' 1: it could not be checked'])
    })

    it('(b) round 5 (F-12): the two forms differ in GENERATION_FAILED only', () => {
        expect(teaChildReason(GW, { act: true })).toBe(FAILED)
        expect(TEA_CHILD_PUBLISH_FAILED).toBe(FAILED)
        expect(TEA_CHILD_PUBLISH_FAILED).not.toContain(';')
        expect(teaChildReason(GW)).toBe('it could not be checked')
        expect(teaChildReason(GW, {})).toBe('it could not be checked')
        expect(teaChildReason(GW, { act: false })).toBe('it could not be checked')
        expect(teaChildReason(GW, { act: 1 })).toBe('it could not be checked')
        const others = [
            child('x', 'X', '1', 'SKIPPED_DIRECT'), child('x', 'X', '1', 'SKIPPED_HIDDEN'),
            child('x', 'X', '1', 'SKIPPED_OTHER_PRODUCT', { detail: 'published with product Suite 9' }),
            child('x', 'X', '1', 'SKIPPED_OTHER_PRODUCT'),
            ...['PUBLISHING_DISABLED', 'LIFECYCLE_TOO_LOW', 'RELEASE_NOT_FOUND', 'RELEASE_ARCHIVED', 'COMPONENT_ARCHIVED',
                'PROFILE_CONFLICT', 'PROFILE_NOT_FOUND', 'NO_SBOM_ARTIFACTS', 'PUBLISH_IN_PROGRESS']
                .map(refusal => child('x', 'X', '1', 'REFUSED', { refusal })),
            child('x', 'X', '1', 'REFUSED'), child('x', 'X', '1', 'SOME_NEW_OUTCOME'),
        ]
        for (const c of others) {
            expect(teaChildReason(c, { act: true }), c.outcome + c.refusal).toBe(teaChildReason(c))
            expect(teaChildReason(c, { act: true })).not.toBe(FAILED)
        }
    })

    it('(c) empty, unread and all-published lists', () => {
        expect(teaPartialChildren([], ADMIN)).toEqual({ loaded: true, total: 0, notPublished: 0, lines: [], offer: [], hint: false })
        const unread = { loaded: false, total: 0, notPublished: 0, lines: [], offer: [], hint: false }
        expect(teaPartialChildren(null, ADMIN)).toEqual(unread)
        expect(teaPartialChildren(undefined, ADMIN)).toEqual(unread)
        const two = teaPartialChildren([LIST[0], LIST[8]], ADMIN)
        expect([two.total, two.notPublished, two.lines]).toEqual([2, 0, []])
    })

    it('(d) the offer', () => {
        const zed = child('r-zed', 'Zed', '1.0', 'REFUSED', { refusal: 'PUBLISHING_DISABLED', ownRow: 'rz' })
        const ada = child('r-ada', 'Ada', '2.0', 'REFUSED', { refusal: 'PUBLISHING_DISABLED', ownRow: 'ra' })
        const p = (list: any[], opts: any = ADMIN) => { const r = teaPartialChildren(list, opts); return [r.offer, r.hint] }
        expect(p(LIST)).toEqual([[], true])
        expect(p(OFFERING)).toEqual([[LIST[1]], false])
        expect(p([zed, LIST[0], ada])).toEqual([[zed, ada], false])
        expect(p([zed, LIST[0], ada], { isOrgAdmin: false, componentOf })).toEqual([[], true])
        expect(p([zed, { ...ada, ownRow: null }])).toEqual([[], true])
        expect(p([zed, ada], { isOrgAdmin: true, componentOf: (r: string) => (r === 'r-ada' ? null : 'c') })).toEqual([[], true])
        expect(p([zed, ada, LIST[3]])).toEqual([[], true])
        expect(p([LIST[2], LIST[4]])).toEqual([[], false])
        expect(p([LIST[3]])).toEqual([[], false])
        const zed11 = { ...zed, release: 'r-zed11', version: '1.1' }
        const r = teaPartialChildren([zed, zed11, ada], ADMIN)
        expect(r.offer).toEqual([zed, ada])
        expect(r.lines).toHaveLength(3)
        const nulls = teaPartialChildren([LIST[0], null, LIST[8]], ADMIN)
        expect([nulls.total, nulls.notPublished]).toEqual([2, 0])
    })

    it('(e) the publish dialog with the list', () => {
        const withList = teaPublishConfirm(PRODUCT, PNAMES, teaPartialChildren(LIST, ADMIN))
        expect(withList.text).toBe(HEAD + '\n7 of 9 component releases will not be published with it:\n' + LIST_LINES.join('\n')
            + '\nCustomers still see these components inside the product SBOM; they cannot open the component releases on '
            + "TEA. To publish them too, a component or organization admin turns publishing on in each component's TEA settings.")
        expect(withList.customClass).toEqual({ htmlContainer: 'tea-partial-children' })
        expect('showDenyButton' in withList).toBe(false)

        const offering = teaPublishConfirm(PRODUCT, PNAMES, teaPartialChildren(OFFERING, ADMIN))
        expect(offering.text).toBe(HEAD + '\n2 of 3 component releases will not be published with it:\nDb 2.0: its TEA profile '
            + 'has publishing disabled\nUi 3.0: published on its own already\nCustomers still see these components inside the '
            + 'product SBOM; they cannot open the component releases on TEA. "Turn on for both" turns on TEA publishing in '
            + 'the TEA settings of Db, and it stays on for their later releases; this product is then published.')
        expect(offering.showDenyButton).toBe(true)
        expect(offering.denyButtonText).toBe('Turn on for both')
        expect(offering.customClass).toEqual({ htmlContainer: 'tea-partial-children', denyButton: 'tea-turn-on-both' })
        expect(offering.text).not.toContain(TEA_PARTIAL_HINT)
        expect(withList.text).toContain(TEA_PARTIAL_HINT)

        const six = ['A', 'B', 'C', 'D', 'E', 'F'].map(n => child('r-' + n, n, '1', 'REFUSED', { refusal: 'PUBLISHING_DISABLED', ownRow: n }))
        expect(teaPartialOfferText(six)).toBe('"Turn on for both" turns on TEA publishing in the TEA settings of A, B, C, D, E, '
            + 'and 1 more, and it stays on for their later releases; this product is then published.')
        const ofDb = (b: string) => teaPartialOfferText([child('1', 'Db', '1', 'REFUSED'), child('2', b, '2', 'REFUSED')])
        const once = '"Turn on for both" turns on TEA publishing in the TEA settings of Db, and it stays on for their later '
            + 'releases; this product is then published.'
        expect(ofDb('Db')).toBe(once)
        expect(ofDb(BREAK('Db', ''))).toBe(once)

        const all3 = teaPublishConfirm(PRODUCT, PNAMES, teaPartialChildren([LIST[0], LIST[8], { ...LIST[0], release: 'r-3' }], ADMIN))
        expect(all3.text).toBe(HEAD + 'All 3 of its component releases are expected to be published with it.')
        expect('customClass' in all3).toBe(false)
        expect(teaPublishConfirm(PRODUCT, PNAMES, teaPartialChildren([LIST[0]], ADMIN)).text)
            .toBe(HEAD + 'Its 1 component release is expected to be published with it.')
        expect(teaPublishConfirm(PRODUCT, PNAMES, teaPartialChildren([], ADMIN)).text)
            .toBe(HEAD + 'It lists no component release, so nothing is published with it.')
        expect(teaPublishConfirm(PRODUCT, PNAMES).text)
            .toBe(HEAD + 'Its 9 component releases are published with it unless their own TEA profile forbids it.')
        expect(teaPublishConfirm(PRODUCT, PNAMES, teaPartialChildren(null, ADMIN))).toEqual(teaPublishConfirm(PRODUCT, PNAMES))
        expect(teaPublishConfirm(COMPONENT, PNAMES, teaPartialChildren(LIST, ADMIN)).text).not.toContain('component releases')
        expect(withList.text).toContain('component releases')
    })
})

describe('FDAW-39 row 50 (q): teaTurnOnPublicConfirm', () => {
    it('a PUBLIC row is confirmed in red, whatever the dry-run says', () => {
        expect(teaTurnOnPublicConfirm(PUB, PCP, SAME)).toEqual(PUBCONFIRM)
        expect(teaTurnOnPublicConfirm(PUB, PCP, { publishedReleases: 3 })!.text.endsWith(' 3 published release(s) resolving '
            + 'to this profile become readable by anyone who knows the TEA URL as soon as you save; visibility is live.')).toBe(true)
        expect(teaTurnOnPublicConfirm(PUB, PCP, { publishedReleases: 0, publishedVersions: 0 })!.text.endsWith(' No release is '
            + 'published under this profile yet; every future publication under it will be public. Every version published '
            + 'under it later will be public, with every document in it (SBOMs, VEX documents, device support statements, '
            + 'published artifacts).')).toBe(true)
        expect(teaTurnOnPublicConfirm(PUB, PCP, null)!.text.endsWith(' No release is published under this profile yet; every '
            + 'future publication under it will be public.')).toBe(true)
        expect(teaTurnOnPublicConfirm(PUB, { ...PCP, visibility: 'PRIVATE' }, SAME)).toBeNull()
        expect(teaTurnOnPublicConfirm({ ...PUB, componentName: null }, PCP, SAME)!.text.startsWith('The TEA settings of 1.0 are public')).toBe(true)
        const broken = teaTurnOnPublicConfirm({ ...PUB, componentName: BREAK('Pub', 'x') }, PCP, SAME)!.text
        expect(broken.startsWith('The TEA settings of Pub x are public')).toBe(true)
        expect(broken).not.toContain('\n')
        expect(TEA_TURN_ON_PUBLIC_TITLE).toBe('Publish this component publicly?')
        expect(TEA_TURN_ON_PUBLIC_BUTTON).toBe('Publish publicly')
    })
})

const stopOf = (kind: string, name: string, refusal = '') => ({ kind, child: name, text: teaTurnOnStopSentence(kind, name, refusal) })

describe('FDAW-39 row 50 (j): teaTurnOnStopText, the progress dialogs and the not-started text', () => {
    it('(j) the stop texts', () => {
        expect(teaTurnOnStopText({ saved: ['Zed 1.0'], stop: stopOf('declined', 'Ada 2.0') }))
            .toBe('Stopped at Ada 2.0: its confirmation was declined. Publishing stays turned on for Zed 1.0. Nothing was published.')
        expect(teaTurnOnStopText({ saved: [], stop: stopOf('changed', 'Zed 1.0') }))
            .toBe('The TEA settings of Zed 1.0 changed since this dialog opened, so nothing was saved for it. Nothing was published.')
        expect(teaTurnOnStopText({ saved: ['Zed 1.0', 'Ada 2.0'], stop: stopOf('refused', 'X 1', 'boom') }))
            .toBe('boom Publishing stays turned on for Zed 1.0, Ada 2.0. Nothing was published.')
        expect(teaTurnOnStopText({ saved: ['Zed 1.0'], stop: stopOf('unanswered', 'Ada 2.0') }))
            .toBe('No answer arrived for saving the TEA settings of Ada 2.0, so publishing may or may not be turned on for it. '
                + 'Open its TEA settings to check. Publishing stays turned on for Zed 1.0. Nothing was published.')
        expect(teaTurnOnStopSentence('unanswered', 'Pub 1.0')).toBe(NOANSWER)
    })

    it('the progress dialogs, whole, and the not-started text', () => {
        expect(teaChildrenReadProgress()).toEqual({ titleText: 'Checking the component releases…',
            text: 'Reading what a publish would do with each component release of this product. The publish has not started yet.',
            allowOutsideClick: false, allowEscapeKey: false, showConfirmButton: false })
        expect(teaTurnOnProgress()).toEqual({ titleText: 'Turning publishing on…',
            text: 'Checking and saving the TEA settings of the listed components. The publish of this product release has not started yet.',
            allowOutsideClick: false, allowEscapeKey: false, showConfirmButton: false })
        expect(teaNotStartedText([])).toBe('Another TEA act on this release is still running, so this one was not started.')
        expect(teaNotStartedText(['Pub 1.0'])).toBe('Another TEA act on this release is still running, so this one was not '
            + 'started. Publishing stays turned on for Pub 1.0.')
        expect(teaNotStartedText(['Pub 1.0'])).not.toContain('Nothing was published')
    })
})

describe('FDAW-39 row 50 (v): teaServerAnswered', () => {
    it('true only for an error the server answered', () => {
        expect(teaServerAnswered(ANSWERED('boom'))).toBe(true)
        expect(teaServerAnswered({ errors: [REFUSED_RIGHT.errors[1]] })).toBe(false)
        expect(teaServerAnswered(ANSWERED('  '))).toBe(false)
        expect(teaServerAnswered(new TypeError('Failed to fetch'))).toBe(false)
        expect(teaServerAnswered(null)).toBe(false)
    })
})

describe('FDAW-39 row 53: the sweep', () => {
    it('positive controls: the fixtures carry what the sweep forbids', () => {
        expect(LIST[3].detail).toMatch(WORD_RE)
        expect(LIST[6].release).toMatch(UUID_RE)
        expect(LIST[1].refusal).toMatch(ENUM_RE)
        expect("Release Pump 1.2.0 is ASSEMBLED; the TEA profile's minimum lifecycle is GENERAL_AVAILABILITY").toMatch(ENUM_RE)
    })

    it('(a) the lines and the dialog texts without HEAD', () => {
        for (const l of teaPartialChildren(LIST, ADMIN).lines) swept(l, l)
        for (const list of [LIST, OFFERING]) {
            const text = teaPublishConfirm(PRODUCT, PNAMES, teaPartialChildren(list, ADMIN)).text
            expect(text.startsWith(HEAD)).toBe(true)
            swept(text.slice(HEAD.length))
        }
    })

    it('(b) teaChildReason, both forms, every row and unknown values', () => {
        const rows = [
            child('x', 'X', '1', 'SKIPPED_DIRECT'), child('x', 'X', '1', 'SKIPPED_HIDDEN'),
            LIST[5], child('x', 'X', '1', 'SKIPPED_OTHER_PRODUCT'),
            ...['PUBLISHING_DISABLED', 'LIFECYCLE_TOO_LOW', 'RELEASE_NOT_FOUND', 'RELEASE_ARCHIVED', 'COMPONENT_ARCHIVED',
                'PROFILE_CONFLICT', 'PROFILE_NOT_FOUND', 'GENERATION_FAILED', 'NO_SBOM_ARTIFACTS', 'SOME_NEW_REFUSAL']
                .map(refusal => child('x', 'X', '1', 'REFUSED', { refusal, detail: LIST[3].detail })),
            child('x', 'X', '1', 'REFUSED'), child('x', 'X', '1', 'SOME_NEW_OUTCOME'),
        ]
        for (const c of rows) {
            swept(teaChildReason(c), String(c.refusal ?? c.outcome))
            swept(teaChildReason(c, { act: true }), String(c.refusal ?? c.outcome))
        }
    })

    it('(c) the share dialog', () => {
        const priv = teaShareConfirm(PRIV_COMPONENT, PNAMES, SHARE)
        swept(priv.text)
        swept(priv.titleText)
        const hidden = teaShareConfirm({ ...COMPONENT, profiles: ['default'], publication: { state: 'HIDDEN' } }, PNAMES, SHARE)
        swept(hidden.text)
        swept(hidden.titleText)
        const v = { ...PRODUCT, cascades: false, effectiveVisibility: 'PUBLIC', profiles: ['default', 'fda'],
            publicUrl: 'https://tea.example/v1.0.0/productRelease/' + UUID7 }
        const pub = teaShareConfirm(v, PNAMES, SHARE).text
        const url = teaPublicUrlText(v)
        expect(pub.split(url).length - 1).toBe(1)
        expect(pub.endsWith('without a key: ' + url)).toBe(true)
        swept(pub.replace(url, ''))
    })

    it('(d) the texts this child adds', () => {
        const texts: string[] = [
            ...['LIFECYCLE_TOO_LOW', 'PROFILE_CONFLICT', 'PROFILE_NOT_FOUND', 'RELEASE_ARCHIVED', 'COMPONENT_ARCHIVED',
                'NO_SBOM_ARTIFACTS', null].map(refusal => teaShareRefusalText({ refusal })),
            TEA_SHARE_NOT_AUTHORIZED,
            teaTurnOnStopText({ saved: ['Zed 1.0'], stop: stopOf('declined', 'Ada 2.0') }),
            teaTurnOnStopText({ saved: [], stop: stopOf('changed', 'Zed 1.0') }),
            teaTurnOnStopText({ saved: ['Zed 1.0'], stop: stopOf('unanswered', 'Ada 2.0') }),
            FDA_PROSE_TEA_NOTE,
            teaPartialOfferText([LIST[1], { ...LIST[1], componentName: 'Ui', ownRow: 'row-ui' }]),
            TEA_PARTIAL_HINT, TEA_CHILDREN_READ_FAILED,
            teaChildrenReadProgress().titleText, teaChildrenReadProgress().text,
            TEA_AUTO_PUBLISH_PAUSED_OWN('component'), TEA_AUTO_PUBLISH_PAUSED_INHERITED('organization', 'component'),
            teaAutoPublishHelp('SAAS'), teaAutoPublishHelp('OSS'), ...TEA_AUTO_PUBLISH_OPTIONS.map(o => o.label),
            TEA_TURN_ON_TEXT, TEA_START_REMOVAL_OBJECT('component', 'organization'),
            TEA_START_REMOVAL_MEMBERS(2, 'zeta, alpha'), TEA_START_MEMBERSHIP('component', ' (adding Payments)', 'perspective Payments'),
            TEA_PUBLIC_CONFIRM_COUNTED(3, 7), TEA_PUBLIC_CONFIRM_NONE_YET,
            PUBTEXT, TEA_ACTION_COUNTED(3, 7), TEA_GLOBAL_ACTION_COUNTED(3, 7), TEA_ACTION_NONE_YET, TEA_GLOBAL_ACTION_NONE_YET,
            TEA_TURN_ON_PUBLIC_TITLE, TEA_TURN_ON_PUBLIC_BUTTON,
            NOANSWER, teaNotStartedText(['Zed 1.0']), teaTurnOnProgress().titleText, teaTurnOnProgress().text, FAILED,
        ]
        for (const t of texts) swept(t, t)
    })

    const PUBLISHED_API = { ...LIST[0], outcome: 'PUBLISHED' }
    const R = { outcome: 'PUBLISHED', collection: { version: 4 }, publication: { aggregateArtifacts: [] },
        artifacts: [{ status: 'PUBLISHED' }, { status: 'SKIPPED_NO_CHECKSUM' }, { status: 'SKIPPED_SOME_NEW_REASON' }],
        children: [PUBLISHED_API, LIST[1], LIST[2], LIST[3], LIST[5], LIST[6], GW] }
    const REASONS = 'Ui 3.0: published on its own already; Lib 6.0: published with product Suite 9; Db 2.0: its TEA '
        + 'profile has publishing disabled; Fw 4.0: its lifecycle is below the minimum for publishing; A component release '
        + 'that could not be read: it could not be found in this organization; Gw 8.0: publishing it on TEA failed (the '
        + 'server log has the cause)'

    it('(f) the share\'s worded result, and its control', () => {
        const worded = teaPublishSummary(R, { worded: true })
        expect(worded).toBe('Published: collection version 4; 1 artifacts published, 2 skipped (1 no checksum, 1 skipped some '
            + 'new reason); 1 component releases published, 2 skipped, 4 refused. Ui 3.0: published on its own already; Lib '
            + '6.0: published with product Suite 9; Db 2.0: its TEA profile has publishing disabled; Fw 4.0: its lifecycle is '
            + 'below the minimum for publishing; A component release that could not be read: it could not be found in this '
            + 'organization; Gw 8.0: publishing it on TEA failed (the server log has the cause)')
        swept(worded)
        expect(worded).not.toContain('could not be checked')
        expect(worded).not.toContain('Connection refused')
        expect(teaChildReason(GW)).toBe('it could not be checked')
        expect(teaPartialChildren([GW], ADMIN).lines).toEqual(['Gw 8.0: it could not be checked'])
        const plain = teaPublishSummary(R)
        expect(plain).toMatch(ENUM_RE)
        expect(plain).toMatch(UUID_RE)
        expect(plain).toMatch(WORD_RE)
    })

    it('(f) round 4: an outcome the UI does not know is counted and named', () => {
        const R2 = { ...R, children: [...R.children, null, { release: 'n', componentName: 'New', version: '1', outcome: 'SOME_NEW_OUTCOME' }] }
        const worded = teaPublishSummary(R2, { worded: true })
        expect(worded).toBe('Published: collection version 4; 1 artifacts published, 2 skipped (1 no checksum, 1 skipped some '
            + 'new reason); 1 component releases published, 2 skipped, 4 refused, 1 other. Ui 3.0: published on its own '
            + 'already; Lib 6.0: published with product Suite 9; Db 2.0: its TEA profile has publishing disabled; Fw 4.0: its '
            + 'lifecycle is below the minimum for publishing; A component release that could not be read: it could not be '
            + 'found in this organization; Gw 8.0: publishing it on TEA failed (the server log has the cause); New 1: some new outcome')
        swept(worded)
        expect(teaPublishSummary(R, { worded: true })).not.toContain(' other')
    })

    it('(f) round 5: a release that is itself unchanged still names its children', () => {
        const RU = { ...R, outcome: 'UNCHANGED' }
        const worded = teaPublishSummary(RU, { worded: true })
        expect(worded).toBe('This release is unchanged since collection version 4; 1 component releases published, 2 skipped, '
            + '4 refused. Ui 3.0: published on its own already; Lib 6.0: published with product Suite 9; Db 2.0: its TEA '
            + 'profile has publishing disabled; Fw 4.0: its lifecycle is below the minimum for publishing; A component release '
            + 'that could not be read: it could not be found in this organization; Gw 8.0: publishing it on TEA failed (the '
            + 'server log has the cause)')
        expect(worded).toBe('This release is unchanged since collection version 4; 1 component releases published, 2 skipped, 4 refused. ' + REASONS)
        swept(worded)
        expect(teaPublishSummary(RU)).toBe('Nothing changed since collection version 4')
        for (const children of [[], [null], undefined]) {
            const r: any = { outcome: 'UNCHANGED', collection: { version: 4 } }
            if (children !== undefined) r.children = children
            expect(teaPublishSummary(r, { worded: true })).toBe('Nothing changed since collection version 4')
        }
        expect(teaPublishSummary({ outcome: 'UNCHANGED', collection: { version: 4 }, children: [PUBLISHED_API] }, { worded: true }))
            .toBe('This release is unchanged since collection version 4; 1 component releases published, 0 skipped, 0 refused')
        expect(teaPublishSummary({ ...R, artifacts: [{ status: 'PUBLISHED' }, { status: 'SKIPPED_SOME_OTHER_REASON' }] }, { worded: true }))
            .toContain('1 skipped (1 skipped some other reason)')
    })
})
