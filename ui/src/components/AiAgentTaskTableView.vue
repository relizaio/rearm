<template>
    <div class="ttable">
        <n-space :size="8" class="ttable__filters">
            <n-input v-model:value="textFilter" size="small" clearable
                     placeholder="Filter by title or ref" style="width: 240px"/>
            <n-select v-model:value="statusFilter" size="small" clearable multiple
                      :options="statusOptions" placeholder="Status" style="min-width: 220px"/>
        </n-space>
        <n-data-table
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

const props = defineProps<{
    tasks: any[]
    agentNames: Record<string, string>
}>()
const emit = defineEmits<{ (e: 'open', task: any): void }>()

const textFilter = ref('')
const statusFilter = ref<string[] | null>(null)

const statusOptions = ['PENDING_INTAKE', 'QUEUED', 'ASSIGNED', 'AWAITING_COORDINATOR', 'ON_HOLD', 'COMPLETED', 'CANCELLED']
    .map(s => ({ label: s.replace(/_/g, ' ').toLowerCase(), value: s }))

const filtered = computed(() => {
    const q = textFilter.value.trim().toLowerCase()
    return (props.tasks ?? []).filter(t => {
        if (statusFilter.value?.length && !statusFilter.value.includes(t.status)) return false
        if (q && !(`${t.title} ${t.externalRef ?? ''}`.toLowerCase().includes(q))) return false
        return true
    })
})

function refOf (t: any): string {
    return t.externalRef?.includes('#') ? '#' + t.externalRef.split('#').pop() : 'draft'
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

const columns: DataTableColumns<any> = [
    {
        title: 'Ref', key: 'ref', width: 76, sorter: (a, b) => refOf(a).localeCompare(refOf(b), undefined, { numeric: true }),
        render: (t: any) => h('code', {}, refOf(t)),
    },
    { title: 'Title', key: 'title', ellipsis: { tooltip: true }, sorter: 'default' },
    {
        title: 'Status', key: 'status', width: 168,
        sorter: (a, b) => a.status.localeCompare(b.status),
        render: (t: any) => h('span', {}, [
            h(NTag, {
                size: 'small', bordered: false,
                type: t.status === 'COMPLETED' ? 'success'
                    : t.status === 'ASSIGNED' ? 'warning'
                    : (t.status === 'ON_HOLD' || t.status === 'CANCELLED') ? 'error' : 'default',
            }, { default: () => t.status.replace(/_/g, ' ').toLowerCase() }),
            blocked(t) ? h(NTag, { size: 'tiny', bordered: false, type: 'warning', style: 'margin-left:4px' },
                { default: () => 'blocked' }) : null,
        ]),
    },
    { title: 'Role', key: 'role', width: 90, sorter: (a, b) => String(a.role ?? '').localeCompare(String(b.role ?? '')), render: (t: any) => t.role ?? '—' },
    { title: 'Order', key: 'orderIndex', width: 70, sorter: (a, b) => (a.orderIndex ?? 0) - (b.orderIndex ?? 0) },
    {
        title: 'Agent', key: 'agent', width: 140,
        render: (t: any) => t.assignment ? (props.agentNames[t.assignment.agent] ?? t.assignment.agent?.slice(0, 8)) : '—',
    },
    { title: 'Deps', key: 'deps', width: 60, render: (t: any) => (t.dependsOn?.length ?? 0) || '—' },
    { title: 'Hops', key: 'hops', width: 60, render: (t: any) => (t.signOffs?.length ?? 0) || '—' },
    { title: 'PRs', key: 'prs', width: 56, render: (t: any) => (t.prUrls?.length ?? 0) || '—' },
    { title: 'Age', key: 'age', width: 64, sorter: (a, b) => new Date(a.createdDate ?? 0).getTime() - new Date(b.createdDate ?? 0).getTime(), render: ageOf },
]

function rowProps (t: any) {
    return { style: 'cursor: pointer', onClick: () => emit('open', t) }
}
</script>

<style scoped lang="scss">
.ttable { &__filters { margin-bottom: 8px; } }
</style>
