<template>
    <n-card title="VEX Statement Proposals">
        <n-tabs v-model:value="statusFilter" type="segment" animated>
            <n-tab-pane name="PENDING" tab="Pending" />
            <n-tab-pane name="ACCEPTED" tab="Accepted" />
            <n-tab-pane name="REJECTED" tab="Rejected" />
            <n-tab-pane name="SUPERSEDED" tab="Superseded" />
            <n-tab-pane name="ERRORED" tab="Errored" />
        </n-tabs>

        <n-data-table
            :columns="columns"
            :data="proposals"
            :loading="loading"
            :row-key="(row: any) => row.uuid"
        />
    </n-card>
</template>

<script lang="ts">
export default {
    name: 'VexProposalsInbox'
}
</script>
<script lang="ts" setup>
import { computed, h, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NCard, NDataTable, NIcon, NTabs, NTabPane, NTag } from 'naive-ui'
import { Eye } from '@vicons/tabler'
import graphqlClient from '@/utils/graphql'
import { useOrgUsersIndex } from '@/utils/userLookup'
import { GET_VEX_PROPOSALS } from '@/graphql/vexImport'

const route = useRoute()
const router = useRouter()
const orgUuid = computed(() => route.params.orguuid as string)
const { format: formatUser } = useOrgUsersIndex(orgUuid)
const statusFilter = ref<'PENDING' | 'ACCEPTED' | 'REJECTED' | 'SUPERSEDED' | 'ERRORED'>('PENDING')
const proposals = ref<any[]>([])
const loading = ref(false)

async function fetchProposals () {
    loading.value = true
    try {
        const r = await graphqlClient.query({
            query: GET_VEX_PROPOSALS,
            variables: { org: orgUuid.value, status: statusFilter.value },
            fetchPolicy: 'network-only'
        })
        proposals.value = r.data?.getVexStatementProposals ?? []
    } finally {
        loading.value = false
    }
}

onMounted(fetchProposals)
watch([statusFilter, orgUuid], fetchProposals)

const columns = computed(() => {
    const cols: any[] = [
        { title: 'CVE', key: 'findingId' },
        { title: 'Component', key: 'location' },
        { title: 'State', key: 'analysisState' },
        { title: 'Justification', key: 'analysisJustification', render: (r: any) => r.analysisJustification ?? '—' },
        { title: 'Scope', key: 'scope' },
        {
            title: 'Status',
            key: 'status',
            render: (r: any) => {
                const type = r.status === 'ACCEPTED' ? 'success'
                    : r.status === 'PENDING' ? 'warning'
                    : r.status === 'REJECTED' ? 'error'
                    : 'default'
                return h(NTag, { type, size: 'small', round: true }, () => r.status)
            },
        },
    ]
    if (statusFilter.value !== 'PENDING') {
        cols.push({
            title: 'Acted at',
            key: 'actedAt',
            render: (r: any) => r.actedAt ? new Date(r.actedAt).toLocaleString() : '—',
        })
        cols.push({
            title: 'Acted by',
            key: 'actedBy',
            render: (r: any) => formatUser(r.actedBy),
        })
    }
    cols.push({
        title: 'Actions',
        key: 'actions',
        width: 80,
        render (row: any) {
            // Open in a new tab so reviewers can keep the inbox open while triaging — they
            // typically work through a queue of proposals and bouncing back to the inbox after
            // each one breaks the cadence. window.open over <a target=_blank> because the
            // click target is an NButton, not a plain anchor.
            const href = router.resolve({
                name: 'VexProposalReview',
                params: { orguuid: orgUuid.value, uuid: row.uuid }
            }).href
            return h(NButton, {
                size: 'small',
                type: 'info',
                title: 'Review proposal (opens in new tab)',
                onClick: () => window.open(href, '_blank', 'noopener')
            }, { default: () => h(NIcon, null, { default: () => h(Eye) }) })
        }
    })
    return cols
})
</script>
