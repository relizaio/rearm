<template>
    <n-drawer :show="task !== null" :width="560" placement="right"
              @update:show="(v: boolean) => { if (!v) emit('close') }">
        <n-drawer-content v-if="task" closable>
            <template #header>
                <task-title :task="task"/>
            </template>

            <!-- The sections are the task page's components (components/task/*), so a verb offered
                 in both places is implemented once. -->
            <div class="tsecs">
                <task-header :task="task" :roles="roles" :priority-levels="priorityLevels"
                             @human-review="p => emit('human-review', p)"
                             @human-signoff="p => emit('human-signoff', p)"
                             @operator-release="p => emit('operator-release', p)"
                             @require-review="p => emit('require-review', p)"/>
                <task-actions :task="task" :roles="roles" :board="board" :can-reopen="canReopen"
                              @authorize="p => emit('authorize', p)" @order="p => emit('order', p)"
                              @complete="p => emit('complete', p)" @cancel="p => emit('cancel', p)"
                              @reopen="p => emit('reopen', p)" @decide="p => emit('decide', p)"/>
                <task-dependencies :task="task" :tasks="tasks" @open="t => emit('open', t)"/>
                <task-assignment :task="task" :agent-names="agentNames"/>
                <task-usage :task="task"/>
                <task-findings :task="task" :roles="roles" :priority-levels="priorityLevels"
                               @decide="p => emit('decide', p)"/>
                <task-open-questions :task="task"/>
                <task-documents :task="task"/>
                <task-hops :task="task" :agent-names="agentNames"/>
                <task-pull-requests :task="task"/>
                <task-questions :task="task" :roles="roles" @answer="p => emit('answer', p)"/>
                <task-history :task="task"/>
            </div>
        </n-drawer-content>
    </n-drawer>
</template>

<script lang="ts" setup>
import { NDrawer, NDrawerContent } from 'naive-ui'
import TaskActions from './task/TaskActions.vue'
import TaskAssignment from './task/TaskAssignment.vue'
import TaskDependencies from './task/TaskDependencies.vue'
import TaskDocuments from './task/TaskDocuments.vue'
import TaskFindings from './task/TaskFindings.vue'
import TaskHeader from './task/TaskHeader.vue'
import TaskHistory from './task/TaskHistory.vue'
import TaskHops from './task/TaskHops.vue'
import TaskOpenQuestions from './task/TaskOpenQuestions.vue'
import TaskPullRequests from './task/TaskPullRequests.vue'
import TaskQuestions from './task/TaskQuestions.vue'
import TaskTitle from './task/TaskTitle.vue'
import TaskUsage from './task/TaskUsage.vue'

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
    (e: 'answer', p: { task: any, answers: { id: string, status: string, resolution: string }[],
        answerAll?: string }): void
    (e: 'authorize', p: { task: any, role: string, orderIndex?: number | null }): void
    (e: 'order', p: { task: any, orderIndex: number }): void
    (e: 'complete', p: { task: any, note: string, skipRequiredRoles: boolean }): void
    (e: 'cancel', p: { task: any, note: string }): void
    (e: 'reopen', p: { task: any, role: string, reason: string }): void
    (e: 'decide', p: { task: any, specification: string, decisions: any[],
        about?: { specification: string } | null }): void
}>()
</script>

<style scoped lang="scss">
// The vertical rhythm n-space gave the drawer's sections, kept now that each component renders
// its own sections as siblings.
.tsecs { display: flex; flex-direction: column; gap: 16px; }
</style>
