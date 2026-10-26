// @vitest-environment happy-dom
//
// TPF-113 (architecture 5, test row 11): the template picker of the device-login page and the key
// request. The GraphQL client is mocked; the save-as modal renders in place.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const query = vi.fn(async (_: any): Promise<any> => ({ data: { myPermissionTemplates: [] } }))
const mutate = vi.fn(async (_: any): Promise<any> => ({ data: {} }))
vi.mock('@/utils/graphql', () => ({ default: { query: (q: any) => query(q), mutate: (m: any) => mutate(m) } }))

const { default: Picker } = await import('./PermissionTemplatePicker.vue')
const { NInput, NSelect } = await import('naive-ui')

const ORG = 'o1'
const ADMIN_T = {
    uuid: 't-admin', name: 'deploy', description: '', org: ORG, user: 'u1',
    permissions: { permissions: [
        { org: ORG, scope: 'ORGANIZATION', object: ORG, type: 'ADMIN', functions: [], approvals: [] },
        { org: ORG, scope: 'COMPONENT', object: 'c1', type: 'READ_WRITE', functions: ['RESOURCE'], approvals: [] },
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

function validationError (): any {
    const err: any = new Error("Validation error of type FieldUndefined: Field 'myPermissionTemplates' is undefined")
    err.errors = [{ message: err.message, extensions: { classification: 'ValidationError' } }]
    return err
}
const serve = (templates: any[]) => query.mockImplementation(async () => ({ data: { myPermissionTemplates: templates } }))

const global = { stubs: { Modal: { props: ['show'], template: '<div v-if="show"><slot/></div>' } } }
async function open (overrides: Record<string, any> = {}) {
    const notify = vi.fn()
    const nameOf = vi.fn(async (_s: string, id: string) => id === 'c1' ? 'api' : id)
    const w = mount(Picker, { props: { orgUuid: ORG, model: MODEL, nameOf, notify, ...overrides }, global })
    await flushPromises()
    return { w, notify }
}
const select = (w: any) => w.findComponent(NSelect)
async function pick (w: any, uuid: string) {
    select(w).vm.$emit('update:value', uuid)
    await flushPromises()
}
async function openSaveAs (w: any) {
    await w.find('[data-testid="save-permission-template"]').trigger('click')
    await flushPromises()
}
const descriptionInput = (w: any) => w.find('[data-testid="save-template-description"] input')
const codePoints = (s: string) => Array.from(s).length
const SMILE = '\u{1F600}'

describe('PermissionTemplatePicker (row 11)', () => {
    beforeEach(() => {
        query.mockReset(); mutate.mockReset()
        serve([ADMIN_T, READ_T])
        mutate.mockImplementation(async () => ({ data: {} }))
    })

    it('offers both served templates, sorted by name', async () => {
        const { w } = await open()
        expect(query.mock.calls[0][0].variables).toEqual({ org: ORG })
        expect(w.find('[data-testid="permission-template-picker"]').exists()).toBe(true)
        expect(select(w).props('options')).toEqual([{ label: 'Audit', value: 't-read' }, { label: 'deploy', value: 't-admin' }])
        expect(select(w).props('placeholder')).toBe('Start from a template')
    })

    it('a pick emits apply with the model built from the template, unclipped without bounds', async () => {
        const { w } = await open()
        await pick(w, 't-admin')
        const [model, reductions] = w.emitted('apply')![0] as any[]
        expect(model).toEqual({
            orgPermission: { type: 'ADMIN', functions: [], approvals: [] },
            scopedPermissions: [{ scope: 'COMPONENT', objectId: 'c1', objectName: 'api', type: 'READ_WRITE', functions: ['RESOURCE'], approvals: [] }],
        })
        expect(reductions).toEqual([])
    })

    it('with maxOrgType READ_WRITE an ADMIN template is applied reduced, with one reduction line', async () => {
        const { w } = await open({ maxOrgType: 'READ_WRITE' })
        await pick(w, 't-admin')
        const [model, reductions] = w.emitted('apply')![0] as any[]
        expect(model.orgPermission.type).toBe('READ_WRITE')
        expect(model.scopedPermissions[0].type).toBe('READ_WRITE')
        expect(reductions).toEqual(['organization-wide ADMIN reduced to READ_WRITE'])
    })

    it('Save as template sends createPermissionTemplate with the org, the trimmed name and the model, then reloads', async () => {
        mutate.mockImplementation(async () => ({ data: { createPermissionTemplate: { uuid: 't-new', name: 'ci' } } }))
        const { w, notify } = await open()
        await openSaveAs(w)
        expect(w.find('[data-testid="save-template-confirm"]').attributes('disabled')).toBeDefined()
        await w.find('[data-testid="save-template-name"] input').setValue('  ci  ')
        await w.find('[data-testid="save-template-description"] input').setValue('for CI')
        serve([ADMIN_T, READ_T, { uuid: 't-new', name: 'ci', org: ORG, permissions: { permissions: [] } }])
        await w.find('[data-testid="save-template-confirm"]').trigger('click')
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mutate.mock.calls[0][0].variables).toEqual({ template: {
            org: ORG, name: 'ci', description: 'for CI',
            permissions: [
                { scope: 'ORGANIZATION', objectId: ORG, type: 'READ_WRITE', functions: [], approvals: [] },
                { scope: 'COMPONENT', objectId: 'c1', type: 'READ_ONLY', functions: [], approvals: [] },
            ],
        } })
        expect(query).toHaveBeenCalledTimes(2)
        expect(select(w).props('options').map((o: any) => o.value)).toContain('t-new')
        expect(select(w).props('value')).toBe('t-new')
        expect(notify).toHaveBeenCalledWith('success', 'Saved', 'Saved template "ci"')
        expect(w.emitted('apply')).toBeUndefined()
    })

    it('renders nothing and shows no toast on a drift-shaped load', async () => {
        query.mockImplementation(async () => { throw validationError() })
        const { w, notify } = await open()
        expect(w.find('[data-testid="permission-template-picker"]').exists()).toBe(false)
        expect(w.find('[data-testid="save-permission-template"]').exists()).toBe(false)
        expect(notify).not.toHaveBeenCalled()
    })

    it('a Not authorized load calls notify error with that text', async () => {
        query.mockImplementation(async () => { throw new Error('Not authorized') })
        const { notify } = await open()
        expect(notify).toHaveBeenCalledTimes(1)
        expect(notify.mock.calls[0][0]).toBe('error')
        expect(notify.mock.calls[0][2]).toContain('Not authorized')
    })

    it('a refused save-as shows the server text and leaves the select unchanged', async () => {
        mutate.mockImplementation(async () => { throw new Error("A template with the name 'ci' already exists.") })
        const { w, notify } = await open()
        await pick(w, 't-read')
        await openSaveAs(w)
        await w.find('[data-testid="save-template-name"] input').setValue('ci')
        await w.find('[data-testid="save-template-confirm"]').trigger('click')
        await flushPromises()
        expect(notify).toHaveBeenCalledTimes(1)
        expect(notify.mock.calls[0][0]).toBe('error')
        expect(notify.mock.calls[0][2]).toContain("A template with the name 'ci' already exists.")
        expect(select(w).props('value')).toBe('t-read')
        expect(select(w).props('options')).toHaveLength(2)
        expect(query).toHaveBeenCalledTimes(1)
    })

    it('the name input carries maxlength 128', async () => {
        const { w } = await open()
        await openSaveAs(w)
        expect(w.find('[data-testid="save-template-name"] input').attributes('maxlength')).toBe('128')
    })

    it('the description input accepts 300 surrogate-pair characters and refuses the 513th code point', async () => {
        const { w } = await open()
        await openSaveAs(w)
        const description = w.findAllComponents(NInput).find((c: any) => c.attributes('data-testid') === 'save-template-description')!
        expect(description.props('maxlength')).toBe('512')
        await descriptionInput(w).setValue(SMILE.repeat(300))
        expect(codePoints(description.props('value'))).toBe(300)
        expect(description.props('value').length).toBe(600)
        await descriptionInput(w).setValue(SMILE.repeat(512))
        expect(codePoints(description.props('value'))).toBe(512)
        await descriptionInput(w).setValue(SMILE.repeat(513))
        expect(codePoints(description.props('value'))).toBe(512)
    })

    it('with disabled true a pick emits nothing', async () => {
        const { w } = await open({ disabled: true })
        await pick(w, 't-admin')
        expect(w.emitted('apply')).toBeUndefined()
        expect(select(w).props('disabled')).toBe(true)
    })

    it('with an empty list the placeholder is No templates yet', async () => {
        serve([])
        const { w } = await open()
        expect(select(w).props('placeholder')).toBe('No templates yet')
    })
})
