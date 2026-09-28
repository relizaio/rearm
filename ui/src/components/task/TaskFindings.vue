<template>
    <!-- The NEWEST round of each indexed type. Every round carries forward what the
         previous one left open, so the newest is the current state; summing rounds would
         count one finding several times. A decision is a new round, never an edit. -->
    <div class="dsec" v-for="r in findingRounds" :key="r.spec">
        <div class="dsec__h">
            {{ r.spec === 'TEST_REPORT' ? 'Test findings' : 'Review findings' }}
            <template v-if="r.release.document?.round"> · round {{ r.release.document.round }}</template>
            <n-tag v-if="documentVerdict(r.release)" size="tiny" :bordered="false"
                   :type="verdictType(documentVerdict(r.release))" style="margin-left: 6px">
                {{ documentVerdict(r.release) }}
            </n-tag>
        </div>
        <div v-for="f in r.findings" :key="f.id ?? ''" class="frow"
             :class="{ 'frow--closed': f.status !== 'OPEN' }">
            <code class="frow__id">{{ f.id }}</code>
            <n-tag size="tiny" :bordered="false" :type="f.priority === 1 ? 'error' : 'warning'">
                P{{ f.priority ?? '?' }}
            </n-tag>
            <n-tag v-if="f.status !== 'OPEN'" size="tiny" :bordered="false"
                   :type="statusType(f.status)">{{ f.status }}</n-tag>
            <n-tag v-if="isCorrection(f)" size="tiny" :bordered="false" type="info" class="frow__corr"
                   title="Filed by a person approving at a gate: work the task was approved past. It never blocks; the producer's next round addresses it.">correction</n-tag>
            <span class="frow__title">{{ f.title }}</span>
            <n-tag v-if="findingElement(f)" size="tiny" :bordered="false" type="info" class="frow__el"
                   title="The element this finding is about: open it under its document"
                   @click="emit('open-element', findingElement(f) ?? '')">{{ findingElement(f) }}</n-tag>
            <code v-if="findingLocation(f)" class="frow__loc" :title="findingLocationFull(f)">{{ findingLocation(f) }}</code>
            <span v-if="f.decidedBy" class="frow__dec" :title="f.resolution ?? ''">
                {{ f.decidedBy.kind === 'USER' ? 'decided by' : 'agent decided' }}
                <actor-ref :actor="f.decidedBy" :task="task"/><template v-if="f.decidedAt"> · <agent-time :at="f.decidedAt"/></template>
            </span>
            <n-button v-if="canDecide && f.status === 'OPEN'" size="tiny" quaternary
                      @click="toggleDecide(r.spec, f)">decide</n-button>
            <div v-if="deciding === r.spec + '/' + f.id" class="fedit">
                <n-input v-model:value="decisionWords" size="small"
                         placeholder="Why (needed to accept or dismiss)"/>
                <n-space :size="6" style="margin-top: 6px" align="center">
                    <n-button size="tiny" type="warning" :disabled="!decisionWords.trim()"
                              @click="decideOne(r.spec, { action: 'ACCEPT', findingId: f.id, resolution: decisionWords.trim() })">
                        Accept the risk
                    </n-button>
                    <n-button size="tiny" :disabled="!decisionWords.trim()"
                              @click="decideOne(r.spec, { action: 'DISMISS', findingId: f.id, resolution: decisionWords.trim() })">
                        Dismiss
                    </n-button>
                    <n-select v-model:value="decisionPriority" :options="priorityOptions" size="tiny"
                              style="width: 72px"/>
                    <n-button size="tiny" :disabled="decisionPriority == null || decisionPriority === f.priority"
                              @click="decideOne(r.spec, { action: 'SET_PRIORITY', findingId: f.id, priority: decisionPriority })">
                        Set priority
                    </n-button>
                </n-space>
            </div>
        </div>
    </div>

    <!-- At a human gate the gate box's form rides on the verdict; this one, which files a round now,
         shows there only when asked for (RD2-18). -->
    <div v-if="canDecide && standaloneFindingShown(task, board, priorityLevels)" class="dsec" data-testid="file-finding">
        <div class="dsec__h">File a finding</div>
        <div v-if="atHumanGate(task)" class="holdmeta" data-testid="file-now-line" style="margin-top: 0">{{ fileNowLine(board, priorityLevels) }}</div>
        <n-space :size="6" align="center">
            <n-select v-model:value="fileSpec" :options="fileSpecOptions" size="small" style="width: 130px"/>
            <n-input v-model:value="fileTitle" size="small" placeholder="What is wrong" style="width: 200px"/>
            <n-select v-model:value="filePriority" :options="filePriorityOptions" size="small" style="width: 72px"
                      data-testid="file-priority"/>
            <n-select v-if="!aboutOf(fileSpec)" v-model:value="fileAbout" :options="aboutOptions" size="small"
                      clearable placeholder="about" style="width: 150px"/>
            <n-button size="small" :disabled="!fileTitle.trim() || filePriority == null || !filePriorityAllowed" @click="fileFinding"
                      data-testid="file-submit">
                File
            </n-button>
        </n-space>
        <div class="holdmeta">
            A blocking finding sends the task to the role that produces what it is about, or to
            the coordinator when it names nothing.
        </div>
    </div>
</template>

<script lang="ts" setup>
// The newest findings round of each indexed type, a person's decisions on its items, and filing a
// new finding.
import AgentTime from '../AgentTime.vue'
import { computed, ref, watch } from 'vue'
import { atHumanGate, fileNowLine, fileNowPriorities, standaloneFindingShown } from '@/utils/agentGateFinding'
import { NButton, NInput, NSelect, NSpace, NTag } from 'naive-ui'
import ActorRef from '../ActorRef.vue'
import { findingElement } from '@/utils/agentElements'
import {
    DECIDABLE_STATUSES,
    DocumentRelease,
    Finding,
    INDEXED_TYPES,
    documentVerdict,
    findingLocation,
    findingLocationFull,
    isCorrection,
    latestRound,
    sortFindings,
    statusType,
    verdictType,
} from '@/utils/agentDocuments'
import { canOperate } from '@/utils/agentBoardAccess'
import { aboutOptionsOf, fileSpecOptions, priorityOptionsOf } from '@/utils/agentTaskOptions'

const props = defineProps<{
    task: any
    roles?: any[]
    priorityLevels?: number
    /** The task's board: deciding and filing need BOARD_WRITE on it (RD2-6); hidden, not disabled. */
    board?: any
}>()
const emit = defineEmits<{
    (e: 'open-element', id: string): void
    (e: 'decide', p: { task: any, specification: string, decisions: any[],
        about?: { specification: string } | null }): void
}>()

const deciding = ref<string | null>(null)
const decisionWords = ref('')
const decisionPriority = ref<number | null>(null)
const fileSpec = ref('REVIEW_FINDINGS')
const fileTitle = ref('')
const filePriority = ref<number | null>(null)
const fileAbout = ref<string | null>(null)

const canDecide = computed(() => DECIDABLE_STATUSES.includes(props.task?.status) && canOperate(props.board))
const priorityOptions = computed(() => priorityOptionsOf(props.priorityLevels))
/** At a gate the form files now, so only priorities that do not block (RD2-18 run 1, T-1); elsewhere every one. */
const filePriorityOptions = computed(() => atHumanGate(props.task)
    ? priorityOptions.value.filter(o => fileNowPriorities(props.board, props.priorityLevels).includes(o.value))
    : priorityOptions.value)
const filePriorityAllowed = computed(() => filePriorityOptions.value.some(o => o.value === filePriority.value))
const aboutOptions = computed(() => aboutOptionsOf(props.roles))
const taskDocuments = computed<DocumentRelease[]>(() => props.task?.documents ?? [])

// The newest round of each findings type, open items first.
const findingRounds = computed(() => INDEXED_TYPES
    .map(spec => ({ spec, release: latestRound(taskDocuments.value, spec) }))
    .filter(r => r.release !== null)
    .map(r => {
        const all = sortFindings(((r.release as DocumentRelease).document?.findings?.findings ?? []) as Finding[])
        return {
            spec: r.spec,
            release: r.release as DocumentRelease,
            findings: [...all.filter(f => f.status === 'OPEN'), ...all.filter(f => f.status !== 'OPEN')],
        }
    }))

function aboutOf (spec: string): string | null {
    return latestRound(taskDocuments.value, spec)?.document?.findings?.about?.specification ?? null
}

function toggleDecide (spec: string, f: Finding) {
    const key = spec + '/' + f.id
    deciding.value = deciding.value === key ? null : key
    decisionWords.value = ''
    decisionPriority.value = f.priority ?? null
}

function decideOne (spec: string, decision: any) {
    emit('decide', { task: props.task, specification: spec, decisions: [decision] })
    deciding.value = null
}

function fileFinding () {
    emit('decide', {
        task: props.task,
        specification: fileSpec.value,
        decisions: [{ action: 'FILE', title: fileTitle.value.trim(), priority: filePriority.value }],
        about: fileAbout.value ? { specification: fileAbout.value } : null,
    })
    fileTitle.value = ''
}

watch(() => props.task?.uuid, () => {
    deciding.value = null
    fileTitle.value = ''
    fileAbout.value = null
})
// A priority the form no longer offers (the task reached a gate) is dropped, not sent (RD2-18 run 1, T-1).
watch(filePriorityOptions, opts => {
    if (filePriority.value != null && !opts.some(o => o.value === filePriority.value)) filePriority.value = null
})
</script>

<style scoped lang="scss">
@use './taskSections';
</style>
