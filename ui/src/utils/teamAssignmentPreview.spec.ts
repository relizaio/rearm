import { describe, it, expect } from 'vitest'
import { previewMatches } from './teamAssignmentPreview'

const comps = [
    { name: 'rebom-backend', type: 'COMPONENT' },
    { name: 'rebom-frontend', type: 'COMPONENT' },
    { name: 'Rebom Suite', type: 'PRODUCT' },
    { name: 'unrelated', type: 'COMPONENT' },
]

describe('previewMatches', () => {
    it('anchors fully, matching the backend Pattern.matcher(...).matches()', () => {
        // "rebom-" would match as a prefix but must NOT match the whole name.
        expect(previewMatches('rebom-', 'ANY', comps).total).toBe(0)
        expect(previewMatches('rebom-.*', 'ANY', comps).total).toBe(2)
    })

    it('honours the component type filter', () => {
        expect(previewMatches('.*', 'PRODUCT', comps).total).toBe(1)
        expect(previewMatches('.*', 'COMPONENT', comps).total).toBe(3)
        expect(previewMatches('.*', 'ANY', comps).total).toBe(4)
    })

    it('truncates and reports the remainder', () => {
        const many = Array.from({ length: 12 }, (_, i) => ({ name: `c${i}`, type: 'COMPONENT' }))
        const r = previewMatches('c.*', 'ANY', many, 8)
        expect(r.names).toHaveLength(8)
        expect(r.more).toBe(4)
        expect(r.total).toBe(12)
    })

    it('reports not-previewable rather than throwing on a JS-invalid pattern', () => {
        const r = previewMatches('[unclosed', 'ANY', comps)
        expect(r.previewable).toBe(false)
        expect(r.total).toBe(0)
    })

    it('treats Java-only syntax as not-previewable, not invalid', () => {
        // Possessive quantifiers are valid Java and rejected by JS. The caller
        // must NOT block saving on this -- the server is the authority.
        const r = previewMatches('a*+', 'ANY', comps)
        expect(r.previewable).toBe(false)
    })

    it('empty pattern previews nothing without erroring', () => {
        const r = previewMatches('', 'ANY', comps)
        expect(r.previewable).toBe(true)
        expect(r.total).toBe(0)
    })
})
