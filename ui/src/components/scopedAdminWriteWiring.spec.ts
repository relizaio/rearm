import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'

/**
 * FDAW-66, architecture 3.6 and 4 row 8: the feature set, marketing release, a component's
 * marketing releases and release views count a scoped ADMIN grant as write, through the same
 * `componentPageWritable` the component page uses (utils/componentWrite.spec.ts runs its cells).
 * These views are never mounted in tests, so the wiring is read from the source.
 */
function stripped (file: string): string {
    return readFileSync(fileURLToPath(new URL(file, import.meta.url)), 'utf8')
        .replace(/<!--[\s\S]*?-->/g, '')
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .replace(/(^|[^:])\/\/.*$/gm, '$1')
}

/** A whitespace-tolerant pattern for `isWritable = computed(() => componentPageWritable(<args>))`. */
function isWritableCall (args: string[]): RegExp {
    const esc = (s: string): string => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    return new RegExp('const\\s+isWritable\\s*:\\s*ComputedRef<boolean>\\s*=\\s*computed\\(\\s*\\(\\)\\s*:\\s*boolean\\s*=>\\s*'
        + 'componentPageWritable\\(\\s*' + args.map(esc).join('\\s*,\\s*') + '\\s*\\)\\s*\\)')
}

const IMPORT = /import\s*\{\s*componentPageWritable\s*\}\s*from\s*'(@|\.\.)\/utils\/componentWrite'/
const PERMS = 'myUser?.permissions?.permissions'

const views: { file: string, args: string[] }[] = [
    { file: './BranchView.vue', args: [PERMS, 'orguuid', 'myPerspective.value', 'branchData.value?.component'] },
    { file: './MarketingReleaseView.vue', args: [PERMS, 'marketingRelease.value?.orgDetails?.uuid', 'myPerspective.value',
        'marketingRelease.value?.componentDetails?.uuid'] },
    { file: './MrktReleasesOfComponent.vue', args: [PERMS, 'orguuid.value', 'myPerspective.value', 'component.value'] },
    { file: './ReleaseView.vue', args: [PERMS, 'updatedRelease.value?.orgDetails?.uuid', 'myPerspective.value',
        'updatedRelease.value?.componentDetails?.uuid'] },
]

/** ReleaseView's canUpdateLifecycle, from its declaration to the first following line that is `})`. */
function canUpdateLifecycleSlice (code: string): string {
    const at = code.indexOf('const canUpdateLifecycle')
    expect(at).toBeGreaterThan(-1)
    const end = code.indexOf('\n})', at)
    expect(end).toBeGreaterThan(at)
    return code.slice(at, end + 3)
}

describe('the four views count a scoped ADMIN grant as write (FDAW-66)', () => {
    for (const v of views) {
        it(`${v.file}: isWritable is componentPageWritable with the view's own arguments, no old fallback`, () => {
            const code = stripped(v.file)
            expect(code).toMatch(isWritableCall(v.args))
            expect(code).toMatch(IMPORT)
            expect(code).not.toContain('perspectivePermission')
            expect(code).not.toContain('componentPermission')
            const rw = code.split("'READ_WRITE'").length - 1
            if (v.file === './ReleaseView.vue') {
                expect(rw).toBe(1)
                expect(canUpdateLifecycleSlice(code)).toContain("'READ_WRITE'")
            } else {
                expect(rw).toBe(0)
            }
        })
    }

    it('ReleaseView: canUpdateLifecycle counts an ADMIN row with LIFECYCLE_UPDATE as a READ_WRITE one', () => {
        const slice = canUpdateLifecycleSlice(stripped('./ReleaseView.vue')).replace(/\s+/g, ' ')
        expect(slice).toContain("p.scope === 'ORGANIZATION' && p.org === orguuid && p.type === 'ADMIN'")
        expect(slice).toContain("p.org === orguuid && (p.type === 'READ_WRITE' || p.type === 'ADMIN') && (p.functions || []).includes('LIFECYCLE_UPDATE')")
    })

    it('leaves each view\'s isAdmin the organization ADMIN grant', () => {
        expect(stripped('./BranchView.vue')).toContain('commonFunctions.isAdmin(orguuid, myUser)')
        expect(stripped('./MarketingReleaseView.vue'))
            .toContain('isAdmin.value = commonFunctions.isAdmin(marketingRelease.value.orgDetails.uuid, myUser)')
    })
})
