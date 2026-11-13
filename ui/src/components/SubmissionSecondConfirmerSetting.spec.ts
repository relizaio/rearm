// @vitest-environment happy-dom
//
// The organization's second-confirmer switch (task FDAW-32, architecture round 2, 3.9, row 10 (a) to (e)):
// it reads the organization's own value (no-cache, another organization's value never taken), sends a
// one-field patch, shows the answered value, goes back on a refusal with the server's sentence as text,
// is disabled for anyone but an organization administrator, and renders no switch it did not read.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { NNotificationProvider } from 'naive-ui'

const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
vi.mock('@/utils/fetchClient', () => ({ fetchWithAuth: vi.fn(), fetchArrayBufferWithAuth: vi.fn() }))

const { default: Setting } = await import('./SubmissionSecondConfirmerSetting.vue')
const { default: submissionQueries } = await import('@/utils/submissionQueries')

const HOSTILE = '<img src=x onerror="window.__hit=1"><b>b</b>'
const ORGS = (v: any) => ({ data: { organizations: [{ uuid: 'o2', settings: { submissionSecondConfirmer: true } },
    { uuid: 'o1', settings: { submissionSecondConfirmer: v } }] } })
const TEXT_23 = 'Require a second confirmer for regulatory packages'
const TEXT_24 = 'Off by default. When on, one person confirms a built draft and a different person finalizes it. '
    + 'A change applies to drafts already open at their next act.'
const TEXT_25 = 'An organization administrator changes this.'
const TEXT_26 = 'The setting could not be read.'

async function mountSetting (props: Record<string, any> = {}) {
    const w = mount(defineComponent({
        render: () => h(NNotificationProvider, () => h(Setting, { orgUuid: 'o1', isWritable: true, ...props })),
    }), { attachTo: document.body })
    await flushPromises()
    return w
}

const sw = (w: any) => w.find('[data-testid="second-confirmer-switch"]')
const isOn = (w: any) => sw(w).attributes('aria-checked') === 'true'
const ofText = (w: any, id: string) => w.find('[data-testid="' + id + '"]').text()

describe('SubmissionSecondConfirmerSetting (FDAW-32 row 10)', () => {
    beforeEach(() => {
        query.mockReset()
        mutate.mockReset()
    })

    it('(a) reads its own organization once, no-cache; off for false and null, on for true; label and help', async () => {
        query.mockResolvedValue(ORGS(false))
        let w = await mountSetting()
        expect(query.mock.calls).toEqual([[{ query: submissionQueries.OrgSecondConfirmerGql, fetchPolicy: 'no-cache' }]])
        expect(sw(w).exists()).toBe(true)
        expect(isOn(w)).toBe(false)
        expect(ofText(w, 'second-confirmer-label')).toBe(TEXT_23)
        expect(ofText(w, 'second-confirmer-help')).toBe(TEXT_24)
        w.unmount()

        query.mockResolvedValue(ORGS(null))
        w = await mountSetting()
        expect(sw(w).exists()).toBe(true)
        expect(isOn(w)).toBe(false)
        w.unmount()

        query.mockResolvedValue(ORGS(true))
        w = await mountSetting()
        expect(isOn(w)).toBe(true)
        w.unmount()
    })

    it('(b) a click sends the one-field patch with no-cache and shows the answered value', async () => {
        query.mockResolvedValue(ORGS(false))
        mutate.mockResolvedValue({ data: { updateOrganizationSettings: { uuid: 'o1', settings: { submissionSecondConfirmer: true } } } })
        let w = await mountSetting()
        await sw(w).trigger('click')
        await flushPromises()
        expect(mutate.mock.calls).toEqual([[{ mutation: submissionQueries.UpdateSecondConfirmerGql,
            variables: { orgUuid: 'o1', on: true }, fetchPolicy: 'no-cache' }]])
        expect(isOn(w)).toBe(true)
        w.unmount()

        mutate.mockReset()
        query.mockResolvedValue(ORGS(true))
        mutate.mockResolvedValue({ data: { updateOrganizationSettings: { uuid: 'o1', settings: { submissionSecondConfirmer: false } } } })
        w = await mountSetting()
        await sw(w).trigger('click')
        await flushPromises()
        expect(mutate.mock.calls[0][0].variables).toEqual({ orgUuid: 'o1', on: false })
        expect(isOn(w)).toBe(false)
        w.unmount()

        // An answer that says false to a request for true leaves the switch off.
        mutate.mockReset()
        query.mockResolvedValue(ORGS(false))
        mutate.mockResolvedValue({ data: { updateOrganizationSettings: { uuid: 'o1', settings: { submissionSecondConfirmer: false } } } })
        w = await mountSetting()
        await sw(w).trigger('click')
        await flushPromises()
        expect(mutate.mock.calls[0][0].variables).toEqual({ orgUuid: 'o1', on: true })
        expect(isOn(w)).toBe(false)
        w.unmount()
    })

    it('(c) a refused change goes back and shows the server sentence as text; nothing read again', async () => {
        query.mockResolvedValue(ORGS(false))
        mutate.mockRejectedValue({ errors: [{ message: 'Not authorized' }] })
        let w = await mountSetting()
        await sw(w).trigger('click')
        await flushPromises()
        expect(isOn(w)).toBe(false)
        expect(ofText(w, 'second-confirmer-error')).toBe('Not authorized')
        expect(query).toHaveBeenCalledTimes(1)
        w.unmount()

        query.mockClear()
        mutate.mockReset()
        mutate.mockRejectedValue({ errors: [{ message: HOSTILE }] })
        w = await mountSetting()
        await sw(w).trigger('click')
        await flushPromises()
        const alert = w.find('[data-testid="second-confirmer-error"]')
        expect(alert.text()).toBe(HOSTILE)
        expect(alert.find('img').exists()).toBe(false)
        expect(alert.find('b').exists()).toBe(false)
        w.unmount()
    })

    it('(d) not an administrator: the switch is disabled, text 25 shows, and a click sends nothing', async () => {
        query.mockResolvedValue(ORGS(false))
        const w = await mountSetting({ isWritable: false })
        expect(sw(w).classes().join(' ')).toContain('n-switch--disabled')
        expect(ofText(w, 'second-confirmer-admin-note')).toBe(TEXT_25)
        await sw(w).trigger('click')
        await flushPromises()
        expect(mutate.mock.calls).toEqual([])
        expect(isOn(w)).toBe(false)
        w.unmount()
    })

    it('(e) a failed read or a missing organization renders no switch; Retry reads again', async () => {
        query.mockRejectedValue(new Error('boom'))
        let w = await mountSetting()
        expect(sw(w).exists()).toBe(false)
        expect(ofText(w, 'second-confirmer-load-error')).toContain(TEXT_26)
        query.mockReset()
        query.mockResolvedValue(ORGS(true))
        await w.find('[data-testid="second-confirmer-retry"]').trigger('click')
        await flushPromises()
        expect(query).toHaveBeenCalledTimes(1)
        expect(sw(w).exists()).toBe(true)
        expect(isOn(w)).toBe(true)
        w.unmount()

        query.mockReset()
        query.mockResolvedValue({ data: { organizations: [{ uuid: 'o2', settings: { submissionSecondConfirmer: true } }] } })
        w = await mountSetting()
        expect(sw(w).exists()).toBe(false)
        expect(ofText(w, 'second-confirmer-load-error')).toContain(TEXT_26)
        w.unmount()
    })
})
