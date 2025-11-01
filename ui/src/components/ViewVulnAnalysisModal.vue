<template>
    <n-modal
        v-model:show="isVisible"
        :title="modalTitle"
        preset="dialog"
        :show-icon="false"
        style="width: 90%;"
    >
        <n-spin :show="loading">
            <n-data-table
                :columns="columns"
                :data="analysisRecords"
                :pagination="{ pageSize: 10 }"
                :row-key="(row: any) => row.uuid"
            />
        </n-spin>
    </n-modal>
</template>

<script lang="ts">
export default {
    name: 'ViewVulnAnalysisModal'
}
</script>

<script lang="ts" setup>
import { ref, computed, watch, h } from 'vue'
import { NModal, NSpin, NDataTable, NTag, NSpace, useNotification, DataTableColumns } from 'naive-ui'
import gql from 'graphql-tag'
import graphqlClient from '@/utils/graphql'

interface Props {
    show: boolean
    orgUuid: string
    location: string
    findingId: string
    findingType: string
}

const props = defineProps<Props>()

const emit = defineEmits<{
    'update:show': [value: boolean]
}>()

const notification = useNotification()
const loading = ref(false)
const analysisRecords = ref<any[]>([])

const isVisible = computed({
    get: () => props.show,
    set: (value: boolean) => emit('update:show', value)
})

const modalTitle = computed(() => {
    return `Vulnerability Analysis Records - ${props.findingType}: ${props.findingId}, location: ${props.location}`
})

const columns: DataTableColumns<any> = [
    {
        title: 'Scope',
        key: 'scope',
        width: 120,
        render: (row: any) => {
            return h(NTag, { type: 'info', size: 'small' }, { default: () => row.scope })
        }
    },
    {
        title: 'Scope UUID',
        key: 'scopeUuid',
        width: 200,
        ellipsis: { tooltip: true }
    },
    {
        title: 'Location Type',
        key: 'locationType',
        width: 120,
        render: (row: any) => {
            return h(NTag, { type: 'success', size: 'small' }, { default: () => row.locationType })
        }
    },
    {
        title: 'Current State',
        key: 'analysisState',
        width: 150,
        render: (row: any) => {
            const stateColors: any = {
                EXPLOITABLE: 'error',
                IN_TRIAGE: 'warning',
                FALSE_POSITIVE: 'success',
                NOT_AFFECTED: 'info'
            }
            return h(NTag, { 
                type: stateColors[row.analysisState] || 'default', 
                size: 'small' 
            }, { default: () => row.analysisState })
        }
    },
    {
        title: 'Justification',
        key: 'analysisJustification',
        width: 180,
        ellipsis: { tooltip: true },
        render: (row: any) => {
            return row.analysisJustification || '-'
        }
    },
    {
        title: 'History',
        key: 'analysisHistory',
        minWidth: 300,
        render: (row: any) => {
            if (!row.analysisHistory || row.analysisHistory.length === 0) {
                return '-'
            }
            
            return h(NSpace, { vertical: true, size: 'small' }, {
                default: () => row.analysisHistory.map((history: any, index: number) => {
                    const stateColors: any = {
                        EXPLOITABLE: 'error',
                        IN_TRIAGE: 'warning',
                        FALSE_POSITIVE: 'success',
                        NOT_AFFECTED: 'info'
                    }
                    
                    const dateStr = history.createdDate 
                        ? new Date(history.createdDate).toLocaleString('en-CA', { hour12: false })
                        : 'Unknown'
                    
                    const justification = history.justification ? ` - ${history.justification}` : ''
                    const details = history.details ? ` (${history.details})` : ''
                    
                    return h('div', { key: index, style: 'font-size: 12px;' }, [
                        h(NTag, { 
                            type: stateColors[history.state] || 'default', 
                            size: 'tiny',
                            style: 'margin-right: 4px;'
                        }, { default: () => history.state }),
                        h('span', {}, `${dateStr}${justification}${details}`)
                    ])
                })
            })
        }
    }
]

// Watch for modal open and fetch data
watch(() => props.show, async (newValue) => {
    if (newValue && props.orgUuid && props.location && props.findingId && props.findingType) {
        await fetchAnalysisRecords()
    }
})

const fetchAnalysisRecords = async () => {
    loading.value = true
    try {
        const response = await graphqlClient.query({
            query: gql`
                query getVulnAnalysisByLocationAndFinding(
                    $org: ID!
                    $location: String!
                    $findingId: String!
                    $findingType: FindingType!
                ) {
                    getVulnAnalysisByLocationAndFinding(
                        org: $org
                        location: $location
                        findingId: $findingId
                        findingType: $findingType
                    ) {
                        uuid
                        org
                        location
                        locationType
                        findingId
                        findingAliases
                        findingType
                        scope
                        scopeUuid
                        analysisState
                        analysisJustification
                        analysisHistory {
                            state
                            justification
                            details
                            createdDate
                        }
                    }
                }
            `,
            variables: {
                org: props.orgUuid,
                location: props.location,
                findingId: props.findingId,
                findingType: props.findingType
            }
        })
        
        analysisRecords.value = response.data.getVulnAnalysisByLocationAndFinding || []
    } catch (error: any) {
        console.error('Error fetching vulnerability analysis records:', error)
        notification.error({
            content: 'Failed to fetch vulnerability analysis records',
            meta: error.message || 'Unknown error',
            duration: 5000
        })
        analysisRecords.value = []
    } finally {
        loading.value = false
    }
}
</script>
