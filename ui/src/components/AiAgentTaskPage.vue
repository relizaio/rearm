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
            <task-title :task="task" :board="board" :roles="roles">
                <!-- The whole task as JSON, timeline included (statusHistory, rounds, returns, reopens). -->
                <n-tooltip>
                    <template #trigger>
                        <n-button size="tiny" quaternary circle data-testid="task-export" aria-label="Export task as JSON"
                                  :loading="exporting" :disabled="exporting" @click="exportTask">
                            <template #icon><n-icon><Download/></n-icon></template>
                        </n-button>
                    </template>
                    Export task as JSON
                </n-tooltip>
            </task-title>
            <n-alert v-if="exportError" type="error" :title="exportError" closable @close="exportError = null"/>

            <!-- The task in its first tab; its revisions in their own, read only once that tab opens
                 (show:lazy mounts the pane on its first show and keeps it; keyed by the task, so
                 another task's page starts unread). -->
            <n-tabs v-model:value="tab" type="line" class="ttabs" data-testid="task-tabs">
                <n-tab-pane name="task" tab="Task">
                    <!-- Two columns above 1200px: the record on the left (what was found, asked, produced
                         and done), the person's controls and the task's place on the board on the right.
                         One column below, controls first. -->
                    <div class="tpage">
                        <div class="tpage__main tsecs">
                            <task-investigation :task="task" :tasks="tasks"/>
                            <task-review-items :task="task" :roles="roles" :priority-levels="priorityLevels" :board="board"
                                           @decide="decideReviewItems" @open-element="openElement"/>
                            <task-open-questions :task="task" :roles="roles"/>
                            <task-questions v-if="canReopen" :task="task" :roles="roles" @answer="answerQuestions"/>
                            <task-answered-questions :task="task" :roles="roles"/>
                            <task-operator-questions :task="task"/>
                            <task-documents :task="task" :focus="elementFocus" :can-rerun="canReopen"/>
                            <task-hops :task="task" :agent-names="agentNames" :agent-dir="agentDir"/>
                            <task-history :task="task"/>
                        </div>
                        <div class="tpage__side tsecs">
                            <task-header :task="task" :tasks="tasks" :roles="roles" :priority-levels="priorityLevels"
                                         :can-operate="canReopen" :board="board"
                                         @human-review="humanReview" @human-signoff="humanSignOff"
                                         @lift-hold="liftHold" @require-review="requireReview"/>
                            <task-actions v-if="canReopen" :task="task" :roles="roles" :board="board" :can-reopen="canReopen" :admin="canReopen"
                                          @authorize="authorizeTask" @order="orderTask" @complete="completeTask"
                                          @cancel="cancelTask" @reopen="reopenTask" @decide="decideReviewItems"
                                          @set-strength="setStrength" @operator-hold="operatorHold" @set-budget="setBudget"
                                          @unassign="unassign"
                                          @set-work-level="setWorkLevel" @set-group="setGroup" @set-tags="setTags" @declare-delivery="declareDelivery"/>
                            <task-commission v-if="canReopen" :task="task" :board="board" :roles="roles" @commission="commission"/>
                            <task-dependencies :task="task" :tasks="tasks" @open="openTask"/>
                            <task-stale :task="task"/>
                            <task-assignment :task="task" :agent-names="agentNames" :agent-dir="agentDir"/>
                            <task-usage :task="task" :board="board"/>
                            <task-pull-requests :task="task" :can-operate="canReopen" @declare-delivery="declareDelivery" @supersede="supersedePr"
                                                @unlink="unlinkPr"/>
                        </div>
                    </div>
                </n-tab-pane>
                <n-tab-pane v-if="canReadHistory && task.uuid" :key="task.uuid" name="revisions" tab="Revisions"
                            display-directive="show:lazy">
                    <AiAgentRevisionHistory kind="task" :uuid="task.uuid" :current="task" open/>
                </n-tab-pane>
            </n-tabs>
        </template>
    </div>
</template>

<script lang="ts" setup>
// A page per board task (gaps §1.26): everything the drawer used to carry, laid out for reading.
// It loads from its uuid alone -- the task, then its board, the board's tasks and roles -- so a
// deep link works without the board panel having been open.
import { computed, provide, ref, watch } from 'vue'
import { useStore } from 'vuex'
import { useRoute, useRouter } from 'vue-router'
import { NAlert, NBreadcrumb, NBreadcrumbItem, NButton, NIcon, NSpin, NTabPane, NTabs, NTooltip } from 'naive-ui'
import { Download } from '@vicons/tabler'
import TaskActions from './task/TaskActions.vue'
import TaskAssignment from './task/TaskAssignment.vue'
import TaskCommission from './task/TaskCommission.vue'
import TaskInvestigation from './task/TaskInvestigation.vue'
import TaskDependencies from './task/TaskDependencies.vue'
import AiAgentRevisionHistory from './AiAgentRevisionHistory.vue'
import TaskDocuments from './task/TaskDocuments.vue'
import TaskReviewItems from './task/TaskReviewItems.vue'
import TaskHeader from './task/TaskHeader.vue'
import TaskHistory from './task/TaskHistory.vue'
import TaskHops from './task/TaskHops.vue'
import TaskOperatorQuestions from './task/TaskOperatorQuestions.vue'
import TaskOpenQuestions from './task/TaskOpenQuestions.vue'
import TaskPullRequests from './task/TaskPullRequests.vue'
import TaskQuestions from './task/TaskQuestions.vue'
import TaskStale from './task/TaskStale.vue'
import TaskAnsweredQuestions from './task/TaskAnsweredQuestions.vue'
import TaskTitle from './task/TaskTitle.vue'
import TaskUsage from './task/TaskUsage.vue'
import { boardCan, canOperate } from '@/utils/agentBoardAccess'
import { AGENT_DIR, AgentName, COORDINATOR_SEAT, agentDirectory, agentNamesOf } from '@/utils/agentSessionLabel'
import { taskLoadErrorText } from '@/utils/agentAccessMessages'
import { useAgentTaskActions } from '@/utils/agentTaskActions'
import { taskLabel, taskPagePath } from '@/utils/agentTaskFormat'
import { downloadJson, taskExportDocument, taskExportFileName } from '@/utils/agentTaskExport'

const store = useStore()
const route = useRoute()
const router = useRouter()

const taskUuid = computed(() => route.params.uuid as string)
const task = ref<any>(null)
const board = ref<any>(null)
const tasks = ref<any[]>([])
const roles = ref<any[]>([])
const agentNames = ref<Record<string, string>>({})
// Agents by uuid with their own name apart from the key's note, for naming sessions (RD2-11).
const agentDir = ref<Record<string, AgentName>>({})
provide(AGENT_DIR, agentDir)
provide(COORDINATOR_SEAT, computed(() => board.value?.coordinatorSeat ?? null))
const loading = ref(false)
const loadError = ref<string | null>(null)

const priorityLevels = computed<number>(() =>
    store.getters?.orgById?.(task.value?.org)?.settings?.reviewItemPriorityLevels ?? 3)
// The operator verbs, reopen included, need BOARD_WRITE on the task's board (task d8e7bd7e).
const canReopen = computed<boolean>(() => canOperate(board.value))
// The revisions read (agentTaskHistory) reads with the board.
const canReadHistory = computed<boolean>(() => boardCan(board.value, 'BOARD_READ'))
// The page's tab: the task, or its revisions. Back to the task when the page moves to another task.
const tab = ref<'task' | 'revisions'>('task')

// The JSON export reads the task afresh with every field (exportAgentTask), not the page's copy, so
// the file is the whole task as it is now.
const exporting = ref(false)
const exportError = ref<string | null>(null)
async function exportTask () {
    if (!task.value?.uuid || exporting.value) return
    exporting.value = true
    exportError.value = null
    try {
        const full = await store.dispatch('exportAgentTask', task.value.uuid)
        if (!full) throw new Error('Task not found')
        downloadJson(taskExportFileName(full), taskExportDocument(full))
    } catch (e: any) {
        exportError.value = 'Export failed: ' + (e?.message ?? String(e))
    } finally {
        exporting.value = false
    }
}

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
        // The board's other tasks are read at card level, as the kanban reads them: the page's
        // consumers read only their key, title, status and links (dependsOn, parentTask, childTasks).
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
        agentDir.value = agentDirectory(agents)
        agentNames.value = agentNamesOf(agents)
    } catch (e: any) {
        task.value = null
        // A refusal says what it needs (RD2-9); any other error keeps the server's words.
        loadError.value = taskLoadErrorText(e)
    } finally {
        loading.value = false
    }
}

watch(taskUuid, () => {
    tab.value = 'task'
    return load()
}, { immediate: true })

// The page stays on its task after any action, including a verdict that hands the task on.
const {
    humanReview, humanSignOff, liftHold, answerQuestions, authorizeTask, orderTask,
    completeTask, cancelTask, reopenTask, decideReviewItems, requireReview, setStrength, operatorHold, setBudget, setWorkLevel,
    setGroup, setTags, declareDelivery, unassign, supersedePr, unlinkPr, commission,
} = useAgentTaskActions(async () => { await load() })

// A review item's element chip opens the element under its document (elements.md §8).
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
.ttabs { margin-top: 8px; }
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
