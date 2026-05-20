<template>
    <div class="aiAgentsTable">
        <n-breadcrumb separator="›" class="crumbs">
            <n-breadcrumb-item @click="back">AI Agents</n-breadcrumb-item>
            <n-breadcrumb-item>All Agents</n-breadcrumb-item>
        </n-breadcrumb>
        <div class="page-head">
            <div>
                <h4>All AI Agents</h4>
                <p class="sub">
                    Every agent registered to this workspace, including those that
                    haven't been active recently. Sortable by any column.
                </p>
            </div>
        </div>

        <n-spin v-if="loading" size="small"/>

        <n-data-table
            v-else
            :columns="columns"
            :data="rootAgents"
            :pagination="{ pageSize: 25 }"
            :row-props="rowProps"
        />
    </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import { useStore } from 'vuex'
import { useRoute, useRouter } from 'vue-router'
import { NBreadcrumb, NBreadcrumbItem, NDataTable, NIcon, NSpin, NTag, NTooltip, DataTableColumns } from 'naive-ui'
import { Info20Regular } from '@vicons/fluent'

const store = useStore()
const route = useRoute()
const router = useRouter()

const orgUuid = computed(() => route.params.orguuid as string)
const loading = ref<boolean>(false)
const agents = ref<any[]>([])

const rootAgents = computed(() => agents.value.filter(a => a.agentType === 'ROOT'))

onMounted(async () => {
    loading.value = true
    try {
        agents.value = await store.dispatch('fetchAgentsOfOrg', orgUuid.value) ?? []
    } finally {
        loading.value = false
    }
})

function back () {
    router.push({ name: 'AiAgentsOfOrg', params: { orguuid: orgUuid.value } })
}

function openAgent (uuid: string) {
    router.push({ name: 'AiAgentView', params: { uuid } })
}

function rowProps (row: any) {
    return {
        style: 'cursor: pointer;',
        onClick: () => openAgent(row.uuid),
    }
}

function formatDate (iso: string | null | undefined): string {
    if (!iso) return '—'
    const d = new Date(iso)
    return isNaN(d.getTime()) ? '—' : d.toLocaleDateString('en-CA')
}

function formatDateTimePrecise (iso: string | null | undefined): string {
    if (!iso) return ''
    const d = new Date(iso)
    if (isNaN(d.getTime())) return ''
    return d.toLocaleString('en-CA', {
        year: 'numeric', month: '2-digit', day: '2-digit',
        hour: '2-digit', minute: '2-digit', second: '2-digit',
        hour12: false,
    })
}

// Date cell with second-precision tooltip on an info icon. Column
// stays sortable on the raw ISO via the column's `sorter` — this
// helper is purely the render side.
function dateCell (iso: string | null | undefined) {
    if (!iso) return h('span', null, '—')
    return h('span', { class: 'date-cell' }, [
        formatDate(iso),
        h(NTooltip, { trigger: 'hover', placement: 'top' }, {
            trigger: () => h(NIcon, { class: 'date-cell__info', size: 13, onClick: (e: Event) => e.stopPropagation() }, () => h(Info20Regular)),
            default: () => formatDateTimePrecise(iso),
        }),
    ])
}

function modelDisplay (m: any): string {
    if (!m) return '—'
    const tail = m.version && m.version !== 'unknown' ? ` @ ${m.version}` : ''
    // Publisher is optional — drop the " · " separator when it's missing
    // so we don't render a leading dot when only the name is known.
    const head = [m.publisher, m.name].filter(Boolean).join(' · ')
    return `${head}${tail}`
}

function shortUuid (u: string | null | undefined): string {
    return u ? `${u.slice(0, 8)}…${u.slice(-4)}` : ''
}

const columns: DataTableColumns<any> = [
    {
        title: 'Name',
        key: 'name',
        sorter: 'default',
        render: (row: any) => h('span', { class: 'name-text' }, row.name),
    },
    {
        title: 'Model',
        key: 'model',
        sorter: (a: any, b: any) => modelDisplay(a.model).localeCompare(modelDisplay(b.model)),
        render: (row: any) => modelDisplay(row.model),
    },
    {
        title: 'Identity',
        key: 'agentIdentity',
        render: (row: any) => row.agentIdentity ? h('code', null, shortUuid(row.agentIdentity)) : '—',
    },
    {
        title: 'Status',
        key: 'status',
        sorter: 'default',
        render: (row: any) => h(NTag, { size: 'small', type: row.status === 'ARCHIVED' ? 'default' : 'success' }, () => row.status ?? 'ACTIVE'),
    },
    {
        title: 'Sessions',
        key: 'sessions',
        sorter: (a: any, b: any) =>
            (a.sessionCounts?.openSessions ?? 0) + (a.sessionCounts?.closedSessions ?? 0)
            - ((b.sessionCounts?.openSessions ?? 0) + (b.sessionCounts?.closedSessions ?? 0)),
        render: (row: any) => {
            const open = row.sessionCounts?.openSessions ?? 0
            const closed = row.sessionCounts?.closedSessions ?? 0
            return `${open} open · ${closed} closed`
        },
    },
    {
        title: 'Sub-agents',
        key: 'subAgents',
        sorter: (a: any, b: any) => (a.subAgents?.length ?? 0) - (b.subAgents?.length ?? 0),
        render: (row: any) => row.subAgents?.length ?? 0,
    },
    {
        title: 'First seen',
        key: 'createdDate',
        sorter: (a: any, b: any) => (a.createdDate ?? '').localeCompare(b.createdDate ?? ''),
        render: (row: any) => dateCell(row.createdDate),
    },
    {
        title: 'Last seen',
        key: 'lastActivityAt',
        sorter: (a: any, b: any) => (a.lastActivityAt ?? '').localeCompare(b.lastActivityAt ?? ''),
        defaultSortOrder: 'descend',
        render: (row: any) => dateCell(row.lastActivityAt),
    },
]
</script>

<style scoped>
.aiAgentsTable { padding: 16px; }
.sub { color: var(--n-text-color-3, #666); font-size: 13px; margin-bottom: 16px; }
.crumbs { margin-bottom: 12px; }
.crumbs :deep(.n-breadcrumb-item:first-child .n-breadcrumb-item__link) { cursor: pointer; }
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 12px; }
.name-text { font-weight: 500; }
.date-cell { display: inline-flex; align-items: center; gap: 4px; }
.date-cell__info { color: var(--n-text-color-3, #999); cursor: help; vertical-align: middle; }
.date-cell__info:hover { color: var(--n-text-color-2, #555); }
</style>
