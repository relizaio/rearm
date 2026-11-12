import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { parse as parseSfc } from '@vue/compiler-sfc'

// NodeTypes of @vue/compiler-core, which compiler-sfc does not re-export.
const ELEMENT = 1
const ATTRIBUTE = 6
const DIRECTIVE = 7

/**
 * FDAW-74 row 12 (3.7): the "Displayed version" block of the release page, read from the source
 * as releaseViewLicenseWiring.spec.ts reads the licence block. The behaviour is run in
 * utils/useReleaseDisplayedVersion.spec.ts.
 */
const source = readFileSync(fileURLToPath(new URL('./ReleaseView.vue', import.meta.url)), 'utf8')
const code = source
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1')
const ast: any = parseSfc(source).descriptor.template?.ast

/** Every element the predicate accepts, with its ancestors (root first). */
function find (pred: (n: any) => boolean, root: any = ast): any[][] {
    const out: any[][] = []
    const walk = (node: any, ancestors: any[]): void => {
        if (node.type === ELEMENT && pred(node)) out.push([...ancestors, node])
        for (const child of node.children || []) walk(child, node.type === ELEMENT ? [...ancestors, node] : ancestors)
    }
    walk(root, [])
    return out
}
/** Every element in document order. */
function documentOrder (): any[] {
    const out: any[] = []
    const walk = (node: any): void => {
        if (node.type === ELEMENT) out.push(node)
        for (const child of node.children || []) walk(child)
    }
    walk(ast)
    return out
}
const directive = (n: any, name: string, arg?: string): string | undefined => (n.props || []).find((p: any) =>
    p.type === DIRECTIVE && p.name === name && (arg === undefined || p.arg?.content === arg))?.exp?.content
const attribute = (n: any, name: string): string | undefined => (n.props || []).find((p: any) =>
    p.type === ATTRIBUTE && p.name === name)?.value?.content
const headingText = (n: any): string => (n.children || []).map((c: any) => c.content || '').join('').trim()
const last = (path: any[]): any => path[path.length - 1]
const byTestId = (id: string) => find((n) => attribute(n, 'data-testid') === id)
const node = (id: string) => last(byTestId(id)[0])

describe('ReleaseView: the displayed version block', () => {
    const block = byTestId('release-displayed-version-block')

    it('is one block on the Meta tab, after the lifecycle dates and before the licence block', () => {
        expect(block).toHaveLength(1)
        const panes = block[0].filter((n: any) => n.tag === 'n-tab-pane').map((n: any) => attribute(n, 'name'))
        expect(panes).toEqual(['meta'])
        expect(directive(last(block[0]), 'if')).toBe('releaseDisplayedVersionLoaded')
        const headings = find((n) => n.tag === 'h3', last(block[0]))
        expect(headings).toHaveLength(1)
        expect(headingText(last(headings[0]))).toBe('Displayed version')
        const order = documentOrder()
        const lifecycle = find((n) => n.tag === 'h3' && headingText(n) === 'Release lifecycle dates')
        const licence = byTestId('release-license-block')
        expect(order.indexOf(last(block[0]))).toBeGreaterThan(order.indexOf(last(lifecycle[0])))
        expect(order.indexOf(last(block[0]))).toBeLessThan(order.indexOf(last(licence[0])))
    })

    it('binds the bound, write access, and both buttons for writers only', () => {
        const input = node('release-displayed-version-input')
        expect(directive(input, 'bind', 'maxlength')).toBe('DISPLAYED_VERSION_MAX_LENGTH')
        expect(directive(input, 'bind', 'disabled')).toContain('!isWritable')
        expect(directive(node('release-displayed-version-save'), 'if')).toBe('isWritable')
        expect(directive(node('release-displayed-version-clear'), 'if')).toBe('isWritable && releaseDisplayedVersionIsSet')
        const notes = byTestId('release-displayed-version-shipped-note')
        expect(notes).toHaveLength(1)
        expect(directive(last(notes[0]), 'if')).toContain('isShippedOrLater(')
    })

    it('renders nothing of the block as raw HTML', () => {
        expect(find((n) => directive(n, 'html') !== undefined, last(block[0]))).toHaveLength(0)
    })

    it('loads where the licence block loads and resets once', () => {
        const loads = (s: string) => code.split(s).length - 1
        expect(loads('releaseDisplayedVersionState.load(')).toBe(loads('releaseLicenseState.load('))
        expect(loads('releaseDisplayedVersionState.reset()')).toBe(1)
    })

    it('reads a DISPLAYED_VERSION row in words, between the narrative and the generated artifact branches', () => {
        const branch = "row.rus === 'DISPLAYED_VERSION'"
        expect(code.split(branch).length - 1).toBe(1)
        const at = code.indexOf(branch)
        expect(at).toBeGreaterThan(code.indexOf("row.rus === 'FDA_NARRATIVE'"))
        expect(at).toBeLessThan(code.indexOf("row.rus === 'SYNTHETIC_ARTIFACT'"))
    })

    it('keeps the field out of graphqlQueries.ts and in its own documents', () => {
        const queries = readFileSync(fileURLToPath(new URL('../utils/graphqlQueries.ts', import.meta.url)), 'utf8')
        const own = readFileSync(fileURLToPath(new URL('../utils/displayedVersion.ts', import.meta.url)), 'utf8')
        expect(queries).not.toContain('displayedVersion')
        expect(own).toContain('displayedVersion')
    })
})
