// @vitest-environment happy-dom
//
// FDAW-81, architecture round 3 section 4 row 8 (REQ-76, REQ-77): the Medical device tab's line about the
// device support statement, mounted.
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import DeviceStatementSetupLine from './DeviceStatementSetupLine.vue'

const INCLUDED = 'Device support statement: included in this product\'s FDA premarket packages. Its text comes from'
    + ' the organization\'s three labeling statements; until they are written, a package that includes it is refused.'
const NOT_YET = 'Device support statement: not set for this product yet. Save with Yes includes it in this product\'s'
    + ' FDA premarket packages; that step needs a product administrator.'
const EXCLUDED = 'Device support statement: excluded for this product under Settings > Regulatory. Save does not change'
    + ' that choice.'

const S = (sel: string) => ({ part: 'DEVICE_SUPPORT_STATEMENT', options: { selection: sel } })
const own = (sel: string) => ({ productPackage: { mode: 'OVERRIDE', state: 'ENABLED', sbomProfile: 'fda', parts: [S(sel)] } })

function line (props: Record<string, unknown>) {
    return mount(DeviceStatementSetupLine, { props: { answer: 'ENABLED', componentType: 'PRODUCT', ...props } })
}

describe('the device support statement line (FDAW-81)', () => {
    it('(a) included: the INCLUDED text, no Edit', () => {
        const w = line({ status: own('INCLUDE') })
        expect(w.findAll('[data-testid="device-fda-statement"]')).toHaveLength(1)
        expect(w.find('[data-testid="device-fda-statement"] [data-testid="device-fda-statement-text"]').text()).toBe(INCLUDED)
        expect(w.find('[data-testid="device-fda-statement-edit"]').exists()).toBe(false)
    })

    it('(b) no own row: the NOT_YET text, no Edit', () => {
        const w = line({ status: { productPackage: null } })
        expect(w.find('[data-testid="device-fda-statement-text"]').text()).toBe(NOT_YET)
        expect(w.find('[data-testid="device-fda-statement-edit"]').exists()).toBe(false)
    })

    it('(c) excluded: the EXCLUDED text and Edit, which emits edit once', async () => {
        const w = line({ status: own('EXCLUDE') })
        expect(w.find('[data-testid="device-fda-statement-text"]').text()).toBe(EXCLUDED)
        const edit = w.find('[data-testid="device-fda-statement-edit"]')
        expect(edit.text()).toBe('Edit')
        await edit.trigger('click')
        expect(w.emitted('edit')).toHaveLength(1)
    })

    it('(d) question 2 = No: nothing', () => {
        expect(line({ status: own('INCLUDE'), answer: 'DISABLED' }).find('[data-testid="device-fda-statement"]').exists())
            .toBe(false)
    })

    it('(e) not a product: nothing', () => {
        for (const componentType of ['COMPONENT', null]) {
            expect(line({ status: own('INCLUDE'), componentType }).find('[data-testid="device-fda-statement"]').exists(),
                String(componentType)).toBe(false)
        }
    })

    it('(f) no status: nothing', () => {
        expect(line({ status: null }).find('[data-testid="device-fda-statement"]').exists()).toBe(false)
    })
})
