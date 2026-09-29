import { describe, it, expect } from 'vitest'
import { validate, visit, print, parse, Kind, type DocumentNode, type GraphQLSchema } from 'graphql'
import {
    ARTIFACT_FINDINGS_QUERY,
    ARTIFACT_FINDINGS_QUERY_CORE,
    ARTIFACT_FINDINGS_QUERY_SCORED,
    RELEASE_FINDINGS_QUERY,
    RELEASE_FINDINGS_QUERY_CORE,
    RELEASE_FINDINGS_QUERY_LATEST,
    RELEASE_FINDINGS_QUERY_MATCHED,
    RELEASE_FINDINGS_QUERY_SCORED
} from './findingsQuery'
import { ceSchema, enumValuesOf, proSchema } from './schemaDriftSupport'
import { FIXED_IN_TITLES } from './fixedInDisplay'
import { FindingSbomMissReason } from '@/constants/findingSbomMissReason'
import { LatestFixVerdict } from '@/constants/latestFixVerdict'

// The findings modal's two loads (a release's findings, an artifact's
// findings) checked against the backend schemas. Before these documents moved
// out of the service and the component, nothing validated them: the release
// selection interpolated ${...} (skipped by scripts/validate-graphql) and the
// artifact query was inline in ReleaseView.vue.
//
// Each load is three documents, richest first (loadFindings): FULL adds the
// per-finding scores and fix versions (and each component's fix targets),
// SCORED the scores only, and CORE is everything else, the fallback for a
// backend without them (a build older than the fields), which must validate on
// CE or the modal blanks there. The release load has two more on top: MATCHED,
// FULL plus each finding's SBOM component, and LATEST, MATCHED plus each
// component's latest version (see the end of the file).
//
// CE ships in this repo; Pro is checked when a sibling rearm-core checkout is
// present, which is never in this repo's own CI.

const errorsAgainst = (schema: GraphQLSchema, doc: DocumentNode) => validate(schema, doc).map(e => e.message)

const SCORE_FIELDS = new Set(['scores', 'topScore', 'epss'])
const FIX_FIELDS = new Set(['fixedIn', 'fixTargets'])
const SBOM_FIELDS = new Set(['sbomMatch'])
const LATEST_FIELDS = new Set(['latestVersion', 'latestVersionChecked', 'latestFix'])

// A document with the given fields taken out, and the score fragment with them
// when the scores go.
function without (doc: DocumentNode, fields: Set<string>): string {
    return print(visit(doc, {
        Field: node => fields.has(node.name.value) ? null : undefined,
        FragmentDefinition: () => fields.has('scores') ? null : undefined
    }))
}

const TIERS: Array<[string, DocumentNode, DocumentNode, DocumentNode]> = [
    ['release findings', RELEASE_FINDINGS_QUERY, RELEASE_FINDINGS_QUERY_SCORED, RELEASE_FINDINGS_QUERY_CORE],
    ['artifact findings', ARTIFACT_FINDINGS_QUERY, ARTIFACT_FINDINGS_QUERY_SCORED, ARTIFACT_FINDINGS_QUERY_CORE]
]

describe('findings modal documents vs the Pro schema (skipped if rearm-core absent)', () => {
    it.runIf(proSchema).each(TIERS)('%s FULL is valid against Pro', (_name, full) => {
        expect(errorsAgainst(proSchema as GraphQLSchema, full)).toEqual([])
    })
    it.runIf(proSchema).each(TIERS)('%s SCORED is valid against Pro', (_name, _full, scored) => {
        expect(errorsAgainst(proSchema as GraphQLSchema, scored)).toEqual([])
    })
    it.runIf(proSchema).each(TIERS)('%s CORE is valid against Pro', (_name, _full, _scored, core) => {
        expect(errorsAgainst(proSchema as GraphQLSchema, core)).toEqual([])
    })
})

describe('findings modal documents vs the CE mirror schema (in-repo, always runs)', () => {
    it.each(TIERS)('%s CORE is valid against CE', (_name, _full, _scored, core) => {
        expect(errorsAgainst(ceSchema, core)).toEqual([])
    })

    it.each(TIERS)('%s SCORED is valid against CE', (_name, _full, scored) => {
        expect(errorsAgainst(ceSchema, scored)).toEqual([])
    })

    /**
     * NOTE: this used to assert FULL was still ahead of CE by the fix versions
     * and fix targets. The 2026-09 CE sync (#472) brought them over, so that
     * canary was retired.
     * The SCORED and CORE fallbacks stay for a backend without the fields (a build
     * older than them); what keeps them honest is "the tiers stay one document".
     */
    it.each(TIERS)('%s FULL is valid against CE', (_name, full) => {
        expect(errorsAgainst(ceSchema, full)).toEqual([])
    })
})

describe('the tiers stay one document', () => {
    // A field added to one tier and not the others would make the modal differ by
    // backend in more than the optional columns.
    it.each(TIERS)('%s FULL is SCORED plus the fix versions, and SCORED is CORE plus the scores', (_name, full, scored, core) => {
        expect(without(full, FIX_FIELDS)).toBe(print(scored))
        expect(without(scored, SCORE_FIELDS)).toBe(print(core))
        expect(print(full)).not.toBe(print(scored))
        expect(print(scored)).not.toBe(print(core))
    })

    it('selects the scores and fix versions on the release\'s own findings only, not on its parents\'', () => {
        const listsSelecting = (field: string) => {
            let lists = 0
            visit(RELEASE_FINDINGS_QUERY, {
                Field: node => {
                    if (node.name.value !== 'vulnerabilityDetails') return
                    if (node.selectionSet?.selections.some(s => s.kind === Kind.FIELD && s.name.value === field)) lists++
                }
            })
            return lists
        }
        expect(listsSelecting('topScore')).toBe(1)
        expect(listsSelecting('fixedIn')).toBe(1)
        let metricsWithTargets = 0
        visit(RELEASE_FINDINGS_QUERY, {
            Field: node => {
                if (node.name.value === 'fixTargets') metricsWithTargets++
            }
        })
        expect(metricsWithTargets).toBe(1)
    })
})

describe('fix version verdicts vs the schemas', () => {
    // The Fixed in column labels every verdict; one the schema adds would show raw.
    it('the column labels exactly the FixedInVerdict values on CE', () => {
        expect(Object.keys(FIXED_IN_TITLES).sort()).toEqual(enumValuesOf(ceSchema, 'FixedInVerdict').sort())
    })

    it.runIf(proSchema)('and on Pro (skipped if rearm-core absent)', () => {
        expect(Object.keys(FIXED_IN_TITLES).sort()).toEqual(enumValuesOf(proSchema as GraphQLSchema, 'FixedInVerdict').sort())
    })
})

// The release load has a fourth document on top, MATCHED: FULL plus each
// finding's SBOM component (Vulnerability.sbomMatch, rearm-saas#705), which
// the grouped view keys on. Release findings only.
describe('the release findings MATCHED document', () => {
    it.runIf(proSchema)('is valid against Pro (skipped if rearm-core absent)', () => {
        expect(errorsAgainst(proSchema as GraphQLSchema, RELEASE_FINDINGS_QUERY_MATCHED)).toEqual([])
    })

    /**
     * TEMPORARY: CE has not mirrored sbomMatch yet, so the modal falls back to
     * FULL there and groups on the client. Once the CE sync brings the field
     * over, flip this to "is valid against CE" (as the F2 canaries were retired
     * after #472) and add a CE run of the FindingSbomMissReason mirror check below.
     */
    it('is ahead of CE by sbomMatch only', () => {
        expect(errorsAgainst(ceSchema, RELEASE_FINDINGS_QUERY_MATCHED).length).toBeGreaterThan(0)
        expect(errorsAgainst(ceSchema, parse(without(RELEASE_FINDINGS_QUERY_MATCHED, SBOM_FIELDS)))).toEqual([])
    })

    it('is FULL plus sbomMatch, selected on the release\'s own findings only', () => {
        expect(without(RELEASE_FINDINGS_QUERY_MATCHED, SBOM_FIELDS)).toBe(print(RELEASE_FINDINGS_QUERY))
        let lists = 0
        visit(RELEASE_FINDINGS_QUERY_MATCHED, {
            Field: node => {
                if (node.name.value !== 'vulnerabilityDetails') return
                if (node.selectionSet?.selections.some(s => s.kind === Kind.FIELD && s.name.value === 'sbomMatch')) lists++
            }
        })
        expect(lists).toBe(1)
    })

    it.runIf(proSchema)('the miss reasons mirror FindingSbomMissReason on Pro (skipped if rearm-core absent)', () => {
        expect(Object.values(FindingSbomMissReason).sort())
            .toEqual(enumValuesOf(proSchema as GraphQLSchema, 'FindingSbomMissReason').sort())
    })
})

// LATEST: MATCHED plus each finding's component's latest version and whether it
// fixes the finding (rearm-saas PR G), the group view's Latest column.
describe('the release findings LATEST document', () => {
    it.runIf(proSchema)('is valid against Pro (skipped if rearm-core absent)', () => {
        expect(errorsAgainst(proSchema as GraphQLSchema, RELEASE_FINDINGS_QUERY_LATEST)).toEqual([])
    })

    it('is MATCHED plus the latest version and latestFix', () => {
        expect(without(RELEASE_FINDINGS_QUERY_LATEST, LATEST_FIELDS)).toBe(print(RELEASE_FINDINGS_QUERY_MATCHED))
    })

    /**
     * TEMPORARY: CE has mirrored neither sbomMatch nor the latest version yet.
     * Flip to "is valid against CE" with the MATCHED canary once the CE sync of
     * PR G brings them over.
     */
    it('is ahead of CE by latestFix', () => {
        expect(errorsAgainst(ceSchema, RELEASE_FINDINGS_QUERY_LATEST).some(e => /latestFix/.test(e))).toBe(true)
    })

    it.runIf(proSchema)('the verdicts mirror LatestFixVerdict on Pro (skipped if rearm-core absent)', () => {
        expect(Object.values(LatestFixVerdict).sort())
            .toEqual(enumValuesOf(proSchema as GraphQLSchema, 'LatestFixVerdict').sort())
    })
})
