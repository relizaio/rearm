<template>
    <div class="aiAgentTaskPage">
        <n-breadcrumb separator="›" class="crumbs">
            <n-breadcrumb-item @click="openBoards">AI Agents</n-breadcrumb-item>
            <n-breadcrumb-item v-if="board" @click="openBoard">{{ board.name }}</n-breadcrumb-item>
            <n-breadcrumb-item>{{ task ? taskLabel(task) : 'Task' }}</n-breadcrumb-item>
        </n-breadcrumb>

        <n-spin v-if="loading && !task" size="small"/>
        <n-alert v-else-if="loadError" type="error" :title="loadError"/>

        <template v-if="task">
            <task-title :task="task" :board="board"/>

            <!-- Two columns above 1200px: the record on the left (what was found, asked, produced
                 and done), the person's controls and the task's place on the board on the right.
                 One column below, controls first. -->
            <div class="tpage">
                <div class="tpage__main tsecs">
                    <task-findings :task="task" :roles="roles" :priority-levels="priorityLevels"
                                   @decide="decideFindings" @open-element="openElement"/>
                    <task-open-questions :task="task"/>
                    <task-questions :task="task" :roles="roles" @answer="answerQuestions"/>
                    <task-documents :task="task" :focus="elementFocus"/>
                    <task-hops :task="task" :agent-names="agentNames"/>
                    <task-history :task="task"/>
                    <AiAgentRevisionHistory v-if="canReadHistory && task.uuid" kind="task" :uuid="task.uuid" :current="task"/>
                </div>
                <div class="tpage__side tsecs">
                    <task-header :task="task" :tasks="tasks" :roles="roles" :priority-levels="priorityLevels"
                                 @human-review="humanReview" @human-signoff="humanSignOff"
                                 @operator-release="operatorRelease" @require-review="requireReview"/>
                    <task-actions :task="task" :roles="roles" :board="board" :can-reopen="canReopen"
                                  @authorize="authorizeTask" @order="orderTask" @complete="completeTask"
                                  @cancel="cancelTask" @reopen="reopenTask" @decide="decideFindings"/>
                    <task-dependencies :task="task" :tasks="tasks" @open="openTask"/>
                    <task-assignment :task="task" :agent-names="agentNames"/>
                    <task-usage :task="task"/>
                    <task-pull-requests :task="task"/>
                </div>
            </div>
        </template>
    </div>
</template>

<script lang="ts" setup>
// A page per board task (gaps §1.26): everything the drawer used to carry, laid out for reading.
// It loads from its uuid alone -- the task, then its board, the board's tasks and roles -- so a
// deep link works without the board panel having been open.
import { computed, ref, watch } from 'vue'
import { useStore } from 'vuex'
import { useRoute, useRouter } from 'vue-router'
import { NAlert, NBreadcrumb, NBreadcrumbItem, NSpin } from 'naive-ui'
import TaskActions from './task/TaskActions.vue'
import TaskAssignment from './task/TaskAssignment.vue'
import TaskDependencies from './task/TaskDependencies.vue'
import AiAgentRevisionHistory from './AiAgentRevisionHistory.vue'
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
import { isOrgAdmin } from '@/utils/agentReopen'
import { useAgentTaskActions } from '@/utils/agentTaskActions'
import { taskLabel, taskPagePath } from '@/utils/agentTaskFormat'

const store = useStore()
const route = useRoute()
const router = useRouter()

const taskUuid = computed(() => route.params.uuid as string)
const task = ref<any>(null)
const board = ref<any>(null)
const tasks = ref<any[]>([])
const roles = ref<any[]>([])
const agentNames = ref<Record<string, string>>({})
const loading = ref(false)
const loadError = ref<string | null>(null)

const priorityLevels = computed<number>(() =>
    store.getters?.orgById?.(task.value?.org)?.settings?.findingPriorityLevels ?? 3)
const canReopen = computed<boolean>(() =>
    !!task.value?.org && isOrgAdmin(store.getters?.myuser?.permissions?.permissions, task.value.org))
// The revisions read (agentTaskHistory) is org admin too (22ddc644).
const canReadHistory = canReopen

async function load () {
    loading.value = true
    loadError.value = null
    try {
        const t = await store.dispatch('fetchAgentTask', taskUuid.value)
        if (!t) {
            task.value = null
            loadError.value = 'Task not found'
            return
        }
        // The board's reads are secondary: a page that shows the task without its neighbours is
        // still the page, so one of them failing leaves that part empty rather than the page.
        const [b, ts, rs, agents] = await Promise.all([
            store.dispatch('fetchAgentBoard', t.board).catch(() => null),
            store.dispatch('fetchAgentTasksOfBoard', { boardUuid: t.board }).catch(() => []),
            store.dispatch('fetchAgentTaskRoleConfigsOfBoard', t.board).catch(() => []),
            store.dispatch('fetchAgentsOfOrg', t.org).catch(() => []),
        ])
        task.value = t
        board.value = b
        tasks.value = ts ?? []
        roles.value = rs ?? []
        const names: Record<string, string> = {}
        for (const a of agents ?? []) names[a.uuid] = a.effectiveDisplayName || a.name || a.uuid.slice(0, 8)
        agentNames.value = names
    } catch (e: any) {
        task.value = null
        loadError.value = `Could not load the task: ${e?.message ?? e}`
    } finally {
        loading.value = false
    }
}

watch(taskUuid, load, { immediate: true })

// The page stays on its task after any action, including a verdict that hands the task on.
const {
    humanReview, humanSignOff, operatorRelease, answerQuestions, authorizeTask, orderTask,
    completeTask, cancelTask, reopenTask, decideFindings, requireReview,
} = useAgentTaskActions(async () => { await load() })

// A finding's element chip opens the element under its document (elements.md §8).
const elementFocus = ref<{ id: string, n: number } | null>(null)
function openElement (id: string) {
    if (id) elementFocus.value = { id, n: (elementFocus.value?.n ?? 0) + 1 }
}

function openTask (t: any) {
    router.push(taskPagePath(t.uuid))
}

function openBoards () {
    if (task.value?.org) router.push({ name: 'AiAgentsOfOrg', params: { orguuid: task.value.org }, query: { tab: 'boards' } })
    else router.back()
}

function openBoard () {
    router.push({ name: 'AiAgentsOfOrg', params: { orguuid: task.value.org },
        query: { tab: 'boards', board: task.value.board } })
}
</script>

<style scoped lang="scss">
.aiAgentTaskPage { padding: 12px 20px 40px; }
.crumbs { margin-bottom: 12px; }
.tsecs { display: flex; flex-direction: column; gap: 16px; min-width: 0; }
.tpage {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(360px, 440px);
    gap: 28px;
    margin-top: 16px;
}
@media (max-width: 1199px) {
    .tpage { grid-template-columns: minmax(0, 1fr); }
    .tpage__side { order: -1; }
}
</style>
