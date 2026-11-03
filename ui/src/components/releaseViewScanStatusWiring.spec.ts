import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { parse as parseSfc } from '@vue/compiler-sfc'
import { stateLine } from '@/utils/artifactScanStatus'
import type { ArtifactScanStatus } from '@/utils/artifactScanStatus'

/**
 * Wiring guard for the scan status icon and modal in ReleaseView (TPF-122, architecture-3 row 21).
 * ReleaseView is never mounted in tests, so this scan checks imports and bindings only; the
 * behaviour runs in artifactScanStatus.spec.ts and ArtifactScanStatusPanel.spec.ts.
 */
const source = readFileSync(fileURLToPath(new URL('./ReleaseView.vue', import.meta.url)), 'utf8')
const panelSource = readFileSync(fileURLToPath(new URL('./ArtifactScanStatusPanel.vue', import.meta.url)), 'utf8')
const code = source
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1')
const descriptor = parseSfc(source).descriptor
const script = (descriptor.scriptSetup || descriptor.script)!.content
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1')
const template = descriptor.template!.content.replace(/<!--[\s\S]*?-->/g, '')
const ast: any = descriptor.template!.ast

// NodeTypes of @vue/compiler-core, which compiler-sfc does not re-export.
const ELEMENT = 1
const TEXT = 2
const DIRECTIVE = 7

function elements (tag: string): any[] {
    const found: any[] = []
    const walk = (node: any, ancestors: any[]): void => {
        if (node.type === ELEMENT && node.tag === tag) found.push({ node, ancestors })
        for (const child of node.children || []) walk(child, [...ancestors, node])
    }
    walk(ast, [])
    return found
}

/** The attributes of an element as their source text, e.g. `v-if="isWritable"`. */
function attrs (node: any): string[] {
    return (node.props || []).map((p: any) => p.loc.source)
}

function textOf (node: any): string {
    if (node.type === TEXT) return node.content
    return (node.children || []).filter((c: any) => c.type === TEXT || (c.type === ELEMENT && c.tag !== 'template'))
        .map(textOf).join('')
}

function fn (name: string): string {
    return script.match(new RegExp(`(async )?function ${name} \\([^)]*\\)[^{]*\\{[\\s\\S]*?\\n\\}`))?.[0] || ''
}

function count (haystack: string, needle: string): number {
    return haystack.split(needle).length - 1
}

describe('ReleaseView scan status wiring (row 21)', () => {
    it('(a) imports the util, the panel and the icon', () => {
        const lines = script.split('\n').filter(l => /^\s*import \{([^}]*)\} from '@\/utils\/artifactScanStatus'/.test(l))
        expect(lines).toHaveLength(1)
        const names = lines[0].match(/\{([^}]*)\}/)![1].split(',').map(s => s.trim())
        expect(names).toContain('loadArtifactScanStatus')
        expect(names).toContain('scanStatusSupported')
        expect(source).toContain("import ArtifactScanStatusPanel from '@/components/ArtifactScanStatusPanel.vue'")
        const tabler = script.split('\n').find(l => l.includes("from '@vicons/tabler'")) || ''
        expect(tabler).toMatch(/\bStethoscope\b/)
    })

    it('(b) the icon sits after Score, under the BOM and latch conditions, once', () => {
        const helper = code.match(/function renderArtifactActionIcons \(row: any\): any\[\] \{[\s\S]*?\n\}/)?.[0] || ''
        expect(helper).toContain('if (!isDownloadable) return els')
        expect(helper).toContain("row.type === 'BOM'")
        const at = (s: string) => helper.indexOf(s)
        expect(at('openSbomScoreModal(row)')).toBeGreaterThan(at("row.type === 'BOM'"))
        expect(at('scanStatusSupported.value !== false')).toBeGreaterThan(at('openSbomScoreModal(row)'))
        expect(at("'data-testid': 'artifact-scan-status'")).toBeGreaterThan(at('scanStatusSupported.value !== false'))
        expect(at('openScanStatusModal(row)')).toBeGreaterThan(at("'data-testid': 'artifact-scan-status'"))
        expect(count(script, "'artifact-scan-status'")).toBe(1)
    })

    it('(c) both inventory tables render the icons through the helper, the generated table does not', () => {
        for (const table of ['artifactsTableFields', 'underlyingArtifactsTableFields']) {
            const columns = code.match(new RegExp(`const ${table}: DataTableColumns<any> = \\[[\\s\\S]*?\\n\\]`))?.[0] || ''
            expect(columns, table).toMatch(/renderArtifactActionIcons\(row\)/)
        }
        const generated = code.match(/const generatedArtifactsTableFields: DataTableColumns<any> = \[[\s\S]*?\n\]/)?.[0] || ''
        expect(generated).not.toBe('')
        expect(generated).not.toMatch(/renderArtifactActionIcons/)
    })

    it('(d) the opener sets the artifact and bumps the run before showing; the loader wraps the util', () => {
        const open = fn('openScanStatusModal')
        const a = open.indexOf('scanStatusArtifact.value = artifact')
        const b = open.indexOf('scanStatusRun.value++')
        const c = open.indexOf('showScanStatusModal.value = true')
        expect(a).toBeGreaterThan(-1)
        expect(b).toBeGreaterThan(a)
        expect(c).toBeGreaterThan(b)
        expect(fn('loadScanStatusOf')).toContain('loadArtifactScanStatus(graphqlClient, artifactUuid)')
    })

    it('(e) one panel, fresh per open, bound to the uuid, the loader and notify only', () => {
        const panels = elements('artifact-scan-status-panel')
        expect(panels).toHaveLength(1)
        const { node, ancestors } = panels[0]
        const a = attrs(node)
        for (const want of ['v-if="showScanStatusModal && scanStatusArtifact"', ':key="scanStatusRun"',
            ':artifact-uuid="scanStatusArtifact.uuid"', ':load="loadScanStatusOf"', ':notify="notify"']) {
            expect(a).toContain(want)
        }
        expect(a).toHaveLength(5)
        const div = ancestors[ancestors.length - 1]
        expect(div.tag).toBe('div')
        expect(attrs(div)).toContain('data-testid="artifact-scan-status-modal"')
        const modal = ancestors[ancestors.length - 2]
        expect(modal.tag).toBe('n-modal')
        const m = attrs(modal)
        expect(m).toContain('v-model:show="showScanStatusModal"')
        expect(m).toContain('preset="dialog"')
        expect(m.find((s: string) => s.startsWith(':title='))).toContain('Scan status: ')
        expect(template).not.toContain(':admin=')
        expect(node.loc.source).not.toContain("userPermission === 'ADMIN'")
        expect(fn('openScanStatusModal')).not.toContain('userPermission')
    })

    it('(f) the DTrack pill keeps its labels; Trigger Enrichment stays once in the Actions column and out of the panel', () => {
        const pill = code.match(/function renderDtrackPill \(row: any\): any \{[\s\S]*?\n\}/)?.[0] || ''
        for (const label of ["'DTrack: not configured'", "'Scan failed'", "'Scan done'", "'Scanning", "'Release rejected'", "'Scan pending'"]) {
            expect(pill, label).toContain(label)
        }
        expect(count(script, "title: 'Trigger Enrichment'")).toBe(1)
        expect(panelSource).not.toContain('Trigger Enrichment')
        expect(panelSource).not.toContain('mutate')
    })

    it('(g) adds no v-html: ReleaseView keeps its one, the panel has none', () => {
        // ReleaseView carried exactly one v-html before TPF-122 (base 589fc22c).
        expect(count(source, 'v-html')).toBe(1)
        expect(count(panelSource, 'v-html')).toBe(0)
    })

    // A rename of the Reconcile button, the BOM Components tab or the SBOM Components sub-tab
    // must change the UNMAPPED text of utils/artifactScanStatus.ts in the same PR.
    it('(h) the controls the UNMAPPED text names exist as it spells them', () => {
        const unmapped = { state: 'UNMAPPED' } as ArtifactScanStatus
        const text = stateLine(unmapped).text
        expect(text).toContain('Reconcile')
        expect(text).toContain('SBOM Components')
        expect(text).toContain('BOM Components')

        expect(count(template, 'tab="BOM Components"')).toBe(1)
        const subTabs = elements('n-tab-pane').map(e => e.node).flatMap((n: any) => (n.props || [])
            .filter((p: any) => p.type === DIRECTIVE && p.name === 'bind' && p.arg?.content === 'tab')
            .map((p: any) => (p.exp?.content || '').trim()))
            .filter((v: string) => v.startsWith('`SBOM Components'))
        expect(subTabs).toHaveLength(1)

        const reconcile = elements('n-button').map(e => e.node).filter((n: any) => textOf(n).trim() === 'Reconcile')
        expect(reconcile).toHaveLength(1)
        const a = attrs(reconcile[0])
        expect(a).toContain('v-if="isWritable"')
        expect(a).toContain('@click="reconcileSbomFromTab"')

        expect(script).toContain('async function reconcileSbomFromTab')
        expect(fn('reconcileReleaseSbom')).toContain('reconcileReleaseSbomComponents(releaseUuid: $releaseUuid)')
    })
})
