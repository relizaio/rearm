import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'

/**
 * Wiring guard for the findings badge on ReleaseSbomComponentGraph.vue. Scope
 * is imports and bindings only; the documents run in
 * sbomComponentFindingsSchemaDrift.spec.ts. There is no vue-tsc, so a helper
 * the page uses without importing is a ReferenceError only at runtime.
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

describe('ReleaseSbomComponentGraph findings wiring', () => {
    const code = source('./ReleaseSbomComponentGraph.vue')

    it.each(['loadRichestServed', 'SBOM_COMPONENT_FINDINGS_QUERY', 'SBOM_COMPONENT_FINDINGS_QUERY_CORE',
        'isSuppressedAnalysisState', 'ANALYSIS_STATE_OPTIONS', 'ROW_SEVERITIES', 'emptySeverityCounts', 'severityBucketOf',
        'getSeverityTagType', 'renderFindingId', 'FindingType', 'formatPrimaryScore', 'fixedInText', 'fixedInTitle',
        'constants'])('imports %s, which it uses', (name) => {
        expectImportedAndUsed(code, name)
    })

    it('loads the findings once the graph row is in', () => {
        expect(code).toMatch(/selected\.value = row\s*\n\s*fetchComponentFindings\(releaseUuid, sbomComponentUuid\)/)
    })
})
