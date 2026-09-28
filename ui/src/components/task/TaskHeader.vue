<template>
    <n-alert v-if="task.hold" type="error"
             :title="task.hold.kind === 'HUMAN_GATE' ? 'Awaiting human review' : `On hold (${(task.hold.level ?? '').toLowerCase()})`">
        {{ task.hold.reason }}
        <div class="holdmeta">held by <actor-ref :actor="task.hold.heldBy" :task="task"/> · {{ ts(task.hold.heldAt) }}
            <n-tag v-if="holdWho" size="small" :bordered="false" class="holdwho"
                   :type="task.hold.level === 'OPERATOR' ? 'error' : 'info'">{{ holdWho }}</n-tag>
        </div>
        <template v-if="task.hold.kind === 'HUMAN_GATE' && canOperate">
            <n-input v-model:value="reviewNote" size="small" placeholder="Review note (optional)"
                     style="margin-top: 8px"/>
            <!-- A rejection with a finding attached routes like a reviewer's: to whoever
                 produces what it is about. Without one it goes to the coordinator. An
                 approval with one files it as a correction, which never blocks, and hands the
                 work over; the server refuses an approval that leaves an existing blocking item
                 undecided. -->
            <n-space :size="6" style="margin-top: 8px" align="center">
                <n-input v-model:value="gateFindingTitle" size="small"
                         placeholder="Finding or correction (optional)" style="width: 230px"/>
                <n-select v-model:value="gateFindingPriority" :options="priorityOptions" size="small"
                          style="width: 72px"/>
                <n-select v-model:value="gateAbout" :options="aboutOptions" size="small" clearable
                          placeholder="about" style="width: 150px"/>
            </n-space>
            <!-- The gated hop rejected with items that block (task RD2-25): approving past them needs a
                 decision on each, with the person's words, as the server requires; sending it back
                 leads. -->
            <div v-if="rejected && blocking.length" class="gatefinds" data-testid="gate-findings">
                <div class="holdmeta">The {{ task.hold.gateRole }} rejected over these. Send it back, or decide each to approve past it:</div>
                <div v-for="f in blocking" :key="f.id" class="gatefind" :data-finding="f.id">
                    <span class="gatefind__id">{{ f.id }}{{ f.priority != null ? ` (P${f.priority})` : '' }}</span>
                    <span class="gatefind__title">{{ f.title }}</span>
                    <n-radio-group v-model:value="decisionOf(f.id).action" size="small">
                        <n-radio-button value="ACCEPT" class="gatefind__accept">accept</n-radio-button>
                        <n-radio-button value="DISMISS" class="gatefind__dismiss">dismiss</n-radio-button>
                    </n-radio-group>
                    <n-input v-model:value="decisionOf(f.id).reason" size="small" class="gatefind__reason"
                             placeholder="why (required)" style="width: 200px"/>
                </div>
            </div>
            <n-space style="margin-top: 8px">
                <n-button v-if="rejected" size="small" type="error" class="gate-reject" @click="reviewAtGate(false)">
                    {{ rejectLabel(true, !!gateFindingTitle.trim()) }}
                </n-button>
                <n-popconfirm v-if="rejected && blocking.length" :disabled="undecidedCount > 0"
                              @positive-click="reviewAtGate(true)">
                    <template #trigger>
                        <n-button size="small" ghost class="gate-approve" :disabled="undecidedCount > 0">
                            {{ approveLabel(task, true, blocking, !!gateFindingTitle.trim()) }}
                        </n-button>
                    </template>
                    <span class="gate-confirm">{{ approveConfirm(blocking, decisions) }}</span>
                </n-popconfirm>
                <n-button v-else size="small" :type="rejected ? 'default' : 'primary'" :ghost="rejected"
                          class="gate-approve" @click="reviewAtGate(true)">
                    {{ approveLabel(task, rejected, blocking, !!gateFindingTitle.trim()) }}
                </n-button>
                <n-button v-if="!rejected" size="small" type="error" ghost class="gate-reject" @click="reviewAtGate(false)">
                    {{ rejectLabel(false, !!gateFindingTitle.trim()) }}
                </n-button>
            </n-space>
        </template>
        <template v-else-if="task.hold.kind === 'QUESTION'">
            <div class="holdmeta">
                A question on this task has nobody to answer it. Answer it {{ answerWhere }} — the
                board routes your answer back to whoever asked.
            </div>
        </template>
        <template v-else-if="canOperate && personMayRelease(task.hold)">
            <!-- A loop stop is released past that stop once: to the role routing would pick, or to
                 the one named here (task 4c566d0d). The cycle is still counted. The first stop of a
                 kind is the coordinator's to release once, and a person may release it too; either
                 is the task's one release of that kind (task c0a2134c). -->
            <div v-if="loopStop" class="holdmeta relstop">
                Releasing routes past this stop once, to the role routing picks or the one you name.
                <template v-if="task.hold.level === 'COORDINATOR'">
                    The coordinator may release it; a release by you counts as the one release of this
                    stop kind, and the next is the operator's.
                </template>
            </div>
            <n-input v-model:value="releaseNote" size="small"
                     placeholder="Note on release (optional)" style="margin-top: 8px"/>
            <n-space style="margin-top: 8px" align="center">
                <n-select v-if="loopStop" v-model:value="releaseRole" :options="roleOptions" size="small"
                          clearable placeholder="role routing picks" style="width: 190px" class="relrole"/>
                <n-button size="small" class="relbtn"
                          @click="emit('operator-release', releasePayload(task, releaseNote, loopStop ? releaseRole : null))">
                    {{ releaseLabel(loopStop, loopStop ? releaseRole : null) }}
                </n-button>
            </n-space>
        </template>
    </n-alert>

    <n-alert v-if="task.status === 'AWAITING_COORDINATOR' && subtasks.total && subtasks.done < subtasks.total"
             type="info" title="Waiting on its subtasks">
        {{ subtasks.done }} of {{ subtasks.total }} done; the board completes it when they finish.
    </n-alert>

    <n-alert v-if="humanStageRole && canOperate" type="info" :title="`Human stage: ${humanStageRole.name}`">
        <div v-if="humanStageRole.prompt" class="holdmeta">{{ humanStageRole.prompt }}</div>
        <n-input v-model:value="reviewNote" size="small" placeholder="Sign-off note (optional)"
                 style="margin-top: 8px"/>
        <n-space style="margin-top: 8px">
            <n-button size="small" type="primary"
                      @click="emit('human-signoff', { task, outcome: 'PASSED', note: reviewNote })">
                Sign off PASSED
            </n-button>
            <n-button size="small" type="error" ghost
                      @click="emit('human-signoff', { task, outcome: 'REJECTED', note: reviewNote })">
                REJECTED
            </n-button>
        </n-space>
    </n-alert>

    <div v-if="!terminal" class="dsec">
        <div class="dsec__h">Human review</div>
        <div class="deprow">
            <n-tag v-if="task.requireHumanReview" size="small" :bordered="false" type="warning">
                next sign-off requires human review
            </n-tag>
            <n-button v-if="canOperate" size="tiny" quaternary
                      @click="emit('require-review', { task, value: !task.requireHumanReview })">
                {{ task.requireHumanReview ? 'clear flag (operator)' : 'require human review of next sign-off' }}
            </n-button>
            <n-tag v-for="m in missingRequired" :key="m" size="small" :bordered="false" type="error">
                required: {{ m }} ✗
            </n-tag>
        </div>
    </div>
</template>

<script lang="ts" setup>
// The hold banner with the controls a hold carries (a gate verdict, an operator release), the human
// stage of a HUMAN role, and the human-review flag. A gate verdict or a release must stay one click
// away wherever the task is shown, so the drawer's preview keeps this whole.
import { computed, ref, watch } from 'vue'
import { NAlert, NButton, NInput, NPopconfirm, NRadioButton, NRadioGroup, NSelect, NSpace, NTag } from 'naive-ui'
import { approveConfirm, approveLabel, gateBlockingFindings, gateDecisions, GateDecision, gateRejected, rejectLabel,
    undecided } from '@/utils/agentGateReview'
import ActorRef from '../ActorRef.vue'
import { isTerminal, missingRequiredRoles, ts } from '@/utils/agentTaskFormat'
import { aboutOptionsOf, priorityOptionsOf } from '@/utils/agentTaskOptions'
import { subtaskProgress } from '@/utils/agentTaskLabels'
import { holdReleaseNote, isLoopStopHold, personMayRelease, releaseLabel, releasePayload, releaseRoleOptions } from '@/utils/agentHoldRelease'

const props = defineProps<{
    task: any
    /** The board's tasks, for a split parent's subtask progress. */
    tasks?: any[]
    roles?: any[]
    priorityLevels?: number
    /** The answer form is on the task page, not beside this banner (the drawer's preview). */
    questionsOnPage?: boolean
    /** BOARD_WRITE on the task's board (task d8e7bd7e): the gate verdict, release and human stage are shown. */
    canOperate?: boolean
    /** The task's board: its blocking priority says which of a rejected hop's items block (task RD2-25). */
    board?: any
}>()
const emit = defineEmits<{
    (e: 'human-review', p: { task: any, approve: boolean, note: string, findings?: any[],
        about?: { specification: string } | null }): void
    (e: 'human-signoff', p: { task: any, outcome: string, note: string }): void
    (e: 'operator-release', p: { task: any, note: string, role?: string }): void
    (e: 'require-review', p: { task: any, value: boolean }): void
}>()

const reviewNote = ref('')
const releaseNote = ref('')
const releaseRole = ref<string | null>(null)
const loopStop = computed(() => isLoopStopHold(props.task?.hold))
const holdWho = computed(() => holdReleaseNote(props.task?.hold))
const roleOptions = computed(() => releaseRoleOptions(props.roles))
const gateFindingTitle = ref('')
const gateFindingPriority = ref<number | null>(1)
const gateAbout = ref<string | null>(null)
// A rejected gate's blocking items and the person's decision on each (task RD2-25).
const rejected = computed(() => gateRejected(props.task))
const blocking = computed(() => rejected.value ? gateBlockingFindings(props.task, props.board) : [])
const decisions = ref<Record<string, GateDecision>>({})
function decisionOf (id: string): GateDecision {
    if (!decisions.value[id]) decisions.value[id] = { action: null, reason: '' }
    return decisions.value[id]
}
const undecidedCount = computed(() => undecided(blocking.value, decisions.value).length)

const priorityOptions = computed(() => priorityOptionsOf(props.priorityLevels))
const aboutOptions = computed(() => aboutOptionsOf(props.roles))
const terminal = computed(() => isTerminal(props.task))
const subtasks = computed(() => subtaskProgress(props.task, props.tasks ?? []))
const answerWhere = computed(() => props.questionsOnPage ? 'on the task page' : 'under Waiting on')
const missingRequired = computed(() => missingRequiredRoles(props.task, props.roles))

// Task queued in a HUMAN-kind role: org admins sign off directly (no claim step).
const humanStageRole = computed(() => {
    if (props.task?.status !== 'QUEUED') return null
    const rc = (props.roles ?? []).find(r => r.name === props.task.role)
    return rc?.kind === 'HUMAN' ? rc : null
})

/**
 * Either verdict may carry the typed finding: a rejection's reason, or an approval's correction. An
 * approval past a rejected hop also carries the person's decision on each blocking item.
 */
function reviewAtGate (approve: boolean) {
    const title = gateFindingTitle.value.trim()
    const decided = approve ? gateDecisions(blocking.value, decisions.value) : []
    const filed = title ? [{ action: 'FILE', title, priority: gateFindingPriority.value }] : []
    const findings = [...decided, ...filed]
    emit('human-review', {
        task: props.task,
        approve,
        note: reviewNote.value,
        findings: findings.length ? findings : undefined,
        about: title && gateAbout.value ? { specification: gateAbout.value } : null,
    })
}

watch(() => props.task?.uuid, () => {
    reviewNote.value = ''
    releaseNote.value = ''
    releaseRole.value = null
    gateFindingTitle.value = ''
    gateAbout.value = null
    decisions.value = {}
})
</script>

<style scoped lang="scss">
@use './taskSections';

.gatefinds { margin-top: 8px; }
.gatefind {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-top: 4px;
    flex-wrap: wrap;
}
.gatefind__id { font-family: monospace; font-size: 12px; }
.gatefind__title { font-size: 12px; }
</style>
