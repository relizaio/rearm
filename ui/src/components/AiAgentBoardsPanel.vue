<template>
    <div class="boardsPanel">
        <div class="section-head">
            <h5>Task boards</h5>
            <n-space :size="8">
                <n-select
                    v-if="boards.length"
                    v-model:value="selectedBoard"
                    :options="boardOptions"
                    size="small"
                    style="min-width: 220px"
                />
                <n-button size="small" quaternary @click="startEditBoard(currentBoard)" v-if="currentBoard">Edit board</n-button>
                <n-button size="small" quaternary @click="showRoles = true" v-if="currentBoard">Roles</n-button>
                <n-button size="small" quaternary @click="openPresets">Org presets</n-button>
                <n-button size="small" quaternary @click="startEditBoard(null)">+ New board</n-button>
            </n-space>
        </div>

        <div v-if="!boards.length" class="empty">
            No boards yet. A board wires tracker repos to a role pipeline governed by its coordinator.
        </div>

        <template v-if="currentBoard">
            <!-- Lock banner + operator lock controls -->
            <n-alert
                v-if="isLocked"
                :type="currentBoard.lock.level === 'OPERATOR' ? 'error' : 'warning'"
                class="lockbanner"
            >
                Board locked ({{ currentBoard.lock.level }}) — no new assignments.
                <template v-if="currentBoard.lock.reason"> Reason: {{ currentBoard.lock.reason }}.</template>
                <n-button
                    v-if="currentBoard.lock.level === 'OPERATOR'"
                    size="tiny" style="margin-left: 10px"
                    @click="operatorLock(false)"
                >Operator unlock</n-button>
            </n-alert>
            <div class="boardmeta">
                <span v-for="s in currentBoard.sources ?? []" :key="s" class="srcchip">{{ s }}</span>
                <n-tag size="tiny" :bordered="false" :type="currentBoard.coordinatorSeat ? 'success' : 'default'">
                    {{ currentBoard.coordinatorSeat ? 'coordinator connected' : 'no coordinator' }}
                </n-tag>
                <n-button v-if="!isLocked" size="tiny" quaternary @click="operatorLock(true)">Operator lock</n-button>
            </div>
            <n-alert v-if="currentBoard.missingCapabilities?.length" type="warning" class="lockbanner">
                Delivery loop incomplete: no active role covers
                {{ currentBoard.missingCapabilities.join(', ') }} — this board cannot ship until a role carries them.
            </n-alert>
            <n-collapse v-if="currentBoard.events?.length" class="eventsfeed">
                <n-collapse-item :title="`Board events (${currentBoard.events.length})`" name="ev">
                    <div v-for="(e, i) in [...currentBoard.events].reverse()" :key="i" class="evrow">
                        <n-tag size="tiny" :bordered="false"
                               :type="e.kind === 'ALERT' ? 'error' : e.kind === 'LOCKED' ? 'warning' : 'default'">{{ e.kind }}</n-tag>
                        <span class="evmsg">{{ e.message }}</span>
                        <span class="evmeta">{{ e.actor }} · {{ formatEventTime(e.eventAt) }}</span>
                    </div>
                </n-collapse-item>
            </n-collapse>

            <n-tabs type="segment" size="small" class="viewtabs"
                    :value="boardView" @update:value="setBoardView">
            <n-tab-pane name="kanban" tab="Kanban">
            <!-- Hub-and-spoke kanban: intake / per-role / awaiting coordinator / done -->
            <div class="board">
                <div class="col">
                    <div class="col__head">Pending intake</div>
                    <TaskCard v-for="t in byStatus('PENDING_INTAKE')" :key="t.uuid" :t="t"/>
                </div>
                <div class="col" v-for="r in activeRoles" :key="r.name">
                    <div class="col__head">{{ r.name }}<span v-if="r.wipLimit" class="col__wip">wip ≤ {{ r.wipLimit }}</span></div>
                    <TaskCard v-for="t in atRole(r.name)" :key="t.uuid" :t="t"/>
                </div>
                <div class="col">
                    <div class="col__head">Awaiting coordinator</div>
                    <TaskCard v-for="t in byStatus('AWAITING_COORDINATOR')" :key="t.uuid" :t="t"/>
                </div>
                <div class="col" v-if="byStatus('ON_HOLD').length">
                    <div class="col__head col__head--hold">On hold</div>
                    <TaskCard v-for="t in byStatus('ON_HOLD')" :key="t.uuid" :t="t"/>
                </div>
                <div class="col col--done">
                    <div class="col__head">Completed</div>
                    <TaskCard v-for="t in byStatus('COMPLETED')" :key="t.uuid" :t="t"/>
                </div>
            </div>
            </n-tab-pane>
            <n-tab-pane name="pert" tab="PERT">
                <AiAgentTaskPertView :tasks="tasks"/>
            </n-tab-pane>
            <n-tab-pane name="timeline" tab="Timeline">
                <AiAgentTaskTimelineView :tasks="tasks" :agent-names="agentNames" @open="openTask"/>
            </n-tab-pane>
            <n-tab-pane name="table" tab="Table">
                <AiAgentTaskTableView :tasks="tasks" :agent-names="agentNames" @open="openTask"/>
            </n-tab-pane>
            </n-tabs>

            <AiAgentTaskDetailDrawer
                :task="selectedTask" :tasks="tasks" :agent-names="agentNames"
                @close="selectedTask = null" @open="openTask"/>
        </template>

        <!-- Board create / edit modal -->
        <n-modal :show="editingBoard !== null" preset="card"
                 :title="editingBoardIsNew ? 'New board' : `Edit board: ${editingBoard?.name}`"
                 style="max-width: 680px"
                 @update:show="(v: boolean) => { if (!v) editingBoard = null }">
            <n-space vertical :size="12" v-if="editingBoard">
                <n-input v-model:value="editingBoard.name" :disabled="!editingBoardIsNew" placeholder="Board name">
                    <template #prefix><span class="flabel">name</span></template>
                </n-input>
                <n-input v-model:value="editingBoard.description" placeholder="Description"/>
                <n-select v-model:value="editingBoard.sources" filterable multiple tag
                          placeholder="Wired sources, e.g. github:owner/repo (type + enter)"
                          :show-arrow="false" :show="false"/>
                <n-input-number v-model:value="editingBoard.perAgentWipLimit" :min="1">
                    <template #prefix><span class="flabel">per-agent WIP limit</span></template>
                </n-input-number>
                <n-checkbox v-if="editingBoardIsNew" v-model:checked="editingBoard.seedFromPresets">
                    seed roles from org presets (a preset named "coordinator" seeds the coordinator prompt)
                </n-checkbox>
                <div>
                    <div class="flabel" style="margin-bottom: 4px">coordinator prompt (implicit role — always present)</div>
                    <n-input v-model:value="editingBoard.coordinatorPrompt" type="textarea"
                             :autosize="{ minRows: 6, maxRows: 16 }"/>
                </div>
                <n-space justify="end">
                    <n-button quaternary @click="editingBoard = null">Cancel</n-button>
                    <n-button type="primary" :loading="saving" @click="saveBoard">Save</n-button>
                </n-space>
            </n-space>
        </n-modal>

        <!-- Role config modal -->
        <n-modal :show="showRoles" preset="card" title="Board roles" style="max-width: 860px"
                 @update:show="(v: boolean) => showRoles = v">
            <p class="hint">
                Roles are served prompts plus advisory routing order — any agent can assume any
                role; the coordinator routes each hop. The coordinator itself is implicit and not
                configurable here. Sign-offs pin the prompt version they ran under.
            </p>
            <n-data-table :columns="roleColumns" :data="sortedRoles" :row-key="(r: any) => r.uuid ?? r.name" size="small"/>
            <n-button class="addbtn" size="small" dashed @click="startAddRole">+ Add role</n-button>

            <n-modal :show="editingRole !== null" preset="card"
                     :title="editingRoleIsNew ? 'New role' : `Edit role: ${editingRole?.name}`"
                     style="max-width: 640px"
                     @update:show="(v: boolean) => { if (!v) editingRole = null }">
                <n-space vertical :size="12" v-if="editingRole">
                    <n-input v-model:value="editingRole.name" :disabled="!editingRoleIsNew" placeholder="Role name">
                        <template #prefix><span class="flabel">name</span></template>
                    </n-input>
                    <n-input-number v-model:value="editingRole.orderIndex">
                        <template #prefix><span class="flabel">routing order</span></template>
                    </n-input-number>
                    <n-input-number v-model:value="editingRole.wipLimit" :min="0" placeholder="0 = uncapped">
                        <template #prefix><span class="flabel">role WIP limit</span></template>
                    </n-input-number>
                    <n-space :size="18">
                        <n-checkbox v-model:checked="editingRole.requireDistinctAgent">require distinct agent</n-checkbox>
                        <n-checkbox v-model:checked="editingRole.active">active</n-checkbox>
                    </n-space>
                    <n-select v-model:value="editingRole.requiredCapabilities" multiple
                              :options="capabilityOptions"
                              placeholder="Required capabilities (declared, unverified in v1)"/>
                    <div>
                        <div class="flabel" style="margin-bottom: 4px">role prompt (served to the assuming agent)</div>
                        <n-input v-model:value="editingRole.prompt" type="textarea" :autosize="{ minRows: 8, maxRows: 20 }"/>
                    </div>
                    <n-space justify="end">
                        <n-button quaternary @click="editingRole = null">Cancel</n-button>
                        <n-button type="primary" :loading="saving" @click="saveRole">Save</n-button>
                    </n-space>
                </n-space>
            </n-modal>
        </n-modal>

        <!-- Org role presets (operator library; boards seed from these) -->
        <n-modal :show="showPresets" preset="card" title="Org role presets" style="max-width: 860px"
                 @update:show="(v: boolean) => showPresets = v">
            <p class="hint">
                Operator-curated library copied onto new boards (copy semantics — edits here do not
                ripple to existing boards). A preset named "coordinator" seeds a new board's
                coordinator prompt.
            </p>
            <n-data-table :columns="presetColumns" :data="sortedPresets" :row-key="(r: any) => r.uuid ?? r.name" size="small"/>
            <n-button class="addbtn" size="small" dashed @click="startAddPreset">+ Add preset</n-button>

            <n-modal :show="editingPreset !== null" preset="card"
                     :title="editingPresetIsNew ? 'New preset' : `Edit preset: ${editingPreset?.name}`"
                     style="max-width: 640px"
                     @update:show="(v: boolean) => { if (!v) editingPreset = null }">
                <n-space vertical :size="12" v-if="editingPreset">
                    <n-input v-model:value="editingPreset.name" :disabled="!editingPresetIsNew" placeholder="Preset name (use coordinator for the coordinator prompt)">
                        <template #prefix><span class="flabel">name</span></template>
                    </n-input>
                    <n-input-number v-model:value="editingPreset.orderIndex">
                        <template #prefix><span class="flabel">routing order</span></template>
                    </n-input-number>
                    <n-input-number v-model:value="editingPreset.wipLimit" :min="0" placeholder="0 = uncapped">
                        <template #prefix><span class="flabel">role WIP limit</span></template>
                    </n-input-number>
                    <n-space :size="18">
                        <n-checkbox v-model:checked="editingPreset.requireDistinctAgent">require distinct agent</n-checkbox>
                        <n-checkbox v-model:checked="editingPreset.active">active</n-checkbox>
                    </n-space>
                    <n-select v-model:value="editingPreset.requiredCapabilities" multiple
                              :options="capabilityOptions" placeholder="Required capabilities"/>
                    <div>
                        <div class="flabel" style="margin-bottom: 4px">prompt</div>
                        <n-input v-model:value="editingPreset.prompt" type="textarea" :autosize="{ minRows: 8, maxRows: 20 }"/>
                    </div>
                    <n-space justify="end">
                        <n-button quaternary @click="editingPreset = null">Cancel</n-button>
                        <n-button type="primary" :loading="saving" @click="savePreset">Save</n-button>
                    </n-space>
                </n-space>
            </n-modal>
        </n-modal>
    </div>
</template>

<script lang="ts" setup>
import { computed, defineComponent, h, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useStore } from 'vuex'
import { NAlert, NButton, NCard, NCheckbox, NCollapse, NCollapseItem, NDataTable, NInput, NInputNumber, NModal, NSelect, NSpace, NTabPane, NTabs, NTag, NTooltip, DataTableColumns, useNotification } from 'naive-ui'
import AiAgentTaskPertView from '@/components/AiAgentTaskPertView.vue'
import AiAgentTaskTimelineView from '@/components/AiAgentTaskTimelineView.vue'
import AiAgentTaskTableView from '@/components/AiAgentTaskTableView.vue'
import AiAgentTaskDetailDrawer from '@/components/AiAgentTaskDetailDrawer.vue'

const props = defineProps<{ orgUuid: string }>()

const store = useStore()
const notification = useNotification()
const route = useRoute()
const router = useRouter()

// Board selection and view live in the query string so a board (and
// the view you were looking at) is linkable and survives a reload.
const BOARD_VIEWS = ['kanban', 'pert', 'timeline', 'table']
const boardView = ref<string>(
    BOARD_VIEWS.includes(route.query.view as string) ? (route.query.view as string) : 'kanban')

function syncQuery () {
    const q: Record<string, string> = { ...(route.query as Record<string, string>), tab: 'boards' }
    if (selectedBoard.value) q.board = selectedBoard.value
    else delete q.board
    q.view = boardView.value
    router.replace({ query: q }).catch(() => { /* duplicate navigation is fine */ })
}

function setBoardView (v: string) {
    boardView.value = v
    syncQuery()
}

const boards = ref<any[]>([])
const selectedBoard = ref<string | null>(null)
const tasks = ref<any[]>([])
const roles = ref<any[]>([])
const showRoles = ref(false)
const editingBoard = ref<any>(null)
const editingBoardIsNew = ref(false)
const editingRole = ref<any>(null)
const editingRoleIsNew = ref(false)
const saving = ref(false)
const selectedTask = ref<any>(null)
const agentNames = ref<Record<string, string>>({})

function openTask (t: any) {
    // resolve to the freshest copy from the board so drawer navigation
    // (deps/lineage chips) always shows current state
    selectedTask.value = tasks.value.find(x => x.uuid === t.uuid) ?? t
}

const showPresets = ref(false)
const presets = ref<any[]>([])
const editingPreset = ref<any>(null)
const editingPresetIsNew = ref(false)

const capabilityOptions = ['TRACKER_READ', 'TRACKER_WRITE', 'CODE_PUSH', 'PR_MERGE']
    .map(c => ({ label: c, value: c }))

const sortedPresets = computed(() =>
    [...presets.value].sort((a, b) => (a.orderIndex ?? 0) - (b.orderIndex ?? 0)))

function formatEventTime (iso: string | null | undefined): string {
    if (!iso) return ''
    const d = new Date(iso)
    return isNaN(d.getTime()) ? '' : d.toLocaleString('en-CA', {
        month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
    })
}

// A QUEUED task is blocked when any dependency is not yet COMPLETED.
function blockedBy (t: any): string[] {
    if (t.status !== 'QUEUED' || !t.dependsOn?.length) return []
    return t.dependsOn.filter((d: string) => {
        const dep = tasks.value.find(x => x.uuid === d)
        return !dep || dep.status !== 'COMPLETED'
    })
}

// Resolve dependency uuids to the task rows so cards can name what
// they wait on ("after") and what waits on them ("blocks").
function depsOf (t: any): any[] {
    return (t.dependsOn ?? []).map((d: string) =>
        tasks.value.find(x => x.uuid === d) ?? { uuid: d, title: 'unknown task', status: 'UNKNOWN' })
}

function dependentsOf (t: any): any[] {
    return tasks.value.filter(x => (x.dependsOn ?? []).includes(t.uuid))
}

// Compact card label: the tracker issue number when there is one,
// otherwise a clipped title.
function depLabel (t: any): string {
    if (t.externalRef?.includes('#')) return '#' + t.externalRef.split('#').pop()
    const title = t.title ?? 'task'
    return title.length > 16 ? title.slice(0, 15) + '…' : title
}

const boardOptions = computed(() => boards.value.map(b => ({ label: b.name, value: b.uuid })))
const currentBoard = computed(() => boards.value.find(b => b.uuid === selectedBoard.value) ?? null)
const isLocked = computed(() => {
    const lvl = currentBoard.value?.lock?.level
    return !!lvl && lvl !== 'NONE'
})
const activeRoles = computed(() =>
    [...roles.value].filter(r => r.active).sort((a, b) => (a.orderIndex ?? 0) - (b.orderIndex ?? 0)))
const sortedRoles = computed(() =>
    [...roles.value].sort((a, b) => (a.orderIndex ?? 0) - (b.orderIndex ?? 0)))

function byStatus (s: string): any[] {
    return tasks.value.filter(t => t.status === s)
}
// QUEUED + ASSIGNED tasks grouped under their current role column
function atRole (role: string): any[] {
    return tasks.value.filter(t => (t.status === 'QUEUED' || t.status === 'ASSIGNED') && t.role === role)
}

// Compact task card as a local render component to keep the template lean.
const TaskCard = defineComponent({
    props: { t: { type: Object, required: true } },
    setup (p: any) {
        return () => h(NCard, { size: 'small', style: 'cursor: pointer', onClick: () => openTask(p.t), class: ['tcard',
            p.t.status === 'ASSIGNED' ? 'tcard--assigned' : '',
            p.t.status === 'COMPLETED' ? 'tcard--done' : ''] }, { default: () => [
            h('div', { class: 'tcard__title' }, p.t.title),
            h('div', { class: 'tcard__ref' }, p.t.sourceUrl
                ? h('a', { href: p.t.sourceUrl, target: '_blank', rel: 'noopener' },
                    (p.t.externalRef ?? 'draft').replace(/^github:/, ''))
                : ((p.t.externalRef ?? 'draft (no tracker ref yet)').replace(/^github:/, ''))),
            h('div', { class: 'tcard__meta' }, [
                p.t.status === 'QUEUED' ? h(NTag, { size: 'tiny', bordered: false }, { default: () => `queued #${p.t.orderIndex}` }) : null,
                p.t.status === 'ASSIGNED' ? h(NTag, { size: 'tiny', bordered: false, type: 'warning' }, { default: () => 'assigned' }) : null,
                p.t.status === 'ON_HOLD' ? h(NTooltip, { trigger: 'hover' }, {
                    trigger: () => h(NTag, { size: 'tiny', bordered: false, type: 'error' }, { default: () => 'on hold' }),
                    default: () => p.t.holdReason ?? 'on hold',
                }) : null,
                blockedBy(p.t).length ? h(NTooltip, { trigger: 'hover' }, {
                    trigger: () => h(NTag, { size: 'tiny', bordered: false, type: 'warning' }, {
                        default: () => 'blocked by ' + blockedBy(p.t)
                            .map((d: string) => depLabel(tasks.value.find(x => x.uuid === d) ?? { uuid: d }))
                            .join(', '),
                    }),
                    default: () => 'Not assignable until every dependency is COMPLETED; the server releases it automatically.',
                }) : null,
                p.t.parentTask ? h(NTag, { size: 'tiny', bordered: false, type: 'info' }, { default: () => 'subtask' }) : null,
                p.t.childTasks?.length ? h(NTag, { size: 'tiny', bordered: false, type: 'info' }, { default: () => `${p.t.childTasks.length} subtasks` }) : null,
                p.t.returns?.length ? h(NTooltip, { trigger: 'hover' }, {
                    trigger: () => h(NTag, { size: 'tiny', bordered: false, type: 'error' }, { default: () => `${p.t.returns.length} return${p.t.returns.length > 1 ? 's' : ''}` }),
                    default: () => p.t.returns.map((r: any) => `${r.role ?? '?'}: ${r.reason}${r.description ? ' — ' + r.description : ''}`).join(' | '),
                }) : null,
                ...(p.t.prUrls ?? []).map((pr: string) => h(NTag, { size: 'tiny', bordered: false, type: 'success' },
                    { default: () => h('a', { href: pr, target: '_blank', rel: 'noopener', class: 'prlink' }, 'PR') })),
            ]),
            p.t.dependsOn?.length ? h('div', { class: 'tcard__deps' }, [
                h('span', { class: 'deplabel' }, 'after'),
                ...depsOf(p.t).map((d: any, i: number) => h(NTooltip, { trigger: 'hover', key: 'a' + i }, {
                    trigger: () => h('span', {
                        class: ['depchip', d.status === 'COMPLETED' ? 'depchip--done' : 'depchip--wait'],
                    }, depLabel(d)),
                    default: () => `${d.title} — ${d.status}`,
                })),
            ]) : null,
            dependentsOf(p.t).length ? h('div', { class: 'tcard__deps' }, [
                h('span', { class: 'deplabel' }, 'blocks'),
                ...dependentsOf(p.t).map((d: any, i: number) => h(NTooltip, { trigger: 'hover', key: 'b' + i }, {
                    trigger: () => h('span', { class: 'depchip depchip--blocks' }, depLabel(d)),
                    default: () => `${d.title} — ${d.status}`,
                })),
            ]) : null,
            p.t.signOffs?.length ? h('div', { class: 'tcard__passages' },
                p.t.signOffs.map((s: any, i: number) => h(NTooltip, { trigger: 'hover', key: i }, {
                    trigger: () => h('span', { class: ['passage', 'passage--' + (s.outcome || '').toLowerCase()] }, s.role),
                    default: () => `${s.role}: ${s.outcome}${s.note ? ' — ' + s.note : ''}`,
                }))) : null,
        ] })
    },
})

const roleColumns: DataTableColumns<any> = [
    { title: 'Order', key: 'orderIndex', width: 70 },
    { title: 'Role', key: 'name', width: 140 },
    { title: 'WIP', key: 'wipLimit', width: 60, render: (r: any) => r.wipLimit ?? '—' },
    {
        title: 'Flags', key: 'flags', width: 170,
        render: (r: any) => h('span', {}, [
            r.requireDistinctAgent ? h(NTag, { size: 'tiny', bordered: false, type: 'warning' }, { default: () => 'distinct agent' }) : null,
            !r.active ? h(NTag, { size: 'tiny', bordered: false, style: 'margin-left:4px' }, { default: () => 'inactive' }) : null,
        ]),
    },
    { title: 'Prompt', key: 'prompt', ellipsis: { tooltip: true }, render: (r: any) => (r.prompt ? r.prompt.split('\n')[0] : '—') },
    {
        title: '', key: 'actions', width: 70,
        render: (r: any) => h(NButton, { size: 'tiny', quaternary: true, onClick: () => { editingRoleIsNew.value = false; editingRole.value = { ...r } } }, { default: () => 'Edit' }),
    },
]

onMounted(refreshBoards)
watch(selectedBoard, async () => {
    syncQuery()
    await refreshBoardContent()
})

async function refreshBoards () {
    boards.value = await store.dispatch('fetchAgentBoardsOfOrg', props.orgUuid) ?? []
    if (!selectedBoard.value) {
        const fromUrl = route.query.board as string | undefined
        const known = fromUrl && boards.value.some(b => b.uuid === fromUrl)
        selectedBoard.value = known ? (fromUrl as string) : (boards.value[0]?.uuid ?? null)
    }
    syncQuery()
    await refreshBoardContent()
}

async function refreshBoardContent () {
    if (!selectedBoard.value) { tasks.value = []; roles.value = []; return }
    const [t, r] = await Promise.all([
        store.dispatch('fetchAgentTasksOfBoard', { boardUuid: selectedBoard.value }),
        store.dispatch('fetchAgentTaskRoleConfigsOfBoard', selectedBoard.value),
    ])
    tasks.value = t ?? []
    roles.value = r ?? []
    // agent uuid -> display name map for timeline/table/drawer; loaded
    // lazily once per panel life, refreshed with board content
    const agents = await store.dispatch('fetchAgentsOfOrg', props.orgUuid) ?? []
    const m: Record<string, string> = {}
    for (const a of agents) m[a.uuid] = a.effectiveDisplayName || a.name || a.uuid.slice(0, 8)
    agentNames.value = m
}

function startEditBoard (b: any | null) {
    editingBoardIsNew.value = b === null
    editingBoard.value = b ? { ...b, sources: [...(b.sources ?? [])] }
        : { name: '', description: '', sources: [], coordinatorPrompt: '', perAgentWipLimit: 2, seedFromPresets: true }
}

async function saveBoard () {
    if (!editingBoard.value?.name?.trim()) {
        notification.error({ content: 'Board name is required' })
        return
    }
    saving.value = true
    try {
        const input: any = {
            description: editingBoard.value.description ?? '',
            sources: editingBoard.value.sources ?? [],
            coordinatorPrompt: editingBoard.value.coordinatorPrompt ?? '',
            perAgentWipLimit: editingBoard.value.perAgentWipLimit ?? 2,
        }
        if (editingBoardIsNew.value) {
            input.name = editingBoard.value.name.trim()
            input.seedFromPresets = !!editingBoard.value.seedFromPresets
            await store.dispatch('createAgentBoard', { orgUuid: props.orgUuid, input })
        } else {
            await store.dispatch('updateAgentBoard', { boardUuid: editingBoard.value.uuid, input })
        }
        notification.success({ content: `Board ${editingBoard.value.name} saved` })
        const wasNew = editingBoardIsNew.value
        const savedName = editingBoard.value.name.trim()
        editingBoard.value = null
        await refreshBoards()
        if (wasNew) {
            const created = boards.value.find(b => b.name === savedName)
            if (created) selectedBoard.value = created.uuid
        }
    } catch (e: any) {
        notification.error({ content: `Save failed: ${e?.message ?? e}` })
    } finally {
        saving.value = false
    }
}

function startAddRole () {
    editingRoleIsNew.value = true
    const maxOrder = Math.max(0, ...roles.value.map(r => r.orderIndex ?? 0))
    editingRole.value = { name: '', prompt: '', orderIndex: maxOrder + 10, wipLimit: 0, requireDistinctAgent: false, active: true }
}

async function saveRole () {
    if (!editingRole.value?.name?.trim() || !selectedBoard.value) {
        notification.error({ content: 'Role name is required' })
        return
    }
    saving.value = true
    try {
        await store.dispatch('setAgentTaskRoleConfig', {
            boardUuid: selectedBoard.value,
            input: {
                name: editingRole.value.name.trim(),
                prompt: editingRole.value.prompt ?? '',
                orderIndex: editingRole.value.orderIndex ?? 0,
                wipLimit: editingRole.value.wipLimit ?? 0,
                requireDistinctAgent: !!editingRole.value.requireDistinctAgent,
                active: !!editingRole.value.active,
                requiredCapabilities: editingRole.value.requiredCapabilities ?? [],
            },
        })
        notification.success({ content: `Role ${editingRole.value.name} saved` })
        editingRole.value = null
        await refreshBoardContent()
    } catch (e: any) {
        notification.error({ content: `Save failed: ${e?.message ?? e}` })
    } finally {
        saving.value = false
    }
}

const presetColumns: DataTableColumns<any> = [
    { title: 'Order', key: 'orderIndex', width: 70 },
    { title: 'Preset', key: 'name', width: 140 },
    {
        title: 'Capabilities', key: 'caps', width: 220,
        render: (r: any) => (r.requiredCapabilities ?? []).join(', ') || '—',
    },
    { title: 'Prompt', key: 'prompt', ellipsis: { tooltip: true }, render: (r: any) => (r.prompt ? r.prompt.split('\n')[0] : '—') },
    {
        title: '', key: 'actions', width: 70,
        render: (r: any) => h(NButton, { size: 'tiny', quaternary: true, onClick: () => { editingPresetIsNew.value = false; editingPreset.value = { ...r } } }, { default: () => 'Edit' }),
    },
]

async function openPresets () {
    presets.value = await store.dispatch('fetchAgentTaskRolePresetsOfOrg', props.orgUuid) ?? []
    showPresets.value = true
}

function startAddPreset () {
    editingPresetIsNew.value = true
    const maxOrder = Math.max(0, ...presets.value.map(r => r.orderIndex ?? 0))
    editingPreset.value = { name: '', prompt: '', orderIndex: maxOrder + 10, wipLimit: 0, requireDistinctAgent: false, active: true, requiredCapabilities: [] }
}

async function savePreset () {
    if (!editingPreset.value?.name?.trim()) {
        notification.error({ content: 'Preset name is required' })
        return
    }
    saving.value = true
    try {
        await store.dispatch('setAgentTaskRolePreset', {
            orgUuid: props.orgUuid,
            input: {
                name: editingPreset.value.name.trim(),
                prompt: editingPreset.value.prompt ?? '',
                orderIndex: editingPreset.value.orderIndex ?? 0,
                wipLimit: editingPreset.value.wipLimit ?? 0,
                requireDistinctAgent: !!editingPreset.value.requireDistinctAgent,
                active: !!editingPreset.value.active,
                requiredCapabilities: editingPreset.value.requiredCapabilities ?? [],
            },
        })
        notification.success({ content: `Preset ${editingPreset.value.name} saved` })
        editingPreset.value = null
        presets.value = await store.dispatch('fetchAgentTaskRolePresetsOfOrg', props.orgUuid) ?? []
    } catch (e: any) {
        notification.error({ content: `Save failed: ${e?.message ?? e}` })
    } finally {
        saving.value = false
    }
}

async function operatorLock (lock: boolean) {
    let reason: string | undefined
    if (lock) {
        reason = window.prompt('Lock reason (shown to agents and on the board):') ?? undefined
        if (reason === undefined) return
    }
    try {
        await store.dispatch('setAgentBoardOperatorLock', { boardUuid: selectedBoard.value, lock, reason })
        notification.success({ content: lock ? 'Board locked (OPERATOR)' : 'Board unlocked' })
        await refreshBoards()
    } catch (e: any) {
        notification.error({ content: `Lock change failed: ${e?.message ?? e}` })
    }
}
</script>

<style lang="scss">
.boardsPanel {
    .section-head {
        display: flex;
        align-items: center;
        justify-content: space-between;
        margin-bottom: 8px;
        h5 { margin: 0; }
    }
    .empty { color: #888; font-size: 13px; padding: 8px 0; }
    .hint { color: #888; font-size: 12px; margin: 0 0 12px; }
    .addbtn { margin-top: 10px; }
    .flabel { color: #888; font-size: 12px; }
    .lockbanner { margin-bottom: 10px; }
    .eventsfeed { margin-bottom: 10px; }
    .evrow {
        display: flex;
        align-items: baseline;
        gap: 8px;
        font-size: 12px;
        padding: 2px 0;
        .evmsg { flex: 1; }
        .evmeta { color: #999; font-size: 11px; white-space: nowrap; }
    }
    .col__head--hold { color: #b03a3a; }
    .boardmeta {
        display: flex;
        align-items: center;
        flex-wrap: wrap;
        gap: 8px;
        margin-bottom: 10px;
        .srcchip {
            font-family: monospace;
            font-size: 12px;
            background: rgba(128, 128, 128, 0.12);
            border-radius: 4px;
            padding: 1px 7px;
        }
        .coordissue { font-size: 12px; }
    }
    .viewtabs { margin-bottom: 6px; }
    .board {
        display: grid;
        grid-auto-flow: column;
        grid-auto-columns: minmax(200px, 1fr);
        gap: 12px;
        align-items: start;
        overflow-x: auto;
    }
    .col__head {
        font-size: 12px;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.04em;
        color: #888;
        padding: 2px 4px 8px;
        .col__wip { margin-left: 6px; font-weight: 400; text-transform: none; }
    }
    .col--done .col__head { color: #4a9d6e; }
    .tcard {
        margin-bottom: 10px;
        &--assigned { border-left: 3px solid #d9a24a; }
        &--done { opacity: 0.85; border-left: 3px solid #4a9d6e; }
        .tcard__title { font-size: 13px; font-weight: 500; margin-bottom: 4px; }
        .tcard__ref { font-size: 12px; margin-bottom: 6px; word-break: break-all; }
        .tcard__meta { display: flex; flex-wrap: wrap; gap: 4px; margin-bottom: 4px; }
        .tcard__passages { display: flex; flex-wrap: wrap; gap: 4px; }
        .tcard__deps {
            display: flex;
            flex-wrap: wrap;
            align-items: center;
            gap: 4px;
            margin-bottom: 4px;
            .deplabel {
                font-size: 10px;
                text-transform: uppercase;
                letter-spacing: 0.05em;
                color: #999;
            }
        }
    }
    .passage {
        font-size: 11px;
        padding: 1px 6px;
        border-radius: 8px;
        background: #eee;
        color: #555;
        &--passed { background: #e2f3e8; color: #2f7a4d; }
        &--rejected { background: #fbe3e3; color: #b03a3a; }
    }
    .depchip {
        font-size: 11px;
        font-family: monospace;
        padding: 1px 6px;
        border-radius: 8px;
        border: 1px dashed transparent;
        &--done { background: #e2f3e8; color: #2f7a4d; }
        &--wait { background: #fdf1de; color: #9a6516; border-color: #e6c88f; }
        &--blocks { background: rgba(128, 128, 128, 0.12); color: #666; }
    }
    .prlink { color: inherit; text-decoration: none; }
}
</style>
