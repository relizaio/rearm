<template>
    <n-card title="Mitigation Attestations">
        <n-tabs v-model:value="statusFilter" type="segment" animated>
            <n-tab-pane name="PENDING" tab="Pending" />
            <n-tab-pane name="ATTESTED" tab="Attested" />
            <n-tab-pane name="WAIVED" tab="Waived" />
            <n-tab-pane name="EXPIRED" tab="Expired" />
        </n-tabs>

        <n-data-table
            :columns="columns"
            :data="attestations"
            :loading="loading"
            :row-key="(row: any) => row.uuid"
        />
    </n-card>
</template>

<script lang="ts">
export default {
    name: 'MitigationAttestationsInbox'
}
</script>
<script lang="ts" setup>
import { computed, h, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NCard, NDataTable, NIcon, NTabs, NTabPane, NTag } from 'naive-ui'
import { Eye } from '@vicons/tabler'
import graphqlClient from '@/utils/graphql'
import { useOrgUsersIndex } from '@/utils/userLookup'
import { GET_MITIGATION_ATTESTATIONS } from '@/graphql/vexImport'

const route = useRoute()
const router = useRouter()
const orgUuid = computed(() => route.params.orguuid as string)
const { format: formatUser } = useOrgUsersIndex(orgUuid)
const statusFilter = ref<'PENDING' | 'ATTESTED' | 'WAIVED' | 'EXPIRED'>('PENDING')
const attestations = ref<any[]>([])
const loading = ref(false)

async function fetchAttestations () {
    loading.value = true
    try {
        const r = await graphqlClient.query({
            query: GET_MITIGATION_ATTESTATIONS,
            variables: { org: orgUuid.value, status: statusFilter.value },
            fetchPolicy: 'network-only'
        })
        attestations.value = r.data?.getMitigationAttestations ?? []
    } finally {
        loading.value = false
    }
}

onMounted(fetchAttestations)
watch([statusFilter, orgUuid], fetchAttestations)

const columns = computed(() => {
    const cols: any[] = [
        { title: 'Type', key: 'claimType' },
        { title: 'Claim', key: 'claimText' },
        { title: 'Scope', key: 'scope' },
        { title: 'Assignee', key: 'assignedTo', render: (r: any) => formatUser(r.assignedTo) },
        {
            title: 'Status',
            key: 'status',
            render: (r: any) => {
                const type = r.status === 'ATTESTED' ? 'success'
                    : r.status === 'PENDING' ? 'warning'
                    : r.status === 'WAIVED' ? 'info'
                    : r.status === 'EXPIRED' ? 'error'
                    : 'default'
                return h(NTag, { type, size: 'small', round: true }, () => r.status)
            },
        },
    ]
    if (statusFilter.value !== 'PENDING') {
        cols.push({
            title: 'Acted at',
            key: 'attestedAt',
            render: (r: any) => r.attestedAt ? new Date(r.attestedAt).toLocaleString() : '—',
        })
        cols.push({
            title: 'Acted by',
            key: 'attestedBy',
            render: (r: any) => formatUser(r.attestedBy),
        })
    }
    cols.push({
        title: 'Actions',
        key: 'actions',
        width: 80,
        render (row: any) {
            return h(NButton, {
                size: 'small',
                type: 'info',
                title: 'Review attestation',
                onClick: () => router.push({ name: 'MitigationAttestationReview', params: { orguuid: orgUuid.value, uuid: row.uuid } })
            }, { default: () => h(NIcon, null, { default: () => h(Eye) }) })
        }
    })
    return cols
})
</script>
