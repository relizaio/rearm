<template>
    <!-- The questions the task was asked and is no longer waiting on (RD2-7): what was asked, the
         answer, what answered it and when. Read from the questions rounds, the record. -->
    <div v-if="answered.length" class="dsec" data-testid="answered-questions">
        <div class="dsec__h">Answered questions ({{ answered.length }})</div>
        <div v-for="q in shown" :key="q.askedIn.release + '/' + q.id" class="aq" :data-id="q.id">
            <div class="aq__q">
                <code class="aq__id">{{ q.id }}</code>
                <span class="aq__title">{{ q.title }}</span>
                <n-tag v-if="q.withdrawn" size="tiny" :bordered="false" data-testid="answered-withdrawn">withdrawn</n-tag>
            </div>
            <div v-if="q.answer" class="aq__a">{{ q.answer }}</div>
            <div class="aq__meta">
                {{ askedByLabel(q.askedIn) }}<template v-if="q.answeredBy"> · {{ answeredByLabel(q.answeredBy) }}</template><template
                    v-else-if="q.withdrawn"> · does not apply</template><template v-if="q.answeredAt"> · <agent-time :at="q.answeredAt"/></template>
            </div>
        </div>
        <n-button v-if="answered.length > ANSWERED_SHOWN" size="tiny" quaternary data-testid="answered-toggle"
                  @click="all = !all">{{ all ? 'show fewer' : `show all ${answered.length}` }}</n-button>
    </div>
</template>

<script lang="ts" setup>
import AgentTime from '../AgentTime.vue'
import { computed, ref } from 'vue'
import { NButton, NTag } from 'naive-ui'
import { ANSWERED_SHOWN, answeredByLabel, answeredQuestions, askedByLabel } from '@/utils/agentQuestionRounds'

const props = defineProps<{ task: any, roles?: any[] }>()

const all = ref(false)
const answered = computed(() => answeredQuestions(props.task, props.roles))
const shown = computed(() => all.value ? answered.value : answered.value.slice(0, ANSWERED_SHOWN))
</script>

<style scoped lang="scss">
@use './taskSections';

.aq {
    padding: 4px 0;
    border-top: 1px solid rgba(128, 128, 128, 0.15);
    &__q { display: flex; gap: 6px; align-items: baseline; }
    &__id { font-weight: 600; }
    &__a { font-size: 13px; margin: 2px 0 0 4px; white-space: pre-wrap; overflow-wrap: anywhere; }
    &__meta { font-size: 11px; opacity: 0.7; margin-top: 2px; }
}
</style>
