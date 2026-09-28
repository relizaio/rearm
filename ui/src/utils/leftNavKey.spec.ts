import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import { join } from 'path'
import { navKeyOf } from './leftNavKey'

// RD2-19 (sweep UI-40): the task page lit Home, as its route was in no map. Every AI Agents page now says in its
// route meta which entry it lights, and the nav reads that before the name map.
const router = readFileSync(join(process.cwd(), 'src/router.ts'), 'utf8')
const nav = readFileSync(join(process.cwd(), 'src/components/LeftNavBar.vue'), 'utf8')

/** The route record that declares this name, as the router source has it. */
function recordOf (name: string): string {
    const at = router.indexOf(`name: '${name}',`)
    expect(at, name).toBeGreaterThan(-1)
    return router.slice(router.lastIndexOf('{', at), router.indexOf('}', router.indexOf('component:', at)) + 1)
}

describe('the side nav entry a route lights', () => {
    it('is AI Agents for the task, session and agent pages, from their meta', () => {
        for (const name of ['AiAgentTaskPage', 'AiAgentSessionView', 'AiAgentView', 'AiAgentsOfOrg', 'AiAgentsTableOfOrg',
            'AiAgentPolicyView']) {
            expect(recordOf(name), name).toContain("meta: { nav: 'aiAgents' }")
            expect(navKeyOf(name, { nav: 'aiAgents' }), name).toBe('aiAgents')
        }
    })

    it('is still Components for a component route, and nothing for a route no entry owns', () => {
        expect(navKeyOf('ComponentsOfOrg', {})).toBe('components')
        expect(navKeyOf('home', undefined)).toBe('home')
        expect(navKeyOf('SomethingElse', {})).toBeNull()
        expect(navKeyOf(undefined, null)).toBeNull()
    })

    it('reads the route meta first, in the nav itself', () => {
        expect(nav).toContain('const menuKey = navKeyOf(route.name, route.meta as Record<string, unknown>)')
        expect(nav).toContain('watch(() => [route.name, route.meta]')
    })
})
