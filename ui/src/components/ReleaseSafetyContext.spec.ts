// @vitest-environment happy-dom
//
// The release Safety Context tab: a row names the broader level it overrides, and a level
// carried from a previous package version is marked with that version. On a product release the
// tab is the device view: a "Release" column, the summary card and completeness banners.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const query = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query } }))
vi.mock('vue-router', () => ({ useRoute: () => ({ params: {} }), RouterLink: { props: ['to'], template: '<a :href="to"><slot/></a>' } }))
vi.mock('sweetalert2', () => ({ default: { fire: vi.fn() } }))
const { default: ReleaseSafetyContext } = await import('./ReleaseSafetyContext.vue')

const tooltip = { template: '<span><slot name="trigger"/><slot/></span>' }

function row (over: any) {
    return {
        uuid: 'r1', kind: 'RECORD', org: 'org-1', recordStatus: 'ACTIVE', releaseUuid: 'rel-1', scope: 'RELEASE',
        componentPurl: 'pkg:generic/acme/pump@1.1.0', schemeKey: 'IEC-62304', levelRaw: 'Class B',
        levelNormalized: 'Class B', sourceRedacted: false, appliesToRelease: 'rel-1', ...over
    }
}

function releaseView (rows: any[], over: any = {}) {
    return {
        release: 'rel-1', productView: false, rows, totalRows: rows.length, summary: [], constituentReleaseCount: 1,
        evaluatedReleaseCount: 1, hiddenReleaseCount: 0, hiddenLevelCount: 0, pendingInventoryReleaseCount: 0,
        complete: true, incompleteReasons: [], ...over
    }
}

async function mounted (rows: any[], over: any = {}) {
    query.mockResolvedValue({ data: { getSafetyContextReleaseView: releaseView(rows, over) } })
    const w = mount(ReleaseSafetyContext, {
        props: { orgUuid: 'org-1', releaseUuid: 'rel-1' },
        global: { stubs: { NTooltip: tooltip, Tooltip: tooltip, SafetyContextProposals: true, SafetyContextSourceModal: true } }
    })
    await flushPromises()
    return w
}

describe('ReleaseSafetyContext', () => {
    beforeEach(() => { query.mockReset() })

    it('names the level a row overrides', async () => {
        const w = await mounted([row({ overridesScope: 'COMPONENT', overridesLevel: 'Class C' })])
        expect(w.text()).toContain('overrides Component: Class C')
    })

    it('marks a level carried from a previous package version', async () => {
        const w = await mounted([row({
            carried: true, componentPurl: 'pkg:generic/acme/pump@1.0.0', shownForPurl: 'pkg:generic/acme/pump@1.1.0'
        })])
        expect(w.text()).toContain('carried from 1.0.0')
        expect(w.text()).toContain('declared for pkg:generic/acme/pump@1.0.0; no level declared yet for pkg:generic/acme/pump@1.1.0')
    })

    it('shows neither for a plain row', async () => {
        const w = await mounted([row({})])
        expect(w.text()).not.toContain('overrides Component')
        expect(w.text()).not.toContain('carried from ')
    })

    it('does not wrap a plain scope cell with no override or cross-release link', async () => {
        const w = await mounted([row({})])
        const cell = w.findAll('[data-col-key="scope"]')[1]
        expect(cell.html()).not.toContain('<span')
    })

    it('marks most stringent only when several non-carried active levels share the component and scheme', async () => {
        const w = await mounted([
            row({ uuid: 'r1', componentMostConservative: true }),
            row({ uuid: 'r2', carried: true, shownForPurl: 'pkg:generic/acme/pump@1.1.0' })
        ])
        const cell = w.findAll('[data-col-key="levelRaw"]')[1]
        expect(cell.text()).not.toContain('most stringent')
    })

    it('does not mark most stringent for a lone active level even when componentMostConservative is set', async () => {
        const w = await mounted([row({ componentMostConservative: true })])
        const cell = w.findAll('[data-col-key="levelRaw"]')[1]
        expect(cell.text()).not.toContain('most stringent')
    })

    it('falls back to the raw scope value for a scope with no label', async () => {
        const w = await mounted([row({ scope: 'UNKNOWN_SCOPE' })])
        expect(w.text()).toContain('UNKNOWN_SCOPE')
    })

    it('strips only the version from the carried purl, not a leading empty host', async () => {
        const w = await mounted([row({ carried: true, componentPurl: '@1.0.0', shownForPurl: 'pkg:generic/acme/pump@1.1.0' })])
        expect(w.text()).toContain('carried from 1.0.0')
    })

    it('strips qualifiers and the subpath from the carried purl version', async () => {
        const w = await mounted([row({
            carried: true, componentPurl: 'pkg:generic/acme/pump@1.0.0?os=linux#sub', shownForPurl: 'pkg:generic/acme/pump@1.1.0'
        })])
        expect(w.text()).toContain('carried from 1.0.0')
        expect(w.text()).not.toContain('carried from 1.0.0?os=linux')
    })

    it('names the version of a level uploaded on another release', async () => {
        const w = await mounted([row({ releaseUuid: 'rel-2', sourceReleaseVersion: '2.0.0' })])
        expect(w.text()).toContain('uploaded on 2.0.0')
    })

    it('does not mark most stringent for several active levels unless componentMostConservative is set', async () => {
        const w = await mounted([row({ uuid: 'r1' }), row({ uuid: 'r2' })])
        const cells = w.findAll('[data-col-key="levelRaw"]')
        expect(cells[1].text()).not.toContain('most stringent')
        expect(cells[2].text()).not.toContain('most stringent')
    })

    it('asks for the release view with up to 500 rows', async () => {
        await mounted([row({})])
        const call = query.mock.calls[0][0]
        expect(call.query.loc.source.body).toContain('getSafetyContextReleaseView')
        expect(call.variables).toMatchObject({ org: 'org-1', release: 'rel-1', includeSuperseded: false, limit: 500 })
    })

    it('says a level was uploaded on a release not visible to you, with no link, when the source is withheld', async () => {
        const w = await mounted([row({ releaseUuid: null, sourceRedacted: true, scope: 'ORG' })])
        const cell = w.findAll('[data-col-key="scope"]')[1]
        expect(cell.text()).toContain('Organization')
        expect(cell.text()).toContain('uploaded on a release not visible to you')
        expect(cell.find('a').exists()).toBe(false)
    })

    it('names no reviewer and shows no archived tag on a withheld row', async () => {
        const w = await mounted([row({
            releaseUuid: null, sourceRedacted: true, acceptedVia: 'MANUAL', sourceDeleted: null, gateRule: 'DOWNGRADE'
        })])
        expect(w.find('[data-col-key="acceptedVia"]:not(th)').text()).toBe('reviewer (not visible to you)Lower than the current level')
        expect(w.text()).not.toContain('source archived')
    })
})

describe('ReleaseSafetyContext device view (product release)', () => {
    beforeEach(() => { query.mockReset() })

    const product = (rows: any[], over: any = {}) => mounted(rows, { productView: true, ...over })

    it('non-product view: no "Release" column, no summary card, no banner', async () => {
        const w = await mounted([row({})], { incompleteReasons: ['READ_CAP'], complete: false })
        expect(w.find('[data-col-key="appliesToRelease"]').exists()).toBe(false)
        expect(w.find('[data-testid="safety-context-summary"]').exists()).toBe(false)
        expect(w.find('[data-testid^="safety-banner-"]').exists()).toBe(false)
        expect(w.text()).toContain('Levels come from SAFETY_CONTEXT')
    })

    it('product view: the "Release" column, the summary card and a HIDDEN_RELEASES banner', async () => {
        const w = await product([
            row({ uuid: 'p', appliesToRelease: 'rel-1' }),
            row({ uuid: 'c', appliesToRelease: 'c1', appliesToComponentName: 'pump-fw', appliesToReleaseVersion: '1.2.0',
                appliesToReleaseArchived: true, levelNormalized: 'Class C' })
        ], {
            summary: [{ schemeKey: 'IEC-62304', level: 'Class C', levelRank: 3, releaseCount: 1, recordCount: 1, unrankedLevels: [] }],
            hiddenReleaseCount: 1, hiddenLevelCount: 2, complete: false, incompleteReasons: ['HIDDEN_RELEASES']
        })
        const cells = w.findAll('[data-col-key="appliesToRelease"]')
        // The ellipsis tooltip stub renders the text twice (trigger and tooltip).
        expect(cells[0].text()).toContain('Release')
        expect(cells[1].text()).toContain('This release')
        expect(cells[1].find('a').exists()).toBe(false)
        expect(cells[2].text()).toContain('pump-fw 1.2.0')
        expect(cells[2].find('a').attributes('href')).toBe('/release/show/c1')
        expect(cells[2].find('[data-testid="safety-release-archived"]').text()).toBe('archived')
        expect(w.find('[data-testid="safety-context-summary"]').text()).toContain('Most stringent level per scheme')
        expect(w.find('[data-testid="safety-summary-line"]').text()).toBe('IEC-62304: Class C (1 release)')
        expect(w.find('[data-testid="safety-banner-HIDDEN_RELEASES"]').text()).toBe('1 component release(s) are not visible to you;'
            + ' 2 level(s) on them are not shown. The most stringent level of this product may be higher than shown.')
        expect(w.text()).toContain('This is a product release.')
        expect(w.text()).not.toContain('Levels come from SAFETY_CONTEXT')
    })

    it('product view with an empty summary: complete and not complete', async () => {
        let w = await product([])
        expect(w.find('[data-testid="safety-summary-empty"]').text())
            .toBe('No safety integrity levels apply to this product release or its component releases.')
        w = await product([], { complete: false, incompleteReasons: ['HIDDEN_RELEASES'], hiddenReleaseCount: 1, hiddenLevelCount: 1 })
        expect(w.find('[data-testid="safety-summary-empty"]').text()).toBe('No safety integrity levels are visible to you.')
        expect(w.find('[data-testid="safety-banner-HIDDEN_RELEASES"]').exists()).toBe(true)
    })

    it('says how many rows it received when the server holds more', async () => {
        const w = await mounted([row({ uuid: 'a' }), row({ uuid: 'b' })], { totalRows: 5 })
        expect(w.find('[data-testid="safety-context-showing"]').text()).toBe('Showing the first 2 of 5 rows.')
        const all = await mounted([row({ uuid: 'a' }), row({ uuid: 'b' })])
        expect(all.find('[data-testid="safety-context-showing"]').exists()).toBe(false)
    })

    // v6: the "most stringent" tag counts distinct records per constituent, component and scheme.
    it('one ORG-scope record listed under two constituents gets no "most stringent" tag', async () => {
        const w = await product([
            row({ uuid: 'org-b', scope: 'ORG', appliesToRelease: 'c1', componentMostConservative: true }),
            row({ uuid: 'org-b', scope: 'ORG', appliesToRelease: 'a2', componentMostConservative: true })
        ])
        const cells = w.findAll('[data-col-key="levelRaw"]')
        expect(cells).toHaveLength(3)
        expect(cells[1].text()).not.toContain('most stringent')
        expect(cells[2].text()).not.toContain('most stringent')
    })

    it('two records of one constituent: the tag on the most stringent only, once per constituent', async () => {
        const c = { assetRefId: 'a-1', levelNormalized: 'Class C', componentMostConservative: true }
        const a = { assetRefId: 'a-2', levelNormalized: 'Class A' }
        const w = await product([
            row({ uuid: 'rc', appliesToRelease: 'c1', ...c }), row({ uuid: 'ra', appliesToRelease: 'c1', ...a }),
            row({ uuid: 'rc', appliesToRelease: 'c2', ...c }), row({ uuid: 'ra', appliesToRelease: 'c2', ...a })
        ])
        const tagged = w.findAll('[data-col-key="levelRaw"]').slice(1).map(x => x.text().includes('most stringent'))
        expect(tagged).toEqual([true, false, true, false])
    })

    // v4: server text is rendered as text, never as markup.
    it('renders hostile names and levels as literal text', async () => {
        const w = await product([row({
            appliesToRelease: 'c1', appliesToComponentName: '<b>x</b>\nline2', appliesToReleaseVersion: '1.0',
            levelNormalized: '<img src=x>'
        })], { summary: [{ schemeKey: '<b>s</b>', level: '<img src=x>', releaseCount: 1, unrankedLevels: [] }] })
        const table = w.find('[data-testid="safety-context-records"]')
        expect(table.find('b').exists()).toBe(false)
        expect(table.find('img').exists()).toBe(false)
        expect(w.find('[data-testid="safety-context-summary"] b').exists()).toBe(false)
        expect(w.find('[data-testid="safety-context-summary"] img').exists()).toBe(false)
        expect(w.text()).toContain('<b>x</b>')
        expect(w.text()).toContain('<img src=x>')
        expect(w.find('[data-testid="safety-summary-line"]').text()).toBe('<b>s</b>: <img src=x> (1 release)')
    })
})
