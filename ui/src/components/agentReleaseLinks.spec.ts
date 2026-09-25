import { describe, it, expect } from 'vitest'
import { readdirSync, readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { parse as parseSfc } from '@vue/compiler-sfc'

// The release page is /release/show/:uuid; /release/:uuid is no route and lands on nothing.
// Read from the SFC template: @vue/test-utils is not a dependency of this suite.
const dir = fileURLToPath(new URL('.', import.meta.url))
const template = (file: string) => parseSfc(readFileSync(dir + file, 'utf8')).descriptor.template?.content ?? ''

describe('release links on the agent board components', () => {
    it('the task drawer links the question stack to the release page, in-app', () => {
        const link = template('AiAgentTaskDetailDrawer.vue').split('\n').find(l => l.includes('qstack__link')) ?? ''
        expect(link).toContain('<router-link')
        expect(link).toContain(':to="`/release/show/${f.questionsRelease}`"')
    })

    it('no AiAgent*.vue builds a /release/${...} path without show/', () => {
        const bare = readdirSync(dir)
            .filter(f => /^AiAgent.*\.vue$/.test(f))
            .flatMap(f => readFileSync(dir + f, 'utf8').split('\n').map((l, i) => ({ at: `${f}:${i + 1}`, l })))
            .filter(({ l }) => l.includes('/release/${'))
            .map(({ at }) => at)
        expect(bare).toEqual([])
    })
})
