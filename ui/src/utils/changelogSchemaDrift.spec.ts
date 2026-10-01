import { describe, it, expect } from 'vitest'
import { parse, validate, type GraphQLSchema } from 'graphql'
import {
    stripAdditiveFields,
    COMPONENT_CHANGELOG_QUERY,
    COMPONENT_CHANGELOG_BY_DATE_QUERY,
    ORGANIZATION_CHANGELOG_BY_DATE_QUERY
} from './changelogQueryTexts'
import { ceSchema, proSchema } from './schemaDriftSupport'

// The changelog queries carry #additive-field-tagged selections (re-scan
// arrival attribution + baseline flag) that exist on Pro before the CE mirror
// catches up. At runtime queryWithAdditiveFallback strips the tagged lines and
// retries when the deployed schema rejects the full document. This spec pins
// that contract the same way notificationInboxSchemaDrift.spec.ts pins the
// inbox split: the STRIPPED text must always validate against the CE mirror
// (in-repo, unconditional), the FULL text against Pro (skipped when the
// sibling rearm-core checkout is absent, e.g. in this repo's own CI).
const queryEntries: [string, string][] = [
    ['COMPONENT_CHANGELOG_QUERY', COMPONENT_CHANGELOG_QUERY],
    ['COMPONENT_CHANGELOG_BY_DATE_QUERY', COMPONENT_CHANGELOG_BY_DATE_QUERY],
    ['ORGANIZATION_CHANGELOG_BY_DATE_QUERY', ORGANIZATION_CHANGELOG_BY_DATE_QUERY]
]

describe('changelog stripped selections vs the CE mirror schema (in-repo, always runs)', () => {
    // The load-bearing invariant: the fallback document every CE install
    // degrades to must validate there, or the changelog blanks for CE users.
    for (const [name, text] of queryEntries) {
        it(`${name} stripped of additive fields is valid against the CE mirror`, () => {
            expect(validate(ceSchema, parse(stripAdditiveFields(text))).map(e => e.message)).toEqual([])
        })
    }

    // NOTE: this suite used to also assert that at least one FULL document was
    // INVALID against the CE mirror ("fallback is load-bearing") as a reminder
    // to untag fields once the mirror caught up. It caught up with the re-scan
    // visibility fields (2026-08 Pro sync), the lines were untagged, and the
    // reminder test retired with them. The stripped-vs-CE invariant above stays:
    // it is what guarantees the runtime fallback document remains valid if
    // future Pro-first fields get tagged again.
})

describe('changelog full selections vs the Pro schema (skipped if rearm-core absent)', () => {
    for (const [name, text] of queryEntries) {
        it.runIf(proSchema)(`${name} full document is valid against Pro (source of truth)`, () => {
            expect(validate(proSchema as GraphQLSchema, parse(text)).map(e => e.message)).toEqual([])
        })
    }
})
