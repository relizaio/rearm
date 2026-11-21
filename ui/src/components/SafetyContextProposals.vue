<template>
    <div>
        <n-tabs v-if="!releaseUuid" v-model:value="statusFilter" type="segment" animated>
            <n-tab-pane name="PENDING" tab="Pending" />
            <n-tab-pane name="ACCEPTED" tab="Accepted" />
            <n-tab-pane name="REJECTED" tab="Rejected" />
            <n-tab-pane name="SUPERSEDED" tab="Superseded" />
        </n-tabs>
        <n-data-table
            :columns="columns"
            :data="proposals"
            :loading="loading"
            :row-key="(row: any) => row.uuid"
            :pagination="{ pageSize: 25 }"
            data-testid="safety-context-proposals"
        />

        <n-modal v-model:show="acceptOpen" preset="card" title="Accept safety level" style="max-width: 560px;">
            <n-space vertical v-if="current">
                <n-text>
                    {{ current.componentPurl }}: {{ current.schemeKey }} {{ current.levelNormalized || current.levelRaw }}
                </n-text>
                <n-text depth="3" style="font-size: 12px;">{{ DECLARED_NOT_VERIFIED }}</n-text>
                <n-form-item v-if="needsComponent" label="Release component this level applies to" required>
                    <n-select v-model:value="acceptComponent" :options="componentOptions" filterable
                        :loading="componentsLoading" placeholder="Pick an SBOM component of the release" />
                </n-form-item>
                <n-form-item :label="needsComment ? 'Comment (required: lower than or not comparable with the current level)' : 'Comment'"
                    :required="needsComment">
                    <n-input v-model:value="acceptComment" type="textarea" maxlength="2000" show-count />
                </n-form-item>
                <n-space justify="end">
                    <n-button @click="acceptOpen = false">Cancel</n-button>
                    <n-button type="primary" :disabled="!canAccept" :loading="acting" @click="doAccept">Accept</n-button>
                </n-space>
            </n-space>
        </n-modal>

        <n-modal v-model:show="rejectOpen" preset="card" title="Reject safety level" style="max-width: 520px;">
            <n-space vertical>
                <n-form-item label="Reason">
                    <n-input v-model:value="rejectReason" type="textarea" maxlength="2000" show-count />
                </n-form-item>
                <n-space justify="end">
                    <n-button @click="rejectOpen = false">Cancel</n-button>
                    <n-button type="error" :loading="acting" @click="doReject">Reject</n-button>
                </n-space>
            </n-space>
        </n-modal>

        <safety-context-source-modal v-model:show="sourceOpen" :uuid="sourceUuid" />
    </div>
</template>

<script lang="ts">
export default {
    name: 'SafetyContextProposals'
}
</script>
<script lang="ts" setup>
import { computed, h, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { NButton, NDataTable, NFormItem, NIcon, NInput, NModal, NSelect, NSpace, NTabPane, NTabs, NTag, NText, NTooltip } from 'naive-ui'
import { Check, Code, X } from '@vicons/tabler'
import Swal from 'sweetalert2'
import graphqlClient from '@/utils/graphql'
import commonFunctions from '@/utils/commonFunctions'
import {
    ACCEPT_SAFETY_PROPOSAL, DECLARED_NOT_VERIFIED, GATE_RULE_LABELS, GET_RELEASE_SBOM_COMPONENT_CHOICES,
    GET_SAFETY_PROPOSALS, GET_SAFETY_PROPOSALS_BY_RELEASE, REJECT_SAFETY_PROPOSAL
} from '@/graphql/safetyContext'
import SafetyContextSourceModal from './SafetyContextSourceModal.vue'

const props = defineProps<{
    orgUuid?: string
    // When set, lists every proposal of this release (all statuses) instead of the org inbox.
    releaseUuid?: string
}>()
const emit = defineEmits(['changed'])

const route = useRoute()
const org = computed(() => props.orgUuid || (route.params.orguuid as string))
const statusFilter = ref<'PENDING' | 'ACCEPTED' | 'REJECTED' | 'SUPERSEDED'>('PENDING')
const proposals = ref<any[]>([])
const loading = ref(false)

async function fetchProposals () {
    if (!org.value) return
    loading.value = true
    try {
        if (props.releaseUuid) {
            const r = await graphqlClient.query({
                query: GET_SAFETY_PROPOSALS_BY_RELEASE,
                variables: { org: org.value, release: props.releaseUuid, limit: 500 },
                fetchPolicy: 'network-only'
            })
            proposals.value = r.data?.getSafetyContextProposalsByRelease ?? []
        } else {
            const r = await graphqlClient.query({
                query: GET_SAFETY_PROPOSALS,
                variables: { org: org.value, status: statusFilter.value, limit: 500 },
                fetchPolicy: 'network-only'
            })
            proposals.value = r.data?.getSafetyContextProposals ?? []
        }
    } catch (err: any) {
        Swal.fire('Error!', commonFunctions.parseGraphQLError(err.message), 'error')
    } finally {
        loading.value = false
    }
}

onMounted(fetchProposals)
watch([statusFilter, org, () => props.releaseUuid], fetchProposals)
defineExpose({ refresh: fetchProposals })

// ---- accept / reject ----------------------------------------------------------------------
const current = ref<any>(null)
const acceptOpen = ref(false)
const rejectOpen = ref(false)
const acceptComment = ref('')
const acceptComponent = ref<string | null>(null)
const rejectReason = ref('')
const acting = ref(false)
const componentOptions = ref<any[]>([])
const componentsLoading = ref(false)

const needsComponent = computed(() => current.value && current.value.matchStatus !== 'MATCHED')
const needsComment = computed(() => current.value
    && (current.value.levelChange === 'DOWNGRADE' || current.value.levelChange === 'UNCOMPARABLE'))
const canAccept = computed(() => (!needsComponent.value || !!acceptComponent.value)
    && (!needsComment.value || acceptComment.value.trim().length > 0))

async function openAccept (row: any) {
    current.value = row
    acceptComment.value = ''
    acceptComponent.value = null
    acceptOpen.value = true
    if (row.matchStatus !== 'MATCHED' && row.releaseUuid) {
        componentsLoading.value = true
        try {
            const r = await graphqlClient.query({
                query: GET_RELEASE_SBOM_COMPONENT_CHOICES,
                variables: { releaseUuid: row.releaseUuid },
                fetchPolicy: 'network-only'
            })
            componentOptions.value = (r.data?.getReleaseSbomComponents ?? []).map((c: any) => ({
                label: c.component?.canonicalPurl || c.sbomComponentUuid,
                value: c.sbomComponentUuid
            }))
        } finally {
            componentsLoading.value = false
        }
    }
}

function openReject (row: any) {
    current.value = row
    rejectReason.value = ''
    rejectOpen.value = true
}

async function doAccept () {
    acting.value = true
    try {
        await graphqlClient.mutate({
            mutation: ACCEPT_SAFETY_PROPOSAL,
            variables: { uuid: current.value.uuid, component: acceptComponent.value, comment: acceptComment.value || null }
        })
        acceptOpen.value = false
        emit('changed')
        await fetchProposals()
    } catch (err: any) {
        Swal.fire('Error!', commonFunctions.parseGraphQLError(err.message), 'error')
    } finally {
        acting.value = false
    }
}

async function doReject () {
    acting.value = true
    try {
        await graphqlClient.mutate({
            mutation: REJECT_SAFETY_PROPOSAL,
            variables: { uuid: current.value.uuid, reason: rejectReason.value || null }
        })
        rejectOpen.value = false
        emit('changed')
        await fetchProposals()
    } catch (err: any) {
        Swal.fire('Error!', commonFunctions.parseGraphQLError(err.message), 'error')
    } finally {
        acting.value = false
    }
}

const sourceOpen = ref(false)
const sourceUuid = ref<string | null>(null)
function openSource (row: any) {
    sourceUuid.value = row.uuid
    sourceOpen.value = true
}

// ---- table --------------------------------------------------------------------------------
function iconButton (title: string, icon: any, type: any, onClick: () => void, disabled = false) {
    return h(NButton, { size: 'small', type, title, disabled, onClick, style: 'margin-right: 4px;' },
        { default: () => h(NIcon, null, { default: () => h(icon) }) })
}

const columns = computed(() => {
    const cols: any[] = [
        { title: 'Component', key: 'componentPurl', ellipsis: { tooltip: true } },
        {
            title: 'Level',
            key: 'levelRaw',
            render: (r: any) => h('span', {}, [
                h('strong', {}, r.levelNormalized || r.levelRaw),
                h('span', { class: 'text-muted' }, ` (${r.schemeKey})`)
            ])
        },
        {
            title: 'Change',
            key: 'levelChange',
            render: (r: any) => r.levelChange
                ? h(NTag, { size: 'small', round: true,
                    type: r.levelChange === 'DOWNGRADE' ? 'error' : r.levelChange === 'UNCOMPARABLE' ? 'warning' : 'default' },
                () => r.levelChange)
                : '-'
        },
        {
            title: 'Why',
            key: 'gateRule',
            render: (r: any) => {
                const flags: string[] = []
                if (r.conflictFlag) flags.push('conflicts with another declaration')
                if (r.elevatedByOverride) flags.push(`issuer raised by override (${r.overrideActorName || 'unknown user'})`)
                if ((r.notes || []).includes('OVERRIDE_IGNORED')) flags.push('issuer override ignored')
                const text = GATE_RULE_LABELS[r.gateRule] || r.gateRule || '-'
                return flags.length
                    ? h(NTooltip, { trigger: 'hover' }, { trigger: () => h('span', {}, `${text} *`), default: () => flags.join('; ') })
                    : text
            }
        },
        { title: 'Issuer', key: 'issuerClassEffective' }
    ]
    if (props.releaseUuid || statusFilter.value !== 'PENDING') {
        cols.push({
            title: 'Status',
            key: 'proposalStatus',
            render: (r: any) => h(NTag, { size: 'small', round: true,
                type: r.proposalStatus === 'ACCEPTED' ? 'success' : r.proposalStatus === 'PENDING' ? 'warning'
                    : r.proposalStatus === 'REJECTED' ? 'error' : 'default' }, () => r.proposalStatus)
        })
        cols.push({
            title: 'Acted',
            key: 'actedAt',
            render: (r: any) => r.actedAt ? new Date(r.actedAt).toLocaleString() : '-'
        })
    }
    cols.push({
        title: 'Actions',
        key: 'actions',
        width: 130,
        render: (r: any) => {
            const buttons = [iconButton('View the imported asset', Code, 'default', () => openSource(r))]
            if (r.proposalStatus === 'PENDING') {
                const conflict = r.gateRule === 'INTRA_ASSET_CONFLICT'
                buttons.push(iconButton(conflict ? 'Conflicting levels: reject and correct the document' : 'Accept',
                    Check, 'success', () => openAccept(r), conflict))
                buttons.push(iconButton('Reject', X, 'error', () => openReject(r)))
            }
            return h('span', {}, buttons)
        }
    })
    return cols
})
</script>
