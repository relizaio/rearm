<template>
    <div v-if="task.assignment" class="dsec">
        <div class="dsec__h">Current assignment</div>
        <div class="hist">
            <div class="hist__row hist__row--active">
                <span class="hist__role">{{ task.assignment.role }}</span>
                <span class="hist__agent"><session-ref v-if="task.assignment.session"
                    :session="sessionOf(task.assignment.session, task.assignment.agent, agentDir, null)"/><template
                    v-else>{{ agentName(agentNames, task.assignment.agent) }}</template></span>
                <span class="hist__time">since {{ ts(task.assignment.assignedAt) }}
                    ({{ dur(task.assignment.assignedAt, null) }})</span>
                <code v-if="task.assignment.promptVersion" class="hist__pv"
                      title="Served role-prompt version">{{ task.assignment.promptVersion }}</code>
            </div>
        </div>
    </div>
</template>

<script lang="ts" setup>
import SessionRef from '../SessionRef.vue'
import { agentName, dur, ts } from '@/utils/agentTaskFormat'
import { AgentName, sessionOf } from '@/utils/agentSessionLabel'

defineProps<{ task: any, agentNames: Record<string, string>, agentDir?: Record<string, AgentName> }>()
</script>

<style scoped lang="scss">
@use './taskSections';
</style>
