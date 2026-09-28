import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { parse as parseSfc } from '@vue/compiler-sfc'

// An inbox row's title and its tag (RD2-22, sweep UI-46): a long board headline -- now key first, from the
// server -- ellipsizes before the EVENT or severity tag instead of running under it, and the whole title
// stays readable on hover. The drawer is not mounted here (it loads the inbox); the row's markup and
// styles are read from what the build ships.
const source = readFileSync(fileURLToPath(new URL('./NotificationInbox.vue', import.meta.url)), 'utf8')
const sfc = parseSfc(source).descriptor
const template = sfc.template?.content ?? ''
const styles = sfc.styles.map(s => s.content).join('\n')

describe('an inbox row with a long title', () => {
    it('ellipsizes the title in a shrinking link beside a tag that keeps its width', () => {
        expect(styles).toMatch(/\.inbox-card-head \.inbox-message-link \{[^}]*flex: 1 1 auto;[^}]*min-width: 0;/)
        expect(styles).toMatch(/\.inbox-message-title \{[^}]*text-overflow: ellipsis;[^}]*white-space: nowrap;/)
        expect(styles).toMatch(/\.inbox-card-head > \.n-tag \{\s*flex: 0 0 auto;\s*\}/)
    })

    // Tester run 1 T-1: the title was an inline span, where max-width and the ellipsis do nothing, inside a
    // link that did not clip -- so it ran under the tag anyway. Measured live at 1280: title right 1195 vs tag
    // left 1109; with these two rules it ends at 1101 with the ellipsis.
    it('makes the title a block and has the link clip it, so the ellipsis applies', () => {
        expect(styles).toMatch(/\.inbox-message-title \{[^}]*display: block;/)
        expect(styles).toMatch(/\.inbox-message-link \{[^}]*overflow: hidden;/)
    })

    it('keeps the whole title on hover', () => {
        expect(template).toContain('class="inbox-message-title" data-testid="inbox-message-title" :title="c.title"')
    })
})
