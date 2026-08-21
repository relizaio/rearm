<template>
    <div class="taskBoard">
        <div class="section-head">
            <h5>Task pipeline</h5>
            <span class="sub" v-if="roleColumns.length">
                {{ openTasks.length }} open · {{ completedTasks.length }} completed
            </span>
        </div>
        <div v-if="tasks.length === 0" class="empty">
            No tasks yet. Agents register tracker issues via <code>rearm agent task register</code>.
        </div>
        <div class="board" v-else>
            <div class="col" v-for="col in roleColumns" :key="col">
                <div class="col__head">{{ col }}</div>
                <n-card
                    v-for="t in tasksAtStage(col)"
                    :key="t.uuid"
                    class="tcard"
                    size="small"
                    :class="{ 'tcard--claimed': isLiveClaim(t) }"
                >
                    <div class="tcard__title">{{ t.title }}</div>
                    <div class="tcard__ref">
                        <a v-if="t.sourceUrl" :href="t.sourceUrl" target="_blank" rel="noopener">{{ refLabel(t) }}</a>
                        <span v-else>{{ refLabel(t) }}</span>
                    </div>
                    <div class="tcard__meta">
                        <n-tag v-if="isLiveClaim(t)" size="tiny" type="warning" :bordered="false">
                            claimed · lease {{ leaseLeft(t) }}
                        </n-tag>
                        <n-tag v-else size="tiny" :bordered="false">unclaimed</n-tag>
                        <n-tag v-if="t.parentTask" size="tiny" :bordered="false" type="info">split child</n-tag>
                        <n-tag v-if="t.childTasks?.length" size="tiny" :bordered="false" type="info">
                            {{ t.childTasks.length }} children
                        </n-tag>
                        <n-tag v-if="t.prUrls?.length" size="tiny" :bordered="false" type="success">
                            {{ t.prUrls.length }} PR{{ t.prUrls.length > 1 ? 's' : '' }}
                        </n-tag>
                    </div>
                    <div class="tcard__passages" v-if="t.rolePassages?.length">
                        <n-tooltip v-for="(p, i) in t.rolePassages" :key="i" trigger="hover">
                            <template #trigger>
                                <span class="passage" :class="'passage--' + (p.outcome || '').toLowerCase()">
                                    {{ p.role }}
                                </span>
                            </template>
                            {{ p.role }}: {{ p.outcome }}{{ p.note ? ' — ' + p.note : '' }}
                        </n-tooltip>
                    </div>
                </n-card>
            </div>
            <div class="col col--done">
                <div class="col__head">Completed</div>
                <n-card v-for="t in completedTasks" :key="t.uuid" class="tcard tcard--done" size="small">
                    <div class="tcard__title">{{ t.title }}</div>
                    <div class="tcard__ref">
                        <a v-if="t.sourceUrl" :href="t.sourceUrl" target="_blank" rel="noopener">{{ refLabel(t) }}</a>
                        <span v-else>{{ refLabel(t) }}</span>
                    </div>
                    <div class="tcard__passages" v-if="t.rolePassages?.length">
                        <n-tooltip v-for="(p, i) in t.rolePassages" :key="i" trigger="hover">
                            <template #trigger>
                                <span class="passage" :class="'passage--' + (p.outcome || '').toLowerCase()">
                                    {{ p.role }}
                                </span>
                            </template>
                            {{ p.role }}: {{ p.outcome }}{{ p.note ? ' — ' + p.note : '' }}
                        </n-tooltip>
                    </div>
                    <div class="tcard__meta">
                        <n-tag v-if="t.childTasks?.length" size="tiny" :bordered="false" type="info">
                            rolled up from {{ t.childTasks.length }} children
                        </n-tag>
                        <n-tag v-for="(pr, i) in (t.prUrls ?? [])" :key="i" size="tiny" type="success" :bordered="false">
                            <a :href="pr" target="_blank" rel="noopener" class="prlink">PR</a>
                        </n-tag>
                    </div>
                </n-card>
            </div>
        </div>
    </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import { NCard, NTag, NTooltip } from 'naive-ui'

const props = defineProps<{
    tasks: any[]
    roleConfigs: any[]
}>()

// Columns come from the org's active non-routing roles in pipeline
// order; tasks whose snapshot carries extra roles still land in a
// column because the union below folds snapshot roles in.
const roleColumns = computed<string[]>(() => {
    const fromConfig = (props.roleConfigs ?? [])
        .filter(r => r.active && !r.routing)
        .sort((a, b) => (a.orderIndex ?? 0) - (b.orderIndex ?? 0))
        .map(r => r.name)
    const seen = new Set(fromConfig)
    for (const t of props.tasks ?? []) {
        for (const r of t.pipeline ?? []) {
            if (!seen.has(r)) { seen.add(r); fromConfig.push(r) }
        }
    }
    return fromConfig
})

const openTasks = computed(() => (props.tasks ?? []).filter(t => t.status === 'OPEN'))
const completedTasks = computed(() => (props.tasks ?? []).filter(t => t.status === 'COMPLETED'))

// A split parent whose pipeline is exhausted but still OPEN waits on
// its children — show it in the column of its last passage so it
// doesn't vanish from the board.
function tasksAtStage (role: string): any[] {
    return openTasks.value.filter(t => {
        const current = t.pipeline?.[t.currentStageIndex]
        if (current) return current === role
        const lastPassage = t.rolePassages?.[t.rolePassages.length - 1]
        return lastPassage?.role === role
    })
}

function isLiveClaim (t: any): boolean {
    const exp = t.activeClaim?.leaseExpiresAt
    return !!exp && new Date(exp).getTime() > Date.now()
}

function leaseLeft (t: any): string {
    const exp = t.activeClaim?.leaseExpiresAt
    if (!exp) return ''
    const mins = Math.max(0, Math.round((new Date(exp).getTime() - Date.now()) / 60000))
    return `${mins}m`
}

function refLabel (t: any): string {
    if (t.externalRef) return t.externalRef.replace(/^github:/, '')
    return 'draft (no tracker ref yet)'
}
</script>

<style scoped lang="scss">
.section-head {
    display: flex;
    align-items: baseline;
    gap: 10px;
    margin-bottom: 8px;
    h5 { margin: 0; }
    .sub { color: var(--n-text-color-3, #888); font-size: 12px; }
}
.empty { color: #888; font-size: 13px; padding: 8px 0; }
.board {
    display: grid;
    grid-auto-flow: column;
    grid-auto-columns: minmax(210px, 1fr);
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
}
.col--done .col__head { color: #4a9d6e; }
.tcard {
    margin-bottom: 10px;
    &--claimed { border-left: 3px solid #d9a24a; }
    &--done { opacity: 0.85; border-left: 3px solid #4a9d6e; }
    &__title { font-size: 13px; font-weight: 500; margin-bottom: 4px; }
    &__ref { font-size: 12px; margin-bottom: 6px; word-break: break-all; }
    &__meta { display: flex; flex-wrap: wrap; gap: 4px; margin-bottom: 4px; }
    &__passages { display: flex; flex-wrap: wrap; gap: 4px; }
}
.passage {
    font-size: 11px;
    padding: 1px 6px;
    border-radius: 8px;
    background: #eee;
    color: #555;
    &--passed { background: #e2f3e8; color: #2f7a4d; }
    &--rejected { background: #fbe3e3; color: #b03a3a; }
    &--skipped { background: #f0f0f0; color: #888; }
}
.prlink { color: inherit; text-decoration: none; }
</style>
