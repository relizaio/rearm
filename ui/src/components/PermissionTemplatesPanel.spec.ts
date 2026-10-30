// @vitest-environment happy-dom
//
// TPF-113 (architecture 5, test row 12): the profile's permission templates panel. The GraphQL
// client, the store and naive-ui's dialog are mocked; ScopedPermissions is stubbed and the editor
// modal renders in place.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const query = vi.fn(async (_: any): Promise<any> => ({ data: {} }))
const mutate = vi.fn(async (_: any): Promise<any> => ({ data: {} }))
vi.mock('@/utils/graphql', () => ({ default: { query: (q: any) => query(q), mutate: (m: any) => mutate(m) } }))
vi.mock('vuex', () => ({
    useStore: () => ({
        dispatch: vi.fn(async () => []),
        getters: {
            myuser: { installationType: 'SAAS' },
            orgById: () => ({ approvalRoles: [] }),
            componentsOfOrg: () => [{ uuid: 'c1', name: 'api' }],
            productsOfOrg: () => [],
            instancesOfOrg: () => [],
        },
    }),
}))
// the TPF-92 functional note's dialog mock: the warning confirms at once
const dialogWarning = vi.fn((opts: any) => opts.onPositiveClick())
vi.mock('naive-ui', async (importOriginal) => {
    const actual: any = await importOriginal()
    return { ...actual, useDialog: () => ({ warning: dialogWarning }) }
})

const { default: Panel } = await import('./PermissionTemplatesPanel.vue')
const { default: ScopedPermissions } = await import('./ScopedPermissions.vue')
const { NInput } = await import('naive-ui')

const ORG = 'o1'
const T1 = {
    uuid: 't1', name: 'deploy', description: 'deploy keys', org: ORG, user: 'u1',
    permissions: { permissions: [
        { org: ORG, scope: 'ORGANIZATION', object: ORG, type: 'ADMIN', functions: [], approvals: [] },
        { org: ORG, scope: 'COMPONENT', object: 'c1', type: 'READ_WRITE', functions: ['RESOURCE'], approvals: [] },
        { org: ORG, scope: 'PERSPECTIVE', object: 'p1', type: 'READ_ONLY', functions: [], approvals: [] },
    ] },
}
const T2 = { uuid: 't2', name: 'audit', description: '', org: ORG, user: 'u1', permissions: { permissions: [] } }

function validationError (): any {
    const err: any = new Error("Validation error of type FieldUndefined: Field 'myPermissionTemplates' is undefined")
    err.errors = [{ message: err.message, extensions: { classification: 'ValidationError' } }]
    return err
}
const templateQueries = () => query.mock.calls.filter((c: any) => c[0].query.loc?.source.body.includes('myPermissionTemplates(')).length

const global = { stubs: { ScopedPermissions: true, Modal: { props: ['show'], template: '<div v-if="show"><slot/></div>' } } }
async function open () {
    const notify = vi.fn()
    const w = mount(Panel, { props: { orgUuid: ORG, notify }, global })
    await flushPromises()
    return { w, notify }
}
const rows = (w: any) => w.findAll('[data-testid="permission-templates-table"] tbody tr')
async function editFirst (w: any) {
    await w.find('[data-testid="edit-permission-template"]').trigger('click')
    await flushPromises()
}
const descriptionInput = (w: any) => w.findAllComponents(NInput).find((c: any) => c.attributes('data-testid') === 'permission-template-description')!
const codePoints = (s: string) => Array.from(s).length
const SMILE = '\u{1F600}'

describe('PermissionTemplatesPanel (row 12)', () => {
    beforeEach(() => {
        query.mockReset(); mutate.mockReset(); dialogWarning.mockClear()
        query.mockImplementation(async (q: any) => q.query.loc?.source.body.includes('perspectives(')
            ? { data: { perspectives: [{ uuid: 'p1', name: 'prod' }] } }
            : { data: { myPermissionTemplates: [T1, T2] } })
        mutate.mockImplementation(async () => ({ data: {} }))
    })

    it('shows the two templates with their summaries', async () => {
        const { w } = await open()
        const r = rows(w)
        expect(r).toHaveLength(2)
        expect(r[0].text()).toContain('audit')
        expect(r[0].text()).toContain('none')
        expect(r[0].text()).toContain('0 grants')
        expect(r[1].text()).toContain('deploy')
        expect(r[1].text()).toContain('ADMIN')
        expect(r[1].text()).toContain('2 grants')
    })

    it('delete confirms and sends deletePermissionTemplate', async () => {
        const { w, notify } = await open()
        await w.findAll('[data-testid="delete-permission-template"]')[1].trigger('click')
        await flushPromises()
        expect(dialogWarning).toHaveBeenCalledTimes(1)
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mutate.mock.calls[0][0].variables).toEqual({ uuid: 't1' })
        expect(mutate.mock.calls[0][0].mutation.loc.source.body).toContain('deletePermissionTemplate(uuid: $uuid)')
        expect(notify).toHaveBeenCalledWith('success', 'Deleted', 'Deleted template "deploy"')
    })

    it('the editor sends updatePermissionTemplate with the full permissions list, and its editor offers SBOM probing', async () => {
        const { w, notify } = await open()
        await w.findAll('[data-testid="edit-permission-template"]')[1].trigger('click')
        await flushPromises()
        expect(w.find('[data-testid="permission-template-editor"]').exists()).toBe(true)
        const editor = w.findComponent(ScopedPermissions)
        expect(editor.props('showSbomProbing')).toBe(true)
        expect(editor.props('maxOrgType')).toBeUndefined()
        expect(editor.props('modelValue').scopedPermissions.map((s: any) => s.objectName)).toEqual(['api', 'prod'])
        await w.find('[data-testid="permission-template-name"] input').setValue(' deploy2 ')
        await w.find('[data-testid="permission-template-save"]').trigger('click')
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mutate.mock.calls[0][0].variables).toEqual({ template: {
            uuid: 't1', name: 'deploy2', description: 'deploy keys',
            permissions: [
                { scope: 'ORGANIZATION', objectId: ORG, type: 'ADMIN', functions: [], approvals: [] },
                { scope: 'COMPONENT', objectId: 'c1', type: 'READ_WRITE', functions: ['RESOURCE'], approvals: [] },
                { scope: 'PERSPECTIVE', objectId: 'p1', type: 'READ_ONLY', functions: [], approvals: [] },
            ],
        } })
        expect(notify).toHaveBeenCalledWith('success', 'Saved', 'Saved template "deploy2"')
    })

    it('with the name cleared Save is disabled', async () => {
        const { w } = await open()
        await editFirst(w)
        const save = () => w.find('[data-testid="permission-template-save"]')
        expect(save().attributes('disabled')).toBeDefined()
        await w.find('[data-testid="permission-template-name"] input').setValue('audit2')
        expect(save().attributes('disabled')).toBeUndefined()
        await w.find('[data-testid="permission-template-name"] input').setValue('   ')
        expect(save().attributes('disabled')).toBeDefined()
    })

    it('the description input accepts 300 surrogate-pair characters and refuses the 513th code point', async () => {
        const { w } = await open()
        await editFirst(w)
        const input = () => w.find('[data-testid="permission-template-description"] input')
        expect(descriptionInput(w).props('maxlength')).toBe('512')
        await input().setValue(SMILE.repeat(300))
        expect(codePoints(descriptionInput(w).props('value'))).toBe(300)
        await input().setValue(SMILE.repeat(512))
        expect(codePoints(descriptionInput(w).props('value'))).toBe(512)
        await input().setValue(SMILE.repeat(513))
        expect(codePoints(descriptionInput(w).props('value'))).toBe(512)
    })

    it('reload() is exposed and re-queries', async () => {
        const { w } = await open()
        const before = templateQueries()
        expect(before).toBe(1)
        await (w.vm as any).reload()
        await flushPromises()
        expect(templateQueries()).toBe(2)
    })

    it('renders nothing when unsupported', async () => {
        query.mockImplementation(async () => { throw validationError() })
        const { w, notify } = await open()
        expect(w.find('[data-testid="permission-templates-table"]').exists()).toBe(false)
        expect((w.vm as any).supported).toBe(false)
        expect(notify).not.toHaveBeenCalled()
    })

    it('a Not authorized load calls notify error with that text', async () => {
        query.mockImplementation(async () => { throw new Error('Not authorized') })
        const { notify } = await open()
        expect(notify).toHaveBeenCalledTimes(1)
        expect(notify.mock.calls[0][0]).toBe('error')
        expect(notify.mock.calls[0][2]).toContain('Not authorized')
    })
})
