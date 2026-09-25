import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { parse as parseSfc } from '@vue/compiler-sfc'
import { compileString } from 'sass'
import postcss from 'postcss'

// The "Board as a spec" modal must scroll its own content. vitest does not apply SFC styles to a
// mounted component, so this reads the rule the build ships: the component's style block compiled
// the way vite compiles it.
const source = readFileSync(fileURLToPath(new URL('./AiAgentBoardsPanel.vue', import.meta.url)), 'utf8')
const { descriptor } = parseSfc(source)
const css = descriptor.styles.map(s => (s.lang === 'scss' ? compileString(s.content).css : s.content)).join('\n')

function declarations (selector: string): Record<string, string> | null {
    let found: Record<string, string> | null = null
    postcss.parse(css).walkRules(rule => {
        if (rule.selector !== selector) return
        found = {}
        rule.walkDecls(d => { found![d.prop] = d.value })
    })
    return found
}

describe('the Board-as-a-spec modal', () => {
    it('renders the spec in a .specBlock', () => {
        const template = descriptor.template?.content ?? ''
        const modal = template.slice(template.indexOf('title="Board as a spec"'))
        expect(modal.slice(0, modal.indexOf('</n-modal>'))).toContain('<pre class="specBlock">{{ specText }}</pre>')
    })

    // A top-level rule: n-modal teleports the card to <body>, outside .boardsPanel, so a nested
    // selector (".boardsPanel .specBlock") would compile fine and never match.
    it('scrolls the block, not the page, and keeps lines unwrapped', () => {
        const d = declarations('.specBlock')
        expect(d, 'a top-level .specBlock rule').not.toBeNull()
        expect(d!.overflow).toBe('auto')
        expect(d!['max-height']).toMatch(/^\d+(\.\d+)?(vh|px)$/)
        expect(d!['white-space']).toBe('pre')
    })
})
