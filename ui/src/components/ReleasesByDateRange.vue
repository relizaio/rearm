<template>
    <n-modal
        v-model:show="show"
        preset="dialog"
        :show-icon="false"
        :title="modalTitle"
        style="width: 95%; max-width: 1400px;"
        :auto-focus="false"
    >
        <n-space vertical>
            <n-space>
                <n-date-picker
                    v-model:value="startDateValue"
                    type="date"
                    placeholder="Start Date"
                    :disabled="loading"
                />
                <n-date-picker
                    v-model:value="endDateValue"
                    type="date"
                    placeholder="End Date"
                    :disabled="loading"
                />
            </n-space>
            <n-spin :show="loading">
                <component-branches-table
                    :data="componentData"
                    :org-uuid="props.orgUuid"
                    :feature-set-label="props.featureSetLabel"
                    :show-is-latest-column="props.showIsLatestColumn ?? false"
                />
            </n-spin>
        </n-space>
    </n-modal>
</template>

<script lang="ts">
export default {
    name: 'ReleasesByDateRange'
}
</script>

<script lang="ts" setup>
import { ref, computed, watch } from 'vue'
import { NModal, NSpin, NSpace, NDatePicker, useNotification } from 'naive-ui'
import gql from 'graphql-tag'
import graphqlClient from '@/utils/graphql'
import commonFunctions from '@/utils/commonFunctions'
import ComponentBranchesTable from './ComponentBranchesTable.vue'

const notification = useNotification()

const props = defineProps<{
    show: boolean
    orgUuid: string
    perspectiveUuid?: string
    perspectiveName?: string
    componentUuid?: string
    branchUuid?: string
    componentName?: string
    branchName?: string
    componentType?: string
    initialStartDate?: number
    initialEndDate?: number
    featureSetLabel?: string
    showIsLatestColumn?: boolean
}>()

const emit = defineEmits(['update:show', 'update:dates'])

const loading = ref(false)
const componentData = ref<any[]>([])
const startDateValue = ref<number | null>(null)
const endDateValue = ref<number | null>(null)

const modalTitle = computed(() => {
    const contextParts: string[] = []
    
    // Add component/branch context similar to FindingsOverTimeChart
    if (props.branchUuid && props.branchName) {
        const branchLabel = props.componentType === 'PRODUCT' ? 'Feature Set' : 'Branch'
        contextParts.push(`${branchLabel}: ${props.branchName}`)
    } else if (props.componentUuid && props.componentName) {
        const typeLabel = props.componentType === 'PRODUCT' ? 'Product' : 'Component'
        contextParts.push(`${typeLabel}: ${props.componentName}`)
    }
    
    // Add perspective context
    if (props.perspectiveName) {
        contextParts.push(`Perspective: ${props.perspectiveName}`)
    }
    
    const contextSuffix = contextParts.length > 0 ? ` - ${contextParts.join(', ')}` : ''
    
    if (startDateValue.value && endDateValue.value) {
        const startStr = new Date(startDateValue.value).toLocaleDateString('en-CA')
        const endStr = new Date(endDateValue.value).toLocaleDateString('en-CA')
        if (startStr === endStr) {
            return `Releases for ${startStr}${contextSuffix}`
        }
        return `Releases from ${startStr} to ${endStr}${contextSuffix}`
    }
    
    return `Releases by Date Range${contextSuffix}`
})

const show = computed({
    get: () => props.show,
    set: (value) => emit('update:show', value)
})

// Convert date to beginning of UTC day
function toStartOfUtcDay(timestamp: number): Date {
    const date = new Date(timestamp)
    return new Date(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate(), 0, 0, 0, 0))
}

// Convert date to end of UTC day
function toEndOfUtcDay(timestamp: number): Date {
    const date = new Date(timestamp)
    return new Date(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate(), 23, 59, 59, 999))
}

watch(() => props.show, async (newVal) => {
    if (newVal) {
        // Set initial dates if provided
        if (props.initialStartDate) {
            startDateValue.value = props.initialStartDate
        }
        if (props.initialEndDate) {
            endDateValue.value = props.initialEndDate
        }
        // Auto-fetch if both dates are set
        if (startDateValue.value && endDateValue.value) {
            await fetchReleases()
        }
    }
})

// Watch for datepicker changes - auto-fetch and emit to parent for URL sync
// Note: No timezone adjustment here - the datepicker already shows local dates correctly
watch([startDateValue, endDateValue], async ([newStart, newEnd]) => {
    if (props.show && newStart && newEnd) {
        // Use toLocaleDateString to get the date as shown in the datepicker (local timezone)
        const fromDate = new Date(newStart).toLocaleDateString('en-CA')
        const toDate = new Date(newEnd).toLocaleDateString('en-CA')
        emit('update:dates', { fromDate, toDate })
        // Auto-fetch on date changes
        await fetchReleases()
    }
})

// Watch for initial date changes
watch(() => [props.initialStartDate, props.initialEndDate], async ([newStart, newEnd]) => {
    if (props.show && newStart && newEnd) {
        startDateValue.value = newStart
        endDateValue.value = newEnd
        await fetchReleases()
    }
})

const COMPONENT_WITH_BRANCHES_FIELDS = `
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
`

const fetchReleases = async () => {
    if (!startDateValue.value || !endDateValue.value) return
    
    loading.value = true
    try {
        const startDate = toStartOfUtcDay(startDateValue.value)
        const endDate = toEndOfUtcDay(endDateValue.value)
        
        let response
        
        if (props.branchUuid) {
            // Branch-specific query
            response = await graphqlClient.query({
                query: gql`
                    query searchReleasesByTimeFrameAndBranch($branchUuid: ID!, $startDate: DateTime!, $endDate: DateTime!) {
                        searchReleasesByTimeFrameAndBranch(branchUuid: $branchUuid, startDate: $startDate, endDate: $endDate) {
                            ${COMPONENT_WITH_BRANCHES_FIELDS}
                        }
                    }
                `,
                variables: {
                    branchUuid: props.branchUuid,
                    startDate: startDate.toISOString(),
                    endDate: endDate.toISOString()
                },
                fetchPolicy: 'no-cache'
            })
            componentData.value = (response.data as any).searchReleasesByTimeFrameAndBranch || []
        } else if (props.componentUuid) {
            // Component-specific query
            response = await graphqlClient.query({
                query: gql`
                    query searchReleasesByTimeFrameAndComponent($componentUuid: ID!, $startDate: DateTime!, $endDate: DateTime!) {
                        searchReleasesByTimeFrameAndComponent(componentUuid: $componentUuid, startDate: $startDate, endDate: $endDate) {
                            ${COMPONENT_WITH_BRANCHES_FIELDS}
                        }
                    }
                `,
                variables: {
                    componentUuid: props.componentUuid,
                    startDate: startDate.toISOString(),
                    endDate: endDate.toISOString()
                },
                fetchPolicy: 'no-cache'
            })
            componentData.value = (response.data as any).searchReleasesByTimeFrameAndComponent || []
        } else {
            // Organization/Perspective query
            response = await graphqlClient.query({
                query: gql`
                    query searchReleasesByTimeFrame($org: ID!, $startDate: DateTime!, $endDate: DateTime!, $perspectiveUuid: ID) {
                        searchReleasesByTimeFrame(org: $org, startDate: $startDate, endDate: $endDate, perspectiveUuid: $perspectiveUuid) {
                            ${COMPONENT_WITH_BRANCHES_FIELDS}
                        }
                    }
                `,
                variables: {
                    org: props.orgUuid,
                    startDate: startDate.toISOString(),
                    endDate: endDate.toISOString(),
                    perspectiveUuid: props.perspectiveUuid || null
                },
                fetchPolicy: 'no-cache'
            })
            componentData.value = (response.data as any).searchReleasesByTimeFrame || []
        }
    } catch (error: any) {
        console.error('Error fetching releases by time frame:', error)
        componentData.value = []
        notification.error({
            title: 'Error',
            content: `Failed to fetch releases: ${commonFunctions.parseGraphQLError(error.message)}`,
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
