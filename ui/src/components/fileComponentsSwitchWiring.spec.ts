import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { parse as parseSfc } from '@vue/compiler-sfc'

// NodeTypes.DIRECTIVE of @vue/compiler-core, which compiler-sfc does not re-export.
const DIRECTIVE = 7

/**
 * Wiring guard for "Leave out file components" in ReleaseView's Export Release BOM dialog
 * (SCORE-11). ReleaseView is never mounted in tests, so this checks the template position and
 * bindings only; the behaviour runs in utils/fileComponentsSwitch.spec.ts.
 */
const source = readFileSync(fileURLToPath(new URL('./ReleaseView.vue', import.meta.url)), 'utf8')
const code = source
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1')

const importBlock = (code.match(/^import\s[^;]*?\sfrom\s+'[^']+'/gms) || []).join('\n')

function pathTo (match: (node: any) => boolean): any[] {
    const ast = parseSfc(source).descriptor.template?.ast
    let path: any[] = []
    const walk = (node: any, ancestors: any[]): void => {
        if (match(node)) path = [...ancestors, node]
        for (const child of node.children || []) walk(child, [...ancestors, node])
    }
    walk(ast, [])
    return path
}

const isSwitch = (node: any) => node.tag === 'n-switch'
    && (node.props || []).some((p: any) => p.name === 'data-testid' && p.value?.content === 'export-exclude-file-components')

describe('ReleaseView file components switch wiring', () => {
    it('imports what it uses', () => {
        for (const name of ['FileSwitchUnsupportedError', 'fileSwitchAvailable', 'SBOM_EXPORT_WITH_FILE_SWITCH',
            'SBOM_EXPORT_WITH_METADATA_FLAGS', 'SBOM_EXPORT_CORE']) {
            expect(importBlock).toMatch(new RegExp(`\\b${name}\\b`))
        }
    })

    it('renders the switch once, in the SBOM form only, bound to the ref and disabled where unavailable', () => {
        const path = pathTo(isSwitch)
        expect(path.length).toBeGreaterThan(0)
        const ifs = path.flatMap((n: any) => (n.props || [])
            .filter((p: any) => p.type === DIRECTIVE && p.name === 'if').map((p: any) => p.exp?.content))
        expect(ifs).toEqual(["exportBomType === 'SBOM'"])
        const props = path[path.length - 1].props
        expect(props.find((p: any) => p.name === 'model')?.exp?.content).toBe('excludeFileComponents')
        expect(props.find((p: any) => p.name === 'bind' && p.arg?.content === 'disabled')?.exp?.content)
            .toBe('!exportFileSwitchAvailable')
        expect(source.match(/data-testid="export-exclude-file-components"/g)).toHaveLength(1)
        expect(source).toMatch(/Leave out file components:/)
        expect(source).toMatch(/Not available on this server\./)
    })

    it('starts every opening of the dialog with the switch off', () => {
        const open = code.match(/function openExportModal \(\) \{[\s\S]*?\n\}/)?.[0] || ''
        expect(open).toMatch(/excludeFileComponents\.value = false/)
    })

    it('scores and exports what the switch says where it can be honoured, and latches a refusal', () => {
        expect(code).toMatch(/const excludeFileComponentsRequested: ComputedRef<boolean> = computed\(\(\): boolean =>\s*excludeFileComponents\.value && exportFileSwitchAvailable\.value\)/)
        expect(code).toMatch(/fileSwitchAvailable\(exportMetadataArgsUnsupported\.value, exportFileSwitchUnsupported\.value\)/)
        const vars = code.match(/function currentReleaseScoreVariables \(\)[\s\S]*?\n\}/)?.[0] || ''
        expect(vars).toMatch(/excludeFileComponents: excludeFileComponentsRequested\.value/)
        const exp = code.match(/async function exportReleaseSbom \([\s\S]*?\n\}/)?.[0] || ''
        expect(exp).toMatch(/runSbomExport\(SBOM_EXPORT_WITH_FILE_SWITCH, \{ \.\.\.fullVariables, excludeFileComponents: true \}\)/)
        expect(exp).toMatch(/runSbomExport\(SBOM_EXPORT_WITH_METADATA_FLAGS, fullVariables\)/)
        expect(exp).toMatch(/excludeFileComponents: leaveOutFiles/)
        expect(exp).toMatch(/err instanceof FileSwitchUnsupportedError\) \{\s*exportFileSwitchUnsupported\.value = true\s*excludeFileComponents\.value = false/)
    })
})
