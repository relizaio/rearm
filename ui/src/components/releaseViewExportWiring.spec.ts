import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { parse as parseSfc } from '@vue/compiler-sfc'

// NodeTypes of @vue/compiler-core, which compiler-sfc does not re-export.
const ELEMENT = 1
const TEXT = 2
const ATTRIBUTE = 6
const DIRECTIVE = 7

/**
 * The "Include support metadata" switch of the Export Release BOM dialog (SCORE-21, SCORE-12
 * ADR-2, test T-15): shown whenever the server offers the option, disabled with a hint when the
 * organization's support disclosure is off, absent with the sync-lag hint when the server does
 * not offer it. ReleaseView is never mounted in tests, so the template is parsed and walked;
 * what a disabled switch sends is supportMetadataArg's, run in exportMetadataFallback.spec.ts.
 */
const source = readFileSync(fileURLToPath(new URL('./ReleaseView.vue', import.meta.url)), 'utf8')
const code = source
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1')
const ast: any = parseSfc(source).descriptor.template?.ast

const HINT = 'Support disclosure is off for this organization. An organization admin turns it on in Organization Settings → Support disclosure export.'
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
/** The element's own text, not its descendants'. */
const text = (n: any): string => (n.children || []).filter((c: any) => c.type === TEXT).map((c: any) => c.content)
    .join(' ').replace(/\s+/g, ' ').trim()
const ifs = (path: any[]): string[] => path.flatMap((n: any) => directive(n, 'if') ?? [])

const supportSwitch = find((n) => n.tag === 'n-switch' && directive(n, 'model', 'value') === 'includeSupportMetadata')
const hint = find((n) => n.tag === 'div' && text(n).includes('Support disclosure is off for this organization'))
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

    it('says why it is off and where to turn it on, only when the server offers it and the org setting is off', () => {
        expect(hint).toHaveLength(1)
        expect(text(hint[0][hint[0].length - 1])).toBe(HINT)
        expect(directive(hint[0][hint[0].length - 1], 'if')).toBe('orgSupportInjectionSupported && !orgSupportInjectionEnabled')
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
    it('sends the switch through supportMetadataArg, gated on the org setting', () => {
        const call = code.match(/includeSupportMetadata: supportMetadataArg\(\s*orgSupportInjectionEnabled\.value, includeSupportMetadata\.value\)/)
        expect(call).not.toBeNull()
    })

    it('resets the switch to off when the dialog opens', () => {
        const open = code.match(/function openExportModal \(\) \{[\s\S]*?\n\}/)?.[0] || ''
        expect(open).toMatch(/includeSupportMetadata\.value = false/)
    })
})
