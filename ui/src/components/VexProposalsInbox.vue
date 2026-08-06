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
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { NButton, NCard, NDataTable, NIcon, NTabs, NTabPane, NTag, NTooltip } from 'naive-ui'
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

// Scope display: the backend resolves scope+scopeUuid into component / branch /
// release names (batched -- see VexProposalScopeResolver). Show the MOST
// SPECIFIC name as the link text, with the fuller path in a tooltip, so the
// column stays narrow in an already-wide table. Falls back to the raw uuid when
// the scoped object no longer resolves, and to the bare enum for ORG scope.
function scopePath (r: any): string {
    return [r.scopeComponentName, r.scopeBranchName, r.scopeReleaseVersion]
        .filter(Boolean).join(' / ')
}

function scopeLink (r: any): { text: string, to: string } | null {
    if (r.scope === 'RELEASE' && r.scopeReleaseUuid) {
        return { text: r.scopeReleaseVersion || r.scopeReleaseUuid, to: `/release/show/${r.scopeReleaseUuid}` }
    }
    if (r.scope === 'BRANCH' && r.scopeBranchUuid && r.scopeComponentUuid) {
        return {
            text: r.scopeBranchName || r.scopeBranchUuid,
            to: `/componentsOfOrg/${r.org}/${r.scopeComponentUuid}/${r.scopeBranchUuid}`
        }
    }
    if (r.scope === 'COMPONENT' && r.scopeComponentUuid) {
        return { text: r.scopeComponentName || r.scopeComponentUuid, to: `/componentsOfOrg/${r.org}/${r.scopeComponentUuid}` }
    }
    return null
}

function renderScope (r: any) {
    const tag = h(NTag, { size: 'small', round: true, style: 'margin-right: 6px;' }, () => r.scope)
    const link = scopeLink(r)
    if (!link) {
        // ORG / RESOURCE_GROUP have no narrower object; anything else that failed
        // to resolve shows its raw uuid so the row is still actionable.
        const unresolved = (r.scope !== 'ORG' && r.scope !== 'RESOURCE_GROUP' && r.scopeUuid)
            ? h('span', { class: 'text-muted' }, r.scopeUuid)
            : null
        return h('span', {}, unresolved ? [tag, unresolved] : [tag])
    }
    const anchorEl = h(RouterLink, { to: link.to, target: '_blank' }, () => link.text)
    const path = scopePath(r)
    return h('span', {}, [
        tag,
        path && path !== link.text
            ? h(NTooltip, { trigger: 'hover' }, { trigger: () => anchorEl, default: () => path })
            : anchorEl
    ])
}

const columns = computed(() => {
    const cols: any[] = [
        { title: 'CVE', key: 'findingId' },
        { title: 'Location', key: 'location' },
        { title: 'State', key: 'analysisState' },
        { title: 'Justification', key: 'analysisJustification', render: (r: any) => r.analysisJustification ?? '—' },
        {
            title: 'Scope',
            key: 'scope',
            render: (r: any) => renderScope(r)
        },
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
