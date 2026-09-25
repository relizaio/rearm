// The findings row layout (gaps §1.26): a long location used to squeeze the title to one word per
// line. A DOM without layout cannot measure that, so this pins the styles that prevent it.
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { compile } from 'sass'
import postcss from 'postcss'

const here = (f: string) => fileURLToPath(new URL(f, import.meta.url))

function declarations (selector: string): Record<string, string> {
    const css = compile(here('./taskSections.scss')).css
    const out: Record<string, string> = {}
    postcss.parse(css).walkRules(rule => {
        if (rule.selectors.includes(selector)) rule.walkDecls(d => { out[d.prop] = d.value })
    })
    return out
}

describe('taskSections.scss', () => {
    it('lets the finding title shrink below its longest word and take the room', () => {
        const d = declarations('.frow__title')
        expect(d['min-width']).toBe('0')
        expect(d.flex).toBe('1 1 auto')
    })

    it('gives the location up first: one line, cut with an ellipsis', () => {
        const d = declarations('.frow__loc')
        expect(d['white-space']).toBe('nowrap')
        expect(d['text-overflow']).toBe('ellipsis')
        expect(d.overflow).toBe('hidden')
        expect(d['min-width']).toBe('0')
        expect(d.flex).toBe('0 1 auto')
        expect(d['max-width']).toBe('38%')
    })

    it('every component that renders finding rows uses the shared styles', () => {
        for (const f of ['TaskFindings.vue', 'TaskOpenQuestions.vue', 'TaskActions.vue']) {
            const src = readFileSync(here('./' + f), 'utf8')
            expect(src, f).toContain('class="frow"')
            expect(src, f).toMatch(/<style scoped lang="scss">\s*@use '\.\/taskSections';/)
        }
    })

    it('the location tooltip is the whole location, path and ref (9a118a2a T-2)', () => {
        const src = readFileSync(here('./TaskFindings.vue'), 'utf8')
        expect(src).toContain('class="frow__loc" :title="findingLocationFull(f)"')
    })
})
