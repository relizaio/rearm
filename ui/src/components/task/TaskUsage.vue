<template>
    <div class="dsec" v-if="(task.usage?.reports ?? 0) > 0">
        <div class="dsec__h">Usage</div>
        <agent-usage-summary :usage="task.usage" :show-by-model="false" />
        <n-tag v-if="task.budgetMicros != null" size="small" :bordered="false" class="budtag"
               :type="budgetChip(task.usage?.derivedCostMicros, task.budgetMicros).type">
            {{ budgetChip(task.usage?.derivedCostMicros, task.budgetMicros).label }} of this task's budget
        </n-tag>
    </div>
</template>

<script lang="ts" setup>
import { NTag } from 'naive-ui'
import AgentUsageSummary from '../AgentUsageSummary.vue'
import { budgetChip } from '@/utils/agentBudget'

defineProps<{ task: any }>()
</script>

<style scoped lang="scss">
@use './taskSections';

.budtag {
    margin-top: 6px;
}
</style>
