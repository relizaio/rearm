<template>
    <div>
        <n-text depth="3" style="font-size: 12px; display: block; margin-bottom: 8px;" data-testid="safety-context-inbox-note">
            {{ DECLARED_NOT_VERIFIED }} Accepting records the level against the component; it does not verify it.
        </n-text>
        <!-- Radio buttons, not nested segment tabs: inside the Finding Analysis tabs a nested
             segment n-tabs renders without its labels. -->
        <n-radio-group v-if="!releaseUuid" v-model:value="statusFilter" size="small" style="margin-bottom: 8px;"
            data-testid="safety-context-status-filter">
            <n-radio-button value="PENDING">Pending</n-radio-button>
            <n-radio-button value="ACCEPTED">Accepted</n-radio-button>
            <n-radio-button value="REJECTED">Rejected</n-radio-button>
            <n-radio-button value="SUPERSEDED">Superseded</n-radio-button>
        </n-radio-group>
        <n-space v-if="selectable" align="center" style="margin-bottom: 8px;" data-testid="safety-bulk-reject-bar">
            <n-button size="small" type="error" :disabled="!checked.length || tooMany" data-testid="safety-reject-selected"
                @click="openBulkSelection">
                Reject selected ({{ checked.length }})
            </n-button>
            <n-text v-if="tooMany" type="error" data-testid="safety-reject-too-many">{{ MAX_BULK_REJECT_TEXT }}</n-text>
        </n-space>
        <n-alert v-if="loadError" type="error" style="margin-bottom: 8px;" data-testid="safety-proposals-load-error">
            Could not load the proposals of this release: {{ loadError }}
        </n-alert>
        <n-data-table
            v-model:checked-row-keys="checked"
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
                <n-descriptions :column="1" size="small" label-placement="left" bordered class="safety-wrap">
                    <n-descriptions-item label="Component">{{ current.componentPurl }}</n-descriptions-item>
                    <n-descriptions-item label="Level">
                        {{ current.levelNormalized || current.levelRaw }} ({{ current.schemeKey }})
                        <span v-if="current.levelNormalized && current.levelNormalized !== current.levelRaw"> - declared as "{{ current.levelRaw }}"</span>
                    </n-descriptions-item>
                    <n-descriptions-item label="Current level">
                        <span data-testid="safety-current-level">{{ currentLevelText }}</span>
                    </n-descriptions-item>
                    <n-descriptions-item v-if="baseline" label="Scope">
                        <span data-testid="safety-baseline-scope">{{ scopeLabel(baseline.scope) }}</span>
                    </n-descriptions-item>
                    <n-descriptions-item v-if="baseline" label="Compared as">
                        <span data-testid="safety-baseline-compared">{{ COMPARED_WITH_LABELS[ctx.comparedWith] || ctx.comparedWith || '-' }}</span>
                    </n-descriptions-item>
                    <n-descriptions-item v-if="baseline" label="Source release">
                        <span data-testid="safety-baseline-source">
                            <router-link v-if="baselineSourceHref" :to="baselineSourceHref">{{ baselineSourceText }}</router-link>
                            <template v-else>{{ baselineSourceText }}</template>
                        </span>
                    </n-descriptions-item>
                    <n-descriptions-item v-if="baseline" label="Accepted">
                        <span data-testid="safety-baseline-accepted">{{ baselineAcceptedText(baseline, ctx.baselineDetail) }}</span>
                    </n-descriptions-item>
                    <n-descriptions-item v-if="baseline && ctx.baselineDetail === 'FULL' && baseline.acceptComment" label="Accept comment">
                        <span data-testid="safety-baseline-comment" style="white-space: pre-wrap;">{{ baseline.acceptComment }}</span>
                    </n-descriptions-item>
                    <n-descriptions-item label="Compared with the current level">
                        <span data-testid="safety-level-change">{{ ctx ? ctx.levelChange : '-' }}</span>
                    </n-descriptions-item>
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
                <n-alert v-if="ctxError" type="error" :show-icon="false" data-testid="safety-context-error">
                    Could not load the current level: {{ ctxError }}
                </n-alert>
                <n-alert v-if="ctxDecided" type="warning" :show-icon="false" data-testid="safety-context-decided">
                    This proposal was already decided ({{ ctxDecided }}). Close to refresh the list.
                </n-alert>
                <n-alert v-if="levelChangeMoved" type="warning" :show-icon="false" data-testid="safety-level-change-moved">
                    When it was uploaded this level compared as {{ current.levelChange }}; against the current level it is now {{ ctx.levelChange }}.
                </n-alert>
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
                <n-alert v-if="componentsError" type="error" :show-icon="false" data-testid="safety-components-error">
                    Could not load the release components: {{ componentsError }}
                </n-alert>
                <n-form-item :label="needsComment ? 'Comment (required: lower than or not comparable with the current level)' : 'Comment'"
                    :required="needsComment">
                    <n-input v-model:value="acceptComment" type="textarea" maxlength="2000" show-count />
                </n-form-item>
                <n-alert v-if="acceptError" type="error" :show-icon="false" data-testid="safety-accept-error" class="safety-wrap">
                    {{ acceptError }}
                </n-alert>
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

        <n-modal v-model:show="bulkOpen" preset="card" title="Reject safety levels" style="max-width: 560px;">
            <n-space vertical>
                <div data-testid="safety-bulk-reject-body" class="safety-wrap">{{ bulkBody }}</div>
                <n-form-item label="Reason (required, recorded on every level)">
                    <n-input v-model:value="bulkReason" type="textarea" maxlength="2000" show-count :disabled="!!bulkResult" />
                </n-form-item>
                <n-alert v-if="bulkResult" :type="bulkResult.rejected > 0 ? 'success' : 'warning'" :show-icon="false"
                    data-testid="safety-bulk-reject-result">
                    <div v-for="line in bulkResultLines" :key="line" data-testid="safety-bulk-reject-line">{{ line }}</div>
                </n-alert>
                <n-alert v-if="bulkError" type="error" :show-icon="false" data-testid="safety-bulk-reject-error" class="safety-wrap">
                    Nothing was rejected. {{ bulkError }}
                </n-alert>
                <n-space justify="end">
                    <n-button v-if="bulkResult" type="primary" data-testid="safety-bulk-reject-close" @click="bulkOpen = false">Close</n-button>
                    <template v-else>
                        <n-button @click="bulkOpen = false">Cancel</n-button>
                        <n-button type="error" :disabled="!bulkReason.trim() || bulkActing" :loading="bulkActing"
                            data-testid="safety-bulk-reject-submit" @click="doBulkReject">Reject</n-button>
                    </template>
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
import { NAlert, NButton, NDataTable, NDescriptions, NDescriptionsItem, NFormItem, NIcon, NInput, NModal, NRadioButton, NRadioGroup, NSelect, NSpace, NTag, NText, NTooltip } from 'naive-ui'
import { ArrowBackUp, Check, Code, X } from '@vicons/tabler'
import Swal from 'sweetalert2'
import graphqlClient from '@/utils/graphql'
import commonFunctions from '@/utils/commonFunctions'
import { graphqlRefusalText } from '@/utils/graphqlErrorText'
import {
    ACCEPT_SAFETY_PROPOSAL, BASELINE_CHANGED_TEXT, COMPARED_WITH_LABELS, DECLARED_NOT_VERIFIED,
    GET_RELEASE_SBOM_COMPONENT_CHOICES, GET_SAFETY_DECISION_CONTEXT, GET_SAFETY_PROPOSALS, GET_SAFETY_PROPOSALS_BY_RELEASE,
    MAX_BULK_REJECT, MAX_BULK_REJECT_TEXT, REJECT_SAFETY_PROPOSAL, REJECT_SAFETY_PROPOSALS, baselineAcceptedText,
    bulkRejectDocumentText, bulkRejectResultLines, bulkRejectSelectionText, notPendingStatus, proposalStatusText,
    scopeLabel, sourceReleaseHref, sourceReleaseLabel, stagedReason
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
// Release mode shows a load error inline; the org inbox keeps its popup.
const loadError = ref<string | null>(null)
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
            if (seq === requestSeq) {
                proposals.value = (r.data as any)?.getSafetyContextProposalsByRelease ?? []
                loadError.value = null
            }
        } else {
            const r = await graphqlClient.query({
                query: GET_SAFETY_PROPOSALS,
                variables: { org: org.value, status: statusFilter.value, limit: PAGE_LIMIT },
                fetchPolicy: 'network-only'
            })
            if (seq === requestSeq) proposals.value = (r.data as any)?.getSafetyContextProposals ?? []
        }
        if (seq === requestSeq) checked.value = []
    } catch (err: any) {
        if (props.releaseUuid) {
            if (seq === requestSeq) loadError.value = graphqlRefusalText(err)
        } else {
            Swal.fire('Error!', commonFunctions.parseGraphQLError(err.message), 'error')
        }
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
const componentsError = ref<string | null>(null)
const acceptError = ref<string | null>(null)

// The decision context: what accepting would compare the proposal with now.
const ctx = ref<any>(null)
const ctxLoading = ref(false)
const ctxError = ref<string | null>(null)
const ctxDecided = ref<string | null>(null)
let ctxSeq = 0

const baseline = computed(() => ctx.value?.baseline ?? null)
const currentLevelText = computed(() => {
    if (ctxLoading.value) return 'Loading the current level...'
    if (!ctx.value) return '-'
    if (!baseline.value) return 'None declared for this package on this release\'s scopes'
    return `${baseline.value.levelNormalized || baseline.value.levelRaw} (${baseline.value.schemeKey})`
})
const baselineSourceText = computed(() => ctx.value?.baselineDetail === 'LEVEL_ONLY'
    ? 'not visible to you' : sourceReleaseLabel(baseline.value))
const baselineSourceHref = computed(() => ctx.value?.baselineDetail === 'LEVEL_ONLY'
    ? null : sourceReleaseHref(baseline.value))
const levelChangeMoved = computed(() => !!ctx.value && !!current.value
    && !!current.value.levelChange && ctx.value.levelChange !== current.value.levelChange)

const needsComponent = computed(() => current.value && current.value.matchStatus !== 'MATCHED')
// The comment rule follows the comparison made now, not the one stored at upload.
const needsComment = computed(() => !!ctx.value
    && (ctx.value.levelChange === 'DOWNGRADE' || ctx.value.levelChange === 'UNCOMPARABLE'))
const canAccept = computed(() => !!ctx.value && !ctxLoading.value
    && (!needsComponent.value || !!acceptComponent.value)
    && (!needsComment.value || acceptComment.value.trim().length > 0))

async function loadContext (row: any) {
    const seq = ++ctxSeq
    ctxLoading.value = true
    ctx.value = null
    ctxError.value = null
    ctxDecided.value = null
    try {
        const r = await graphqlClient.query({
            query: GET_SAFETY_DECISION_CONTEXT,
            variables: { uuid: row.uuid },
            fetchPolicy: 'network-only'
        })
        if (seq !== ctxSeq) return
        ctx.value = (r.data as any)?.getSafetyContextDecisionContext ?? null
        if (!ctx.value) ctxError.value = 'no answer'
    } catch (err: any) {
        if (seq !== ctxSeq) return
        const text = graphqlRefusalText(err)
        const status = notPendingStatus(text)
        if (status) ctxDecided.value = status
        else ctxError.value = text
    } finally {
        if (seq === ctxSeq) ctxLoading.value = false
    }
}

async function loadComponents (row: any) {
    componentsLoading.value = true
    componentsError.value = null
    try {
        const r = await graphqlClient.query({
            query: GET_RELEASE_SBOM_COMPONENT_CHOICES,
            variables: { releaseUuid: row.releaseUuid },
            fetchPolicy: 'network-only'
        })
        if (current.value?.uuid === row.uuid) {
            componentOptions.value = ((r.data as any)?.getReleaseSbomComponents ?? []).map((c: any) => ({
                label: c.component?.canonicalPurl || c.sbomComponentUuid,
                value: c.sbomComponentUuid
            }))
        }
    } catch (err: any) {
        if (current.value?.uuid === row.uuid) componentsError.value = graphqlRefusalText(err)
    } finally {
        componentsLoading.value = false
    }
}

async function openAccept (row: any) {
    current.value = row
    acceptComment.value = ''
    acceptComponent.value = null
    componentOptions.value = []
    componentsError.value = null
    acceptError.value = null
    acceptOpen.value = true
    const loads = [loadContext(row)]
    if (row.matchStatus !== 'MATCHED' && row.releaseUuid) loads.push(loadComponents(row))
    await Promise.all(loads)
}

// A proposal someone else decided meanwhile: closing the dialog refreshes the list.
watch(acceptOpen, (open) => {
    if (!open && ctxDecided.value) fetchProposals()
})

function openReject (row: any) {
    current.value = row
    rejectReason.value = ''
    rejectOpen.value = true
}

async function doAccept () {
    if (!canAccept.value || acting.value) return
    const row = current.value
    acting.value = true
    acceptError.value = null
    try {
        await graphqlClient.mutate({
            mutation: ACCEPT_SAFETY_PROPOSAL,
            variables: {
                uuid: row.uuid, component: acceptComponent.value, comment: acceptComment.value || null,
                expectedBaseline: ctx.value.baselineToken
            }
        })
        acceptOpen.value = false
        emit('changed')
        await fetchProposals()
    } catch (err: any) {
        const text = graphqlRefusalText(err)
        acceptError.value = text
        // The level it compares with moved: show the new one; the comment stays.
        if (text.includes(BASELINE_CHANGED_TEXT)) await loadContext(row)
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

// ---- bulk reject --------------------------------------------------------------------------
const checked = ref<string[]>([])
const selectable = computed(() => !!props.releaseUuid || statusFilter.value === 'PENDING')
const tooMany = computed(() => checked.value.length > MAX_BULK_REJECT)
const bulkOpen = ref(false)
const bulkMode = ref<'selection' | 'document'>('selection')
const bulkRow = ref<any>(null)
const bulkCount = ref(0)
const bulkReason = ref('')
const bulkActing = ref(false)
const bulkResult = ref<any>(null)
const bulkError = ref<string | null>(null)
const bulkBody = computed(() => bulkMode.value === 'document'
    ? bulkRejectDocumentText(bulkRow.value) : bulkRejectSelectionText(bulkCount.value))
const bulkResultLines = computed(() => bulkRejectResultLines(bulkResult.value))

function resetBulk () {
    bulkReason.value = ''
    bulkResult.value = null
    bulkError.value = null
}

function openBulkSelection () {
    if (!checked.value.length || tooMany.value) return
    resetBulk()
    bulkMode.value = 'selection'
    bulkRow.value = null
    bulkCount.value = checked.value.length
    bulkOpen.value = true
}

function openBulkDocument (row: any) {
    resetBulk()
    bulkMode.value = 'document'
    bulkRow.value = row
    bulkOpen.value = true
}

async function doBulkReject () {
    if (bulkActing.value || !bulkReason.value.trim()) return
    if (bulkMode.value === 'selection' && checked.value.length > MAX_BULK_REJECT) return
    const selection = bulkMode.value === 'document'
        ? { sourceArtifact: bulkRow.value.sourceArtifact, sourceDocumentHash: bulkRow.value.sourceDocumentHash }
        : { proposals: [...checked.value] }
    bulkActing.value = true
    bulkError.value = null
    try {
        const r = await graphqlClient.mutate({
            mutation: REJECT_SAFETY_PROPOSALS,
            variables: { org: org.value, selection, reason: bulkReason.value }
        })
        const result = (r.data as any)?.rejectSafetyContextProposals
        if (!result) throw new Error('no answer')
        bulkResult.value = result
        checked.value = []
        if (result.rejected > 0) emit('changed')
        await fetchProposals()
    } catch (err: any) {
        bulkError.value = graphqlRefusalText(err)
    } finally {
        bulkActing.value = false
    }
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
            ellipsis: { tooltip: true },
            render: (r: any) => {
                const text = sourceReleaseLabel(r)
                const to = sourceReleaseHref(r)
                return to ? h(RouterLink, { to }, () => text) : text
            }
        })
    }
    if (selectable.value) {
        cols.unshift({ type: 'selection', disabled: (r: any) => r.proposalStatus !== 'PENDING' })
    }
    if (props.releaseUuid || statusFilter.value !== 'PENDING') {
        cols.push({
            title: 'Status',
            key: 'proposalStatus',
            render: (r: any) => h(NTag, { size: 'small', round: true,
                type: r.proposalStatus === 'ACCEPTED' ? 'success' : r.proposalStatus === 'PENDING' ? 'warning'
                    : r.proposalStatus === 'REJECTED' ? 'error' : 'default' }, () => proposalStatusText(r))
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
        width: 170,
        render: (r: any) => {
            const buttons = [iconButton('View the imported asset', Code, 'default', () => openSource(r))]
            if (r.proposalStatus === 'PENDING') {
                const conflict = r.gateRule === 'INTRA_ASSET_CONFLICT'
                buttons.push(iconButton(conflict ? 'Conflicting levels: reject and correct the document' : 'Accept',
                    Check, 'success', () => openAccept(r), conflict))
                buttons.push(iconButton('Reject', X, 'error', () => openReject(r)))
                if (r.sourceArtifact && r.sourceDocumentHash) {
                    buttons.push(iconButton('Undo this upload: reject all its pending levels', ArrowBackUp, 'warning',
                        () => openBulkDocument(r)))
                }
            }
            return h('span', {}, buttons)
        }
    })
    return cols
})
</script>

<style scoped>
.safety-wrap {
    word-break: break-word;
}
</style>
