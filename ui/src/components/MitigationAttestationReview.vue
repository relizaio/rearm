<template>
    <n-spin :show="loading">
        <n-card v-if="att" :title="`Mitigation Attestation — ${att.claimType}`">

            <n-alert v-if="linkedAnalysis" type="info" style="margin-bottom: 12px;">
                <template #header>Finding-analysis row exists at this scope</template>
                <div style="margin-bottom: 6px;">
                    A <strong>{{ linkedAnalysis.analysisState }}</strong> analysis row exists for
                    <code v-if="linkedProposal">{{ linkedProposal.findingId }}</code>
                    <span v-if="linkedProposal"> on <code>{{ linkedProposal.location }}</code></span>
                    at <strong>{{ att.scope }}</strong> scope.
                </div>
                <n-button text type="primary" @click="goToFindingAnalysis">Open in Finding Analysis →</n-button>
            </n-alert>

            <n-descriptions :column="1" bordered label-placement="left" :label-style="{ width: '160px', minWidth: '160px' }">
                <n-descriptions-item label="Claim type">{{ att.claimType }}</n-descriptions-item>
                <n-descriptions-item label="Claim">{{ att.claimText }}</n-descriptions-item>
                <n-descriptions-item label="Scope">{{ att.scope }} ({{ att.scopeUuid }})</n-descriptions-item>
                <n-descriptions-item label="Originating proposal">
                    <n-button text type="primary" @click="goToProposal">{{ att.proposal }} →</n-button>
                </n-descriptions-item>
                <n-descriptions-item label="Assignee">{{ att.assignedTo ?? 'unassigned' }}</n-descriptions-item>
                <n-descriptions-item label="Assigned at">{{ formatTime(att.assignedAt) }}</n-descriptions-item>
                <n-descriptions-item label="Deadline">{{ formatTime(att.deadline) }}</n-descriptions-item>
                <n-descriptions-item label="Status">{{ att.status }}</n-descriptions-item>
                <n-descriptions-item v-if="att.attestedAt" :label="att.status === 'WAIVED' ? 'Waived at' : 'Attested at'">
                    {{ formatTime(att.attestedAt) }}
                </n-descriptions-item>
                <n-descriptions-item v-if="att.attestedBy" :label="att.status === 'WAIVED' ? 'Waived by' : 'Attested by'">
                    {{ formatUser(att.attestedBy) }}
                </n-descriptions-item>
                <n-descriptions-item v-if="att.evidence" label="Evidence">{{ att.evidence }}</n-descriptions-item>
                <n-descriptions-item v-if="att.statusReason" label="Reason">{{ att.statusReason }}</n-descriptions-item>
            </n-descriptions>

            <n-divider />
            <n-space v-if="att.status === 'PENDING'" vertical :size="8">
                <n-input
                    v-model:value="evidence"
                    type="textarea"
                    :autosize="{ minRows: 2, maxRows: 4 }"
                    placeholder="Evidence — describe how the mitigation has been verified in this scope (required for Attest)"
                />
                <n-input
                    v-model:value="waiveReason"
                    type="textarea"
                    :autosize="{ minRows: 2, maxRows: 4 }"
                    placeholder="Waive reason — required to permanently abandon the deferred finding-analysis write"
                />
                <n-space>
                    <n-button type="primary" :loading="busy" :disabled="!evidence.trim()" @click="onAttest">Attest</n-button>
                    <n-button type="error" :loading="busy" :disabled="!waiveReason.trim()" @click="onWaive">Waive</n-button>
                    <n-text v-if="!evidence.trim() && !waiveReason.trim()" depth="3" style="font-size: 12px; align-self: center;">
                        (Attest needs evidence; Waive needs a reason)
                    </n-text>
                </n-space>
            </n-space>
            <n-alert v-else type="info">
                Attestation is <strong>{{ att.status }}</strong>.
            </n-alert>
        </n-card>
    </n-spin>
</template>

<script lang="ts">
export default {
    name: 'MitigationAttestationReview'
}
</script>
<script lang="ts" setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
    NAlert, NButton, NCard, NDescriptions, NDescriptionsItem, NDivider,
    NInput, NSpace, NSpin, NText, useMessage
} from 'naive-ui'
import graphqlClient from '@/utils/graphql'
import { useOrgUsersIndex } from '@/utils/userLookup'
import {
    ATTEST_MITIGATION, GET_MITIGATION_ATTESTATION, GET_VEX_PROPOSAL,
    GET_VULN_ANALYSIS_BY_LOCATION_AND_FINDING, WAIVE_MITIGATION
} from '@/graphql/vexImport'

const route = useRoute()
const router = useRouter()
const message = useMessage()
const uuid = computed(() => route.params.uuid as string)
const orgUuid = computed(() => route.params.orguuid as string)
const { format: formatUser } = useOrgUsersIndex(orgUuid)

function goToProposal () {
    if (!att.value?.proposal) return
    router.push({
        name: 'VexProposalReview',
        params: { orguuid: orgUuid.value, uuid: att.value.proposal }
    })
}

function goToFindingAnalysis () {
    if (!linkedProposal.value) return
    router.push({
        name: 'VulnerabilityAnalysis',
        params: { orguuid: orgUuid.value },
        query: { cveId: linkedProposal.value.findingId }
    })
}

function formatTime (s: string | null | undefined): string {
    if (!s) return '—'
    try { return new Date(s).toLocaleString() } catch { return s }
}

const evidence = ref('')
const waiveReason = ref('')
const busy = ref(false)
const loading = ref(false)
const att = ref<any | null>(null)
const linkedProposal = ref<any | null>(null)
const linkedAnalysis = ref<any | null>(null)

async function fetchAttestation () {
    loading.value = true
    try {
        const r = await graphqlClient.query({
            query: GET_MITIGATION_ATTESTATION,
            variables: { uuid: uuid.value },
            fetchPolicy: 'network-only'
        })
        att.value = r.data?.getMitigationAttestation ?? null
        if (att.value?.proposal) {
            await fetchProposalContext()
        }
    } finally {
        loading.value = false
    }
}

async function fetchProposalContext () {
    if (!att.value?.proposal) return
    try {
        const r = await graphqlClient.query({
            query: GET_VEX_PROPOSAL,
            variables: { uuid: att.value.proposal },
            fetchPolicy: 'network-only'
        })
        linkedProposal.value = r.data?.getVexStatementProposal ?? null
        if (linkedProposal.value) {
            const a = await graphqlClient.query({
                query: GET_VULN_ANALYSIS_BY_LOCATION_AND_FINDING,
                variables: {
                    org: linkedProposal.value.org,
                    location: linkedProposal.value.location,
                    findingId: linkedProposal.value.findingId,
                    findingType: linkedProposal.value.findingType,
                },
                fetchPolicy: 'network-only'
            })
            const rows = a.data?.getVulnAnalysisByLocationAndFinding ?? []
            // Pick the analysis at the attestation's exact scope, if any.
            linkedAnalysis.value = rows.find((x: any) =>
                x.scope === att.value.scope && x.scopeUuid === att.value.scopeUuid) ?? null
        }
    } catch {
        linkedProposal.value = null
        linkedAnalysis.value = null
    }
}

onMounted(fetchAttestation)

async function onAttest () {
    if (!evidence.value.trim()) {
        message.warning('Please add evidence describing how the mitigation has been verified')
        return
    }
    busy.value = true
    try {
        await graphqlClient.mutate({ mutation: ATTEST_MITIGATION, variables: { uuid: uuid.value, evidence: evidence.value.trim() } })
        message.success('Attestation recorded')
        evidence.value = ''
        await fetchAttestation()
    } catch (e: any) {
        message.error(e.message ?? 'Attest failed')
    } finally { busy.value = false }
}

async function onWaive () {
    if (!waiveReason.value.trim()) {
        message.warning('Please add a reason explaining why you\'re waiving this attestation')
        return
    }
    busy.value = true
    try {
        await graphqlClient.mutate({ mutation: WAIVE_MITIGATION, variables: { uuid: uuid.value, reason: waiveReason.value.trim() } })
        message.success('Attestation waived')
        waiveReason.value = ''
        await fetchAttestation()
    } catch (e: any) {
        message.error(e.message ?? 'Waive failed')
    } finally { busy.value = false }
}
</script>
