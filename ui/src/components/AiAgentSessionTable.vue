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
    { title: 'Branch', key: 'branch', render: (row: any) => (row.branch ? h('code', null, row.branch) : '—') },
    {
        title: 'Status',
        key: 'status',
        render: (row: any) =>
            h(NTag, { size: 'small', type: row.status === 'OPEN' ? 'info' : 'default' }, { default: () => row.status }),
    },
    {
        title: 'Started',
        key: 'startedAt',
        render: (row: any) => (row.startedAt ? new Date(row.startedAt).toLocaleString() : '—'),
    },
    {
        title: 'Closed',
        key: 'closedAt',
        render: (row: any) => (row.closedAt ? new Date(row.closedAt).toLocaleString() : '—'),
    },
    { title: 'Artifacts', key: 'artifacts', width: 110, render: (row: any) => row.artifacts?.length ?? 0 },
    { title: 'Commits', key: 'commits', width: 100, render: (row: any) => row.commits?.length ?? 0 },
])
</script>
