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
                <n-button size="small" quaternary @click="registering = { title: '', externalRef: '', sourceUrl: '' }"
                          v-if="currentBoard">+ New task</n-button>
                <n-button size="small" quaternary @click="startEditBoard(currentBoard)" v-if="currentBoard">Edit board</n-button>
                <n-button size="small" quaternary @click="showRoles = true" v-if="currentBoard">Roles</n-button>
                <n-button size="small" quaternary @click="openSpec" v-if="currentBoard">View as spec</n-button>
                <n-button size="small" quaternary @click="subscribeToBoard" v-if="currentBoard"
                          title="Get notified when this board needs a person: alerts, holds, returns, tasks waiting">
                    Subscribe
                </n-button>
                <n-button size="small" quaternary @click="applyKinds = ['BOARD']" v-if="canApplySpec">Apply spec</n-button>
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
                <template v-if="actorLabel(currentBoard.lock.lockedBy)"> Held by {{ actorLabel(currentBoard.lock.lockedBy) }}.</template>
                <n-button
                    v-if="currentBoard.lock.level === 'OPERATOR'"
                    size="tiny" style="margin-left: 10px"
                    @click="operatorLock(false)"
                >Operator unlock</n-button>
            </n-alert>
            <div class="boardmeta">
                <n-tooltip v-if="currentBoard.declarative" trigger="hover">
                    <template #trigger>
                        <span class="srcchip">applied from {{ provenanceLabel(currentBoard.declarative) }}</span>
                    </template>
                    Last configured from a board file, {{ formatEventTime(currentBoard.declarative.appliedAt) }}
                    (spec {{ (currentBoard.declarative.specHash ?? '').slice(0, 12) }}). Edits made here since
                    show in the next export, and the file wins for every field it declares when applied again.
                </n-tooltip>
                <span v-for="s in currentBoard.sources ?? []" :key="s" class="srcchip">{{ s }}</span>
                <n-tag size="tiny" :bordered="false" :type="currentBoard.coordinatorSeat ? 'success' : 'default'">
                    {{ currentBoard.coordinatorSeat ? 'coordinator connected' : 'no coordinator' }}
                </n-tag>
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
                <n-button v-if="!isLocked" size="tiny" quaternary @click="operatorLock(true)">Operator lock</n-button>
            </div>
            <n-alert v-if="currentBoard.missingCapabilities?.length" type="warning" class="lockbanner">
                Delivery loop incomplete: no active role or the coordinator covers
                {{ currentBoard.missingCapabilities.join(', ') }} — give a role the capability, or declare
                that the coordinator covers it in the board's settings.
            </n-alert>
            <n-alert v-if="awaitingHumanReview.length" type="error" class="lockbanner">
                {{ awaitingHumanReview.length }} task{{ awaitingHumanReview.length > 1 ? 's' : '' }} awaiting your review:
                <n-button v-for="t in awaitingHumanReview" :key="t.uuid" size="tiny" quaternary
                          style="margin-left: 6px" @click="openTask(t)">
                    {{ t.externalRef ? '#' + t.externalRef.split('#').pop() : t.title }} ({{ t.hold.gateRole }})
                </n-button>
            </n-alert>
            <n-collapse v-if="currentBoard.events?.length" class="eventsfeed">
                <n-collapse-item :title="`Board events (${currentBoard.events.length})`" name="ev">
                    <div v-for="(e, i) in [...currentBoard.events].reverse()" :key="i" class="evrow">
                        <n-tag size="tiny" :bordered="false"
                               :type="e.kind === 'ALERT' ? 'error' : e.kind === 'LOCKED' ? 'warning' : 'default'">{{ e.kind }}</n-tag>
                        <span class="evmsg">{{ e.message }}</span>
                        <span class="evmeta">{{ actorLabel(e.actor) }} · {{ formatEventTime(e.eventAt) }}</span>
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
                    <div class="col__head">
                        {{ r.name }}
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
                <div class="col" v-if="byStatus('DELIVERING').length">
                    <div class="col__head">Delivering</div>
                    <TaskCard v-for="t in byStatus('DELIVERING')" :key="t.uuid" :t="t"/>
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
                <AiAgentTaskTableView :tasks="tasks" :agent-names="agentNames" :board-has-sources="boardHasSources"
                                      @open="openTask"/>
            </n-tab-pane>
            <n-tab-pane name="usage" tab="Usage">
                <AgentBoardUsagePanel :board-uuid="selectedBoard" :tasks="tasks" :agent-names="agentNames"
                                      :budget-micros="currentBoard?.budgetMicros" :lifetime-spent-micros="lifetimeSpentMicros"
                                      :soft-alert-percent="currentBoard?.softAlertPercent"/>
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
                @set-strength="setStrength" @operator-hold="operatorHold" @set-budget="setBudget"
                :can-reopen="canReopen" @reopen="reopenTask"/>

            <!-- A person registers a task directly; on a board with sources it names the issue, so
                 the coordinator's intake of the same issue finds it rather than duplicating it. -->
            <n-modal :show="registering !== null" preset="card" title="New task" style="max-width: 520px"
                     @update:show="(v: boolean) => { if (!v) registering = null }">
                <n-space vertical :size="10" v-if="registering">
                    <n-input v-model:value="registering.title" placeholder="Title"/>
                    <n-input v-model:value="registering.externalRef"
                             :placeholder="(currentBoard.sources?.length ?? 0) > 0
                                 ? 'Tracker issue, e.g. github:owner/repo#42 (required)'
                                 : 'Tracker issue (optional)'"/>
                    <n-input v-model:value="registering.sourceUrl" placeholder="Link (optional)"/>
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
                <n-select v-model:value="editingBoard.priorityType" :options="priorityOptions"
                          placeholder="Priority enforcement"/>
                <!-- Budget and stops (task 40f270be): the board file's settings, checked as a file's are.
                     A value emptied here is cleared; one left alone is not sent. -->
                <div class="flabel">budget and stops</div>
                <n-space :size="8" data-testid="board-settings">
                    <n-input-number v-model:value="editingBoard.budgetDollars" :min="0" :step="1" :precision="2"
                                    placeholder="no budget" style="width: 170px">
                        <template #prefix><span class="flabel">budget $</span></template>
                    </n-input-number>
                    <n-input-number v-model:value="editingBoard.softAlertPercent" :min="1" :max="100"
                                    placeholder="80" style="width: 150px">
                        <template #prefix><span class="flabel">alert %</span></template>
                    </n-input-number>
                    <n-input-number v-model:value="editingBoard.cycleCap" :min="1" placeholder="3" style="width: 140px">
                        <template #prefix><span class="flabel">cycle cap</span></template>
                    </n-input-number>
                    <n-input-number v-model:value="editingBoard.noProgressRepeatsToStop" :min="1" placeholder="1"
                                    style="width: 170px">
                        <template #prefix><span class="flabel">no-progress stop</span></template>
                    </n-input-number>
                    <n-input-number v-model:value="editingBoard.blockingPriority" :min="1" :max="priorityLevels" placeholder="strict"
                                    style="width: 150px">
                        <template #prefix><span class="flabel">blocking P≤</span></template>
                    </n-input-number>
                    <n-input-number v-model:value="editingBoard.completionPriority" :min="1" :max="priorityLevels" placeholder="strict"
                                    style="width: 160px">
                        <template #prefix><span class="flabel">completion P≤</span></template>
                    </n-input-number>
                </n-space>
                <!-- task c0a2134c: on (the default, null) a no-progress or cycle-cap stop parks for the
                     coordinator first, which may release it once per stop kind per task or escalate it. -->
                <n-checkbox :checked="editingBoard.coordinatorStopRelease !== false" data-testid="board-stop-release"
                            @update:checked="(v: boolean) => { editingBoard.coordinatorStopRelease = v }">
                    the coordinator may release a no-progress or cycle-cap stop once per task
                </n-checkbox>
                <n-text depth="3" style="font-size: 11.5px; margin-top: -6px;">
                    Blank budget: no board limit. Blank priorities: strict, every open item counts. The
                    placeholders are the defaults a blank field takes. Unchecked, every stop is the
                    operator's; budget stops always are.
                </n-text>
                <!-- What the coordinator seat does itself, e.g. merging once the last required role
                     has passed. The tracker verbs are always the coordinator's, so they are not offered. -->
                <n-select v-model:value="editingBoard.coordinatorCapabilities" multiple
                          :options="coordinatorCapabilityOptions"
                          placeholder="Coordinator covers (e.g. PR_MERGE when it merges)"/>
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
                <div>
                    <div class="flabel" style="margin-bottom: 4px">document path templates</div>
                    <n-input v-for="row in templateTypeRows" :key="row.spec"
                             v-model:value="editingBoard.documentPaths[row.spec]"
                             :placeholder="row.placeholder" style="margin-bottom: 4px;">
                        <template #prefix><span class="flabel">{{ row.spec.toLowerCase().replace(/_/g, ' ') }}</span></template>
                    </n-input>
                    <n-text depth="3" style="font-size: 11.5px;">
                        Placeholders: <code>{task}</code> <code>{round}</code> <code>{type}</code>
                        <code>{component}</code>. Blank uses the default shown, which the server
                        picks by scope: per task for a type a role produces per task, else one
                        file per component.
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
                <n-space justify="end">
                    <n-button quaternary @click="editingBoard = null">Cancel</n-button>
                    <n-button type="primary" :loading="saving" @click="saveBoard">Save</n-button>
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
                <pre class="specBlock">{{ specText }}</pre>
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
import { computed, defineComponent, h, onMounted, ref, watch } from 'vue'
import type { ComputedRef } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { useStore } from 'vuex'
import { NAlert, NButton, NCard, NCheckbox, NCollapse, NCollapseItem, NDataTable, NIcon, NInput, NInputNumber, NModal, NRadioButton, NRadioGroup, NSelect, NSpace, NSpin, NTabPane, NTabs, NTag, NTooltip, DataTableColumns, useNotification, NText } from 'naive-ui'
import { QuestionCircle20Regular } from '@vicons/fluent'
import AiAgentTaskPertView from '@/components/AiAgentTaskPertView.vue'
import AiAgentTaskTimelineView from '@/components/AiAgentTaskTimelineView.vue'
import AiAgentTaskTableView from '@/components/AiAgentTaskTableView.vue'
import AgentBoardUsagePanel from '@/components/AgentBoardUsagePanel.vue'
import { budgetChip, dollarsToMicros, hopBudgetInput, microsToDollars, settingsPatch } from '@/utils/agentBudget'
import { actorLabel } from '@/utils/agentActors'
import { refLabel, roleTagFor, subtaskProgress, subtaskTag } from '@/utils/agentTaskLabels'
import { CAPABILITIES, COORDINATOR_CAPABILITIES, toOptions } from '@/utils/agentCapabilities'
import { templateRows } from '@/utils/agentDocuments'
import { isOrgAdmin } from '@/utils/agentReopen'
import { prChips } from '@/utils/agentDelivery'

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
import { useAgentTaskActions } from '@/utils/agentTaskActions'
import { taskPagePath } from '@/utils/agentTaskFormat'
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

// --- the board as a spec -------------------------------------------------
// Rendered from the server's own export rather than assembled here, so what the
// page shows is the document a client would receive, not a lookalike.
const showSpec = ref(false)
const specLoading = ref(false)
const specFormat = ref<'yaml' | 'json'>('yaml')
const specRaw = ref<any>(null)

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
    try {
        specRaw.value = await store.dispatch('fetchAgentBoardSpec', currentBoard.value.uuid)
    } catch (e: any) {
        notify('error', 'Could not read the board spec', e?.message ?? String(e))
        showSpec.value = false
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

function formatEventTime (iso: string | null | undefined): string {
    if (!iso) return ''
    const d = new Date(iso)
    return isNaN(d.getTime()) ? '' : d.toLocaleString('en-CA', {
        month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
    })
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
        .map(([agent, count]) => ({ agent, count, name: agentNames.value[agent] ?? agent.slice(0, 8) }))
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
    completeTask, cancelTask, reopenTask, decideFindings, requireReview, setStrength, operatorHold, setBudget,
} = useAgentTaskActions(async (t: any, keepOpen: boolean) => {
    if (!keepOpen) selectedTask.value = null
    await refreshBoardContent()
    if (keepOpen) selectedTask.value = tasks.value.find(x => x.uuid === t.uuid) ?? null
})

const priorityLevels = computed(() =>
    store.getters.orgById(props.orgUuid)?.settings?.findingPriorityLevels ?? 3)

const registering = ref<{ title: string, externalRef: string, sourceUrl: string } | null>(null)
const canRegister = computed(() => !!registering.value?.title.trim()
    && ((currentBoard.value?.sources?.length ?? 0) === 0 || !!registering.value?.externalRef.trim()))

async function registerTask () {
    if (!registering.value || !currentBoard.value) return
    try {
        await store.dispatch('agentTaskRegister', {
            boardUuid: currentBoard.value.uuid,
            input: {
                title: registering.value.title.trim(),
                externalRef: registering.value.externalRef.trim() || null,
                sourceUrl: registering.value.sourceUrl.trim() || null,
            },
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

function byStatus (s: string): any[] {
    const list = tasks.value.filter(t => t.status === s)
    // completed reads best newest-first; everything else by priority
    if (s === 'COMPLETED') {
        return [...list].sort((a, b) =>
            String(b.completedAt ?? '').localeCompare(String(a.completedAt ?? '')))
    }
    return byPriority(list)
}
// QUEUED + ASSIGNED tasks grouped under their current role column
function atRole (role: string): any[] {
    return byPriority(tasks.value.filter(t =>
        (t.status === 'QUEUED' || t.status === 'ASSIGNED') && t.role === role))
}

// Compact task card as a local render component to keep the template lean.
const TaskCard = defineComponent({
    props: { t: { type: Object, required: true } },
    setup (p: any) {
        return () => h(NCard, { size: 'small', style: 'cursor: pointer', onClick: () => openTask(p.t), class: ['tcard',
            p.t.status === 'ASSIGNED' ? 'tcard--assigned' : '',
            p.t.status === 'COMPLETED' ? 'tcard--done' : '',
            workRank(p.t) === 1 ? 'tcard--ready' : '',
            workRank(p.t) >= 2 && workRank(p.t) <= 3 ? 'tcard--stuck' : ''] }, { default: () => [
            h('div', { class: 'tcard__title' }, [
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
                blockedBy(p.t).length ? h(NTooltip, { trigger: 'hover' }, {
                    trigger: () => h(NTag, { size: 'tiny', bordered: false, type: 'warning' }, {
                        default: () => 'blocked by ' + blockedBy(p.t)
                            .map((d: string) => depLabel(tasks.value.find(x => x.uuid === d) ?? { uuid: d }))
                            .join(', '),
                    }),
                    default: () => 'Not assignable until every dependency is COMPLETED; the server releases it automatically.',
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
        render: (r: any) => h(NButton, { size: 'tiny', quaternary: true, onClick: () => { editingRoleIsNew.value = false; editingRole.value = { ...r, hopDollars: microsToDollars(r.hopBudgetMicros),
            producesOutputTypes: (r.producesOutputs ?? []).map((p: any) => p?.specification).filter(Boolean),
            strength: strengthDraft(r) } } }, { default: () => 'Edit' }),
    },
]

onMounted(refreshBoards)
watch(selectedBoard, async () => {
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
// Reopening a completed task is an org admin's (agentTaskReopen); the server decides.
const canReopen = computed<boolean>(() => isOrgAdmin(store.getters.myuser?.permissions?.permissions, props.orgUuid))

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
    await loadLifetimeSpend()
    // agent uuid -> display name map for timeline/table/drawer; loaded
    // lazily once per panel life, refreshed with board content
    models.value = await store.dispatch('fetchModelOntologiesOfOrg', props.orgUuid).catch(() => []) ?? []
    const agents = await store.dispatch('fetchAgentsOfOrg', props.orgUuid) ?? []
    const m: Record<string, string> = {}
    for (const a of agents) m[a.uuid] = a.effectiveDisplayName || a.name || a.uuid.slice(0, 8)
    agentNames.value = m
}

function startEditBoard (b: any | null) {
    editingBoardIsNew.value = b === null
    // documentsRepo comes back as the repository ROW; the editor works in uris, and the mutation
    // takes one and resolves it. Flattened here so the input binds to a string.
    editingBoard.value = b ? { ...b, sources: [...(b.sources ?? [])], budgetDollars: microsToDollars(b.budgetMicros),
        documentsRepo: b.documentsRepo?.uri ?? '',
        documentPaths: { ...(b.documentPaths ?? {}) },
        coordinatorCapabilities: [...(b.coordinatorCapabilities ?? [])] }
        : { name: '', description: '', sources: [], coordinatorPrompt: '', perAgentWipLimit: 2,
            priorityType: 'LAX', seedFromPresets: true, documentsRepo: '', documentPaths: {},
            coordinatorCapabilities: [] }
}

/** hopBudgetMicros for the role input: set, removed (null), or left out when never set and still blank. */
function hopBudgetField (draft: any): Record<string, number | null> {
    if (draft?.kind === 'HUMAN') return {}
    const hop = hopBudgetInput(draft?.hopBudgetMicros, draft?.hopDollars)
    return hop === undefined ? {} : { hopBudgetMicros: hop }
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
const spendChip = computed(() => null === lifetimeSpentMicros.value ? null
    : budgetChip(lifetimeSpentMicros.value, currentBoard.value?.budgetMicros, currentBoard.value?.softAlertPercent))

async function saveBoard () {
    if (!editingBoard.value?.name?.trim()) {
        notification.error({ content: 'Board name is required', duration: 8000 })
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
        const settings = settingsPatch(original, {
            budgetMicros: dollarsToMicros(editingBoard.value.budgetDollars),
            softAlertPercent: editingBoard.value.softAlertPercent ?? null,
            cycleCap: editingBoard.value.cycleCap ?? null,
            noProgressRepeatsToStop: editingBoard.value.noProgressRepeatsToStop ?? null,
            blockingPriority: editingBoard.value.blockingPriority ?? null,
            completionPriority: editingBoard.value.completionPriority ?? null,
            coordinatorStopRelease: editingBoard.value.coordinatorStopRelease ?? null,
        })
        if (settings) input.settings = settings
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
        notification.error({ content: `Save failed: ${e?.message ?? e}`, duration: 8000 })
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

async function operatorLock (lock: boolean) {
    let reason: string | undefined
    if (lock) {
        reason = window.prompt('Lock reason (shown to agents and on the board):') ?? undefined
        if (reason === undefined) return
    }
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
