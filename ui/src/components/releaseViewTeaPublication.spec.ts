import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'

/**
 * TEA-5 (design 4.10 test 58): the release page's TEA actions, banner, section and History branch,
 * read from the SFC source with comments stripped (the narrow style of
 * releaseViewGeneratedArtifacts.spec.ts). The behaviour lives in utils/teaPublication.spec.ts;
 * this pins the wiring: the import, the header controls, where the section and the banner sit, the
 * History branch, and the gated query that loads the view.
 */
const read = (rel: string) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8')
const strip = (source: string) => source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .split('\n')
    .map(line => line.replace(/(^|[^:])\/\/.*$/, '$1'))
    .join('\n')

const source = read('./ReleaseView.vue')
const code = strip(source)
const template = code.slice(0, code.indexOf('<script'))

describe('ReleaseView TEA publication', () => {
    it('imports the helpers', () => {
        const helpers = ['teaPublishConfirm', 'teaHideConfirm', 'teaPublicUrlText', 'teaPublishSummary', 'teaCollectionRows',
            'teaEntryRows', 'teaPublicationBanner', 'teaPublicationHistoryText', 'teaConcealedText', 'teaPublishedWithText',
            'teaActErrorText']
        const imp = source.match(/import\s+\{([^}]*)\}\s+from\s+'@\/utils\/teaPublication'/)
        expect(imp).not.toBeNull()
        for (const h of helpers) expect(imp![1], h).toMatch(new RegExp('\\b' + h + '\\b'))
    })

    it('holds tea-publish, tea-republish, tea-hide and tea-badge in the header icon row, after the Export icon', () => {
        const exportIcon = template.indexOf('title="Export Release xBOM"')
        const headerEnd = template.indexOf('</n-gi>', exportIcon)
        expect(exportIcon).toBeGreaterThan(0)
        const header = template.slice(exportIcon, headerEnd)
        for (const id of ['tea-publish', 'tea-republish', 'tea-hide', 'tea-badge']) {
            expect(header, id).toContain(`data-testid="${id}"`)
        }
        expect(header).toContain('<template v-if="teaView && !isDocumentRound">')
        expect(header).toMatch(/:title="teaView\.canPublish \? 'Publish on TEA' : \(teaView\.refusalMessage/)
    })

    it('renders the section after the Generated artifacts container', () => {
        const generated = template.indexOf('<h3>Generated artifacts</h3>')
        const section = template.indexOf('data-testid="tea-section"')
        expect(generated).toBeGreaterThan(0)
        expect(section).toBeGreaterThan(generated)
        expect(template).toContain('<div class="container" v-if="teaView?.publication && !isDocumentRound" data-testid="tea-section">')
        expect(template.slice(section)).toMatch(/^[^\n]*\n\s*<h3>Transparency Exchange \(TEA\)<\/h3>/)
    })

    it('puts tea-public-banner under the title block, before the tabs', () => {
        const title = template.indexOf('<div v-if="release && release.componentDetails">')
        const grid = template.indexOf('</n-grid>', title)
        const banner = template.indexOf('data-testid="tea-public-banner"')
        expect(banner).toBeGreaterThan(grid)
        expect(banner).toBeLessThan(template.indexOf('<n-tabs'))
        expect(template).toMatch(/<n-alert v-if="teaBanner" data-testid="tea-public-banner" type="error" :closable="false"/)
    })

    it('the section names why it is concealed and, for a cascade child, the product in place of the profile (round 2, 80)', () => {
        const section = template.slice(template.indexOf('data-testid="tea-section"'))
        const body = section.slice(0, section.indexOf('<n-data-table'))
        expect(body).toContain('<div v-if="teaConcealedLine" data-testid="tea-concealed">{{ teaConcealedLine }}</div>')
        expect(body).toMatch(/<div v-if="teaView\.publication\.cascadeOf" data-testid="tea-published-with">\{\{ teaPublishedWithText\(teaView\.publication\) \}\}<\/div>\s*<div v-else><strong>Profile: <\/strong>/)
        expect(code).toMatch(/const teaConcealedLine[^=]*= computed\(\(\) => teaConcealedText\(teaView\.value\?\.publication,\s*updatedRelease\.value\?\.lifecycle\)\)/)
        const queries = strip(read('../utils/graphqlQueries.ts'))
        const fields = queries.slice(queries.indexOf('const TEA_PUBLICATION_FIELDS_GQL'))
        const block = fields.slice(0, fields.indexOf('`', fields.indexOf('`') + 1))
        expect(block).toContain('concealedBecause')
        expect(block).toContain('cascadeOfLabel')
    })

    it('the publish and hide catch blocks say what teaActErrorText says, then reload the section (round 4, 95)', () => {
        for (const fn of ['async function runTeaPublish', 'async function hideOnTea']) {
            const start = code.indexOf(fn)
            expect(start, fn).toBeGreaterThan(0)
            const body = code.slice(start, code.indexOf('\n}\n', start))
            expect(body, fn).toMatch(/catch \(err: any\) \{\s*Swal\.fire\('Error!', teaActErrorText\(err\), 'error'\)\s*\}\s*await loadTeaPublication\(\)/)
            expect(body, fn).not.toContain('parseGraphQLError')
        }
    })

    it('has a TEA_PUBLICATION branch in the History renderer', () => {
        expect(code).toMatch(/row\.rus === 'TEA_PUBLICATION'[\s\S]{0,80}teaPublicationHistoryText\(row, teaView\.value\)/)
    })
})

describe('the TEA view rides its own gated query, never the shared release fragments', () => {
    const queries = strip(read('../utils/graphqlQueries.ts'))

    it.each(['singleReleaseDataNoParent', 'singleReleaseProductNoParent'])('%s does not select teaPublication', (fragment) => {
        const start = queries.indexOf(`const ${fragment} =`)
        expect(start).toBeGreaterThanOrEqual(0)
        const end = queries.indexOf('`', queries.indexOf('`', start) + 1)
        expect(queries.slice(start, end)).not.toContain('teaPublication')
    })

    it('loadTeaPublication is gated by teaProfilesAvailable before it sends the query', () => {
        const load = code.slice(code.indexOf('async function loadTeaPublication'))
        const gate = load.indexOf('if (!teaProfilesAvailable(myUser?.installationType)) return')
        const query = load.indexOf('graphqlQueries.ReleaseTeaPublicationViewGql')
        expect(gate).toBeGreaterThan(0)
        expect(query).toBeGreaterThan(gate)
        expect(load.slice(0, load.indexOf('\n}\n'))).toContain('if (seq !== teaViewLoadSeq) return')
    })

    it('starts the load from fetchRelease and reloads after each mutation', () => {
        const fetch = code.slice(code.indexOf('async function fetchRelease ()'))
        expect(fetch.slice(0, fetch.indexOf('\n}\n'))).toContain('loadTeaPublication()')
        for (const fn of ['async function runTeaPublish', 'async function hideOnTea']) {
            const body = code.slice(code.indexOf(fn))
            expect(body.slice(0, body.indexOf('\n}\n')), fn).toContain('await loadTeaPublication()')
        }
    })

    it('graphqlQueries carries the four TEA publication documents', () => {
        for (const doc of ['ReleaseTeaPublicationViewGql', 'PublishReleaseOnTeaGql', 'RepublishReleaseOnTeaGql', 'HideReleaseOnTeaGql']) {
            expect(queries).toMatch(new RegExp('\\b' + doc + ': '))
        }
        expect(queries).toContain('releaseTeaPublicationView(release: $release)')
        expect(queries).toContain('publishReleaseOnTea(release: $release, comment: $comment)')
        expect(queries).toContain('republishReleaseOnTea(release: $release, comment: $comment)')
        expect(queries).toContain('hideReleaseOnTea(release: $release, comment: $comment)')
    })
})
