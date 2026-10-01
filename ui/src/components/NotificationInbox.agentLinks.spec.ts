import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import { join } from 'path'
import { parse as parseSfc } from '@vue/compiler-sfc'

// RD2-15 (sweep UI-30): an agent-board row and its detail link the task and the board. The drawer is not
// mounted here (it loads the inbox); the wiring is read from what the build ships, and the links themselves
// from inboxLinksOf, whose spec covers them.
const source = readFileSync(join(process.cwd(), 'src/components/NotificationInbox.vue'), 'utf8')
const template = parseSfc(source).descriptor.template?.content ?? ''

describe('the inbox links a board row to its task and board', () => {
    it('in the row, beside its channel, without opening the detail', () => {
        expect(source).toContain('links: inboxLinksOf(row),')
        expect(template).toContain('data-testid="inbox-open-task"')
        expect(template).toContain('@click.prevent.stop="goTo(c.links.task)">Open task</a>')
        expect(template).toContain('@click.prevent.stop="goTo(c.links.board)">Open board</a>')
    })

    it('in the detail, and leaving closes the drawers', () => {
        expect(source).toContain('const drawerLinks = computed(() => inboxLinksOf(inboxDrawerRow.value))')
        expect(template).toContain('@click="goTo(drawerLinks.task)">Open task</n-button>')
        expect(template).toContain('@click="goTo(drawerLinks.board)">Open board</n-button>')
        const goTo = source.slice(source.indexOf('function goTo'), source.indexOf('function goTo') + 300)
        expect(goTo).toContain('inboxDrawerOpen.value = false')
        expect(goTo).toContain('inboxListDrawerOpen.value = false')
        expect(goTo).toContain('router.push(path)')
    })
})
