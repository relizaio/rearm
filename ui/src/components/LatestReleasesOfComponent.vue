<template>
    <div class="latestReleases">
        <div class="latestReleasesHeader">
            <span class="latestReleasesTitle">Most recent releases across {{ props.featureSetLabel.toLowerCase() }}es</span>
            <n-input-number v-model:value="limit" size="small" :min="1" :max="200" :step="5" style="width: 110px;" data-testid="latest-limit" @update:value="onLimitChange" />
            <n-icon class="clickable" size="18" title="Refresh" @click="fetchLatest"><Refresh /></n-icon>
        </div>
        <n-data-table
            data-testid="latest-releases-table"
            :data="rows"
            :columns="columns"
            :row-props="rowProps"
            :row-class-name="rowClassName"
            :row-key="(row: any) => row.uuid"
            :loading="loading"
            :pagination="rows.length > 50 ? { pageSize: 50 } : false" />
        <div v-if="!loading && !rows.length && !loadError" class="latestReleasesEmpty">No releases yet.</div>
        <div v-if="loadError" class="latestReleasesEmpty">{{ loadError }}</div>
        <vulnerability-modal
            v-model:show="showVulnModal"
            :component-name="vulnModalRelease?.componentDetails?.name || ''"
            :version="vulnModalRelease?.version || ''"
            :data="vulnModalData"
            :loading="vulnModalLoading"
            :artifacts="vulnModalArtifacts"
            :org-uuid="vulnModalOrgUuid"
            :dtrack-project-uuids="vulnModalDtrackProjectUuids"
            :release-uuid="vulnModalRelease?.uuid || ''"
            :branch-uuid="vulnModalRelease?.branchDetails?.uuid || ''"
            :branch-name="vulnModalRelease?.branchDetails?.name || ''"
            :component-uuid="vulnModalRelease?.componentDetails?.uuid || ''"
            :component-type="vulnModalRelease?.componentDetails?.type || ''"
            :artifact-view-only="false"
            :initial-severity-filter="vulnModalSeverity"
            :initial-type-filter="vulnModalType"
        />
    </div>
</template>

<script lang="ts">
export default {
    name: 'LatestReleasesOfComponent'
}
</script>
<script lang="ts" setup>
// The N most recent releases of a component / product across all its
// branches, newest first -- the home page's Most Recent Releases widget
// scoped to one component. N is the user's choice, remembered in the
// browser. The branch cell opens that branch in detail mode; the version
// cell (or the row) opens the release. Scan status and vulnerability
// circles mirror the home widget.
import { ref, Ref, h, watch, onMounted } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { NDataTable, NIcon, NInputNumber, NTag, NTooltip, NSpace, useNotification, DataTableColumns } from 'naive-ui'
import { Refresh, Star, CalendarTime } from '@vicons/tabler'
import graphqlClient from '@/utils/graphql'
import GqlQueries from '@/utils/graphqlQueries'
import constants from '@/utils/constants'
import { ReleaseVulnerabilityService } from '@/utils/releaseVulnerabilityService'
import { isDtrackConfiguredForOrg, getReleaseScanStatus } from '@/utils/releaseScanStatus'
import VulnerabilityModal from './VulnerabilityModal.vue'

const props = withDefaults(defineProps<{
    componentUuid: string
    orgUuid: string
    selectedBranchUuid?: string
    featureSetLabel?: string
    /** Bump to force a refetch from the parent (e.g. after a branch was archived). */
    refreshToken?: number
}>(), {
    selectedBranchUuid: '',
    featureSetLabel: 'Branch',
    refreshToken: 0
})
const emit = defineEmits<{ (e: 'selectBranch', branchUuid: string): void }>()

const router = useRouter()
const notification = useNotification()
const loading = ref(false)
const loadError = ref('')
const LIMIT_STORAGE_KEY = 'rearmLatestReleasesLimit'
function storedLimit (): number {
    try {
        const v = parseInt(window.localStorage.getItem(LIMIT_STORAGE_KEY) || '', 10)
        return Number.isFinite(v) && v > 0 ? Math.min(v, 200) : 20
    } catch { return 20 }
}
const limit = ref(storedLimit())
function onLimitChange (v: number | null) {
    if (!v || v < 1) return
    try { window.localStorage.setItem(LIMIT_STORAGE_KEY, String(v)) } catch {}
    fetchLatest()
}
const rows: Ref<any[]> = ref([])
const dtrackConfigured = ref(false)

async function fetchLatest () {
    loading.value = true
    loadError.value = ''
    try {
        const response = await graphqlClient.query({
            query: GqlQueries.LatestReleasesOfComponentGql,
            variables: { componentUuid: props.componentUuid, limit: limit.value },
            fetchPolicy: 'no-cache'
        })
        rows.value = (response.data as any).latestReleasesOfComponent || []
    } catch (error: any) {
        console.error('Error fetching latest releases per branch:', error)
        rows.value = []
        loadError.value = 'Could not load the latest releases (the backend may predate this view).'
        notification.error({ content: 'Error', meta: 'Failed to load latest releases', duration: 3000 })
    } finally {
        loading.value = false
    }
}

const branchTypeBadge: Record<string, { label: string, type: 'info' | 'warning' | 'success' | 'default' }> = {
    PULL_REQUEST: { label: 'PR', type: 'info' },
    TAG: { label: 'Tag', type: 'default' },
    HOTFIX: { label: 'Hotfix', type: 'warning' },
    RELEASE: { label: 'Release', type: 'success' }
}

function formatDate (dateStr: string): string {
    return dateStr ? new Date(dateStr).toLocaleDateString('en-CA') : ''
}
function formatDateTime (dateStr: string): string {
    return dateStr ? new Date(dateStr).toLocaleString('en-CA') : ''
}

const circle = (title: string, color: string, value: any, onClick: () => void) =>
    h('span', { title, class: 'circle', style: { background: color, cursor: 'pointer', fontSize: '0.8em' }, onClick: (e: Event) => { e.stopPropagation(); onClick() } }, String(value ?? 0))

const columns: DataTableColumns<any> = [
    {
        title: () => props.featureSetLabel,
        key: 'branch',
        render: (row: any) => {
            const b = row.branchDetails || {}
            const els: any[] = [h('span', { 'data-testid': 'latest-branch-name' }, b.name || '')]
            if (b.type === 'BASE') {
                els.push(h(NIcon, { size: 14, title: `Base ${props.featureSetLabel.toLowerCase()}` }, () => h(Star)))
            }
            const badge = branchTypeBadge[b.type]
            if (badge) {
                els.push(h(NTag, { size: 'small', type: badge.type, bordered: false }, () => badge.label))
            }
            return h('div', {
                style: 'display: flex; align-items: center; gap: 6px; cursor: pointer;',
                title: `Open ${props.featureSetLabel.toLowerCase()} ${b.name || ''}`,
                onClick: (e: Event) => { e.stopPropagation(); if (b.uuid) emit('selectBranch', b.uuid) }
            }, els)
        }
    },
    {
        title: 'Release',
        key: 'version',
        width: 260,
        render: (row: any) => h(RouterLink, {
            to: { name: 'ReleaseView', params: { uuid: row.uuid } },
            'data-testid': 'latest-release-link',
            onClick: (e: Event) => e.stopPropagation()
        }, { default: () => row.version })
    },
    {
        title: 'Lifecycle',
        key: 'lifecycle',
        width: 200,
        render: (row: any) => row.lifecycle
    },
    {
        title: 'Created',
        key: 'createdDate',
        width: 150,
        render: (row: any) => h('span', { style: 'display: inline-flex; align-items: center; gap: 4px;' }, [
            formatDate(row.createdDate),
            h(NTooltip, { trigger: 'hover', delay: 200 }, {
                trigger: () => h(NIcon, { size: 14, style: 'cursor: help;' }, () => h(CalendarTime)),
                default: () => 'Release created: ' + formatDateTime(row.createdDate)
            })
        ])
    },
    {
        title: 'Scan',
        key: 'scan',
        width: 320,
        render: (row: any) => {
            const status = getReleaseScanStatus(row, dtrackConfigured.value)
            if (status.kind !== 'ready') {
                return h('span', {
                    title: status.title,
                    style: { display: 'inline-block', padding: '2px 10px', borderRadius: '12px', color: 'white', fontSize: '0.8em', whiteSpace: 'nowrap', background: status.color }
                }, status.label)
            }
            if (!row.metrics?.lastScanned) return ''
            const m = row.metrics
            return h(NSpace, { size: 1 }, () => [
                circle('Critical Severity Vulnerabilities', constants.VulnerabilityColors.CRITICAL, m.critical, () => openVulnModal(row, 'CRITICAL', ['Vulnerability', 'Weakness'])),
                circle('High Severity Vulnerabilities', constants.VulnerabilityColors.HIGH, m.high, () => openVulnModal(row, 'HIGH', ['Vulnerability', 'Weakness'])),
                circle('Medium Severity Vulnerabilities', constants.VulnerabilityColors.MEDIUM, m.medium, () => openVulnModal(row, 'MEDIUM', ['Vulnerability', 'Weakness'])),
                circle('Low Severity Vulnerabilities', constants.VulnerabilityColors.LOW, m.low, () => openVulnModal(row, 'LOW', ['Vulnerability', 'Weakness'])),
                circle('Vulnerabilities with Unassigned Severity', constants.VulnerabilityColors.UNASSIGNED, m.unassigned, () => openVulnModal(row, 'UNASSIGNED', ['Vulnerability', 'Weakness'])),
                h('div', { style: 'width: 12px;' }),
                circle('Licensing Policy Violations', constants.ViolationColors.LICENSE, m.policyViolationsLicenseTotal, () => openVulnModal(row, '', 'Violation')),
                circle('Security Policy Violations', constants.ViolationColors.SECURITY, m.policyViolationsSecurityTotal, () => openVulnModal(row, '', 'Violation')),
                circle('Operational Policy Violations', constants.ViolationColors.OPERATIONAL, m.policyViolationsOperationalTotal, () => openVulnModal(row, '', 'Violation'))
            ])
        }
    }
]

const rowProps = (row: any) => ({
    style: 'cursor: pointer;',
    onClick: () => { if (row.uuid) router.push({ name: 'ReleaseView', params: { uuid: row.uuid } }) }
})
const rowClassName = (row: any) => (props.selectedBranchUuid && row.branchDetails?.uuid === props.selectedBranchUuid) ? 'selectedRow' : ''

const showVulnModal = ref(false)
const vulnModalRelease: Ref<any> = ref(null)
const vulnModalData: Ref<any[]> = ref([])
const vulnModalLoading = ref(false)
const vulnModalArtifacts: Ref<any[]> = ref([])
const vulnModalOrgUuid = ref('')
const vulnModalDtrackProjectUuids: Ref<string[]> = ref([])
const vulnModalSeverity = ref('')
const vulnModalType: Ref<string | string[]> = ref('')
async function openVulnModal (rel: any, severityFilter: string, typeFilter: string | string[]) {
    vulnModalRelease.value = rel
    vulnModalSeverity.value = severityFilter
    vulnModalType.value = typeFilter
    vulnModalLoading.value = true
    showVulnModal.value = true
    try {
        const releaseData = await ReleaseVulnerabilityService.fetchReleaseVulnerabilityData(rel.uuid, rel.org)
        vulnModalArtifacts.value = releaseData.artifacts
        vulnModalOrgUuid.value = releaseData.orgUuid
        vulnModalDtrackProjectUuids.value = releaseData.dtrackProjectUuids
        vulnModalData.value = releaseData.vulnerabilityData || []
    } catch (error) {
        console.error('Error fetching vulnerability details:', error)
        notification.error({ content: 'Error', meta: 'Failed to load vulnerability details', duration: 3000 })
    } finally {
        vulnModalLoading.value = false
    }
}

watch(() => props.componentUuid, () => fetchLatest())
watch(() => props.refreshToken, () => fetchLatest())
onMounted(async () => {
    try {
        dtrackConfigured.value = await isDtrackConfiguredForOrg(props.orgUuid)
    } catch {
        dtrackConfigured.value = false
    }
    await fetchLatest()
})
</script>

<style scoped lang="scss">
.latestReleasesHeader {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-bottom: 6px;
}
.latestReleasesTitle {
    font-weight: 600;
}
.latestReleasesEmpty {
    padding: 8px 0;
    color: #777;
}
:deep(.selectedRow td) {
    background-color: #f1f1f1 !important;
}
</style>
