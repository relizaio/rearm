import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { withoutComments } from '@/utils/specText'

/**
 * FDAW-150 W-2: the finding analysis screens print values through findingAnalysisLabels.ts. The
 * labels are tested in findingAnalysisLabels.spec.ts; this pins the seam: each helper is imported
 * and used (no vue-tsc, so a missing import is a runtime ReferenceError), and no raw render is
 * left. A raw read that must stay (a colour map keyed on the state, the suppressed test) is not
 * matched: each forbidden text is the printed form (a slot's default, a template literal, a
 * returned string).
 */
function source (file: string): string {
    return withoutComments(readFileSync(fileURLToPath(new URL(file, import.meta.url)), 'utf8'))
}

const importBlockOf = (code: string) => (code.match(/^import\s[^;]*?\sfrom\s+'[^']+'/gms) || []).join('\n')
const withoutImports = (code: string) => code.replace(/^import\s[^;]*?\sfrom\s+'[^']+'/gms, '')

function expectImportedAndUsed (code: string, name: string) {
    expect(importBlockOf(code), `${name} imported`).toMatch(
        new RegExp(`import\\s*\\{[^}]*\\b${name}\\b[^}]*\\}\\s*from\\s*'@/utils/findingAnalysisLabels'`))
    expect(withoutImports(code), `${name} used`).toMatch(new RegExp(`\\b${name}\\b`))
}

const FILES: Record<string, { helpers: string[], forbidden: string[] }> = {
    './ViewVulnAnalysisModal.vue': {
        helpers: ['findingTypeLabel', 'locationTypeLabel', 'analysisStateLabel', 'analysisJustificationLabel',
            'analysisResponseLabel', 'analysisScopeLabel'],
        forbidden: [
            'default: () => row.locationType', 'default: () => row.analysisState', 'default: () => history.state',
            'scopeLabel = row.scope', 'return row.scopeUuid', '${props.findingType}:', 'history.responses.join',
            "return row.analysisJustification || '-'", '`Justification: ${history.justification}`'
        ]
    },
    './VulnerabilityAnalysis.vue': {
        helpers: ['findingTypeLabel', 'analysisStateLabel', 'analysisJustificationLabel', 'analysisScopeLabel'],
        forbidden: [
            'default: () => row.findingType', 'default: () => row.analysisState', 'default: () => history.state',
            'scopeLabel = row.scope', 'return row.scopeUuid', 'history.responses.join',
            '"Justification: " + row.analysisJustification', '`Justification: ${history.justification}`'
        ]
    },
    './CreateVulnAnalysisModal.vue': {
        helpers: ['findingTypeLabel', 'locationTypeLabel'],
        forbidden: ['{{ formData.findingType }}', '{{ formData.locationType }}']
    },
    './UpdateVulnAnalysisModal.vue': {
        helpers: ['analysisScopeLabel'],
        forbidden: ['[${scope}]']
    },
    '../utils/metrics.ts': {
        helpers: ['analysisStateLabel'],
        forbidden: ['`State: ${row.analysisState}`']
    }
}

describe.each(Object.entries(FILES))('%s prints labels', (file, { helpers, forbidden }) => {
    const code = source(file)

    it.each(helpers)('imports %s, which it uses', (name) => {
        expectImportedAndUsed(code, name)
    })

    it.each(forbidden)('has no raw render %s', (text) => {
        expect(code).not.toContain(text)
    })
})

describe('the raw reads that must stay raw stay raw', () => {
    it('metrics.ts still strikes through on the raw state', () => {
        expect(source('../utils/metrics.ts')).toContain('isSuppressedAnalysisState(row.analysisState)')
    })

    it.each(['./ViewVulnAnalysisModal.vue', './VulnerabilityAnalysis.vue'])('%s keys its colours on the raw state', (file) => {
        expect(source(file)).toContain('stateColors[row.analysisState]')
    })

    it('the Finding Analysis page filters on the shared option arrays, Resolved included', () => {
        const code = source('./VulnerabilityAnalysis.vue')
        expect(code).toMatch(/filterOptions:\s*FINDING_TYPE_OPTIONS/)
        expect(code).toMatch(/filterOptions:\s*ANALYSIS_STATE_OPTIONS/)
        expect(code.match(/RESOLVED: 'success'/g)?.length).toBe(2)
    })
})

describe.each(['./ViewVulnAnalysisModal.vue', './UpdateVulnAnalysisModal.vue'])('%s reads the feature-set word from the org', (file) => {
    const code = source(file)
    const computedRe = /featureSetLabel\s*=\s*computed\(\s*\(\)\s*=>\s*store\.getters\.myorg\?\.terminology\?\.featureSetLabel[^\n]*/

    it('through store.getters.myorg.terminology', () => {
        expect(code).toMatch(computedRe)
    })

    it('not through the modal\'s myorg or store.state.org', () => {
        const body = code.match(computedRe)?.[0] || ''
        expect(body).not.toContain('myorg.value')
        expect(body).not.toContain('store.state.org')
        expect(code).not.toContain('myorg.value?.featureSetLabel')
        expect(code).not.toContain('myorg.value?.terminology')
    })
})
