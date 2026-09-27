import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'

/**
 * Wiring guard for the per-finding scores and the release header pills. Scope
 * is imports and bindings only (see releaseViewCoverageImports.spec.ts for why
 * a source scan must not claim behaviour); the columns, sort and pills run in
 * findingColumnsFilters.spec.ts and vulnScoreDisplay.spec.ts.
 *
 * What only a scan can check: the helpers are imported (no vue-tsc, so a
 * missing import is a runtime ReferenceError), the flat table hands its sort to
 * the modal (without that the opening sort on a score column, which appears
 * only once the rows arrive, never applies), and the header pills open the
 * modal on their column.
 */
function source (file: string): string {
    return readFileSync(fileURLToPath(new URL(file, import.meta.url)), 'utf8')
        .replace(/<!--[\s\S]*?-->/g, '')
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .replace(/(^|[^:])\/\/.*$/gm, '$1')
}

const importBlockOf = (code: string) => (code.match(/^import\s[^;]*?\sfrom\s+'[^']+'/gms) || []).join('\n')

function expectImportedAndUsed (code: string, name: string) {
    expect(code).toMatch(new RegExp(`\\b${name}\\b(?![^\\n]*from ')`))
    expect(importBlockOf(code)).toMatch(new RegExp(`\\b${name}\\b`))
}

describe('VulnerabilityModal score wiring', () => {
    const code = source('./VulnerabilityModal.vue')

    it.each(['openingFindingSort', 'findingSortStateOf', 'hasScoreFields'])('imports %s, which it uses', (name) => {
        expectImportedAndUsed(code, name)
    })

    it('keeps the sort in the modal, fed by the flat table and the grouped tables', () => {
        const flatTable = code.match(/<n-data-table\b(?:[^>"']|"[^"]*"|'[^']*')*:data="filteredData"(?:[^>"']|"[^"]*"|'[^']*')*>/)?.[0] || ''
        expect(flatTable).toContain('@update:sorter="onSorterChange"')
        expect(code).toMatch(/sortState:\s*\(\)\s*=>\s*sortState\.value/)
        expect(code).toMatch(/onUpdateSorter:\s*onSorterChange/)
    })

    it('re-seeds the sort from initialSortKey on every open', () => {
        const onOpen = code.match(/watch\(\(\) => props\.show, \(open\) => \{[\s\S]*?\n\}\)/)?.[0] || ''
        expect(onOpen).toMatch(/sortState\.value = openingFindingSort\(props\.initialSortKey\)/)
    })

    it('shows the score columns in both views only when the rows carry scores', () => {
        expect(code).toMatch(/const showScores = computed\(\(\) => hasScoreFields\(props\.data\)\)/)
        expect(code.match(/showScores:\s*showScores\.value/g)).toHaveLength(2)
    })
})

describe('ReleaseView findings and header wiring', () => {
    const code = source('./ReleaseView.vue')

    it.each(['cvssPillOf', 'epssPillOf', 'cvssBandOf', 'ReleaseVulnerabilityService'])('imports %s, which it uses', (name) => {
        expectImportedAndUsed(code, name)
    })

    it('loads an artifact\'s findings through the service, not an inline query', () => {
        expect(code).toMatch(/ReleaseVulnerabilityService\.fetchArtifactFindings\(artifactUuid\)/)
        expect(code).not.toMatch(/query getArtifactDetails/)
    })

    it('opens the modal on the pill\'s column, and KEV-only from the KEV circle', () => {
        expect(code).toMatch(/v-if="cvssPill"[^>]*@click="viewDetailedVulnerabilitiesForRelease\(releaseUuid, '', '', \{ sortKey: 'score' \}\)"/)
        expect(code).toMatch(/v-if="epssPill"[^>]*@click="viewDetailedVulnerabilitiesForRelease\(releaseUuid, '', '', \{ sortKey: 'epss' \}\)"/)
        expect(code).toMatch(/v-if="updatedRelease\.metrics\.kevCount > 0"[^>]*@click="viewDetailedVulnerabilitiesForRelease\(releaseUuid, '', '', \{ kevOnly: true \}\)"/)
        const modal = code.match(/<vulnerability-modal\b[\s\S]*?\/>/)?.[0] || ''
        expect(modal).toContain(':initial-sort-key="currentSortKey"')
        expect(modal).toContain(':initial-kev-only="currentKevOnly"')
    })

    it('takes the opening sort and KEV filter from the header control that opened the release findings', () => {
        const releaseOpen = code.match(/async function viewDetailedVulnerabilitiesForRelease\([\s\S]*?\n\}/)?.[0] || ''
        expect(releaseOpen).toMatch(/currentKevOnly\.value = !!opening\.kevOnly/)
        expect(releaseOpen).toMatch(/currentSortKey\.value = opening\.sortKey \|\| 'severity'/)
    })

    it('resets the opening sort and KEV filter when an artifact\'s findings open', () => {
        const artifactOpen = code.match(/async function viewDetailedVulnerabilities\([\s\S]*?\n\}/)?.[0] || ''
        expect(artifactOpen).toMatch(/currentSortKey\.value = 'severity'/)
        expect(artifactOpen).toMatch(/currentKevOnly\.value = false/)
    })
})
