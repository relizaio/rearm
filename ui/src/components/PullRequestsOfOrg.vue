<template>
    <div class="prsOfOrg">
        <h4>Pull Requests</h4>

        <div class="filters">
            <n-input
                v-model:value="searchQuery"
                round
                clearable
                placeholder="Filter by identity, title, or VCS"
                :style="{ 'max-width': '400px' }">
                <template #suffix>
                    <n-icon size="18"><Search/></n-icon>
                </template>
            </n-input>

            <n-checkbox-group v-model:value="selectedStates" @update:value="onStatesChanged">
                <n-space>
                    <n-checkbox value="OPEN">OPEN</n-checkbox>
                    <n-checkbox value="MERGED">MERGED</n-checkbox>
                    <n-checkbox value="CLOSED">CLOSED</n-checkbox>
                </n-space>
            </n-checkbox-group>

            <n-spin v-if="loading" size="small"/>
        </div>

        <n-data-table
            :columns="columns"
            :data="filtered"
            :pagination="{ pageSize: 25 }"
            @update:filters="onFilterUpdate"/>
    </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref, watch } from 'vue'
import { useStore } from 'vuex'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { NDataTable, NIcon, NInput, NTag, NTooltip, NCheckbox, NCheckboxGroup, NSpace, NSpin, DataTableColumns, DataTableFilterState } from 'naive-ui'
import { Search } from '@vicons/tabler'
import { Info20Regular } from '@vicons/fluent'

const store = useStore()
const route = useRoute()
const router = useRouter()

const orgUuid = computed(() => route.params.orguuid as string)
const searchQuery = ref('')
const selectedStates = ref<string[]>(['OPEN'])
const prs = ref<any[]>([])
const loading = ref(false)
const vcsFilter = ref<string[]>([])

const stateTagType = (s: string) => s === 'OPEN' ? 'success' : (s === 'MERGED' ? 'info' : 'default')

// Calendar-day display (en-CA → ISO yyyy-mm-dd) for the cell, plus a
// second-granularity tooltip so operators can see exact times without
// the column getting unreadably wide.
const fmtDate = (raw: string | null | undefined) => {
    if (!raw) return null
    const d = new Date(raw)
    if (Number.isNaN(d.getTime())) return null
    return d.toLocaleDateString('en-CA')
}

const fmtFull = (raw: string | null | undefined) => {
    if (!raw) return null
    const d = new Date(raw)
    if (Number.isNaN(d.getTime())) return null
    const utc = d.toISOString().replace('T', ' ').replace(/\.\d+Z$/, ' UTC')
    const local = d.toLocaleString(undefined, {
        year: 'numeric', month: '2-digit', day: '2-digit',
        hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
    })
    return `${utc}\nlocal: ${local}`
}

const renderDateCell = (raw: string | null | undefined) => {
    const day = fmtDate(raw)
    if (!day) return '—'
    const full = fmtFull(raw)
    return h('span', { class: 'date-cell' }, [
        day,
        h(NTooltip, { trigger: 'hover', placement: 'top', style: 'white-space: pre-line' }, {
            trigger: () => h(NIcon, { size: 14, class: 'date-info' }, { default: () => h(Info20Regular) }),
            default: () => full
        })
    ])
}

const vcsName = (uuid: string) => {
    if (!uuid) return '—'
    const repo = store.getters.vcsRepoById(uuid)
    return repo?.name || uuid.slice(0, 8) + '…'
}

// Filter dropdown options come from every VCS repo registered in the
// org — not just the ones that show up in the currently-loaded PR set.
// Otherwise a cross-nav from a VCS page (which lands on OPEN-only by
// default) could pre-apply a filter for a VCS that has no OPEN PRs and
// the user wouldn't see the active filter listed in the dropdown.
const vcsFilterOptions = computed(() => {
    const repos = store.getters.vcsReposOfOrg(orgUuid.value) || []
    return repos
        .map((r: any) => ({ label: r.name, value: r.uuid }))
        .sort((a: any, b: any) => a.label.localeCompare(b.label))
})

const columns = computed<DataTableColumns<any>>(() => [
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
        filter: 'default',
        filterMultiple: true,
        filterOptionValues: vcsFilter.value,
        filterOptions: vcsFilterOptions.value,
        render: (row) => {
            if (!row.targetVcsRepository) return '—'
            return h(RouterLink as any,
                { to: { name: 'VcsRepository', params: { uuid: row.targetVcsRepository } } },
                { default: () => vcsName(row.targetVcsRepository) })
        }
    },
    {
        title: 'Commits',
        key: 'commits',
        width: 90,
        render: (row) => (row.commits || []).length
    },
    {
        title: 'Created',
        key: 'prCreatedDate',
        width: 130,
        render: (row) => renderDateCell(row.prCreatedDate || row.createdDate)
    },
    {
        title: 'Closed/Merged',
        key: 'closedOrMergedDate',
        width: 150,
        render: (row) => renderDateCell(row.mergedDate || row.closedDate)
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
])

const filtered = computed(() => {
    const q = searchQuery.value.trim().toLowerCase()
    if (!q) return prs.value
    return prs.value.filter((pr) => {
        return (pr.identity || '').toLowerCase().includes(q)
            || (pr.title || '').toLowerCase().includes(q)
            || vcsName(pr.targetVcsRepository).toLowerCase().includes(q)
    })
})

const fetchPrs = async () => {
    if (!orgUuid.value) return
    loading.value = true
    try {
        // Pre-fetch VCS repos so vcsName() resolves for the rendered cells
        // and the column filter dropdown has labels available.
        await store.dispatch('fetchVcsRepos', orgUuid.value)
        prs.value = (await store.dispatch('fetchPullRequestsOfOrg', {
            org: orgUuid.value,
            states: selectedStates.value
        })) || []
    } finally {
        loading.value = false
    }
}

const onStatesChanged = () => {
    // Persist active filters to the URL so a refresh (or someone sharing
    // the link) lands on the same view. State always serializes; vcs is
    // optional and only present when the user came from a VCS jump.
    const query: any = { ...route.query, states: selectedStates.value.join(',') || undefined }
    router.replace({ name: route.name as any, params: route.params, query })
    fetchPrs()
}

// Naive-UI fires this with the current values of every filtered column;
// we only care about Target VCS here.
const onFilterUpdate = (filters: DataTableFilterState) => {
    const v = filters['targetVcsRepository']
    vcsFilter.value = Array.isArray(v) ? (v as string[]) : (v ? [v as string] : [])
}

onMounted(async () => {
    // Hydrate filters from the URL: ?states=OPEN,MERGED&vcs=<uuid>
    const stateParam = route.query.states
    if (typeof stateParam === 'string' && stateParam.length > 0) {
        selectedStates.value = stateParam.split(',').filter(Boolean)
    }
    const vcsParam = route.query.vcs
    if (typeof vcsParam === 'string' && vcsParam.length > 0) {
        vcsFilter.value = [vcsParam]
    }
    await fetchPrs()
})

watch(orgUuid, fetchPrs)
</script>

<style scoped lang="scss">
.prsOfOrg { padding: 1rem; }
.filters {
    display: flex;
    align-items: center;
    gap: 1rem;
    margin: 10px 0;
    flex-wrap: wrap;
}
.date-cell {
    display: inline-flex;
    align-items: center;
    gap: 4px;
}
.date-info {
    color: #888;
    cursor: help;
}
</style>
