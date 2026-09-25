<template>
    <div v-if="!terminal" class="dsec">
        <div class="dsec__h">Task actions</div>
        <div v-if="authorizable" class="deprow">
            <n-select v-model:value="authorizeRole" :options="roleOptions" size="small"
                      placeholder="role" style="width: 170px"/>
            <n-button size="small" :disabled="!authorizeRole"
                      @click="emit('authorize', { task, role: authorizeRole as string, orderIndex: orderDraft })">
                Authorize
            </n-button>
        </div>
        <div class="deprow">
            <n-input-number v-model:value="orderDraft" size="small" :min="0" style="width: 130px">
                <template #prefix><span class="deplab" style="min-width: 0">order</span></template>
            </n-input-number>
            <n-button size="small" :disabled="orderDraft == null || orderDraft === task.orderIndex"
                      @click="emit('order', { task, orderIndex: orderDraft as number })">Set order</n-button>
            <span v-if="task.orderSetBy" class="holdmeta" style="margin-top: 0">
                set by {{ actorLabel(task.orderSetBy) }} · {{ ts(task.orderSetAt) }}
            </span>
        </div>
        <!-- A task's required model strength (D20: lowering it is a person's call). -->
        <div v-if="admin" class="deprow strow">
            <n-input-number v-model:value="strengthDraft" size="small" :min="0" :step="0.5" :precision="2"
                            :placeholder="strengthPlaceholder(task, roles)" style="width: 190px">
                <template #prefix><span class="deplab" style="min-width: 0">strength</span></template>
            </n-input-number>
            <n-button size="small" :disabled="strengthToSet(task, strengthDraft) === undefined"
                      @click="emit('set-strength', { task, requiredStrength: strengthToSet(task, strengthDraft) as number })">
                Set strength
            </n-button>
            <n-button v-if="task.requiredStrength != null" size="small" quaternary
                      @click="emit('set-strength', { task, requiredStrength: null })">Clear</n-button>
            <span v-if="task.strengthSetBy" class="holdmeta" style="margin-top: 0">
                set by {{ actorLabel(task.strengthSetBy) }} · {{ ts(task.strengthSetAt) }}
            </span>
        </div>
        <!-- An operator hold: the coordinator cannot lift it; the hold banner offers the release. -->
        <div v-if="canPlaceHold(task, !!admin)" class="deprow holdrow">
            <n-input v-model:value="holdReason" size="small" placeholder="Why hold it (required)"
                     style="width: 260px"/>
            <n-button size="small" type="warning" ghost :disabled="!holdPayload(task, holdReason)"
                      @click="placeHold">Put on hold (operator)</n-button>
        </div>
        <div class="deprow">
            <n-button size="small" type="primary" ghost :disabled="!completable"
                      @click="showComplete = true">Complete…</n-button>
            <n-input v-model:value="cancelNote" size="small" placeholder="Why cancel (optional)"
                     style="width: 220px"/>
            <n-popconfirm @positive-click="emit('cancel', { task, note: cancelNote })">
                <template #trigger>
                    <n-button size="small" type="error" ghost>Cancel task</n-button>
                </template>
                Cancelling is final. An agent working it finds it gone at its next call.
            </n-popconfirm>
        </div>
    </div>

    <!-- A completed task whose delivery cannot land (a PR that no longer merges) goes back to
         the role that must redo its part; whoever read that part re-runs after it. -->
    <div v-if="reopenOptions.length" class="dsec">
        <div class="dsec__h">Reopen</div>
        <div class="deprow">
            <n-select v-model:value="reopenRole" :options="reopenOptions" size="small"
                      placeholder="role" style="width: 170px"/>
            <n-input v-model:value="reopenReason" size="small" style="width: 300px"
                     placeholder="Why its delivery cannot land (required)"/>
            <n-popconfirm @positive-click="reopen">
                <template #trigger>
                    <n-button size="small" :disabled="!reopenReady">
                        Reopen to {{ reopenRole ?? '…' }}
                    </n-button>
                </template>
                The role's earlier pass stops counting; whoever read its part re-runs when it
                republishes.
            </n-popconfirm>
        </div>
    </div>
    <div v-if="task.reopenCount" class="holdmeta">
        Reopened {{ task.reopenCount }}× · last {{ ts(task.reopenedAt) }}
    </div>

    <!-- A person's complete: findings that block completion are decided one by one, never
         skipped; required roles that have not passed may be skipped, with a note. -->
    <n-modal :show="showComplete" preset="card" title="Complete task" style="max-width: 560px"
             @update:show="(v: boolean) => { showComplete = v }">
        <n-space vertical :size="12">
            <div v-if="blockers.length">
                <div class="dsec__h">Findings that block completion</div>
                <div v-for="b in blockers" :key="b.specification + b.finding.id" class="frow">
                    <code class="frow__id">{{ b.finding.id }}</code>
                    <n-tag size="tiny" :bordered="false" type="error">P{{ b.finding.priority ?? '?' }}</n-tag>
                    <span class="frow__title">{{ b.finding.title }}</span>
                    <div class="fedit">
                        <n-input v-model:value="blockerWords[b.finding.id ?? '']" size="small"
                                 placeholder="Why"/>
                        <n-space :size="6" style="margin-top: 6px">
                            <n-button size="tiny" type="warning"
                                      :disabled="!(blockerWords[b.finding.id ?? ''] ?? '').trim()"
                                      @click="decideOne(b.specification, { action: 'ACCEPT', findingId: b.finding.id, resolution: blockerWords[b.finding.id ?? ''].trim() })">
                                Accept the risk
                            </n-button>
                            <n-button size="tiny"
                                      :disabled="!(blockerWords[b.finding.id ?? ''] ?? '').trim()"
                                      @click="decideOne(b.specification, { action: 'DISMISS', findingId: b.finding.id, resolution: blockerWords[b.finding.id ?? ''].trim() })">
                                Dismiss
                            </n-button>
                        </n-space>
                    </div>
                </div>
            </div>
            <div v-if="missingRequired.length">
                <div class="dsec__h">Required roles without a pass</div>
                <n-tag v-for="m in missingRequired" :key="m" size="small" :bordered="false" type="error"
                       style="margin-right: 6px">{{ m }}</n-tag>
                <div class="holdmeta">
                    A role whose rejection you have decided over counts as passed. Otherwise, skip
                    it and say why.
                </div>
                <n-checkbox v-model:checked="skipRequired" style="margin-top: 6px">
                    complete without them
                </n-checkbox>
            </div>
            <n-input v-model:value="completeNote" type="textarea" :autosize="{ minRows: 2, maxRows: 5 }"
                     :placeholder="skipRequired ? 'Why (required when skipping roles)' : 'Note (optional)'"/>
            <n-space justify="end">
                <n-button size="small" @click="showComplete = false">Back</n-button>
                <n-button size="small" type="primary"
                          :disabled="blockers.length > 0 || (skipRequired && !completeNote.trim())"
                          @click="emit('complete', { task, note: completeNote, skipRequiredRoles: skipRequired })">
                    Complete
                </n-button>
            </n-space>
        </n-space>
    </n-modal>
</template>

<script lang="ts" setup>
// A person's verbs on a task: authorize, order, complete, cancel, reopen, and the decisions a
// completion needs. The drawer's preview and the task page both carry this whole.
import { computed, ref, watch } from 'vue'
import { NButton, NCheckbox, NInput, NInputNumber, NModal, NPopconfirm, NSelect, NSpace, NTag } from 'naive-ui'
import { actorLabel } from '@/utils/agentActors'
import { reopenPayload, reopenRoleOptions } from '@/utils/agentReopen'
import { DocumentRelease, completionBlockers } from '@/utils/agentDocuments'
import { isTerminal, missingRequiredRoles, ts } from '@/utils/agentTaskFormat'
import { roleOptionsOf } from '@/utils/agentTaskOptions'
import { canPlaceHold, holdPayload, strengthPlaceholder, strengthToSet } from '@/utils/agentTaskAdmin'

const props = defineProps<{
    task: any
    roles?: any[]
    board?: any
    /** Org admin: may reopen a completed task (the server's rule for agentTaskReopen). */
    canReopen?: boolean
    /** Org admin: may set the task's strength and place an operator hold (task 6fdc5a37). */
    admin?: boolean
}>()
const emit = defineEmits<{
    (e: 'authorize', p: { task: any, role: string, orderIndex?: number | null }): void
    (e: 'order', p: { task: any, orderIndex: number }): void
    (e: 'complete', p: { task: any, note: string, skipRequiredRoles: boolean }): void
    (e: 'cancel', p: { task: any, note: string }): void
    (e: 'reopen', p: { task: any, role: string, reason: string }): void
    (e: 'decide', p: { task: any, specification: string, decisions: any[],
        about?: { specification: string } | null }): void
    (e: 'set-strength', p: { task: any, requiredStrength: number | null }): void
    (e: 'operator-hold', p: { task: any, reason: string }): void
}>()

const authorizeRole = ref<string | null>(null)
const orderDraft = ref<number | null>(null)
const cancelNote = ref('')
const reopenRole = ref<string | null>(null)
const reopenReason = ref('')
const reopenOptions = computed(() => reopenRoleOptions(props.task, props.roles, !!props.canReopen))
const reopenReady = computed(() => null !== reopenPayload(props.task, reopenRole.value, reopenReason.value))
function reopen () {
    const p = reopenPayload(props.task, reopenRole.value, reopenReason.value)
    if (p) emit('reopen', { task: props.task, role: p.role, reason: p.reason })
}
const strengthDraft = ref<number | null>(null)
const holdReason = ref('')
function placeHold () {
    const p = holdPayload(props.task, holdReason.value)
    if (p) emit('operator-hold', p)
}
const showComplete = ref(false)
const completeNote = ref('')
const skipRequired = ref(false)
const blockerWords = ref<Record<string, string>>({})

const authorizable = computed(() =>
    props.task?.status === 'PENDING_INTAKE' || props.task?.status === 'AWAITING_COORDINATOR')
// DELIVERING: a person completing it says the delivery happened (a PR merged by hand where CI does
// not report), and the server completes it outright.
const completable = computed(() =>
    ['AWAITING_COORDINATOR', 'PENDING_INTAKE', 'QUEUED', 'ON_HOLD', 'DELIVERING'].includes(props.task?.status))
const terminal = computed(() => isTerminal(props.task))
const roleOptions = computed(() => roleOptionsOf(props.roles))
const missingRequired = computed(() => missingRequiredRoles(props.task, props.roles))
const blockers = computed(() => completionBlockers((props.task?.documents ?? []) as DocumentRelease[],
    props.board?.completionPriority ?? null))

function decideOne (spec: string, decision: any) {
    emit('decide', { task: props.task, specification: spec, decisions: [decision] })
}

watch(() => props.task?.uuid, () => { reopenRole.value = null; reopenReason.value = '' })
watch(() => props.task?.uuid, () => {
    authorizeRole.value = props.task?.role ?? null
    orderDraft.value = props.task?.orderIndex ?? null
    strengthDraft.value = props.task?.requiredStrength ?? null
    holdReason.value = ''
    cancelNote.value = ''
    showComplete.value = false
    completeNote.value = ''
    skipRequired.value = false
    blockerWords.value = {}
}, { immediate: true })
</script>

<style scoped lang="scss">
@use './taskSections';
</style>
