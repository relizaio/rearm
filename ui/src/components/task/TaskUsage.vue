<template>
    <div class="dsec" v-if="taskSpendVisible(task) || (task.usage?.reports ?? 0) > 0">
        <div class="dsec__h">Usage</div>
        <!-- What the board charges the task (task 02bfab7c): its rows plus its coordinator share,
             the figure its budget is held to. The usage summary below is its own rows only. -->
        <n-space :size="6" align="center" class="spend">
            <n-tooltip trigger="hover">
                <template #trigger>
                    <n-tag size="small" :bordered="false" class="spendtag">{{ taskSpendLabel(task).label }}</n-tag>
                </template>
                {{ taskSpendLabel(task).title }}
            </n-tooltip>
            <n-tag v-if="task.budgetMicros != null" size="small" :bordered="false" class="budtag"
                   :type="budgetChip(taskSpentMicros(task), task.budgetMicros, board?.softAlertPercent).type">
                {{ budgetChip(taskSpentMicros(task), task.budgetMicros, board?.softAlertPercent).label }} of this task's budget
            </n-tag>
        </n-space>
        <agent-usage-summary v-if="(task.usage?.reports ?? 0) > 0" :usage="task.usage" :show-by-model="false" />
    </div>
</template>

<script lang="ts" setup>
import { NSpace, NTag, NTooltip } from 'naive-ui'
import AgentUsageSummary from '../AgentUsageSummary.vue'
import { budgetChip } from '@/utils/agentBudget'
import { taskSpendLabel, taskSpendVisible, taskSpentMicros } from '@/utils/agentUsage'

defineProps<{ task: any, board?: any }>()
</script>

<style scoped lang="scss">
@use './taskSections';

.spend {
    margin-bottom: 6px;
}
</style>
