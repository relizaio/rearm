<template>
    <n-card title="VEX Statement Proposals">
        <n-radio-group v-model:value="statusFilter" size="small" style="margin-bottom: 8px;"
            data-testid="vex-proposals-status-filter">
            <n-radio-button value="PENDING">Pending</n-radio-button>
            <n-radio-button value="ACCEPTED">Accepted</n-radio-button>
            <n-radio-button value="REJECTED">Rejected</n-radio-button>
            <n-radio-button value="SUPERSEDED">Superseded</n-radio-button>
            <n-radio-button value="ERRORED">Errored</n-radio-button>
        </n-radio-group>

        <n-data-table
            :columns="columns"
            :data="proposals"
            :loading="loading"
            :row-key="(row: any) => row.uuid"
        />

        <n-collapse v-if="isOrgAdmin" style="margin-top: 16px;" data-testid="vex-import-audit">
            <n-collapse-item title="Import audit" name="import-audit">
                <n-text depth="3" style="font-size: 12px; display: block; margin-bottom: 8px;">
                    Accepted VEX statements at Organization or Branch scope, or with a suppressing state, whose accepting
                    credential cannot be shown to hold Finding Analysis write at the statement's scope now. Permissions
                    are judged now, not at acceptance time. Statements accepted before this audit existed do not name
                    the key that accepted them: a user key's import shows its owner under Acted by and never reads Yes;
                    a statement whose mitigation attestation completed after the accept reads Unknown. Read-only.
                </n-text>
                <n-space align="center" style="margin-bottom: 8px;">
                    <n-button size="small" type="primary" :loading="auditLoading" @click="runAudit">Run</n-button>
                    <n-checkbox v-model:checked="auditIncludeHeld">Show held too</n-checkbox>
                </n-space>
                <n-alert v-if="auditError" type="error" style="margin-bottom: 8px;">{{ auditError }}</n-alert>
                <n-alert v-if="audit?.truncated" type="warning" style="margin-bottom: 8px;"
                    data-testid="vex-import-audit-truncated">
                    Results truncated at 5000; the newest are shown.
                </n-alert>
                <n-data-table
                    v-if="audit"
                    size="small"
                    :columns="auditColumns"
                    :data="audit.rows"
                    :row-key="(row: any) => row.proposal"
                    data-testid="vex-import-audit-rows"
                />
            </n-collapse-item>
        </n-collapse>
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
import { useStore } from 'vuex'
import { NAlert, NButton, NCard, NCheckbox, NCollapse, NCollapseItem, NDataTable, NIcon, NRadioButton, NRadioGroup, NSpace, NTag, NText, NTooltip } from 'naive-ui'
import { Eye } from '@vicons/tabler'
import graphqlClient from '@/utils/graphql'
import commonFunctions from '@/utils/commonFunctions'
import { useOrgUsersIndex } from '@/utils/userLookup'
import { GET_VEX_IMPORT_AUTHORITY_REPORT, GET_VEX_PROPOSALS } from '@/graphql/vexImport'

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

// Import audit (org admins): the server gates the report on organization admin; the card is shown
// only to them so nobody else is offered a button that is always refused. acceptedBy and actedBy are
// a user or a key; formatUser names a user of the org and shows anything else (a key) by its id.
const store = useStore()
const isOrgAdmin = computed((): boolean => {
    const perm = store.getters.myuser?.permissions?.permissions?.find((p: any) =>
        p.scope === 'ORGANIZATION' && p.org === orgUuid.value && p.object === orgUuid.value)
    return perm?.type === 'ADMIN'
})
const audit = ref<{ rows: any[], truncated: boolean } | null>(null)
const auditIncludeHeld = ref(false)
const auditLoading = ref(false)
const auditError = ref('')

async function runAudit () {
    auditLoading.value = true
    auditError.value = ''
    try {
        const r: any = await graphqlClient.query({
            query: GET_VEX_IMPORT_AUTHORITY_REPORT,
            variables: { org: orgUuid.value, includeHeld: auditIncludeHeld.value },
            fetchPolicy: 'no-cache'
        })
        audit.value = r.data?.vexImportAuthorityReport ?? { rows: [], truncated: false }
    } catch (err: any) {
        audit.value = null
        auditError.value = commonFunctions.parseGraphQLError(err?.message ?? String(err))
    } finally {
        auditLoading.value = false
    }
}

// A new org or a changed toggle invalidates the shown result; the next Run reads it again.
watch([orgUuid, auditIncludeHeld], () => { audit.value = null; auditError.value = '' })

const ACCEPTED_BY_KIND_TEXT: Record<string, string> = {
    USER: 'User', USER_KEY: 'User key', FREEFORM_KEY: 'Freeform key', FEDERATED_KEY: 'Federated key',
    COMPONENT_KEY: 'Component key', ORGANIZATION_RW_KEY: 'Organization read-write key', OTHER_KEY: 'Other key', NONE: 'Not recorded'
}
const HOLDS_NOW_TEXT: Record<string, string> = { YES: 'Yes', NO: 'No', UNKNOWN: 'Unknown' }

// The scope with its resolved name where the inbox already resolves it (the proposal is in the list on
// screen, e.g. under Accepted); otherwise the scope and its uuid.
const shownProposals = computed((): Record<string, any> =>
    Object.fromEntries(proposals.value.map((p: any) => [p.uuid, p])))

function auditScope (r: any) {
    const shown = shownProposals.value[r.proposal]
    if (shown) return renderScope(shown)
    const tag = h(NTag, { size: 'small', round: true, style: 'margin-right: 6px;' }, () => r.scope)
    return r.scope === 'ORG' ? tag : h('span', {}, [tag, h('span', { class: 'text-muted' }, r.scopeUuid)])
}

const auditColumns = [
    { title: 'Finding', key: 'findingId' },
    { title: 'Location', key: 'location' },
    { title: 'Scope', key: 'scope', render: (r: any) => auditScope(r) },
    { title: 'State', key: 'analysisState' },
    { title: 'Accepted at', key: 'acceptedAt', render: (r: any) => r.acceptedAt ? new Date(r.acceptedAt).toLocaleString() : '—' },
    { title: 'Accepted via', key: 'acceptedVia', render: (r: any) => r.acceptedVia === 'IMPORT' ? 'Import' : r.acceptedVia === 'MANUAL' ? 'Manual' : '—' },
    { title: 'Accepted by kind', key: 'acceptedByKind', render: (r: any) => ACCEPTED_BY_KIND_TEXT[r.acceptedByKind] ?? r.acceptedByKind },
    { title: 'Accepted by', key: 'acceptedBy', render: (r: any) => formatUser(r.acceptedBy) },
    { title: 'Acted by', key: 'actedBy', render: (r: any) => formatUser(r.actedBy) },
    {
        title: 'Holds now',
        key: 'holdsFindingAnalysisWriteNow',
        render: (r: any) => {
            const v = r.holdsFindingAnalysisWriteNow
            const type = v === 'YES' ? 'success' : v === 'NO' ? 'error' : 'warning'
            return h(NTag, { type, size: 'small', round: true }, () => HOLDS_NOW_TEXT[v] ?? v)
        }
    },
    {
        title: 'Still as accepted',
        key: 'analysisStillAsAccepted',
        render: (r: any) => r.analysisStillAsAccepted === true ? 'Yes' : r.analysisStillAsAccepted === false ? 'No' : '—'
    }
]

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
