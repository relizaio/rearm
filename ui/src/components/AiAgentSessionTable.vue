<template>
    <n-data-table
        :columns="columns"
        :data="rows"
        :pagination="{ pageSize: 10 }"
    />
</template>

<script setup lang="ts">
import { h, computed } from 'vue'
import { NDataTable, NTag, DataTableColumns } from 'naive-ui'

const props = defineProps<{
    rows: any[]
    onOpen: (uuid: string) => void
}>()

function fmt (s: string | null | undefined): string {
    return s ? new Date(s).toLocaleString('en-CA') : '—'
}

const columns = computed<DataTableColumns<any>>(() => [
    {
        title: 'Session',
        key: 'clientSessionId',
        width: 200,
        render: (row: any) =>
            h(
                'a',
                {
                    href: '#',
                    onClick: (e: Event) => {
                        e.preventDefault()
                        props.onOpen(row.uuid)
                    },
                },
                h('code', null, row.clientSessionId ?? row.uuid.slice(0, 8))
            ),
    },
    { title: 'Title', key: 'title' },
    {
        title: 'Status',
        key: 'status',
        width: 90,
        render: (row: any) =>
            h(NTag, { size: 'small', type: row.status === 'OPEN' ? 'info' : 'default' }, { default: () => row.status }),
    },
    {
        title: 'Started',
        key: 'startedAt',
        width: 170,
        render: (row: any) => fmt(row.startedAt),
    },
    {
        title: 'Closed',
        key: 'closedAt',
        width: 170,
        render: (row: any) => fmt(row.closedAt),
    },
    { title: 'Commits', key: 'commits', width: 90, render: (row: any) => row.commits?.length ?? 0 },
    { title: 'Artifacts', key: 'artifacts', width: 95, render: (row: any) => row.artifacts?.length ?? 0 },
    { title: 'Releases', key: 'releases', width: 90, render: (row: any) => row.releases?.length ?? 0 },
    { title: 'PRs', key: 'pullRequests', width: 70, render: (row: any) => row.pullRequests?.length ?? 0 },
])
</script>
