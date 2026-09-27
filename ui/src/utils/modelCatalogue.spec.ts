import { describe, expect, it } from 'vitest'
import {
    bundleOptions, canonicalIsFree, hasDuplicate, isSyntheticRow, mergeDirection, mergeSurvivor, modelDeclaredAs, modelDraftOf,
    modelEditChanged, modelFieldOfError, modelLabel, modelUpdateInput, sharedCanonicalIds, unresolvedCount, usageLabel,
} from './modelCatalogue'

// The model catalogue page (RD2-27).
const opus = { uuid: 'o1', name: 'claude-opus-5-5', version: '1', canonicalId: 'claude-opus-5-5', resolution: 'RESOLVED',
    pricing: [{ uuid: 'p1' }], createdDate: '2026-09-01T00:00:00Z' }
const fable = { uuid: 'f1', name: 'claude-fable-5-1', version: '5.1', canonicalId: 'claude-fable-5-1', resolution: 'RESOLVED',
    pricing: [], createdDate: '2026-08-01T00:00:00Z' }
const local = { uuid: 'l1', name: 'house-model', version: 'unknown', canonicalId: null, resolution: 'UNRESOLVED',
    pricing: [], createdDate: '2026-07-01T00:00:00Z' }
const synthetic = { uuid: 's1', name: 'synthetic', version: 'unknown', resolution: 'SYNTHETIC' }

describe('a row\'s label', () => {
    it('is the canonical id of a resolved row and the declared name otherwise, never name and version joined', () => {
        expect(modelLabel(opus)).toBe('claude-opus-5-5')
        expect(modelLabel(local)).toBe('house-model')
        expect(modelLabel({ ...opus, resolution: 'UNRESOLVED' })).toBe('claude-opus-5-5')
        expect(modelLabel({})).toBe('(unnamed)')
        expect(modelLabel(opus)).not.toContain(' 1')
    })

    it('says what was declared on a second line, leaving out an unknown version', () => {
        expect(modelDeclaredAs(opus)).toBe('declared as claude-opus-5-5 · v1')
        expect(modelDeclaredAs(fable)).toBe('declared as claude-fable-5-1 · v5.1')
        expect(modelDeclaredAs(local)).toBe('declared as house-model')
        expect(modelDeclaredAs({ name: 'x', version: 'UNKNOWN' })).toBe('declared as x')
    })
})

describe('the usage column and the banner', () => {
    it('reads sessions and lines, or unused', () => {
        expect(usageLabel({ sessions: 3, lines: 12 })).toBe('3 sessions · 12 lines')
        expect(usageLabel({ sessions: 1, lines: 1 })).toBe('1 session · 1 line')
        expect(usageLabel({ sessions: 0, lines: 0 })).toBe('unused')
        expect(usageLabel(null)).toBe('unused')
    })

    it('counts unresolved rows, never the synthetic pseudo-row', () => {
        const legacyPlaceholder = { name: '<synthetic>', version: 'unknown', resolution: 'UNRESOLVED' }
        expect(isSyntheticRow(synthetic)).toBe(true)
        expect(isSyntheticRow(legacyPlaceholder)).toBe(true)
        expect(isSyntheticRow(local)).toBe(false)
        expect(unresolvedCount([opus, local, synthetic, legacyPlaceholder])).toBe(1)
        expect(unresolvedCount([])).toBe(0)
    })
})

describe('a merge', () => {
    it('keeps the resolved row with pricing, else a resolved one, else the older', () => {
        expect(mergeSurvivor(fable, opus)).toEqual({ survivor: opus, folded: fable })
        expect(mergeSurvivor(local, fable)).toEqual({ survivor: fable, folded: local })
        const olderLocal = { ...local, uuid: 'l0', createdDate: '2026-06-01T00:00:00Z' }
        expect(mergeSurvivor(local, olderLocal).survivor.uuid).toBe('l0')
        expect(mergeSurvivor(olderLocal, local).survivor.uuid).toBe('l0')
    })

    it('is hinted when two rows share a canonical id, in any case', () => {
        const twin = { ...fable, uuid: 'f2', canonicalId: 'CLAUDE-FABLE-5-1' }
        expect(sharedCanonicalIds([opus, fable, twin, local])).toEqual(['claude-fable-5-1'])
        expect(hasDuplicate(fable, [opus, fable, twin])).toBe(true)
        expect(hasDuplicate(opus, [opus, fable, twin])).toBe(false)
        expect(hasDuplicate(local, [local, { ...local, uuid: 'l2' }])).toBe(false, 'no canonical id, no duplicate')
    })
})

describe('the edit drawer', () => {
    it('prefills the canonical id a row lacks with the suggestion', () => {
        expect(modelDraftOf({ ...local, suggestedCanonicalId: 'claude-sonnet-5' }).canonicalId).toBe('claude-sonnet-5')
        expect(modelDraftOf(opus).canonicalId).toBe('claude-opus-5-5')
        expect(modelDraftOf(local).version).toBe('', 'unknown reads as blank')
    })

    it('sends the uuid and only what changed', () => {
        const d = modelDraftOf(opus)
        expect(modelUpdateInput(opus, d)).toEqual({ uuid: 'o1' })
        expect(modelEditChanged(opus, d)).toBe(false)
        d.version = ' 5.5 '
        d.description = 'the big one'
        d.tier = 'FRONTIER'
        expect(modelUpdateInput(opus, d)).toEqual({ uuid: 'o1', version: '5.5', description: 'the big one', tier: 'FRONTIER' })
        expect(modelEditChanged(opus, d)).toBe(true)
    })

    it('clears a canonical id as null, and sends a strength cleared as null', () => {
        const d = modelDraftOf(opus)
        d.canonicalId = '  '
        d.strength = 4
        expect(modelUpdateInput(opus, d)).toEqual({ uuid: 'o1', canonicalId: null, strength: 4 })
        const rated = { ...opus, strength: 3 }
        const unrate = modelDraftOf(rated)
        unrate.strength = null
        expect(modelUpdateInput(rated, unrate)).toEqual({ uuid: 'o1', strength: null })
        const suggested = { ...local, suggestedCanonicalId: 'claude-sonnet-5' }
        expect(modelUpdateInput(suggested, modelDraftOf(suggested))).toEqual({ uuid: 'l1', canonicalId: 'claude-sonnet-5' },
            'accepting the suggestion is a change')
    })

    it('says when a canonical id is free text the bundle does not price', () => {
        const bundle = [{ canonicalId: 'claude-opus-5', publisher: 'Anthropic' }, { canonicalId: 'claude-fable-5-1' }]
        expect(canonicalIsFree('house/model-2', bundle)).toBe(true)
        expect(canonicalIsFree('CLAUDE-OPUS-5', bundle)).toBe(false)
        expect(canonicalIsFree('', bundle)).toBe(false)
        expect(bundleOptions(bundle)).toEqual([{ label: 'claude-opus-5 — Anthropic', value: 'claude-opus-5' },
            { label: 'claude-fable-5-1', value: 'claude-fable-5-1' }])
    })

    it('puts each refusal beside its field', () => {
        expect(modelFieldOfError('Model claude-fable version 5.1 is already a row of this organization; merge this row into it instead'))
            .toBe('name')
        expect(modelFieldOfError('A model needs a name')).toBe('name')
        expect(modelFieldOfError('Model claude-fable 5.1 already carries canonical id claude-fable-5-1; merge this row into it instead'))
            .toBe('canonicalId')
        expect(modelFieldOfError('strength must be at most 10')).toBe('strength')
        expect(modelFieldOfError('Not authorized')).toBeNull()
    })
})

// Tester run 1 T-2: duplicates share a canonical id, so a label alone cannot tell them apart.
describe('the merge sentence', () => {
    it('names each row by its label and what it was declared as', () => {
        const priced = { uuid: 'a', name: 'claude-sonnet', version: '5', canonicalId: 'claude-sonnet-5', resolution: 'RESOLVED' }
        const twin = { uuid: 'b', name: 'rd27-foo', version: 'unknown', canonicalId: 'claude-sonnet-5', resolution: 'RESOLVED' }
        expect(mergeDirection({ survivor: priced, folded: twin })).toBe('claude-sonnet-5 (declared as rd27-foo) will point at '
            + 'the survivor, claude-sonnet-5 (declared as claude-sonnet · v5).')
        expect(mergeDirection({ survivor: local, folded: { ...local, uuid: 'l2', name: 'house-model-2' } }))
            .toBe('house-model-2 (declared as house-model-2) will point at the survivor, house-model (declared as house-model), the older row.')
        expect(mergeDirection(null)).toBe('')
    })
})
