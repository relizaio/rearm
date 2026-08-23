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
                <n-alert v-if="task.holdReason" type="error" title="On hold">
                    {{ task.holdReason }}
                </n-alert>

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
                                <span class="hist__agent">{{ agentName(e.rec.agent) }}</span>
                                <span class="hist__time">{{ ts(e.rec.signedOffAt) }}
                                    · worked {{ dur(e.rec.assignedAt, e.rec.signedOffAt) }}</span>
                                <code v-if="e.rec.promptVersion" class="hist__pv"
                                      title="Served role-prompt version">{{ e.rec.promptVersion }}</code>
                                <div v-if="e.rec.note" class="hist__note">{{ e.rec.note }}</div>
                            </template>
                            <template v-else>
                                <n-tag size="tiny" :bordered="false" type="warning">RETURNED</n-tag>
                                <span class="hist__role">{{ e.rec.role }}</span>
                                <span class="hist__agent">{{ agentName(e.rec.agent) }}</span>
                                <span class="hist__time">{{ ts(e.rec.returnedAt) }} · {{ e.rec.reason }}</span>
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
import { computed } from 'vue'
import { NAlert, NDrawer, NDrawerContent, NSpace, NTag } from 'naive-ui'

const props = defineProps<{
    task: any | null
    tasks: any[]
    agentNames: Record<string, string>
}>()
const emit = defineEmits<{
    (e: 'close'): void
    (e: 'open', task: any): void
}>()

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
    &__note { width: 100%; color: #555; font-size: 12px; padding-left: 2px; }
}
.shist {
    font-size: 11.5px;
    &__row { display: flex; gap: 8px; align-items: baseline; padding: 2px 0; flex-wrap: wrap; }
    &__time { color: #999; font-family: monospace; }
    &__arrow { color: #555; }
    &__trig { font-size: 10px; color: #888; background: rgba(128, 128, 128, 0.1); padding: 0 4px; border-radius: 4px; }
    &__dur { color: #b0854a; font-size: 10.5px; }
}
.prov { font-size: 12px; color: #777; div { margin-bottom: 3px; } }
.sesschip { font-size: 10.5px; margin-right: 4px; background: rgba(128, 128, 128, 0.1); padding: 0 5px; border-radius: 4px; }
.prlink2 { font-size: 12.5px; }
.empty { color: #888; font-size: 12.5px; }
</style>
