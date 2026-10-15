import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { parse as parseSfc } from '@vue/compiler-sfc'
import { print } from 'graphql'
import graphqlQueries from '@/utils/graphqlQueries'

// NodeTypes of @vue/compiler-core, which compiler-sfc does not re-export.
const ELEMENT = 1
const INTERPOLATION = 5
const ATTRIBUTE = 6
const DIRECTIVE = 7

/**
 * T-27 (SCORE-42, design ADR-4): the "Declared licence for this release" block of the release
 * page. ReleaseView is never mounted in tests, so the template is parsed and walked and the
 * script read for its WIRING only; the behaviour (load, the fence, reset, save, clear) is run in
 * utils/useReleaseDeclaredLicense.spec.ts (T-29), as the coverage composable's comment requires.
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
const interpolations = (n: any): string[] => (n.children || []).filter((c: any) => c.type === INTERPOLATION)
    .map((c: any) => c.content?.content?.trim())
const headingText = (n: any): string => (n.children || []).map((c: any) => c.content || '').join('').trim()
const last = (path: any[]): any => path[path.length - 1]
const byTestId = (id: string) => find((n) => attribute(n, 'data-testid') === id)
const node = (id: string) => last(byTestId(id)[0])

/** A top-level script function, from its opening to the first line that closes it at column 0. */
function body (opening: string): string {
    const at = code.indexOf(opening)
    if (at < 0) return ''
    const end = code.slice(at).search(/\n\}\)?\n/)
    return end < 0 ? '' : code.slice(at, at + end + 3)
}

const LOAD = 'releaseLicenseState.load('

describe('ReleaseView: the declared licence block', () => {
    const block = byTestId('release-license-block')

    it('is one block on the Meta tab, after the lifecycle dates, and not on the Support tab', () => {
        expect(block).toHaveLength(1)
        const panes = block[0].filter((n: any) => n.tag === 'n-tab-pane').map((n: any) => attribute(n, 'name'))
        expect(panes).toEqual(['meta'])
        const order = documentOrder()
        const lifecycle = find((n) => n.tag === 'h3' && headingText(n) === 'Release lifecycle dates')
        const tags = find((n) => n.tag === 'h3' && headingText(n) === 'Tags')
        expect(lifecycle).toHaveLength(1)
        expect(order.indexOf(last(block[0]))).toBeGreaterThan(order.indexOf(last(lifecycle[0])))
        expect(order.indexOf(last(block[0]))).toBeLessThan(order.indexOf(last(tags[0])))
        const heading = find((n) => n.tag === 'h3' && headingText(n) === 'Declared licence for this release')
        expect(heading).toHaveLength(1)
        expect(heading[0]).toContain(last(block[0]))
    })

    it('is gated on the drift flag and on a read for this release', () => {
        expect(directive(last(block[0]), 'if')).toBe('declaredLicenseSupported && releaseLicenseLoaded')
        const note = node('release-license-unsupported')
        expect(directive(note, 'if')).toBe('!declaredLicenseSupported')
        expect(interpolations(note)).toEqual(['UNSUPPORTED_NOTE'])
    })

    it('renders the effective line, and the URL as a link only through licenseUrlHref', () => {
        const effective = node('release-license-effective')
        expect(interpolations(effective)).toEqual(['effectiveLicenseLine(releaseLicenseEffective)'])
        const links = find((n) => n.tag === 'a', effective)
        expect(links).toHaveLength(1)
        const a = last(links[0])
        expect(directive(a, 'if')).toBe('licenseUrlHref(releaseLicenseEffective.url)')
        expect(directive(a, 'bind', 'href')).toMatch(/^licenseUrlHref\(releaseLicenseEffective\.url\)/)
        expect(attribute(a, 'target')).toBe('_blank')
        expect(attribute(a, 'rel')).toBe('noopener noreferrer')
        // The template's only bound href on this block is the one above.
        const hrefs = find((n) => directive(n, 'bind', 'href') !== undefined || attribute(n, 'href') !== undefined, last(block[0]))
        expect(hrefs).toHaveLength(1)
    })

    it('binds the input to releaseLicense, disabled without write access', () => {
        const input = node('release-license-override')
        expect(input.tag).toBe('n-input')
        expect(directive(input, 'model', 'value')).toBe('releaseLicense')
        expect(directive(input, 'bind', 'disabled')).toMatch(/^!isWritable\b/)
        expect(directive(input, 'on', 'update:value')).toBe('releaseLicenseError = null')
    })

    it('gates Save on a change and Clear on an override, both for writers only', () => {
        const save = node('release-license-save')
        expect(directive(save, 'if')).toBe('isWritable')
        expect(directive(save, 'bind', 'disabled')).toBe('!releaseLicenseDirty || savingReleaseLicense')
        expect(directive(save, 'on', 'click')).toBe('saveReleaseLicense(false)')
        const clear = node('release-license-clear')
        expect(directive(clear, 'if')).toBe('isWritable && releaseLicenseIsOverridden')
        expect(directive(clear, 'on', 'click')).toBe('saveReleaseLicense(true)')
        const error = node('release-license-error')
        expect(directive(error, 'if')).toBe('releaseLicenseError')
    })

    it('instantiates the composable once, committing the drift flag on unsupported', () => {
        expect(code).toMatch(/import \{ useReleaseDeclaredLicense \} from '@\/utils\/useReleaseDeclaredLicense'/)
        expect(code.match(/useReleaseDeclaredLicense\(/g)).toHaveLength(1)
        expect(code).toMatch(/const releaseLicenseState = useReleaseDeclaredLicense\(graphqlClient as any, \{\s*onUnsupported: \(\) => store\.commit\('SET_DECLARED_LICENSE_SUPPORTED', false\)\s*\}\)/)
    })

    it('loads after the narrative seed on mount and on navigation, never awaited', () => {
        for (const opening of ['onMounted(async () => {\n    loadingBar.start()', 'async function goToRelease']) {
            const fn = body(opening)
            expect(fn).not.toBe('')
            const loads = fn.split(LOAD).length - 1
            expect(loads).toBe(1)
            const at = fn.indexOf(LOAD)
            expect(at).toBeGreaterThan(fn.indexOf('seedReleaseNarrative()'))
            expect(fn.indexOf('seedReleaseNarrative()')).toBeGreaterThan(0)
            expect(fn.slice(0, at)).not.toMatch(/await\s*$/)
            expect(fn.slice(at)).toMatch(/^releaseLicenseState\.load\(updatedRelease\.value\.uuid, updatedRelease\.value\.org \|\| updatedRelease\.value\.orgDetails\?\.uuid\)/)
        }
        // Nowhere else, and never awaited anywhere.
        expect(code.split(LOAD).length - 1).toBe(2)
        expect(code).not.toMatch(/await\s+releaseLicenseState\.load\(/)
    })

    it('resets the block on navigation before the next release is fetched', () => {
        const fn = body('async function goToRelease')
        const reset = fn.indexOf('releaseLicenseState.reset()')
        expect(reset).toBeGreaterThan(0)
        expect(reset).toBeLessThan(fn.indexOf('await fetchRelease()'))
    })

    it('leaves the read and the write to the composable', () => {
        expect(code).not.toMatch(/loadReleaseDeclaredLicense/)
        expect(code).not.toMatch(/UPDATE_RELEASE_DECLARED_LICENSE/)
        expect(code).not.toMatch(/releaseDeclaredLicenseVariables/)
    })

    it('words the success from the outcome', () => {
        const fn = body('async function saveReleaseLicense')
        expect(fn).toMatch(/const outcome = await releaseLicenseState\.save\(clear\)/)
        expect(fn).toMatch(/if \(outcome === 'saved'\) notify\('success', 'Saved', RELEASE_SAVED\)/)
        expect(fn).toMatch(/else if \(outcome === 'cleared'\) notify\('success', 'Saved', RELEASE_CLEARED\)/)
    })

    it('keeps the fields out of both single-release documents', () => {
        for (const doc of [graphqlQueries.SingleReleaseGql, graphqlQueries.SingleReleaseProductGql]) {
            expect(print(doc)).not.toMatch(/\blicenseExpression\b|\beffectiveLicense\b/)
        }
    })
})
