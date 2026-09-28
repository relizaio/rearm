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

    it('keeps the whole title on hover', () => {
        expect(template).toContain('class="inbox-message-title" data-testid="inbox-message-title" :title="c.title"')
    })
})
