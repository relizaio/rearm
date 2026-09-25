<template>
    <div v-if="task.assignment" class="dsec">
        <div class="dsec__h">Current assignment</div>
        <div class="hist">
            <div class="hist__row hist__row--active">
                <span class="hist__role">{{ task.assignment.role }}</span>
                <span class="hist__agent">{{ agentName(agentNames, task.assignment.agent) }}</span>
                <span class="hist__time">since {{ ts(task.assignment.assignedAt) }}
                    ({{ dur(task.assignment.assignedAt, null) }})</span>
                <code v-if="task.assignment.promptVersion" class="hist__pv"
                      title="Served role-prompt version">{{ task.assignment.promptVersion }}</code>
            </div>
        </div>
    </div>
</template>

<script lang="ts" setup>
import { agentName, dur, ts } from '@/utils/agentTaskFormat'

defineProps<{ task: any, agentNames: Record<string, string> }>()
</script>

<style scoped lang="scss">
@use './taskSections';
</style>
