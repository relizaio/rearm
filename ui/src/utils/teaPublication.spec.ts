import { describe, it, expect } from 'vitest'
import {
    teaPublishConfirm, teaHideConfirm, teaPublicUrlText, teaPublishSummary, teaCollectionRows, teaEntryRows,
    teaPublicationBanner, teaPublicationHistoryText,
} from './teaPublication'

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
        expect(cascading.text).toContain('Its 4 component releases are published with it.')
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

    it('UNCHANGED names the version', () => {
        expect(teaPublishSummary({ outcome: 'UNCHANGED', publication: publication({ latestVersion: 3 }),
            collection: { version: 3, entries: [] }, artifacts: [], children: [] }))
            .toBe('Nothing changed since collection version 3')
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
    })
})

describe('editionPermissionFunctions (PUBLISH_EXTERNALLY only where TEA is served)', () => {
    it('offers PUBLISH_EXTERNALLY on Pro and drops it on CE, keeping every other function', async () => {
        const { editionPermissionFunctions } = await import('./teaProfile')
        const all = ['RESOURCE', 'ARTIFACT_DOWNLOAD', 'PUBLISH_EXTERNALLY', 'AGENT']
        expect(editionPermissionFunctions(all, 'SAAS')).toEqual(all)
        expect(editionPermissionFunctions(all, 'OSS')).toEqual(['RESOURCE', 'ARTIFACT_DOWNLOAD', 'AGENT'])
    })
})
