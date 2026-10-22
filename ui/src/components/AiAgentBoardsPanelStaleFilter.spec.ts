import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'

const source = readFileSync(fileURLToPath(new URL('./AiAgentBoardsPanel.vue', import.meta.url)), 'utf8')

// The kanban's "stale only" checkbox handler (task TPF-69, mutation round). The panel is pinned by source
// text in this repository, so the handler is: the checkbox value as a boolean, then the URL sync.
describe('the kanban: stale only handler', () => {
    it('stores the checkbox value as it is, then keeps it in the URL', () => {
        const at = source.indexOf('function setStaleOnly (v: boolean) {')
        expect(at).toBeGreaterThan(-1)
        const body = source.slice(at, source.indexOf('\n}', at))
        expect(body).toContain('staleOnly.value = !!v\n')
        expect(body.indexOf('staleOnly.value = !!v')).toBeLessThan(body.indexOf('syncQuery()'))
    })
})
