<template>
    <div class="downloadLogView">
        <h4>Download Log for {{ myorg.name }}</h4>
        <n-space vertical>
            <n-space align="flex-end" wrap>
                <n-space vertical :size="4">
                    <span style="font-size: 12px; color: #666;">From Date</span>
                    <n-date-picker
                        v-model:value="fromDateValue"
                        type="date"
                        placeholder="From Date"
                        clearable
                        style="width: 200px;"
                    />
                </n-space>
                <n-space vertical :size="4">
                    <span style="font-size: 12px; color: #666;">To Date</span>
                    <n-date-picker
                        v-model:value="toDateValue"
                        type="date"
                        placeholder="To Date"
                        clearable
                        style="width: 200px;"
                    />
                </n-space>
                <n-space vertical :size="4" v-if="currentPerspectiveUuid">
                    <span style="font-size: 12px; color: #666;">Perspective</span>
                    <n-tag type="info">{{ currentPerspectiveName }}</n-tag>
                </n-space>
                <n-button type="primary" :loading="loading" @click="fetchLogs">
                    Fetch Logs
                </n-button>
            </n-space>
            <n-spin :show="loading">
                <n-data-table
                    :columns="columns"
                    :data="logs"
                    :bordered="true"
                    :striped="true"
                    :pagination="pagination"
                    :row-key="(row: any) => row.uuid"
                    :scroll-x="1275"
                    size="small"
                    style="margin-top: 12px;"
                />
            </n-spin>
            <n-text v-if="!loading && logs.length === 0" depth="3">
                No download log entries found for the selected date range.
            </n-text>
        </n-space>
    </div>
</template>

<script lang="ts">
export default {
    name: 'DownloadLogView'
}
</script>

<script lang="ts" setup>
import { ref, computed, onMounted, h, ComputedRef, resolveComponent } from 'vue'
import { NSpace, NButton, NDataTable, NSpin, NDatePicker, NTag, NText, NTooltip, useNotification } from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import gql from 'graphql-tag'
import { useStore } from 'vuex'
import graphqlClient from '@/utils/graphql'

const store = useStore()
const notification = useNotification()

const myorg: ComputedRef<any> = computed((): any => store.getters.myorg)
const myperspective: ComputedRef<string> = computed((): string => store.getters.myperspective)
const perspectives: ComputedRef<any[]> = computed((): any => store.getters.perspectivesOfOrg(myorg.value?.uuid || ''))

const currentPerspectiveUuid = computed(() =>
    myperspective.value !== 'default' ? myperspective.value : undefined
)
const currentPerspectiveName = computed(() => {
    if (myperspective.value === 'default') return undefined
    const p = perspectives.value.find((p: any) => p.uuid === myperspective.value)
    return p ? p.name : undefined
})

const loading = ref(false)
const logs = ref<any[]>([])

const now = new Date()
const thirtyDaysAgo = new Date(now.getTime() - 30 * 24 * 60 * 60 * 1000)
const fromDateValue = ref<number | null>(thirtyDaysAgo.getTime())
const toDateValue = ref<number | null>(now.getTime())

const pagination = {
    pageSize: 50
}

function formatDownloadType(type: string): string {
    switch (type) {
        case 'ARTIFACT_DOWNLOAD': return 'Artifact Download'
        case 'RAW_ARTIFACT_DOWNLOAD': return 'Raw Artifact Download'
        case 'VDR_EXPORT': return 'VDR Export'
        case 'SBOM_EXPORT': return 'SBOM Export'
        default: return type
    }
}

function formatSubjectType(type: string): string {
    switch (type) {
        case 'ARTIFACT': return 'Artifact'
        case 'RELEASE': return 'Release'
        default: return type
    }
}

function formatCreatedType(type: string): string {
    switch (type) {
        case 'PROGRAMMATIC': return 'API Key'
        case 'MANUAL': return 'User'
        default: return type ?? '—'
    }
}

function formatDate(dateStr: string): string {
    if (!dateStr) return '—'
    const d = new Date(dateStr)
    return d.toLocaleDateString('en-CA') + ' ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

function truncateUuid(uuid: string): string {
    if (!uuid) return '—'
    return uuid.length > 12 ? uuid.substring(0, 8) + '…' : uuid
}

function renderUuidCell(uuid: string) {
    if (!uuid) return h('span', '—')
    return h(
        NTooltip,
        { placement: 'top' },
        {
            trigger: () => h('span', { style: 'font-family: monospace; font-size: 11px; cursor: default;' }, truncateUuid(uuid)),
            default: () => h('span', { style: 'font-family: monospace; font-size: 11px;' }, uuid)
        }
    )
}

function renderNameWithUuidCell(name: string | undefined, uuid: string) {
    if (!uuid) return h('span', '\u2014')
    if (!name) return renderUuidCell(uuid)
    return h(
        NTooltip,
        { placement: 'top' },
        {
            trigger: () => h('span', { style: 'cursor: default;' }, name),
            default: () => h('span', { style: 'font-family: monospace; font-size: 11px;' }, uuid)
        }
    )
}

function renderSubjectCell(row: any) {
    if (row.subjectType === 'RELEASE' && row.subjectUuid) {
        const RouterLink = resolveComponent('RouterLink')
        const label = row.subjectName ?? row.subjectUuid
        return h(
            NTooltip,
            { placement: 'top' },
            {
                trigger: () => h(RouterLink as any, { to: `/release/show/${row.subjectUuid}` }, () => label),
                default: () => h('span', { style: 'font-family: monospace; font-size: 11px;' }, row.subjectUuid)
            }
        )
    }
    return renderNameWithUuidCell(row.subjectName, row.subjectUuid)
}

function buildConfigSummary(row: any): string {
    const cfg = row.downloadConfig
    if (!cfg) return '—'
    const parts: string[] = []
    switch (row.downloadType) {
        case 'SBOM_EXPORT':
            if (cfg.structure) parts.push(cfg.structure)
            if (cfg.mediaType) parts.push(cfg.mediaType.replace(/_/g, '/'))
            if (cfg.belongsTo) parts.push(cfg.belongsTo)
            if (cfg.tldOnly) parts.push('top-level only')
            if (cfg.ignoreDev) parts.push('no-dev')
            if (cfg.excludeCoverageTypes?.length) parts.push(`excl: ${cfg.excludeCoverageTypes.join(',')}`)
            break
        case 'VDR_EXPORT':
            parts.push(cfg.includeSuppressed ? 'incl. suppressed' : 'excl. suppressed')
            if (cfg.targetLifecycle) parts.push(`lifecycle: ${cfg.targetLifecycle}`)
            if (cfg.targetApproval) parts.push(`approval: ${cfg.targetApproval}`)
            if (cfg.upToDate) parts.push(`until: ${new Date(cfg.upToDate).toLocaleDateString('en-CA')}`)
            break
        case 'ARTIFACT_DOWNLOAD':
        case 'RAW_ARTIFACT_DOWNLOAD':
            if (cfg.artifactVersion != null) parts.push(`v${cfg.artifactVersion}`)
            break
    }
    return parts.length > 0 ? parts.join(' · ') : '—'
}

const columns: DataTableColumns = [
    {
        title: 'Date / Time',
        key: 'createdDate',
        width: 155,
        render: (row: any) => h('span', { style: 'white-space: nowrap;' }, formatDate(row.createdDate))
    },
    {
        title: 'Download Type',
        key: 'downloadType',
        width: 230,
        render: (row: any) => h('span', { style: 'white-space: nowrap;' }, formatDownloadType(row.downloadType))
    },
    {
        title: 'Type',
        key: 'subjectType',
        width: 80,
        render: (row: any) => h('span', { style: 'white-space: nowrap;' }, formatSubjectType(row.subjectType))
    },
    {
        title: 'Subject',
        key: 'subjectUuid',
        width: 260,
        render: (row: any) => renderSubjectCell(row)
    },
    {
        title: 'Config Details',
        key: 'configDetails',
        width: 220,
        render: (row: any) => h('span', { style: 'font-size: 12px; color: #555;' }, buildConfigSummary(row))
    },
    {
        title: 'Via',
        key: 'createdType',
        width: 75,
        render: (row: any) => h('span', { style: 'white-space: nowrap;' }, formatCreatedType(row.createdType))
    },
    {
        title: 'Downloaded By',
        key: 'downloadedBy',
        width: 150,
        render: (row: any) => renderNameWithUuidCell(row.downloadedByName, row.downloadedBy)
    },
    {
        title: 'IP Address',
        key: 'ipAddress',
        width: 130,
        render: (row: any) => h('span', { style: 'white-space: nowrap;' }, row.ipAddress ?? '—')
    }
]

async function fetchLogs() {
    if (!myorg.value?.uuid) return
    loading.value = true
    try {
        const fromDate = fromDateValue.value
            ? new Date(fromDateValue.value).toISOString()
            : undefined
        const toDate = toDateValue.value
            ? new Date(new Date(toDateValue.value).setHours(23, 59, 59, 999)).toISOString()
            : undefined

        const response = await graphqlClient.query({
            query: gql`
                query listDownloadLogs($org: ID!, $fromDate: DateTime, $toDate: DateTime, $perspective: ID) {
                    listDownloadLogs(org: $org, fromDate: $fromDate, toDate: $toDate, perspective: $perspective) {
                        uuid
                        downloadType
                        subjectType
                        subjectUuid
                        subjectName
                        downloadedBy
                        downloadedByName
                        createdType
                        ipAddress
                        createdDate
                        downloadConfig {
                            artifactVersion
                            structure
                            mediaType
                            belongsTo
                            excludeCoverageTypes
                            tldOnly
                            ignoreDev
                            includeSuppressed
                            targetLifecycle
                            upToDate
                            targetApproval
                        }
                    }
                }
            `,
            variables: {
                org: myorg.value.uuid,
                fromDate: fromDate ?? null,
                toDate: toDate ?? null,
                perspective: currentPerspectiveUuid.value ?? null
            },
            fetchPolicy: 'no-cache'
        })
        logs.value = (response.data as any).listDownloadLogs ?? []
    } catch (error: any) {
        console.error('Error fetching download logs:', error)
        notification.error({
            title: 'Error',
            content: `Failed to fetch download logs: ${error?.message ?? 'Unknown error'}`,
            duration: 5000,
            keepAliveOnHover: true
        })
    } finally {
        loading.value = false
    }
}

onMounted(async () => {
    await fetchLogs()
})
</script>

<style scoped>
.downloadLogView {
    padding: 20px;
}
</style>
