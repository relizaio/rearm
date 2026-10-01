<template>
    <div class="role-commissions" data-testid="role-commissions">
        <div class="flabel" style="margin-bottom: 4px">may commission a report from</div>
        <n-select v-model:value="commissions.roles" multiple :options="roleOptions" data-testid="commissions-roles"
                  placeholder="Nobody: this role does its own research"/>
        <n-space v-if="commissions.roles.length" :size="8" align="center" wrap style="margin-top: 6px">
            <n-select v-model:value="commissions.intake" :options="INTAKE_OPTIONS" style="width: 300px"
                      data-testid="commissions-intake"/>
            <n-input-number v-model:value="commissions.defaultBudgetDollars" :min="0" :precision="2" clearable
                            placeholder="none" style="width: 190px" data-testid="commissions-budget">
                <template #prefix><span class="flabel">default budget $</span></template>
            </n-input-number>
            <n-select v-model:value="commissions.review" :options="reviewOptions" clearable style="width: 200px"
                      placeholder="no review" data-testid="commissions-review"/>
        </n-space>
        <n-text depth="3" style="font-size: 11.5px; display: block; margin-top: 4px;">
            An agent in this role, holding a task, may ask these roles for an investigation report with
            <code>rearm agent task commission</code>; the report comes back pinned on its task. Only roles that
            produce INVESTIGATION_REPORT can be named. The budget is capped by the board's.
        </n-text>
    </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import { NInputNumber, NSelect, NSpace, NText } from 'naive-ui'
import { CommissionsDraft, commissionableRoleOptions, INTAKE_OPTIONS } from '@/utils/agentInvestigation'

/**
 * A role's commissions (task RD4-12), bound with v-model:commissions to a draft made by commissionsDraftOf();
 * commissionsPatch() turns the draft into what the API takes.
 */
const commissions = defineModel<CommissionsDraft>('commissions', { required: true })
const props = defineProps<{ roles: any[], self?: string | null }>()

const roleOptions = computed(() => commissionableRoleOptions(props.roles, props.self))
const reviewOptions = computed(() => (props.roles ?? []).filter((r: any) => r?.active)
    .map((r: any) => ({ label: r.name, value: r.name })))
</script>
