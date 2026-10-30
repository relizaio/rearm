// @vitest-environment happy-dom
//
// TPF-113 mutation round: what PermissionTemplatePicker.spec.ts leaves open. The GraphQL client is mocked and
// the save-as modal renders in place, as there.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const query = vi.fn(async (_: any): Promise<any> => ({ data: { myPermissionTemplates: [] } }))
const mutate = vi.fn(async (_: any): Promise<any> => ({ data: {} }))
vi.mock('@/utils/graphql', () => ({ default: { query: (q: any) => query(q), mutate: (m: any) => mutate(m) } }))

const { default: Picker } = await import('./PermissionTemplatePicker.vue')
const { NButton, NInput, NModal, NSelect } = await import('naive-ui')
const { extractError } = await import('@/utils/notificationsCommon')

const ORG = 'o1'
const ADMIN_T = {
    uuid: 't-admin', name: 'deploy', description: '', org: ORG, user: 'u1',
    permissions: { permissions: [
        { org: ORG, scope: 'ORGANIZATION', object: ORG, type: 'ADMIN', functions: ['SBOM_PROBING', 'RESOURCE'], approvals: [] },
        { org: ORG, scope: 'COMPONENT', object: 'c1', type: 'READ_WRITE', functions: ['RESOURCE', 'DOWNLOAD'], approvals: [] },
    ] },
}
const READ_T = {
    uuid: 't-read', name: 'Audit', description: 'read only', org: ORG, user: 'u1',
    permissions: { permissions: [{ org: ORG, scope: 'ORGANIZATION', object: ORG, type: 'READ_ONLY', functions: [], approvals: [] }] },
}
const MODEL = {
    orgPermission: { type: 'READ_WRITE', functions: [], approvals: [] },
    scopedPermissions: [{ scope: 'COMPONENT', objectId: 'c1', objectName: 'api', type: 'READ_ONLY', functions: [], approvals: [] }],
}
const serve = (templates: any[]) => query.mockImplementation(async () => ({ data: { myPermissionTemplates: templates } }))
const deferred = () => { let resolve!: (v: any) => void; const promise = new Promise<any>(r => { resolve = r }); return { promise, resolve } }

const global = { stubs: { Modal: { props: ['show'], template: '<div v-if="show"><slot/></div>' } } }
async function open (overrides: Record<string, any> = {}) {
    const notify = vi.fn()
    const nameOf = vi.fn(async (_s: string, id: string) => id === 'c1' ? 'api' : id)
    const w = mount(Picker, { props: { orgUuid: ORG, model: MODEL, nameOf, notify, ...overrides }, global })
    await flushPromises()
    return { w, notify, nameOf }
}
const select = (w: any) => w.findComponent(NSelect)
async function pick (w: any, uuid: string | null) {
    select(w).vm.$emit('update:value', uuid)
    await flushPromises()
}
const saveButton = (w: any) => w.find('[data-testid="save-permission-template"]')
const confirmButton = (w: any) => w.find('[data-testid="save-template-confirm"]')
const nameInput = (w: any) => w.find('[data-testid="save-template-name"] input')
const descriptionInput = (w: any) => w.find('[data-testid="save-template-description"] input')
async function openSaveAs (w: any) {
    await saveButton(w).trigger('click')
    await flushPromises()
}

describe('PermissionTemplatePicker edges', () => {
    beforeEach(() => {
        query.mockReset(); mutate.mockReset()
        serve([ADMIN_T, READ_T])
        mutate.mockImplementation(async () => ({ data: {} }))
    })

    it('renders nothing until the server has answered', async () => {
        const wait = deferred()
        query.mockImplementation(() => wait.promise)
        const { w } = await open()
        expect(w.find('[data-testid="permission-template-picker"]').exists()).toBe(false)
        expect(saveButton(w).exists()).toBe(false)
        wait.resolve({ data: { myPermissionTemplates: [READ_T] } })
        await flushPromises()
        expect(w.find('[data-testid="permission-template-picker"]').exists()).toBe(true)
    })

    it('with disabled true the Save as template button is disabled and a pick selects nothing', async () => {
        const { w } = await open({ disabled: true })
        expect(saveButton(w).attributes('disabled')).toBeDefined()
        await pick(w, 't-read')
        expect(select(w).props('value')).toBeNull()
        expect(w.emitted('apply')).toBeUndefined()
    })

    it('with no organization the list is empty, the button is disabled and a pick does nothing', async () => {
        const { w } = await open()
        expect(select(w).props('options')).toHaveLength(2)
        expect(saveButton(w).attributes('disabled')).toBeUndefined()
        await w.setProps({ orgUuid: null })
        await flushPromises()
        expect(select(w).props('options')).toEqual([])
        expect(select(w).props('placeholder')).toBe('No templates yet')
        expect(saveButton(w).attributes('disabled')).toBeDefined()
        await pick(w, 't-read')
        expect(w.emitted('apply')).toBeUndefined()
    })

    it('another organization clears the pick and loads that organization\'s templates', async () => {
        const { w } = await open()
        await pick(w, 't-read')
        expect(select(w).props('value')).toBe('t-read')
        serve([ADMIN_T])
        await w.setProps({ orgUuid: 'o2' })
        await flushPromises()
        expect(select(w).props('value')).toBeNull()
        expect(query).toHaveBeenCalledTimes(2)
        expect(query.mock.calls[1][0].variables).toEqual({ org: 'o2' })
        expect(select(w).props('options')).toEqual([{ label: 'deploy', value: 't-admin' }])
    })

    it('an answer that arrives after the organization changed is dropped', async () => {
        const first = deferred()
        query.mockImplementationOnce(() => first.promise)
        const { w } = await open()
        serve([READ_T])
        await w.setProps({ orgUuid: 'o2' })
        await flushPromises()
        expect(select(w).props('options')).toEqual([{ label: 'Audit', value: 't-read' }])
        first.resolve({ data: { myPermissionTemplates: [ADMIN_T] } })
        await flushPromises()
        expect(select(w).props('options')).toEqual([{ label: 'Audit', value: 't-read' }])
    })

    it('a pick of a uuid the list does not have changes nothing', async () => {
        const { w } = await open()
        await pick(w, 'nope')
        await pick(w, null)
        expect(select(w).props('value')).toBeNull()
        expect(w.emitted('apply')).toBeUndefined()
    })

    it('a pick applies the clip of every bound it is given, once', async () => {
        const { w } = await open({ maxOrgType: 'READ_WRITE', allowedFunctions: ['RESOURCE', 'DOWNLOAD'], orgFunctions: ['RESOURCE'] })
        await pick(w, 't-admin')
        expect(w.emitted('apply')).toHaveLength(1)
        const [model, reductions] = w.emitted('apply')![0] as any[]
        expect(model.orgPermission).toEqual({ type: 'READ_WRITE', functions: ['RESOURCE'], approvals: [] })
        expect(model.scopedPermissions[0].functions).toEqual(['RESOURCE', 'DOWNLOAD'])
        expect(reductions).toEqual([
            'organization-wide ADMIN reduced to READ_WRITE',
            'organization-wide functions reduced to RESOURCE',
        ])
    })

    it('the function bound of the scoped rows and the one of the organization row are not the same bound', async () => {
        const { w } = await open({ allowedFunctions: ['RESOURCE'], orgFunctions: ['RESOURCE', 'SBOM_PROBING'] })
        await pick(w, 't-admin')
        const [model, reductions] = w.emitted('apply')![0] as any[]
        expect(model.orgPermission.functions).toEqual(['SBOM_PROBING', 'RESOURCE'])
        expect(model.scopedPermissions[0].functions).toEqual(['RESOURCE'])
        expect(reductions).toEqual(['component api functions reduced to RESOURCE'])
    })

    it('a pick is dropped when the organization changes while the names are resolved', async () => {
        const gate = deferred()
        const { w } = await open({ nameOf: vi.fn(() => gate.promise) })
        await pick(w, 't-admin')
        serve([READ_T])
        await w.setProps({ orgUuid: 'o2' })
        gate.resolve('api')
        await flushPromises()
        expect(w.emitted('apply')).toBeUndefined()
    })

    it('a pick is dropped when the host starts loading while the names are resolved', async () => {
        const gate = deferred()
        const { w } = await open({ nameOf: vi.fn(() => gate.promise) })
        await pick(w, 't-admin')
        await w.setProps({ disabled: true })
        gate.resolve('api')
        await flushPromises()
        expect(w.emitted('apply')).toBeUndefined()
    })

    it('the save-as dialog is titled, closes on Cancel and on its own close, and opens empty each time', async () => {
        const { w } = await open()
        await openSaveAs(w)
        expect((w.findComponent(NModal).vm as any).$attrs.title).toBe('Save as template')
        await nameInput(w).setValue('left over')
        await descriptionInput(w).setValue('left over too')
        const cancel = w.findAllComponents(NButton).find((b: any) => b.text() === 'Cancel')!
        await cancel.trigger('click')
        await flushPromises()
        expect(nameInput(w).exists()).toBe(false)
        await openSaveAs(w)
        expect(nameInput(w).element.value).toBe('')
        expect(descriptionInput(w).element.value).toBe('')
        w.findComponent(NModal).vm.$emit('update:show', false)
        await flushPromises()
        expect(nameInput(w).exists()).toBe(false)
    })

    it('Save template is disabled for a name of blanks and while the save is under way, and is enabled again after a refusal', async () => {
        const wait = deferred()
        mutate.mockImplementation(() => wait.promise)
        const { w, notify } = await open()
        await openSaveAs(w)
        await nameInput(w).setValue('   ')
        expect(confirmButton(w).attributes('disabled')).toBeDefined()
        await nameInput(w).setValue('ci')
        expect(confirmButton(w).attributes('disabled')).toBeUndefined()
        await confirmButton(w).trigger('click')
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(confirmButton(w).attributes('disabled')).toBeDefined()
        wait.resolve(Promise.reject(new Error('Not authorized')))
        await flushPromises()
        expect(notify).toHaveBeenCalledTimes(1)
        expect(confirmButton(w).attributes('disabled')).toBeUndefined()
        expect(nameInput(w).exists()).toBe(true)
    })

    it('a saved template closes the dialog; with no uuid in the answer the pick stays where it was', async () => {
        const { w, notify } = await open()
        await pick(w, 't-read')
        await openSaveAs(w)
        await nameInput(w).setValue('ci')
        await confirmButton(w).trigger('click')
        await flushPromises()
        expect(nameInput(w).exists()).toBe(false)
        expect(notify).toHaveBeenCalledWith('success', 'Saved', 'Saved template "ci"')
        expect(select(w).props('value')).toBe('t-read')
        expect(query).toHaveBeenCalledTimes(2)
    })

    it('the guards of the two actions hold when the dialog is reached with the button\'s guard out of the way', async () => {
        const { w } = await open()
        const vm = w.vm as any
        await w.setProps({ disabled: true })
        vm.openSaveAs()
        await flushPromises()
        expect(nameInput(w).exists()).toBe(false)
        await w.setProps({ orgUuid: null, disabled: false })
        vm.openSaveAs()
        await flushPromises()
        expect(nameInput(w).exists()).toBe(false)
        await w.setProps({ orgUuid: ORG, disabled: false })
        await flushPromises()
        vm.openSaveAs()
        await flushPromises()
        expect(nameInput(w).exists()).toBe(true)
        await nameInput(w).setValue('ci')
        await w.setProps({ disabled: true })
        await vm.saveAs()
        expect(mutate).not.toHaveBeenCalled()
        await w.setProps({ disabled: false })
        await nameInput(w).setValue('  ')
        await vm.saveAs()
        expect(mutate).not.toHaveBeenCalled()
        await nameInput(w).setValue('ci')
        await w.setProps({ orgUuid: null })
        await vm.saveAs()
        expect(mutate).not.toHaveBeenCalled()
    })

    it('a failed load and a failed save are shown with the server\'s own text, without the error class', async () => {
        const err = Object.assign(new Error('Not authorized'), { graphQLErrors: [{ message: 'Not authorized' }] })
        query.mockImplementation(async () => { throw err })
        const loadFailed = await open()
        expect(loadFailed.notify).toHaveBeenCalledWith('error', 'Error', extractError(err))
        expect(extractError(err)).toBe('Not authorized')

        query.mockReset(); serve([READ_T])
        const refusal = new Error("A template with the name 'ci' already exists.")
        mutate.mockImplementation(async () => { throw refusal })
        const { w, notify } = await open()
        await openSaveAs(w)
        await nameInput(w).setValue('ci')
        await confirmButton(w).trigger('click')
        await flushPromises()
        expect(notify).toHaveBeenCalledWith('error', 'Error', "A template with the name 'ci' already exists.")
    })

    it('the description input is the one the dialog counts in code points, and the name input is not', async () => {
        const { w } = await open()
        await openSaveAs(w)
        const inputs = w.findAllComponents(NInput)
        const byId = (id: string) => inputs.find((c: any) => c.attributes('data-testid') === id)!
        expect(byId('save-template-description').props('countGraphemes')).toBeTypeOf('function')
        expect(byId('save-template-description').props('countGraphemes')('a\u{1F600}')).toBe(2)
        expect(byId('save-template-name').props('maxlength')).toBe('128')
        expect(byId('save-template-description').props('maxlength')).toBe('512')
    })
})
