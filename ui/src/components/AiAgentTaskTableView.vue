<template>
    <div class="ttable">
        <n-space :size="8" class="ttable__filters">
            <n-input v-model:value="textFilter" size="small" clearable
                     :placeholder="hasLadder(board) ? 'Filter by key, title, ref, work level (L2), group or tag' : 'Filter by key, title, ref, group or tag'"
                     style="width: 300px"/>
            <n-select v-model:value="statusFilter" size="small" clearable multiple
                      :options="statusOptions" placeholder="Status" style="min-width: 220px"/>
            <!-- Group and tag (RD2-31): the board page keeps them in the URL, shared with the kanban. -->
            <n-select v-if="board?.groups?.length" :value="groupFilter ?? null" size="small" clearable data-testid="table-group-filter"
                      :options="groupFilterOptions" placeholder="Group" style="width: 170px"
                      @update:value="(v: string | null) => emit('update:groupFilter', v ?? null)"/>
            <n-select v-if="tagFilterOptions.length" :value="tagFilter ?? null" size="small" clearable filterable
                      data-testid="table-tag-filter" :options="tagFilterOptions" placeholder="Tag" style="width: 150px"
                      @update:value="(v: string | null) => emit('update:tagFilter', v ?? null)"/>
        </n-space>
        <n-data-table
            class="ttable__table"
            :columns="columns"
            :data="filtered"
            :row-key="(r: any) => r.uuid"
            :row-props="rowProps"
            :pagination="{ pageSize: 15 }"
            size="small"
        />
    </div>
</template>

<script lang="ts" setup>
import { computed, h, ref } from 'vue'
import { NDataTable, NInput, NSelect, NSpace, NTag, DataTableColumns } from 'naive-ui'
import { RouterLink } from 'vue-router'
import { taskPagePath } from '@/utils/agentTaskFormat'
import { compareTaskKeys, matchesTaskText, roleTagFor, shortRef } from '@/utils/agentTaskLabels'
import { hasLadder, levelOf, levelTooltip, matchesLevel, taskLevelLabel } from '@/utils/agentTaskLevel'
import { actorLabel } from '@/utils/agentActors'
import SessionRef from './SessionRef.vue'
import { AgentName, sessionOf } from '@/utils/agentSessionLabel'
import { groupColour, groupOptions, groupRank, matchesGroupOrTag, NO_GROUP, passesGroupAndTag, tagKeys, tagOptions } from '@/utils/agentTaskGroups'

const props = defineProps<{
    tasks: any[]
    agentNames: Record<string, string>
    /** A board without sources has no tracker refs, so no task on it is a "draft". */
    boardHasSources?: boolean
    /** The board, for the level a task without its own reads (its default). */
    board?: any
    /** The group filter: a key, NO_GROUP for the ungrouped tasks, null for any (RD2-31). */
    groupFilter?: string | null
    /** The tag filter: a tag key, or null for any. */
    tagFilter?: string | null
    /** Agents with their own name apart from the key's note, for the Agent column (RD2-11). */
    agentDir?: Record<string, AgentName>
}>()
const emit = defineEmits<{
    (e: 'open', task: any): void
    (e: 'update:groupFilter', v: string | null): void
    (e: 'update:tagFilter', v: string | null): void
}>()

const groupFilterOptions = computed(() => [{ label: 'ungrouped', value: NO_GROUP }, ...groupOptions(props.board)])
const tagFilterOptions = computed(() => tagOptions(props.tasks))

const textFilter = ref('')
const statusFilter = ref<string[] | null>(null)

const statusOptions = ['PENDING_INTAKE', 'QUEUED', 'ASSIGNED', 'AWAITING_COORDINATOR', 'ON_HOLD', 'DELIVERING', 'COMPLETED', 'CANCELLED']
    .map(s => ({ label: s.replace(/_/g, ' ').toLowerCase(), value: s }))

const filtered = computed(() => {
    const q = textFilter.value.trim().toLowerCase()
    return (props.tasks ?? []).filter(t => {
        if (statusFilter.value?.length && !statusFilter.value.includes(t.status)) return false
        if (!passesGroupAndTag(t, props.groupFilter, props.tagFilter)) return false
        // "L2" or "level 2" filters by level, on a board with a ladder; any other text by key, title, ref, group and tags.
        if (!matchesLevel(t, props.board, q) && !matchesTaskText(t, q) && !matchesGroupOrTag(t, q)) return false
        return true
    })
})

function refOf (t: any): string {
    return shortRef(t, props.boardHasSources ?? true)
}

function ageOf (t: any): string {
    const created = new Date(t.createdDate ?? '').getTime()
    if (isNaN(created)) return '—'
    const end = t.completedAt ? new Date(t.completedAt).getTime() : Date.now()
    const mins = Math.round((end - created) / 60000)
    if (mins < 60) return `${mins}m`
    const hrs = Math.floor(mins / 60)
    return hrs < 48 ? `${hrs}h` : `${Math.floor(hrs / 24)}d`
}

function blocked (t: any): boolean {
    return t.status === 'QUEUED' && (t.dependsOn ?? []).some((d: string) => {
        const dep = (props.tasks ?? []).find(x => x.uuid === d)
        return !dep || dep.status !== 'COMPLETED'
    })
}

const allColumns: DataTableColumns<any> = [
    {
        // The key leads (board-documents.md D12): what people say aloud and type in the filter.
        title: 'Key', key: 'key', width: 84, sorter: compareTaskKeys,
        render: (t: any) => h('code', {}, t.key ?? '—'),
    },
    {
        title: 'Ref', key: 'ref', width: 76, sorter: (a, b) => refOf(a).localeCompare(refOf(b), undefined, { numeric: true }),
        render: (t: any) => h('code', {}, refOf(t)),
    },
    {
        // The title opens the task page; the rest of the row opens the drawer, as before.
        title: 'Title', key: 'title', ellipsis: { tooltip: true }, sorter: 'default',
        render: (t: any) => h(RouterLink, { to: taskPagePath(t.uuid), onClick: (e: Event) => e.stopPropagation() },
            { default: () => t.title }),
    },
    {
        title: 'Status', key: 'status', width: 168,
        sorter: (a, b) => a.status.localeCompare(b.status),
        render: (t: any) => h('span', {}, [
            h(NTag, {
                size: 'small', bordered: false,
                type: t.status === 'COMPLETED' ? 'success'
                    : t.status === 'DELIVERING' ? 'info'
                        : t.status === 'ASSIGNED' ? 'warning'
                            : (t.status === 'ON_HOLD' || t.status === 'CANCELLED') ? 'error' : 'default',
            }, { default: () => t.status.replace(/_/g, ' ').toLowerCase() }),
            blocked(t) ? h(NTag, { size: 'tiny', bordered: false, type: 'warning', style: 'margin-left:4px' },
                { default: () => 'blocked' }) : null,
        ]),
    },
    {
        // The level the board reads (RD2-1), "1 · solution" (task RD3-6): sorted numerically, a task with none last.
        title: 'Work level', key: 'level', width: 120,
        sorter: (a, b) => (levelOf(a, props.board) ?? 99) - (levelOf(b, props.board) ?? 99),
        render: (t: any) => {
            const l = taskLevelLabel(t, props.board)
            return l ? h('span', { title: levelTooltip(t, props.board, actorLabel) ?? '', 'data-level': l }, l) : '—'
        },
    },
    {
        // The group in its colour (RD2-31): sorted in the board's order, ungrouped last.
        title: 'Group', key: 'group', width: 110,
        sorter: (a, b) => groupSortRank(a) - groupSortRank(b),
        render: (t: any) => t.group?.key
            ? h('code', { 'data-group': t.group.key, style: `color: ${groupColour(t.group.key)}` }, t.group.key) : '—',
    },
    {
        title: 'Tags', key: 'tags', width: 150,
        sorter: (a, b) => tagKeys(a).join(',').localeCompare(tagKeys(b).join(',')),
        render: (t: any) => tagKeys(t).length
            ? h('span', { 'data-tags': tagKeys(t).join(',') }, tagKeys(t).map(k => h(NTag, { size: 'tiny', bordered: false, round: true,
                style: 'margin-right:3px' }, { default: () => k }))) : '—',
    },
    { title: 'Role', key: 'role', width: 90, sorter: (a, b) => String(a.role ?? '').localeCompare(String(b.role ?? '')), render: (t: any) => roleTagFor(t)?.text ?? '—' },
    { title: 'Order', key: 'orderIndex', width: 80, minWidth: 80, sorter: (a, b) => (a.orderIndex ?? 0) - (b.orderIndex ?? 0) },
    {
        title: 'Agent', key: 'agent', width: 140,
        // The session working it, role first and linked (RD2-11), not the key's note shared by every session on it.
        render: (t: any) => !t.assignment ? '—'
            : t.assignment.session
                ? h(SessionRef, { session: sessionOf(t.assignment.session, t.assignment.agent, props.agentDir, t.assignment.role) })
                : (props.agentNames[t.assignment.agent] ?? t.assignment.agent?.slice(0, 8)),
    },
    { title: 'Deps', key: 'deps', width: 60, render: (t: any) => (t.dependsOn?.length ?? 0) || '—' },
    { title: 'Hops', key: 'hops', width: 60, render: (t: any) => (t.signOffs?.length ?? 0) || '—' },
    { title: 'PRs', key: 'prs', width: 56, render: (t: any) => (t.prUrls?.length ?? 0) || '—' },
    { title: 'Age', key: 'age', width: 64, sorter: (a, b) => new Date(a.createdDate ?? 0).getTime() - new Date(b.createdDate ?? 0).getTime(), render: ageOf },
]
// The Level column only on a board with a ladder (task RD3-6): without one no task has a level.
const columns = computed<DataTableColumns<any>>(() => allColumns.filter((c: any) => c.key !== 'level' || hasLadder(props.board)))

function groupSortRank (t: any): number {
    return t.group?.key ? groupRank(t.group.key, props.board) : Number.MAX_SAFE_INTEGER
}

function rowProps (t: any) {
    return { style: 'cursor: pointer', onClick: () => emit('open', t) }
}
</script>

<style scoped lang="scss">
// Headers keep to one line (RD2-12): "Order" wrapped mid-word at 1280.
.ttable__table :deep(.n-data-table-th) { white-space: nowrap; }
.ttable { &__filters { margin-bottom: 8px; } }
</style>
