import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'

/**
 * FDAW-16 3.8: the draft card's readiness verbs reach the release page through the Regulatory panel, and each
 * opens the place that fixes it. No vue-tsc runs on <script setup>, so the seam is pinned on the source, comment-aware,
 * as releaseViewBulkWiring.spec.ts does for the bulk sweep.
 */
const source = readFileSync(fileURLToPath(new URL('./ReleaseView.vue', import.meta.url)), 'utf8')
const code = source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .split('\n')
    .map(l => l.replace(/(^|[^:])\/\/.*$/, '$1'))
    .join('\n')

function functionBody (name: string): string {
    const start = code.search(new RegExp(`\\n(async )?function ${name} \\(`))
    if (start < 0) throw new Error(`no function ${name} in ReleaseView.vue`)
    const rest = code.slice(start + 1)
    const next = rest.search(/\n(async )?function /)
    return next < 0 ? rest : rest.slice(0, next)
}

function panelTag (): string {
    const start = code.indexOf('<ReleaseSubmissionsPanel')
    if (start < 0) throw new Error('no ReleaseSubmissionsPanel in ReleaseView.vue')
    return code.slice(start, code.indexOf('/>', start))
}

describe('the readiness verbs are wired into ReleaseView (FDAW-16 3.8)', () => {
    it('the panel gets the organization-write fact and hands its fix to onReadinessFix', () => {
        expect(panelTag()).toMatch(/:can-write-org="canWriteOrg"/)
        expect(panelTag()).toMatch(/@fix="onReadinessFix"/)
    })

    it('each verb opens its place: the bulk dialog on its half, or the vendored table', () => {
        const body = functionBody('onReadinessFix')
        expect(body).toMatch(/verb === 'ADD_SUPPLIERS'\) openBulkAttest\('supplier'\)/)
        expect(body).toMatch(/verb === 'ASSESS_SUPPORT'\) openBulkAttest\('support'\)/)
        expect(body).toMatch(/verb === 'JUSTIFY_HASHES'\) openBulkAttest\('hashes'\)/)
        expect(body).toMatch(/verb === 'DESCRIBE_VENDORED'\) \{\s*vendoredExpanded\.value = \['unidentified'\]\s*await goToSbomComponents\(\)/)
        expect(body).not.toContain('EDIT_COVER_SUMMARY')
    })

    it('Assess findings opens every finding of the release, suppressed shown and KEV-only off (FDAW-17 3.3)', () => {
        const body = functionBody('onReadinessFix')
        expect(body).toMatch(/verb === 'ASSESS_FINDINGS' && updatedRelease\.value\?\.uuid\) \{\s*await viewDetailedVulnerabilitiesForRelease\(updatedRelease\.value\.uuid, '', '', \{ kevOnly: false, showSuppressed: true \}\)/)
        const opener = code.slice(code.indexOf('async function viewDetailedVulnerabilitiesForRelease('),
            code.indexOf('async function viewDetailedVulnerabilities('))
        expect(opener).toContain('currentShowSuppressed.value = !!opening.showSuppressed')
        // the artifact opener resets it, so the next plain open hides suppressed findings again
        const artifactOpener = code.slice(code.indexOf('async function viewDetailedVulnerabilities('))
        expect(artifactOpener.slice(0, artifactOpener.indexOf('\n}'))).toContain('currentShowSuppressed.value = false')
        expect(code).toMatch(/<vulnerability-modal[\s\S]*?:initial-show-suppressed="currentShowSuppressed"[\s\S]*?\/>/)
    })

    it('the panel gets the Finding Analysis Write fact, not organization write (FDAW-17 3.10)', () => {
        expect(panelTag()).toMatch(/:can-write-finding-analysis="canWriteFindingAnalysisHere"/)
        expect(code).toMatch(/const canWriteFindingAnalysisHere[^=]*= computed\(\(\): boolean =>\s*canWriteFindingAnalysis\(/)
    })

    it('openBulkAttest opens on the half it is given, the support half by default', () => {
        const body = functionBody('openBulkAttest')
        expect(body).toMatch(/^function openBulkAttest \(half: 'support' \| 'hashes' \| 'supplier' = 'support'\)/)
        expect(body).toContain('bulkTab.value = half')
        expect(body).not.toContain("bulkTab.value = 'support'")
    })

    it('a click calls openBulkAttest with no argument, so the event never becomes the half', () => {
        expect(code).not.toMatch(/@click="openBulkAttest"/)
        expect((code.match(/@click="openBulkAttest\(\)"/g) || []).length).toBe(2)
    })

    it('the vendored collapse is controlled, so the verb can open it', () => {
        expect(code).toMatch(/<n-collapse v-if="vendoredLoaded \|\| vendoredError"[^>]*v-model:expanded-names="vendoredExpanded"/)
        expect(code).toMatch(/<n-collapse-item :title="vendoredPanelTitle\(vendoredRows\)" name="unidentified">/)
    })
})
