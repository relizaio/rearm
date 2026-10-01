<template>
    <!-- The questions a hop of this task (task RD4-5), or the coordinator seat (RD4-17), parked it on for the
         operator, each with the answer a person's lift or action gave: the record of a decision reached on the
         board rather than in a chat. -->
    <div v-if="questions.length" class="dsec" data-testid="operator-questions">
        <div class="dsec__h">Asked of the operator ({{ questions.length }})</div>
        <div v-for="(q, i) in questions" :key="i" class="oq" :data-answered="q.answer !== null">
            <div class="oq__q" data-testid="operator-question">{{ q.question }}</div>
            <div v-if="q.answer !== null" class="oq__a" data-testid="operator-answer">{{ q.answer }}</div>
            <div v-else class="oq__a oq__a--waiting" data-testid="operator-waiting">awaiting the operator</div>
            <!-- PRs linked while the seat had it parked (task RD4-19): beside the answer, not the answer. -->
            <div v-for="(l, j) in q.linked" :key="j" class="oq__link" data-testid="operator-question-link">
                {{ linkedLine(l) }} <agent-time :at="l.at"/>
            </div>
            <div class="oq__meta">
                asked by <template v-if="q.askedByCoordinator">the coordinator, </template><actor-ref :actor="q.askedBy" :task="task"/><template v-if="q.askedAt"> · <agent-time
                    :at="q.askedAt"/></template><template v-if="q.answer !== null"> · answered by <actor-ref
                    :actor="q.answeredBy" :task="task"/><template v-if="q.answeredAt"> · <agent-time
                    :at="q.answeredAt"/></template></template>
            </div>
        </div>
    </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import ActorRef from '../ActorRef.vue'
import AgentTime from '../AgentTime.vue'
import { linkedLine, operatorQuestions } from '@/utils/agentOperatorQuestion'

const props = defineProps<{ task: any }>()

const questions = computed(() => operatorQuestions(props.task))
</script>

<style scoped lang="scss">
@use './taskSections';

.oq {
    padding: 4px 0;
    border-top: 1px solid rgba(128, 128, 128, 0.15);
    &__q { font-weight: 600; white-space: pre-wrap; overflow-wrap: anywhere; }
    &__a { font-size: 13px; margin: 2px 0 0 4px; white-space: pre-wrap; overflow-wrap: anywhere; }
    &__a--waiting { opacity: 0.7; font-style: italic; }
    &__link { font-size: 12px; margin: 2px 0 0 4px; opacity: 0.85; overflow-wrap: anywhere; }
    &__meta { font-size: 11px; opacity: 0.7; margin-top: 2px; }
}
</style>
