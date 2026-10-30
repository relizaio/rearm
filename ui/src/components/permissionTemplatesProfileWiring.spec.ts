import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { parse as parseSfc } from '@vue/compiler-sfc'

// TPF-113 mutation round: the profile's heading and its sentence appear only when the panel has answered that it
// is supported, and the panel itself is mounted outside that block, so it can ask the server and answer at all
// (permissionTemplatesWiring.spec.ts pins where the panel sits and what it is bound to).
const ELEMENT = 1
const DIRECTIVE = 7
const source = readFileSync(fileURLToPath(new URL('./UserProfile.vue', import.meta.url)), 'utf8')
const ast: any = parseSfc(source).descriptor.template?.ast

function find (root: any, pred: (n: any) => boolean): any[] {
    const out: any[] = []
    const walk = (node: any): void => {
        if (node.type === ELEMENT && pred(node)) out.push(node)
        for (const child of node.children || []) walk(child)
    }
    walk(root)
    return out
}
const directive = (n: any, name: string): string | undefined => (n.props || []).find((p: any) => p.type === DIRECTIVE && p.name === name)?.exp?.content
const text = (n: any): string => (n.children || []).map((c: any) => c.type === ELEMENT ? text(c) : (c.content ?? '')).join('')

describe('the profile\'s permission templates block (TPF-113)', () => {
    const gated = find(ast, n => n.tag === 'template' && directive(n, 'if') === 'templatesPanel?.supported')

    it('the heading and the sentence sit in one block shown only when the panel is supported', () => {
        expect(gated).toHaveLength(1)
        const headings = find(gated[0], n => n.tag === 'h4')
        expect(headings.map(text)).toEqual(['Permission templates'])
        const paragraphs = find(gated[0], n => n.tag === 'p')
        expect(paragraphs.map(text)).toEqual([
            'Saved permission sets you can start a device-login approval or a key request from. '
            + 'A template grants nothing by itself: what you approve from it is reduced to your own permissions at that moment.',
        ])
    })

    it('the panel is not inside that block, and the block is the one just before it', () => {
        expect(find(gated[0], n => n.tag === 'PermissionTemplatesPanel')).toHaveLength(0)
        const panel = find(ast, n => n.tag === 'PermissionTemplatesPanel')
        expect(panel).toHaveLength(1)
        expect(gated[0].loc.end.offset).toBeLessThan(panel[0].loc.start.offset)
        expect(source.slice(gated[0].loc.end.offset, panel[0].loc.start.offset).trim()).toBe('')
    })
})
