<template>
    <n-drawer :show="task !== null" :width="560" placement="right"
              @update:show="(v: boolean) => { if (!v) emit('close') }">
        <n-drawer-content v-if="task" closable>
            <template #header>
                <task-title :task="task" :board="board" :roles="roles" clamp/>
            </template>

            <!-- A preview (gaps §1.26): what a person needs to decide whether to open the task,
                 and the verbs that must stay one click away -- a gate verdict, a release, the
                 task actions. Review item rounds, questions, documents and history are on the page. -->
            <div class="tsecs">
                <router-link :to="taskPagePath(task.uuid)" class="openpage">Open task page →</router-link>
                <!-- The board holds its tasks card-level: the sections wait for the task's whole read. -->
                <div v-if="loading" class="tsecs__loading" data-testid="drawer-loading">
                    <n-spin size="small"/><span>Loading the task…</span>
                </div>
                <n-alert v-else-if="loadError" type="warning" :bordered="false" data-testid="drawer-load-error">{{ loadError }}</n-alert>
                <template v-else>
                    <task-header :task="task" :tasks="tasks" :roles="roles" :priority-levels="priorityLevels" questions-on-page
                                 :can-operate="canOperate(board)" :board="board"
                                 @human-review="p => emit('human-review', p)"
                                 @human-signoff="p => emit('human-signoff', p)"
                                 @lift-hold="p => emit('lift-hold', p)"
                                 @require-review="p => emit('require-review', p)"/>
                    <!-- What the task waits on, for every reader (RD2-7). -->
                    <task-open-questions :task="task" :roles="roles"/>
                    <task-actions v-if="canOperate(board)" ref="actions" :task="task" :roles="roles" :board="board" :can-reopen="canReopen" :admin="canReopen"
                                  @authorize="p => emit('authorize', p)" @order="p => emit('order', p)"
                                  @complete="p => emit('complete', p)" @cancel="p => emit('cancel', p)"
                                  @reopen="p => emit('reopen', p)" @decide="p => emit('decide', p)"
                                  @set-strength="p => emit('set-strength', p)" @operator-hold="p => emit('operator-hold', p)"
                                  @unassign="p => emit('unassign', p)"
                                  @set-work-level="p => emit('set-work-level', p)"
                                  @set-group="p => emit('set-group', p)" @set-tags="p => emit('set-tags', p)"
                                  @declare-delivery="p => emit('declare-delivery', p)"
                                  @set-budget="p => emit('set-budget', p)"/>
                    <task-summary :task="task" :tasks="tasks" :roles="roles" :agent-names="agentNames" :board="board"
                                  @open="t => emit('open', t)" @focus-unassign="actions?.focusUnassign()"/>
                </template>
            </div>
        </n-drawer-content>
    </n-drawer>
</template>

<script lang="ts" setup>
import { ref } from 'vue'
import { RouterLink } from 'vue-router'
import { NAlert, NDrawer, NDrawerContent, NSpin } from 'naive-ui'
import TaskActions from './task/TaskActions.vue'
import TaskHeader from './task/TaskHeader.vue'
import TaskOpenQuestions from './task/TaskOpenQuestions.vue'
import TaskSummary from './task/TaskSummary.vue'
import TaskTitle from './task/TaskTitle.vue'
import { taskPagePath } from '@/utils/agentTaskFormat'
import { canOperate } from '@/utils/agentBoardAccess'

// The task actions, for the summary's stale line to focus the Unassign reason (TPF-69); a ref, not a
// query, because n-drawer teleports its content out of this component's root.
const actions = ref<InstanceType<typeof TaskActions> | null>(null)

defineProps<{
    task: any | null
    tasks: any[]
    agentNames: Record<string, string>
    roles?: any[]
    board?: any
    priorityLevels?: number
    /** Org admin: may reopen a completed task (the server's rule for agentTaskReopen). */
    canReopen?: boolean
    /** The task's whole read is on its way: the card's fields only so far, so a spinner in place of the sections. */
    loading?: boolean
    /** The whole read failed: said in place of the sections. */
    loadError?: string | null
}>()
const emit = defineEmits<{
    (e: 'close'): void
    (e: 'open', task: any): void
    (e: 'human-review', p: { task: any, accept: boolean, note: string, reviewItems?: any[],
        about?: { specification: string } | null }): void
    (e: 'human-signoff', p: { task: any, outcome: string, note: string }): void
    (e: 'lift-hold', p: { task: any, note: string, role?: string }): void
    (e: 'require-review', p: { task: any, value: boolean }): void
    (e: 'authorize', p: { task: any, role: string, orderIndex?: number | null }): void
    (e: 'order', p: { task: any, orderIndex: number }): void
    (e: 'set-budget', p: { task: any, budgetMicros: number | null }): void
    (e: 'complete', p: { task: any, note: string, skipRequiredRoles: boolean }): void
    (e: 'cancel', p: { task: any, note: string }): void
    (e: 'reopen', p: { task: any, role: string, reason: string }): void
    (e: 'decide', p: { task: any, specification: string, decisions: any[],
        about?: { specification: string } | null }): void
    (e: 'set-strength', p: { task: any, requiredStrength: number | null }): void
    (e: 'set-work-level', p: { task: any, workLevel: number | null }): void
    (e: 'set-group', p: { task: any, group: string | null }): void
    (e: 'set-tags', p: { task: any, tags: { key: string, value?: string | null }[] }): void
    (e: 'declare-delivery', p: { task: any, unit: string, commit: string | null, outcome: string, note: string | null }): void
    (e: 'operator-hold', p: { task: any, reason: string }): void
    (e: 'unassign', p: { task: any, reason: string }): void
}>()
</script>

<style scoped lang="scss">
.tsecs { display: flex; flex-direction: column; gap: 16px; }
.openpage { align-self: flex-start; font-weight: 600; font-size: 13px; }
.tsecs__loading { display: flex; align-items: center; gap: 8px; color: #888; font-size: 13px; }
</style>
