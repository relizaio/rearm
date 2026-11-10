import { describe, it, expect } from 'vitest'
import { Kind, parse, validate, type FieldNode, type GraphQLSchema, type SelectionSetNode } from 'graphql'
import { SBOM_COMPONENT_OVERLAY_DETAIL, SET_SBOM_COMPONENT_OVERLAY } from './overlayInput'
import { DESCRIBE_VENDORED_COMPONENT, RELEASE_UNIDENTIFIED_COMPONENTS, REMOVE_VENDORED_DESCRIPTION,
    VENDORED_DESCRIPTIONS_BY_NAME, VENDORED_IDENTIFIER } from './vendoredComponents'
import { SET_VENDORED_COMPONENT_SUPPORT } from './setSbomComponentSupport'
import { BULK_SET_SUPPLIER_BY_SELECTOR } from './bulkSelectorForm'
import { SBOM_COMPONENT_SUPPORT_DETAIL } from './sbomComponentSupportDetail'
import { ceSchema, proSchema } from './schemaDriftSupport'

/**
 * FDAW-51 3.3.4 (F-4): the static validation of the overlay documents. The specs of rows 14 to
 * 16 mock the client, so a typo in one of these would otherwise first surface at request
 * time. Each is validated against the CE mirror's schema, which this PR carries, and against
 * Pro's when the rearm-core checkout (or REARM_PRO_SCHEMA) is present.
 */
const DOCUMENTS: Array<[string, any]> = [
    ['SBOM_COMPONENT_OVERLAY_DETAIL', SBOM_COMPONENT_OVERLAY_DETAIL],
    ['SET_SBOM_COMPONENT_OVERLAY', SET_SBOM_COMPONENT_OVERLAY],
    ['RELEASE_UNIDENTIFIED_COMPONENTS', RELEASE_UNIDENTIFIED_COMPONENTS],
    ['VENDORED_IDENTIFIER', VENDORED_IDENTIFIER],
    ['VENDORED_DESCRIPTIONS_BY_NAME', VENDORED_DESCRIPTIONS_BY_NAME],
    ['DESCRIBE_VENDORED_COMPONENT', DESCRIBE_VENDORED_COMPONENT],
    ['REMOVE_VENDORED_DESCRIPTION', REMOVE_VENDORED_DESCRIPTION],
    ['SET_VENDORED_COMPONENT_SUPPORT', SET_VENDORED_COMPONENT_SUPPORT],
    ['BULK_SET_SUPPLIER_BY_SELECTOR', BULK_SET_SUPPLIER_BY_SELECTOR]
]

function errorsAgainst (schema: GraphQLSchema, doc: any): string[] {
    return validate(schema, parse(doc.loc.source.body)).map(e => e.message)
}

describe('the overlay documents', () => {
    it('are nine', () => {
        expect(DOCUMENTS).toHaveLength(9)
    })

    it.each(DOCUMENTS)('%s: parses', (_name, doc) => {
        expect(() => parse(doc.loc.source.body)).not.toThrow()
    })

    it.each(DOCUMENTS)('%s: validates against the CE schema', (_name, doc) => {
        expect(errorsAgainst(ceSchema, doc)).toEqual([])
    })

    /** runIf, never an early return: an absent Pro schema must read SKIPPED, not PASSED. */
    it.runIf(proSchema).each(DOCUMENTS)('%s: validates against the Pro schema', (_name, doc) => {
        expect(errorsAgainst(proSchema as GraphQLSchema, doc)).toEqual([])
    })
})

/** The leaf paths of a selection set, `a`, `b { c }` read as `a`, `b.c`. */
function leafPaths (set: SelectionSetNode | undefined, prefix = ''): string[] {
    const out: string[] = []
    for (const s of set?.selections ?? []) {
        if (s.kind !== Kind.FIELD) continue
        const path = prefix + s.name.value
        if (s.selectionSet) out.push(...leafPaths(s.selectionSet, path + '.'))
        else out.push(path)
    }
    return out.sort()
}

/** The selection under `component` of the document's one root field. */
function componentSelection (doc: any): string[] {
    const op = parse(doc.loc.source.body).definitions[0] as any
    const root = op.selectionSet.selections[0] as FieldNode
    const component = root.selectionSet?.selections
        .find(s => s.kind === Kind.FIELD && s.name.value === 'component') as FieldNode | undefined
    return leafPaths(component?.selectionSet)
}

/**
 * FDAW-51 3.3.1 (row 14): the vendored Attest form seeds from the panel's document, so its
 * selection under `component` must name every field the component form's seed names, or a
 * re-opened vendored attestation loses a stored fact the component form keeps.
 */
describe('the vendored seed selection', () => {
    it('names every field SBOM_COMPONENT_SUPPORT_DETAIL selects under component', () => {
        const seed = componentSelection(SBOM_COMPONENT_SUPPORT_DETAIL)
        expect(seed.length).toBeGreaterThan(10)
        expect(componentSelection(RELEASE_UNIDENTIFIED_COMPONENTS)).toEqual(seed)
    })
})
