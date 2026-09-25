import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'

/**
 * Wiring guard for the group-by-component view in VulnerabilityModal.vue. Scope
 * is imports and bindings only (see releaseViewCoverageImports.spec.ts for why a
 * source scan must not claim behaviour); the grouping and the filter parity run
 * in findingGroups.spec.ts and findingColumnsFilters.spec.ts.
 *
 * What only a scan can check here: the helpers are imported (no vue-tsc, so a
 * missing import is a runtime ReferenceError), and the flat table hands its
 * filter changes to the modal. Without that the filters fall back to living
 * inside the flat table, and the grouped view would group rows the flat table
 * hides.
 */
const code = readFileSync(fileURLToPath(new URL('./VulnerabilityModal.vue', import.meta.url)), 'utf8')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1')

const importBlock = (code.match(/^import\s[^;]*?\sfrom\s+'[^']+'/gms) || []).join('\n')

describe('VulnerabilityModal group-by-component wiring', () => {
    it.each(['groupFindingsByComponent', 'matchesFindingFilters', 'storedGroupByComponent', 'storeGroupByComponent',
        'componentCountOf', 'initialColumnFilters', 'buildComponentGroupColumns', 'buildVulnerabilityColumns'])('imports %s, which it uses', (name) => {
        expect(code).toMatch(new RegExp(`\\b${name}\\b(?![^\\n]*from ')`))
        expect(importBlock).toMatch(new RegExp(`\\b${name}\\b`))
    })

    it('renders the grouped table from the groups and their columns', () => {
        const groupedTable = code.match(/<n-data-table\b(?:[^>"']|"[^"]*"|'[^']*')*v-if="groupByComponent"(?:[^>"']|"[^"]*"|'[^']*')*>/)?.[0] || ''
        expect(groupedTable).toContain(':columns="componentGroupColumns"')
        expect(groupedTable).toContain(':data="componentGroups"')
    })

    it('re-seeds the filters and re-reads the grouping choice on every open', () => {
        const onOpen = code.match(/watch\(\(\) => props\.show, \(open\) => \{[\s\S]*?\n\}\)/)?.[0] || ''
        expect(onOpen).toMatch(/typeFilter\.value = /)
        expect(onOpen).toMatch(/severityFilter\.value = /)
        expect(onOpen).toMatch(/groupByComponent\.value = storedGroupByComponent\(\)/)
        expect(code).toMatch(/initialColumnFilters\(props\.initialTypeFilter, props\.initialSeverityFilter\)/)
    })

    it('keeps the Type and Severity filters in the modal, fed by the flat table', () => {
        const flatTable = code.match(/<n-data-table\b(?:[^>"']|"[^"]*"|'[^']*')*:data="filteredData"(?:[^>"']|"[^"]*"|'[^']*')*>/)?.[0] || ''
        expect(flatTable).toContain('@update:filters="onColumnFiltersChange"')
        expect(code).toMatch(/typeFilter:\s*\(\)\s*=>\s*typeFilter\.value/)
        expect(code).toMatch(/severityFilter:\s*\(\)\s*=>\s*severityFilter\.value/)
        expect(code).toMatch(/onUpdateFilters:\s*onColumnFiltersChange/)
    })
})
