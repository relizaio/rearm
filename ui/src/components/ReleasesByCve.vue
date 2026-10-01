<template>
    <n-modal
        v-model:show="show"
        preset="dialog"
        :show-icon="false"
        :title="modalTitle"
        style="width: 95%; max-width: 1400px;"
        :auto-focus="false"
    >
        <n-spin :show="loading">
            <n-alert
                v-if="truncated"
                type="warning"
                style="margin-bottom: 12px;"
                :show-icon="true"
            >
                Showing the {{ shownReleases }} most recent matching releases of {{ totalReleases }} total.
                Narrow the search by perspective or refine the finding to see the rest.
            </n-alert>
            <component-branches-table
                :data="componentData"
                :org-uuid="props.orgUuid"
                :feature-set-label="props.featureSetLabel"
                :show-is-latest-column="props.showIsLatestColumn ?? true"
            />
        </n-spin>
        <vulnerability-details-modal
            v-model:show="vulnDetail.show"
            :org-uuid="props.orgUuid || ''"
            :vuln-id="vulnDetail.vulnId"
            :severity="vulnDetail.severity"
            :known-exploited="vulnDetail.knownExploited"
        />
    </n-modal>
</template>

<script lang="ts">
export default {
    name: 'ReleasesByCve'
}
</script>

<script lang="ts" setup>
import { ref, computed, watch, h } from 'vue'
import { NModal, NSpin, NAlert, useNotification } from 'naive-ui'
import gql from 'graphql-tag'
import graphqlClient from '@/utils/graphql'
import commonFunctions from '@/utils/commonFunctions'
import ComponentBranchesTable from './ComponentBranchesTable.vue'
import VulnerabilityDetailsModal from './VulnerabilityDetailsModal.vue'
import { useVulnerabilityDetail } from '@/utils/useVulnerabilityDetail'
import { findingTypeOfSearchedId, renderFindingId } from '@/utils/findingUtils'

const notification = useNotification()

const props = defineProps<{
    show: boolean
    cveId: string
    orgUuid: string
    perspectiveUuid?: string
    perspectiveName?: string
    featureSetLabel?: string
    showIsLatestColumn?: boolean
}>()

const emit = defineEmits(['update:show'])

const loading = ref(false)
const componentData = ref<any[]>([])
const totalReleases = ref(0)
const shownReleases = ref(0)
const truncated = ref(false)

const { vulnDetail, openVulnDetail } = useVulnerabilityDetail(() => props.orgUuid)

const modalTitle = computed(() => {
    // The home search binds its input here, and a cleared input is null.
    const cveId = (props.cveId || '').trim()
    const perspectiveSuffix = props.perspectiveName ? `, Perspective: ${props.perspectiveName}` : ''
    return () => h('span', [
        'Releases Affected by ',
        renderFindingId(h, cveId, findingTypeOfSearchedId(cveId), openVulnDetail),
        perspectiveSuffix
    ])
})

const show = computed({
    get: () => props.show,
    set: (value) => emit('update:show', value)
})

watch(() => props.show, async (newVal) => {
    if (newVal && props.cveId && props.orgUuid) {
        await fetchReleases()
    }
})

// Also watch for cveId changes to trigger search when modal is already open
watch(() => props.cveId, async (newVal) => {
    if (props.show && newVal && props.orgUuid) {
        await fetchReleases()
    }
})

const fetchReleases = async () => {
    loading.value = true
    try {
        const response = await graphqlClient.query({
            query: gql`
                query searchReleasesByCveId($org: ID!, $cveId: String!, $perspectiveUuid: ID) {
                    searchReleasesByCveId(org: $org, cveId: $cveId, perspectiveUuid: $perspectiveUuid) {
                        totalReleases
                        shownReleases
                        truncated
                        components {
                            uuid
                            name
                            type
                            versionSchema
                            branches {
                                uuid
                                name
                                status
                                versionSchema
                                latestReleaseVersion
                                releases {
                                    uuid
                                    version
                                    createdDate
                                    lifecycle
                                    metrics {
                                        critical
                                        high
                                        medium
                                        low
                                        unassigned
                                        policyViolationsLicenseTotal
                                        policyViolationsSecurityTotal
                                        policyViolationsOperationalTotal
                                    }
                                }
                            }
                        }
                    }
                }
            `,
            variables: {
                org: props.orgUuid,
                cveId: props.cveId,
                perspectiveUuid: props.perspectiveUuid || null
            },
            fetchPolicy: 'no-cache'
        })
        const result = (response.data as any).searchReleasesByCveId
        componentData.value = result?.components || []
        totalReleases.value = result?.totalReleases || 0
        shownReleases.value = result?.shownReleases || 0
        truncated.value = result?.truncated || false
    } catch (error: any) {
        console.error('Error fetching releases by CVE ID:', error)
        componentData.value = []
        truncated.value = false
        notification.error({
            title: 'Error',
            content: `Failed to fetch releases for ${props.cveId}: ${commonFunctions.parseGraphQLError(error.message)}`,
            duration: 5000,
            keepAliveOnHover: true
        })
    } finally {
        loading.value = false
    }
}
</script>

<style scoped>
.circle {
    display: inline-block;
    min-width: 24px;
    height: 24px;
    border-radius: 50%;
    text-align: center;
    line-height: 24px;
    color: white;
    font-size: 12px;
    padding: 0 4px;
}
</style>
