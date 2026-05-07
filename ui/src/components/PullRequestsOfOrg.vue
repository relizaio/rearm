<template>
    <div class="prsOfOrg">
        <h4>Pull Requests</h4>

        <n-input
            v-model:value="searchQuery"
            round
            clearable
            placeholder="Filter by identity, title, or VCS"
            :style="{ 'max-width': '400px', 'margin': '10px 0' }">
            <template #suffix>
                <n-icon size="18"><Search/></n-icon>
            </template>
        </n-input>

        <n-data-table
            :columns="columns"
            :data="filtered"
            :pagination="{ pageSize: 25 }"/>
    </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import { useStore } from 'vuex'
import { useRoute, RouterLink } from 'vue-router'
import { NDataTable, NIcon, NInput, NTag, DataTableColumns } from 'naive-ui'
import { Search } from '@vicons/tabler'

const store = useStore()
const route = useRoute()
const orgUuid = computed(() => route.params.orguuid as string)
const searchQuery = ref('')
const prs = ref<any[]>([])

const stateTagType = (s: string) => s === 'OPEN' ? 'success' : (s === 'MERGED' ? 'info' : 'default')

const fmt = (raw: string | null | undefined) => {
    if (!raw) return '—'
    const d = new Date(raw)
    if (Number.isNaN(d.getTime())) return '—'
    return d.toLocaleDateString('en-CA')
}

const vcsName = (uuid: string) => {
    if (!uuid) return '—'
    const repo = store.getters.vcsRepoById(uuid)
    return repo?.name || uuid.slice(0, 8) + '…'
}

const columns: DataTableColumns<any> = [
    {
        title: 'Identity',
        key: 'identity',
        width: 130,
        render: (row) => h(RouterLink as any,
            { to: { name: 'PullRequestView', params: { uuid: row.uuid } } },
            { default: () => row.identity || row.uuid.slice(0, 8) })
    },
    {
        title: 'Title',
        key: 'title',
        render: (row) => row.title || '—'
    },
    {
        title: 'State',
        key: 'state',
        width: 100,
        render: (row) => h(NTag, { type: stateTagType(row.state), size: 'small', bordered: false },
            { default: () => row.state || '—' })
    },
    {
        title: 'Target VCS',
        key: 'targetVcsRepository',
        render: (row) => vcsName(row.targetVcsRepository)
    },
    {
        title: 'Commits',
        key: 'commits',
        width: 90,
        render: (row) => (row.commits || []).length
    },
    {
        title: 'Created',
        key: 'createdDate',
        width: 110,
        render: (row) => fmt(row.prCreatedDate || row.createdDate)
    },
    {
        title: 'Closed',
        key: 'closedDate',
        width: 110,
        render: (row) => fmt(row.closedDate)
    },
    {
        title: 'Endpoint',
        key: 'endpoint',
        render: (row) => {
            if (!row.endpoint) return '—'
            return h('a', { href: row.endpoint, target: '_blank', rel: 'noopener' },
                row.endpoint.replace(/^https?:\/\//, ''))
        }
    }
]

const filtered = computed(() => {
    const q = searchQuery.value.trim().toLowerCase()
    if (!q) return prs.value
    return prs.value.filter((pr) => {
        return (pr.identity || '').toLowerCase().includes(q)
            || (pr.title || '').toLowerCase().includes(q)
            || vcsName(pr.targetVcsRepository).toLowerCase().includes(q)
    })
})

onMounted(async () => {
    if (orgUuid.value) {
        await store.dispatch('fetchVcsRepos', orgUuid.value)
        prs.value = (await store.dispatch('fetchPullRequestsOfOrg', orgUuid.value)) || []
    }
})
</script>

<style scoped lang="scss">
.prsOfOrg { padding: 1rem; }
</style>
