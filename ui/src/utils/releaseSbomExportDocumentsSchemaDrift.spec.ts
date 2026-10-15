import { describe, it, expect } from 'vitest'
import { validate } from 'graphql'
import graphqlQueries from './graphqlQueries'
import { ceSchema, proSchema } from './schemaDriftSupport'
import { SBOM_EXPORT_CORE, SBOM_EXPORT_WITH_FILE_SWITCH, SBOM_EXPORT_WITH_METADATA_FLAGS } from './releaseSbomExportDocuments'

// The release BOM export documents and the release score query against both schemas (SCORE-11,
// T-10). The file switch is in Pro, and the CE backend in this repo has it since the CE sync of
// October 2026 (SCORE-11 ADR-7), so every document validates on both. A CE backend older than
// that rejects the switched document as drift, which the dialog answers by disabling the switch,
// while the two documents sent with the switch off stay valid there.
const EXPORT_DOCUMENTS: Array<[string, any]> = [
    ['SBOM_EXPORT_WITH_FILE_SWITCH', SBOM_EXPORT_WITH_FILE_SWITCH],
    ['SBOM_EXPORT_WITH_METADATA_FLAGS', SBOM_EXPORT_WITH_METADATA_FLAGS],
    ['SBOM_EXPORT_CORE', SBOM_EXPORT_CORE],
]

function argumentNames (doc: any): string[] {
    return doc.definitions[0].selectionSet.selections[0].arguments.map((a: any) => a.name.value)
}

describe('release BOM export documents vs the schemas', () => {
    it.runIf(proSchema).each(EXPORT_DOCUMENTS)('%s is valid against the Pro schema', (_name, doc) => {
        expect(validate(proSchema as any, doc).map(e => e.message)).toEqual([])
    })

    it('the switched export and the release score both name excludeFileComponents', () => {
        expect(argumentNames(SBOM_EXPORT_WITH_FILE_SWITCH)).toContain('excludeFileComponents')
        expect(argumentNames(graphqlQueries.ReleaseSbomScoreGql)).toContain('excludeFileComponents')
    })

    it('only the switched document names it: the switch-off exports are what they were', () => {
        expect(argumentNames(SBOM_EXPORT_WITH_METADATA_FLAGS)).not.toContain('excludeFileComponents')
        expect(argumentNames(SBOM_EXPORT_CORE)).not.toContain('excludeFileComponents')
        expect(argumentNames(SBOM_EXPORT_WITH_FILE_SWITCH).filter(a => a !== 'excludeFileComponents'))
            .toEqual(argumentNames(SBOM_EXPORT_WITH_METADATA_FLAGS))
    })

    it('the switch-off documents are valid against the CE schema', () => {
        expect(validate(ceSchema, SBOM_EXPORT_WITH_METADATA_FLAGS).map(e => e.message)).toEqual([])
        expect(validate(ceSchema, SBOM_EXPORT_CORE).map(e => e.message)).toEqual([])
    })

    it('the switched document is valid against the CE schema', () => {
        expect(validate(ceSchema, SBOM_EXPORT_WITH_FILE_SWITCH).map(e => e.message)).toEqual([])
    })
})
