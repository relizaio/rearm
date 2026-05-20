<template>
    <div class="aiAgentsTable">
        <div class="page-head">
            <div>
                <h4>All AI Agents</h4>
                <p class="sub">
                    Every agent registered to this workspace, including those that
                    haven't been active recently. Sortable by any column.
                </p>
            </div>
            <n-button quaternary @click="back">← Back to overview</n-button>
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
import { NButton, NDataTable, NSpin, NTag, DataTableColumns } from 'naive-ui'

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

function modelDisplay (m: any): string {
    if (!m) return '—'
    const tail = m.version && m.version !== 'unknown' ? ` @ ${m.version}` : ''
    return `${m.publisher ?? ''} · ${m.name ?? ''}${tail}`.replace(/^· /, '')
}

function shortUuid (u: string | null | undefined): string {
    return u ? `${u.slice(0, 8)}…${u.slice(-4)}` : ''
}

const columns: DataTableColumns<any> = [
    {
        title: 'Name',
        key: 'name',
        sorter: 'default',
        render: (row: any) => h('div', { class: 'name-cell' }, [
            h('span', { class: 'mark', style: { background: row.color || '#888' } }, row.iconKind || '◆'),
            h('span', { class: 'name-text' }, row.name),
        ]),
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
        render: (row: any) => formatDate(row.createdDate),
    },
    {
        title: 'Last seen',
        key: 'lastActivityAt',
        sorter: (a: any, b: any) => (a.lastActivityAt ?? '').localeCompare(b.lastActivityAt ?? ''),
        defaultSortOrder: 'descend',
        render: (row: any) => formatDate(row.lastActivityAt),
    },
]
</script>

<style scoped>
.aiAgentsTable { padding: 16px; }
.sub { color: var(--n-text-color-3, #666); font-size: 13px; margin-bottom: 16px; }
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 12px; }
.name-cell { display: flex; align-items: center; gap: 8px; }
.mark { display: inline-flex; width: 22px; height: 22px; border-radius: 5px; color: white; align-items: center; justify-content: center; font-size: 12px; font-weight: 600; flex-shrink: 0; }
.name-text { font-weight: 500; }
</style>
