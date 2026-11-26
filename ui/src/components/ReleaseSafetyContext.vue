<template>
    <n-space vertical>
        <h3>Safety integrity levels</h3>
        <n-alert type="info" :show-icon="false" data-testid="safety-context-not-verified">
            {{ headerText(productView) }}
        </n-alert>
        <n-card v-if="productView" key="summary" size="small" :title="SUMMARY_TITLE" data-testid="safety-context-summary">
            <div v-if="!summary.length" data-testid="safety-summary-empty">{{ emptySummaryText(complete) }}</div>
            <div v-for="s in summary" :key="s.schemeKey" data-testid="safety-summary-line">{{ summaryLine(s) }}</div>
        </n-card>
        <n-space v-if="productView && viewBanners.length" key="banners" vertical data-testid="safety-context-banners">
            <n-alert v-for="b in viewBanners" :key="b.reason" type="warning" :data-testid="`safety-banner-${b.reason}`">
                {{ b.text }}
            </n-alert>
        </n-space>
        <n-checkbox v-model:checked="includeSuperseded">Show superseded levels</n-checkbox>
        <n-data-table
            :columns="recordColumns"
            :data="records"
            :loading="loading"
            :row-key="rowKey"
            :pagination="{ pageSize: 25 }"
            data-testid="safety-context-records"
        />
        <div v-if="showing" key="showing" data-testid="safety-context-showing">{{ showing }}</div>
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
import { computed, h, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { NAlert, NButton, NCard, NCheckbox, NDataTable, NIcon, NSpace, NTag, NTooltip } from 'naive-ui'
import { Code } from '@vicons/tabler'
import Swal from 'sweetalert2'
import graphqlClient from '@/utils/graphql'
import commonFunctions from '@/utils/commonFunctions'
import { GET_SAFETY_RELEASE_VIEW } from '@/graphql/safetyContext'
import {
    REDACTED_SOURCE_TEXT, SUMMARY_TITLE, acceptedDetail, acceptedWho, banners, distinctActiveLevels, emptySummaryText,
    headerText, issuerOverrideTooltip, releaseCellText, rowKey, showingText, summaryLine
} from '@/utils/safetyContextView'
import SafetyContextProposals from './SafetyContextProposals.vue'
import SafetyContextSourceModal from './SafetyContextSourceModal.vue'

const props = defineProps<{ orgUuid: string, releaseUuid: string }>()

const view = ref<any>(null)
const records = computed<any[]>(() => view.value?.rows ?? [])
const productView = computed(() => view.value?.productView === true)
const summary = computed<any[]>(() => view.value?.summary ?? [])
const complete = computed(() => view.value?.complete !== false)
const viewBanners = computed(() => banners(view.value))
const showing = computed(() => showingText(records.value.length, view.value?.totalRows))
const loading = ref(false)
const includeSuperseded = ref(false)
const sourceOpen = ref(false)
const sourceUuid = ref<string | null>(null)

// Only the latest request may write the list (the superseded toggle can race).
let requestSeq = 0

async function fetchRecords () {
    if (!props.orgUuid || !props.releaseUuid) return
    const seq = ++requestSeq
    loading.value = true
    try {
        const r = await graphqlClient.query({
            query: GET_SAFETY_RELEASE_VIEW,
            variables: { org: props.orgUuid, release: props.releaseUuid, includeSuperseded: includeSuperseded.value, limit: 500 },
            fetchPolicy: 'network-only'
        })
        if (seq === requestSeq) view.value = (r.data as any)?.getSafetyContextReleaseView ?? null
    } catch (err: any) {
        Swal.fire('Error!', commonFunctions.parseGraphQLError(err.message), 'error')
    } finally {
        if (seq === requestSeq) loading.value = false
    }
}

onMounted(fetchRecords)
watch([includeSuperseded, () => props.releaseUuid], fetchRecords)

const SCOPE_LABELS: Record<string, string> = {
    RELEASE: 'This release', BRANCH: 'Branch', COMPONENT: 'Component', ORG: 'Organization'
}
function scopeLabel (scope: string): string {
    return SCOPE_LABELS[scope] || scope
}

function purlVersion (purl: string): string {
    const at = (purl || '').indexOf('@')
    if (at < 0) return purl
    return purl.slice(at + 1).split(/[?#]/)[0]
}

const releaseColumn = {
    title: 'Release',
    key: 'appliesToRelease',
    ellipsis: { tooltip: true },
    render: (r: any) => {
        const text = releaseCellText(r, props.releaseUuid)
        if (!r.appliesToRelease || r.appliesToRelease === props.releaseUuid) return text
        const parts: any[] = [h(RouterLink, { to: `/release/show/${r.appliesToRelease}` }, () => text)]
        if (r.appliesToReleaseArchived) {
            parts.push(' ', h(NTag, { size: 'small', round: true, 'data-testid': 'safety-release-archived' }, () => 'archived'))
        }
        return h('span', {}, parts)
    }
}

const baseColumns = [
    { title: 'Component', key: 'componentPurl', ellipsis: { tooltip: true } },
    { title: 'Asset', key: 'assetRefId', ellipsis: { tooltip: true } },
    { title: 'Scheme', key: 'schemeKey', ellipsis: { tooltip: true } },
    {
        title: 'Applies via',
        key: 'scope',
        render: (r: any) => {
            const where = scopeLabel(r.scope)
            const fromOther = r.releaseUuid && r.releaseUuid !== props.releaseUuid
            const parts: any[] = [where]
            if (r.sourceRedacted) {
                parts.push(h('br'), h('span', { 'data-testid': 'safety-source-redacted' }, REDACTED_SOURCE_TEXT))
            } else if (fromOther) {
                parts.push(h('br'), h(RouterLink, { to: `/release/show/${r.releaseUuid}` },
                    () => `uploaded on ${r.sourceReleaseVersion || 'another release'}`))
            }
            if (r.overridesScope) {
                parts.push(h('br'), h('span', { 'data-testid': 'safety-overrides' },
                    `overrides ${scopeLabel(r.overridesScope)}: ${r.overridesLevel}`))
            }
            return parts.length > 1 ? h('span', {}, parts) : where
        }
    },
    {
        title: 'Level',
        key: 'levelRaw',
        render: (r: any) => {
            const label = h('strong', {}, r.levelNormalized || r.levelRaw)
            // Marked only where several active levels exist for the component and scheme.
            const stringent = r.componentMostConservative && distinctActiveLevels(records.value, r) > 1
            if (!stringent && !r.carried) return label
            const parts: any[] = [label]
            if (stringent) parts.push(' ', h(NTag, { size: 'small', type: 'warning', round: true }, () => 'most stringent'))
            if (r.carried) {
                parts.push(' ', h(NTooltip, { trigger: 'hover' }, {
                    trigger: () => h(NTag, { size: 'small', round: true, type: 'info', 'data-testid': 'safety-carried' },
                        () => `carried from ${purlVersion(r.componentPurl)}`),
                    default: () => `declared for ${r.componentPurl}; no level declared yet for ${r.shownForPurl}`
                }))
            }
            return h('span', {}, parts)
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
            const text = `${acceptedWho(r)}${r.acceptedAt ? ', ' + new Date(r.acceptedAt).toLocaleString() : ''}`
            const detail = acceptedDetail(r)
            return detail ? h(NTooltip, { trigger: 'hover' }, { trigger: () => h('span', {}, text), default: () => detail }) : text
        }
    },
    {
        title: 'Issuer',
        key: 'issuerClassEffective',
        render: (r: any) => r.elevatedByOverride
            ? h(NTooltip, { trigger: 'hover' }, {
                trigger: () => h('span', {}, `${r.issuerClassEffective} *`),
                default: () => issuerOverrideTooltip(r)
            })
            : r.issuerClassEffective
    },
    {
        title: 'Source',
        key: 'sourceArtifact',
        render: (r: any) => h('span', {}, [
            r.sourceDeleted && !r.sourceRedacted ? h(NTag, { size: 'small', round: true, style: 'margin-right: 4px;' }, () => 'source archived') : null,
            h(NButton, {
                size: 'small', title: 'View the imported asset',
                onClick: () => { sourceUuid.value = r.uuid; sourceOpen.value = true }
            }, { default: () => h(NIcon, null, { default: () => h(Code) }) })
        ])
    }
]

const recordColumns = computed(() => productView.value ? [releaseColumn, ...baseColumns] : baseColumns)
</script>
