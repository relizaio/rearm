<template>
    <div v-if="roleOptions.length" class="dsec" data-testid="task-commission">
        <div class="dsec__h">Investigations</div>
        <div class="deprow">
            <n-button size="small" data-testid="commission-open" @click="open">Commission investigation…</n-button>
        </div>
    </div>
    <n-modal :show="!!draft" preset="card" title="Commission investigation" style="max-width: 560px"
             @update:show="(v: boolean) => { if (!v) draft = null }">
        <n-space v-if="draft" vertical :size="10">
            <n-select v-model:value="draft.role" :options="roleOptions" placeholder="role to investigate"
                      data-testid="commission-role"/>
            <n-input v-model:value="draft.title" placeholder="Title: one line" data-testid="commission-title"/>
            <n-input v-model:value="draft.brief" type="textarea" :autosize="{ minRows: 3, maxRows: 10 }"
                     placeholder="Brief: what to find out, and what the report should answer" data-testid="commission-brief"/>
            <n-space :size="8" align="center" wrap>
                <n-input-number v-model:value="draft.budgetDollars" :min="0" :precision="2" clearable placeholder="none"
                                style="width: 170px" data-testid="commission-budget">
                    <template #prefix><span class="deplab" style="min-width: 0">budget $</span></template>
                </n-input-number>
                <n-date-picker v-model:value="draft.deadline" type="datetime" clearable placeholder="deadline"
                               data-testid="commission-deadline"/>
                <n-select v-model:value="draft.review" :options="reviewOptions" clearable placeholder="no review"
                          style="width: 170px" data-testid="commission-review"/>
            </n-space>
            <n-checkbox v-model:checked="draft.returnToTask" data-testid="commission-return">
                bring the report back to {{ task.key ?? 'this task' }}
            </n-checkbox>
            <div v-if="problem" class="holdmeta" style="color: #d03050" data-testid="commission-problem">{{ problem }}</div>
            <n-space justify="end">
                <n-button size="small" @click="draft = null">Back</n-button>
                <n-button size="small" type="primary" :disabled="!!problem" data-testid="commission-submit" @click="submit">
                    Commission
                </n-button>
            </n-space>
        </n-space>
    </n-modal>
</template>

<script lang="ts" setup>
// A person commissions an investigation from the task page (task RD4-12): any active role that produces
// BOARD_INVESTIGATION_REPORT, with a brief, a budget, a deadline and a review, its report brought back to this task.
import { computed, ref, watch } from 'vue'
import { NButton, NCheckbox, NDatePicker, NInput, NInputNumber, NModal, NSelect, NSpace } from 'naive-ui'
import { CommissionDraft, commissionDraftOf, commissionInput, commissionProblem, investigatingRoleOptions,
    reviewRoleOptions } from '@/utils/agentInvestigation'

const props = defineProps<{ task: any, board?: any, roles?: any[] }>()
const emit = defineEmits<{ (e: 'commission', p: { task: any, input: Record<string, any> }): void }>()

const draft = ref<CommissionDraft | null>(null)
const roleOptions = computed(() => investigatingRoleOptions(props.roles))
const reviewOptions = computed(() => reviewRoleOptions(props.roles, draft.value?.role ?? null))
const problem = computed(() => draft.value ? commissionProblem(draft.value) : '')

function open () {
    draft.value = commissionDraftOf()
}

function submit () {
    if (!draft.value) return
    const input = commissionInput(props.board?.uuid ?? props.task?.board, props.task, draft.value)
    if (!input) return
    emit('commission', { task: props.task, input })
    draft.value = null
}

watch(() => props.task?.uuid, () => { draft.value = null })
</script>

<style scoped lang="scss">
@use './taskSections';
</style>
