import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import { join } from 'path'
import { createMemoryHistory, createRouter } from 'vue-router'
import { BRANCHES_TAB, LATEST_TAB, SETTINGS_DEFAULT_TAB, TAGS_TAB, resolveComponentPageTab, resolveComponentSettingsTab } from './componentPageTab'

// Branch and feature-set links opened the component / product page on the
// Latest tab, which has no branch pane, since Latest became the default tab.
// They must open Branches (components) or Feature Sets (products, same tab
// name 'branches') with that branch selected; plain links keep opening Latest
// and an explicit ?tab= always wins.

const routerSrc = readFileSync(join(process.cwd(), 'src/router.ts'), 'utf8')
const viewSrc = readFileSync(join(process.cwd(), 'src/components/ComponentView.vue'), 'utf8')

/** The path the app's router declares for this route name. */
function pathOf (name: string): string {
    const at = routerSrc.indexOf(`name: '${name}',`)
    expect(at, name).toBeGreaterThan(-1)
    const record = routerSrc.slice(routerSrc.lastIndexOf('{', at), at)
    const m = record.match(/path:\s*'([^']+)'/)
    expect(m, name).not.toBeNull()
    return m![1]
}

// The two page routes as router.ts declares them, so the links below resolve
// exactly as they do in the app.
const router = createRouter({
    history: createMemoryHistory(),
    routes: ['ComponentsOfOrg', 'ProductsOfOrg'].map(name => ({ name, path: pathOf(name), component: {} }))
})

const ORG = '3d2b24d7-0000-4000-8000-000000000001'
const COMP = '3d2b24d7-0000-4000-8000-000000000002'
const BR = '3d2b24d7-0000-4000-8000-000000000003'

/** The tab ComponentView opens on for a link, before the branch list loads. */
function tabOnOpen (to: any, branchType?: string, isComponent?: boolean): { tab: string, branch: string } {
    const r = router.resolve(to)
    const branch = r.params.branchuuid ? String(r.params.branchuuid) : ''
    return { tab: resolveComponentPageTab({ queryTab: r.query.tab, branchUuid: branch, branchType, isComponent }), branch }
}

describe('resolveComponentPageTab', () => {
    it('opens Latest for a plain component or product link', () => {
        expect(resolveComponentPageTab({})).toBe(LATEST_TAB)
        expect(resolveComponentPageTab({ branchUuid: '' })).toBe(LATEST_TAB)
        expect(resolveComponentPageTab({ branchUuid: undefined, queryTab: '' })).toBe(LATEST_TAB)
        expect(resolveComponentPageTab({ isComponent: false })).toBe(LATEST_TAB)
    })

    it('opens Branches for a branch on a component', () => {
        expect(resolveComponentPageTab({ branchUuid: BR })).toBe(BRANCHES_TAB)
        for (const t of ['BASE', 'FEATURE', 'REGULAR', 'RELEASE', 'DEVELOP', 'HOTFIX', undefined, null]) {
            expect(resolveComponentPageTab({ branchUuid: BR, branchType: t, isComponent: true }), String(t)).toBe('branches')
        }
    })

    it('opens Feature Sets (the branches tab) for a feature set on a product', () => {
        expect(resolveComponentPageTab({ branchUuid: BR, isComponent: false })).toBe('branches')
        expect(resolveComponentPageTab({ branchUuid: BR, branchType: 'BASE', isComponent: false })).toBe('branches')
    })

    it('opens Branches for a pull-request branch: the Pull Requests tab is gone, they list there with a PR badge', () => {
        expect(resolveComponentPageTab({ branchUuid: BR, branchType: 'PULL_REQUEST' })).toBe('branches')
    })

    it('opens Tags for a tag on a component, never on a product (no Tags tab)', () => {
        expect(resolveComponentPageTab({ branchUuid: BR, branchType: 'TAG' })).toBe(TAGS_TAB)
        expect(resolveComponentPageTab({ branchUuid: BR, branchType: 'TAG', isComponent: false })).toBe('branches')
    })

    it('lets an explicit tab win, with or without a branch', () => {
        expect(resolveComponentPageTab({ queryTab: 'latest', branchUuid: BR })).toBe('latest')
        expect(resolveComponentPageTab({ queryTab: 'tags', branchUuid: BR, branchType: 'FEATURE' })).toBe('tags')
        expect(resolveComponentPageTab({ queryTab: 'branches' })).toBe('branches')
        expect(resolveComponentPageTab({ queryTab: 'latest', branchUuid: BR, isComponent: false })).toBe('latest')
    })

    it('reads repeated query values and ignores blanks and non-strings', () => {
        expect(resolveComponentPageTab({ queryTab: ['tags', 'latest'], branchUuid: BR })).toBe('tags')
        expect(resolveComponentPageTab({ queryTab: '  ', branchUuid: BR })).toBe('branches')
        expect(resolveComponentPageTab({ queryTab: null, branchUuid: '  ' })).toBe('latest')
        expect(resolveComponentPageTab({ queryTab: 5, branchUuid: 7 })).toBe('latest')
    })
})

describe('links into the component / product page, resolved by the app routes', () => {
    it('a component branch link opens Branches with that branch selected', () => {
        expect(tabOnOpen({ name: 'ComponentsOfOrg', params: { orguuid: ORG, compuuid: COMP, branchuuid: BR } }))
            .toEqual({ tab: 'branches', branch: BR })
        // Raw URL form (charts, VEX inbox, BranchView hrefs).
        expect(tabOnOpen(`/componentsOfOrg/${ORG}/${COMP}/${BR}`)).toEqual({ tab: 'branches', branch: BR })
    })

    it('a product feature-set link opens Feature Sets with that feature set selected', () => {
        expect(tabOnOpen({ name: 'ProductsOfOrg', params: { orguuid: ORG, compuuid: COMP, branchuuid: BR } }, undefined, false))
            .toEqual({ tab: 'branches', branch: BR })
        expect(tabOnOpen(`/productsOfOrg/${ORG}/${COMP}/${BR}`, 'BASE', false)).toEqual({ tab: 'branches', branch: BR })
    })

    it('a component or product link without a branch still opens Latest', () => {
        expect(tabOnOpen({ name: 'ComponentsOfOrg', params: { orguuid: ORG, compuuid: COMP } })).toEqual({ tab: 'latest', branch: '' })
        expect(tabOnOpen({ name: 'ProductsOfOrg', params: { orguuid: ORG, compuuid: COMP } }, undefined, false))
            .toEqual({ tab: 'latest', branch: '' })
        expect(tabOnOpen({ name: 'ComponentsOfOrg', params: { orguuid: ORG, compuuid: COMP }, query: { componentSettingsView: 'true' } }))
            .toEqual({ tab: 'latest', branch: '' })
    })

    it('a branch-settings deep link (branch row double-click) opens Branches, so the branch pane is there', () => {
        expect(tabOnOpen({ name: 'ComponentsOfOrg', params: { orguuid: ORG, compuuid: COMP, branchuuid: BR }, query: { branchSettingsView: 'true' } }))
            .toEqual({ tab: 'branches', branch: BR })
    })

    it('a deep link with an explicit tab wins over the branch', () => {
        expect(tabOnOpen({ name: 'ComponentsOfOrg', params: { orguuid: ORG, compuuid: COMP, branchuuid: BR }, query: { tab: 'latest' } }))
            .toEqual({ tab: 'latest', branch: BR })
        expect(tabOnOpen(`/productsOfOrg/${ORG}/${COMP}/${BR}?tab=latest`, undefined, false)).toEqual({ tab: 'latest', branch: BR })
        expect(tabOnOpen(`/componentsOfOrg/${ORG}/${COMP}?tab=tags`)).toEqual({ tab: 'tags', branch: '' })
    })
})

describe('ComponentView takes its tab from resolveComponentPageTab', () => {
    it('on mount, on a query-only tab change, and once the branch list loads', () => {
        expect(viewSrc).toContain("import { resolveComponentPageTab, resolveComponentSettingsTab, SETTINGS_DEFAULT_TAB } from '@/utils/componentPageTab'")
        expect(viewSrc.match(/resolveComponentPageTab\(/g)?.length).toBe(3)
        expect(viewSrc).toMatch(/const selectedTab: Ref<string> = ref\(resolveComponentPageTab\(/)
    })

    it('no longer hard-codes a default tab or the retired Pull Requests tab', () => {
        expect(viewSrc).not.toMatch(/route\.query\.tab as string\) \|\| '/)
        expect(viewSrc).not.toContain("'pull-requests'")
    })

    it('still names its tab panes latest, branches and tags', () => {
        for (const t of [LATEST_TAB, BRANCHES_TAB, TAGS_TAB]) expect(viewSrc).toContain(`<n-tab-pane ${t === 'tags' ? 'v-if="componentData.type === \'COMPONENT\'" ' : ''}name="${t}"`)
    })
})

// The organization's TEA overrides table links a component to its settings on the TEA tab:
// ?componentSettingsView=true&componentSettingsTab=tea.
describe('resolveComponentSettingsTab', () => {
    const VISIBLE = [SETTINGS_DEFAULT_TAB, 'licenseSupplier', 'tea', 'sbomProfiles', 'regulatory']

    it('opens the pane the query names when the page shows it', () => {
        expect(resolveComponentSettingsTab('tea', VISIBLE)).toBe('tea')
        expect(resolveComponentSettingsTab('regulatory', VISIBLE)).toBe('regulatory')
        expect(resolveComponentSettingsTab(['tea', 'regulatory'], VISIBLE)).toBe('tea')
    })

    it('opens Core Settings for no pane, an unknown one, or one this page hides', () => {
        expect(SETTINGS_DEFAULT_TAB).toBe('Core Settings')
        for (const q of [undefined, null, '', '  ', 5, 'nope', 'locks']) {
            expect(resolveComponentSettingsTab(q, VISIBLE), String(q)).toBe(SETTINGS_DEFAULT_TAB)
        }
    })
})

describe('ComponentView opens its settings modal on ?componentSettingsTab=', () => {
    const modalTabs = viewSrc.slice(viewSrc.indexOf('<n-tabs', viewSrc.indexOf('Settings for {{ componentData?.name }}')))
    const tabsTag = modalTabs.slice(0, modalTabs.indexOf('>'))
    const open = viewSrc.slice(viewSrc.indexOf('const openComponentSettings = async function'),
        viewSrc.indexOf('const hasComponentChanges'))
    const close = viewSrc.slice(viewSrc.indexOf('const handleComponentSettingsClose'), viewSrc.indexOf('// Perspectives management'))

    it('holds the modal pane in componentSettingsTab and keeps the switch handler', () => {
        expect(tabsTag).toContain('v-model:value="componentSettingsTab"')
        expect(tabsTag).toContain('@update:value="handleTabSwitch"')
        expect(viewSrc).toContain('const componentSettingsTab = ref<string>(SETTINGS_DEFAULT_TAB)')
    })

    it('resolves the pane from the query against the visible panes before showing the modal', () => {
        const at = open.indexOf('componentSettingsTab.value = resolveComponentSettingsTab(route.query.componentSettingsTab, visibleSettingsTabs())')
        expect(at).toBeGreaterThan(-1)
        expect(at).toBeLessThan(open.indexOf('showComponentSettingsModal.value = true'))
        expect(open).toContain('if (componentSettingsTab.value !== SETTINGS_DEFAULT_TAB) await handleTabSwitch(componentSettingsTab.value)')
    })

    it('lists every pane the modal renders as visible, the TEA tab under the TEA gate', () => {
        const fn = viewSrc.slice(viewSrc.indexOf('function visibleSettingsTabs'), viewSrc.indexOf('const showCreateOutputTriggerModal'))
        const panes = [...modalTabs.slice(0, modalTabs.indexOf('</n-tabs>')).matchAll(/<n-tab-pane([^>]*)>/g)]
            .map(m => m[1]).filter(a => !a.includes('v-if="false"')).map(a => a.match(/name="([^"]+)"/)![1])
        for (const name of panes) expect(fn, name).toContain(name === 'Core Settings' ? 'SETTINGS_DEFAULT_TAB' : "'" + name + "'")
        expect(fn).toContain("...(teaProfilesAvailable(myUser.installationType) ? ['tea', 'sbomProfiles'] : [])")
    })

    it('drops the parameter with componentSettingsView on close and on a branch switch', () => {
        expect(close).toContain("newQuery.delete('componentSettingsTab')")
        expect(viewSrc).toContain('delete cleanQuery.componentSettingsTab')
    })
})
