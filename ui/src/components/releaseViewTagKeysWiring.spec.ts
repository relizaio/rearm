import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { parse as parseSfc } from '@vue/compiler-sfc'

// NodeTypes of @vue/compiler-core, which compiler-sfc does not re-export.
const ELEMENT = 1
const ATTRIBUTE = 6
const DIRECTIVE = 7

/**
 * The release page's tag key suggestions. ReleaseView is never mounted in tests, so the script is
 * read and the template walked for the WIRING only; the loader's behaviour (no request for a
 * reader, never rejecting) is run in utils/releaseTagKeyOptions.spec.ts.
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
const directive = (n: any, name: string, arg?: string): string | undefined => (n.props || []).find((p: any) =>
    p.type === DIRECTIVE && p.name === name && (arg === undefined || p.arg?.content === arg))?.exp?.content
const hasAttribute = (n: any, name: string): boolean => (n.props || []).some((p: any) =>
    p.type === ATTRIBUTE && p.name === name)

/** A top-level script function, from its opening to the first line that closes it at column 0. */
function body (opening: string): string {
    const at = code.indexOf(opening)
    if (at < 0) return ''
    const end = code.slice(at).search(/\n\}\)?\n/)
    return end < 0 ? '' : code.slice(at, at + end + 3)
}

describe('ReleaseView: release tag key suggestions', () => {
    it('loads the keys through loadReleaseTagKeyOptions, gated on isWritable', () => {
        expect(code).toMatch(/import\s*\{[^}]*\bloadReleaseTagKeyOptions\b[^}]*\}\s*from\s*'@\/utils\/releaseTagKeyOptions'/)
        const fetch = body('async function fetchReleaseKeys (')
        expect(fetch).not.toBe('')
        expect(fetch.replace(/\s+/g, ' ')).toContain(
            'releaseTagKeys.value = await loadReleaseTagKeyOptions(graphqlClient, release.value?.org, isWritable.value)')
    })

    it('holds no inline releaseTagKeys query any more', () => {
        expect(code).not.toContain('releaseTagKeys(orgUuid')
    })

    it('keeps the add-tag select filterable, taggable and only in the writers form', () => {
        const selects = find((n) => n.tag === 'n-select' && directive(n, 'bind', 'options') === 'releaseTagKeys')
        expect(selects).toHaveLength(1)
        const path = selects[0]
        const select = path[path.length - 1]
        expect(hasAttribute(select, 'filterable')).toBe(true)
        expect(hasAttribute(select, 'tag')).toBe(true)
        const form = path.filter((n: any) => n.tag === 'n-form')
        expect(form).toHaveLength(1)
        expect(directive(form[0], 'if')).toBe('isWritable')
    })

    it('awaits only never-rejecting loaders between fetchRelease and the end of loading on mount', () => {
        const mount = body('onMounted(async () => {')
        expect(mount).not.toBe('')
        const detect = mount.indexOf('await detectIsProduct()')
        const fetchAt = mount.indexOf('await fetchRelease()')
        const done = mount.indexOf('isLoading.value = false')
        expect(detect).toBeGreaterThan(-1)
        expect(fetchAt).toBeGreaterThan(detect)
        expect(done).toBeGreaterThan(fetchAt)
        const between = mount.slice(fetchAt + 'await fetchRelease()'.length, done)
        const awaited = new Set(Array.from(between.matchAll(/await\s+([A-Za-z_$][\w$]*)\s*\(/g), (m) => m[1]))
        expect(awaited).toEqual(new Set(['fetchReleaseKeys', 'loadDtrackConfigured']))
        const dtrack = body('async function loadDtrackConfigured (')
        expect(dtrack).toMatch(/try\s*\{[\s\S]*graphqlClient\.query\([\s\S]*\}\s*catch\s*\(/)
    })

    it('keeps the try/finally of goToRelease, with the loading flag reset in the finally', () => {
        const go = body('async function goToRelease (')
        expect(go).not.toBe('')
        const m = go.match(/try\s*\{([\s\S]*?)\}\s*finally\s*\{([\s\S]*?)\}/)
        expect(m).not.toBeNull()
        expect(m![1]).toContain('await fetchReleaseKeys()')
        expect(m![2]).toContain('isLoading.value = false')
    })
})
