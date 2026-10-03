import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'

/**
 * TEA-4: the release page's Generated artifacts section, read from the SFC source with comments
 * stripped (the narrow style of releaseViewCoverageImports.spec.ts). The behaviour of the rows
 * lives in utils/generatedArtifacts.spec.ts, where the code runs; this pins the wiring a running
 * test cannot reach in a repo without a DOM environment: the import, the section, the read-only
 * actions, the inventory computed staying blind to the list, the History branch and the queries.
 */
const read = (rel: string) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8')
const strip = (source: string) => source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .split('\n')
    .map(line => line.replace(/(^|[^:])\/\/.*$/, '$1'))
    .join('\n')

const source = read('./ReleaseView.vue')
const code = strip(source)

/** The text of a top-level `const name ... = ` declaration up to the next top-level declaration. */
function declaration (name: string): string {
    const start = code.indexOf(`const ${name}`)
    expect(start, `${name} is declared`).toBeGreaterThanOrEqual(0)
    const next = code.slice(start + 1).search(/\n(const|function|let) /)
    return next < 0 ? code.slice(start) : code.slice(start, start + 1 + next)
}

describe('ReleaseView Generated artifacts section', () => {
    it('imports and calls generatedArtifactRows', () => {
        expect(source).toMatch(/import\s+\{[^}]*\bgeneratedArtifactRows\b[^}]*\}\s+from\s+'@\/utils\/generatedArtifacts'/)
        expect(code).toMatch(/generatedArtifactRows\(updatedRelease\.value\)/)
    })

    it('renders the section with its own table', () => {
        expect(code).toContain('<h3>Generated artifacts</h3>')
        expect(code).toMatch(/:data="generatedArtifacts"\s+:columns="generatedArtifactsTableFields"/)
    })

    it('offers download only: no delete, edit, tag or Dependency-Track action', () => {
        const fields = declaration('generatedArtifactsTableFields')
        expect(fields).toContain('openDownloadArtifactModal(row)')
        for (const forbidden of ['Trash', 'Edit', 'Tag)', 'renderArtifactDtrackActions', 'deleteArtifactFromRelease',
            'uploadNewBomVersion', 'openEditArtifactTagsModal', 'isWritable']) {
            expect(fields, `generatedArtifactsTableFields must not contain ${forbidden}`).not.toContain(forbidden)
        }
    })

    it('keeps the inventory artifacts computed blind to the generated list', () => {
        expect(declaration('artifacts:')).not.toContain('syntheticArtifactDetails')
    })

    it('names SYNTHETIC_ARTIFACT events in the History renderer', () => {
        expect(code).toMatch(/row\.rus === 'SYNTHETIC_ARTIFACT'[\s\S]{0,120}syntheticHistoryText\(row\.objectId, updatedRelease\.value\)/)
    })
})

describe('release queries carry syntheticArtifactDetails', () => {
    const queries = strip(read('../utils/graphqlQueries.ts'))

    it.each(['singleReleaseDataNoParent', 'singleReleaseProductNoParent'])('%s selects it', (fragment) => {
        const start = queries.indexOf(`const ${fragment} =`)
        expect(start).toBeGreaterThanOrEqual(0)
        const end = queries.indexOf('`', queries.indexOf('`', start) + 1)
        expect(queries.slice(start, end)).toMatch(/syntheticArtifactDetails\s*\{/)
    })
})
