import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import { join } from 'path'
import { parse as parseSfc } from '@vue/compiler-sfc'

// T-543 (TPF-185 design 3.13): an upstream alert row and its detail link the follow or the source,
// beside the agent links and built the same way. The drawer is not mounted here (it loads the
// inbox); the wiring is read from what the build ships, and the links themselves from
// upstreamInboxLinksOf, whose spec covers them. The agent wiring stays as it is
// (NotificationInbox.agentLinks.spec.ts).
const source = readFileSync(join(process.cwd(), 'src/components/NotificationInbox.vue'), 'utf8')
const template = parseSfc(source).descriptor.template?.content ?? ''

/** The opening tag of the element that carries this data-testid. */
function tagWith (testId: string): string {
    const at = template.indexOf(`data-testid="${testId}"`)
    expect(at, testId).toBeGreaterThan(-1)
    const start = template.lastIndexOf('<', at)
    const end = template.indexOf('>', at)
    return template.slice(start, end + 1)
}

describe('the inbox links an upstream alert to its follow and its source', () => {
    it('reads the links of each row and of the drawer from upstreamInboxLinksOf with the installation type', () => {
        expect(source).toContain("import { upstreamInboxLinksOf } from '@/utils/upstreamInboxLinks'")
        expect(source).toContain('upstreamLinks: upstreamInboxLinksOf(row, myuser.value?.installationType),')
        expect(source).toContain('const drawerUpstreamLinks = computed(() => upstreamInboxLinksOf(inboxDrawerRow.value, myuser.value?.installationType))')
        expect(source.match(/upstreamInboxLinksOf\(/g)).toHaveLength(2)
    })

    it('in the row, each link calls goTo with its link', () => {
        expect(tagWith('inbox-open-follow')).toContain('@click.prevent.stop="goTo(c.upstreamLinks.follow)"')
        expect(tagWith('inbox-open-follow')).toContain('v-if="c.upstreamLinks.follow"')
        expect(tagWith('inbox-open-upstream-source')).toContain('@click.prevent.stop="goTo(c.upstreamLinks.source)"')
        expect(tagWith('inbox-open-upstream-source')).toContain('v-if="c.upstreamLinks.source"')
        expect(template).toContain('@click.prevent.stop="goTo(c.upstreamLinks.follow)">Open follow</a>')
        expect(template).toContain('@click.prevent.stop="goTo(c.upstreamLinks.source)">Open source</a>')
    })

    it('in the detail, each button calls goTo with its link', () => {
        expect(tagWith('inbox-drawer-open-follow')).toContain('v-if="drawerUpstreamLinks.follow"')
        expect(tagWith('inbox-drawer-open-upstream-source')).toContain('v-if="drawerUpstreamLinks.source"')
        expect(template).toContain('@click="goTo(drawerUpstreamLinks.follow)">Open follow</n-button>')
        expect(template).toContain('@click="goTo(drawerUpstreamLinks.source)">Open source</n-button>')
    })

    it('keeps the agent links as they are', () => {
        expect(source).toContain('links: inboxLinksOf(row),')
        expect(source).toContain('const drawerLinks = computed(() => inboxLinksOf(inboxDrawerRow.value))')
    })
})
