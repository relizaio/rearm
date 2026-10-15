// @vitest-environment happy-dom
//
// T-25 (SCORE-42, design ADR-2): the organization settings block "Default declared licence":
// the two inputs from a mocked read, Save with only the changed field, the canonical form and
// the cleared URL taken from the mutation response, the server's refusal inline, a schema-drift
// read reported to OrgSettings, any other read failure shown with a Retry, and read-only for a
// non-admin.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const query = vi.fn()
const mutate = vi.fn()
// commonFunctions imports the same module; its message extractor runs for real.
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))

const { default: Panel } = await import('./OrgDeclaredLicensePanel.vue')
const { ORG_CLEARED, ORG_DECLARED_LICENSE_QUERY, ORG_SAVED, UPDATE_ORG_DECLARED_LICENSE } = await import('@/utils/declaredLicense')

const ORG = '11111111-2222-3333-4444-555555555555'
const URL1 = 'https://x.example/licence.txt'
const URL_REFUSAL = 'defaultLicenseUrl needs a defaultLicenseExpression at the same level'

const reply = (expression: string | null, url: string | null) =>
    ({ data: { organization: { uuid: ORG, settings: { defaultLicenseExpression: expression, defaultLicenseUrl: url } } } })
const saved = (expression: string | null, url: string | null) =>
    ({ data: { updateOrganizationSettings: { uuid: ORG, settings: { defaultLicenseExpression: expression, defaultLicenseUrl: url } } } })

// A schema-drift refusal as graphqlDriftFallback.spec.ts builds one (200 with a validation error).
function driftError (): any {
    const err: any = new Error('Validation error (FieldUndefined@[organization/settings/defaultLicenseExpression]) : Field \'defaultLicenseExpression\' in type \'Settings\' is undefined')
    err.errors = [{ message: err.message, extensions: { classification: 'ValidationError' } }]
    return err
}

async function mountPanel (read: any, canManage = true) {
    if (read instanceof Error) query.mockRejectedValueOnce(read)
    else query.mockResolvedValueOnce(read)
    const notify = vi.fn()
    const w = mount(Panel, { props: { orgUuid: ORG, canManage, notify }, attachTo: document.body })
    await flushPromises()
    return { w, notify }
}

const input = (w: any, id: string): HTMLInputElement => w.find(`[data-testid="${id}"] input`).element as HTMLInputElement
async function type (w: any, id: string, value: string) {
    await w.find(`[data-testid="${id}"] input`).setValue(value)
}
const saveButton = (w: any) => w.find('[data-testid="org-default-license-save"]')
const disabled = (w: any): boolean => (saveButton(w).element as HTMLButtonElement).disabled

beforeEach(() => {
    query.mockReset()
    mutate.mockReset()
})

describe('OrgDeclaredLicensePanel', () => {
    it('rendersBothInputsFromTheReadWithSaveDisabled', async () => {
        const { w } = await mountPanel(reply('Apache-2.0', URL1))
        expect(query).toHaveBeenCalledWith({ query: ORG_DECLARED_LICENSE_QUERY, variables: { orgUuid: ORG }, fetchPolicy: 'no-cache' })
        expect(input(w, 'org-default-license').value).toBe('Apache-2.0')
        expect(input(w, 'org-default-license-url').value).toBe(URL1)
        expect(disabled(w)).toBe(true)
        await type(w, 'org-default-license-url', 'https://x.example/other')
        expect(disabled(w)).toBe(false)
        w.unmount()
    })

    it('savesOnlyTheChangedFieldAndShowsTheServedCanonicalForm', async () => {
        const { w, notify } = await mountPanel(reply(null, null))
        await type(w, 'org-default-license', 'apache-2.0')
        mutate.mockResolvedValueOnce(saved('Apache-2.0', null))
        await saveButton(w).trigger('click')
        await flushPromises()
        expect(mutate).toHaveBeenCalledOnce()
        expect(mutate.mock.calls[0][0]).toEqual({
            mutation: UPDATE_ORG_DECLARED_LICENSE,
            variables: { orgUuid: ORG, settings: { defaultLicenseExpression: 'apache-2.0' } },
            fetchPolicy: 'no-cache',
        })
        expect(input(w, 'org-default-license').value).toBe('Apache-2.0')
        expect(disabled(w)).toBe(true)
        expect(notify).toHaveBeenCalledWith('success', 'Saved', ORG_SAVED)
        w.unmount()
    })

    it('sendsOnlyTheUrlBesideAnUnchangedExpression', async () => {
        const { w } = await mountPanel(reply('LicenseRef-Acme', null))
        await type(w, 'org-default-license-url', URL1)
        mutate.mockResolvedValueOnce(saved('LicenseRef-Acme', URL1))
        await saveButton(w).trigger('click')
        await flushPromises()
        expect(mutate.mock.calls[0][0].variables).toEqual({ orgUuid: ORG, settings: { defaultLicenseUrl: URL1 } })
        w.unmount()
    })

    it('clearsWithTheEmptyStringAndShowsBothFieldsEmpty', async () => {
        const { w, notify } = await mountPanel(reply('LicenseRef-Acme', URL1))
        await type(w, 'org-default-license', '')
        mutate.mockResolvedValueOnce(saved(null, null))
        await saveButton(w).trigger('click')
        await flushPromises()
        // The URL is left to the server, which clears it with the expression.
        expect(mutate.mock.calls[0][0].variables).toEqual({ orgUuid: ORG, settings: { defaultLicenseExpression: '' } })
        expect(input(w, 'org-default-license').value).toBe('')
        expect(input(w, 'org-default-license-url').value).toBe('')
        expect(notify).toHaveBeenCalledWith('success', 'Saved', ORG_CLEARED)
        w.unmount()
    })

    it('showsTheServersRefusalInlineAndKeepsTheForm', async () => {
        const { w, notify } = await mountPanel(reply(null, null))
        await type(w, 'org-default-license-url', URL1)
        mutate.mockRejectedValueOnce({ graphQLErrors: [{ message: URL_REFUSAL }] })
        await saveButton(w).trigger('click')
        await flushPromises()
        expect(w.find('[data-testid="org-default-license-error"]').text()).toBe(URL_REFUSAL)
        expect(input(w, 'org-default-license-url').value).toBe(URL1)
        expect(notify).not.toHaveBeenCalled()
        // Cleared on input.
        await type(w, 'org-default-license', 'LicenseRef-Acme')
        expect(w.find('[data-testid="org-default-license-error"]').exists()).toBe(false)
        w.unmount()
    })

    it('reportsADriftRefusalAndRendersNoInput', async () => {
        const { w, notify } = await mountPanel(driftError())
        expect(w.emitted('unsupported')).toHaveLength(1)
        expect(w.find('[data-testid="org-default-license"]').exists()).toBe(false)
        expect(w.find('[data-testid="org-default-license-load-error"]').exists()).toBe(false)
        expect(notify).not.toHaveBeenCalled()
        w.unmount()
    })

    it('showsAnotherReadFailureWithARetry', async () => {
        const { w, notify } = await mountPanel(new Error('Network error: 502 Bad Gateway'))
        expect(w.emitted('unsupported')).toBeUndefined()
        expect(w.find('[data-testid="org-default-license-load-error"]').text()).toContain('502 Bad Gateway')
        expect(notify).toHaveBeenCalledTimes(1)
        expect(w.find('[data-testid="org-default-license"]').exists()).toBe(false)

        query.mockResolvedValueOnce(reply('MIT', null))
        await w.find('[data-testid="org-default-license-retry"]').trigger('click')
        await flushPromises()
        expect(query).toHaveBeenCalledTimes(2)
        expect(input(w, 'org-default-license').value).toBe('MIT')
        expect(w.find('[data-testid="org-default-license-load-error"]').exists()).toBe(false)
        w.unmount()
    })

    it('isReadOnlyWithoutSaveForANonAdmin', async () => {
        const { w } = await mountPanel(reply('MIT', URL1), false)
        expect(input(w, 'org-default-license').readOnly).toBe(true)
        expect(input(w, 'org-default-license-url').readOnly).toBe(true)
        expect(saveButton(w).exists()).toBe(false)
        w.unmount()
    })
})
