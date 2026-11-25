// T-511 (TPF-185 design 3.2 and 3.6): the Upstream Sources tab of OrgSettings.vue is gated on the
// edition predicate, a deep link to it falls back on CE (proOnlyTabs), a non-admin member may open
// it (not in adminOnlyTabs), and the panel learns whether the caller is an administrator.
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'

const SOURCE = readFileSync(fileURLToPath(new URL('./OrgSettings.vue', import.meta.url)), 'utf8')

function arrayOf (name: string): string[] {
    const m = SOURCE.match(new RegExp(`const ${name} = \\[([^\\]]*)\\]`))
    if (!m) throw new Error(`${name} not found`)
    return [...m[1].matchAll(/'([^']*)'/g)].map(x => x[1])
}

describe('OrgSettings.vue wires the Upstream Sources tab', () => {
    it('gatesThePaneOnUpstreamAvailable', () => {
        const pane = SOURCE.match(/<n-tab-pane[^>]*name="upstreamSources"[^>]*>/)
        expect(pane).not.toBeNull()
        expect(pane![0]).toContain('v-if="upstreamAvailable"')
    })

    it('computesUpstreamAvailableFromThePredicate', () => {
        expect(SOURCE).toMatch(/const upstreamAvailable[^=]*= computed\([^\n]*upstreamSourcesAvailable\(myUser\.value\?\.installationType\)\)/)
        expect(SOURCE).toContain('import { upstreamSourcesAvailable } from \'@/utils/upstream\'')
    })

    it('listsTheTabInProOnlyTabsAndNotInAdminOnlyTabs', () => {
        expect(arrayOf('proOnlyTabs')).toContain('upstreamSources')
        expect(arrayOf('adminOnlyTabs')).not.toContain('upstreamSources')
    })

    it('passesIsOrgAdminToThePanel', () => {
        const panel = SOURCE.match(/<upstream-sources-panel[^>]*\/>/)
        expect(panel).not.toBeNull()
        expect(panel![0]).toContain(':is-org-admin="isOrgAdmin"')
        expect(panel![0]).toContain(':org-uuid="orgResolved"')
    })
})
