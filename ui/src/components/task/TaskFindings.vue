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
            <span class="frow__title">{{ f.title }}</span>
            <code v-if="findingLocation(f)" class="frow__loc" :title="findingLocation(f)">{{ findingLocation(f) }}</code>
            <span v-if="f.decidedBy" class="frow__dec" :title="f.resolution ?? ''">
                {{ f.decidedBy.kind === 'USER' ? 'decided by' : 'agent decided' }}
                {{ actorLabel(f.decidedBy) }}<template v-if="f.decidedAt"> · {{ ts(f.decidedAt) }}</template>
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

    <div v-if="canDecide" class="dsec">
        <div class="dsec__h">File a finding</div>
        <n-space :size="6" align="center">
            <n-select v-model:value="fileSpec" :options="fileSpecOptions" size="small" style="width: 130px"/>
            <n-input v-model:value="fileTitle" size="small" placeholder="What is wrong" style="width: 200px"/>
            <n-select v-model:value="filePriority" :options="priorityOptions" size="small" style="width: 72px"/>
            <n-select v-if="!aboutOf(fileSpec)" v-model:value="fileAbout" :options="aboutOptions" size="small"
                      clearable placeholder="about" style="width: 150px"/>
            <n-button size="small" :disabled="!fileTitle.trim() || filePriority == null" @click="fileFinding">
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
import { computed, ref, watch } from 'vue'
import { NButton, NInput, NSelect, NSpace, NTag } from 'naive-ui'
import { actorLabel } from '@/utils/agentActors'
import {
    DECIDABLE_STATUSES,
    DocumentRelease,
    Finding,
    INDEXED_TYPES,
    documentVerdict,
    findingLocation,
    latestRound,
    sortFindings,
    statusType,
    verdictType,
} from '@/utils/agentDocuments'
import { ts } from '@/utils/agentTaskFormat'
import { aboutOptionsOf, fileSpecOptions, priorityOptionsOf } from '@/utils/agentTaskOptions'

const props = defineProps<{
    task: any
    roles?: any[]
    priorityLevels?: number
}>()
const emit = defineEmits<{
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

const canDecide = computed(() => DECIDABLE_STATUSES.includes(props.task?.status))
const priorityOptions = computed(() => priorityOptionsOf(props.priorityLevels))
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
</script>

<style scoped lang="scss">
@use './taskSections';
</style>
