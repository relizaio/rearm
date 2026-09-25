<template>
    <div class="dsec" v-if="openQuestionGroups.length && !answering">
        <div class="dsec__h">Open questions</div>
        <div v-for="g in openQuestionGroups" :key="String(g.priority)" class="fgroup">
            <div v-for="f in g.findings" :key="f.id ?? ''" class="frow">
                <code class="frow__id">{{ f.id }}</code>
                <span class="frow__title">{{ f.title }}</span>
            </div>
        </div>
    </div>
</template>

<script lang="ts" setup>
// Open questions, when there is no answer form showing them: the task is with the role meant to
// answer, and a reader still wants to see what it is waiting on.
import { computed } from 'vue'
import { Finding, groupByPriority } from '@/utils/agentDocuments'
import { answerableQuestions } from '@/utils/agentTaskQuestions'

const props = defineProps<{ task: any }>()

const answering = computed(() => answerableQuestions(props.task).length > 0)
const openQuestionGroups = computed(() => groupByPriority((props.task?.openQuestions ?? []) as Finding[]))
</script>

<style scoped lang="scss">
@use './taskSections';
</style>
