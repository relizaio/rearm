// T-508 (TPF-185 design 3.4 and 3.5, threat model rows 2, 3, 8 and 10): the files of the upstream
// sources feature render no value as HTML, build no link from a value, write nothing to the
// console or to browser storage, put nothing into the store and make no request of their own.
// It reads every file under components/upstream/ and every utils/upstream*.ts by directory, so the
// files later parts of the feature add fall under it without a change here.
import { describe, expect, it } from 'vitest'
import { readFileSync, readdirSync, statSync } from 'fs'
import { join } from 'path'
import { fileURLToPath } from 'url'

const SRC = fileURLToPath(new URL('..', import.meta.url))
const isSpec = (f: string) => /\.spec\.ts$/.test(f)

function filesUnder (dir: string): string[] {
    return readdirSync(dir).flatMap(f => {
        const p = join(dir, f)
        if (statSync(p).isDirectory()) return filesUnder(p)
        return (f.endsWith('.vue') || f.endsWith('.ts')) && !isSpec(f) ? [p] : []
    })
}

export function featureFiles (): string[] {
    const components = filesUnder(join(SRC, 'components', 'upstream'))
    const utils = readdirSync(join(SRC, 'utils'))
        .filter(f => /^upstream.*\.ts$/.test(f) && !isSpec(f))
        .map(f => join(SRC, 'utils', f))
    return [...components, ...utils].sort()
}

const FORBIDDEN: Array<[string, RegExp]> = [
    ['v-html', /v-html/],
    ['innerHTML', /innerHTML/],
    ['outerHTML', /outerHTML/],
    ['insertAdjacentHTML', /insertAdjacentHTML/],
    ['console.', /console\./],
    ['localStorage', /localStorage/],
    ['sessionStorage', /sessionStorage/],
    ['document.cookie', /document\.cookie/],
    ['fetch(', /\bfetch\(/],
    ['XMLHttpRequest', /XMLHttpRequest/],
    ['axios', /axios/],
    ['window.open', /window\.open/],
    ['location.href', /location\.href/],
    ['store.commit', /store\.commit/],
    ['an href attribute or binding', /\bhref\b/],
    ['a src binding', /(:|v-bind:)src\b|\bsrc\s*:/],
]

describe('the files of the upstream sources feature', () => {
    const files = featureFiles()

    it('areFound', () => {
        expect(files.length).toBeGreaterThan(0)
        expect(files.some(f => f.endsWith('UpstreamSourcesPanel.vue'))).toBe(true)
        expect(files.some(f => f.endsWith('upstreamSourceQueries.ts'))).toBe(true)
    })

    it.each(FORBIDDEN)('contain no %s', (_what, pattern) => {
        const hits = files.filter(f => pattern.test(readFileSync(f, 'utf8')))
        expect(hits.map(f => f.slice(SRC.length))).toEqual([])
    })

    it('findsAForbiddenPatternWhereItIs', () => {
        const sample = '<div v-html="x" /><a :href="y" /><img :src="z" />'
        expect(FORBIDDEN.filter(([, p]) => p.test(sample)).map(([w]) => w)).toEqual(['v-html', 'an href attribute or binding', 'a src binding'])
    })
})

// T-526 (TPF-229, design 3.8, threat model row 6): no screen but the confirmation page follows
// anything. The confirm document is imported by that page only, and its field is named in the
// query module only.
describe('the single sender of the confirm', () => {
    const files = filesUnder(SRC)
    const rel = (f: string) => f.slice(SRC.length).split('\\').join('/')

    it('readsTheWholeSourceTree', () => {
        expect(files.length).toBeGreaterThan(100)
        expect(files.some(f => f.endsWith('router.ts'))).toBe(true)
    })

    it('hasTheConfirmDocumentInTheQueryModuleAndTheConfirmationPageOnly', () => {
        const hits = files.filter(f => /\bCONFIRM_UPSTREAM_FOLLOW\b/.test(readFileSync(f, 'utf8'))).map(rel).sort()
        expect(hits).toEqual(['components/upstream/UpstreamFollowPreparationPage.vue', 'utils/upstreamFollowQueries.ts'])
    })

    it('namesTheConfirmFieldInTheQueryModuleOnly', () => {
        const hits = files.filter(f => /\bconfirmUpstreamFollow\b/.test(readFileSync(f, 'utf8'))).map(rel)
        expect(hits).toEqual(['utils/upstreamFollowQueries.ts'])
    })
})
