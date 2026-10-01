// Every naive-ui element a component's template uses must be imported by that component.
//
// naive-ui is not registered globally, so an <n-tooltip> nobody imported is an unknown element:
// Vue keeps its default slot and drops the named ones, which is how the task drawer's header lost
// its role tag and printed the tooltip text instead (task 562ac668, T-1). Lint, the type check and
// the build all accept it.
import { describe, expect, it } from 'vitest'
import { readFileSync, readdirSync, statSync } from 'fs'
import { join } from 'path'
import { fileURLToPath } from 'url'

const COMPONENTS = fileURLToPath(new URL('.', import.meta.url))

function vueFiles (dir: string): string[] {
    return readdirSync(dir).flatMap(f => {
        const p = join(dir, f)
        if (statSync(p).isDirectory()) return vueFiles(p)
        return f.endsWith('.vue') ? [p] : []
    })
}

function pascal (tag: string): string {
    return 'N' + tag.split('-').map(s => s.charAt(0).toUpperCase() + s.slice(1)).join('')
}

export function missingNaiveImports (source: string): string[] {
    const template = source.slice(source.indexOf('<template'), source.lastIndexOf('</template>'))
    const used = new Set([...template.matchAll(/<n-([a-z][a-z0-9-]*)/g)].map(m => pascal(m[1])))
    const imported = new Set([...source.matchAll(/import\s*\{([^}]*)\}\s*from\s*['"]naive-ui['"]/g)]
        .flatMap(m => m[1].split(',').map(s => s.trim().split(/\s+as\s+/).pop() as string).filter(Boolean)))
    return [...used].filter(n => !imported.has(n)).sort()
}

describe('naive-ui elements are imported where they are used', () => {
    it('finds a missing import', () => {
        expect(missingNaiveImports(`<template><n-tooltip><template #trigger><n-tag/></template>x</n-tooltip></template>
<script setup lang="ts">import { NTag } from 'naive-ui'</script>`)).toEqual(['NTooltip'])
    })

    it('counts an aliased import under its local name', () => {
        expect(missingNaiveImports(`<template><n-gi/></template>
<script setup lang="ts">import { NGridItem as NGi } from 'naive-ui'</script>`)).toEqual([])
    })

    for (const file of vueFiles(COMPONENTS)) {
        it(file.slice(COMPONENTS.length), () => {
            expect(missingNaiveImports(readFileSync(file, 'utf8'))).toEqual([])
        })
    }
})
