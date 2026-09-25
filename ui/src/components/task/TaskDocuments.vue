<template>
    <div class="dsec" v-if="taskDocuments.length">
        <div class="dsec__h">Documents</div>
        <template v-for="d in taskDocuments" :key="d.uuid ?? ''">
        <div class="drow">
            <span class="drow__label">{{ documentLabel(d) }}</span>
            <n-tag v-if="documentLifecycleLabel(d)" size="tiny" :bordered="false"
                   :type="documentLifecycleLabel(d)?.type" :title="d.lifecycle ?? undefined">{{ documentLifecycleLabel(d)?.label }}</n-tag>
            <n-tag v-if="documentVerdict(d)" size="tiny" :bordered="false"
                   :type="verdictType(documentVerdict(d))">{{ documentVerdict(d) }}</n-tag>
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
import AiAgentDocumentElements from '../AiAgentDocumentElements.vue'
import { DocumentRelease, documentFileUrl, documentLabel, documentLifecycleLabel, documentVerdict, testCounts, verdictType } from '@/utils/agentDocuments'
import { documentDefining, elementsOf } from '@/utils/agentElements'

const props = defineProps<{
    task: any
    /** An element to open under its document, e.g. from a finding's element chip; n re-triggers the same id. */
    focus?: { id: string, n: number } | null
}>()

const taskDocuments = computed<DocumentRelease[]>(() => props.task?.documents ?? [])

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
</style>
