<template>
    <div class="dsec" v-if="openQuestionGroups.length && !answering">
        <div class="dsec__h">{{ title }}</div>
        <div v-if="latest?.waitingOn" class="oq__sub">waiting on {{ latest.waitingOn.roleName ?? 'a role' }}</div>
        <div v-else-if="latest?.withCoordinator" class="oq__sub">with the coordinator to name a role</div>
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
import { latestQuestionRound, questionRoundLabel } from '@/utils/agentQuestionRounds'

const props = defineProps<{ task: any, roles?: any[] }>()

const answering = computed(() => answerableQuestions(props.task).length > 0)
const openQuestionGroups = computed(() => groupByPriority((props.task?.openQuestions ?? []) as Finding[]))
// "Questions from coder · round 1 · about ARCHITECTURE round 1 · open (2)" (gaps §1.27).
const latest = computed(() => latestQuestionRound(props.task, props.roles))
const title = computed(() => {
    const open = (props.task?.openQuestions ?? []).length
    return latest.value ? `${questionRoundLabel(latest.value)} · open (${open})` : 'Open questions'
})
</script>

<style scoped lang="scss">
@use './taskSections';

.oq__sub {
    font-size: 12px;
    opacity: 0.75;
    margin: -2px 0 4px;
}
</style>
