<template>
    <n-spin :show="loading">
        <n-card v-if="proposal" :title="`VEX Proposal — ${proposal.findingId}`">

            <n-alert v-if="sameScopeAnalyses.length > 0" type="info" style="margin-bottom: 12px;">
                <template #header>Finding-analysis row exists at this scope</template>
                <div style="margin-bottom: 6px;">
                    A <strong>{{ sameScopeAnalyses[0].analysisState }}</strong> analysis row exists for
                    <code>{{ proposal.findingId }}</code> on
                    <code>{{ proposal.location }}</code>
                    at <strong>{{ proposal.scope }}</strong> scope.
                </div>
                <n-button text type="primary" @click="goToFindingAnalysis">Open in Finding Analysis →</n-button>
            </n-alert>

            <n-grid :cols="2" x-gap="24">
                <n-gi>
                    <n-h4>Original VEX statement</n-h4>
                    <n-code language="json" :code="formatJson(proposal.sourceStatementJson)" />
                </n-gi>
                <n-gi>
                    <n-h4>
                        Proposed ReARM analysis
                        <n-button v-if="proposal.status === 'PENDING' && !editing"
                            text size="small" type="primary" style="margin-left: 8px;"
                            @click="startEditing">
                            Modify
                        </n-button>
                    </n-h4>

                    <!-- Read-only view -->
                    <n-descriptions v-if="!editing" :column="1" bordered label-placement="left" :label-style="{ width: '160px', minWidth: '160px' }">
                        <n-descriptions-item label="Vulnerability">{{ proposal.findingId }}</n-descriptions-item>
                        <n-descriptions-item label="Component">{{ proposal.rawLocation }}</n-descriptions-item>
                        <n-descriptions-item label="State">{{ proposal.analysisState }}</n-descriptions-item>
                        <n-descriptions-item label="Justification">{{ proposal.analysisJustification ?? '—' }}</n-descriptions-item>
                        <n-descriptions-item label="Responses">{{ (proposal.responses?.length ? proposal.responses.join(', ') : '—') }}</n-descriptions-item>
                        <n-descriptions-item label="Severity">{{ proposal.severity ?? '—' }}</n-descriptions-item>
                        <n-descriptions-item label="Aliases">{{ (proposal.findingAliases?.length ? proposal.findingAliases.join(', ') : '—') }}</n-descriptions-item>
                        <n-descriptions-item label="Details">{{ proposal.details ?? '—' }}</n-descriptions-item>
                        <n-descriptions-item label="Recommendation">{{ proposal.recommendation ?? '—' }}</n-descriptions-item>
                        <n-descriptions-item label="Workaround">{{ proposal.workaround ?? '—' }}</n-descriptions-item>
                        <n-descriptions-item label="Status">{{ proposal.status }}</n-descriptions-item>
                    </n-descriptions>

                    <!-- Edit form -->
                    <n-form v-else label-placement="left" :label-width="160" size="small">
                        <n-form-item label="State">
                            <n-select v-model:value="edits.analysisState" :options="stateOptions" />
                        </n-form-item>
                        <n-form-item label="Justification">
                            <n-select v-model:value="edits.analysisJustification" :options="justificationOptions" clearable />
                        </n-form-item>
                        <n-form-item label="Responses">
                            <n-select v-model:value="edits.responses" :options="responseOptions" multiple clearable />
                        </n-form-item>
                        <n-form-item label="Severity">
                            <n-select v-model:value="edits.severity" :options="severityOptions" clearable />
                        </n-form-item>
                        <n-form-item label="Details">
                            <n-input v-model:value="edits.details" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }" placeholder="Impact statement / details" />
                        </n-form-item>
                        <n-form-item label="Recommendation">
                            <n-input v-model:value="edits.recommendation" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }" placeholder="Recommended action" />
                        </n-form-item>
                        <n-form-item label="Workaround">
                            <n-input v-model:value="edits.workaround" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }" placeholder="Workaround text" />
                        </n-form-item>
                        <n-space>
                            <n-button size="small" type="primary" :loading="busy" @click="onSaveEdits">Save changes</n-button>
                            <n-button size="small" @click="cancelEditing">Cancel</n-button>
                        </n-space>
                    </n-form>

                    <n-divider />
                    <n-h4 v-if="proposal.translationNotes?.length">Translation notes</n-h4>
                    <n-list v-if="proposal.translationNotes?.length" bordered>
                        <n-list-item v-for="(note, i) in proposal.translationNotes" :key="i">{{ note }}</n-list-item>
                    </n-list>
                </n-gi>
            </n-grid>

            <n-divider />
            <n-h4 v-if="otherAnalyses.length || proposal.demotionReason">Existing analyses at other scopes for this finding</n-h4>
            <n-alert v-if="proposal.demotionReason === 'BROADER_SCOPE_CONFLICT'" type="warning" style="margin-bottom: 12px;">
                Auto-accept was demoted to STAGE: an analysis at a broader scope already exists with a different
                suppression class. Review the rows below before accepting.
            </n-alert>
            <n-data-table
                v-if="otherAnalyses.length"
                :columns="otherAnalysisColumns"
                :data="otherAnalyses"
                :pagination="{ pageSize: 10 }"
                size="small" />
            <n-text v-else-if="proposal.demotionReason" depth="3">
                No additional context rows found at this load — they may have been removed since the proposal was created.
            </n-text>

            <n-divider />

            <!-- Action area for PENDING -->
            <template v-if="proposal.status === 'PENDING' && !editing">
                <n-alert v-if="sameScopeAnalyses.length > 0" type="warning" style="margin-bottom: 12px;">
                    A finding-analysis row already exists at this exact scope (likely from an earlier accepted proposal).
                    <strong>Rejecting this proposal won't remove that row</strong> — Reject is an audit-only action on this batch entry. To change the rendered state, accept a contradicting VEX or edit the analysis directly.
                </n-alert>

                <n-space vertical :size="8">
                    <n-input
                        v-model:value="actionComment"
                        type="textarea"
                        :autosize="{ minRows: 2, maxRows: 4 }"
                        placeholder="Optional comment (reviewer note for accept, reason for reject)" />
                    <n-space>
                        <n-button type="primary" :loading="busy" @click="onAccept">Accept</n-button>
                        <n-button type="error" :loading="busy" :disabled="!actionComment.trim()" @click="onReject">
                            Reject
                        </n-button>
                        <n-text v-if="!actionComment.trim()" depth="3" style="font-size: 12px; align-self: center;">
                            (Reject requires a comment)
                        </n-text>
                    </n-space>
                </n-space>
            </template>

            <!-- Acted summary for completed proposals -->
            <n-alert v-else-if="proposal.mitigationAttestation && !proposal.targetVulnAnalysis && attestation?.status === 'WAIVED'" type="info">
                Proposal is <strong>{{ proposal.status }}</strong>; the linked mitigation was <strong>waived</strong>, so the deferred finding-analysis write is permanently abandoned.
                <div style="margin-top: 6px;">
                    <span v-if="proposal.actedBy" style="font-size: 12px;">Acted by: {{ formatUser(proposal.actedBy) }} at {{ formatTime(proposal.actedAt) }}</span>
                </div>
                <div v-if="proposal.statusReason" style="margin-top: 6px;">
                    <strong>Comment:</strong> {{ proposal.statusReason }}
                </div>
                <n-button text type="primary" @click="goToAttestation">Open attestation →</n-button>
            </n-alert>
            <n-alert v-else-if="proposal.mitigationAttestation && !proposal.targetVulnAnalysis" type="warning">
                Proposal is <strong>{{ proposal.status }}</strong>; the finding-analysis write is <strong>deferred</strong> until the linked mitigation is attested or waived.
                <div style="margin-top: 6px;">
                    <span v-if="proposal.actedBy" style="font-size: 12px;">Acted by: {{ formatUser(proposal.actedBy) }} at {{ formatTime(proposal.actedAt) }}</span>
                </div>
                <div v-if="proposal.statusReason" style="margin-top: 6px;">
                    <strong>Comment:</strong> {{ proposal.statusReason }}
                </div>
                <n-button text type="primary" @click="goToAttestation">Open attestation →</n-button>
            </n-alert>
            <n-alert v-else-if="proposal.mitigationAttestation && proposal.targetVulnAnalysis" type="success">
                Proposal is <strong>{{ proposal.status }}</strong>. Mitigation has been attested and a finding-analysis row was written.
                <div style="margin-top: 6px;">
                    <span v-if="proposal.actedBy" style="font-size: 12px;">Acted by: {{ formatUser(proposal.actedBy) }} at {{ formatTime(proposal.actedAt) }}</span>
                </div>
                <div v-if="proposal.statusReason" style="margin-top: 6px;">
                    <strong>Comment:</strong> {{ proposal.statusReason }}
                </div>
                <n-button text type="primary" @click="goToAttestation">View attestation →</n-button>
            </n-alert>
            <n-alert v-else-if="proposal.status !== 'PENDING'" :type="proposal.status === 'ACCEPTED' ? 'success' : 'info'">
                Proposal is <strong>{{ proposal.status }}</strong>.
                <div style="margin-top: 6px;">
                    <span v-if="proposal.actedBy" style="font-size: 12px;">Acted by: {{ formatUser(proposal.actedBy) }} at {{ formatTime(proposal.actedAt) }}</span>
                </div>
                <div v-if="proposal.statusReason" style="margin-top: 6px;">
                    <strong>Comment:</strong> {{ proposal.statusReason }}
                </div>
            </n-alert>
        </n-card>
    </n-spin>
</template>

<script lang="ts">
export default {
    name: 'VexProposalReview'
}
</script>
<script lang="ts" setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
    NAlert, NButton, NCard, NCode, NDataTable, NDescriptions, NDescriptionsItem, NDivider,
    NForm, NFormItem, NGi, NGrid, NH4, NInput, NList, NListItem, NSelect, NSpace, NSpin, NText, useMessage
} from 'naive-ui'
import graphqlClient from '@/utils/graphql'
import { useOrgUsersIndex } from '@/utils/userLookup'
import {
    ACCEPT_VEX_PROPOSAL, GET_MITIGATION_ATTESTATION, GET_VEX_PROPOSAL,
    GET_VULN_ANALYSIS_BY_LOCATION_AND_FINDING, REJECT_VEX_PROPOSAL, UPDATE_VEX_PROPOSAL
} from '@/graphql/vexImport'

const route = useRoute()
const router = useRouter()
const message = useMessage()
const uuid = computed(() => route.params.uuid as string)
const orgUuid = computed(() => route.params.orguuid as string)
const { format: formatUser } = useOrgUsersIndex(orgUuid)

function goToAttestation () {
    if (!proposal.value?.mitigationAttestation) return
    router.push({
        name: 'MitigationAttestationReview',
        params: { orguuid: orgUuid.value, uuid: proposal.value.mitigationAttestation }
    })
}

function goToFindingAnalysis () {
    if (!proposal.value) return
    router.push({
        name: 'VulnerabilityAnalysis',
        params: { orguuid: orgUuid.value },
        query: { cveId: proposal.value.findingId }
    })
}

const actionComment = ref('')
const busy = ref(false)
const loading = ref(false)
const proposal = ref<any | null>(null)
const attestation = ref<any | null>(null)
const allAnalyses = ref<any[]>([])

const editing = ref(false)
const edits = ref<any>({
    analysisState: null,
    analysisJustification: null,
    details: null,
    severity: null,
    responses: [],
    recommendation: null,
    workaround: null,
})

const stateOptions = [
    { label: 'EXPLOITABLE', value: 'EXPLOITABLE' },
    { label: 'IN_TRIAGE', value: 'IN_TRIAGE' },
    { label: 'NOT_AFFECTED', value: 'NOT_AFFECTED' },
    { label: 'FALSE_POSITIVE', value: 'FALSE_POSITIVE' },
    { label: 'RESOLVED', value: 'RESOLVED' },
]
const justificationOptions = [
    { label: 'CODE_NOT_PRESENT', value: 'CODE_NOT_PRESENT' },
    { label: 'CODE_NOT_REACHABLE', value: 'CODE_NOT_REACHABLE' },
    { label: 'REQUIRES_CONFIGURATION', value: 'REQUIRES_CONFIGURATION' },
    { label: 'REQUIRES_DEPENDENCY', value: 'REQUIRES_DEPENDENCY' },
    { label: 'REQUIRES_ENVIRONMENT', value: 'REQUIRES_ENVIRONMENT' },
    { label: 'PROTECTED_BY_COMPILER', value: 'PROTECTED_BY_COMPILER' },
    { label: 'PROTECTED_AT_RUNTIME', value: 'PROTECTED_AT_RUNTIME' },
    { label: 'PROTECTED_AT_PERIMETER', value: 'PROTECTED_AT_PERIMETER' },
    { label: 'PROTECTED_BY_MITIGATING_CONTROL', value: 'PROTECTED_BY_MITIGATING_CONTROL' },
]
const responseOptions = [
    { label: 'CAN_NOT_FIX', value: 'CAN_NOT_FIX' },
    { label: 'WILL_NOT_FIX', value: 'WILL_NOT_FIX' },
    { label: 'UPDATE', value: 'UPDATE' },
    { label: 'ROLLBACK', value: 'ROLLBACK' },
    { label: 'WORKAROUND_AVAILABLE', value: 'WORKAROUND_AVAILABLE' },
]
const severityOptions = [
    { label: 'CRITICAL', value: 'CRITICAL' },
    { label: 'HIGH', value: 'HIGH' },
    { label: 'MEDIUM', value: 'MEDIUM' },
    { label: 'LOW', value: 'LOW' },
    { label: 'INFO', value: 'INFO' },
    { label: 'UNASSIGNED', value: 'UNASSIGNED' },
]

const otherAnalyses = computed(() => {
    if (!proposal.value) return []
    return allAnalyses.value.filter((a: any) =>
        a.scope !== proposal.value.scope || a.scopeUuid !== proposal.value.scopeUuid)
})

const sameScopeAnalyses = computed(() => {
    if (!proposal.value) return []
    return allAnalyses.value.filter((a: any) =>
        a.scope === proposal.value.scope && a.scopeUuid === proposal.value.scopeUuid)
})

const otherAnalysisColumns = [
    { title: 'Scope', key: 'scope' },
    { title: 'Scope UUID', key: 'scopeUuid' },
    { title: 'State', key: 'analysisState' },
    { title: 'Justification', key: 'analysisJustification', render: (r: any) => r.analysisJustification ?? '—' },
]

async function fetchProposal () {
    loading.value = true
    try {
        const r = await graphqlClient.query({
            query: GET_VEX_PROPOSAL,
            variables: { uuid: uuid.value },
            fetchPolicy: 'network-only'
        })
        proposal.value = r.data?.getVexStatementProposal ?? null
        if (proposal.value) {
            await fetchExistingAnalyses()
            await fetchAttestation()
        }
    } finally {
        loading.value = false
    }
}

async function fetchExistingAnalyses () {
    if (!proposal.value) return
    try {
        const r = await graphqlClient.query({
            query: GET_VULN_ANALYSIS_BY_LOCATION_AND_FINDING,
            variables: {
                org: proposal.value.org,
                location: proposal.value.location,
                findingId: proposal.value.findingId,
                findingType: proposal.value.findingType,
            },
            fetchPolicy: 'network-only'
        })
        allAnalyses.value = r.data?.getVulnAnalysisByLocationAndFinding ?? []
    } catch {
        allAnalyses.value = []
    }
}

async function fetchAttestation () {
    if (!proposal.value?.mitigationAttestation) {
        attestation.value = null
        return
    }
    try {
        const r = await graphqlClient.query({
            query: GET_MITIGATION_ATTESTATION,
            variables: { uuid: proposal.value.mitigationAttestation },
            fetchPolicy: 'network-only'
        })
        attestation.value = r.data?.getMitigationAttestation ?? null
    } catch {
        attestation.value = null
    }
}

onMounted(fetchProposal)

function formatJson (s: string): string {
    try { return JSON.stringify(JSON.parse(s), null, 2) } catch { return s }
}

function formatTime (s: string | null | undefined): string {
    if (!s) return '—'
    try { return new Date(s).toLocaleString() } catch { return s }
}

function startEditing () {
    edits.value = {
        analysisState: proposal.value.analysisState,
        analysisJustification: proposal.value.analysisJustification,
        details: proposal.value.details,
        severity: proposal.value.severity,
        responses: proposal.value.responses ? [...proposal.value.responses] : [],
        recommendation: proposal.value.recommendation,
        workaround: proposal.value.workaround,
    }
    editing.value = true
}

function cancelEditing () {
    editing.value = false
}

async function onSaveEdits () {
    busy.value = true
    try {
        await graphqlClient.mutate({
            mutation: UPDATE_VEX_PROPOSAL,
            variables: { uuid: uuid.value, updates: edits.value }
        })
        message.success('Proposal updated')
        editing.value = false
        await fetchProposal()
    } catch (e: any) {
        message.error(e.message ?? 'Update failed')
    } finally { busy.value = false }
}

async function onAccept () {
    busy.value = true
    try {
        await graphqlClient.mutate({
            mutation: ACCEPT_VEX_PROPOSAL,
            variables: { uuid: uuid.value, comment: actionComment.value.trim() || null }
        })
        message.success('Proposal accepted')
        actionComment.value = ''
        await fetchProposal()
    } catch (e: any) {
        message.error(e.message ?? 'Accept failed')
    } finally { busy.value = false }
}

async function onReject () {
    if (!actionComment.value.trim()) {
        message.warning('Please add a comment explaining why you\'re rejecting this proposal')
        return
    }
    busy.value = true
    try {
        await graphqlClient.mutate({
            mutation: REJECT_VEX_PROPOSAL,
            variables: { uuid: uuid.value, reason: actionComment.value.trim() }
        })
        message.success('Proposal rejected')
        actionComment.value = ''
        await fetchProposal()
    } catch (e: any) {
        message.error(e.message ?? 'Reject failed')
    } finally { busy.value = false }
}
</script>
