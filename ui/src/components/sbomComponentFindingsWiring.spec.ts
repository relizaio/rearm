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
        'constants', 'useVulnerabilityDetail'])('imports %s, which it uses', (name) => {
        expectImportedAndUsed(code, name)
    })

    it('opens a finding id in the details panel, not the external page', () => {
        expect(code).toMatch(/renderFindingId\(h, row\.vulnId, FindingType\.VULNERABILITY, \(vulnId: string\) => openVulnDetail\(/)
        expect(code).toMatch(/<vulnerability-details-modal[^>]*:org-uuid="findingsOrgUuid"/)
        expect(importBlockOf(code)).toMatch(/import VulnerabilityDetailsModal from '\.\/VulnerabilityDetailsModal\.vue'/)
    })

    it('reads the release org the documents bring for the details panel, the route having none by component id', () => {
        expect(code).toMatch(/extractPath: data => data\n/)
        expect(code).toMatch(/releaseOrgUuid\.value = result\.data\?\.release\?\.org/)
        expect(code).toMatch(/const graphRow = result\.data\?\.getReleaseSbomComponentGraph/)
        expect(code).toMatch(/useVulnerabilityDetail\(\(\) => findingsOrgUuid\.value\)/)
    })

    it('loads the findings once the graph row is in', () => {
        expect(code).toMatch(/selected\.value = row\s*\n\s*fetchComponentFindings\(releaseUuid, sbomComponentUuid\)/)
    })
})
