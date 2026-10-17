import { describe, expect, it } from 'vitest'
import { escapeRegExp, withoutComments, withoutHtmlComments } from './specText'

describe('specText', () => {
    it('removes a comment that a first removal would form', () => {
        expect(withoutHtmlComments('a<!<!-- x -->-- y -->b')).toBe('ab')
        expect(withoutComments('a//* x */* y */b')).toBe('ab')
    })

    it('removes HTML, block and line comments, but keeps a URL', () => {
        const src = '<!-- t --><div/>\n/* b */const u = "https://x.io" // c\nconst v = 1'
        expect(withoutComments(src)).toBe('<div/>\nconst u = "https://x.io" \nconst v = 1')
    })

    it('escapes every metacharacter, so a path matches only itself', () => {
        const re = new RegExp(`^${escapeRegExp('../utils/a.b(c)')}$`)
        expect(re.test('../utils/a.b(c)')).toBe(true)
        expect(re.test('../utils/aXb(c)')).toBe(false)
        expect(re.test('..Xutils/a.b(c)')).toBe(false)
    })
})
