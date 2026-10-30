// @vitest-environment happy-dom
//
// TPF-120 (architecture round 2, test row 21): the auto-integrate explanation panel, mounted. It renders
// the evaluation it is given and nothing else; a redacted row is never read as "no release" and never
// completed client-side.
import { afterEach, describe, expect, it } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { NAlert, NDataTable, NTag } from 'naive-ui'
import AutoIntegratePreviewPanel from './AutoIntegratePreviewPanel.vue'
import {
    FOREIGN_RELEASE_NOTE, NOT_READABLE_NOTE, type AutoIntegrateDependencyRow, type AutoIntegrateEvaluation
} from '@/utils/autoIntegratePreview'

const relA = { uuid: 'r1', version: '1.0.0', lifecycle: 'ASSEMBLED', createdDate: '2026-10-29T10:00:00Z' }
const c1: AutoIntegrateDependencyRow = { component: { uuid: 'c1', name: 'api' }, branch: { uuid: 'b1', name: 'main' }, status: 'REQUIRED',
    source: 'LATEST_ASSEMBLED', release: relA, note: 'latest ASSEMBLED release of branch main' }
const c3none: AutoIntegrateDependencyRow = { component: { uuid: 'c3', name: 'db' }, branch: { uuid: 'b3', name: 'main' }, status: 'REQUIRED',
    source: 'NONE', release: null, note: 'no release on branch main' }
const cForeign: AutoIntegrateDependencyRow = { component: { uuid: 'c5', name: 'ext' }, branch: { uuid: 'b5', name: 'main' }, status: 'TRANSIENT',
    source: 'LATEST_ASSEMBLED', release: null, note: FOREIGN_RELEASE_NOTE }
// The hidden release's version is simply absent from the fixture: nothing to leak.
const cRedacted: AutoIntegrateDependencyRow = { component: { uuid: 'c6', name: 'secret' }, branch: { uuid: 'b6', name: 'main' }, status: 'REQUIRED',
    source: 'PINNED', release: null, note: NOT_READABLE_NOTE }
const cIgnored: AutoIntegrateDependencyRow = { component: { uuid: 'c7', name: 'old' }, branch: { uuid: 'b7', name: 'main' }, status: 'IGNORED',
    source: 'NONE', release: null, note: 'ignored' }

const ev = (o: Partial<AutoIntegrateEvaluation>): AutoIntegrateEvaluation => ({
    outcome: 'WOULD_CREATE', existingRelease: null, projectedVersion: null, dependencies: [], note: null, ...o
})
const evReq = ev({ outcome: 'REQUIRED_DEPENDENCY_WITHOUT_RELEASE', dependencies: [c1, c3none, cForeign, cRedacted, cIgnored] })
const words = { branchFirstUpper: 'Feature Set' }
const componentHref = (row: AutoIntegrateDependencyRow) => '/c/' + row.component.uuid
const releaseHref = (uuid: string) => '/release/show/' + uuid

let wrappers: VueWrapper<any>[] = []
function open (evaluation: AutoIntegrateEvaluation, extra: Record<string, any> = { componentHref, releaseHref }) {
    const w = mount(AutoIntegratePreviewPanel, { props: { evaluation, words, ...extra }, attachTo: document.body })
    wrappers.push(w)
    return w
}
afterEach(() => {
    wrappers.forEach((w) => w.unmount())
    wrappers = []
})
const rows = (w: VueWrapper<any>) => w.findAll('[data-testid^="auto-integrate-preview-row-"]')
const row = (w: VueWrapper<any>, uuid: string) => w.get(`[data-testid="auto-integrate-preview-row-${uuid}"]`)
const alertType = (w: VueWrapper<any>) => w.findComponent(NAlert).props('type')

describe('AutoIntegratePreviewPanel (row 21)', () => {
    it('(a) renders the panel and the REQUIRED outcome line as a warning', () => {
        const w = open(evReq)
        expect(w.find('[data-testid="auto-integrate-preview-panel"]').exists()).toBe(true)
        expect(w.get('[data-testid="auto-integrate-preview-outcome"]').text())
            .toContain('Not created: REQUIRED dependency without a release: db (main): no release on branch main.')
        expect(alertType(w)).toBe('warning')
    })

    it('(b) renders one row per dependency, in order, with its kind', () => {
        const w = open(evReq)
        expect(rows(w).map((r) => r.attributes('data-row-kind'))).toEqual(['release', 'none', 'foreign', 'redacted', 'ignored'])
        expect(rows(w).map((r) => r.attributes('data-testid')))
            .toEqual(['c1', 'c3', 'c5', 'c6', 'c7'].map((u) => 'auto-integrate-preview-row-' + u))
    })

    it('(c) a row with a release links the component and the release', () => {
        const r = row(open(evReq), 'c1')
        expect(r.get('a[href="/c/c1"]').text()).toBe('api')
        expect(r.text()).toContain('main')
        expect(r.text()).toContain('REQUIRED')
        expect(r.text()).toContain('latest assembled')
        expect(r.get('a[href="/release/show/r1"]').text().startsWith('1.0.0 (Assembled')).toBe(true)
        expect(r.text()).toContain('latest ASSEMBLED release of branch main')
    })

    it('(d) a redacted row reads as not shown, never as none, and reconstructs nothing', () => {
        const r = row(open(evReq), 'c6')
        expect(r.classes()).toContain('autoIntegrateRedacted')
        const tags = r.findAllComponents(NTag)
        expect(tags).toHaveLength(1)
        expect(tags[0].text()).toBe('not shown')
        expect(r.text()).toContain('release not shown: you have no read permission on this component')
        expect(r.find('a[href^="/release/show/"]').exists()).toBe(false)
        expect(r.text()).not.toContain('none')
        expect(r.text()).toContain('secret')
        expect(r.text()).not.toMatch(/\d+\.\d+\.\d+/)
    })

    it('(e) foreign, none and ignored rows', () => {
        const w = open(evReq)
        const f = row(w, 'c5')
        expect(f.classes()).toContain('autoIntegrateForeign')
        expect(f.text()).toContain('another organization')
        expect(f.text()).toContain(FOREIGN_RELEASE_NOTE)
        expect(f.find('a[href^="/release/show/"]').exists()).toBe(false)
        const n = row(w, 'c3')
        expect(n.classes()).toContain('autoIntegrateNoRelease')
        expect(n.text()).toContain('none')
        const i = row(w, 'c7')
        expect(i.classes()).toContain('autoIntegrateIgnored')
        expect(i.text()).toContain('ignored')
    })

    it('(f) a matching release names the existing release once, as the link in the outcome line', () => {
        const w = open(ev({ outcome: 'MATCHING_PRODUCT_RELEASE_EXISTS', existingRelease: relA, dependencies: [c1] }))
        const a = w.get('[data-testid="auto-integrate-preview-existing-release"]')
        expect(a.element.tagName).toBe('A')
        expect(a.attributes('href')).toBe('/release/show/r1')
        expect(a.text().startsWith('1.0.0 (Assembled')).toBe(true)
        const line = a.element.parentElement as HTMLElement
        expect(line.textContent).toBe(`Not created: a matching product release already exists: ${a.text()}.`)
        const alert = w.get('[data-testid="auto-integrate-preview-outcome"]')
        expect(alert.text()).not.toContain('Existing release:')
        // the table now sits inside the alert and names c1's release in its row; the explanation names it once
        const explanation = alert.text().replace(w.get('[data-testid="auto-integrate-preview-table"]').text(), '')
        expect(explanation.split('1.0.0 (Assembled')).toHaveLength(2, 'the release is named once')
        expect(w.findAll('[data-testid="auto-integrate-preview-existing-release"]')).toHaveLength(1)
        expect(alertType(w)).toBe('info')
        expect(w.find('[data-testid="auto-integrate-preview-projected-version"]').exists()).toBe(false)
    })

    it('(g) would-create shows the projected version beside a redacted row', () => {
        const w = open(ev({ outcome: 'WOULD_CREATE', projectedVersion: '1.4.0', dependencies: [c1, cRedacted] }))
        expect(w.get('[data-testid="auto-integrate-preview-projected-version"]').text()).toBe('1.4.0')
        expect(alertType(w)).toBe('success')
        expect(w.get('[data-testid="auto-integrate-preview-outcome"]').text()).toContain('(projected version 1.4.0)')
        const r = row(w, 'c6')
        expect(r.classes()).toContain('autoIntegrateRedacted')
        expect(r.findAllComponents(NTag)[0].text()).toBe('not shown')
        expect(r.find('a[href^="/release/show/"]').exists()).toBe(false)
        expect(r.text()).not.toMatch(/\d+\.\d+\.\d+/)
    })

    it('(h) a refused create shows the server note as an error', () => {
        const w = open(ev({ outcome: 'CREATE_FAILED',
            note: 'Component P is locked, so ReARM will not assign a version: frozen. Or ask an administrator to release lock L' }))
        expect(alertType(w)).toBe('error')
        expect(w.get('[data-testid="auto-integrate-preview-outcome"]').text()).toContain('Not created: Component P is locked')
    })

    it('(i) without resolvers names and releases are plain text', () => {
        const w = open(ev({ outcome: 'MATCHING_PRODUCT_RELEASE_EXISTS', existingRelease: relA, dependencies: [c1, c3none] }), {})
        expect(w.get('[data-testid="auto-integrate-preview-table"]').findAll('a')).toHaveLength(0)
        expect(row(w, 'c1').text()).toContain('api')
        expect(row(w, 'c1').text()).toContain('1.0.0 (Assembled')
        const plain = w.get('[data-testid="auto-integrate-preview-existing-release"]')
        expect(plain.element.tagName).not.toBe('A')
        // still the one line, the release as plain text inside it
        expect((plain.element.parentElement as HTMLElement).textContent)
            .toBe(`Not created: a matching product release already exists: ${plain.text()}.`)
        expect(w.get('[data-testid="auto-integrate-preview-outcome"]').text()).not.toContain('Existing release:')
    })

    it('(i2) a refused create that carries the existing release keeps its own Existing release line', () => {
        const w = open(ev({ outcome: 'CREATE_FAILED', existingRelease: relA, note: 'the create step was declined' }))
        const alert = w.get('[data-testid="auto-integrate-preview-outcome"]')
        expect(alert.text()).toContain('Not created: the create step was declined')
        const a = w.get('[data-testid="auto-integrate-preview-existing-release"]')
        expect(a.element.tagName).toBe('A')
        expect(a.attributes('href')).toBe('/release/show/r1')
        const line = a.element.parentElement as HTMLElement
        expect(line.textContent?.trim().startsWith('Existing release:')).toBe(true)
        expect(w.findAll('[data-testid="auto-integrate-preview-existing-release"]')).toHaveLength(1)
    })

    it('(j2) closes from an X icon in the top-right corner, not a Close button under the table', async () => {
        const w = open(evReq)
        const x = w.get('[data-testid="auto-integrate-preview-close"]')
        expect(x.element.tagName).toBe('BUTTON')
        expect(x.attributes('title')).toBe('Close')
        expect(x.attributes('aria-label')).toBe('Close preview')
        expect(x.text()).toBe('')
        expect(x.find('.n-icon svg').exists()).toBe(true)
        expect(x.classes()).toContain('autoIntegratePreviewClose')
        expect(w.findAll('button').filter((b) => b.text() === 'Close')).toHaveLength(0)
        expect(w.find('.autoIntegratePreviewFooter').exists()).toBe(false)
        // the corner of the block: its first child, before the alert
        expect(w.get('[data-testid="auto-integrate-preview-panel"]').element.firstElementChild).toBe(x.element)
        await x.trigger('click')
        expect(w.emitted('close')).toHaveLength(1)
    })

    it('(k) one block in the outcome\'s color: the table sits inside the alert, with no fill of its own', () => {
        for (const [outcome, type] of [['WOULD_CREATE', 'success'], ['MATCHING_PRODUCT_RELEASE_EXISTS', 'info'],
            ['REQUIRED_DEPENDENCY_WITHOUT_RELEASE', 'warning'], ['CREATE_FAILED', 'error']] as const) {
            const w = open(ev({ outcome, existingRelease: outcome === 'MATCHING_PRODUCT_RELEASE_EXISTS' ? relA : null, dependencies: [c1] }))
            expect(w.get('[data-testid="auto-integrate-preview-panel"]').classes(), outcome).toContain('autoIntegratePreview--' + type)
            expect(alertType(w)).toBe(type)
            const alert = w.get('[data-testid="auto-integrate-preview-outcome"]')
            expect(alert.find('[data-testid="auto-integrate-preview-table"]').exists(), outcome).toBe(true)
        }
        const table = open(evReq).findComponent(NDataTable)
        expect(table.props('themeOverrides')).toMatchObject({ tdColor: 'transparent', thColor: 'transparent', tdColorStriped: 'transparent' })
    })

    it('(j) Close emits close once', async () => {
        const w = open(evReq)
        await w.get('[data-testid="auto-integrate-preview-close"]').trigger('click')
        expect(w.emitted('close')).toHaveLength(1)
    })

    it('(k) server strings render as text', () => {
        const w = open(ev({ dependencies: [{ ...c1, note: '<b>x</b>' }] }))
        expect(w.html()).not.toContain('<script')
        const r = row(w, 'c1')
        expect(r.text()).toContain('<b>x</b>')
        expect(r.find('b').exists()).toBe(false)
    })
})
