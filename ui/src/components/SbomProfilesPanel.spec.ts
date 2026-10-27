// @vitest-environment happy-dom
//
// The organization SBOM Profiles sub-tab (task TEA-30, design 4.3 row 16): the catalog rows with the
// built-in default first and undeletable, the profiles of perspectives and components named (an
// archived one read by uuid and tagged), create and edit in the hosted editor, and
// afterCatalogChange(): after every successful write the reads run again, and never after a
// cancelled or refused delete. A failed read shows a notification and Retry. The regulatory package
// settings are their own Regulatory sub-tab, never hosted here. On CE only `default` is listed and
// no new name can be created.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { NNotificationProvider } from 'naive-ui'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const query = vi.fn()
const mutate = vi.fn()
const fire = vi.fn()
const log: string[] = []
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
vi.mock('sweetalert2', () => ({ default: { fire: (...a: any[]) => fire(...a) } }))
vi.mock('./SbomProfileEditor.vue', () => ({ default: { name: 'SbomProfileEditor', props: ['name', 'scope', 'orgUuid'],
    emits: ['saved', 'removed', 'cancel'], render: () => null } }))

const { default: Panel } = await import('./SbomProfilesPanel.vue')

const ORG = '44444444-4444-4444-8444-444444444444'
const LISTED = '11111111-1111-4111-8111-111111111111'
const ARCHIVED = '22222222-2222-4222-8222-222222222222'
const operation = (opts: any) => (opts.query ?? opts.mutation)?.definitions?.[0]?.name?.value
const reads = (name: string) => query.mock.calls.filter(c => operation(c[0]) === name).length

const CATALOG = [
    { name: 'top', resolution: { status: 'RESOLVED', source: 'ORGANIZATION', sourceObject: null,
        profile: { name: 'top', revision: 1, dependencyDepth: 'TOP_LEVEL_ONLY', description: 'top-level only' }, conflictingPerspectives: [] } },
    { name: 'default', resolution: { status: 'RESOLVED', source: 'DEFAULT', sourceObject: null,
        profile: { name: 'default', revision: 0, dependencyDepth: 'FULL' }, conflictingPerspectives: [] } },
]
const STORED = [{ name: 'top', scope: 'ORGANIZATION', revision: 1 }]
const OF_ORG = [
    { uuid: 'r1', scope: 'ORGANIZATION', object: null, name: 'top', mode: null, revision: 1 },
    { uuid: 'r2', scope: 'COMPONENT', object: LISTED, name: 'top', mode: 'OVERRIDE', revision: 0 },
    { uuid: 'r3', scope: 'COMPONENT', object: ARCHIVED, name: 'legacy', mode: 'OVERRIDE', revision: 4 },
]

let catalogFails = false
function answers () {
    query.mockImplementation(async (opts: any) => {
        const op = operation(opts)
        log.push(op)
        switch (op) {
        case 'SbomProfileCatalog':
            if (catalogFails) throw new Error('catalog read refused')
            return { data: { sbomProfileCatalog: CATALOG } }
        case 'SbomProfiles': return { data: { sbomProfiles: STORED } }
        case 'SbomProfilesOfOrg': return { data: { sbomProfilesOfOrg: OF_ORG } }
        case 'TeaComponentName': return { data: { component: { uuid: ARCHIVED, name: 'old-widget', type: 'COMPONENT', status: 'ARCHIVED' } } }
        default: throw new Error('unexpected query ' + op)
        }
    })
}

async function mountPanel (installationType = 'SAAS') {
    const w = mount(defineComponent({
        render: () => h(NNotificationProvider, () => h(Panel, { orgUuid: ORG, isOrgAdmin: true, installationType,
            components: [{ uuid: LISTED, name: 'widget', status: 'ACTIVE' }], perspectives: [] })),
    }), { attachTo: document.body })
    await flushPromises()
    return w
}

const editor = (w: any) => w.findComponent({ name: 'SbomProfileEditor' })
/** The log entries after the last mark: the reads of one afterCatalogChange() and the reload. */
const since = (mark: number) => log.slice(mark)

describe('SbomProfilesPanel', () => {
    beforeEach(() => {
        query.mockReset()
        mutate.mockReset()
        fire.mockReset()
        log.length = 0
        catalogFails = false
        answers()
        mutate.mockResolvedValue({ data: { deleteSbomProfile: true } })
    })

    it('lists default first as built-in with no Delete, then the stored profile with one', async () => {
        const w = await mountPanel()
        const rows = w.findAll('[data-testid="sbom-profile-rows"] tbody tr')
        expect(rows).toHaveLength(2)
        expect(rows[0].text()).toContain('default')
        expect(rows[0].text()).toContain('built-in defaults')
        expect(rows[0].find('[data-testid="sbom-delete-row"]').exists()).toBe(false)
        expect(rows[0].find('[data-testid="sbom-edit"]').exists()).toBe(true)
        expect(rows[1].text()).toContain('top')
        expect(rows[1].text()).toContain('stored')
        expect(rows[1].text()).toContain('Top-level dependencies only')
        expect(rows[1].find('[data-testid="sbom-delete-row"]').exists()).toBe(true)
        expect(query.mock.calls.find(c => operation(c[0]) === 'SbomProfileCatalog')![0].variables)
            .toEqual({ org: ORG, scope: 'ORGANIZATION', object: null })
        w.unmount()
    })

    it('names the profiles of components, an archived one read once and tagged', async () => {
        const w = await mountPanel()
        const other = w.find('[data-testid="sbom-other-rows"]')
        expect(other.text()).toContain('widget')
        expect(other.text()).toContain('old-widget')
        expect(other.text()).toContain('legacy')
        expect(other.find('[data-testid="sbom-archived-tag"]').exists()).toBe(true)
        expect(other.findAll('tbody tr')).toHaveLength(2)
        expect(reads('TeaComponentName')).toBe(1)
        w.unmount()
    })

    it('opens the editor in create mode and for a row', async () => {
        const w = await mountPanel()
        await w.find('[data-testid="sbom-create"]').trigger('click')
        await flushPromises()
        expect(editor(w).exists()).toBe(true)
        expect(editor(w).props('name')).toBeNull()
        expect(editor(w).props('scope')).toBe('ORGANIZATION')
        w.unmount()
    })

    it('after saved, removed and a confirmed row delete: the reads again, once each', async () => {
        const w = await mountPanel()
        await w.find('[data-testid="sbom-create"]').trigger('click')
        await flushPromises()

        let mark = log.length
        editor(w).vm.$emit('saved', { name: 'fresh' })
        await flushPromises()
        expect(since(mark)).toEqual(expect.arrayContaining(['SbomProfileCatalog', 'SbomProfiles', 'SbomProfilesOfOrg']))
        expect(since(mark).filter(op => op === 'SbomProfileCatalog')).toHaveLength(1)

        mark = log.length
        editor(w).vm.$emit('removed', 'fresh')
        await flushPromises()
        expect(since(mark)).toEqual(expect.arrayContaining(['SbomProfileCatalog', 'SbomProfiles']))
        expect(since(mark).filter(op => op === 'SbomProfileCatalog')).toHaveLength(1)

        mark = log.length
        fire.mockResolvedValueOnce({ isConfirmed: true })
        await w.find('[data-testid="sbom-delete-row"]').trigger('click')
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mutate.mock.calls[0][0].variables).toEqual({ org: ORG, scope: 'ORGANIZATION', object: null, name: 'top' })
        expect(since(mark)).toEqual(expect.arrayContaining(['SbomProfileCatalog', 'SbomProfiles']))
        expect(since(mark).filter(op => op === 'SbomProfileCatalog')).toHaveLength(1)
        w.unmount()
    })

    it('reloads nothing after a cancelled or a refused row delete', async () => {
        const w = await mountPanel()
        const catalogReads = reads('SbomProfileCatalog')
        fire.mockResolvedValueOnce({ isConfirmed: false })
        await w.find('[data-testid="sbom-delete-row"]').trigger('click')
        await flushPromises()
        expect(mutate).not.toHaveBeenCalled()

        fire.mockResolvedValueOnce({ isConfirmed: true })
        mutate.mockRejectedValueOnce(new Error('SBOM profile top of the organization cannot be deleted: the TEA publishing '
            + 'settings of the organization name it and it would no longer resolve there; point them at another profile first'))
        await w.find('[data-testid="sbom-delete-row"]').trigger('click')
        await flushPromises()
        expect(fire.mock.calls[2][0].title).toBe('Error!')
        expect(fire.mock.calls[2][0].text).toContain('the TEA publishing settings of the organization name it')
        expect(reads('SbomProfileCatalog')).toBe(catalogReads)
        w.unmount()
    })

    it('shows a failed catalog read as a notification with no rows and Retry', async () => {
        catalogFails = true
        const w = await mountPanel()
        expect(document.body.textContent).toContain('catalog read refused')
        expect(w.findAll('[data-testid="sbom-profile-rows"] tbody tr').filter((r: any) => r.text().includes('top'))).toHaveLength(0)
        expect(w.find('[data-testid="sbom-retry"]').exists()).toBe(true)
        expect(w.find('[data-testid="sbom-create"]').exists()).toBe(false)
        catalogFails = false
        await w.find('[data-testid="sbom-retry"]').trigger('click')
        await flushPromises()
        expect(w.find('[data-testid="sbom-create"]').exists()).toBe(true)
        w.unmount()
    })

    it('hosts no package settings editor on any edition: the Regulatory sub-tab holds it', async () => {
        for (const t of ['SAAS', 'OSS']) {
            const w = await mountPanel(t)
            expect(w.findComponent({ name: 'PackageSettingsEditor' }).exists(), t).toBe(false)
            w.unmount()
        }
        expect(readFileSync(resolve(__dirname, 'SbomProfilesPanel.vue'), 'utf8')).not.toContain('PackageSettingsEditor')
    })

    it('CE: lists the default profile only, in both lists, and offers no Create', async () => {
        const w = await mountPanel('OSS')
        const rows = w.findAll('[data-testid="sbom-profile-rows"] tbody tr')
        expect(rows).toHaveLength(1)
        expect(rows[0].text()).toContain('default')
        expect(rows[0].find('[data-testid="sbom-edit"]').exists()).toBe(true)
        expect(w.find('[data-testid="sbom-create"]').exists()).toBe(false)
        // The overrides of the other scopes name `top` and `legacy` only: none is listed on CE.
        expect(w.find('[data-testid="sbom-other-rows"]').text()).not.toContain('legacy')
        expect(w.find('[data-testid="sbom-other-rows"]').text()).not.toContain('widget')
        w.unmount()
    })

    it('Pro: lists every profile and offers Create', async () => {
        const w = await mountPanel('SAAS')
        expect(w.findAll('[data-testid="sbom-profile-rows"] tbody tr')).toHaveLength(2)
        expect(w.find('[data-testid="sbom-create"]').exists()).toBe(true)
        w.unmount()
    })
})
