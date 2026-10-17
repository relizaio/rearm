import { describe, it, expect, vi } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { parse as parseSfc } from '@vue/compiler-sfc'
import { buildReleaseScoreVariables } from '@/utils/sbomScore'

// commonFunctions pulls in the Apollo client and Keycloak; buildReleaseScoreVariables needs none of it.
vi.mock('@/utils/commonFunctions', () => ({ default: {} }))

// NodeTypes.DIRECTIVE of @vue/compiler-core, which compiler-sfc does not re-export.
const DIRECTIVE = 7

/**
 * Wiring guard for the SBOM score buttons in ReleaseView (SCORE-6). ReleaseView is never
 * mounted in tests, so this scan checks imports and bindings only; the behaviour runs in
 * sbomScore.spec.ts, SbomScoreReport.spec.ts and SbomScorePanel.spec.ts.
 *
 * What only a scan can check: the helpers are imported (no vue-tsc, so a missing import is a
 * runtime ReferenceError), both artifact tables render their icons through the one helper (so
 * the Score icon cannot be on one table and missing from the other), and the two queries are
 * sent uncached with the panel's abort signal, the export dialog binds the state that
 * useReleaseSbomScore holds (its behaviour is in useReleaseSbomScore.spec.ts), and the export
 * panel sits under no BOM-type v-if, so a type switch does not remount it (parsed template).
 */
const source = readFileSync(fileURLToPath(new URL('./ReleaseView.vue', import.meta.url)), 'utf8')
const code = source
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1')

const importBlock = (code.match(/^import\s[^;]*?\sfrom\s+'[^']+'/gms) || []).join('\n')

describe('ReleaseView SBOM score wiring', () => {
    it('imports what it uses', () => {
        for (const name of ['SbomScorePanel', 'buildReleaseScoreVariables', 'SBOM_SCORE_PROFILES', 'ReportAnalytics', 'useReleaseSbomScore']) {
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
        expect(open).toMatch(/releaseSbomScore\.reset\(\)/)
    })

    it('binds the export dialog to useReleaseSbomScore: disabled while pending, stale line, fresh panel per click', () => {
        expect(code).toMatch(/const releaseSbomScore = useReleaseSbomScore\(\{\s*variables: currentReleaseScoreVariables,\s*formDisabled: \(\) => releaseSbomScoreDisabled\.value,\s*sbomForm: \(\) => exportBomType\.value === 'SBOM'\s*\}\)/)
        for (const [alias, field] of [['sbomScoreRequested', 'requested'], ['releaseSbomScoreShown', 'shown'], ['sbomScorePending', 'pending'], ['sbomScoreRun', 'run'],
            ['releaseSbomScoreOptionsChanged', 'optionsChanged'], ['releaseSbomScoreButtonDisabled', 'buttonDisabled'], ['scoreReleaseSbom', 'score']]) {
            expect(code, alias).toMatch(new RegExp(`const ${alias}(: [^=]+)? = releaseSbomScore\\.${field}\\n`))
        }
        const button = code.match(/<n-button[^>]*data-testid="release-sbom-score"[^>]*>/)?.[0] || ''
        expect(button).toMatch(/:disabled="releaseSbomScoreButtonDisabled"/)
        expect(button).toMatch(/@click="scoreReleaseSbom"/)
        const block = code.match(/<div v-if="sbomScoreRequested"[\s\S]*?:file-id="updatedRelease\.uuid" \/>/)?.[0] || ''
        expect(block).toMatch(/v-if="releaseSbomScoreOptionsChanged"[^>]*>\s*Options changed; score again/)
        expect(block).toMatch(/<sbom-score-panel\s+:key="sbomScoreRun"\s+v-model:pending="sbomScorePending"\s+:load="loadReleaseSbomScore"/)
        const load = code.match(/async function loadReleaseSbomScore[\s\S]*?\n\}/)?.[0] || ''
        expect(load).toMatch(/variables: releaseSbomScore\.scoredVariables\(\)/)
    })

    // T-14 (SCORE-21, SCORE-12 ADR-3): the Score click's variables, built from ReleaseView's own
    // call with its refs stubbed, carry two nulls whatever the modal's switches and the org say.
    it('scores with both metadata flags null while the support switch is ticked, in either org state', () => {
        const fn = code.match(/function currentReleaseScoreVariables \(\): Record<string, any> \{[\s\S]*?\n\}/)?.[0] || ''
        const literal = fn.match(/buildReleaseScoreVariables\((\{[\s\S]*\})\)\s*\n\}$/)?.[1] || ''
        expect(literal).toMatch(/release: updatedRelease\.value\.uuid/)
        expect(literal).not.toMatch(/includeSupportMetadata|includeInternalMetadata|orgSupportInjectionEnabled/)
        // eslint-disable-next-line no-new-func
        const formFrom = new Function('refs', `with (refs) { return (${literal}) }`) as (refs: object) => any
        for (const orgSupportInjectionEnabled of [false, true]) {
            for (const excludeFileComponentsRequested of [false, true]) {
                const refs = {
                    updatedRelease: { value: { uuid: 'r-1' } }, tldOnly: { value: false }, ignoreDev: { value: false },
                    selectedBomStructureType: { value: 'FLAT' }, selectedRebomType: { value: '' },
                    computedExcludeCoverageTypes: { value: [] }, exportMetadataArgsUnsupported: { value: false },
                    excludeFileComponentsRequested: { value: excludeFileComponentsRequested },
                    includeSupportMetadata: { value: true }, includeInternalMetadata: { value: true },
                    orgSupportInjectionEnabled: { value: orgSupportInjectionEnabled }
                }
                const v = buildReleaseScoreVariables(formFrom(refs))
                const label = `org ${orgSupportInjectionEnabled}, file switch ${excludeFileComponentsRequested}`
                expect(v.release, label).toBe('r-1')
                expect('includeSupportMetadata' in v, label).toBe(true)
                expect(v.includeSupportMetadata, label).toBeNull()
                expect('includeInternalMetadata' in v, label).toBe(true)
                expect(v.includeInternalMetadata, label).toBeNull()
                expect(v.excludeFileComponents, label).toBe(excludeFileComponentsRequested ? true : null)
            }
        }
    })

    it('keeps the export score mounted across a BOM type switch: hidden by v-show, not under a type v-if (D-10)', () => {
        // A panel under the SBOM-only form unmounts on OBOM/VDR and mounts afresh back on SBOM,
        // and its onMounted sends the score again without a click (tester run 2, T-6).
        const ast = parseSfc(source).descriptor.template?.ast
        let path: any[] = []
        const walk = (node: any, ancestors: any[]): void => {
            if (node.tag === 'sbom-score-panel' && node.props.some((p: any) => p.exp?.content === 'loadReleaseSbomScore')) path = [...ancestors, node]
            for (const child of node.children || []) walk(child, [...ancestors, node])
        }
        walk(ast, [])
        expect(path.length).toBeGreaterThan(0)
        const conditions = (dir: string) => path.flatMap((n: any) => (n.props || [])
            .filter((p: any) => p.type === DIRECTIVE && p.name === dir).map((p: any) => p.exp?.content))
        expect(conditions('if')).toEqual(['sbomScoreRequested'])
        expect(conditions('else-if')).toEqual([])
        expect(conditions('else')).toEqual([])
        expect(path[path.length - 2].props.find((p: any) => p.name === 'show')?.exp?.content).toBe('releaseSbomScoreShown')
        expect(code).toMatch(/:style="releaseSbomScoreShown \? 'width: 90%; max-width: 1100px' : undefined"/)
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
