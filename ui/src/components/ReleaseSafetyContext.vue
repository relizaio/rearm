<template>
    <n-space vertical>
        <h3>Safety integrity levels</h3>
        <n-alert type="info" :show-icon="false" data-testid="safety-context-not-verified">
            {{ DECLARED_NOT_VERIFIED }} Levels come from SAFETY_CONTEXT (CycloneDX 2.0) artifacts uploaded
            to this release. The highest level per component and scheme is marked.
        </n-alert>
        <n-checkbox v-model:checked="includeSuperseded">Show superseded levels</n-checkbox>
        <n-data-table
            :columns="recordColumns"
            :data="records"
            :loading="loading"
            :row-key="(row: any) => row.uuid"
            :pagination="{ pageSize: 25 }"
            data-testid="safety-context-records"
        />
        <h3>Proposals for this release</h3>
        <safety-context-proposals :org-uuid="orgUuid" :release-uuid="releaseUuid" @changed="fetchRecords" />
        <safety-context-source-modal v-model:show="sourceOpen" :uuid="sourceUuid" />
    </n-space>
</template>

<script lang="ts">
export default {
    name: 'ReleaseSafetyContext'
}
</script>
<script lang="ts" setup>
import { h, onMounted, ref, watch } from 'vue'
import { NAlert, NButton, NCheckbox, NDataTable, NIcon, NSpace, NTag, NTooltip } from 'naive-ui'
import { Code } from '@vicons/tabler'
import Swal from 'sweetalert2'
import graphqlClient from '@/utils/graphql'
import commonFunctions from '@/utils/commonFunctions'
import { DECLARED_NOT_VERIFIED, GATE_RULE_LABELS, GET_SAFETY_RECORDS_BY_RELEASE } from '@/graphql/safetyContext'
import SafetyContextProposals from './SafetyContextProposals.vue'
import SafetyContextSourceModal from './SafetyContextSourceModal.vue'

const props = defineProps<{ orgUuid: string, releaseUuid: string }>()

const records = ref<any[]>([])
const loading = ref(false)
const includeSuperseded = ref(false)
const sourceOpen = ref(false)
const sourceUuid = ref<string | null>(null)

async function fetchRecords () {
    if (!props.orgUuid || !props.releaseUuid) return
    loading.value = true
    try {
        const r = await graphqlClient.query({
            query: GET_SAFETY_RECORDS_BY_RELEASE,
            variables: { org: props.orgUuid, release: props.releaseUuid, includeSuperseded: includeSuperseded.value, limit: 500 },
            fetchPolicy: 'network-only'
        })
        records.value = r.data?.getSafetyContextRecordsByRelease ?? []
    } catch (err: any) {
        Swal.fire('Error!', commonFunctions.parseGraphQLError(err.message), 'error')
    } finally {
        loading.value = false
    }
}

onMounted(fetchRecords)
watch([includeSuperseded, () => props.releaseUuid], fetchRecords)

const recordColumns = [
    { title: 'Component', key: 'componentPurl', ellipsis: { tooltip: true } },
    { title: 'Scheme', key: 'schemeKey' },
    {
        title: 'Level',
        key: 'levelRaw',
        render: (r: any) => {
            const label = h('strong', {}, r.levelNormalized || r.levelRaw)
            return r.componentMostConservative
                ? h('span', {}, [label, ' ', h(NTag, { size: 'small', type: 'warning', round: true }, () => 'highest')])
                : label
        }
    },
    {
        title: 'Status',
        key: 'recordStatus',
        render: (r: any) => h(NTag, { size: 'small', round: true, type: r.recordStatus === 'ACTIVE' ? 'success' : 'default' },
            () => r.recordStatus)
    },
    {
        title: 'Accepted',
        key: 'acceptedVia',
        render: (r: any) => {
            const who = r.acceptedVia === 'MANUAL' ? (r.acceptedByName || 'reviewer') : 'auto'
            const text = `${who}${r.acceptedAt ? ', ' + new Date(r.acceptedAt).toLocaleString() : ''}`
            const detail = [GATE_RULE_LABELS[r.gateRule] || r.gateRule, r.acceptComment].filter(Boolean).join(' - ')
            return detail ? h(NTooltip, { trigger: 'hover' }, { trigger: () => h('span', {}, text), default: () => detail }) : text
        }
    },
    {
        title: 'Issuer',
        key: 'issuerClassEffective',
        render: (r: any) => r.elevatedByOverride
            ? h(NTooltip, { trigger: 'hover' }, {
                trigger: () => h('span', {}, `${r.issuerClassEffective} *`),
                default: () => `Raised from ${r.issuerClassDerived} by ${r.overrideActorName || 'an override'}`
            })
            : r.issuerClassEffective
    },
    {
        title: 'Source',
        key: 'sourceArtifact',
        render: (r: any) => h('span', {}, [
            r.sourceDeleted ? h(NTag, { size: 'small', round: true, style: 'margin-right: 4px;' }, () => 'source archived') : null,
            h(NButton, {
                size: 'small', title: 'View the imported asset',
                onClick: () => { sourceUuid.value = r.uuid; sourceOpen.value = true }
            }, { default: () => h(NIcon, null, { default: () => h(Code) }) })
        ])
    }
]
</script>
