<template>
    <div>
        <div style="display: flex; align-items: center; gap: 8px;">
            <h3 style="margin: 0;">Most Recent Releases</h3>
            <router-link
                v-if="props.showFullPageIcon"
                :to="{ name: 'MostRecentReleases', params: { orguuid: props.orgUuid } }"
                title="Open Full Page View"
                style="display: flex; align-items: center;"
            >
                <n-icon class="clickable" size="20">
                    <ArrowExpand20Regular />
                </n-icon>
            </router-link>
        </div>
        <n-spin :show="loading" style="min-height: 150px;">
            <ol v-if="releases.length">
                <li v-for="rel in releases" :key="rel.uuid" style="margin-bottom: 8px;">
                    <div style="display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap;">
                        <span>
                            <router-link :to="{ name: rel.componentDetails?.type === 'PRODUCT' ? 'ProductsOfOrg' : 'ComponentsOfOrg', params: { orguuid: props.orgUuid, compuuid: rel.componentDetails?.uuid } }">{{ rel.componentDetails?.name }}</router-link>
                            &nbsp;·&nbsp;{{ rel.branchDetails?.name }}
                            &nbsp;·&nbsp;<router-link :to="{ name: 'ReleaseView', params: { uuid: rel.uuid } }">{{ rel.version }}</router-link>
                            &nbsp;·&nbsp;{{ formatDate(rel.createdDate) }}
                        </span>
                        <n-space :size="1" v-if="rel.metrics?.lastScanned">
                            <span title="Critical Severity Vulnerabilities" class="circle" :style="{ background: constants.VulnerabilityColors.CRITICAL, cursor: 'pointer' }" @click="openVulnModal(rel, 'CRITICAL', 'Vulnerability')">{{ rel.metrics.critical }}</span>
                            <span title="High Severity Vulnerabilities" class="circle" :style="{ background: constants.VulnerabilityColors.HIGH, cursor: 'pointer' }" @click="openVulnModal(rel, 'HIGH', 'Vulnerability')">{{ rel.metrics.high }}</span>
                            <span title="Medium Severity Vulnerabilities" class="circle" :style="{ background: constants.VulnerabilityColors.MEDIUM, cursor: 'pointer' }" @click="openVulnModal(rel, 'MEDIUM', 'Vulnerability')">{{ rel.metrics.medium }}</span>
                            <span title="Low Severity Vulnerabilities" class="circle" :style="{ background: constants.VulnerabilityColors.LOW, cursor: 'pointer' }" @click="openVulnModal(rel, 'LOW', 'Vulnerability')">{{ rel.metrics.low }}</span>
                            <span title="Vulnerabilities with Unassigned Severity" class="circle" :style="{ background: constants.VulnerabilityColors.UNASSIGNED, cursor: 'pointer' }" @click="openVulnModal(rel, 'UNASSIGNED', 'Vulnerability')">{{ rel.metrics.unassigned }}</span>
                            <div style="width: 12px;"></div>
                            <span title="Licensing Policy Violations" class="circle" :style="{ background: constants.ViolationColors.LICENSE, cursor: 'pointer' }" @click="openVulnModal(rel, '', 'Violation')">{{ rel.metrics.policyViolationsLicenseTotal }}</span>
                            <span title="Security Policy Violations" class="circle" :style="{ background: constants.ViolationColors.SECURITY, cursor: 'pointer' }" @click="openVulnModal(rel, '', 'Violation')">{{ rel.metrics.policyViolationsSecurityTotal }}</span>
                            <span title="Operational Policy Violations" class="circle" :style="{ background: constants.ViolationColors.OPERATIONAL, cursor: 'pointer' }" @click="openVulnModal(rel, '', 'Violation')">{{ rel.metrics.policyViolationsOperationalTotal }}</span>
                        </n-space>
                    </div>
                </li>
            </ol>
            <div v-else>No releases in the last 2 days.</div>
        </n-spin>

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
    name: 'MostRecentReleasesWidget'
}
</script>

<script lang="ts" setup>
import { ref, Ref, watch, onMounted } from 'vue'
import { NIcon, NSpin, NSpace, useNotification } from 'naive-ui'
import { ArrowExpand20Regular } from '@vicons/fluent'
import graphqlClient from '@/utils/graphql'
import GqlQueries from '@/utils/graphqlQueries'
import constants from '@/utils/constants'
import { ReleaseVulnerabilityService } from '@/utils/releaseVulnerabilityService'
import VulnerabilityModal from './VulnerabilityModal.vue'

const props = withDefaults(defineProps<{
    orgUuid: string
    perspectiveUuid?: string
    showFullPageIcon?: boolean
    maxReleases?: number
    startDate?: Date
    endDate?: Date
}>(), {
    showFullPageIcon: true,
    maxReleases: 5
})

const notification = useNotification()
const loading = ref(false)
const releases: Ref<any[]> = ref([])

const showVulnModal = ref(false)
const vulnModalRelease: Ref<any> = ref(null)
const vulnModalData: Ref<any[]> = ref([])
const vulnModalLoading = ref(false)
const vulnModalArtifacts: Ref<any[]> = ref([])
const vulnModalOrgUuid = ref('')
const vulnModalDtrackProjectUuids: Ref<string[]> = ref([])
const vulnModalSeverity = ref('')
const vulnModalType = ref('')

function getDateRange(): { startDate: Date, endDate: Date } {
    if (props.startDate && props.endDate) {
        return { startDate: props.startDate, endDate: props.endDate }
    }
    const now = new Date()
    const y = now.getUTCFullYear()
    const m = now.getUTCMonth()
    const d = now.getUTCDate()
    return {
        startDate: new Date(Date.UTC(y, m, d - 1, 0, 0, 0, 0)),
        endDate: new Date(Date.UTC(y, m, d, 23, 59, 59, 999))
    }
}

async function fetchReleases() {
    if (!props.orgUuid) return
    loading.value = true
    try {
        const { startDate, endDate } = getDateRange()
        const usePerspective = props.perspectiveUuid && props.perspectiveUuid !== 'default'
        let rawReleases: any[]
        if (usePerspective) {
            const response = await graphqlClient.query({
                query: GqlQueries.ReleasesByDateRangeAndPerspectiveGql,
                variables: {
                    perspectiveUuid: props.perspectiveUuid,
                    startDate: startDate.toISOString(),
                    endDate: endDate.toISOString()
                },
                fetchPolicy: 'no-cache'
            })
            rawReleases = (response.data as any).releasesByDateRangeAndPerspective || []
        } else {
            const response = await graphqlClient.query({
                query: GqlQueries.ReleasesByDateRangeGql,
                variables: {
                    org: props.orgUuid,
                    startDate: startDate.toISOString(),
                    endDate: endDate.toISOString()
                },
                fetchPolicy: 'no-cache'
            })
            rawReleases = (response.data as any).releasesByDateRange || []
        }
        releases.value = rawReleases
            .slice()
            .sort((a: any, b: any) => new Date(b.createdDate).getTime() - new Date(a.createdDate).getTime())
            .slice(0, props.maxReleases)
    } catch (error: any) {
        console.error('Error fetching recent releases:', error)
        notification.error({ content: 'Error', meta: 'Failed to load recent releases', duration: 3000 })
    } finally {
        loading.value = false
    }
}

async function openVulnModal(rel: any, severityFilter: string, typeFilter: string) {
    vulnModalRelease.value = rel
    vulnModalSeverity.value = severityFilter
    vulnModalType.value = typeFilter
    vulnModalLoading.value = true
    showVulnModal.value = true
    try {
        const releaseData = await ReleaseVulnerabilityService.fetchReleaseVulnerabilityData(
            rel.uuid,
            rel.org
        )
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

function formatDate(dateStr: string): string {
    if (!dateStr) return ''
    return new Date(dateStr).toLocaleDateString('en-CA')
}

watch(() => props.perspectiveUuid, () => fetchReleases())
watch(() => [props.startDate, props.endDate], () => fetchReleases())

onMounted(() => fetchReleases())
</script>
