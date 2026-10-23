import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'

// The unassign link of the drawer's stale line (task TPF-69, mutation round). TaskActions is pinned by source text,
// as the panel is: the reason input takes the focus and is scrolled into view without moving the page.
describe('TaskActions: focusUnassign', () => {
    it('focuses the reason input and scrolls it into view nearest', () => {
        const src = readFileSync(fileURLToPath(new URL('./TaskActions.vue', import.meta.url)), 'utf8')
        const at = src.indexOf('function focusUnassign () {')
        expect(at).toBeGreaterThan(-1)
        const body = src.slice(at, src.indexOf('\n}', at))
        expect(body).toContain('unassignInput.value?.focus()')
        expect(body).toContain("scrollIntoView?.({ block: 'nearest' })")
    })
})
