// T-528 (TPF-185 design 3.3, threat model row 12): the three routes of the upstream pages and the
// menu entry, read from router.ts and LeftNavBar.vue as text. The menu entry sits in nonOssOptions,
// which the menu adds only when the installation is not OSS; the route paths carry the
// organization and a uuid only.
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'

const SRC = fileURLToPath(new URL('../..', import.meta.url))
const router = readFileSync(SRC + 'router.ts', 'utf8')
const nav = readFileSync(SRC + 'components/LeftNavBar.vue', 'utf8')

const ROUTES: Array<[string, string, string]> = [
    ['UpstreamOfOrg', '/upstreamOfOrg/:orguuid', 'components/upstream/UpstreamOfOrg.vue'],
    ['UpstreamFollowPreparation', '/upstreamFollowPreparation/:orguuid/:uuid', 'components/upstream/UpstreamFollowPreparationPage.vue'],
    ['UpstreamFollow', '/upstreamFollow/:orguuid/:uuid', 'components/upstream/UpstreamFollowPage.vue'],
]

/** The text of the route object literal whose name is the given one. */
function routeBlock (name: string): string {
    const at = router.indexOf(`name: '${name}'`)
    expect(at, `route ${name}`).toBeGreaterThan(-1)
    const start = router.lastIndexOf('{', at)
    let depth = 0
    for (let i = start; i < router.length; i++) {
        if (router[i] === '{') depth++
        else if (router[i] === '}' && --depth === 0) return router.slice(start, i + 1)
    }
    throw new Error('unbalanced route block')
}

/** The text of a const array literal of LeftNavBar.vue, bracket to matching bracket. */
function arrayBlock (name: string): string {
    const found = new RegExp(`const ${name}\\s*=\\s*\\[`).exec(nav)
    expect(found, name).not.toBeNull()
    const start = nav.indexOf('[', found!.index)
    let depth = 0
    for (let i = start; i < nav.length; i++) {
        if (nav[i] === '[') depth++
        else if (nav[i] === ']' && --depth === 0) return nav.slice(start, i + 1)
    }
    throw new Error('unbalanced array')
}

describe('the routes of the upstream pages', () => {
    it.each(ROUTES)('%s has the path %s, its page and meta nav upstream', (name, path, file) => {
        const block = routeBlock(name)
        expect(block).toContain(`path: '${path}'`)
        expect(block).toContain(`import('@/${file}')`)
        expect(block).toMatch(/meta:\s*\{\s*nav:\s*'upstream'\s*\}/)
    })

    it('carriesNoRouteParameterButTheOrganizationAndAUuid', () => {
        for (const [name] of ROUTES) {
            const path = /path:\s*'([^']*)'/.exec(routeBlock(name))![1]
            const params = [...path.matchAll(/:(\w+)/g)].map(m => m[1])
            expect(params.filter(p => p !== 'orguuid' && p !== 'uuid'), name).toEqual([])
            expect(path).not.toContain('?')
        }
    })

    it('registersEachRouteOnce', () => {
        for (const [name] of ROUTES) expect(router.split(`name: '${name}'`).length - 1, name).toBe(1)
    })
})

describe('the menu entry Upstream', () => {
    it('isInNonOssOptionsWithItsRouteAndLabel', () => {
        const nonOss = arrayBlock('nonOssOptions')
        expect(nonOss).toContain("key: 'upstream'")
        expect(nonOss).toMatch(/name: 'UpstreamOfOrg',\s*params: \{orguuid: org\}[\s\S]*?default: \(\) => 'Upstream'[\s\S]*?key: 'upstream'/)
    })

    it('isNowhereElse', () => {
        expect(nav.split("key: 'upstream'").length - 1).toBe(1)
        for (const other of ['baseOptions', 'settingsOptions']) expect(arrayBlock(other)).not.toContain("'upstream'")
    })

    it('reachesTheMenuOnlyThroughTheNonOssBranch', () => {
        expect(nav).toMatch(/myuser\.installationType !== 'OSS' \? baseOptions\.concat\(nonOssOptions\) : baseOptions/)
    })
})
