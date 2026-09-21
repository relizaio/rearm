<template>
    <n-drawer :show="task !== null" :width="560" placement="right"
              @update:show="(v: boolean) => { if (!v) emit('close') }">
        <n-drawer-content v-if="task" closable>
            <template #header>
                <div class="dhead">
                    <div class="dhead__title">{{ task.title }}</div>
                    <div class="dhead__sub">
                        <a v-if="task.sourceUrl" :href="task.sourceUrl" target="_blank" rel="noopener">
                            {{ (task.externalRef ?? 'draft').replace(/^github:/, '') }}
                        </a>
                        <span v-else>{{ (task.externalRef ?? 'draft — no tracker ref yet').replace(/^github:/, '') }}</span>
                        <n-tag size="small" :bordered="false" :type="statusTone(task.status)">
                            {{ task.status.replace(/_/g, ' ') }}
                        </n-tag>
                        <n-tag v-if="task.role" size="small" :bordered="false">{{ task.role }} · #{{ task.orderIndex }}</n-tag>
                    </div>
                </div>
            </template>

            <n-space vertical :size="16">
                <n-alert v-if="task.hold" type="error"
                         :title="task.hold.kind === 'HUMAN_GATE' ? 'Awaiting human review' : `On hold (${(task.hold.level ?? '').toLowerCase()})`">
                    {{ task.hold.reason }}
                    <div class="holdmeta">held by {{ actorLabel(task.hold.heldBy) }} · {{ ts(task.hold.heldAt) }}</div>
                    <template v-if="task.hold.kind === 'HUMAN_GATE'">
                        <n-input v-model:value="reviewNote" size="small" placeholder="Review note (optional)"
                                 style="margin-top: 8px"/>
                        <n-space style="margin-top: 8px">
                            <n-button size="small" type="primary"
                                      @click="emit('human-review', { task, approve: true, note: reviewNote })">
                                Approve {{ task.hold.gateRole }} pass
                            </n-button>
                            <n-button size="small" type="error" ghost
                                      @click="emit('human-review', { task, approve: false, note: reviewNote })">
                                Reject
                            </n-button>
                        </n-space>
                    </template>
                    <template v-else-if="task.hold.level === 'OPERATOR'">
                        <n-space style="margin-top: 8px">
                            <n-button size="small" @click="emit('operator-release', task)">Operator release</n-button>
                        </n-space>
                    </template>
                </n-alert>

                <n-alert v-if="humanStageRole" type="info" :title="`Human stage: ${humanStageRole.name}`">
                    <div v-if="humanStageRole.prompt" class="holdmeta">{{ humanStageRole.prompt }}</div>
                    <n-input v-model:value="reviewNote" size="small" placeholder="Sign-off note (optional)"
                             style="margin-top: 8px"/>
                    <n-space style="margin-top: 8px">
                        <n-button size="small" type="primary"
                                  @click="emit('human-signoff', { task, outcome: 'PASSED', note: reviewNote })">
                            Sign off PASSED
                        </n-button>
                        <n-button size="small" type="error" ghost
                                  @click="emit('human-signoff', { task, outcome: 'REJECTED', note: reviewNote })">
                            REJECTED
                        </n-button>
                    </n-space>
                </n-alert>

                <div v-if="!terminal" class="dsec">
                    <div class="dsec__h">Human review</div>
                    <div class="deprow">
                        <n-tag v-if="task.requireHumanReview" size="small" :bordered="false" type="warning">
                            next sign-off requires human review
                        </n-tag>
                        <n-button size="tiny" quaternary
                                  @click="emit('require-review', { task, value: !task.requireHumanReview })">
                            {{ task.requireHumanReview ? 'clear flag (operator)' : 'require human review of next sign-off' }}
                        </n-button>
                        <n-tag v-for="m in missingRequired" :key="m" size="small" :bordered="false" type="error">
                            required: {{ m }} ✗
                        </n-tag>
                    </div>
                </div>

                <div v-if="task.dependsOn?.length || dependents.length" class="dsec">
                    <div class="dsec__h">Dependencies</div>
                    <div v-if="task.dependsOn?.length" class="deprow">
                        <span class="deplab">after</span>
                        <n-tag v-for="d in resolvedDeps" :key="d.uuid" size="small" :bordered="false"
                               :type="d.status === 'COMPLETED' ? 'success' : 'warning'"
                               class="depclick" @click="emit('open', d)">
                            {{ label(d) }} · {{ d.status === 'COMPLETED' ? 'done' : 'pending' }}
                        </n-tag>
                    </div>
                    <div v-if="dependents.length" class="deprow">
                        <span class="deplab">blocks</span>
                        <n-tag v-for="d in dependents" :key="d.uuid" size="small" :bordered="false"
                               class="depclick" @click="emit('open', d)">
                            {{ label(d) }}
                        </n-tag>
                    </div>
                </div>

                <div v-if="task.parentTask || task.childTasks?.length" class="dsec">
                    <div class="dsec__h">Lineage</div>
                    <div class="deprow" v-if="parentTask">
                        <span class="deplab">parent</span>
                        <n-tag size="small" :bordered="false" type="info" class="depclick"
                               @click="emit('open', parentTask)">{{ label(parentTask) }}</n-tag>
                    </div>
                    <div class="deprow" v-if="childTasksResolved.length">
                        <span class="deplab">subtasks</span>
                        <n-tag v-for="c in childTasksResolved" :key="c.uuid" size="small" :bordered="false"
                               :type="c.status === 'COMPLETED' ? 'success' : 'default'" class="depclick"
                               @click="emit('open', c)">{{ label(c) }}</n-tag>
                    </div>
                </div>

                <div v-if="task.assignment" class="dsec">
                    <div class="dsec__h">Current assignment</div>
                    <div class="hist">
                        <div class="hist__row hist__row--active">
                            <span class="hist__role">{{ task.assignment.role }}</span>
                            <span class="hist__agent">{{ agentName(task.assignment.agent) }}</span>
                            <span class="hist__time">since {{ ts(task.assignment.assignedAt) }}
                                ({{ dur(task.assignment.assignedAt, null) }})</span>
                            <code v-if="task.assignment.promptVersion" class="hist__pv"
                                  title="Served role-prompt version">{{ task.assignment.promptVersion }}</code>
                        </div>
                    </div>
                </div>

                <div class="dsec" v-if="(task.usage?.reports ?? 0) > 0">
                    <div class="dsec__h">Usage</div>
                    <agent-usage-summary :usage="task.usage" :show-by-model="false" />
                </div>

                <div class="dsec" v-if="openFindingGroups.length">
                    <div class="dsec__h">Open findings</div>
                    <!-- From the NEWEST round of each indexed type. Every round carries forward
                         what the previous one left open, so the newest is the current state;
                         summing rounds would count one finding several times. -->
                    <div v-for="g in openFindingGroups" :key="String(g.priority)" class="fgroup">
                        <div class="fgroup__h">
                            <n-tag size="tiny" :bordered="false"
                                   :type="g.priority === 1 ? 'error' : 'warning'">
                                P{{ g.priority ?? '?' }}
                            </n-tag>
                            <span class="fgroup__count">{{ g.findings.length }}</span>
                        </div>
                        <div v-for="f in g.findings" :key="f.id ?? ''" class="frow">
                            <code class="frow__id">{{ f.id }}</code>
                            <span class="frow__title">{{ f.title }}</span>
                            <code v-if="findingLocation(f)" class="frow__loc">{{ findingLocation(f) }}</code>
                        </div>
                    </div>
                </div>

                <div class="dsec" v-if="taskDocuments.length">
                    <div class="dsec__h">Documents</div>
                    <div v-for="d in taskDocuments" :key="d.uuid ?? ''" class="drow">
                        <span class="drow__label">{{ documentLabel(d) }}</span>
                        <n-tag v-if="documentVerdict(d)" size="tiny" :bordered="false"
                               :type="verdictType(documentVerdict(d))">{{ documentVerdict(d) }}</n-tag>
                        <n-tag v-if="testCounts(d)" size="tiny" :bordered="false" type="info">
                            {{ testCounts(d)?.failed }} failed / {{ testCounts(d)?.passed }} passed
                        </n-tag>
                        <!-- A link only where we can build one. A guessed URL that 404s reads as
                             "the document is missing", so an unknown host shows the path instead. -->
                        <a v-if="documentFileUrl(d)" :href="documentFileUrl(d) ?? undefined" target="_blank"
                           rel="noopener" class="drow__path">{{ d.document?.path }}</a>
                        <code v-else class="drow__path drow__path--plain">{{ d.document?.path }}</code>
                        <code v-if="d.sourceCodeEntryDetails?.commit" class="drow__commit"
                              title="Commit this document is pinned to">{{ d.sourceCodeEntryDetails.commit.slice(0, 8) }}</code>
                    </div>
                </div>

                <div class="dsec">
                    <div class="dsec__h">History</div>
                    <div v-if="!history.length" class="empty">No hops recorded yet.</div>
                    <div class="hist">
                        <div v-for="(e, i) in history" :key="i" class="hist__row">
                            <template v-if="e.kind === 'signoff'">
                                <n-tag size="tiny" :bordered="false"
                                       :type="e.rec.outcome === 'PASSED' ? 'success' : 'error'">
                                    {{ e.rec.outcome }}
                                </n-tag>
                                <span class="hist__role">{{ e.rec.role }}</span>
                                <n-tag v-if="e.rec.reviewedBy" size="tiny" :bordered="false" type="info">human</n-tag>
                                <span class="hist__agent">{{ actorLabel(e.rec.reviewedBy) || agentName(e.rec.agent) }}</span>
                                <span class="hist__time">{{ ts(e.rec.signedOffAt) }}<template v-if="e.rec.assignedAt">
                                    · worked {{ dur(e.rec.assignedAt, e.rec.signedOffAt) }}</template></span>
                                <code v-if="e.rec.promptVersion" class="hist__pv"
                                      title="Served role-prompt version">{{ e.rec.promptVersion }}</code>
                                <span v-if="hopHasUsage(e.rec)" class="hist__usage"
                                      :title="hopTitle(e.rec)">{{ hopLabel(e.rec) }}</span>
                                <div v-if="hopOutputs(e.rec).length" class="hist__outputs">
                                    <span v-for="o in hopOutputs(e.rec)" :key="o.uuid ?? ''" class="hist__output">
                                        <a v-if="documentFileUrl(o)" :href="documentFileUrl(o) ?? undefined"
                                           target="_blank" rel="noopener">{{ documentLabel(o) }}</a>
                                        <span v-else>{{ documentLabel(o) }}</span>
                                    </span>
                                </div>
                                <div v-if="e.rec.note" class="hist__note">{{ e.rec.note }}</div>
                            </template>
                            <template v-else>
                                <n-tag size="tiny" :bordered="false" type="warning">RETURNED</n-tag>
                                <span class="hist__role">{{ e.rec.role }}</span>
                                <span class="hist__agent">{{ agentName(e.rec.agent) }}</span>
                                <span class="hist__time">{{ ts(e.rec.returnedAt) }} · {{ e.rec.reason }}</span>
                                <span v-if="hopHasUsage(e.rec)" class="hist__usage"
                                      :title="hopTitle(e.rec)">{{ hopLabel(e.rec) }}</span>
                                <div v-if="hopOutputs(e.rec).length" class="hist__outputs">
                                    <span v-for="o in hopOutputs(e.rec)" :key="o.uuid ?? ''" class="hist__output">
                                        <a v-if="documentFileUrl(o)" :href="documentFileUrl(o) ?? undefined"
                                           target="_blank" rel="noopener">{{ documentLabel(o) }}</a>
                                        <span v-else>{{ documentLabel(o) }}</span>
                                    </span>
                                </div>
                                <div v-if="e.rec.description" class="hist__note">{{ e.rec.description }}</div>
                            </template>
                        </div>
                    </div>
                </div>

                <div v-if="task.prUrls?.length" class="dsec">
                    <div class="dsec__h">Pull requests</div>
                    <div class="deprow">
                        <a v-for="pr in task.prUrls" :key="pr" :href="pr" target="_blank"
                           rel="noopener" class="prlink2">{{ pr.split('/').slice(-3).join('/') }}</a>
                    </div>
                </div>

                <div v-if="task.statusHistory?.length" class="dsec">
                    <div class="dsec__h">Status history</div>
                    <div class="shist">
                        <div v-for="(c, i) in task.statusHistory" :key="i" class="shist__row">
                            <span class="shist__time">{{ ts(c.at) }}</span>
                            <span class="shist__arrow">{{ (c.from ?? '·').toLowerCase().replace(/_/g, ' ') }} → {{ c.to.toLowerCase().replace(/_/g, ' ') }}</span>
                            <code class="shist__trig">{{ c.trigger }}</code>
                            <span v-if="actorLabel(c.actor)" class="shist__by">by {{ actorLabel(c.actor) }}</span>
                            <span v-if="i > 0" class="shist__dur">+{{ dur(task.statusHistory[i-1].at, c.at) || '0m' }}</span>
                        </div>
                    </div>
                </div>

                <div class="dsec">
                    <div class="dsec__h">Provenance</div>
                    <div class="prov">
                        <div>created {{ ts(task.createdDate) }}<template v-if="task.completedAt"> · completed {{ ts(task.completedAt) }}</template></div>
                        <div v-if="task.registeredBySession">registered by session <code>{{ shortId(task.registeredBySession) }}</code></div>
                        <div v-if="task.sessions?.length">worked by {{ task.sessions.length }} session{{ task.sessions.length > 1 ? 's' : '' }}:
                            <code v-for="s in task.sessions" :key="s" class="sesschip">{{ shortId(s) }}</code>
                        </div>
                    </div>
                </div>
            </n-space>
        </n-drawer-content>
    </n-drawer>
</template>

<script lang="ts" setup>
import { computed, ref, watch } from 'vue'
import { NAlert, NButton, NDrawer, NDrawerContent, NInput, NSpace, NTag } from 'naive-ui'
import AgentUsageSummary from './AgentUsageSummary.vue'
import { costLabel, formatTokens, totalTokens } from '@/utils/agentUsage'
import { actorLabel } from '@/utils/agentActors'
import {
    DocumentRelease,
    Finding,
    documentFileUrl,
    documentLabel,
    documentVerdict,
    findingLocation,
    groupByPriority,
    outputsOfHop,
    testCounts,
    verdictType,
} from '@/utils/agentDocuments'

const props = defineProps<{
    task: any | null
    tasks: any[]
    agentNames: Record<string, string>
    roles?: any[]
}>()
const emit = defineEmits<{
    (e: 'close'): void
    (e: 'open', task: any): void
    (e: 'human-review', p: { task: any, approve: boolean, note: string }): void
    (e: 'human-signoff', p: { task: any, outcome: string, note: string }): void
    (e: 'operator-release', task: any): void
    (e: 'require-review', p: { task: any, value: boolean }): void
}>()

const reviewNote = ref('')

// Documents this task has produced, newest first as the server returns them.
const taskDocuments = computed<DocumentRelease[]>(() => props.task?.documents ?? [])

// Findings still open, grouped by priority. The server already restricts this to the newest round
// of each indexed type; grouping is purely presentation.
const openFindingGroups = computed(() => groupByPriority((props.task?.openFindings ?? []) as Finding[]))

// The documents a hop recorded as its outputs. A hop stores uuids and the task carries the
// releases, so they are resolved here rather than holding two shapes of the same thing.
function hopOutputs (rec: any): DocumentRelease[] {
    return outputsOfHop(rec?.outputs, taskDocuments.value)
}

// Per-hop cost, shown inline on the history row rather than in a column: a hop
// that cost nothing to report is the common case, and an always-present column
// of dashes would push the role and agent off the row for no gain.
function hopHasUsage (rec: any): boolean {
    return (rec?.usage?.reports ?? 0) > 0
}

function hopLabel (rec: any): string {
    return costLabel(rec.usage) + ' · ' + formatTokens(totalTokens(rec.usage)) + ' tok'
}

function hopTitle (rec: any): string {
    const u = rec.usage ?? {}
    return `${u.requests ?? 0} requests, ${u.turns ?? 0} turns\n` +
        `in ${formatTokens(u.inputTokens)} · out ${formatTokens(u.outputTokens)} · ` +
        `cache read ${formatTokens(u.cacheReadTokens)} · cache write ${formatTokens(u.cacheWriteTokens)}` +
        (u.costComplete === false ? '\nSome rows had no applicable price: the cost is a lower bound.' : '')
}
watch(() => props.task?.uuid, () => { reviewNote.value = '' })

const terminal = computed(() =>
    props.task?.status === 'COMPLETED' || props.task?.status === 'CANCELLED')

// Task queued in a HUMAN-kind role: org admins sign off directly (no claim step).
const humanStageRole = computed(() => {
    if (props.task?.status !== 'QUEUED') return null
    const rc = (props.roles ?? []).find(r => r.name === props.task.role)
    return rc?.kind === 'HUMAN' ? rc : null
})

// Active REQUIRED roles whose most recent sign-off on this task is not PASSED --
// mirrors the server-side completion gate so the gap is visible before "done".
const missingRequired = computed(() => {
    if (!props.task || terminal.value || props.task.childTasks?.length) return []
    return (props.roles ?? [])
        .filter(r => r.active && r.necessity === 'REQUIRED')
        .map(r => r.name)
        .filter((role: string) => {
            const last = [...(props.task.signOffs ?? [])].reverse()
                .find((s: any) => (s.role ?? '').toLowerCase() === role.toLowerCase())
            return !last || last.outcome !== 'PASSED'
        })
})

const resolvedDeps = computed(() => (props.task?.dependsOn ?? [])
    .map((d: string) => props.tasks.find(t => t.uuid === d) ?? { uuid: d, title: 'unknown', status: 'UNKNOWN' }))
const dependents = computed(() => props.task
    ? props.tasks.filter(t => (t.dependsOn ?? []).includes(props.task.uuid)) : [])
const parentTask = computed(() => props.task?.parentTask
    ? props.tasks.find(t => t.uuid === props.task.parentTask) ?? null : null)
const childTasksResolved = computed(() => (props.task?.childTasks ?? [])
    .map((c: string) => props.tasks.find(t => t.uuid === c)).filter(Boolean))

// Sign-offs and returns interleaved chronologically — the task's hop log.
const history = computed(() => {
    if (!props.task) return []
    const rows = [
        ...(props.task.signOffs ?? []).map((rec: any) => ({ kind: 'signoff', rec, at: rec.signedOffAt })),
        ...(props.task.returns ?? []).map((rec: any) => ({ kind: 'return', rec, at: rec.returnedAt })),
    ]
    return rows.sort((a, b) => String(a.at ?? '').localeCompare(String(b.at ?? '')))
})

function agentName (uuid: string | null | undefined): string {
    if (!uuid) return '—'
    return props.agentNames[uuid] ?? shortId(uuid)
}

function label (t: any): string {
    if (t.externalRef?.includes('#')) return '#' + t.externalRef.split('#').pop()
    return (t.title ?? 'task').length > 20 ? t.title.slice(0, 19) + '…' : (t.title ?? 'task')
}

function shortId (u: string): string {
    return u ? u.slice(0, 8) : ''
}

function ts (iso: string | null | undefined): string {
    if (!iso) return '—'
    const d = new Date(iso)
    return isNaN(d.getTime()) ? '—' : d.toLocaleString('en-CA', {
        month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
    })
}

function dur (from: string | null | undefined, to: string | null | undefined): string {
    if (!from) return ''
    const a = new Date(from).getTime()
    const b = to ? new Date(to).getTime() : Date.now()
    if (isNaN(a) || isNaN(b) || b < a) return ''
    const mins = Math.round((b - a) / 60000)
    if (mins < 60) return `${mins}m`
    const h = Math.floor(mins / 60)
    return h < 48 ? `${h}h ${mins % 60}m` : `${Math.floor(h / 24)}d`
}

function statusTone (s: string): string {
    if (s === 'COMPLETED') return 'success'
    if (s === 'ON_HOLD' || s === 'CANCELLED') return 'error'
    if (s === 'ASSIGNED') return 'warning'
    return 'default'
}
</script>

<style scoped lang="scss">
.dhead {
    &__title { font-size: 15px; font-weight: 600; }
    &__sub { display: flex; align-items: center; gap: 8px; margin-top: 4px; font-size: 12px; flex-wrap: wrap; }
}
.dsec {
    &__h {
        font-size: 11px;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.05em;
        color: #999;
        margin-bottom: 6px;
    }
}
.fgroup {
    margin-bottom: 8px;
    &__h { display: flex; align-items: center; gap: 6px; margin-bottom: 3px; }
    &__count { font-size: 11px; color: #999; }
}
.frow {
    display: flex; align-items: baseline; gap: 7px; font-size: 12.5px;
    padding: 2px 0 2px 10px;
    &__id { font-weight: 600; font-size: 11.5px; }
    &__title { flex: 1; }
    &__loc { font-size: 11px; color: #999; }
}
.drow {
    display: flex; align-items: baseline; flex-wrap: wrap; gap: 7px; font-size: 12.5px;
    padding: 3px 0;
    &__label { font-weight: 600; }
    &__path { font-size: 11.5px; }
    &__path--plain { color: #777; }
    &__commit { font-size: 11px; color: #999; }
}
.deprow { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; margin-bottom: 4px; }
.deplab { font-size: 10px; text-transform: uppercase; letter-spacing: 0.05em; color: #999; min-width: 52px; }
.depclick { cursor: pointer; }
.hist {
    &__row {
        padding: 6px 0;
        border-bottom: 1px solid rgba(128, 128, 128, 0.12);
        font-size: 12.5px;
        display: flex;
        align-items: baseline;
        flex-wrap: wrap;
        gap: 7px;
        &--active { border-left: 3px solid #d9a24a; padding-left: 8px; }
        &:last-child { border-bottom: none; }
    }
    &__role { font-weight: 600; }
    &__agent { color: #666; }
    &__time { color: #999; font-size: 11.5px; }
    &__pv { font-size: 10.5px; color: #999; background: rgba(128, 128, 128, 0.1); padding: 0 5px; border-radius: 4px; }
    // Pushed to the right so the hop reads role/agent/time first and cost last:
    // the money is the qualifier on the hop, not its headline.
    &__usage { margin-left: auto; font-size: 11.5px; color: #777; white-space: nowrap; }
    &__outputs { width: 100%; display: flex; flex-wrap: wrap; gap: 8px; padding-left: 2px; margin-top: 3px; }
    &__output { font-size: 11.5px; color: #666; }
    &__note { width: 100%; color: #555; font-size: 12px; padding-left: 2px; }
}
.shist {
    font-size: 11.5px;
    &__row { display: flex; gap: 8px; align-items: baseline; padding: 2px 0; flex-wrap: wrap; }
    &__time { color: #999; font-family: monospace; }
    &__arrow { color: #555; }
    &__trig { font-size: 10px; color: #888; background: rgba(128, 128, 128, 0.1); padding: 0 4px; border-radius: 4px; }
    &__by { color: #777; font-size: 10.5px; }
    &__dur { color: #b0854a; font-size: 10.5px; }
}
.holdmeta { font-size: 11.5px; color: #888; margin-top: 4px; white-space: pre-wrap; }
.prov { font-size: 12px; color: #777; div { margin-bottom: 3px; } }
.sesschip { font-size: 10.5px; margin-right: 4px; background: rgba(128, 128, 128, 0.1); padding: 0 5px; border-radius: 4px; }
.prlink2 { font-size: 12.5px; }
.empty { color: #888; font-size: 12.5px; }
</style>
