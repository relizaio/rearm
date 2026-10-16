import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { parse as parseSfc } from '@vue/compiler-sfc'

// NodeTypes of @vue/compiler-core, which compiler-sfc does not re-export.
const ELEMENT = 1
const INTERPOLATION = 5
const ATTRIBUTE = 6
const DIRECTIVE = 7

/**
 * T-26 (SCORE-42, design ADR-3): the "Declared licence and supplier" block of a component's
 * settings. ComponentView is never mounted in tests, so the template is parsed and walked and
 * the script read for its wiring (the deviceWindowPanelWiring.spec.ts and
 * releaseViewExportWiring.spec.ts patterns); the loader, the variable builder and the link rule
 * are run in utils/declaredLicense.spec.ts.
 */
const source = readFileSync(fileURLToPath(new URL('./ComponentView.vue', import.meta.url)), 'utf8')
const code = source
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1')
const queries = readFileSync(fileURLToPath(new URL('../utils/graphqlQueries.ts', import.meta.url)), 'utf8')
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
const attribute = (n: any, name: string): string | undefined => (n.props || []).find((p: any) =>
    p.type === ATTRIBUTE && p.name === name)?.value?.content
const elementChildren = (n: any): any[] => (n.children || []).filter((c: any) => c.type === ELEMENT)
const interpolations = (n: any): string[] => {
    const out: string[] = []
    const walk = (x: any): void => {
        if (x.type === INTERPOLATION) out.push(x.content?.content?.trim())
        for (const c of x.children || []) walk(c)
    }
    walk(n)
    return out
}
const last = (path: any[]): any => path[path.length - 1]
const byTestId = (id: string) => find((n) => attribute(n, 'data-testid') === id)

/** A top-level script function or const, from its opening to the first line that closes it at column 0. */
function body (opening: string): string {
    const at = code.indexOf(opening)
    if (at < 0) return ''
    const end = code.slice(at).search(/\n\}\)?\n/)
    return end < 0 ? '' : code.slice(at, at + end + 3)
}

const block = byTestId('component-license-block')
const blockNode = () => last(block[0])
const input = (id: string) => last(byTestId(id)[0])

describe('ComponentView: the declared licence and supplier block', () => {
    it('is one block, binding the three inputs to componentLicense', () => {
        expect(block).toHaveLength(1)
        for (const [id, field] of [['component-license', 'expression'], ['component-license-url', 'url'], ['component-supplier', 'supplier']]) {
            const paths = byTestId(id)
            expect(paths).toHaveLength(1)
            expect(paths[0]).toContain(blockNode())
            const node = last(paths[0])
            expect(node.tag).toBe('n-input')
            expect(directive(node, 'model', 'value')).toBe(`componentLicense.${field}`)
            expect(directive(node, 'bind', 'readonly')).toBe('!isWritable')
        }
    })

    it('is a sibling of coreSettingsActions, not a child', () => {
        const actions = find((n) => attribute(n, 'class') === 'coreSettingsActions' && directive(n, 'if') === 'hasCoreSettingsChanges && isWritable')
        expect(actions).toHaveLength(1)
        expect(block[0]).not.toContain(last(actions[0]))
        const parent = actions[0][actions[0].length - 2]
        const siblings = elementChildren(parent)
        expect(siblings[siblings.indexOf(last(actions[0])) + 1]).toBe(blockNode())
    })

    it('is gated on the drift flag and on a successful read', () => {
        const gate = directive(blockNode(), 'if') || ''
        expect(gate).toMatch(/\bdeclaredLicenseSupported\b/)
        expect(gate).toMatch(/\bcomponentLicenseLoaded\b/)
        const note = last(byTestId('component-license-unsupported')[0])
        expect(directive(note, 'if')).toMatch(/!declaredLicenseSupported$/)
        expect(interpolations(note)).toEqual(['UNSUPPORTED_NOTE'])
    })

    it('gates Save on a change and on write access, and shows the refusal inline', () => {
        const save = input('component-license-save')
        expect(directive(save, 'if')).toBe('isWritable')
        expect(directive(save, 'bind', 'disabled')).toBe('!declaredLicenseDiffers(componentLicense, componentLicenseBaseline) || savingComponentLicense')
        expect(directive(save, 'on', 'click')).toBe('saveComponentLicense')
        const error = input('component-license-error')
        expect(directive(error, 'if')).toBe('componentLicenseError')
        expect(interpolations(error)).toEqual(['componentLicenseError'])
    })

    it('renders the supplier hint, which ends with the schema sentence', () => {
        const supplierCell = byTestId('component-supplier')[0]
        const column = supplierCell[supplierCell.length - 2]
        expect(interpolations(column)).toContain('SUPPLIER_HINT')
    })

    it('loads in a wrapper that catches every read error, hides and never rethrows', () => {
        const load = body('async function loadComponentLicense')
        expect(load).not.toBe('')
        const tryAt = load.indexOf('try {')
        const callAt = load.indexOf('loadComponentDeclaredLicense(')
        const catchAt = load.indexOf('} catch')
        expect(tryAt).toBeGreaterThan(0)
        expect(callAt).toBeGreaterThan(tryAt)
        expect(catchAt).toBeGreaterThan(callAt)
        const handler = load.slice(catchAt)
        expect(handler).toMatch(/componentLicenseLoaded\.value = false/)
        expect(handler).not.toMatch(/\bthrow\b/)
        expect(handler).not.toMatch(/\bnotify\(/)
        expect(handler).not.toMatch(/SET_DECLARED_LICENSE_SUPPORTED/)
        // The flag is committed on the unsupported branch only.
        const commits = load.match(/SET_DECLARED_LICENSE_SUPPORTED/g) || []
        expect(commits).toHaveLength(1)
        expect(load.slice(callAt, catchAt)).toMatch(/if \(!r\.supported\) \{[^}]*store\.commit\('SET_DECLARED_LICENSE_SUPPORTED', false\)/)
    })

    it('loads after the device window on mount, before the settings modal, and after a device-class save', () => {
        const mounted = body('onMounted(async () => {')
        const device = mounted.indexOf('await loadDeviceWindow()')
        const license = mounted.indexOf('await loadComponentLicense()')
        const modal = mounted.indexOf('openComponentSettings()')
        expect(device).toBeGreaterThan(0)
        expect(license).toBeGreaterThan(device)
        expect(modal).toBeGreaterThan(license)
        const deviceClass = body('async function saveDeviceClass')
        expect(deviceClass.indexOf('loadComponentLicense()')).toBeGreaterThan(deviceClass.indexOf('await loadDeviceWindow()'))
    })

    it('saves through its own document and builder, with the refusal in componentLicenseError', () => {
        const save = body('async function saveComponentLicense')
        expect(save).toMatch(/componentDeclaredLicenseVariables\(componentUuid,\s*componentData\.value\?\.name \|\| updatedComponent\.value\?\.name/)
        expect(save).toMatch(/mutation: UPDATE_COMPONENT_DECLARED_LICENSE/)
        expect(save).toMatch(/componentLicenseError\.value = commonFunctions\.extractGraphQLErrorMessage\(e\)/)
        expect(save).toMatch(/notify\('success', 'Saved', COMPONENT_SAVED\)/)
    })

    it('stays out of the core settings save, its dirty check and its reset', () => {
        for (const opening of ['async function save ()', 'const hasCoreSettingsChanges', 'function resetCoreSettings']) {
            const fn = body(opening)
            expect(fn).not.toBe('')
            expect(fn).not.toMatch(/licenseExpression|licenseUrl|supplierName|componentLicense/)
        }
    })

    it('is never read into the shared component fragments', () => {
        for (const [from, to] of [['const COMPONENT_FULL_DATA', 'const COMPONENT_MUTATE'], ['const COMPONENT_SHORT_DATA', '`\n']]) {
            const at = queries.indexOf(from)
            expect(at).toBeGreaterThan(0)
            const fragment = queries.slice(at, queries.indexOf(to, at + from.length + 5))
                .split('\n').filter(l => !l.trim().startsWith('#')).join('\n')
            expect(fragment).not.toMatch(/licenseExpression|licenseUrl|supplierName/)
        }
    })
})
