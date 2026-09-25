<template>
    <div class="dsec" v-if="taskDocuments.length">
        <div class="dsec__h">Documents</div>
        <template v-for="d in listedDocuments" :key="d.uuid ?? ''">
        <div class="drow">
            <span class="drow__label">{{ documentLabel(d) }}</span>
            <!-- A QUESTIONS round's verdict is the asking hop's REJECTED; it reads as if the
                 questions were rejected, so the round says whether it is open or answered. -->
            <n-tag v-if="documentVerdict(d) && d.document?.specification !== 'QUESTIONS'" size="tiny" :bordered="false"
                   :type="verdictType(documentVerdict(d))">{{ documentVerdict(d) }}</n-tag>
            <template v-if="questionRoundFor(d)">
                <n-tag size="tiny" :bordered="false" :type="questionStateType(questionRoundFor(d)!)"
                       class="drow__qstate">{{ questionStateLabel(questionRoundFor(d)!) }}</n-tag>
                <span v-for="a in questionRoundFor(d)!.answeredBy" :key="'release' in a ? a.release : `p${a.round}`"
                      class="drow__answered">{{ answeredByLabel(a) }}</span>
            </template>
            <n-tag v-if="testCounts(d)" size="tiny" :bordered="false" type="info">
                {{ testCounts(d)?.failed }} failed / {{ testCounts(d)?.passed }} passed
            </n-tag>
            <!-- A link only where we can build one. A guessed URL that 404s reads as
                 "the document is missing", so an unknown host shows the path instead. -->
            <a v-if="documentFileUrl(d)" :href="documentFileUrl(d) ?? undefined" target="_blank"
               rel="noopener" class="drow__path">{{ d.document?.path }}</a>
            <code v-else class="drow__path drow__path--plain">{{ d.document?.path }}</code>
            <code v-if="d.sourceCodeEntryDetails?.commit" class="drow__commit"
                  title="Commit this document is pinned to">{{ d.sourceCodeEntryDetails.commit.slice(0, 8) }}</code>
            <n-button v-if="elementsOf(d).length" size="tiny" quaternary
                      @click="expandedDoc = expandedDoc === d.uuid ? null : (d.uuid ?? null)">
                {{ elementsOf(d).length }} element{{ elementsOf(d).length === 1 ? '' : 's' }}
            </n-button>
        </div>
        <AiAgentCheckReport v-if="d.document?.elements" :release="d" :documents="taskDocuments"/>
        <AiAgentDocumentElements v-if="expandedDoc === d.uuid" :release="d" :documents="taskDocuments"
                                 :board-uuid="task?.board" :task-uuid="task?.uuid"
                                 :task-status="task?.status" :focus="focusedElement"/>
        </template>
    </div>
</template>

<script lang="ts" setup>
// Documents this task has produced, newest first as the server returns them.
import { computed, ref, watch } from 'vue'
import { NButton, NTag } from 'naive-ui'
import AiAgentCheckReport from '../AiAgentCheckReport.vue'
import AiAgentDocumentElements from '../AiAgentDocumentElements.vue'
import { DocumentRelease, documentFileUrl, documentLabel, documentVerdict, testCounts, verdictType } from '@/utils/agentDocuments'
import { documentDefining, elementsOf } from '@/utils/agentElements'
import { answeredByLabel, questionRounds, questionStateLabel, questionStateType } from '@/utils/agentQuestionRounds'

const props = defineProps<{
    task: any
    /** An element to open under its document, e.g. from a finding's element chip; n re-triggers the same id. */
    focus?: { id: string, n: number } | null
}>()

const taskDocuments = computed<DocumentRelease[]>(() => props.task?.documents ?? [])
// The rows of the Documents list: a CHECK_REPORT round is read under the document it is about.
const listedDocuments = computed(() => taskDocuments.value.filter(d => d?.document?.specification !== 'CHECK_REPORT'))
// Each QUESTIONS round's state and what answered it (gaps §1.27), by release.
const roundsByRelease = computed(() => new Map(questionRounds(props.task).map(r => [r.release, r])))
function questionRoundFor (d: DocumentRelease) {
    return d?.uuid ? roundsByRelease.value.get(d.uuid) ?? null : null
}

// The document whose element list is open, and the element to open in it (elements.md §8).
const expandedDoc = ref<string | null>(null)
const focusedElement = ref<string | null>(null)
watch(() => props.task?.uuid, () => {
    expandedDoc.value = null
    focusedElement.value = null
})
watch(() => props.focus, (f) => {
    if (!f?.id) return
    const d = documentDefining(taskDocuments.value, f.id)
    if (!d?.uuid) return
    expandedDoc.value = d.uuid
    // cleared first, so naming the same element again still opens it
    focusedElement.value = null
    setTimeout(() => { focusedElement.value = f.id })
})
</script>

<style scoped lang="scss">
@use './taskSections';

.drow__answered {
    font-size: 12px;
    opacity: 0.75;
}
</style>
