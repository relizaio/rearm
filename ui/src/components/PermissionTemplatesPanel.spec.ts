// @vitest-environment happy-dom
//
// TPF-113 (architecture 5, test row 12): the profile's permission templates panel. The GraphQL
// client, the store and naive-ui's dialog are mocked; ScopedPermissions is stubbed and the editor
// modal renders in place.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

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

// New template from the profile: the same editor opened empty, saved through createPermissionTemplate with the
// picker's Save as template bounds; the list refreshes after a save, a refusal goes to notify, and nothing shows on CE.
describe('PermissionTemplatesPanel: New template', () => {
    beforeEach(() => {
        query.mockReset(); mutate.mockReset(); dialogWarning.mockClear()
        query.mockImplementation(async (q: any) => q.query.loc?.source.body.includes('perspectives(')
            ? { data: { perspectives: [{ uuid: 'p1', name: 'prod' }] } }
            : { data: { myPermissionTemplates: [T1, T2] } })
        mutate.mockImplementation(async () => ({ data: { createPermissionTemplate: { uuid: 't3' } } }))
    })
    async function openNew (w: any) {
        await w.find('[data-testid="new-permission-template"]').trigger('click')
        await flushPromises()
    }

    it('opens the editor empty, with the same bounds as Save as template', async () => {
        const { w } = await open()
        await openNew(w)
        expect(w.find('[data-testid="permission-template-editor"]').exists()).toBe(true)
        expect((w.find('[data-testid="permission-template-name"] input').element as HTMLInputElement).value).toBe('')
        expect(descriptionInput(w).props('value')).toBe('')
        expect(descriptionInput(w).props('maxlength')).toBe('512')
        expect(descriptionInput(w).props('countGraphemes')).toBeTypeOf('function')
        const name = w.findAllComponents(NInput).find((c: any) => c.attributes('data-testid') === 'permission-template-name')!
        expect(name.props('maxlength')).toBe('128')
        const model = w.findComponent(ScopedPermissions).props('modelValue')
        expect(model).toEqual({ orgPermission: { type: 'NONE', functions: [], approvals: [] }, scopedPermissions: [] })
        expect(w.find('[data-testid="permission-template-save"]').exists()).toBe(false)
        const create = () => w.find('[data-testid="permission-template-create"]')
        expect(create().attributes('disabled')).toBeDefined()
        await w.find('[data-testid="permission-template-name"] input').setValue('   ')
        expect(create().attributes('disabled')).toBeDefined()
        await w.find('[data-testid="permission-template-name"] input').setValue('ci')
        expect(create().attributes('disabled')).toBeUndefined()
    })

    it('saves through createPermissionTemplate with the org and the model, and refreshes the list', async () => {
        const { w, notify } = await open()
        await openNew(w)
        const before = templateQueries()
        await w.find('[data-testid="permission-template-name"] input').setValue(' ci read ')
        await w.find('[data-testid="permission-template-description"] input').setValue('for CI')
        w.findComponent(ScopedPermissions).vm.$emit('update:modelValue', {
            orgPermission: { type: 'READ_ONLY', functions: [], approvals: [] },
            scopedPermissions: [{ scope: 'COMPONENT', objectId: 'c1', objectName: 'api', type: 'READ_WRITE', functions: [], approvals: [] }],
        })
        await flushPromises()
        await w.find('[data-testid="permission-template-create"]').trigger('click')
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mutate.mock.calls[0][0].mutation.loc.source.body).toContain('createPermissionTemplate(')
        expect(mutate.mock.calls[0][0].variables).toEqual({ template: {
            org: ORG, name: 'ci read', description: 'for CI',
            permissions: [
                { scope: 'ORGANIZATION', objectId: ORG, type: 'READ_ONLY', functions: [], approvals: [] },
                { scope: 'COMPONENT', objectId: 'c1', type: 'READ_WRITE', functions: [], approvals: [] },
            ],
        } })
        expect(notify).toHaveBeenCalledWith('success', 'Saved', 'Saved template "ci read"')
        expect(templateQueries()).toBe(before + 1)
        expect(w.find('[data-testid="permission-template-editor"]').exists()).toBe(false)
    })

    it('a refusal goes to notify with the server text and keeps the editor open', async () => {
        mutate.mockImplementation(async () => { throw new Error('Not authorized: too many templates') })
        const { w, notify } = await open()
        await openNew(w)
        await w.find('[data-testid="permission-template-name"] input').setValue('ci')
        await w.find('[data-testid="permission-template-create"]').trigger('click')
        await flushPromises()
        expect(notify.mock.calls.at(-1)?.[0]).toBe('error')
        expect(notify.mock.calls.at(-1)?.[2]).toContain('too many templates')
        expect(w.find('[data-testid="permission-template-editor"]').exists()).toBe(true)
    })

    it('is a plus icon with a New template tooltip and label, in a native button', async () => {
        const { w } = await open()
        const btn = w.get('[data-testid="new-permission-template"]')
        expect(btn.element.tagName).toBe('BUTTON')
        expect(btn.attributes('type')).toBe('button')
        expect(btn.attributes('title')).toBe('New template')
        expect(btn.attributes('aria-label')).toBe('New template')
        expect(btn.attributes('disabled')).toBeUndefined()
        expect(btn.find('.n-icon svg').exists()).toBe(true)
        expect(btn.text()).toBe('')
        const source = readFileSync(join(process.cwd(), 'src/components/PermissionTemplatesPanel.vue'), 'utf8')
        expect(source).toContain('<n-icon class="icons" size="24"><CirclePlus /></n-icon>')
        expect(source).toContain("import { CirclePlus, Edit as EditIcon, Trash } from '@vicons/tabler'")
        const none = mount(Panel, { props: { orgUuid: null, notify: vi.fn() }, global })
        await flushPromises()
        expect(none.find('[data-testid="new-permission-template"]').exists()).toBe(false, 'no organization, no list')
    })

    it('shows no New template control on a server without templates (CE)', async () => {
        query.mockImplementation(async () => { throw validationError() })
        const { w } = await open()
        expect(w.find('[data-testid="new-permission-template"]').exists()).toBe(false)
    })
})
