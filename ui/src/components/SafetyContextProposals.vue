<template>
    <div>
        <n-text depth="3" style="font-size: 12px; display: block; margin-bottom: 8px;" data-testid="safety-context-inbox-note">
            {{ DECLARED_NOT_VERIFIED }} Accepting records the level against the component; it does not verify it.
        </n-text>
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
        <n-text depth="3" style="font-size: 12px; display: block; margin-top: 4px;">
            * Hover the reason for details: a conflicting declaration, an issuer raised or ignored override.
        </n-text>
        <n-text v-if="truncated" type="warning" style="font-size: 12px;">
            Showing the first {{ PAGE_LIMIT }} proposals; act on these to see the rest.
        </n-text>

        <n-modal v-model:show="acceptOpen" preset="card" title="Accept safety level" style="max-width: 560px;">
            <n-space vertical v-if="current">
                <n-descriptions :column="1" size="small" label-placement="left" bordered>
                    <n-descriptions-item label="Component">{{ current.componentPurl }}</n-descriptions-item>
                    <n-descriptions-item label="Level">
                        {{ current.levelNormalized || current.levelRaw }} ({{ current.schemeKey }})
                        <span v-if="current.levelNormalized && current.levelNormalized !== current.levelRaw"> - declared as "{{ current.levelRaw }}"</span>
                    </n-descriptions-item>
                    <n-descriptions-item label="Compared with the current level">{{ current.levelChange || '-' }}</n-descriptions-item>
                    <n-descriptions-item label="Why it was staged">{{ stagedReason(current) }}</n-descriptions-item>
                    <n-descriptions-item label="Issuer">
                        {{ current.issuerClassEffective }}
                        <span v-if="current.elevatedByOverride"> (raised from {{ current.issuerClassDerived }} by {{ current.overrideActorName || 'an override' }})</span>
                    </n-descriptions-item>
                    <n-descriptions-item label="Component match">{{ current.matchStatus }}</n-descriptions-item>
                    <n-descriptions-item v-if="current.docSerialNumber" label="Document">
                        {{ current.docSerialNumber }}<span v-if="current.docVersion"> v{{ current.docVersion }}</span>
                    </n-descriptions-item>
                </n-descriptions>
                <n-alert v-if="current.conflictFlag" type="warning" :show-icon="false">
                    Another declaration for this component and scheme disagrees with this one.
                </n-alert>
                <n-alert v-if="current.sourceDeleted" type="warning" :show-icon="false">
                    The uploaded document this level came from has been archived.
                </n-alert>
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
import { RouterLink, useRoute } from 'vue-router'
import { NAlert, NButton, NDataTable, NDescriptions, NDescriptionsItem, NFormItem, NIcon, NInput, NModal, NSelect, NSpace, NTabPane, NTabs, NTag, NText, NTooltip } from 'naive-ui'
import { Check, Code, X } from '@vicons/tabler'
import Swal from 'sweetalert2'
import graphqlClient from '@/utils/graphql'
import commonFunctions from '@/utils/commonFunctions'
import {
    ACCEPT_SAFETY_PROPOSAL, DECLARED_NOT_VERIFIED, GET_RELEASE_SBOM_COMPONENT_CHOICES,
    GET_SAFETY_PROPOSALS, GET_SAFETY_PROPOSALS_BY_RELEASE, REJECT_SAFETY_PROPOSAL, stagedReason
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
const PAGE_LIMIT = 500
const truncated = computed(() => proposals.value.length >= PAGE_LIMIT)
// Only the latest request may write the list: a slow earlier response must not overwrite a newer tab.
let requestSeq = 0

async function fetchProposals () {
    if (!org.value) return
    const seq = ++requestSeq
    loading.value = true
    try {
        if (props.releaseUuid) {
            const r = await graphqlClient.query({
                query: GET_SAFETY_PROPOSALS_BY_RELEASE,
                variables: { org: org.value, release: props.releaseUuid, limit: PAGE_LIMIT },
                fetchPolicy: 'network-only'
            })
            if (seq === requestSeq) proposals.value = r.data?.getSafetyContextProposalsByRelease ?? []
        } else {
            const r = await graphqlClient.query({
                query: GET_SAFETY_PROPOSALS,
                variables: { org: org.value, status: statusFilter.value, limit: PAGE_LIMIT },
                fetchPolicy: 'network-only'
            })
            if (seq === requestSeq) proposals.value = r.data?.getSafetyContextProposals ?? []
        }
    } catch (err: any) {
        Swal.fire('Error!', commonFunctions.parseGraphQLError(err.message), 'error')
    } finally {
        if (seq === requestSeq) loading.value = false
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
    componentOptions.value = []
    acceptOpen.value = true
    if (row.matchStatus !== 'MATCHED' && row.releaseUuid) {
        componentsLoading.value = true
        try {
            const r = await graphqlClient.query({
                query: GET_RELEASE_SBOM_COMPONENT_CHOICES,
                variables: { releaseUuid: row.releaseUuid },
                fetchPolicy: 'network-only'
            })
            if (current.value?.uuid === row.uuid) {
                componentOptions.value = (r.data?.getReleaseSbomComponents ?? []).map((c: any) => ({
                    label: c.component?.canonicalPurl || c.sbomComponentUuid,
                    value: c.sbomComponentUuid
                }))
            }
        } catch (err: any) {
            Swal.fire('Error!', 'Could not load the release components: ' + commonFunctions.parseGraphQLError(err.message), 'error')
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
        { title: 'Asset', key: 'assetRefId', ellipsis: { tooltip: true } },
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
                const text = stagedReason(r)
                return flags.length
                    ? h(NTooltip, { trigger: 'hover' }, { trigger: () => h('span', {}, `${text} *`), default: () => flags.join('; ') })
                    : text
            }
        },
        { title: 'Issuer', key: 'issuerClassEffective' }
    ]
    if (!props.releaseUuid) {
        cols.splice(1, 0, {
            title: 'Release',
            key: 'releaseUuid',
            render: (r: any) => r.releaseUuid
                ? h(RouterLink, { to: `/release/show/${r.releaseUuid}` }, () => 'open release')
                : '-'
        })
    }
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
