// @vitest-environment happy-dom
//
// The SBOM score report view (SCORE-6, design 3.5): a tab per profile with its verdict, score,
// source and checks failed first; the structure checks collapsed below; SCORE-10's skipped file
// components in the header. Fixtures: src/utils/__fixtures__/sbomScore (goldens from rearm-cli 9ead33a).
import { describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('@/utils/commonFunctions', () => ({ default: { extractGraphQLErrorMessage: (e: any) => e?.message } }))

import { NTag } from 'naive-ui'
import SbomScoreReport from './SbomScoreReport.vue'
import { parseSbomScoreReport } from '@/utils/sbomScore'
import { fixtureText } from '@/utils/__fixtures__/sbomScore/fixtures'

function mountReport (name: string) {
    return mount(SbomScoreReport, { props: { report: parseSbomScoreReport(fixtureText(name)) }, attachTo: document.body })
}

// A fixture changed in place, for the cases the files do not carry.
function mountEdited (name: string, edit: (report: any) => void) {
    const report = JSON.parse(fixtureText(name))
    edit(report)
    return mount(SbomScoreReport, { props: { report: parseSbomScoreReport(JSON.stringify(report)) }, attachTo: document.body })
}

function verdictTagType (w: ReturnType<typeof mountReport>) {
    const tag = w.findAllComponents(NTag).find(t => t.attributes('data-testid') === 'sbom-score-verdict')!
    return tag.props('type')
}

function bodyRows (w: ReturnType<typeof mountReport>, testid = 'sbom-score-checks') {
    return w.find(`[data-testid="${testid}"]`).findAll('tbody tr').filter(r => !r.classes().includes('n-data-table-tr--expanded'))
}

describe('SbomScoreReport', () => {
    // T-9
    it('renders one profile: verdict, score, rows failed first, source link', () => {
        const w = mountReport('fda.spdx')
        expect(w.findAll('.n-tabs-tab[data-name]')).toHaveLength(1)
        expect(w.find('[data-testid="sbom-score-verdict"]').text()).toBe('NOT READY')
        expect(w.find('[data-testid="sbom-score-score"]').text()).toBe('Score: 90 / 100')
        expect(w.find('[data-testid="sbom-score-summary"]').text()).toBe('9 passed, 1 failed, 1 not assessed')
        const rows = bodyRows(w)
        expect(rows).toHaveLength(11)
        expect(rows[0].text()).toContain('FAIL')
        expect(rows[0].text()).toContain('fda.component.support-level')
        const link = w.find('a[data-testid="sbom-score-source"]')
        expect(link.attributes('href')).toBe('https://www.fda.gov/media/119933/download')
        expect(link.attributes('rel')).toBe('noopener')
        expect(link.attributes('target')).toBe('_blank')
        w.unmount()
    })

    // T-10
    it('renders two profiles, every status, and the structure block collapsed', async () => {
        const w = mountReport('two-profiles')
        const tabs = w.findAll('.n-tabs-tab[data-name]')
        expect(tabs.map(t => t.text())).toEqual(['CISA Minimum Elements for an SBOM (2026)', 'FDA Premarket SBOM (2026)'])

        // The first profile: score null, the ERROR row with the engine's message, NOT_ASSESSED greyed with its note.
        expect(w.find('[data-testid="sbom-score-score"]').text()).toBe('Score: —')
        expect(w.find('[data-testid="sbom-score-verdict"]').text()).toBe('NOT READY')
        const rows = bodyRows(w)
        const byId = (id: string) => rows.find(r => r.text().includes(id))!
        expect(byId('cisa.component.supplier').text()).toContain('supplier field has an unexpected type')
        expect(byId('cisa.component.supplier').text()).toContain('—')
        const notAssessed = byId('cisa.document.generation-context')
        expect(notAssessed.classes()).toContain('sbom-score-not-assessed')
        expect(notAssessed.text()).toContain('the document does not say when it was generated')
        expect(rows.filter(r => r.classes().includes('sbom-score-not-assessed'))).toHaveLength(1)

        // FAIL: note and remedy shown; remedy only on FAIL rows.
        const hash = byId('cisa.component.hash')
        expect(hash.text()).toContain('a hash is required for every component')
        expect(hash.text()).toContain('Remedy: regenerate the SBOM with hashes')
        expect(rows.filter(r => r.text().includes('Remedy:')).map(r => r.text().includes('FAIL'))).toEqual([true, true])
        // The fixture gives a remedy to a PASS, a NOT_ASSESSED and an ERROR check too; none is shown.
        for (const id of ['cisa.component.name', 'cisa.document.generation-context', 'cisa.component.supplier']) {
            expect(byId(id).text(), id).not.toContain('Remedy:')
        }
        expect(w.text()).not.toContain('keep a name on every component')
        expect(w.text()).not.toContain('record when and how the SBOM was generated')
        expect(w.text()).not.toContain('name the supplier of every component')
        expect(w.text()).not.toContain('pkg:maven/org.beta/beta@2.0.0')

        // FAIL with a failing list expands to it, with the truncation line.
        await hash.find('.n-data-table-expand-trigger').trigger('click')
        await flushPromises()
        const failing = w.find('[data-testid="sbom-score-failing"]')
        expect(failing.text()).toContain('pkg:maven/org.beta/beta@2.0.0')
        expect(failing.text()).toContain('list truncated by the engine')
        // A PASS row has no expand trigger, and neither has the ERROR row although it carries a failing list.
        expect(byId('cisa.component.name').find('.n-data-table-expand-trigger').exists()).toBe(false)
        expect(byId('cisa.component.supplier').find('.n-data-table-expand-trigger').exists()).toBe(false)
        expect(rows.filter(r => r.find('.n-data-table-expand-trigger').exists())).toHaveLength(2)
        expect(w.text()).not.toContain('pkg:npm/epsilon@5.0.0')

        // The unmatched engine error is listed on its own.
        expect(w.find('[data-testid="sbom-score-engine-errors"]').text()).toContain('the dependency graph could not be read')
        expect(w.find('[data-testid="sbom-score-engine-errors"]').text()).not.toContain('supplier field')

        // Structure: present, collapsed, with its count.
        const structure = w.find('[data-testid="sbom-score-structure"]')
        expect(structure.text()).toContain('Structure checks (2)')
        expect(w.find('[data-testid="sbom-score-structure-checks"]').exists()).toBe(false)
        await structure.find('.n-collapse-item__header-main').trigger('click')
        await flushPromises()
        expect(bodyRows(w, 'sbom-score-structure-checks')[0].text()).toContain('structure.cdx.schema')

        // Switching tabs changes the table.
        await tabs[1].trigger('click')
        await flushPromises()
        expect(w.find('[data-testid="sbom-score-score"]').text()).toBe('Score: 100 / 100')
        const fdaRows = bodyRows(w)
        expect(fdaRows).toHaveLength(3)
        expect(fdaRows[0].text()).toContain('fda.document.vulnerabilities')
        expect(w.text()).not.toContain('cisa.component.hash')
        w.unmount()
    })

    // T-11
    it('says how many file components were skipped (SCORE-10), and only then', () => {
        const skipped = mountReport('skip-files')
        expect(skipped.find('[data-testid="sbom-score-header"]').text()).toContain('3 file components skipped')
        skipped.unmount()
        const plain = mountReport('fda.cdx')
        expect(plain.find('[data-testid="sbom-score-header"]').text()).not.toContain('skipped')
        expect(plain.find('[data-testid="sbom-score-header"]').text()).toContain('CycloneDX 1.6 (json), 4 components')
        plain.unmount()
    })

    // Design 3.5: the skipped count shows when componentsSkipped > 0 or options.skipFiles, either alone.
    it.each([
        ['componentsSkipped alone', { componentsSkipped: 3 }, undefined, '(3 file components skipped, not counted)'],
        ['skipFiles alone, no count', {}, { skipFiles: true }, '(0 file components skipped, not counted)'],
        ['skipFiles with a zero count', { componentsSkipped: 0 }, { skipFiles: true }, '(0 file components skipped, not counted)'],
        ['a zero count, no options', { componentsSkipped: 0 }, undefined, null],
        ['a zero count, skipFiles false', { componentsSkipped: 0 }, { skipFiles: false }, null],
    ])('skipped-files text: %s', (_name, input, options, expected) => {
        const w = mountEdited('fda.cdx', r => {
            Object.assign(r.input, input)
            if (options === undefined) delete r.options
            else r.options = options
        })
        const header = w.find('[data-testid="sbom-score-header"]').text()
        if (expected === null) expect(header).not.toContain('skipped')
        else expect(header).toContain(expected)
        w.unmount()
    })

    // Design 3.5: READY green, NOT READY red, anything else grey.
    it.each([
        ['READY', 'READY', 'success'],
        ['NOT_READY', 'NOT READY', 'error'],
        ['UNKNOWN', 'UNKNOWN', 'default'],
    ])('colours the %s verdict tag', (verdict, text, type) => {
        const w = mountEdited('fda.cdx', r => { r.profiles[0].verdict = verdict })
        expect(w.find('[data-testid="sbom-score-verdict"]').text()).toBe(text)
        expect(verdictTagType(w)).toBe(type)
        w.unmount()
    })

    it('shows remedies on FAIL rows only, on a golden whose PASS rows carry one', () => {
        const report = parseSbomScoreReport(fixtureText('fda.cdx'))
        const passWithRemedy = report.profiles[0].checks.filter(c => c.status === 'PASS' && c.remedy)
        expect(passWithRemedy.length).toBeGreaterThan(0)
        const w = mountReport('fda.cdx')
        const rows = bodyRows(w)
        for (const c of passWithRemedy) {
            const row = rows.find(r => r.text().includes(c.id))!
            expect(row.text(), c.id).not.toContain('Remedy:')
        }
        w.unmount()
    })

    it('leaves the structure block out when the report has no structure checks', () => {
        // fda.cdx carries three structure checks; the SPDX golden has none.
        const w = mountReport('fda.spdx')
        expect(w.find('[data-testid="sbom-score-structure"]').exists()).toBe(false)
        expect(w.find('[data-testid="sbom-score-engine-errors"]').exists()).toBe(false)
        w.unmount()
    })
})
