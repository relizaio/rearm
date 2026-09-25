<template>
    <n-drawer :show="task !== null" :width="560" placement="right"
              @update:show="(v: boolean) => { if (!v) emit('close') }">
        <n-drawer-content v-if="task" closable>
            <template #header>
                <task-title :task="task" :board="board"/>
            </template>

            <!-- A preview (gaps §1.26): what a person needs to decide whether to open the task,
                 and the verbs that must stay one click away -- a gate verdict, a release, the
                 task actions. Findings rounds, questions, documents and history are on the page. -->
            <div class="tsecs">
                <router-link :to="taskPagePath(task.uuid)" class="openpage">Open task page →</router-link>
                <task-header :task="task" :tasks="tasks" :roles="roles" :priority-levels="priorityLevels" questions-on-page
                             @human-review="p => emit('human-review', p)"
                             @human-signoff="p => emit('human-signoff', p)"
                             @operator-release="p => emit('operator-release', p)"
                             @require-review="p => emit('require-review', p)"/>
                <task-actions :task="task" :roles="roles" :board="board" :can-reopen="canReopen" :admin="canReopen"
                              @authorize="p => emit('authorize', p)" @order="p => emit('order', p)"
                              @complete="p => emit('complete', p)" @cancel="p => emit('cancel', p)"
                              @reopen="p => emit('reopen', p)" @decide="p => emit('decide', p)"
                              @set-strength="p => emit('set-strength', p)" @operator-hold="p => emit('operator-hold', p)"/>
                <task-summary :task="task" :tasks="tasks" :roles="roles" :agent-names="agentNames"
                              @open="t => emit('open', t)"/>
            </div>
        </n-drawer-content>
    </n-drawer>
</template>

<script lang="ts" setup>
import { RouterLink } from 'vue-router'
import { NDrawer, NDrawerContent } from 'naive-ui'
import TaskActions from './task/TaskActions.vue'
import TaskHeader from './task/TaskHeader.vue'
import TaskSummary from './task/TaskSummary.vue'
import TaskTitle from './task/TaskTitle.vue'
import { taskPagePath } from '@/utils/agentTaskFormat'

defineProps<{
    task: any | null
    tasks: any[]
    agentNames: Record<string, string>
    roles?: any[]
    board?: any
    priorityLevels?: number
    /** Org admin: may reopen a completed task (the server's rule for agentTaskReopen). */
    canReopen?: boolean
}>()
const emit = defineEmits<{
    (e: 'close'): void
    (e: 'open', task: any): void
    (e: 'human-review', p: { task: any, approve: boolean, note: string, findings?: any[],
        about?: { specification: string } | null }): void
    (e: 'human-signoff', p: { task: any, outcome: string, note: string }): void
    (e: 'operator-release', p: { task: any, note: string }): void
    (e: 'require-review', p: { task: any, value: boolean }): void
    (e: 'authorize', p: { task: any, role: string, orderIndex?: number | null }): void
    (e: 'order', p: { task: any, orderIndex: number }): void
    (e: 'complete', p: { task: any, note: string, skipRequiredRoles: boolean }): void
    (e: 'cancel', p: { task: any, note: string }): void
    (e: 'reopen', p: { task: any, role: string, reason: string }): void
    (e: 'decide', p: { task: any, specification: string, decisions: any[],
        about?: { specification: string } | null }): void
    (e: 'set-strength', p: { task: any, requiredStrength: number | null }): void
    (e: 'operator-hold', p: { task: any, reason: string }): void
}>()
</script>

<style scoped lang="scss">
.tsecs { display: flex; flex-direction: column; gap: 16px; }
.openpage { align-self: flex-start; font-weight: 600; font-size: 13px; }
</style>
