import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { parse as parseSfc } from '@vue/compiler-sfc'

// NodeTypes of @vue/compiler-core, which compiler-sfc does not re-export.
const ELEMENT = 1
const ATTRIBUTE = 6
const DIRECTIVE = 7

/**
 * TPF-120 (architecture round 2, test row 20): the auto-integrate Preview control, the explanation panel
 * and the trigger's wiring in BranchView. BranchView is never mounted in tests, so the template is parsed
 * and walked and the script read for its wiring (the componentViewBulkArchiveWiring.spec.ts idiom); the
 * logic is run in utils/autoIntegratePreview.spec.ts and the panel in AutoIntegratePreviewPanel.spec.ts.
 */
const source = readFileSync(fileURLToPath(new URL('./BranchView.vue', import.meta.url)), 'utf8')
const panelSource = readFileSync(fileURLToPath(new URL('./AutoIntegratePreviewPanel.vue', import.meta.url)), 'utf8')
const descriptor = parseSfc(source).descriptor
const script = descriptor.scriptSetup?.content ?? descriptor.script?.content ?? ''
const code = script
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1')
const ast: any = descriptor.template?.ast

function find (pred: (n: any) => boolean, root: any = ast): any[][] {
    const out: any[][] = []
    const walk = (node: any, ancestors: any[]): void => {
        if (node.type === ELEMENT && pred(node)) out.push([...ancestors, node])
        for (const child of node.children || []) walk(child, node.type === ELEMENT ? [...ancestors, node] : ancestors)
    }
    walk(root, [])
    return out
}
const directive = (n: any, name: string, arg?: string): string | undefined => (n.props || []).find((p: any) =>
    p.type === DIRECTIVE && p.name === name && (arg === undefined || p.arg?.content === arg))?.exp?.content
const hasDirective = (n: any, name: string, arg?: string): boolean => (n.props || []).some((p: any) =>
    p.type === DIRECTIVE && p.name === name && (arg === undefined || p.arg?.content === arg))
const attribute = (n: any, name: string): string | undefined => (n.props || []).find((p: any) =>
    p.type === ATTRIBUTE && p.name === name)?.value?.content
const last = (path: any[]): any => path[path.length - 1]

/** A top-level script function or const, from its opening to the first line that closes it at column 0. */
function definition (text: string, opening: string): string {
    const at = text.indexOf(opening)
    if (at < 0) return ''
    const end = text.slice(at).search(/\n\}\)?\n/)
    return end < 0 ? '' : text.slice(at, at + end + 3)
}
const count = (text: string, needle: string): number => text.split(needle).length - 1
const TRIGGER_GUARD = "isWritable && branchData.autoIntegrate === 'ENABLED' && modifiedBranch.autoIntegrate === 'ENABLED'"

describe('BranchView: the auto-integrate Preview control and panel (template)', () => {
    it('(a) one Preview icon beside the trigger, under the trigger conditions plus the probe flag', () => {
        const previews = find((n) => attribute(n, 'data-testid') === 'auto-integrate-preview-trigger')
        expect(previews).toHaveLength(1)
        const preview = last(previews[0])
        expect(preview.tag).toBe('n-icon')
        const guard = directive(preview, 'if') as string
        const parts = ['isWritable', "branchData.autoIntegrate === 'ENABLED'", "modifiedBranch.autoIntegrate === 'ENABLED'", 'autoIntegrateSupported === true']
        const at = parts.map((p) => guard.indexOf(p))
        expect(at.every((i) => i >= 0)).toBe(true)
        expect([...at].sort((x, y) => x - y)).toEqual(at)
        expect(directive(preview, 'on', 'click')).toBe('previewAutoIntegrate')
        const triggers = find((n) => directive(n, 'on', 'click') === 'triggerAutoIntegrate')
        expect(triggers).toHaveLength(1)
        const trigger = last(triggers[0])
        expect(directive(trigger, 'if')).toBe(TRIGGER_GUARD)
        const previewParent = previews[0][previews[0].length - 2]
        const triggerParent = triggers[0][triggers[0].length - 2]
        expect(previewParent.tag).toBe('p')
        expect(previewParent).toBe(triggerParent)
    })

    it('(b) one panel inside the dependencies block, bound to the evaluation, words and the two resolvers', () => {
        const panels = find((n) => n.tag === 'AutoIntegratePreviewPanel')
        expect(panels).toHaveLength(1)
        const panel = last(panels[0])
        expect(directive(panel, 'if')).toContain('autoIntegratePanelVisible')
        expect(directive(panel, 'bind', 'evaluation')).toBe('autoIntegrateEvaluation')
        expect(directive(panel, 'bind', 'words')).toBe('words')
        expect(directive(panel, 'bind', 'component-href')).toBe('autoIntegrateComponentHref')
        expect(directive(panel, 'bind', 'release-href')).toBe('autoIntegrateReleaseHref')
        expect(hasDirective(panel, 'on', 'close')).toBe(true)
        expect(panels[0].some((n: any) => n.tag === 'div' && (attribute(n, 'class') || '').split(/\s+/).includes('effectiveDependenciesBlock'))).toBe(true)
    })

    it('(b2) the panel sits between the Dependencies heading row and the dependencies table', () => {
        const panelPath = find((n) => n.tag === 'AutoIntegratePreviewPanel')[0]
        const block = panelPath[panelPath.length - 2]
        expect((attribute(block, 'class') || '').split(/\s+/)).toContain('effectiveDependenciesBlock')
        const kids = block.children.filter((n: any) => n.type === ELEMENT)
        const at = kids.indexOf(last(panelPath))
        // the heading row with the help, add, trigger and preview icons comes right before it
        expect(kids[at - 1].tag).toBe('p')
        expect(find((n) => attribute(n, 'data-testid') === 'auto-integrate-preview-trigger', kids[at - 1])).toHaveLength(1)
        // the dependencies table (or its empty state) right after it, and nothing after the empty state
        expect(directive(kids[at + 1], 'if')).toBe('modifiedBranch.effectiveDependencies && modifiedBranch.effectiveDependencies.length')
        expect(find((n) => n.tag === 'n-data-table', kids[at + 1]).length).toBeGreaterThan(0)
        expect(hasDirective(kids[at + 2], 'else')).toBe(true)
        expect(kids).toHaveLength(at + 3)
    })
})

describe('BranchView: the auto-integrate wiring (script)', () => {
    it('(c) the trigger runs through the composable and explains a null result', () => {
        const trigger = definition(code, 'async function triggerAutoIntegrate')
        expect(trigger).not.toBe('')
        expect(trigger).toContain('autoIntegratePreview.runTrigger(graphqlClient)')
        expect(trigger).toContain('showEvaluation(')
        expect(trigger).toContain('triggerNotice(')
        expect(trigger).toContain("'Auto Integrate Failed'")
        expect(trigger).toContain("'Auto Integrate Completed'")
        expect(trigger).not.toContain('mutation autoIntegrateFeatureSet')
        expect(count(source, 'mutation autoIntegrateFeatureSet')).toBe(0)
    })

    it('(d) the one opener resets the preview before it shows the modal, then probes', () => {
        const opener = definition(code, 'const openBranchSettings')
        expect(opener).not.toBe('')
        const reset = opener.indexOf('autoIntegratePreview.reset()')
        const show = opener.indexOf('showBranchSettingsModal.value = true')
        const probe = opener.indexOf('probeOnOpen(graphqlClient)')
        expect(reset).toBeGreaterThanOrEqual(0)
        expect(show).toBeGreaterThan(reset)
        expect(probe).toBeGreaterThan(show)
        for (const g of ["componentDetails?.type === 'PRODUCT'", 'isWritable.value', "autoIntegrate === 'ENABLED'"]) {
            expect(opener).toContain(g)
        }
        const close = definition(code, 'const handleBranchSettingsClose')
        expect(count(code, 'showBranchSettingsModal.value = true')).toBe(2)
        expect(count(opener, 'showBranchSettingsModal.value = true')).toBe(1)
        expect(count(close, 'showBranchSettingsModal.value = true')).toBe(1)
    })

    it('(e) the close handler keeps its reset', () => {
        expect(definition(code, 'const handleBranchSettingsClose')).toContain('autoIntegratePreview.reset()')
    })

    it('(f) no v-html in BranchView or the panel', () => {
        expect(count(source, 'v-html')).toBe(0)
        expect(count(panelSource, 'v-html')).toBe(0)
    })

    it('(g) the tabler import brings ListSearch', () => {
        const line = script.split('\n').find((l) => /from '@vicons\/tabler'/.test(l)) || ''
        expect(line.split(/[{},\s]+/)).toContain('ListSearch')
    })

    it('(h) the util import names the composable and triggerNotice', () => {
        const matches = [...script.matchAll(/^\s*import \{([^}]*)\} from '@\/utils\/autoIntegratePreview'/gm)]
        expect(matches).toHaveLength(1)
        const names = matches[0][1].split(',').map((s) => s.trim())
        expect(names).toContain('useAutoIntegratePreview')
        expect(names).toContain('triggerNotice')
    })
})
