// @vitest-environment happy-dom
//
// The role form's commissions (task RD4-12): the roles offered are the other roles that produce the report, and the
// rest of the block shows once a role is named.
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import RoleCommissionsEditor from './RoleCommissionsEditor.vue'
import { commissionsDraftOf } from '@/utils/agentInvestigation'

const report = [{ specification: 'INVESTIGATION_REPORT', scope: 'TASK' }]
const roles = [
    { name: 'architect', active: true, producesOutputs: [] },
    { name: 'tester', active: true, producesOutputs: report },
    { name: 'lead', active: true, producesOutputs: [] },
]

describe('RoleCommissionsEditor', () => {
    it('offers the other roles that produce the report, and hides intake, budget and review until one is named', async () => {
        const w = mount(RoleCommissionsEditor, { props: { commissions: commissionsDraftOf(null), roles, self: 'architect' } })
        expect((w.vm as any).roleOptions).toEqual([{ label: 'tester', value: 'tester' }])
        expect(w.find('[data-testid="commissions-intake"]').exists()).toBe(false)
        const named = mount(RoleCommissionsEditor, { props: {
            commissions: commissionsDraftOf({ commissions: { roles: ['tester'], intake: 'AUTO' } }), roles, self: 'architect' } })
        expect(named.find('[data-testid="commissions-intake"]').exists()).toBe(true)
        expect((named.vm as any).reviewOptions.map((o: any) => o.value)).toEqual(['architect', 'tester', 'lead'])
    })
})
