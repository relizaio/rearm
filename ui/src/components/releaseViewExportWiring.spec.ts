import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { parse as parseSfc } from '@vue/compiler-sfc'

// NodeTypes of @vue/compiler-core, which compiler-sfc does not re-export.
const ELEMENT = 1
const TEXT = 2
const INTERPOLATION = 5
const ATTRIBUTE = 6
const DIRECTIVE = 7

/**
 * The "Include support metadata" switch of the Export Release BOM dialog (SCORE-21, SCORE-12
 * ADR-2, tests T-15, T-19, T-20): shown whenever the server offers the option, disabled with a
 * hint unless the RELEASE organization's export state is ENABLED (round 2 ADR-11), absent with
 * the sync-lag hint when the server does not offer it. ReleaseView is never mounted in tests,
 * so the template is parsed and walked and the script read for its wiring; the gate, the hint
 * texts and the load on open are RUN in utils/useExportSupportSwitch.spec.ts, and what a
 * disabled switch sends is supportMetadataArg's, run in exportMetadataFallback.spec.ts.
 */
const source = readFileSync(fileURLToPath(new URL('./ReleaseView.vue', import.meta.url)), 'utf8')
const code = source
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1')
const ast: any = parseSfc(source).descriptor.template?.ast

const SYNC_LAG = 'This server does not offer the support-metadata disclosure yet'

/** Every element the predicate accepts, with its ancestors (root first). */
function find (pred: (n: any) => boolean): any[][] {
    const out: any[][] = []
    const walk = (node: any, ancestors: any[]): void => {
        if (node.type === ELEMENT && pred(node)) out.push([...ancestors, node])
        for (const child of node.children || []) walk(child, node.type === ELEMENT ? [...ancestors, node] : ancestors)
    }
    walk(ast, [])
    return out
}

const directive = (n: any, name: string, arg?: string): string | undefined => (n.props || []).find((p: any) =>
    p.type === DIRECTIVE && p.name === name && (arg === undefined || p.arg?.content === arg))?.exp?.content
const attribute = (n: any, name: string): string | undefined => (n.props || []).find((p: any) =>
    p.type === ATTRIBUTE && p.name === name)?.value?.content
/** The expressions interpolated directly in the element ({{ x }}). */
const interpolations = (n: any): string[] => (n.children || []).filter((c: any) => c.type === INTERPOLATION)
    .map((c: any) => c.content?.content?.trim())
/** The element's own text, not its descendants'. */
const text = (n: any): string => (n.children || []).filter((c: any) => c.type === TEXT).map((c: any) => c.content)
    .join(' ').replace(/\s+/g, ' ').trim()
const ifs = (path: any[]): string[] => path.flatMap((n: any) => directive(n, 'if') ?? [])

const supportSwitch = find((n) => n.tag === 'n-switch' && directive(n, 'model', 'value') === 'includeSupportMetadata')
const hint = find((n) => n.tag === 'div' && attribute(n, 'data-testid') === 'export-support-hint')
/** The source of one top-level script statement, from its opening line to the next blank line. */
const definition = (opening: string): string => {
    const at = code.indexOf(opening)
    if (at < 0) return ''
    const end = code.indexOf('\n\n', at)
    return code.slice(at, end < 0 ? undefined : end)
}
const openExportModal = (): string => code.match(/function openExportModal \(\) \{[\s\S]*?\n\}/)?.[0] || ''
const syncLag = find((n) => n.tag === 'div' && text(n).includes(SYNC_LAG))

describe('ReleaseView export dialog: the support metadata switch', () => {
    it('shows the switch whenever the server offers the option, not only when the org setting is on', () => {
        expect(supportSwitch).toHaveLength(1)
        expect(ifs(supportSwitch[0])).toContain('orgSupportInjectionSupported')
        expect(ifs(supportSwitch[0]).join(' ')).not.toMatch(/orgSupportInjectionEnabled/)
    })

    it('disables it when the org setting is off, and gives it a test id', () => {
        const node = supportSwitch[0][supportSwitch[0].length - 1]
        expect(directive(node, 'bind', 'disabled')).toBe('!orgSupportInjectionEnabled')
        expect(attribute(node, 'data-testid')).toBe('export-include-support-metadata')
    })

    it('renders one hint under it, whose text and presence are the export state\'s (T-19)', () => {
        expect(hint).toHaveLength(1)
        const node = hint[0][hint[0].length - 1]
        expect(directive(node, 'if')).toBe('exportSupportHint')
        expect(interpolations(node)).toEqual(['exportSupportHint'])
        expect(text(node)).toBe('')
        expect(definition('const exportSupportHint')).toMatch(/= exportSupportSwitch\.hint$/)
        // No second hint carries a state's text of its own beside the bound one.
        for (const literal of ['Support disclosure is off', 'Checking whether this organization', 'could not determine whether']) {
            expect(find((n) => text(n).includes(literal))).toHaveLength(0)
        }
    })

    it('keeps the sync-lag hint for a server that does not offer the option, exclusive with the org hint', () => {
        expect(syncLag).toHaveLength(1)
        expect(directive(syncLag[0][syncLag[0].length - 1], 'if')).toBe('!orgSupportInjectionSupported')
        // Both hints and the switch share one parent and its other conditions, so the two v-if
        // expressions alone decide which shows: supported && !enabled vs !supported.
        expect(hint[0][hint[0].length - 2]).toBe(syncLag[0][syncLag[0].length - 2])
        expect(ifs(hint[0].slice(0, -1))).toEqual(ifs(syncLag[0].slice(0, -1)))
    })

    // supportMetadataArg(false, x) is null, so a disabled switch sends the organization default
    // whatever it shows (run in exportMetadataFallback.spec.ts, the support-metadata argument).
    it('sends the switch through supportMetadataArg, gated on the release organization\'s export state', () => {
        const call = code.match(/includeSupportMetadata: supportMetadataArg\(\s*orgSupportInjectionEnabled\.value, includeSupportMetadata\.value\)/)
        expect(call).not.toBeNull()
    })

    it('never reads the gate from the organization selected in the header (T-15, run 1 T-1)', () => {
        const gate = definition('const orgSupportInjectionEnabled')
        expect(gate).toMatch(/= exportSupportSwitch\.offered$/)
        expect(gate).not.toMatch(/myorg/)
        expect(code).not.toMatch(/supportInjectionFromSettings/)
        // The switch's source is the release-scoped coverage, loaded for the release's own org.
        const wiring = definition('const exportSupportSwitch = useExportSupportSwitch(')
        expect(wiring).not.toMatch(/myorg/)
        expect(wiring).toMatch(/supported: \(\): boolean => orgSupportInjectionSupported\.value,/)
        expect(wiring).toMatch(/coverage: \(\) => sbomCoverage\.value,/)
        expect(wiring).toMatch(/loading: \(\): boolean => sbomCoverageLoading\.value,/)
        expect(wiring).toMatch(/load: \(\): Promise<void> => loadSbomCoverage\(\)/)
        expect(definition('async function loadSbomCoverage')).toMatch(
            /sbomCoverageState\.load\(\s*updatedRelease\.value\?\.orgDetails\?\.uuid, updatedRelease\.value\?\.uuid\)/)
    })

    it('resets the switch to off when the dialog opens', () => {
        expect(openExportModal()).toMatch(/includeSupportMetadata\.value = false/)
    })

    it('asks for the release organization\'s export state before the dialog shows, without waiting (T-20)', () => {
        const open = openExportModal()
        const load = open.indexOf('\n    exportSupportSwitch.loadIfUnknown()\n')
        expect(load).toBeGreaterThan(-1)
        expect(load).toBeLessThan(open.indexOf('showExportSBOMModal.value = true'))
        expect(open).not.toMatch(/await/)
    })
})
