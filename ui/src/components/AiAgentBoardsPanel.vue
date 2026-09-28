<template>
    <div class="boardsPanel">
        <div class="section-head">
            <h5>Task boards</h5>
            <n-space :size="8">
                <n-select
                    v-if="boards.length"
                    v-model:value="selectedBoard"
                    :options="boardOptions"
                    :render-label="renderBoardOption"
                    size="small"
                    style="min-width: 220px"
                />
                <n-button size="small" quaternary @click="newTask"
                          v-if="currentBoard && canOperate(currentBoard)">+ New task</n-button>
                <n-button size="small" quaternary @click="startEditBoard(currentBoard)"
                          v-if="currentBoard && canConfigure(currentBoard)">Edit board</n-button>
                <n-button size="small" quaternary @click="showRoles = true" v-if="currentBoard">Roles</n-button>
                <n-button size="small" quaternary @click="openSpec" v-if="currentBoard && canConfigureRead(currentBoard)"
                          data-testid="view-as-spec">View as spec</n-button>
                <!-- Subscriptions are an org admin's (RD2-14); everyone else is told how the board reaches them. -->
                <n-button size="small" quaternary @click="subscribeToBoard"
                          v-if="currentBoard && subscribeOffer(currentBoard, isAdmin).kind === 'subscribe'" data-testid="subscribe"
                          title="Get notified when this board needs a person: alerts, holds, returns, tasks waiting">
                    Subscribe
                </n-button>
                <span v-else-if="currentBoard" class="subhint" data-testid="subscribe-hint">
                    {{ (subscribeOffer(currentBoard, isAdmin) as any).text }}
                </span>
                <n-button size="small" quaternary @click="applyKinds = ['BOARD']"
                          v-if="canApplySpec || canConfigure(currentBoard)">Apply spec</n-button>
                <n-button size="small" quaternary @click="openPresets">Org presets</n-button>
                <n-button size="small" quaternary @click="startEditBoard(null)">+ New board</n-button>
            </n-space>
        </div>

        <!-- A refusal says so (RD2-9): the list is what the person may read, so an empty one is "none
             yet" only for an org admin, and a board link to a board not listed is a board they cannot open. -->
        <n-alert v-if="hiddenBoard" type="warning" :bordered="false" class="lockbanner" data-testid="hidden-board">
            {{ hiddenBoardText(hiddenBoard) }}
        </n-alert>
        <div v-if="!boards.length" class="empty" data-testid="no-boards">{{ noBoardsText(isAdmin) }}</div>

        <template v-if="currentBoard">
            <!-- Lock banner + operator lock controls -->
            <n-alert
                v-if="isLocked"
                :type="currentBoard.lock.level === 'OPERATOR' ? 'error' : 'warning'"
                class="lockbanner"
            >
                Board locked ({{ currentBoard.lock.level }}) — no new assignments.
                <template v-if="currentBoard.lock.reason"> Reason: {{ currentBoard.lock.reason }}.</template>
                <template v-if="actorLabel(currentBoard.lock.lockedBy)"> Held by {{ actorLabel(currentBoard.lock.lockedBy) }}.</template>
                <BoardLockControl v-if="currentBoard.lock.level === 'OPERATOR' && canOperate(currentBoard)"
                                  action="unlock" :board="currentBoard" :when="formatEventTime" @unlock="operatorLock(false)"/>
            </n-alert>
            <div class="boardmeta">
                <n-tooltip v-if="currentBoard.declarative" trigger="hover">
                    <template #trigger>
                        <span class="srcchip">applied from {{ provenanceLabel(currentBoard.declarative) }}</span>
                    </template>
                    Last configured from a board file, <agent-time :at="currentBoard.declarative.appliedAt"/>
                    (spec {{ (currentBoard.declarative.specHash ?? '').slice(0, 12) }}). Edits made here since
                    show in the next export, and the file wins for every field it declares when applied again.
                </n-tooltip>
                <span v-for="s in currentBoard.sources ?? []" :key="s" class="srcchip">{{ s }}</span>
                <n-tag size="tiny" :bordered="false" :type="currentBoard.coordinatorSeat ? 'success' : 'default'">
                    {{ currentBoard.coordinatorSeat ? 'coordinator connected' : 'no coordinator' }}
                </n-tag>
                <n-tag v-if="boardTargetChip" size="tiny" :bordered="false" data-testid="target-chip">
                    <RouterLink :to="boardTargetChip.to">{{ boardTargetChip.label }}</RouterLink>
                </n-tag>
                <n-tag v-for="p in perspectiveChips(currentBoard)" :key="p" size="tiny" :bordered="false"
                       data-testid="perspective-chip">{{ p }}</n-tag>
                <n-tag v-if="spendChip" size="tiny" :bordered="false" :type="spendChip.type" data-testid="spend-chip">
                    {{ spendChip.label }}
                </n-tag>
                <n-tooltip trigger="hover">
                    <template #trigger>
                        <span class="wipchip wipchip--board">agent WIP ≤ {{ currentBoard.perAgentWipLimit }}</span>
                    </template>
                    Max concurrently assigned tasks per agent on this board.
                </n-tooltip>
                <n-tooltip trigger="hover">
                    <template #trigger>
                        <span class="wipchip" :class="{ 'wipchip--strict': currentBoard.priorityType === 'STRICT' }">
                            priority {{ (currentBoard.priorityType ?? 'LAX').toLowerCase() }}
                        </span>
                    </template>
                    {{ currentBoard.priorityType === 'STRICT'
                        ? 'STRICT: a worker may only take the top eligible task for it — coordinator ordering is enforced at assignment.'
                        : 'LAX: the poll offers work in priority order, but a worker may assign any eligible task.' }}
                </n-tooltip>
                <!-- The level a task without its own reads (RD2-1). -->
                <n-tooltip v-if="currentBoard.defaultTaskLevel != null" trigger="hover">
                    <template #trigger>
                        <span class="wipchip" data-testid="default-level-chip">default level {{ currentBoard.defaultTaskLevel }}</span>
                    </template>
                    Tasks without a level of their own read level {{ currentBoard.defaultTaskLevel }}. {{ LEVEL_LADDER_HINT }}
                </n-tooltip>
                <n-tooltip v-for="w in agentWip" :key="w.agent" trigger="hover">
                    <template #trigger>
                        <span class="wipchip" :class="{ 'wipchip--full': w.count >= currentBoard.perAgentWipLimit }">
                            {{ w.name }} {{ w.count }}/{{ currentBoard.perAgentWipLimit }}
                        </span>
                    </template>
                    {{ w.count >= currentBoard.perAgentWipLimit
                        ? 'At the per-agent WIP limit — this agent gets no new assignments until a task leaves ASSIGNED.'
                        : 'Concurrently assigned tasks vs the board per-agent limit.' }}
                </n-tooltip>
                <BoardLockControl v-if="!isLocked && canOperate(currentBoard)" action="lock" :board="currentBoard"
                                  :when="formatEventTime" @lock="p => operatorLock(true, p.reason)"/>
            </div>
            <!-- One warning for what the board lacks (task 5c70990d): the delivery loop's capabilities,
                 then what no key can do on the board. -->
            <n-alert v-if="boardWarningShown(currentBoard.missingCapabilities, missingCoverage)" type="warning"
                     class="lockbanner" data-testid="board-warning">
                <div v-if="currentBoard.missingCapabilities?.length">
                    Delivery loop incomplete: no active role or the coordinator covers
                    {{ currentBoard.missingCapabilities.join(', ') }} — give a role the capability, or declare
                    that the coordinator covers it in the board's settings.
                </div>
                <div v-for="line in coverageLines(missingCoverage)" :key="line" class="coverage-line">{{ line }}</div>
            </n-alert>
            <!-- A person who can approve is asked to; a reader is told the board waits on a person (RD2-6). -->
            <n-alert v-if="awaitingHumanReview.length && canOperate(currentBoard)" type="error" class="lockbanner"
                     data-testid="review-banner">
                {{ awaitingHumanReview.length }} task{{ awaitingHumanReview.length > 1 ? 's' : '' }} awaiting your review:
                <n-button v-for="t in awaitingHumanReview" :key="t.uuid" size="tiny" quaternary
                          style="margin-left: 6px" @click="openTask(t)">
                    {{ reviewBannerLabel(t) }}
                </n-button>
            </n-alert>
            <n-alert v-else-if="awaitingHumanReview.length" type="info" class="lockbanner" data-testid="review-banner-info">
                {{ awaitingHumanReview.length }} task{{ awaitingHumanReview.length > 1 ? 's' : '' }} awaiting human review.
            </n-alert>
            <n-collapse v-if="currentBoard.events?.length" class="eventsfeed">
                <n-collapse-item :title="`Board events (${currentBoard.events.length})`" name="ev">
                    <div v-for="(e, i) in [...currentBoard.events].reverse()" :key="i" class="evrow">
                        <n-tag size="tiny" :bordered="false"
                               :type="e.kind === 'ALERT' ? 'error' : e.kind === 'LOCKED' ? 'warning' : 'default'">{{ e.kind }}</n-tag>
                        <span class="evmsg">{{ e.message }}</span>
                        <span class="evmeta">{{ actorLabel(e.actor) }} · <agent-time :at="e.eventAt"/></span>
                    </div>
                </n-collapse-item>
            </n-collapse>

            <n-tabs type="segment" size="small" class="viewtabs"
                    :value="boardView" @update:value="setBoardView">
            <n-tab-pane name="kanban" tab="Kanban">
            <!-- Grouping and the level filter (RD2-1), kept in the URL (?groupBy=level&level=2). The
                 grouping is generic: RD2-2 adds "group" to GROUPINGS, not a second toggle. -->
            <n-space :size="8" align="center" class="kanbanbar" data-testid="kanban-bar">
                <span class="flabel">group by</span>
                <n-radio-group :value="groupBy" size="small" @update:value="setGroupBy" data-testid="group-by">
                    <n-radio-button v-for="o in GROUP_BY_OPTIONS" :key="o.value" :value="o.value" :label="o.label"/>
                </n-radio-group>
                <n-select :value="levelFilter" :options="levelFilterOptions" size="small" clearable
                          placeholder="any level" style="width: 130px" data-testid="level-filter"
                          @update:value="setLevelFilter"/>
                <!-- The group and tag filters (RD2-31), in the URL as ?group=core-work&tag=client-req. -->
                <n-select v-if="currentBoard?.groups?.length" :value="groupFilter" :options="groupFilterOptions" size="small" clearable
                          placeholder="any group" style="width: 170px" data-testid="group-filter"
                          @update:value="setGroupFilter"/>
                <n-select v-if="tagFilterOptions.length" :value="tagFilter" :options="tagFilterOptions" size="small" clearable filterable
                          placeholder="any tag" style="width: 150px" data-testid="tag-filter"
                          @update:value="setTagFilter"/>
            </n-space>
            <!-- Hub-and-spoke kanban: intake / per-role / awaiting coordinator / done -->
            <div v-for="lane in kanbanLanes" :key="lane.key" class="lane" :data-lane="lane.key">
            <div v-if="groupBy !== 'none'" class="lane__head" data-testid="lane-head"
                 :style="groupBy === 'group' && groupColour(lane.key === 'none' ? null : lane.key)
                     ? { borderLeft: `4px solid ${groupColour(lane.key)}`, paddingLeft: '6px' } : undefined">{{ lane.label }}</div>
            <!-- Columns at a laptop's width (RD2-13): each head counts its cards, an empty column folds to
                 its head until opened, and a strip still too wide says how many columns sit past the edge. -->
            <!-- Above the strip, where it shows without scrolling the page (RD2-13 run 1, T-2). -->
            <div v-if="moreLanesHint(pastEdge[lane.key] ?? 0)" class="board__more" data-testid="more-lanes">
                {{ moreLanesHint(pastEdge[lane.key] ?? 0) }}
            </div>
            <div class="board" :ref="(el: any) => boardEl(lane.key, el)" @scroll="measureBoards">
                <div class="col" :class="{ 'col--folded': folded('intake', byStatus('PENDING_INTAKE', lane.tasks)) }" data-col="intake">
                    <div class="col__head" @click="openColumn('intake')">Pending intake<span class="col__count" data-testid="col-count">{{ byStatus('PENDING_INTAKE', lane.tasks).length }}</span></div>
                    <template v-if="!folded('intake', byStatus('PENDING_INTAKE', lane.tasks))">
                        <TaskCard v-for="t in byStatus('PENDING_INTAKE', lane.tasks)" :key="t.uuid" :t="t"/>
                    </template>
                </div>
                <div class="col" v-for="r in activeRoles" :key="r.name" :class="{ 'col--folded': folded('role:' + r.name, atRole(r.name, lane.tasks)) }"
                     :data-col="'role:' + r.name">
                    <div class="col__head" @click="openColumn('role:' + r.name)">
                        {{ r.name }}<span class="col__count" data-testid="col-count">{{ atRole(r.name, lane.tasks).length }}</span>
                        <n-tooltip v-if="r.kind === 'HUMAN'" trigger="hover">
                            <template #trigger><span class="col__human">human</span></template>
                            Human stage: never offered to agent polls — an org admin signs off directly from the queue (open the card).
                        </n-tooltip>
                        <n-tooltip v-if="r.necessity === 'REQUIRED'" trigger="hover">
                            <template #trigger><span class="col__req">required</span></template>
                            Unskippable: no task completes without a passing {{ r.name }} sign-off.
                        </n-tooltip>
                        <n-tooltip v-if="r.humanGate && r.humanGate !== 'NONE'" trigger="hover">
                            <template #trigger><span class="col__gate">gated</span></template>
                            {{ r.humanGate === 'ON_PASS' ? 'Passing sign-offs' : 'Every sign-off' }} in this role parks the task for human review.
                        </n-tooltip>
                        <span v-if="r.wipLimit" class="col__wip"
                              :class="{ 'col__wip--full': assignedInRole(r.name) >= r.wipLimit }">
                            {{ assignedInRole(r.name) }}/{{ r.wipLimit }} wip
                        </span>
                    </div>
                    <template v-if="!folded('role:' + r.name, atRole(r.name, lane.tasks))">
                        <TaskCard v-for="t in atRole(r.name, lane.tasks)" :key="t.uuid" :t="t"/>
                    </template>
                </div>
                <div class="col" :class="{ 'col--folded': folded('awaiting', byStatus('AWAITING_COORDINATOR', lane.tasks)) }" data-col="awaiting">
                    <div class="col__head" @click="openColumn('awaiting')">Awaiting coordinator<span class="col__count" data-testid="col-count">{{ byStatus('AWAITING_COORDINATOR', lane.tasks).length }}</span></div>
                    <template v-if="!folded('awaiting', byStatus('AWAITING_COORDINATOR', lane.tasks))">
                        <TaskCard v-for="t in byStatus('AWAITING_COORDINATOR', lane.tasks)" :key="t.uuid" :t="t"/>
                    </template>
                </div>
                <div class="col" v-if="byStatus('ON_HOLD', lane.tasks).length" data-col="hold">
                    <div class="col__head col__head--hold">On hold<span class="col__count" data-testid="col-count">{{ byStatus('ON_HOLD', lane.tasks).length }}</span></div>
                    <TaskCard v-for="t in byStatus('ON_HOLD', lane.tasks)" :key="t.uuid" :t="t"/>
                </div>
                <div class="col" v-if="byStatus('DELIVERING', lane.tasks).length" data-col="delivering">
                    <div class="col__head">Delivering<span class="col__count" data-testid="col-count">{{ byStatus('DELIVERING', lane.tasks).length }}</span></div>
                    <TaskCard v-for="t in byStatus('DELIVERING', lane.tasks)" :key="t.uuid" :t="t"/>
                </div>
                <div class="col col--done" :class="{ 'col--folded': folded('done', byStatus('COMPLETED', lane.tasks)) }" data-col="done">
                    <div class="col__head" @click="openColumn('done')">Completed<span class="col__count" data-testid="col-count">{{ byStatus('COMPLETED', lane.tasks).length }}</span></div>
                    <template v-if="!folded('done', byStatus('COMPLETED', lane.tasks))">
                        <TaskCard v-for="t in byStatus('COMPLETED', lane.tasks)" :key="t.uuid" :t="t"/>
                    </template>
                </div>
            </div>
            </div>
            <!-- An empty board says how tasks arrive (RD2-13). -->
            <div v-if="!tasks.length" class="board__empty" data-testid="empty-board">
                {{ EMPTY_BOARD_HINT }}
                <n-button v-if="currentBoard && canOperate(currentBoard)" size="tiny" style="margin-left: 8px" @click="newTask">+ New task</n-button>
            </div>
            </n-tab-pane>
            <n-tab-pane name="pert" tab="PERT">
                <AiAgentTaskPertView :tasks="tasks" :board="currentBoard"/>
            </n-tab-pane>
            <n-tab-pane name="timeline" tab="Timeline">
                <AiAgentTaskTimelineView :tasks="tasks" :agent-names="agentNames" :agent-dir="agentDir" :board="currentBoard" @open="openTask"/>
            </n-tab-pane>
            <n-tab-pane name="table" tab="Table">
                <AiAgentTaskTableView :tasks="tasks" :agent-names="agentNames" :agent-dir="agentDir" :board-has-sources="boardHasSources" :board="currentBoard"
                                      :group-filter="groupFilter" :tag-filter="tagFilter"
                                      @update:group-filter="setGroupFilter" @update:tag-filter="setTagFilter"
                                      @open="openTask"/>
            </n-tab-pane>
            <n-tab-pane name="usage" tab="Usage">
                <AgentBoardUsagePanel :board-uuid="selectedBoard" :agent-names="agentNames"
                                      :budget-micros="currentBoard?.budgetMicros" :lifetime-spent-micros="lifetimeSpentMicros"
                                      :soft-alert-percent="currentBoard?.softAlertPercent"/>
            </n-tab-pane>
            <n-tab-pane name="documents" tab="Documents">
                <AgentBoardDocumentsPanel :series="documentSeries" :org-uuid="orgUuid"/>
            </n-tab-pane>
            <n-tab-pane name="groups" tab="Groups">
                <AgentBoardGroupsPanel :board="currentBoard" :can-configure="canConfigure(currentBoard)" :save-group="saveGroup"/>
            </n-tab-pane>
            </n-tabs>

            <AiAgentTaskDetailDrawer
                :task="selectedTask" :tasks="tasks" :agent-names="agentNames" :roles="roles"
                :board="currentBoard" :priority-levels="priorityLevels"
                @close="selectedTask = null" @open="openTask"
                @human-review="humanReview" @human-signoff="humanSignOff"
                @operator-release="operatorRelease" @require-review="requireReview"
                @authorize="authorizeTask" @order="orderTask"
                @complete="completeTask" @cancel="cancelTask" @decide="decideFindings"
                @set-strength="setStrength" @operator-hold="operatorHold" @set-budget="setBudget" @set-level="setLevel"
                @release-assignment="releaseAssignment"
                @set-group="setGroup" @set-tags="setTags" @delivered="delivered"
                :can-reopen="canReopen" @reopen="reopenTask"/>

            <!-- A person registers a task directly; on a board with sources it names the issue, so
                 the coordinator's intake of the same issue finds it rather than duplicating it. -->
            <n-modal :show="registering !== null" preset="card" title="New task" style="max-width: 520px"
                     @update:show="(v: boolean) => { if (!v) registering = null }">
                <n-space vertical :size="10" v-if="registering">
                    <!-- A title is one line of at most 120 characters, what a card shows; the rest goes
                         in the description (task fceb1e57). Said here before the server refuses it. -->
                    <n-input v-model:value="registering.title" placeholder="Title (one line, at most 120 characters)"
                             data-testid="new-task-title"
                             :status="taskTitleProblem(registering.title) ? 'error' : undefined"/>
                    <n-text v-if="taskTitleProblem(registering.title)" type="error" data-testid="new-task-title-error"
                            style="font-size: 12px; margin-top: -6px;">
                        {{ taskTitleProblem(registering.title) }}
                    </n-text>
                    <n-input v-model:value="registering.description" type="textarea" data-testid="new-task-description"
                             :autosize="{ minRows: 3, maxRows: 12 }"
                             placeholder="Description (optional): what the task is, beyond its title"
                             :status="taskDescriptionProblem(registering.description) ? 'error' : undefined"/>
                    <n-text v-if="taskDescriptionProblem(registering.description)" type="error"
                            style="font-size: 12px; margin-top: -6px;">
                        {{ taskDescriptionProblem(registering.description) }}
                    </n-text>
                    <n-input v-model:value="registering.externalRef"
                             :placeholder="(currentBoard.sources?.length ?? 0) > 0
                                 ? 'Tracker issue, e.g. github:owner/repo#42 (required)'
                                 : 'Tracker issue (optional)'"/>
                    <n-input v-model:value="registering.sourceUrl" placeholder="Link (optional)"/>
                    <!-- Group, tags and level (RD2-31): a group takes new tasks only while OPEN. -->
                    <n-select v-if="currentBoard.groups?.length" v-model:value="registering.group" data-testid="new-task-group"
                              :options="groupOptions(currentBoard, { openOnly: true, none: true })" placeholder="Group: none"/>
                    <n-input v-model:value="registering.tagsText" data-testid="new-task-tags"
                             placeholder="Tags (optional), comma separated, e.g. client-req, sandbox-only"
                             :status="tagsProblem(parseTags(registering.tagsText)) ? 'error' : undefined"/>
                    <n-text v-if="tagsProblem(parseTags(registering.tagsText))" type="error" style="font-size: 12px; margin-top: -6px;">
                        {{ tagsProblem(parseTags(registering.tagsText)) }}
                    </n-text>
                    <n-input-number v-model:value="registering.level" :min="0" :max="MAX_LEVEL" :step="1" :precision="0" clearable
                                    data-testid="new-task-level" :placeholder="`Level: ${levelPlaceholder(currentBoard)}`"/>
                    <n-space justify="end">
                        <n-button size="small" @click="registering = null">Cancel</n-button>
                        <n-button size="small" type="primary" :disabled="!canRegister" @click="registerTask">
                            Register
                        </n-button>
                    </n-space>
                </n-space>
            </n-modal>
        </template>

        <!-- Board create / edit modal -->
        <n-modal :show="editingBoard !== null" preset="card" class="boardForm"
                 :title="editingBoardIsNew ? 'New board' : `Edit board: ${editingBoard?.name}`"
                 style="max-width: 680px"
                 @update:show="(v: boolean) => { if (!v) editingBoard = null }">
            <n-space vertical :size="12" v-if="editingBoard">
                <n-input v-model:value="editingBoard.name" :disabled="!editingBoardIsNew" placeholder="Board name">
                    <template #prefix><span class="flabel">name</span></template>
                </n-input>
                <n-input v-model:value="editingBoard.description" placeholder="Description"/>
                <!-- The node the board builds (task RD2-4): required on New, editable on Edit; a refusal about
                     it (not a member of a perspective, archived, ...) is shown beside it. -->
                <div>
                    <n-select v-model:value="editingBoard.target" filterable :options="targetOptions"
                              :render-label="renderTargetOption" data-testid="board-target"
                              :status="boardFieldErrors.target ? 'error' : undefined"
                              placeholder="Target component"/>
                    <n-text v-if="boardFieldErrors.target" type="error" data-testid="board-target-error"
                            style="display: block; font-size: 12px;">
                        {{ boardFieldErrors.target }}
                    </n-text>
                    <n-text v-else-if="targetMissing(editingBoardIsNew, editingBoard.target)" depth="3"
                            data-testid="board-target-hint" style="display: block; font-size: 12px;">
                        Target component: {{ TARGET_HINT }}
                    </n-text>
                </div>
                <n-select v-model:value="editingBoard.sources" filterable multiple tag
                          placeholder="Wired sources, e.g. github:owner/repo (type + enter)"
                          :show-arrow="false" :show="false"/>
                <n-input-number v-model:value="editingBoard.perAgentWipLimit" :min="1">
                    <template #prefix><span class="flabel">per-agent WIP limit</span></template>
                </n-input-number>
                <n-select v-model:value="editingBoard.priorityType" :options="priorityOptions"
                          placeholder="Priority enforcement"/>
                <!-- Budget and stops (task 40f270be): the board file's settings, checked as a file's are.
                     A value emptied here is cleared; one left alone is not sent. -->
                <div class="flabel">budget and stops</div>
                <!-- Label above each field, two columns at 1280 (RD2-12): no placeholder is cut and every control
                     shows whole. Placeholders are the defaults a blank field takes. -->
                <div class="form-grid" data-testid="board-settings">
                    <label class="fcell"><span class="flabel">budget $</span>
                        <n-input-number v-model:value="editingBoard.budgetDollars" :min="0" :step="1" :precision="2"
                                        placeholder="none" data-testid="board-budget"/>
                    </label>
                    <label class="fcell"><span class="flabel">alert %</span>
                        <n-input-number v-model:value="editingBoard.softAlertPercent" :min="1" :max="100" placeholder="80"/>
                    </label>
                    <label class="fcell"><span class="flabel">cycle cap</span>
                        <n-input-number v-model:value="editingBoard.cycleCap" :min="1" placeholder="3"/>
                    </label>
                    <label class="fcell"><span class="flabel">no-progress stop</span>
                        <n-input-number v-model:value="editingBoard.noProgressRepeatsToStop" :min="1" placeholder="1"/>
                    </label>
                    <label class="fcell"><span class="flabel">blocking P≤</span>
                        <n-input-number v-model:value="editingBoard.blockingPriority" :min="1" :max="priorityLevels" placeholder="strict"/>
                    </label>
                    <label class="fcell"><span class="flabel">completion P≤</span>
                        <n-input-number v-model:value="editingBoard.completionPriority" :min="1" :max="priorityLevels" placeholder="strict"/>
                    </label>
                    <!-- task 28dc4afb: a task waiting on a person longer than this raises a queue-age
                         notification (AGENT_TASK_QUEUE_AGE); empty or 0 is off. -->
                    <label class="fcell"><span class="flabel">notify a person after, min</span>
                        <n-tooltip trigger="hover">
                            <template #trigger>
                                <n-input-number v-model:value="editingBoard.humanQueueAgeMinutes" :min="0" placeholder="off"
                                                data-testid="board-human-queue-age"/>
                            </template>
                            A task waiting on a person longer than this raises a notification; empty or 0 is off.
                        </n-tooltip>
                    </label>
                    <!-- task 04dedcc5: how long the event log keeps an event; 0 keeps everything. -->
                    <label class="fcell"><span class="flabel">keep events, days</span>
                        <n-input-number v-model:value="editingBoard.eventRetentionDays" :min="0" placeholder="15"
                                        data-testid="board-event-retention"/>
                    </label>
                    <!-- task RD3-4: when the board ALERTs that work went stale; blank is off. Only an ALERT. -->
                    <label v-for="f in STALENESS_FIELDS" :key="f.key" class="fcell"><span class="flabel">{{ f.label }}</span>
                        <n-tooltip trigger="hover">
                            <template #trigger>
                                <n-input-number v-model:value="editingBoard.stalenessDraft[f.key]" :min="1" placeholder="off"
                                                :data-testid="'board-staleness-' + f.key"/>
                            </template>
                            {{ f.help }}
                        </n-tooltip>
                    </label>
                    <!-- RD2-1: the level a task without its own reads; blank clears it. -->
                    <label class="fcell"><span class="flabel">default level</span>
                        <n-tooltip trigger="hover">
                            <template #trigger>
                                <n-input-number v-model:value="editingBoard.defaultTaskLevel" :min="0" :max="MAX_LEVEL" :precision="0"
                                                placeholder="none" data-testid="board-default-level"
                                                :status="boardFieldErrors.defaultTaskLevel ? 'error' : undefined"/>
                            </template>
                            {{ LEVEL_LADDER_HINT }}
                        </n-tooltip>
                    </label>
                </div>
                <n-text v-if="boardFieldErrors.defaultTaskLevel" type="error" data-testid="board-default-level-error"
                        style="font-size: 12px; margin-top: -6px;">{{ boardFieldErrors.defaultTaskLevel }}</n-text>
                <!-- task c0a2134c: on (the default, null) a no-progress or cycle-cap stop parks for the
                     coordinator first, which may release it once per stop kind per task or escalate it. -->
                <n-checkbox :checked="editingBoard.coordinatorStopRelease !== false" data-testid="board-stop-release"
                            @update:checked="(v: boolean) => { editingBoard.coordinatorStopRelease = v }">
                    the coordinator may release a no-progress or cycle-cap stop once per task
                </n-checkbox>
                <n-text depth="3" style="font-size: 11.5px; margin-top: -6px;">
                    Blank budget: no board limit. Blank priorities: strict, every open item counts. The
                    placeholders are the defaults a blank field takes. Events older than the days kept
                    are deleted daily; 0 keeps everything, and the log then grows without bound. Unchecked, every stop is the
                    operator's; budget stops always are.
                </n-text>
                <!-- task 18c5c293: how a task on this board proves it was delivered. -->
                <div class="flabel">delivery</div>
                <n-space :size="8" align="center" data-testid="board-delivery">
                    <n-select v-model:value="editingBoard.deliveryMode" :options="deliveryModeOptions" clearable
                              placeholder="PRs registered here (default)" size="small" style="min-width: 280px"/>
                    <n-checkbox v-if="editingBoard.deliveryMode === 'NONE'" v-model:checked="editingBoard.deliveryAttest">
                        wait for a push or release to be attested
                    </n-checkbox>
                </n-space>
                <n-text depth="3" style="font-size: 11.5px; margin-top: -6px;">{{ deliveryModeHelp }}</n-text>
                <!-- task 71a3dd22: who merges and how; blank fields take the defaults the placeholders name. -->
                <n-space :size="8" align="center" data-testid="board-merge">
                    <n-select v-model:value="editingBoard.merge.by" :options="mergeByOptions" clearable size="small"
                              :placeholder="'merged by: ' + effectiveMergeBy" style="min-width: 240px"/>
                    <n-input v-if="editingBoard.merge.by === 'ROLE'" v-model:value="editingBoard.merge.byRole"
                             placeholder="role name" size="small" style="width: 140px"/>
                    <n-select v-model:value="editingBoard.merge.method" :options="mergeMethodOptions" clearable
                              placeholder="merge commit (default)" size="small" style="min-width: 220px"/>
                    <n-select v-model:value="editingBoard.merge.order" :options="mergeOrderOptions" clearable
                              placeholder="as the notes say (default)" size="small" style="min-width: 220px"/>
                    <n-checkbox v-model:checked="editingBoard.merge.atTestedHead">only at the tested head</n-checkbox>
                    <n-checkbox :checked="editingBoard.deliveryMode === 'ATTESTED' || editingBoard.merge.requireAttestation"
                                :disabled="editingBoard.deliveryMode === 'ATTESTED'"
                                @update:checked="(v: boolean) => { editingBoard.merge.requireAttestation = v }">
                        attest every merge
                    </n-checkbox>
                </n-space>
                <n-text depth="3" style="font-size: 11.5px; margin-top: -6px;">{{ mergeByHelp }}</n-text>
                <!-- What the coordinator seat does itself, e.g. merging once the last required role
                     has passed. The tracker verbs are always the coordinator's, so they are not offered. -->
                <n-select v-model:value="editingBoard.coordinatorCapabilities" multiple
                          :options="coordinatorCapabilityOptions"
                          placeholder="Coordinator covers (e.g. PR_MERGE when it merges)"/>
                <!-- Perspectives the board hangs off (board-permissions.md §3): the target must be a member
                     of each, and adding or removing one needs BOARD_WRITE and CONFIGURATION_WRITE on it. -->
                <n-select v-model:value="editingBoard.perspectives" multiple filterable
                          :options="perspectiveOptions" data-testid="board-perspectives"
                          placeholder="Perspectives (a grant on any of them covers the board)"/>
                <n-input v-model:value="editingBoard.documentsRepo"
                         placeholder="Documents repository, e.g. https://github.com/acme/docs">
                    <template #prefix><span class="flabel">documents repo</span></template>
                </n-input>
                <n-text depth="3" style="font-size: 11.5px; margin-top: -6px;">
                    A git URI on any host, resolved once to a repository record — so a remote
                    written as ssh on one machine and https on another is the same repository. It
                    must correspond to one of the sources above, which is what puts document writes
                    under the coordinator's rogue-activity watch; a source written as
                    <code>github:acme/docs</code> matches <code>https://github.com/acme/docs</code>.
                </n-text>
                <!-- Task keys and document naming (board-documents.md §5; task fceb1e57). A refusal on
                     save is shown beside the field it is about. -->
                <div data-testid="board-task-prefix">
                    <n-input v-model:value="editingBoard.taskPrefix" :maxlength="8"
                             :placeholder="editingBoardIsNew ? taskPrefixPlaceholder(editingBoard.name) : 'unchanged'"
                             :status="boardFieldErrors.taskPrefix || taskPrefixProblem(editingBoard.taskPrefix) ? 'error' : undefined">
                        <template #prefix><span class="flabel">task-key prefix</span></template>
                    </n-input>
                    <n-text depth="3" style="font-size: 11.5px;">
                        Tasks are keyed <code>{{ normaliseTaskPrefix(editingBoard.taskPrefix) || (editingBoardIsNew ? derivedTaskPrefix(editingBoard.name) : editingBoard.heldTaskPrefix) }}-1</code>,
                        <code>-2</code>… A change is a rename: existing keys stay and still resolve, and a
                        prefix is never reused in the organization.
                        <template v-if="priorTaskPrefixes(editingBoard.taskPrefixHistory, editingBoard.heldTaskPrefix).length">
                            Held before: {{ priorTaskPrefixes(editingBoard.taskPrefixHistory, editingBoard.heldTaskPrefix).join(', ') }}.
                        </template>
                    </n-text>
                    <n-text v-if="boardFieldErrors.taskPrefix || taskPrefixProblem(editingBoard.taskPrefix)" type="error"
                            data-testid="board-task-prefix-error" style="display: block; font-size: 12px;">
                        {{ boardFieldErrors.taskPrefix || taskPrefixProblem(editingBoard.taskPrefix) }}
                    </n-text>
                </div>
                <div data-testid="board-documents">
                    <div class="flabel" style="margin-bottom: 4px">documents</div>
                    <n-space :size="8" align="center">
                        <n-input v-model:value="editingBoard.documentsDraft.prefix" size="small" style="width: 260px"
                                 :placeholder="slug(editingBoard.name) || 'the board name, slugged'">
                            <template #prefix><span class="flabel">name prefix</span></template>
                        </n-input>
                        <n-checkbox v-model:checked="editingBoard.documentsDraft.shared">repository shared by several boards</n-checkbox>
                        <n-checkbox v-model:checked="editingBoard.documentsDraft.rootSet">set the root</n-checkbox>
                        <n-input v-model:value="editingBoard.documentsDraft.root" size="small" style="width: 260px"
                                 :disabled="!editingBoard.documentsDraft.rootSet"
                                 :placeholder="editingBoard.documentsDraft.rootSet ? 'empty is the repository root' : documentsRootPlaceholder(editingBoard.documentsDraft.shared)">
                            <template #prefix><span class="flabel">root</span></template>
                        </n-input>
                    </n-space>
                    <n-text depth="3" style="font-size: 11.5px;">
                        The name prefix replaces the board name in its document components' names. A
                        shared repository puts this board under <code>boards/{board}/</code> unless a
                        root is set; a set root may be empty, the repository's own root.
                        <template v-if="!editingBoardIsNew">Resolved now: <code>{{ editingBoard.documentsRoot || '(the repository root)' }}</code>.</template>
                    </n-text>
                    <n-text v-if="boardFieldErrors.documents" type="error" data-testid="board-documents-error"
                            style="display: block; font-size: 12px;">
                        {{ boardFieldErrors.documents }}
                    </n-text>
                </div>
                <div>
                    <div class="flabel" style="margin-bottom: 4px">document path templates</div>
                    <n-input v-for="row in templateTypeRows" :key="row.spec"
                             v-model:value="editingBoard.documentPaths[row.spec]"
                             :placeholder="row.placeholder" style="margin-bottom: 4px;">
                        <template #prefix><span class="flabel">{{ row.spec.toLowerCase().replace(/_/g, ' ') }}</span></template>
                    </n-input>
                    <n-text depth="3" style="font-size: 11.5px;">
                        Placeholders: <template v-for="p in PATH_PLACEHOLDERS" :key="p"><code>{{ p }}</code> </template>
                        (<code>{task}</code> is read as <code>{key}</code>, the task's key). Blank
                        uses the default shown, which the server picks by scope: per task for a type
                        a role produces per task, else one file per component.
                        <template v-if="documentsRootNote(editingBoard.documentsRoot)">
                            {{ documentsRootNote(editingBoard.documentsRoot) }}
                        </template>
                    </n-text>
                </div>
                <n-checkbox v-if="editingBoardIsNew" v-model:checked="editingBoard.seedFromPresets">
                    seed roles from org presets (the coordinator prompt comes from
                    <code>{{ coordinatorPresetFor(editingBoard) }}</code>, by whether sources are wired)
                </n-checkbox>
                <div>
                    <div class="flabel" style="margin-bottom: 4px">coordinator prompt (implicit role — always present)</div>
                    <n-input v-model:value="editingBoard.coordinatorPrompt" type="textarea"
                             :autosize="{ minRows: 6, maxRows: 16 }"/>
                    <!--
                        Two prompts, and nothing switches automatically when a board gains or
                        loses sources: the prompt is operator-curated text, and rewriting it
                        under them because they wired a tracker would be the wrong kind of helpful.
                    -->
                    <n-space v-if="!editingBoardIsNew" align="center" style="margin-top: 6px">
                        <n-text depth="3" style="font-size: 12px">
                            reseed from a preset:
                        </n-text>
                        <n-button size="tiny" :loading="reseeding"
                                  @click="reseedCoordinator(coordinatorPresetFor(editingBoard))">
                            {{ coordinatorPresetFor(editingBoard) }}
                        </n-button>
                        <n-button size="tiny" quaternary :loading="reseeding"
                                  @click="reseedCoordinator(otherCoordinatorPreset(editingBoard))">
                            {{ otherCoordinatorPreset(editingBoard) }}
                        </n-button>
                    </n-space>
                </div>
                <AiAgentRevisionHistory v-if="canReadHistory && !editingBoardIsNew && editingBoard.uuid" kind="board"
                                        :uuid="editingBoard.uuid" :current="boards.find(b => b.uuid === editingBoard.uuid)"/>
                <n-space justify="end">
                    <n-button quaternary @click="editingBoard = null">Cancel</n-button>
                    <n-button type="primary" :loading="saving" data-testid="board-save"
                              :disabled="targetMissing(editingBoardIsNew, editingBoard.target)" @click="saveBoard">Save</n-button>
                </n-space>
            </n-space>
        </n-modal>

        <!-- Role config modal -->
        <n-modal :show="showSpec" preset="card" title="Board as a spec" style="max-width: 900px"
                 @update:show="(v: boolean) => showSpec = v">
            <p class="hintText">
                The board as configuration — what it builds, how work is routed, and the prompts
                that define each role. Tasks are deliberately absent: they are the work, not the
                workflow.
            </p>
            <n-space size="small" style="margin-bottom: 8px;">
                <n-radio-group v-model:value="specFormat" size="small">
                    <n-radio-button value="yaml">YAML</n-radio-button>
                    <n-radio-button value="json">JSON</n-radio-button>
                </n-radio-group>
                <n-button size="small" @click="copySpec" :disabled="!specText">Copy</n-button>
            </n-space>
            <n-spin :show="specLoading">
                <!-- A refusal in place of the spec, never an empty block (RD2-6). -->
                <n-alert v-if="specError" type="warning" :bordered="false" data-testid="spec-error">{{ specError }}</n-alert>
                <pre v-else class="specBlock">{{ specText }}</pre>
            </n-spin>
        </n-modal>

        <n-modal :show="showRoles" preset="card" title="Board roles" style="max-width: 1040px"
                 @update:show="(v: boolean) => showRoles = v">
            <p class="hint">
                Roles are served prompts plus advisory routing order — any agent can assume any
                role; the coordinator routes each hop. The coordinator itself is implicit and not
                configurable here. Sign-offs pin the prompt version they ran under.
            </p>
            <n-data-table :columns="roleColumns" :data="sortedRoles" :row-key="(r: any) => r.uuid ?? r.name" size="small"/>
            <n-button v-if="canConfigure(currentBoard)" class="addbtn" size="small" dashed @click="startAddRole">+ Add role</n-button>

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
                    <n-input-number v-if="editingRole.kind !== 'HUMAN'" v-model:value="editingRole.wipLimit"
                                    :min="0" placeholder="0 = uncapped">
                        <template #prefix><span class="flabel">role WIP limit</span></template>
                    </n-input-number>
                    <n-input-number v-if="editingRole.kind !== 'HUMAN'" v-model:value="editingRole.hopDollars"
                                    :min="0" :precision="2" placeholder="no allowance">
                        <template #prefix><span class="flabel">hop allowance $</span></template>
                    </n-input-number>
                    <div>
                        <div class="flabel" style="margin-bottom: 4px">worked by</div>
                        <n-radio-group v-model:value="editingRole.kind" size="small">
                            <n-radio-button value="AGENTIC">
                                <span style="display: inline-flex; align-items: center;">
                                    AGENTIC
                                    <n-tooltip trigger="hover">
                                        <template #trigger>
                                            <n-icon size="16" style="margin-left: 4px;">
                                                <QuestionCircle20Regular />
                                            </n-icon>
                                        </template>
                                        Worked by agents: the role is offered on poll, assigned to a session, and signed off by the agent that assumed it.
                                    </n-tooltip>
                                </span>
                            </n-radio-button>
                            <n-radio-button value="HUMAN">
                                <span style="display: inline-flex; align-items: center;">
                                    HUMAN
                                    <n-tooltip trigger="hover">
                                        <template #trigger>
                                            <n-icon size="16" style="margin-left: 4px;">
                                                <QuestionCircle20Regular />
                                            </n-icon>
                                        </template>
                                        A human workflow stage: never offered to agent polls and never assignable — an org admin signs off directly from the queue. WIP, distinct-agent and capabilities do not apply.
                                    </n-tooltip>
                                </span>
                            </n-radio-button>
                        </n-radio-group>
                    </div>
                    <div>
                        <div class="flabel" style="margin-bottom: 4px">coordinator pass</div>
                        <n-radio-group v-model:value="editingRole.necessity" size="small">
                            <n-radio-button value="OPTIONAL">
                                <span style="display: inline-flex; align-items: center;">
                                    OPTIONAL
                                    <n-tooltip trigger="hover">
                                        <template #trigger>
                                            <n-icon size="16" style="margin-left: 4px;">
                                                <QuestionCircle20Regular />
                                            </n-icon>
                                        </template>
                                        The coordinator may skip this role — routing through it is its judgment, per task.
                                    </n-tooltip>
                                </span>
                            </n-radio-button>
                            <n-radio-button value="REQUIRED">
                                <span style="display: inline-flex; align-items: center;">
                                    REQUIRED
                                    <n-tooltip trigger="hover">
                                        <template #trigger>
                                            <n-icon size="16" style="margin-left: 4px;">
                                                <QuestionCircle20Regular />
                                            </n-icon>
                                        </template>
                                        Unskippable: no task completes unless its most recent sign-off in this role is PASSED. Cancelled tasks and split parents are exempt.
                                    </n-tooltip>
                                </span>
                            </n-radio-button>
                        </n-radio-group>
                    </div>
                    <div>
                        <div class="flabel" style="margin-bottom: 4px">human gate</div>
                        <n-radio-group v-model:value="editingRole.humanGate" size="small">
                            <n-radio-button value="NONE"
                                           :disabled="editingRole.kind === 'HUMAN'">
                                <span style="display: inline-flex; align-items: center;">
                                    NONE
                                    <n-tooltip trigger="hover">
                                        <template #trigger>
                                            <n-icon size="16" style="margin-left: 4px;">
                                                <QuestionCircle20Regular />
                                            </n-icon>
                                        </template>
                                        No human review of this role's sign-offs.
                                    </n-tooltip>
                                </span>
                            </n-radio-button>
                            <n-radio-button value="ON_PASS"
                                           :disabled="editingRole.kind === 'HUMAN'">
                                <span style="display: inline-flex; align-items: center;">
                                    ON PASS
                                    <n-tooltip trigger="hover">
                                        <template #trigger>
                                            <n-icon size="16" style="margin-left: 4px;">
                                                <QuestionCircle20Regular />
                                            </n-icon>
                                        </template>
                                        A PASSED sign-off in this role parks the task for human review — and only then, so a task never routed through this role never pauses.
                                    </n-tooltip>
                                </span>
                            </n-radio-button>
                            <n-radio-button value="ON_ANY_SIGNOFF"
                                           :disabled="editingRole.kind === 'HUMAN'">
                                <span style="display: inline-flex; align-items: center;">
                                    ANY
                                    <n-tooltip trigger="hover">
                                        <template #trigger>
                                            <n-icon size="16" style="margin-left: 4px;">
                                                <QuestionCircle20Regular />
                                            </n-icon>
                                        </template>
                                        Every sign-off in this role parks the task for human review, rejections included (human arbitration of bounces).
                                    </n-tooltip>
                                </span>
                            </n-radio-button>
                        </n-radio-group>
                    </div>
                    <n-space :size="18" v-if="editingRole.kind !== 'HUMAN'">
                        <n-checkbox v-model:checked="editingRole.requireDistinctAgent">require distinct agent</n-checkbox>
                        <n-tooltip trigger="hover">
                            <template #trigger>
                                <n-checkbox v-model:checked="editingRole.blindReview">blind review</n-checkbox>
                            </template>
                            The session in this role reads its task without the earlier hops' notes, sessions and
                            agents: it reviews the work, not the worker's account of it.
                        </n-tooltip>
                        <n-checkbox v-model:checked="editingRole.active">active</n-checkbox>
                    </n-space>
                    <n-checkbox v-else v-model:checked="editingRole.active">active</n-checkbox>
                    <n-select v-if="editingRole.kind !== 'HUMAN'" v-model:value="editingRole.requiredCapabilities" multiple
                              :options="capabilityOptions"
                              placeholder="Required capabilities (declared, unverified in v1)"/>
                    <div v-if="editingRole.kind !== 'HUMAN'">
                        <div class="flabel" style="margin-bottom: 4px">documents this role must publish</div>
                        <n-select v-model:value="editingRole.producesOutputTypes" multiple
                                  :options="outputTypeOptions"
                                  placeholder="None — the role hands over a sign-off note only"/>
                        <n-text depth="3" style="font-size: 11.5px;">
                            Enforced at sign-off, not at assignment: the hop has to run before it
                            can produce anything, so the refusal lands on the hop that can still fix
                            it. A HUMAN role is exempt — a person reviewing here does not publish
                            through the CLI.
                        </n-text>
                    </div>
                    <RoleStrengthEditor v-if="editingRole.kind !== 'HUMAN'" v-model:strength="editingRole.strength"
                                        :models="models"/>
                    <div>
                        <div class="flabel" style="margin-bottom: 4px">{{ editingRole.kind === 'HUMAN'
                            ? 'reviewer guidance (shown to the human in the UI)'
                            : 'role prompt (served to the assuming agent)' }}</div>
                        <n-input v-model:value="editingRole.prompt" type="textarea" :autosize="{ minRows: 8, maxRows: 20 }"/>
                    </div>
                    <AiAgentRevisionHistory v-if="canReadHistory && !editingRoleIsNew && editingRole.uuid" kind="role"
                                            :uuid="editingRole.uuid" :current="roles.find(r => r.uuid === editingRole.uuid)"/>
                    <n-space justify="end">
                        <n-button quaternary @click="editingRole = null">Cancel</n-button>
                        <n-button type="primary" :loading="saving" @click="saveRole">Save</n-button>
                    </n-space>
                </n-space>
            </n-modal>
        </n-modal>

        <!-- Org role presets (operator library; boards seed from these) -->
        <n-modal :show="showPresets" preset="card" title="Org role presets" style="max-width: 1040px"
                 @update:show="(v: boolean) => showPresets = v">
            <p class="hint">
                Operator-curated library copied onto new boards (copy semantics — edits here do not
                ripple to existing boards). The presets named "coordinator-tracker" and
                "coordinator-board-truth" seed a new board's coordinator prompt, by whether it has sources.
            </p>
            <n-data-table :columns="presetColumns" :data="sortedPresets" :row-key="(r: any) => r.uuid ?? r.name" size="small"/>
            <n-space class="addbtn" :size="8">
                <n-button size="small" dashed @click="startAddPreset">+ Add preset</n-button>
                <n-button v-if="canApplySpec" size="small" dashed @click="applyKinds = ['ROLE_PRESETS']">
                    Apply presets file
                </n-button>
            </n-space>

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
                    <n-input-number v-if="editingPreset.kind !== 'HUMAN'" v-model:value="editingPreset.wipLimit"
                                    :min="0" placeholder="0 = uncapped">
                        <template #prefix><span class="flabel">role WIP limit</span></template>
                    </n-input-number>
                    <n-input-number v-if="editingPreset.kind !== 'HUMAN'" v-model:value="editingPreset.hopDollars"
                                    :min="0" :precision="2" placeholder="no allowance">
                        <template #prefix><span class="flabel">hop allowance $</span></template>
                    </n-input-number>
                    <div>
                        <div class="flabel" style="margin-bottom: 4px">worked by</div>
                        <n-radio-group v-model:value="editingPreset.kind" size="small">
                            <n-radio-button value="AGENTIC">
                                <span style="display: inline-flex; align-items: center;">
                                    AGENTIC
                                    <n-tooltip trigger="hover">
                                        <template #trigger>
                                            <n-icon size="16" style="margin-left: 4px;">
                                                <QuestionCircle20Regular />
                                            </n-icon>
                                        </template>
                                        Worked by agents: the role is offered on poll, assigned to a session, and signed off by the agent that assumed it.
                                    </n-tooltip>
                                </span>
                            </n-radio-button>
                            <n-radio-button value="HUMAN">
                                <span style="display: inline-flex; align-items: center;">
                                    HUMAN
                                    <n-tooltip trigger="hover">
                                        <template #trigger>
                                            <n-icon size="16" style="margin-left: 4px;">
                                                <QuestionCircle20Regular />
                                            </n-icon>
                                        </template>
                                        A human workflow stage: never offered to agent polls and never assignable — an org admin signs off directly from the queue. WIP, distinct-agent and capabilities do not apply.
                                    </n-tooltip>
                                </span>
                            </n-radio-button>
                        </n-radio-group>
                    </div>
                    <div>
                        <div class="flabel" style="margin-bottom: 4px">coordinator pass</div>
                        <n-radio-group v-model:value="editingPreset.necessity" size="small">
                            <n-radio-button value="OPTIONAL">
                                <span style="display: inline-flex; align-items: center;">
                                    OPTIONAL
                                    <n-tooltip trigger="hover">
                                        <template #trigger>
                                            <n-icon size="16" style="margin-left: 4px;">
                                                <QuestionCircle20Regular />
                                            </n-icon>
                                        </template>
                                        The coordinator may skip this role — routing through it is its judgment, per task.
                                    </n-tooltip>
                                </span>
                            </n-radio-button>
                            <n-radio-button value="REQUIRED">
                                <span style="display: inline-flex; align-items: center;">
                                    REQUIRED
                                    <n-tooltip trigger="hover">
                                        <template #trigger>
                                            <n-icon size="16" style="margin-left: 4px;">
                                                <QuestionCircle20Regular />
                                            </n-icon>
                                        </template>
                                        Unskippable: no task completes unless its most recent sign-off in this role is PASSED. Cancelled tasks and split parents are exempt.
                                    </n-tooltip>
                                </span>
                            </n-radio-button>
                        </n-radio-group>
                    </div>
                    <div>
                        <div class="flabel" style="margin-bottom: 4px">human gate</div>
                        <n-radio-group v-model:value="editingPreset.humanGate" size="small">
                            <n-radio-button value="NONE"
                                           :disabled="editingPreset.kind === 'HUMAN'">
                                <span style="display: inline-flex; align-items: center;">
                                    NONE
                                    <n-tooltip trigger="hover">
                                        <template #trigger>
                                            <n-icon size="16" style="margin-left: 4px;">
                                                <QuestionCircle20Regular />
                                            </n-icon>
                                        </template>
                                        No human review of this role's sign-offs.
                                    </n-tooltip>
                                </span>
                            </n-radio-button>
                            <n-radio-button value="ON_PASS"
                                           :disabled="editingPreset.kind === 'HUMAN'">
                                <span style="display: inline-flex; align-items: center;">
                                    ON PASS
                                    <n-tooltip trigger="hover">
                                        <template #trigger>
                                            <n-icon size="16" style="margin-left: 4px;">
                                                <QuestionCircle20Regular />
                                            </n-icon>
                                        </template>
                                        A PASSED sign-off in this role parks the task for human review — and only then, so a task never routed through this role never pauses.
                                    </n-tooltip>
                                </span>
                            </n-radio-button>
                            <n-radio-button value="ON_ANY_SIGNOFF"
                                           :disabled="editingPreset.kind === 'HUMAN'">
                                <span style="display: inline-flex; align-items: center;">
                                    ANY
                                    <n-tooltip trigger="hover">
                                        <template #trigger>
                                            <n-icon size="16" style="margin-left: 4px;">
                                                <QuestionCircle20Regular />
                                            </n-icon>
                                        </template>
                                        Every sign-off in this role parks the task for human review, rejections included (human arbitration of bounces).
                                    </n-tooltip>
                                </span>
                            </n-radio-button>
                        </n-radio-group>
                    </div>
                    <n-space :size="18" v-if="editingPreset.kind !== 'HUMAN'">
                        <n-checkbox v-model:checked="editingPreset.requireDistinctAgent">require distinct agent</n-checkbox>
                        <n-checkbox v-model:checked="editingPreset.blindReview">blind review</n-checkbox>
                        <n-checkbox v-model:checked="editingPreset.active">active</n-checkbox>
                    </n-space>
                    <n-checkbox v-else v-model:checked="editingPreset.active">active</n-checkbox>
                    <n-select v-if="editingPreset.kind !== 'HUMAN'" v-model:value="editingPreset.requiredCapabilities" multiple
                              :options="capabilityOptions" placeholder="Required capabilities"/>
                    <RoleStrengthEditor v-if="editingPreset.kind !== 'HUMAN'" v-model:strength="editingPreset.strength"
                                        :models="models"/>
                    <div>
                        <div class="flabel" style="margin-bottom: 4px">prompt</div>
                        <n-input v-model:value="editingPreset.prompt" type="textarea" :autosize="{ minRows: 8, maxRows: 20 }"/>
                    </div>
                    <AiAgentRevisionHistory v-if="canReadPresetHistory && !editingPresetIsNew && editingPreset.uuid" kind="role"
                                            :uuid="editingPreset.uuid" :current="presets.find(p => p.uuid === editingPreset.uuid)"/>
                    <n-space justify="end">
                        <n-button quaternary @click="editingPreset = null">Cancel</n-button>
                        <n-button type="primary" :loading="saving" @click="savePreset">Save</n-button>
                    </n-space>
                </n-space>
            </n-modal>
        </n-modal>
        <DeclarativeApplyModal :show="applyKinds !== null" :org-uuid="props.orgUuid" :kinds="applyKinds ?? ['BOARD']"
                               @close="applyKinds = null" @applied="onSpecApplied"/>
    </div>
</template>

<script lang="ts" setup>
import { computed, defineComponent, h, nextTick, onBeforeUnmount, onMounted, provide, ref, watch } from 'vue'
import type { ComputedRef } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { useStore } from 'vuex'
import { NAlert, NButton, NCard, NCheckbox, NCollapse, NCollapseItem, NDataTable, NIcon, NInput, NInputNumber, NModal, NRadioButton, NRadioGroup, NSelect, NSpace, NSpin, NTabPane, NTabs, NTag, NTooltip, DataTableColumns, useNotification, NText } from 'naive-ui'
import { QuestionCircle20Regular } from '@vicons/fluent'
import AiAgentTaskPertView from '@/components/AiAgentTaskPertView.vue'
import AiAgentTaskTimelineView from '@/components/AiAgentTaskTimelineView.vue'
import AiAgentTaskTableView from '@/components/AiAgentTaskTableView.vue'
import AgentBoardUsagePanel from '@/components/AgentBoardUsagePanel.vue'
import AgentTime from '@/components/AgentTime.vue'
import AgentBoardDocumentsPanel from '@/components/AgentBoardDocumentsPanel.vue'
import AgentBoardGroupsPanel from '@/components/AgentBoardGroupsPanel.vue'
import { budgetChip, hopBudgetInput, microsToDollars, settingsDraftOf, settingsPatch } from '@/utils/agentBudget'
import { STALENESS_FIELDS, stalenessDraftOf, stalenessPatch, stalenessProblem } from '@/utils/agentStaleness'
import { actorLabel } from '@/utils/agentActors'
import { refLabel, roleTagFor, subtaskProgress, subtaskTag, waitsOnLabel } from '@/utils/agentTaskLabels'
import { columnFolded, EMPTY_BOARD_HINT, moreLanesHint, stripMeasure } from '@/utils/agentKanban'
import { CAPABILITIES, COORDINATOR_CAPABILITIES, toOptions } from '@/utils/agentCapabilities'
import { documentsRootNote, PATH_PLACEHOLDERS, templateRows } from '@/utils/agentDocuments'
import { boardFieldOfError, derivedTaskPrefix, documentsDraftOf, documentsPatch, documentsRootPlaceholder,
    normaliseTaskPrefix, priorTaskPrefixes, slug, taskDescriptionProblem, taskPrefixPatch, taskRegisterInput,
    taskTitleProblem, taskPrefixPlaceholder, taskPrefixProblem } from '@/utils/agentBoardNaming'
import { isOrgAdmin } from '@/utils/agentReopen'
import { AGENT_DIR, AgentName, COORDINATOR_SEAT, agentDirectory, agentLabel, agentNamesOf } from '@/utils/agentSessionLabel'
import { hiddenBoardText, noBoardsText } from '@/utils/agentAccessMessages'
import { boardPickerOptions, renderBoardOption, reviewBannerLabel } from '@/utils/agentTaskKeys'
import { boardTargetOptions, targetChip, targetMissing, targetOf, targetOptionType, targetPatch, TARGET_HINT } from '@/utils/agentBoardTarget'
import { boardCan, canConfigure, canConfigureRead, canOperate, specRefusal, subscribeOffer } from '@/utils/agentBoardAccess'
import { boardWarningShown, coverageLines } from '@/utils/agentBoardCoverage'
import { defaultLevelPatch, GROUP_BY_OPTIONS, groupByFromQuery, groupTasks, LEVEL_LADDER_HINT, levelFromQuery, levelLabel, levelOf,
    levelPlaceholder, levelTooltip, MAX_LEVEL, withLevelQuery } from '@/utils/agentTaskLevel'
import { groupColour, groupFromQuery, groupLabel, groupByKey, groupOptions, NO_GROUP, parseTags, passesGroupAndTag,
    registerGroupFields, tagFromQuery, tagOptions, tagsProblem, waitingOnLabel, withGroupQuery } from '@/utils/agentTaskGroups'
import { boardPerspectiveOptions, perspectiveChips, perspectivesPatch } from '@/utils/agentBoardPerspectives'
import { DELIVERY_MODE_OPTIONS, MERGE_BY_OPTIONS, MERGE_METHOD_OPTIONS, MERGE_ORDER_OPTIONS, deliveryPolicyPatch, mergeDraftOf,
    prChips } from '@/utils/agentDelivery'

/**
 * Types the board editor offers a template for. The task-scoped pair, because those are the ones
 * an agent publishes per round and therefore the ones whose layout an operator actually chooses;
 * component-scoped documents share one shape and are rarely per-board.
 */
// The board being edited is the selected one when it is not new, and then its roles are loaded;
// a new board has none yet, so only the index types are listed.
const templateTypeRows = computed(() => templateRows(
    editingBoard.value && !editingBoardIsNew.value && editingBoard.value.uuid === selectedBoard.value ? roles.value : [],
    editingBoard.value?.effectiveDocumentPaths))
import AiAgentTaskDetailDrawer from '@/components/AiAgentTaskDetailDrawer.vue'
import BoardLockControl from '@/components/BoardLockControl.vue'
import { useAgentTaskActions } from '@/utils/agentTaskActions'
import { taskPagePath, ts } from '@/utils/agentTaskFormat'
import AiAgentRevisionHistory from '@/components/AiAgentRevisionHistory.vue'
import DeclarativeApplyModal from '@/components/DeclarativeApplyModal.vue'
import type { SpecKind } from '@/utils/declarativeSpec'
import RoleStrengthEditor from '@/components/RoleStrengthEditor.vue'
import { mergeOutputs, strengthDraft, strengthInput, strengthSummary } from '@/utils/roleStrength'

const props = defineProps<{ orgUuid: string }>()

const store = useStore()
const notification = useNotification()
const route = useRoute()
const router = useRouter()

// Board selection and view live in the query string so a board (and
// the view you were looking at) is linkable and survives a reload.
const BOARD_VIEWS = ['kanban', 'pert', 'timeline', 'table', 'groups']
const boardView = ref<string>(
    BOARD_VIEWS.includes(route.query.view as string) ? (route.query.view as string) : 'kanban')

function syncQuery () {
    const q: Record<string, string> = withGroupQuery(withLevelQuery({ ...(route.query as Record<string, string>), tab: 'boards' },
        groupBy.value, levelFilter.value), groupFilter.value, tagFilter.value)
    if (selectedBoard.value) q.board = selectedBoard.value
    else delete q.board
    q.view = boardView.value
    router.replace({ query: q }).catch(() => { /* duplicate navigation is fine */ })
}

// The kanban's grouping and level filter (RD2-1), read from and written to the URL with the board.
const groupBy = ref<string>(groupByFromQuery(route.query))
const levelFilter = ref<number | null>(levelFromQuery(route.query))
function setGroupBy (v: string) {
    groupBy.value = v
    syncQuery()
}
function setLevelFilter (v: number | null) {
    levelFilter.value = v ?? null
    syncQuery()
}
const levelFilterOptions = Array.from({ length: MAX_LEVEL + 1 }, (_, i) => ({ label: `L${i}`, value: i }))

// The group and tag filters (RD2-31), shared by the kanban and the table and kept in the URL.
const groupFilter = ref<string | null>(groupFromQuery(route.query))
const tagFilter = ref<string | null>(tagFromQuery(route.query))
function setGroupFilter (v: string | null) {
    groupFilter.value = v ?? null
    syncQuery()
}
function setTagFilter (v: string | null) {
    tagFilter.value = v ?? null
    syncQuery()
}
const groupFilterOptions = computed(() => [{ label: 'ungrouped', value: NO_GROUP }, ...groupOptions(currentBoard.value)])
const tagFilterOptions = computed(() => tagOptions(tasks.value))

function setBoardView (v: string) {
    boardView.value = v
    syncQuery()
}

const boards = ref<any[]>([])
const selectedBoard = ref<string | null>(null)
const tasks = ref<any[]>([])
// The kanban's tasks under the level filter, in lanes by the grouping (one lane for "none").
const kanbanLanes = computed(() => groupTasks(
    tasks.value.filter(t => (levelFilter.value == null || levelOf(t, currentBoard.value) === levelFilter.value)
        && passesGroupAndTag(t, groupFilter.value, tagFilter.value)),
    groupBy.value, currentBoard.value))
const roles = ref<any[]>([])
const showRoles = ref(false)

// --- the board as a spec -------------------------------------------------
// Rendered from the server's own export rather than assembled here, so what the
// page shows is the document a client would receive, not a lookalike.
const showSpec = ref(false)
const specLoading = ref(false)
const specFormat = ref<'yaml' | 'json'>('yaml')
const specRaw = ref<any>(null)
const specError = ref<string | null>(null)

function stripNulls (v: any): any {
    if (Array.isArray(v)) return v.map(stripNulls).filter(x => x !== null && x !== undefined)
    if (v && typeof v === 'object') {
        const out: any = {}
        Object.keys(v).filter(k => k !== '__typename').forEach(k => {
            const c = stripNulls(v[k])
            const empty = c === null || c === undefined || (Array.isArray(c) && !c.length)
            if (!empty) out[k] = c
        })
        return out
    }
    return v
}

/** Minimal YAML for the shapes a spec contains: maps, lists and scalars. */
function toYaml (v: any, indent = 0): string {
    const pad = '  '.repeat(indent)
    if (Array.isArray(v)) {
        if (!v.length) return ''
        return v.map(item => {
            if (item && typeof item === 'object') {
                const body = toYaml(item, indent + 1)
                return `${pad}-\n${body}`
            }
            return `${pad}- ${scalar(item)}`
        }).join('\n')
    }
    if (v && typeof v === 'object') {
        return Object.keys(v).map(k => {
            const c = v[k]
            if (c && typeof c === 'object') {
                const body = toYaml(c, indent + 1)
                return body ? `${pad}${k}:\n${body}` : `${pad}${k}:`
            }
            return `${pad}${k}: ${scalar(c)}`
        }).join('\n')
    }
    return `${pad}${scalar(v)}`
}

function scalar (v: any): string {
    if (typeof v !== 'string') return String(v)
    // prompts are multi-line and must not be mistaken for structure
    if (v.includes('\n')) return '|\n' + v.split('\n').map(l => '  ' + l).join('\n')
    return /[:#]|^\s|\s$/.test(v) ? JSON.stringify(v) : v
}

const specText: ComputedRef<string> = computed(() => {
    if (!specRaw.value) return ''
    const clean = stripNulls(specRaw.value)
    return specFormat.value === 'json' ? JSON.stringify(clean, null, 2) : toYaml(clean)
})

/** Opens the org's subscriptions with a new subscription pre-filled for this board (82880ea6). */
function subscribeToBoard () {
    if (!currentBoard.value) return
    router.push({
        name: 'OrgSettings',
        params: { orguuid: props.orgUuid },
        query: { tab: 'integrations', integrationsTab: 'subscriptions', newBoardSub: currentBoard.value.uuid },
    })
}

async function openSpec () {
    if (!currentBoard.value) return
    showSpec.value = true
    specLoading.value = true
    specRaw.value = null
    specError.value = null
    try {
        specRaw.value = await store.dispatch('fetchAgentBoardSpec', currentBoard.value.uuid)
    } catch (e: any) {
        // Said in the modal: the person asked to see the spec, and an empty block explains nothing.
        specError.value = specRefusal(e)
    } finally {
        specLoading.value = false
    }
}

async function copySpec () {
    try {
        await navigator.clipboard.writeText(specText.value)
        notify('success', 'Copied', 'The spec is on your clipboard')
    } catch (e: any) {
        notify('error', 'Could not copy', e?.message ?? String(e))
    }
}
const editingBoard = ref<any>(null)
const deliveryModeOptions = DELIVERY_MODE_OPTIONS.map(o => ({ label: o.label, value: o.value }))
const deliveryModeHelp = computed(() => (DELIVERY_MODE_OPTIONS.find(o => o.value === (editingBoard.value?.deliveryMode ?? 'PR_ROWS'))
    ?? DELIVERY_MODE_OPTIONS[0]).help)
const mergeByOptions = MERGE_BY_OPTIONS.map(o => ({ label: o.label, value: o.value }))
const mergeMethodOptions = MERGE_METHOD_OPTIONS
const mergeOrderOptions = MERGE_ORDER_OPTIONS
/** Who merges when the form leaves it blank: the board's resolved default, or the coordinator's rule for a new one. */
const effectiveMergeBy = computed(() => {
    const by = boards.value.find(x => x.uuid === editingBoard.value?.uuid)?.effectiveDeliveryPolicy?.merge?.by
    return by ? String(by).toLowerCase().replace('role:', 'role ') : 'default'
})
const mergeByHelp = computed(() => MERGE_BY_OPTIONS.find(o => o.value === editingBoard.value?.merge?.by)?.help
    ?? 'Blank: the coordinator when it covers PR_MERGE, else the role that carries it, else a person.')
const editingBoardIsNew = ref(false)
const editingRole = ref<any>(null)
const editingRoleIsNew = ref(false)
const saving = ref(false)
const selectedTask = ref<any>(null)
const agentNames = ref<Record<string, string>>({})
// Agents by uuid with their own name apart from the key's note, for naming sessions (RD2-11).
const agentDir = ref<Record<string, AgentName>>({})
provide(AGENT_DIR, agentDir)
provide(COORDINATOR_SEAT, computed(() => currentBoard.value?.coordinatorSeat ?? null))

function openTask (t: any) {
    // resolve to the freshest copy from the board so drawer navigation
    // (deps/lineage chips) always shows current state
    selectedTask.value = tasks.value.find(x => x.uuid === t.uuid) ?? t
}

const showPresets = ref(false)
const presets = ref<any[]>([])
const editingPreset = ref<any>(null)
// The org's model catalogue, for picking per-model strength overrides. Loaded with the board
// content; an operator who cannot read it gets an empty picker, not a broken editor.
const models = ref<any[]>([])
const editingPresetIsNew = ref(false)

const capabilityOptions = toOptions(CAPABILITIES)
// The coordinator always has the tracker verbs, and the server refuses them here.
const coordinatorCapabilityOptions = toOptions(COORDINATOR_CAPABILITIES)

/**
 * Document types a role can be required to publish.
 *
 * The task-scoped pair only. A component-scoped document belongs to the thing rather than to a
 * hop, so requiring one per hop would refuse a sign-off on the second task to touch it.
 */
const outputTypeOptions = [
    { label: 'review findings', value: 'REVIEW_FINDINGS' },
    { label: 'test report', value: 'TEST_REPORT' },
]

const priorityOptions = [
    { label: 'LAX — priority is advisory; workers may take any eligible task', value: 'LAX' },
    { label: 'STRICT — workers may only take their top eligible task', value: 'STRICT' },
]

const sortedPresets = computed(() =>
    [...presets.value].sort((a, b) => (a.orderIndex ?? 0) - (b.orderIndex ?? 0)))

/** The board's times in the one format (RD2-23); a function prop for the lock control's line. */
function formatEventTime (iso: string | null | undefined): string {
    return iso ? ts(iso) : ''
}

// Concurrently assigned tasks per agent on this board (drives the WIP chips).
const agentWip = computed(() => {
    const counts = new Map<string, number>()
    for (const t of tasks.value) {
        if (t.status === 'ASSIGNED' && t.assignment?.agent) {
            counts.set(t.assignment.agent, (counts.get(t.assignment.agent) ?? 0) + 1)
        }
    }
    return [...counts.entries()]
        // Per agent, as the server counts the limit; named by the agent, not the key's note (RD2-11).
        .map(([agent, count]) => ({ agent, count, name: agentLabel(agent, agentDir.value) }))
        .sort((a, b) => b.count - a.count)
})

const awaitingHumanReview = computed(() =>
    tasks.value.filter(t => t.status === 'ON_HOLD' && t.hold?.kind === 'HUMAN_GATE'))

// Client-side mirror of the server completion gate (active REQUIRED roles
// whose latest sign-off on the task is not PASSED) so gaps show pre-"done".
function missingRequired (t: any): string[] {
    if (t.childTasks?.length || t.status === 'COMPLETED' || t.status === 'CANCELLED') return []
    return roles.value
        .filter(r => r.active && r.necessity === 'REQUIRED')
        .map(r => r.name)
        .filter((role: string) => {
            const last = [...(t.signOffs ?? [])].reverse()
                .find((so: any) => (so.role ?? '').toLowerCase() === role.toLowerCase())
            return !last || last.outcome !== 'PASSED'
        })
}

// Mirrors AgentBoardService.coordinatorPresetFor: by whether sources are wired, with no
// fallback. Shown so an operator can see which preset a board will take its prompt from.
function coordinatorPresetFor (b: any): string {
    return (b?.sources?.length ?? 0) > 0 ? 'coordinator-tracker' : 'coordinator-board-truth'
}

function otherCoordinatorPreset (b: any): string {
    return coordinatorPresetFor(b) === 'coordinator-tracker'
        ? 'coordinator-board-truth'
        : 'coordinator-tracker'
}

const reseeding = ref(false)

async function reseedCoordinator (presetName: string) {
    if (!editingBoard.value?.uuid) return
    reseeding.value = true
    try {
        const res = await store.dispatch('agentBoardReseedCoordinatorPrompt', {
            boardUuid: editingBoard.value.uuid, presetName })
        editingBoard.value.coordinatorPrompt = res?.coordinatorPrompt ?? ''
        notification.success({ content: `Coordinator prompt reseeded from ${presetName}`,
            duration: 3000 })
    } catch (e: any) {
        notification.error({ content: `Reseed failed: ${e?.message ?? e}`, duration: 8000 })
    } finally {
        reseeding.value = false
    }
}

// ---------- operator actions: people run a board without a coordinator ----------

// A person's verbs on a task, shared with the task page. A verdict that hands the task on closes
// the drawer; any other action reloads and keeps the drawer on the same task.
const {
    humanReview, humanSignOff, operatorRelease, authorizeTask, orderTask,
    completeTask, cancelTask, reopenTask, decideFindings, requireReview, setStrength, operatorHold, setBudget, setLevel,
    setGroup, setTags, delivered, releaseAssignment,
} = useAgentTaskActions(async (t: any, keepOpen: boolean) => {
    if (!keepOpen) selectedTask.value = null
    await refreshBoardContent()
    if (keepOpen) selectedTask.value = tasks.value.find(x => x.uuid === t.uuid) ?? null
})

const priorityLevels = computed(() =>
    store.getters.orgById(props.orgUuid)?.settings?.findingPriorityLevels ?? 3)

const registering = ref<{ title: string, description: string, externalRef: string, sourceUrl: string,
    group?: string | null, tagsText?: string, level?: number | null } | null>(null)
const canRegister = computed(() => !!registering.value?.title.trim()
    && !taskTitleProblem(registering.value.title) && !taskDescriptionProblem(registering.value.description)
    && !tagsProblem(parseTags(registering.value.tagsText))
    && ((currentBoard.value?.sources?.length ?? 0) === 0 || !!registering.value?.externalRef.trim()))

/** A new task's draft, from the header or the empty board's hint (RD2-13). */
function newTask () {
    registering.value = { title: '', description: '', externalRef: '', sourceUrl: '', group: NO_GROUP, tagsText: '', level: null }
}

// The kanban's columns at a laptop's width (RD2-13): empty columns fold until opened; each lane's strip says how
// many columns sit past its edge, measured after each render, on scroll and on resize.
const openedColumns = ref<Set<string>>(new Set())
function folded (key: string, cards: any[]): boolean {
    return columnFolded(key, cards.length, openedColumns.value)
}
function openColumn (key: string) {
    if (openedColumns.value.has(key)) return
    openedColumns.value = new Set([...openedColumns.value, key])
}
const pastEdge = ref<Record<string, number>>({})
// Each lane's strip, measured when it mounts and whenever the tasks, the open columns or the window change (RD2-13).
const strips = stripMeasure(fn => { nextTick(fn) }, pastEdge)
const boardEl = strips.boardEl
const measureBoards = strips.measure
onMounted(() => window.addEventListener('resize', measureBoards))
onBeforeUnmount(() => window.removeEventListener('resize', measureBoards))
watch([tasks, openedColumns], () => nextTick(measureBoards))

/** A group from the Groups tab (agentBoardGroupSet); a refusal goes back to the form, beside its field. */
async function saveGroup (group: Record<string, any>) {
    if (!currentBoard.value) return
    await store.dispatch('agentBoardGroupSet', { boardUuid: currentBoard.value.uuid, group })
    notification.success({ content: `Group ${group.key} saved`, duration: 3000 })
    await refreshBoards()
}

async function registerTask () {
    if (!registering.value || !currentBoard.value) return
    try {
        await store.dispatch('agentTaskRegister', {
            boardUuid: currentBoard.value.uuid,
            input: { ...taskRegisterInput(registering.value), ...registerGroupFields(registering.value) },
        })
        notification.success({ content: 'Task registered — pending intake', duration: 3000 })
        registering.value = null
        await refreshBoardContent()
    } catch (e: any) {
        notification.error({ content: `Register failed: ${e?.message ?? e}`, duration: 8000 })
    }
}


function assignedInRole (role: string): number {
    return tasks.value.filter(t => t.status === 'ASSIGNED' && t.assignment?.role === role).length
}

// A QUEUED task is wip-capped when its role's concurrent-assignment
// limit is already exhausted: eligible, but the server will refuse
// assignment until a slot frees.
function wipCapped (t: any): boolean {
    if (t.status !== 'QUEUED' || blockedBy(t).length) return false
    const rc = roles.value.find(r => r.name === t.role)
    if (!rc?.wipLimit) return false
    return assignedInRole(t.role) >= rc.wipLimit
}

// A QUEUED task is blocked when any dependency is not yet COMPLETED.
function blockedBy (t: any): string[] {
    if (t.status !== 'QUEUED' || !t.dependsOn?.length) return []
    return t.dependsOn.filter((d: string) => {
        const dep = tasks.value.find(x => x.uuid === d)
        return !dep || dep.status !== 'COMPLETED'
    })
}

// What waits on a card ("blocks"); what it waits on is its one waits-on chip (RD2-13).
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

// "<prefix> · <name>", a locked board tagged (RD2-22).
const boardOptions = computed(() => boardPickerOptions(boards.value))
const currentBoard = computed(() => boards.value.find(b => b.uuid === selectedBoard.value) ?? null)
const isLocked = computed(() => {
    const lvl = currentBoard.value?.lock?.level
    return !!lvl && lvl !== 'NONE'
})
const activeRoles = computed(() =>
    [...roles.value].filter(r => r.active).sort((a, b) => (a.orderIndex ?? 0) - (b.orderIndex ?? 0)))
const sortedRoles = computed(() =>
    [...roles.value].sort((a, b) => (a.orderIndex ?? 0) - (b.orderIndex ?? 0)))

// Column ordering: in-flight first, then what a worker could actually
// pick up now, then capped, then dependency-blocked - each group by
// coordinator priority. The top of a column always answers "what is
// moving and what is next"; stuck work sinks.
function workRank (t: any): number {
    if (t.status === 'ASSIGNED') return 0
    if (t.status === 'QUEUED') {
        if (blockedBy(t).length) return 3
        if (wipCapped(t)) return 2
        return 1
    }
    return 4
}

function byPriority (list: any[]): any[] {
    return [...list].sort((a, b) =>
        workRank(a) - workRank(b)
        || (a.orderIndex ?? 0) - (b.orderIndex ?? 0)
        || String(a.createdDate ?? '').localeCompare(String(b.createdDate ?? '')))
}

function byStatus (s: string, among: any[] = tasks.value): any[] {
    const list = among.filter(t => t.status === s)
    // completed reads best newest-first; everything else by priority
    if (s === 'COMPLETED') {
        return [...list].sort((a, b) =>
            String(b.completedAt ?? '').localeCompare(String(a.completedAt ?? '')))
    }
    return byPriority(list)
}
// QUEUED + ASSIGNED tasks grouped under their current role column
function atRole (role: string, among: any[] = tasks.value): any[] {
    return byPriority(among.filter(t =>
        (t.status === 'QUEUED' || t.status === 'ASSIGNED') && t.role === role))
}

// Compact task card as a local render component to keep the template lean.
const TaskCard = defineComponent({
    props: { t: { type: Object, required: true } },
    setup (p: any) {
        // The group's colour on the card's edge, in every grouping (RD2-31).
        return () => h(NCard, { size: 'small', onClick: () => openTask(p.t), 'data-group': p.t.group?.key ?? undefined,
            style: 'cursor: pointer' + (groupColour(p.t.group?.key) ? `; border-left: 3px solid ${groupColour(p.t.group?.key)}` : ''),
            class: ['tcard',
                p.t.status === 'ASSIGNED' ? 'tcard--assigned' : '',
                p.t.status === 'COMPLETED' ? 'tcard--done' : '',
                workRank(p.t) === 1 ? 'tcard--ready' : '',
                workRank(p.t) >= 2 && workRank(p.t) <= 3 ? 'tcard--stuck' : ''] }, { default: () => [
            h('div', { class: 'tcard__title' }, [
                // The key leads the card, as on the task page (RD2-22); the level beside it (RD2-1).
                p.t.key ? h('span', { class: 'tcard__key', 'data-testid': 'card-key' }, p.t.key) : null,
                levelLabel(p.t, currentBoard.value) ? h(NTooltip, { trigger: 'hover' }, {
                    trigger: () => h(NTag, { size: 'tiny', bordered: false, class: 'tcard__level' },
                        { default: () => levelLabel(p.t, currentBoard.value) }),
                    default: () => levelTooltip(p.t, currentBoard.value, actorLabel),
                }) : null,
                // The group beside the key and level (RD2-31).
                p.t.group?.key ? h(NTooltip, { trigger: 'hover' }, {
                    trigger: () => h(NTag, { size: 'tiny', bordered: false, class: 'tcard__group', 'data-testid': 'card-group',
                        color: { color: `${groupColour(p.t.group.key)}22`, textColor: groupColour(p.t.group.key) ?? undefined } },
                    { default: () => p.t.group.key }),
                    default: () => groupLabel(groupByKey(currentBoard.value, p.t.group.key) ?? p.t.group),
                }) : null,
                p.t.title,
                // The page, without opening the drawer on the way.
                h(RouterLink, { to: taskPagePath(p.t.uuid), class: 'tcard__open', title: 'Open task page',
                    onClick: (e: Event) => e.stopPropagation() }, { default: () => '↗' }),
            ]),
            p.t.sourceUrl
                ? h('div', { class: 'tcard__ref' }, h('a', { href: p.t.sourceUrl, target: '_blank', rel: 'noopener' },
                    refLabel(p.t, boardHasSources.value) ?? 'link'))
                : (refLabel(p.t, boardHasSources.value) ? h('div', { class: 'tcard__ref' }, refLabel(p.t, boardHasSources.value)) : null),
            h('div', { class: 'tcard__meta' }, [
                p.t.status === 'QUEUED' ? h(NTag, { size: 'tiny', bordered: false }, { default: () => `queued #${p.t.orderIndex}` }) : null,
                p.t.status === 'ASSIGNED' ? h(NTag, { size: 'tiny', bordered: false, type: 'warning' }, { default: () => 'assigned' }) : null,
                roleTagFor(p.t)?.kind === 'history' ? h(NTooltip, { trigger: 'hover' }, {
                    trigger: () => h(NTag, { size: 'tiny', bordered: false, class: 'tag--history' }, { default: () => roleTagFor(p.t)?.text }),
                    default: () => roleTagFor(p.t)?.tooltip,
                }) : null,
                p.t.status === 'ON_HOLD' ? h(NTooltip, { trigger: 'hover' }, {
                    trigger: () => h(NTag, { size: 'tiny', bordered: false, type: 'error' }, {
                        default: () => p.t.hold?.kind === 'HUMAN_GATE' ? '\u270b human review' : 'on hold',
                    }),
                    default: () => p.t.hold?.reason ?? 'on hold',
                }) : null,
                p.t.requireHumanReview ? h(NTooltip, { trigger: 'hover' }, {
                    trigger: () => h(NTag, { size: 'tiny', bordered: false, type: 'warning' }, { default: () => 'review flagged' }),
                    default: () => 'The next sign-off on this task parks it for human review.',
                }) : null,
                missingRequired(p.t).length && (p.t.status === 'AWAITING_COORDINATOR') ? h(NTooltip, { trigger: 'hover' }, {
                    trigger: () => h(NTag, { size: 'tiny', bordered: false, type: 'error' }, {
                        default: () => 'needs ' + missingRequired(p.t).join(', '),
                    }),
                    default: () => 'Required role(s) without a passing sign-off — completion is blocked until they stamp.',
                }) : null,
                // The blocker named once, by key (RD2-13): the "after" row that named it again is gone.
                waitsOnLabel(p.t, tasks.value) ? h(NTooltip, { trigger: 'hover' }, {
                    trigger: () => h(NTag, { size: 'tiny', bordered: false, type: 'warning', 'data-testid': 'card-waits-on' },
                        { default: () => waitsOnLabel(p.t, tasks.value) }),
                    default: () => 'Not assignable until every dependency is COMPLETED; the server releases it automatically.',
                }) : null,
                // A linked PR moved past the head its passing test named (RD2-13): re-test before merging.
                prChips(p.t).some((c: any) => c.moved) ? h(NTooltip, { trigger: 'hover' }, {
                    trigger: () => h(NTag, { size: 'tiny', bordered: false, type: 'error', 'data-testid': 'card-pr-moved' },
                        { default: () => 'PR moved · re-test' }),
                    default: () => prChips(p.t).filter((c: any) => c.moved).map((c: any) => `${c.label}: ${c.heads}`).join(' | '),
                }) : null,
                waitingOnLabel(p.t) ? h(NTooltip, { trigger: 'hover' }, {
                    trigger: () => h(NTag, { size: 'tiny', bordered: false, type: 'warning', 'data-testid': 'card-waiting' },
                        { default: () => waitingOnLabel(p.t) }),
                    default: () => 'Not offered or assignable until every task of the groups it waits on is done or cancelled.',
                }) : null,
                wipCapped(p.t) ? h(NTooltip, { trigger: 'hover' }, {
                    trigger: () => h(NTag, { size: 'tiny', bordered: false, type: 'error' }, { default: () => 'wip-capped' }),
                    default: () => `Role ${p.t.role} is at its WIP limit — assignable as soon as a slot frees.`,
                }) : null,
                p.t.parentTask ? h(NTag, { size: 'tiny', bordered: false, type: 'info' }, { default: () => 'subtask' }) : null,
                subtaskTag(p.t, tasks.value) ? h(NTooltip, { trigger: 'hover' }, {
                    trigger: () => h(NTag, { size: 'tiny', bordered: false, type: subtaskTag(p.t, tasks.value)?.type },
                        { default: () => subtaskTag(p.t, tasks.value)?.text }),
                    default: () => subtaskProgress(p.t, tasks.value).open.length
                        ? 'Open: ' + subtaskProgress(p.t, tasks.value).open
                            .map((c: any) => `${depLabel(c)} (${String(c.status ?? '').toLowerCase().replace(/_/g, ' ')})`).join(', ')
                        + '. The board completes this task when they finish.'
                        : 'Every subtask is done.',
                }) : null,
                p.t.returns?.length ? h(NTooltip, { trigger: 'hover' }, {
                    trigger: () => h(NTag, { size: 'tiny', bordered: false, type: 'error' }, { default: () => `${p.t.returns.length} return${p.t.returns.length > 1 ? 's' : ''}` }),
                    default: () => p.t.returns.map((r: any) => `${r.role ?? '?'}: ${r.reason}${r.description ? ' — ' + r.description : ''}`).join(' | '),
                }) : null,
                ...prChips(p.t).map((c) => h(NTooltip, { trigger: 'hover' }, {
                    trigger: () => h(NTag, { size: 'tiny', bordered: false, type: c.type },
                        { default: () => h('a', { href: c.url, target: '_blank', rel: 'noopener', class: 'prlink' },
                            c.state === 'linked' ? 'PR' : `PR ${c.state}`) }),
                    default: () => `${c.label}: ${c.title}`,
                })),
            ]),
            dependentsOf(p.t).length ? h('div', { class: 'tcard__deps' }, [
                h('span', { class: 'deplabel' }, 'blocks'),
                ...dependentsOf(p.t).map((d: any, i: number) => h(NTooltip, { trigger: 'hover', key: 'b' + i }, {
                    trigger: () => h('span', { class: 'depchip depchip--blocks' }, depLabel(d)),
                    default: () => `${d.title} — ${d.status}`,
                })),
            ]) : null,
            p.t.signOffs?.length ? h('div', { class: 'tcard__passages' },
                p.t.signOffs.map((s: any, i: number) => h(NTooltip, { trigger: 'hover', key: i }, {
                    trigger: () => h('span', { class: ['passage', 'passage--' + (s.outcome || '').toLowerCase()] },
                        (s.reviewedBy ? '\u270b ' : '') + s.role),
                    default: () => `${s.role}: ${s.outcome}${s.reviewedBy ? ' by ' + actorLabel(s.reviewedBy) : ''}${s.note ? ' — ' + s.note : ''}`,
                }))) : null,
        ] })
    },
})

// Header label carrying its own explanation — these three columns are
// governance, and the words alone ("pass", "gate") don't carry the rules.
function hintHeader (label: string, hint: string) {
    return () => h(NTooltip, { trigger: 'hover' }, {
        trigger: () => h('span', { class: 'hinthead' }, label),
        default: () => hint,
    })
}

// Always rendered, defaults included: an empty cell would read as
// "unset" when it actually means AGENTIC / OPTIONAL / no gate.
const kindCell = (r: any) => h(NTag,
    { size: 'tiny', bordered: false, type: r.kind === 'HUMAN' ? 'info' : 'default' },
    { default: () => (r.kind === 'HUMAN' ? '\u270b human' : 'agent') })

const necessityCell = (r: any) => (r.necessity === 'REQUIRED'
    ? h(NTag, { size: 'tiny', bordered: false, type: 'error' }, { default: () => 'required' })
    : h('span', { class: 'cellmuted' }, 'optional'))

const gateCell = (r: any) => (!r.humanGate || r.humanGate === 'NONE'
    ? h('span', { class: 'cellmuted' }, 'none')
    : h(NTag, { size: 'tiny', bordered: false, type: 'warning' },
        { default: () => (r.humanGate === 'ON_PASS' ? 'on pass' : 'any sign-off') }))

const governanceColumns = (): DataTableColumns<any> => [
    {
        title: hintHeader('Worked by', 'AGENTIC roles are polled and assigned to agents.'
            + ' HUMAN roles are never offered to agents — an org admin signs off directly from the queue.'),
        key: 'kind', width: 100, render: kindCell,
    },
    {
        title: hintHeader('Pass', 'OPTIONAL: the coordinator may skip this role — routing is its judgment.'
            + ' REQUIRED: unskippable — no task completes without a passing sign-off in this role.'),
        key: 'necessity', width: 95, render: necessityCell,
    },
    {
        title: hintHeader('Human gate', 'Human review of this role\'s sign-offs, fired only when the role'
            + ' actually signs off. on pass = passing sign-offs park for review; any sign-off = rejections gate too.'),
        key: 'humanGate', width: 115, render: gateCell,
    },
]

const roleColumns: DataTableColumns<any> = [
    { title: 'Order', key: 'orderIndex', width: 62 },
    { title: 'Role', key: 'name', width: 120 },
    { title: 'WIP', key: 'wipLimit', width: 52, render: (r: any) => r.wipLimit ?? '—' },
    { title: 'Strength', key: 'strength', width: 150, render: (r: any) => r.kind === 'HUMAN' ? '—' : strengthSummary(r) },
    ...governanceColumns(),
    {
        title: 'Flags', key: 'flags', width: 120,
        render: (r: any) => h('span', {}, [
            r.requireDistinctAgent ? h(NTag, { size: 'tiny', bordered: false, type: 'warning' }, { default: () => 'distinct agent' }) : null,
            r.blindReview ? h(NTag, { size: 'tiny', bordered: false, type: 'info', style: 'margin-left:4px' }, { default: () => 'blind' }) : null,
            !r.active ? h(NTag, { size: 'tiny', bordered: false, style: 'margin-left:4px' }, { default: () => 'inactive' }) : null,
        ]),
    },
    { title: 'Prompt', key: 'prompt', ellipsis: { tooltip: true }, render: (r: any) => (r.prompt ? r.prompt.split('\n')[0] : '—') },
    {
        title: '', key: 'actions', width: 62,
        // Editing a role's prompt is configuring the board (task d8e7bd7e): hidden without CONFIGURATION_WRITE.
        render: (r: any) => !canConfigure(currentBoard.value) ? null : h(NButton, { size: 'tiny', quaternary: true, onClick: () => { editingRoleIsNew.value = false; editingRole.value = { ...r, hopDollars: microsToDollars(r.hopBudgetMicros),
            producesOutputTypes: (r.producesOutputs ?? []).map((p: any) => p?.specification).filter(Boolean),
            strength: strengthDraft(r) } } }, { default: () => 'Edit' }),
    },
]

onMounted(refreshBoards)
onMounted(() => loadTargetComponents(true))
watch(selectedBoard, async () => {
    // Picking a board the person can read clears the notice about the one they could not.
    if (selectedBoard.value) hiddenBoard.value = null
    syncQuery()
    await refreshBoardContent()
})

// ---------- declarative boards: apply a board or presets file ----------

// Who may apply a file: the org's admin, or a permission carrying CONFIGURATION_WRITE at write level
// (declarative-boards D10). The server decides; this only keeps the button from inviting a refusal.
const canApplySpec = computed<boolean>(() => {
    const perms = store.getters.myuser?.permissions?.permissions ?? []
    return perms.some((p: any) => p.org === props.orgUuid && ((p.scope === 'ORGANIZATION' && p.type === 'ADMIN')
        || ((p.functions ?? []).includes('CONFIGURATION_WRITE') && (p.type === 'READ_WRITE' || p.type === 'ADMIN'))))
})

// A board without sources is its own tracker: no task has a ref there, and none is a "draft".
const boardHasSources = computed<boolean>(() => (currentBoard.value?.sources?.length ?? 0) > 0)
// The operator verbs, reopen included, need BOARD_WRITE on the board (task d8e7bd7e); the server decides.
const canReopen = computed<boolean>(() => canOperate(currentBoard.value))
// A board's and its roles' histories read with the board; the org presets' stay the org admin's.
const canReadHistory = computed<boolean>(() => boardCan(currentBoard.value, 'BOARD_READ'))
const canReadPresetHistory = computed<boolean>(() => isOrgAdmin(store.getters.myuser?.permissions?.permissions, props.orgUuid))
// Only an org admin reads every board, so only an admin's empty list means the org has none (RD2-9).
const isAdmin = computed<boolean>(() => isOrgAdmin(store.getters.myuser?.permissions?.permissions, props.orgUuid))
const hiddenBoard = ref<string | null>(null)

const applyKinds = ref<SpecKind[] | null>(null)

function provenanceLabel (d: any): string {
    const src = d?.source
    if (!src?.repo && !src?.path) return 'a file'
    return `${src.repo ?? ''}${src.commit ? '@' + String(src.commit).slice(0, 8) : ''}${src.path ? ' ' + src.path : ''}`.trim()
}

async function onSpecApplied (p: { kind: SpecKind, name?: string }) {
    if (p.kind === 'ROLE_PRESETS') {
        presets.value = await store.dispatch('fetchAgentTaskRolePresetsOfOrg', props.orgUuid) ?? []
        return
    }
    await refreshBoards()
    const applied = boards.value.find(b => b.name === p.name)
    if (applied && applied.uuid !== selectedBoard.value) {
        selectedBoard.value = applied.uuid
        syncQuery()
        await refreshBoardContent()
    }
}

async function refreshBoards () {
    boards.value = await store.dispatch('fetchAgentBoardsOfOrg', props.orgUuid) ?? []
    if (!selectedBoard.value) {
        const fromUrl = route.query.board as string | undefined
        const known = fromUrl && boards.value.some(b => b.uuid === fromUrl)
        // A link to a board the list does not hold is not silently another board (RD2-9).
        hiddenBoard.value = fromUrl && !known ? fromUrl : null
        selectedBoard.value = known ? (fromUrl as string) : hiddenBoard.value ? null : (boards.value[0]?.uuid ?? null)
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
    await loadLifetimeSpend()
    await loadDocumentSeries()
    await loadCoverage()
    // agent uuid -> display name map for timeline/table/drawer; loaded
    // lazily once per panel life, refreshed with board content
    models.value = await store.dispatch('fetchModelOntologiesOfOrg', props.orgUuid).catch(() => []) ?? []
    const agents = await store.dispatch('fetchAgentsOfOrg', props.orgUuid) ?? []
    agentDir.value = agentDirectory(agents)
    agentNames.value = agentNamesOf(agents)
}

// A save refusal about one of the naming fields, shown beside it (task fceb1e57).
const boardFieldErrors = ref<{ taskPrefix?: string, documents?: string, defaultTaskLevel?: string, target?: string }>({})

// The target picker's list (task RD2-4): the org's software components, read (cache-first) when the
// form opens. Read into the form, not off the store getter, which also holds components fetched one
// by one, a board's DOCUMENT components among them. Also read quietly with the panel, so the header
// chip can name the target on a server that does not resolve targetDetails (T-1).
const targetComponents = ref<any[]>([])
async function loadTargetComponents (quiet = false) {
    try {
        targetComponents.value = await store.dispatch('fetchComponents', props.orgUuid) ?? []
    } catch (e: any) {
        targetComponents.value = []
        if (!quiet) notification.error({ content: `Could not load the components to pick a target from: ${e?.message ?? e}`, duration: 8000 })
    }
}
const targetOptions = computed(() => boardTargetOptions(targetComponents.value, editingBoardIsNew.value ? null
    : targetOf(boards.value.find(x => x.uuid === editingBoard.value?.uuid), targetComponents.value)))
const renderTargetOption = (o: any) => h('span', null, [o.label, h('span', { style: 'color: #888; font-size: 12px;' }, ` · ${targetOptionType(o)}`)])
const boardTargetChip = computed(() => targetChip(currentBoard.value, props.orgUuid, targetComponents.value))

function startEditBoard (b: any | null) {
    editingBoardIsNew.value = b === null
    boardFieldErrors.value = {}
    void loadTargetComponents()
    // documentsRepo comes back as the repository ROW; the editor works in uris, and the mutation
    // takes one and resolves it. Flattened here so the input binds to a string.
    editingBoard.value = b ? { ...b, sources: [...(b.sources ?? [])], budgetDollars: microsToDollars(b.budgetMicros),
        documentsRepo: b.documentsRepo?.uri ?? '',
        stalenessDraft: stalenessDraftOf(b.staleness),
        documentPaths: { ...(b.documentPaths ?? {}) },
        coordinatorCapabilities: [...(b.coordinatorCapabilities ?? [])],
        perspectives: [...(b.perspectives ?? [])],
        taskPrefix: b.taskPrefix ?? '', heldTaskPrefix: b.taskPrefix ?? '', documentsDraft: documentsDraftOf(b),
        deliveryMode: b.deliveryPolicy?.mode ?? null, deliveryAttest: !!b.deliveryPolicy?.attest,
        merge: mergeDraftOf(b.deliveryPolicy) }
        : { name: '', description: '', sources: [], coordinatorPrompt: '', perAgentWipLimit: 2,
            priorityType: 'LAX', seedFromPresets: true, documentsRepo: '', documentPaths: {},
            coordinatorCapabilities: [], perspectives: [], merge: mergeDraftOf(null), stalenessDraft: stalenessDraftOf(null),
            taskPrefix: '', documentsDraft: documentsDraftOf(null), target: null }
}

/** hopBudgetMicros for the role input: set, removed (null), or left out when never set and still blank. */
function hopBudgetField (draft: any): Record<string, number | null> {
    if (draft?.kind === 'HUMAN') return {}
    const hop = hopBudgetInput(draft?.hopBudgetMicros, draft?.hopDollars)
    return hop === undefined ? {} : { hopBudgetMicros: hop }
}

// What no key can do on the board (task 5c70990d): read for the selected board only, since the
// server reads the org's keys for it; an overlay like the spend, never a precondition.
const missingCoverage = ref<any[]>([])
async function loadCoverage () {
    missingCoverage.value = []
    if (!selectedBoard.value) return
    try {
        missingCoverage.value = await store.dispatch('fetchAgentBoardCoverage', selectedBoard.value) ?? []
    } catch {
        missingCoverage.value = []
    }
}

// What the board has produced, per document series (task 36d0549e); an overlay like the spend.
const documentSeries = ref<any[]>([])
async function loadDocumentSeries () {
    documentSeries.value = []
    if (!selectedBoard.value) return
    try {
        documentSeries.value = await store.dispatch('fetchAgentBoardDocumentSeries', selectedBoard.value) ?? []
    } catch {
        documentSeries.value = []
    }
}

// Everything the board has spent since it was created, against its budget (task 40f270be).
const lifetimeSpentMicros = ref<number | null>(null)
async function loadLifetimeSpend () {
    lifetimeSpentMicros.value = null
    if (!selectedBoard.value) return
    try {
        const u = await store.dispatch('fetchAgentBoardUsage', { boardUuid: selectedBoard.value,
            from: currentBoard.value?.createdDate ?? '1970-01-01T00:00:00Z', to: new Date().toISOString() })
        lifetimeSpentMicros.value = u?.derivedCostMicros ?? 0
    } catch {
        // usage is an overlay on the board, never a precondition for using it
        lifetimeSpentMicros.value = null
    }
}
const perspectiveOptions = computed(() => boardPerspectiveOptions(store.getters.perspectivesOfOrg(props.orgUuid)))
const spendChip = computed(() => null === lifetimeSpentMicros.value ? null
    : budgetChip(lifetimeSpentMicros.value, currentBoard.value?.budgetMicros, currentBoard.value?.softAlertPercent))

async function saveBoard () {
    if (!editingBoard.value?.name?.trim()) {
        notification.error({ content: 'Board name is required', duration: 8000 })
        return
    }
    if (targetMissing(editingBoardIsNew.value, editingBoard.value.target)) {
        boardFieldErrors.value = { target: `Board requires a target component: ${TARGET_HINT}` }
        return
    }
    const staleProblem = stalenessProblem(editingBoard.value.stalenessDraft ?? {})
    if (staleProblem) {
        notification.error({ content: staleProblem, duration: 8000 })
        return
    }
    saving.value = true
    try {
        const input: any = {
            description: editingBoard.value.description ?? '',
            sources: editingBoard.value.sources ?? [],
            coordinatorPrompt: editingBoard.value.coordinatorPrompt ?? '',
            perAgentWipLimit: editingBoard.value.perAgentWipLimit ?? 2,
            priorityType: editingBoard.value.priorityType ?? 'LAX',
        }
        // Sent only when set. The server applies documentsRepo AFTER the sources patch in the same
        // call, so adding the repository to sources and naming it here works in one save.
        if (editingBoard.value.documentsRepo) {
            input.documentsRepo = editingBoard.value.documentsRepo.trim()
        }
        const paths = Object.fromEntries(
            Object.entries(editingBoard.value.documentPaths ?? {})
                .filter(([, v]) => !!(v as string)?.trim()))
        if (Object.keys(paths).length) input.documentPaths = paths
        // Always sent: the form shows the current list, so an emptied one clears it ([]).
        input.coordinatorCapabilities = editingBoard.value.coordinatorCapabilities ?? []
        // Only what changed: an emptied setting clears, one left alone is not sent (task 40f270be).
        const original = editingBoardIsNew.value ? null : boards.value.find(x => x.uuid === editingBoard.value.uuid)
        // Only when changed: a change asks for consent on each perspective added or removed.
        // Sent on create, and on update only when changed (task RD2-4); a board always has one.
        const target = targetPatch(original, editingBoard.value.target)
        if (target !== undefined) input.target = target
        const perspectives = perspectivesPatch(original, editingBoard.value.perspectives)
        if (perspectives !== undefined) input.perspectives = perspectives
        const settings: Record<string, any> | null = settingsPatch(original, settingsDraftOf(editingBoard.value))
        if (settings) input.settings = settings
        // The staleness block goes whole when it changed, null when emptied (task RD3-4).
        const stale = stalenessPatch(original?.staleness, editingBoard.value.stalenessDraft ?? {})
        if (stale !== undefined) input.settings = { ...(input.settings ?? {}), staleness: stale }
        // Only when changed; cleared restores the default, PR_ROWS (task 18c5c293).
        const delivery = deliveryPolicyPatch(original, editingBoard.value.deliveryMode, !!editingBoard.value.deliveryAttest,
            editingBoard.value.merge)
        if (delivery.changed) input.deliveryPolicy = delivery.value
        // Only when changed; the documents block goes whole, an explicit empty root as '' (task fceb1e57).
        const taskPrefix = taskPrefixPatch(original, editingBoard.value.taskPrefix)
        if (taskPrefix !== undefined) input.taskPrefix = taskPrefix
        const documents = documentsPatch(original, editingBoard.value.documentsDraft)
        if (documents !== undefined) input.documents = documents
        // Only when changed; blank sends null, which clears it (RD2-1).
        const defaultLevel = defaultLevelPatch(original, editingBoard.value.defaultTaskLevel)
        if (defaultLevel.changed) input.defaultTaskLevel = defaultLevel.value
        boardFieldErrors.value = {}
        if (editingBoardIsNew.value) {
            input.name = editingBoard.value.name.trim()
            input.seedFromPresets = !!editingBoard.value.seedFromPresets
            await store.dispatch('createAgentBoard', { orgUuid: props.orgUuid, input })
        } else {
            await store.dispatch('updateAgentBoard', { boardUuid: editingBoard.value.uuid, input })
        }
        notification.success({ content: `Board ${editingBoard.value.name} saved`, duration: 3000 })
        const wasNew = editingBoardIsNew.value
        const savedName = editingBoard.value.name.trim()
        editingBoard.value = null
        await refreshBoards()
        if (wasNew) {
            const created = boards.value.find(b => b.name === savedName)
            if (created) selectedBoard.value = created.uuid
        }
    } catch (e: any) {
        const message = String(e?.message ?? e)
        const field = boardFieldOfError(message)
        if (field) boardFieldErrors.value = { [field]: message }
        else notification.error({ content: `Save failed: ${message}`, duration: 8000 })
    } finally {
        saving.value = false
    }
}

function startAddRole () {
    editingRoleIsNew.value = true
    const maxOrder = Math.max(0, ...roles.value.map(r => r.orderIndex ?? 0))
    editingRole.value = { name: '', prompt: '', orderIndex: maxOrder + 10, wipLimit: 0, requireDistinctAgent: false, blindReview: false, active: true, kind: 'AGENTIC', necessity: 'OPTIONAL', humanGate: 'NONE', producesOutputTypes: [], strength: strengthDraft(null) }
}

async function saveRole () {
    if (!editingRole.value?.name?.trim() || !selectedBoard.value) {
        notification.error({ content: 'Role name is required', duration: 8000 })
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
                requireDistinctAgent: editingRole.value.kind === 'HUMAN' ? null : !!editingRole.value.requireDistinctAgent,
                blindReview: editingRole.value.kind === 'HUMAN' ? null : !!editingRole.value.blindReview,
                active: !!editingRole.value.active,
                requiredCapabilities: editingRole.value.kind === 'HUMAN' ? null : (editingRole.value.requiredCapabilities ?? []),
                wipLimit: editingRole.value.kind === 'HUMAN' ? null : (editingRole.value.wipLimit ?? 0),
                ...hopBudgetField(editingRole.value),
                kind: editingRole.value.kind ?? 'AGENTIC',
                necessity: editingRole.value.necessity ?? 'OPTIONAL',
                humanGate: editingRole.value.kind === 'HUMAN' ? null : (editingRole.value.humanGate ?? 'NONE'),
                // Always sent for an agentic role, including as an empty list: omitting it would
                // leave a role's outputs unchanged, so an operator could never REMOVE one.
                producesOutputs: editingRole.value.kind === 'HUMAN' ? null
                    : mergeOutputs(editingRole.value.producesOutputs, editingRole.value.producesOutputTypes ?? []),
                // A HUMAN role has no model; leaving the fields out leaves nothing to refuse.
                ...(editingRole.value.kind === 'HUMAN' ? {} : strengthInput(editingRole.value.strength)),
            },
        })
        notification.success({ content: `Role ${editingRole.value.name} saved`, duration: 3000 })
        editingRole.value = null
        await refreshBoardContent()
    } catch (e: any) {
        notification.error({ content: `Save failed: ${e?.message ?? e}`, duration: 8000 })
    } finally {
        saving.value = false
    }
}

const presetColumns: DataTableColumns<any> = [
    { title: 'Order', key: 'orderIndex', width: 62 },
    { title: 'Preset', key: 'name', width: 120 },
    ...governanceColumns(),
    {
        title: 'Capabilities', key: 'caps', width: 170,
        render: (r: any) => (r.requiredCapabilities ?? []).join(', ') || '—',
    },
    { title: 'Prompt', key: 'prompt', ellipsis: { tooltip: true }, render: (r: any) => (r.prompt ? r.prompt.split('\n')[0] : '—') },
    {
        title: '', key: 'actions', width: 70,
        render: (r: any) => h(NButton, { size: 'tiny', quaternary: true, onClick: () => { editingPresetIsNew.value = false; editingPreset.value = { ...r, hopDollars: microsToDollars(r.hopBudgetMicros), strength: strengthDraft(r) } } }, { default: () => 'Edit' }),
    },
]

async function openPresets () {
    presets.value = await store.dispatch('fetchAgentTaskRolePresetsOfOrg', props.orgUuid) ?? []
    showPresets.value = true
}

function startAddPreset () {
    editingPresetIsNew.value = true
    const maxOrder = Math.max(0, ...presets.value.map(r => r.orderIndex ?? 0))
    editingPreset.value = { name: '', prompt: '', orderIndex: maxOrder + 10, wipLimit: 0, requireDistinctAgent: false, blindReview: false, active: true, requiredCapabilities: [], kind: 'AGENTIC', necessity: 'OPTIONAL', humanGate: 'NONE', strength: strengthDraft(null) }
}

async function savePreset () {
    if (!editingPreset.value?.name?.trim()) {
        notification.error({ content: 'Preset name is required', duration: 8000 })
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
                wipLimit: editingPreset.value.kind === 'HUMAN' ? null : (editingPreset.value.wipLimit ?? 0),
                ...hopBudgetField(editingPreset.value),
                requireDistinctAgent: editingPreset.value.kind === 'HUMAN' ? null : !!editingPreset.value.requireDistinctAgent,
                blindReview: editingPreset.value.kind === 'HUMAN' ? null : !!editingPreset.value.blindReview,
                active: !!editingPreset.value.active,
                requiredCapabilities: editingPreset.value.kind === 'HUMAN' ? null : (editingPreset.value.requiredCapabilities ?? []),
                kind: editingPreset.value.kind ?? 'AGENTIC',
                necessity: editingPreset.value.necessity ?? 'OPTIONAL',
                humanGate: editingPreset.value.kind === 'HUMAN' ? null : (editingPreset.value.humanGate ?? 'NONE'),
                ...(editingPreset.value.kind === 'HUMAN' ? {} : strengthInput(editingPreset.value.strength)),
            },
        })
        notification.success({ content: `Preset ${editingPreset.value.name} saved`, duration: 3000 })
        editingPreset.value = null
        presets.value = await store.dispatch('fetchAgentTaskRolePresetsOfOrg', props.orgUuid) ?? []
    } catch (e: any) {
        notification.error({ content: `Save failed: ${e?.message ?? e}`, duration: 8000 })
    } finally {
        saving.value = false
    }
}

/** Lock with the reason the in-page form asked for, or unlock once confirmed (RD2-17). */
async function operatorLock (lock: boolean, reason?: string) {
    try {
        await store.dispatch('setAgentBoardOperatorLock', { boardUuid: selectedBoard.value, lock, reason })
        notification.success({ content: lock ? 'Board locked (OPERATOR)' : 'Board unlocked', duration: 3000 })
        await refreshBoards()
    } catch (e: any) {
        notification.error({ content: `Lock change failed: ${e?.message ?? e}`, duration: 8000 })
    }
}
</script>

<style lang="scss">
// The board form's settings, label above each field, two columns (RD2-12). The form is a modal, which naive
// teleports to the body, out of .boardsPanel: these rules hang off the modal's own class (tester run 1 T-2).
.boardForm .form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px 16px; }
.boardForm .form-grid .fcell { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.boardForm .form-grid .fcell .n-input-number { width: 100%; }
.boardForm .form-grid .flabel { color: #888; font-size: 12px; }
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
    .hinthead { border-bottom: 1px dotted #bbb; cursor: help; }
    .cellmuted { color: #9a9a9a; font-size: 12px; }
    .col__human, .col__req, .col__gate {
        font-size: 10px;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.04em;
        padding: 1px 6px;
        border-radius: 8px;
        margin-left: 6px;
        cursor: default;
    }
    .col__human { background: rgba(64, 128, 255, 0.15); color: #3d6fd9; }
    .col__req { background: rgba(208, 48, 80, 0.12); color: #d03050; }
    .col__gate { background: rgba(240, 160, 32, 0.15); color: #b0740a; }
    .wipchip {
        font-size: 11px;
        padding: 1px 8px;
        border-radius: 9px;
        background: rgba(128, 128, 128, 0.1);
        color: #666;
        &--board { font-weight: 600; }
        &--full { background: #fbe3e3; color: #b03a3a; }
        &--strict { background: #e8f0fb; color: #3c5f96; font-weight: 600; }
    }
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
    .kanbanbar { margin-bottom: 8px; }
    .lane { margin-bottom: 12px; }
    .lane__head { font-weight: 600; font-size: 13px; margin: 6px 0; }
    .tcard__level { margin-right: 6px; }
    .subhint { font-size: 12px; color: #888; align-self: center; }
    .tcard__key { margin-right: 6px; font-family: monospace; font-size: 12px; color: #666; }
    .tcard__group { margin-right: 6px; font-family: monospace; }
    // A laptop's width holds the common columns (RD2-13): 180 px each at the least, an empty column folded to
    // its head, and a hint when the strip still runs past the edge.
    .board {
        display: flex;
        gap: 12px;
        align-items: flex-start;
        overflow-x: auto;
    }
    .col { flex: 1 1 180px; min-width: 180px; }
    .col--folded { flex: 0 0 auto; min-width: 0; }
    .col--folded .col__head { cursor: pointer; opacity: 0.6; white-space: nowrap; }
    .col__count { margin-left: 6px; font-weight: 500; color: #888; }
    .board__more { text-align: right; font-size: 12px; color: #888; margin-bottom: 2px; }
    .board__empty { color: #888; font-size: 13px; padding: 12px 0; }
    .col__head {
        font-size: 12px;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.04em;
        color: #888;
        padding: 2px 4px 8px;
        .col__wip {
            margin-left: 6px;
            font-weight: 400;
            text-transform: none;
            &--full { color: #b03a3a; font-weight: 600; }
        }
    }
    .col--done .col__head { color: #4a9d6e; }
    .tcard {
        margin-bottom: 10px;
        &--assigned { border-left: 3px solid #d9a24a; }
        &--ready { border-left: 3px solid #6c8fc7; }
        &--stuck { opacity: 0.72; }
        &--done { opacity: 0.85; border-left: 3px solid #4a9d6e; }
        .tcard__title { font-size: 13px; font-weight: 500; margin-bottom: 4px; }
        .tcard__open { margin-left: 6px; font-size: 12px; text-decoration: none; opacity: 0.7; }
        .tcard__ref { font-size: 12px; margin-bottom: 6px; word-break: break-all; }
        .tcard__meta { display: flex; flex-wrap: wrap; gap: 4px; margin-bottom: 4px; }
        // A long chip ellipsizes inside the card; its tooltip says it whole (RD2-13).
        .tcard__meta .n-tag { max-width: 100%; }
        .tcard__meta .n-tag .n-tag__content { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
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

/* A role tag that names the last hop, not where the task is now (task 562ac668). Top level: the
   drawer is teleported out of the panel. */
.tag--history { opacity: 0.75; font-style: italic; }

/* Top level, not under .boardsPanel: n-modal teleports its card to <body>, so a nested rule never
   reaches the "Board as a spec" modal. The block scrolls, not the page; long lines scroll sideways
   instead of painting past the card. white-space stays pre: the spec is YAML/JSON, and Copy gives
   specText, never what is on screen. */
.specBlock {
    margin: 0;
    padding: 10px 12px;
    max-height: 65vh;
    overflow: auto;
    white-space: pre;
    font-size: 12px;
    line-height: 1.45;
    background: var(--n-color-modal, #fafafa);
    border-radius: 4px;
}
</style>
