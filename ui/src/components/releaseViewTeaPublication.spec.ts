import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'

/**
 * TEA-5 (design 4.10 test 58): the release page's TEA actions, banner, TEA tab and History branch,
 * read from the SFC source with comments stripped (the narrow style of
 * releaseViewGeneratedArtifacts.spec.ts). The behaviour lives in utils/teaPublication.spec.ts;
 * this pins the wiring: the import, the header controls, the TEA tab and where the banner sits, the
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
        expect(header).toContain('<template v-if="teaView && !isDocumentRound && !teaActing">')
        expect(header).toMatch(/:title="teaView\.canPublish \? 'Publish on TEA' : \(teaView\.refusalMessage/)
    })

    it('renders the section in its own TEA tab, gated on publishing ENABLED, and no longer in Components', () => {
        const line = '<n-tab-pane v-if="teaTabShown" name="tea" tab="TEA" data-testid="release-tea-tab">'
        const pane = template.indexOf(line)
        expect(pane).toBeGreaterThan(0)
        const body = template.slice(pane, template.indexOf('</n-tab-pane>', pane))
        expect(body).toContain('<div class="container" v-if="teaView.publication" data-testid="tea-section">')
        expect(body.slice(body.indexOf('data-testid="tea-section"'))).toMatch(/^[^\n]*\n\s*<h3>Transparency Exchange \(TEA\)<\/h3>/)
        expect(code).toMatch(/const teaTabShown: ComputedRef<boolean> = computed\(\(\) => teaView\.value\?\.publishing === 'ENABLED' && !isDocumentRound\.value\)/)
        const components = template.slice(template.indexOf('<n-tab-pane name="components" tab="Components">'),
            template.indexOf('name="underlyingArtifacts"'))
        expect(components.length).toBeGreaterThan(0)
        expect(components).not.toContain('tea-section')
        expect(components).not.toContain('Transparency Exchange')
        // The view selects the switch the tab reads; the CORE document of the drift fallback does not.
        const queries = strip(read('../utils/graphqlQueries.ts'))
        const doc = (name: string) => {
            const at = queries.indexOf('query ' + name + '(')
            expect(at, name).toBeGreaterThan(0)
            return queries.slice(at, queries.indexOf('}`', at)).split('\n').map(l => l.trim())
        }
        expect(doc('ReleaseTeaPublicationView')).toContain('publishing')
        expect(doc('ReleaseTeaPublicationViewCore')).not.toContain('publishing')
    })

    it('says the release is not published, with the refusal or the publish hint, when there is no publication', () => {
        const pane = template.slice(template.indexOf('name="tea" tab="TEA"'))
        const body = pane.slice(0, pane.indexOf('</n-tab-pane>'))
        const empty = body.slice(body.indexOf('<div class="container" v-else data-testid="tea-not-published">'))
        expect(body.indexOf('data-testid="tea-not-published"')).toBeGreaterThan(body.indexOf('data-testid="tea-section"'))
        expect(empty).toContain('<p>This release is not published on TEA.</p>')
        expect(empty).toContain('<p v-if="!teaView.canPublish && teaView.refusalMessage" data-testid="tea-not-published-reason" class="text-muted">{{ teaView.refusalMessage }}</p>')
        expect(empty).toMatch(/<p v-else-if="teaView\.canPublish" data-testid="tea-not-published-hint"[^>]*>[^<]*publish button[^<]*<\/p>/)
    })

    it('a hidden publication says so in a line of its own', () => {
        const section = template.slice(template.indexOf('data-testid="tea-section"'))
        expect(section.slice(0, section.indexOf('<n-data-table'))).toContain(
            '<div v-if="teaView.publication.state === \'HIDDEN\'" data-testid="tea-hidden"><strong>Hidden from TEA:</strong>')
    })

    it('the badge opens the TEA tab, and an open TEA tab that goes away falls back to Components', () => {
        const badge = template.slice(template.indexOf('data-testid="tea-badge"'))
        expect(badge.slice(0, badge.indexOf('</span>'))).toContain('@click="openTeaTab"')
        expect(code).not.toContain('scrollToTeaSection')
        const open = code.slice(code.indexOf('function openTeaTab'))
        expect(open.slice(0, open.indexOf('\n}\n'))).toContain("if (teaTabShown.value) activeTab.value = 'tea'")
        expect(code).toMatch(/watch\(teaTabShown, \(shown\) => \{\s*if \(!shown && activeTab\.value === 'tea'\) activeTab\.value = 'components'\s*\}\)/)
    })

    it('re-publishes with the WorldUpload icon, titled for a new version only on change', () => {
        const at = template.indexOf('data-testid="tea-republish"')
        const icon = template.slice(template.lastIndexOf('<Icon', at), template.indexOf('</Icon>', at))
        expect(icon).toContain('title="Re-publish on TEA (a new version only if anything changed)"')
        expect(icon).toContain('<WorldUpload/>')
        expect(icon).not.toContain('<Refresh/>')
        expect(source).toMatch(/import \{[^}]*\bWorldUpload\b[^}]*\} from '@vicons\/tabler'/)
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

    it('the publish and hide acts go through withTeaProgress, whose catch says what teaActErrorText says, then reloads the section (round 4, 95)', () => {
        for (const fn of ['async function runTeaPublish', 'async function hideOnTea']) {
            const start = code.indexOf(fn)
            expect(start, fn).toBeGreaterThan(0)
            const body = code.slice(start, code.indexOf('\n}\n', start))
            expect(body, fn).toContain('await withTeaProgress(')
            expect(body, fn).not.toContain('parseGraphQLError')
        }
        const start = code.indexOf('async function withTeaProgress')
        const body = code.slice(start, code.indexOf('\n}\n', start))
        expect(body).toMatch(/catch \(err: any\) \{\s*await Swal\.fire\('Error!', teaActErrorText\(err\), 'error'\)\s*\} finally \{\s*teaActing\.value = null\s*\}\s*await loadTeaPublication\(\)/)
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

    it('falls back to the CORE view (no publishing, so no TEA tab) where the backend rejects the full one', () => {
        const load = code.slice(code.indexOf('async function loadTeaPublication'))
        const body = load.slice(0, load.indexOf('\n}\n'))
        expect(body).toContain('await loadWithSchemaDriftFallback(graphqlClient as any, {')
        expect(body).toContain('coreQuery: graphqlQueries.ReleaseTeaPublicationViewCoreGql')
        expect(body).toContain('skipFull: isDriftRejected(fullQuery)')
        expect(body).toContain('if (degraded) markDriftRejected(fullQuery)')
    })

    it('starts the load from fetchRelease and reloads after each mutation', () => {
        const fetch = code.slice(code.indexOf('async function fetchRelease ()'))
        expect(fetch.slice(0, fetch.indexOf('\n}\n'))).toContain('loadTeaPublication()')
        for (const fn of ['async function runTeaPublish', 'async function hideOnTea']) {
            const body = code.slice(code.indexOf(fn))
            expect(body.slice(0, body.indexOf('\n}\n')), fn).toContain('await withTeaProgress(')
        }
        {
            const body = code.slice(code.indexOf('async function withTeaProgress'))
            expect(body.slice(0, body.indexOf('\n}\n'))).toContain('await loadTeaPublication()')
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

describe('ReleaseView waits for a TEA act with a spinner', () => {
    const rv = read('./ReleaseView.vue')
    it('runs publish, re-publish and hide through withTeaProgress, one at a time', () => {
        expect(rv).toContain("await withTeaProgress(act, view, async () => {")
        expect(rv).toContain("await withTeaProgress('hide', view, async () => {")
        expect(rv).toContain('if (teaActing.value) return')
        expect(rv).toContain('Swal.fire({ ...teaActProgress(act, view), didOpen: () => Swal.showLoading() })')
    })
    it('shows the header spinner instead of the TEA buttons while an act runs', () => {
        expect(rv).toContain('<span v-if="teaActing" data-testid="tea-acting"')
        expect(rv).toContain('<template v-if="teaView && !isDocumentRound && !teaActing">')
    })
})

describe('ReleaseView reads the narrative default of the release\'s own organization', () => {
    const rv = read('./ReleaseView.vue')
    it('uses the selected org only when it is the release\'s org, else reads that org', () => {
        expect(rv).toContain('if (org && org === store.getters.myorg?.uuid) return store.getters.myorg?.settings?.fdaAssessmentNarrative')
        expect(rv).toContain("return releaseOrgNarrative.value.org === org ? releaseOrgNarrative.value.text : ''")
        expect(rv).toContain('organizations { uuid settings { fdaAssessmentNarrative } }')
        expect(rv).toContain('    loadReleaseOrgNarrative()')
    })
})
