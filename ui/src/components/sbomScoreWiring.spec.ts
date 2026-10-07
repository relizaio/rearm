import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'

/**
 * Wiring guard for the SBOM score buttons in ReleaseView (SCORE-6). ReleaseView is never
 * mounted in tests, so this scan checks imports and bindings only; the behaviour runs in
 * sbomScore.spec.ts, SbomScoreReport.spec.ts and SbomScorePanel.spec.ts.
 *
 * What only a scan can check: the helpers are imported (no vue-tsc, so a missing import is a
 * runtime ReferenceError), both artifact tables render their icons through the one helper (so
 * the Score icon cannot be on one table and missing from the other), and the two queries are
 * sent uncached with the panel's abort signal.
 */
const code = readFileSync(fileURLToPath(new URL('./ReleaseView.vue', import.meta.url)), 'utf8')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1')

const importBlock = (code.match(/^import\s[^;]*?\sfrom\s+'[^']+'/gms) || []).join('\n')

describe('ReleaseView SBOM score wiring', () => {
    it('imports what it uses', () => {
        for (const name of ['SbomScorePanel', 'buildReleaseScoreVariables', 'SBOM_SCORE_PROFILES', 'ReportAnalytics']) {
            expect(importBlock).toMatch(new RegExp(`\\b${name}\\b`))
        }
    })

    it('renders the icons of both artifact tables through one helper, the score for downloadable BOMs only', () => {
        const helper = code.match(/function renderArtifactActionIcons \(row: any\): any\[\] \{[\s\S]*?\n\}/)?.[0] || ''
        expect(helper).toMatch(/downloadableArtifact/)
        expect(helper).toMatch(/if \(!isDownloadable\) return els/)
        expect(helper).toMatch(/row\.type === 'BOM'[\s\S]*openSbomScoreModal\(row\)/)
        for (const table of ['artifactsTableFields', 'underlyingArtifactsTableFields']) {
            const columns = code.match(new RegExp(`const ${table}: DataTableColumns<any> = \\[[\\s\\S]*?\\n\\]`))?.[0] || ''
            expect(columns, table).toMatch(/renderArtifactActionIcons\(row\)/)
            expect(columns, table).not.toMatch(/title: 'Download Artifact'/)
        }
    })

    it('puts Score beside Export in the SBOM form, disabled for anything but a JSON SBOM', () => {
        expect(code).toMatch(/@click="scoreReleaseSbom"/)
        expect(code).toMatch(/exportBomType\.value !== 'SBOM' \|\| selectedSbomMediaType\.value !== 'JSON'/)
        expect(code).toMatch(/Scoring applies to SBOM exports in JSON/)
        expect(code).toMatch(/<sbom-score-panel[\s\S]*?:load="loadReleaseSbomScore"/)
        expect(code).toMatch(/<sbom-score-panel[\s\S]*?:load="loadArtifactSbomScore"/)
    })

    it('starts a reopened export dialog without a score', () => {
        const open = code.match(/function openExportModal \(\) \{[\s\S]*?\n\}/)?.[0] || ''
        expect(open).toMatch(/sbomScoreRequested\.value = false/)
    })

    it('sends both queries uncached, with the abort signal, the artifact one on the augmented latest document', () => {
        for (const [fn, doc] of [['loadReleaseSbomScore', 'ReleaseSbomScoreGql'], ['loadArtifactSbomScore', 'ArtifactSbomScoreGql']]) {
            const body = code.match(new RegExp(`async function ${fn} \\(signal: AbortSignal\\)[\\s\\S]*?\\n\\}`))?.[0] || ''
            expect(body, fn).toMatch(new RegExp(`graphqlQueries\\.${doc}\\b`))
            expect(body, fn).toMatch(/fetchPolicy: 'no-cache'/)
            expect(body, fn).toMatch(/context: \{ fetchOptions: \{ signal \} \}/)
        }
        const artifact = code.match(/async function loadArtifactSbomScore[\s\S]*?\n\}/)?.[0] || ''
        expect(artifact).toMatch(/raw: false/)
        expect(artifact).not.toMatch(/version:/)
    })
})
