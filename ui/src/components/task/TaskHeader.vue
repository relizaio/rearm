<template>
    <!-- The board's pause, as the board page shows it (RD2-16): a task on a paused board says so here too. -->
    <n-alert v-if="pauseBannerText(board)" :type="board.pause.level === 'OPERATOR' ? 'error' : 'warning'"
             class="boardpause" data-testid="task-board-pause" :bordered="false">
        {{ pauseBannerText(board) }}
    </n-alert>
    <n-alert v-if="task.hold" type="error"
             :title="task.hold.kind === 'HUMAN_GATE' ? 'Awaiting human review'
                 : awaiting ? 'Awaiting the operator' : `On hold (${holdWord(task.hold)})`">
        {{ task.hold.reason }}
        <!-- A gated sign-off is held by the gate, not by the system actor that placed it (RD2-23). -->
        <div class="holdmeta"><template v-if="task.hold.kind === 'HUMAN_GATE'">{{ holdPhrase(task.hold) }}</template><template
            v-else>held by <actor-ref :actor="task.hold.heldBy" :task="task"/></template> · <agent-time :at="task.hold.heldAt"/>
            <n-tag v-if="holdWho" size="small" :bordered="false" class="holdwho"
                   :type="task.hold.level === 'OPERATOR' ? 'error' : 'info'">{{ holdWho }}</n-tag>
        </div>
        <!-- PRs linked since the seat parked it (task RD4-19): preparation for the decision, never its answer. -->
        <div v-for="(l, i) in linkedSinceParked(task)" :key="i" class="holdmeta parkedlink" data-testid="parked-link">
            {{ linkedLine(l) }} <agent-time :at="l.at"/>
        </div>
        <template v-if="task.hold.kind === 'HUMAN_GATE' && canOperate">
            <n-input v-model:value="reviewNote" size="small" placeholder="Review note (optional)"
                     style="margin-top: 8px"/>
            <!-- A rejection with a review item attached routes like a reviewer's: to whoever
                 produces what it is about. Without one it goes to the coordinator. An
                 acceptance with one files it as a correction, which never blocks, and hands the
                 work over; the server refuses an acceptance that leaves an existing blocking item
                 undecided. -->
            <n-space :size="6" style="margin-top: 8px" align="center">
                <n-input v-model:value="gateReviewItemTitle" size="small"
                         placeholder="Review item or correction (optional)" style="width: 230px"/>
                <n-select v-model:value="gateReviewItemPriority" :options="priorityOptions" size="small" placeholder="severity"
                          style="width: 96px" data-testid="gate-severity"/>
                <n-select v-model:value="gateAbout" :options="aboutOptions" size="small" clearable
                          placeholder="about" style="width: 150px"/>
            </n-space>
            <!-- One review item form at a gate (RD2-18): this one rides on the verdict; the review items section's
                 form, which files a round now, opens from here only when asked for. -->
            <div class="holdmeta gatemode" data-testid="gate-review-item-mode">
                {{ GATE_REVIEW_ITEM_MODE }}
                <!-- Filing now takes only what does not block (RD2-18 run 1, T-1): none on a strict board. -->
                <span v-if="!fileNowPriorities(board, priorityLevels).length" data-testid="gate-every-blocks">{{ EVERY_PRIORITY_BLOCKS }}</span>
                <n-button v-else text size="tiny" type="primary" class="gatefilenow" data-testid="gate-file-now"
                          @click="toggleFileNow(task)">
                    {{ fileNowOpened(task) ? 'hide the file-now form' : 'file now without deciding' }}
                </n-button>
            </div>
            <!-- The gated hop rejected with items that block (task RD2-25): accepting past them needs a
                 decision on each, with the person's words, as the server requires; sending it back
                 leads. -->
            <div v-if="rejected && blocking.length" class="gateitems" data-testid="gate-review-items">
                <div class="holdmeta">The {{ task.hold.gateRole }} rejected over these. Send it back, or decide each to accept past it:</div>
                <div v-for="f in blocking" :key="f.id" class="gateitem" :data-review-item="f.id">
                    <span class="gateitem__id">{{ f.id }}{{ f.priority != null ? ` (P${f.priority})` : '' }}</span>
                    <span class="gateitem__title">{{ f.title }}</span>
                    <n-radio-group v-model:value="decisionOf(f.id).action" size="small">
                        <n-radio-button value="ACCEPT" class="gateitem__accept">accept</n-radio-button>
                        <n-radio-button value="DISMISS" class="gateitem__dismiss">dismiss</n-radio-button>
                    </n-radio-group>
                    <n-input v-model:value="decisionOf(f.id).reason" size="small" class="gateitem__reason"
                             placeholder="why (required)" style="width: 200px"/>
                </div>
            </div>
            <n-space style="margin-top: 8px">
                <n-button v-if="rejected" size="small" type="error" class="gate-reject" @click="reviewAtGate(false)">
                    {{ rejectLabel(true, !!gateReviewItemTitle.trim()) }}
                </n-button>
                <n-popconfirm v-if="rejected && blocking.length" :disabled="undecidedCount > 0"
                              @positive-click="reviewAtGate(true)">
                    <template #trigger>
                        <n-button size="small" ghost class="gate-accept" :disabled="undecidedCount > 0">
                            {{ acceptLabel(task, true, blocking, !!gateReviewItemTitle.trim()) }}
                        </n-button>
                    </template>
                    <span class="gate-confirm">{{ acceptConfirm(blocking, decisions) }}</span>
                </n-popconfirm>
                <n-button v-else size="small" :type="rejected ? 'default' : 'primary'" :ghost="rejected"
                          class="gate-accept" @click="reviewAtGate(true)">
                    {{ acceptLabel(task, rejected, blocking, !!gateReviewItemTitle.trim()) }}
                </n-button>
                <n-button v-if="!rejected" size="small" type="error" ghost class="gate-reject" @click="reviewAtGate(false)">
                    {{ rejectLabel(false, !!gateReviewItemTitle.trim()) }}
                </n-button>
            </n-space>
        </template>
        <template v-else-if="task.hold.kind === 'QUESTION'">
            <div class="holdmeta">
                A question on this task has nobody to answer it. Answer it {{ answerWhere }} — the
                board routes your answer back to whoever asked.
            </div>
        </template>
        <template v-else-if="canOperate && personMayLift(task.hold)">
            <!-- A loop stop is lifted past that stop once: to the role routing would pick, or to
                 the one named here (task 4c566d0d). The cycle is still counted. The first stop of a
                 kind is the coordinator's to lift once, and a person may lift it too; either
                 is the task's one lift of that kind (task c0a2134c). -->
            <div v-if="loopStop" class="holdmeta relstop">
                Lifting routes past this stop once, to the role routing picks or the one you name.
                <template v-if="task.hold.level === 'COORDINATOR'">
                    The coordinator may lift it; a lift by you counts as the one lift of this
                    stop kind, and the next is the operator's.
                </template>
            </div>
            <!-- A hop its holder parked for the operator (task RD4-5): the lift is the answer, required,
                 recorded on the task, and the hop resumes with its holder, so no role is named. -->
            <div v-if="parked" class="holdmeta relparked" data-testid="parked-hop">
                The {{ task.role }} hop working this task asks the operator. Your answer is recorded on the task,
                and the hop resumes with its holder.
            </div>
            <!-- A task the coordinator seat parked for the operator (task RD4-17): the lift is the answer too,
                 and it returns the task to where it was parked; anything a person does on the task answers it as
                 well (architecture round 2), recorded as "<action> by <person>: <note>". -->
            <div v-else-if="seat" class="holdmeta relparked" data-testid="seat-parked">
                The coordinator asks the operator. Your answer is recorded on the task, and the task returns to
                {{ returnsTo(task) }}. Anything else you do on it (answering its questions, declaring, superseding,
                completing, reopening, cancelling, a new order or level) answers it too, and is recorded as the
                answer.
            </div>
            <n-input v-model:value="liftNote" size="small" data-testid="lift-note"
                     :placeholder="awaiting ? 'Your answer (required)' : 'Note on lifting (optional)'" style="margin-top: 8px"/>
            <!-- Every lift a person gives may name the role it routes to, a manual hold's as well as a
                 stop's (RD2-20): the verb takes one, and the feed says "routed to <role>". -->
            <n-space style="margin-top: 8px" align="center">
                <n-select v-if="!awaiting" v-model:value="liftRole" :options="roleOptions" size="small"
                          clearable placeholder="role routing picks" style="width: 190px" class="relrole"/>
                <n-button size="small" class="relbtn" :disabled="liftNeedsAnswer(task, liftNote)"
                          @click="emit('lift-hold', liftPayload(task, liftNote, awaiting ? null : liftRole))">
                    {{ awaiting ? 'Answer and lift' : liftLabel(loopStop, liftRole) }}
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

    <!-- The flag only; the required roles still to pass are a line under the status (RD2-23). -->
    <div v-if="!terminal && (task.requireHumanReview || canOperate)" class="dsec">
        <div class="dsec__h">Human review</div>
        <div class="deprow">
            <n-tag v-if="task.requireHumanReview" size="small" :bordered="false" type="warning">
                next sign-off requires human review
            </n-tag>
            <n-button v-if="canOperate" size="tiny" quaternary
                      @click="emit('require-review', { task, value: !task.requireHumanReview })">
                {{ task.requireHumanReview ? 'clear flag (operator)' : 'require human review of next sign-off' }}
            </n-button>
        </div>
    </div>
</template>

<script lang="ts" setup>
// The hold banner with the controls a hold carries (a gate verdict, an operator lift), the human
// stage of a HUMAN role, and the human-review flag. A gate verdict or a release must stay one click
// away wherever the task is shown, so the drawer's preview keeps this whole.
import { computed, ref, watch } from 'vue'
import { EVERY_PRIORITY_BLOCKS, fileNowOpened, fileNowPriorities, GATE_REVIEW_ITEM_MODE, toggleFileNow } from '@/utils/agentGateReviewItem'
import { NAlert, NButton, NInput, NPopconfirm, NRadioButton, NRadioGroup, NSelect, NSpace, NTag } from 'naive-ui'
import { acceptConfirm, acceptLabel, gateBlockingReviewItems, gateDecisions, GateDecision, gateRejected, rejectLabel,
    undecided } from '@/utils/agentGateReview'
import ActorRef from '../ActorRef.vue'
import AgentTime from '../AgentTime.vue'
import { isTerminal } from '@/utils/agentTaskFormat'
import { holdPhrase, holdWord } from '@/utils/agentWords'
import { aboutOptionsOf, priorityOptionsOf } from '@/utils/agentTaskOptions'
import { subtaskProgress } from '@/utils/agentTaskLabels'
import { holdLiftNote, isLoopStopHold, personMayLift, liftLabel, liftPayload, liftRoleOptions } from '@/utils/agentHoldLift'
import { pauseBannerText } from '@/utils/agentTaskHints'
import { awaitingOperator, linkedLine, linkedSinceParked, parkedHop, liftNeedsAnswer, returnsTo, seatParked } from '@/utils/agentOperatorQuestion'

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
    (e: 'human-review', p: { task: any, accept: boolean, note: string, reviewItems?: any[],
        about?: { specification: string } | null }): void
    (e: 'human-signoff', p: { task: any, outcome: string, note: string }): void
    (e: 'lift-hold', p: { task: any, note: string, role?: string }): void
    (e: 'require-review', p: { task: any, value: boolean }): void
}>()

const reviewNote = ref('')
const liftNote = ref('')
const liftRole = ref<string | null>(null)
const loopStop = computed(() => isLoopStopHold(props.task?.hold))
const parked = computed(() => parkedHop(props.task))
const seat = computed(() => seatParked(props.task))
const awaiting = computed(() => awaitingOperator(props.task))
const holdWho = computed(() => holdLiftNote(props.task?.hold))
const roleOptions = computed(() => liftRoleOptions(props.roles))
const gateReviewItemTitle = ref('')
const gateReviewItemPriority = ref<number | null>(1)
const gateAbout = ref<string | null>(null)
// A rejected gate's blocking items and the person's decision on each (task RD2-25).
const rejected = computed(() => gateRejected(props.task))
const blocking = computed(() => rejected.value ? gateBlockingReviewItems(props.task, props.board) : [])
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

// Task queued in a HUMAN-kind role: org admins sign off directly (no claim step).
const humanStageRole = computed(() => {
    if (props.task?.status !== 'QUEUED') return null
    const rc = (props.roles ?? []).find(r => r.name === props.task.role)
    return rc?.kind === 'HUMAN' ? rc : null
})

/**
 * Either verdict may carry the typed review item: a rejection's reason, or an acceptance's correction. An
 * acceptance past a rejected hop also carries the person's decision on each blocking item.
 */
function reviewAtGate (accept: boolean) {
    const title = gateReviewItemTitle.value.trim()
    const decided = accept ? gateDecisions(blocking.value, decisions.value) : []
    const filed = title ? [{ action: 'FILE', title, priority: gateReviewItemPriority.value }] : []
    const reviewItems = [...decided, ...filed]
    emit('human-review', {
        task: props.task,
        accept,
        note: reviewNote.value,
        reviewItems: reviewItems.length ? reviewItems : undefined,
        about: title && gateAbout.value ? { specification: gateAbout.value } : null,
    })
}

watch(() => props.task?.uuid, () => {
    reviewNote.value = ''
    liftNote.value = ''
    liftRole.value = null
    gateReviewItemTitle.value = ''
    gateAbout.value = null
    decisions.value = {}
})
</script>

<style scoped lang="scss">
@use './taskSections';

.gateitems { margin-top: 8px; }
.gateitem {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-top: 4px;
    flex-wrap: wrap;
}
.gateitem__id { font-family: monospace; font-size: 12px; }
.gateitem__title { font-size: 12px; }
</style>
