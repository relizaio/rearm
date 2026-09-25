<template>
    <div class="dsec" v-if="taskDocuments.length">
        <div class="dsec__h">Documents</div>
        <div v-for="d in taskDocuments" :key="d.uuid ?? ''" class="drow">
            <span class="drow__label">{{ documentLabel(d) }}</span>
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
        </div>
    </div>
</template>

<script lang="ts" setup>
// Documents this task has produced, newest first as the server returns them.
import { computed } from 'vue'
import { NTag } from 'naive-ui'
import { DocumentRelease, documentFileUrl, documentLabel, documentVerdict, testCounts, verdictType } from '@/utils/agentDocuments'

const props = defineProps<{ task: any }>()

const taskDocuments = computed<DocumentRelease[]>(() => props.task?.documents ?? [])
</script>

<style scoped lang="scss">
@use './taskSections';
</style>
