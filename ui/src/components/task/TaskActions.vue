<template>
    <div v-if="!terminal" class="dsec">
        <div class="dsec__h">Task actions</div>
        <div v-if="authorizable" class="deprow">
            <n-select v-model:value="authorizeRole" :options="roleOptions" size="small"
                      placeholder="role" style="width: 170px"/>
            <disabled-hint :reason="hint('authorize')">
                <n-button size="small" :disabled="!authorizeRole" data-testid="authorize"
                          @click="emit('authorize', { task, role: authorizeRole as string, orderIndex: orderDraft })">
                    Authorize
                </n-button>
            </disabled-hint>
        </div>
        <div class="deprow">
            <n-input-number v-model:value="orderDraft" size="small" :min="0" style="width: 130px">
                <template #prefix><span class="deplab" style="min-width: 0">order</span></template>
            </n-input-number>
            <disabled-hint :reason="hint('order')">
                <n-button size="small" :disabled="orderDraft == null || orderDraft === task.orderIndex" data-testid="order-set"
                          @click="emit('order', { task, orderIndex: orderDraft as number })">Set order</n-button>
            </disabled-hint>
            <span v-if="task.orderSetBy" class="holdmeta" style="margin-top: 0">
                set by <actor-ref :actor="task.orderSetBy" :task="task"/> · {{ ts(task.orderSetAt) }}
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
                set by <actor-ref :actor="task.strengthSetBy" :task="task"/> · {{ ts(task.strengthSetAt) }}
            </span>
        </div>
        <!-- The task's level, the depth in the product tree (RD2-1): a declaration the board makes
             about the work, as strength and budget are. Blank reads the board's default. -->
        <div class="deprow lvlrow" data-testid="level-row">
            <n-input-number v-model:value="levelDraft" size="small" :min="0" :max="MAX_LEVEL" :step="1" :precision="0"
                            :placeholder="levelPlaceholder(board)" style="width: 190px">
                <template #prefix><span class="deplab" style="min-width: 0">level</span></template>
            </n-input-number>
            <n-button size="small" :disabled="levelToSet(task, levelDraft) === undefined" data-testid="level-set"
                      @click="emit('set-level', { task, level: levelToSet(task, levelDraft) as number })">
                Set level
            </n-button>
            <n-button v-if="task.level != null" size="small" quaternary data-testid="level-clear"
                      @click="emit('set-level', { task, level: null })">Clear</n-button>
            <span v-if="task.levelSetBy" class="holdmeta" style="margin-top: 0">
                set by <actor-ref :actor="task.levelSetBy" :task="task"/> · {{ ts(task.levelSetAt) }}
            </span>
        </div>
        <!-- The task's group and tags (RD2-31): a move goes into an OPEN group or out of every group;
             the tags are replaced whole on save, each key trimmed and lower-cased as the server does. -->
        <div v-if="!terminal && (board?.groups?.length || task.group)" class="deprow grouprow" data-testid="group-move-row">
            <n-select v-model:value="groupDraft" size="small" style="width: 220px" data-testid="group-select"
                      :options="groupOptions(board, { openOnly: true, none: true })" placeholder="group"/>
            <n-button size="small" :disabled="!groupChanged" data-testid="group-move"
                      @click="emit('set-group', { task, group: groupToSend(groupDraft) })">Move</n-button>
        </div>
        <div class="deprow tagrow" data-testid="tags-row">
            <span class="deplab">tags</span>
            <n-dynamic-tags :value="tagsDraft" size="small" data-testid="tags-edit"
                            @update:value="(v: string[]) => tagsDraft = parseTags(v.join(','))">
                <!-- A named trigger: the default is an icon-only "+" a screen reader cannot name. -->
                <template #trigger="{ activate, disabled }">
                    <n-button size="small" dashed :disabled="disabled" aria-label="Add tag" data-testid="tags-add"
                              @click="activate()">+ tag</n-button>
                </template>
            </n-dynamic-tags>
            <n-button size="small" :disabled="!tagsReady" data-testid="tags-save"
                      @click="emit('set-tags', { task, tags: tagsToSet(task, tagsDraft) ?? [] })">Save tags</n-button>
            <span v-if="tagsProblem(tagsDraft)" class="holdmeta" style="margin-top: 0; color: #d03050">{{ tagsProblem(tagsDraft) }}</span>
        </div>
        <!-- A DELIVERING task on a board with no PRs waits for its push or release to be attested
             (RD2-10); a linked PR is attested from its row under Pull requests instead. -->
        <div v-if="task.status === 'DELIVERING' && !(task.prUrls?.length)" class="deprow attestrow" data-testid="attest-delivery-row">
            <template v-if="!deliveryDraft">
                <n-button size="small" data-testid="attest-delivery" @click="deliveryDraft = attestDraftOf(null)">Attest delivery…</n-button>
            </template>
            <template v-else>
                <n-input v-model:value="deliveryDraft.unit" size="small" placeholder="Unit: the branch or release"
                         style="width: 200px" data-testid="attest-delivery-unit"/>
                <n-input v-model:value="deliveryDraft.commit" size="small" placeholder="Commit (7 to 40 hex)" style="width: 170px"
                         data-testid="attest-delivery-commit"
                         :status="deliveryDraft.commit && commitProblem(deliveryDraft.commit) ? 'error' : undefined"/>
                <n-input v-model:value="deliveryDraft.note" size="small" placeholder="Note (optional)" style="width: 180px"/>
                <n-button size="small" type="primary" :disabled="!attestPayload(task, deliveryDraft)" data-testid="attest-delivery-submit"
                          @click="submitDelivery">Attest</n-button>
                <n-button size="small" quaternary @click="deliveryDraft = null">Cancel</n-button>
            </template>
        </div>
        <!-- An operator hold: the coordinator cannot lift it; the hold banner offers the release. -->
        <div v-if="canPlaceHold(task, !!admin)" class="deprow holdrow">
            <n-input v-model:value="holdReason" size="small" placeholder="Why hold it (required)"
                     style="width: 260px"/>
            <disabled-hint :reason="hint('hold')">
                <n-button size="small" type="warning" ghost :disabled="!holdPayload(task, holdReason)" data-testid="hold-place"
                          @click="placeHold">Put on hold (operator)</n-button>
            </disabled-hint>
        </div>
        <!-- What this task may spend, on top of the board's limit (task 6f1b348d). Blank and set
             clears it. A raise does not release a budget hold: release the hold to resume. -->
        <div class="deprow budrow">
            <n-input-number v-model:value="budgetDraft" size="small" :min="0" :precision="2"
                            placeholder="none" style="width: 170px" data-testid="task-budget">
                <template #prefix><span class="deplab" style="min-width: 0">budget $</span></template>
            </n-input-number>
            <n-button size="small" :disabled="!budgetChanged(task.budgetMicros, budgetDraft)"
                      @click="emit('set-budget', { task, budgetMicros: dollarsToMicros(budgetDraft) })">
                {{ budgetDraft == null && task.budgetMicros != null ? 'Clear budget' : 'Set budget' }}
            </n-button>
            <span v-if="task.budgetSetBy" class="holdmeta" style="margin-top: 0">
                set by <actor-ref :actor="task.budgetSetBy" :task="task"/> · {{ ts(task.budgetSetAt) }}
            </span>
        </div>
        <div class="deprow">
            <disabled-hint :reason="hint('complete')">
                <n-button size="small" type="primary" ghost :disabled="!completable" data-testid="complete-open"
                          @click="showComplete = true">Complete…</n-button>
            </disabled-hint>
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
                    <disabled-hint :reason="hint('reopen')">
                        <n-button size="small" :disabled="!reopenReady || boardLocked(board)" data-testid="reopen">
                            Reopen to {{ reopenRole ?? '…' }}
                        </n-button>
                    </disabled-hint>
                </template>
                The role's earlier pass stops counting; whoever read its part re-runs when it
                republishes.
            </n-popconfirm>
        </div>
    </div>
    <!-- Who reopened it, to which role and why (RD2-16). -->
    <div v-if="reopenLine(task, ts)" class="holdmeta" data-testid="reopen-line">{{ reopenLine(task, ts) }}</div>

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
import { NButton, NCheckbox, NDynamicTags, NInput, NInputNumber, NModal, NPopconfirm, NSelect, NSpace, NTag } from 'naive-ui'
import { groupOptions, groupToSend, NO_GROUP, parseTags, tagKeys, tagsProblem, tagsToSet } from '@/utils/agentTaskGroups'
import ActorRef from '../ActorRef.vue'
import DisabledHint from './DisabledHint.vue'
import { boardLocked, COMPLETABLE, disabledReason, HintedAction, reopenLine } from '@/utils/agentTaskHints'
import { reopenPayload, reopenRoleOptions } from '@/utils/agentReopen'
import { DocumentRelease, completionBlockers } from '@/utils/agentDocuments'
import { isTerminal, missingRequiredRoles, ts } from '@/utils/agentTaskFormat'
import { roleOptionsOf } from '@/utils/agentTaskOptions'
import { canPlaceHold, holdPayload, strengthPlaceholder, strengthToSet } from '@/utils/agentTaskAdmin'
import { levelPlaceholder, levelToSet, MAX_LEVEL } from '@/utils/agentTaskLevel'
import { budgetChanged, dollarsToMicros, microsToDollars } from '@/utils/agentBudget'
import { AttestDraft, attestDraftOf, attestPayload, commitProblem } from '@/utils/agentDelivery'

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
    (e: 'set-budget', p: { task: any, budgetMicros: number | null }): void
    (e: 'complete', p: { task: any, note: string, skipRequiredRoles: boolean }): void
    (e: 'cancel', p: { task: any, note: string }): void
    (e: 'reopen', p: { task: any, role: string, reason: string }): void
    (e: 'decide', p: { task: any, specification: string, decisions: any[],
        about?: { specification: string } | null }): void
    (e: 'set-strength', p: { task: any, requiredStrength: number | null }): void
    (e: 'set-level', p: { task: any, level: number | null }): void
    (e: 'set-group', p: { task: any, group: string | null }): void
    (e: 'set-tags', p: { task: any, tags: { key: string, value?: string | null }[] }): void
    (e: 'delivered', p: { task: any, unit: string, commit: string | null, outcome: string, note: string | null }): void
    (e: 'operator-hold', p: { task: any, reason: string }): void
}>()

const authorizeRole = ref<string | null>(null)
const orderDraft = ref<number | null>(null)
const budgetDraft = ref<number | null>(null)
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
const levelDraft = ref<number | null>(null)
const deliveryDraft = ref<AttestDraft | null>(null)
function submitDelivery () {
    const p = deliveryDraft.value ? attestPayload(props.task, deliveryDraft.value) : null
    if (!p) return
    emit('delivered', p)
    deliveryDraft.value = null
}
const groupDraft = ref<string>(NO_GROUP)
const groupChanged = computed(() => groupToSend(groupDraft.value) !== (props.task?.group?.key ?? null))
const tagsDraft = ref<string[]>([])
const tagsReady = computed(() => tagsToSet(props.task, tagsDraft.value) !== undefined && !tagsProblem(tagsDraft.value))
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
const completable = computed(() => COMPLETABLE.includes(props.task?.status))
/** Why a button is disabled, from the same rules the server applies (RD2-16). */
function hint (action: HintedAction): string | null {
    return disabledReason(action, props.task, props.board, { role: authorizeRole.value, order: orderDraft.value,
        holdReason: holdReason.value, reopenRole: reopenRole.value, reopenReason: reopenReason.value })
}
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
    levelDraft.value = props.task?.level ?? null
    deliveryDraft.value = null
    groupDraft.value = props.task?.group?.key ?? NO_GROUP
    tagsDraft.value = tagKeys(props.task)
    holdReason.value = ''
    budgetDraft.value = microsToDollars(props.task?.budgetMicros)
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
