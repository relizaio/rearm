<template>
    <n-modal
        v-model:show="isVisible"
        :title="modalTitle"
        preset="dialog"
        :show-icon="false"
        style="width: 90%;"
    >
        <n-spin :show="loading">
            <n-space vertical>
                <n-data-table
                    :columns="columns"
                    :data="analysisRecords"
                    :pagination="{ pageSize: 10 }"
                    :row-key="(row: any) => row.uuid"
                />
                <n-button v-if="hasAvailableScopes" type="primary" @click="handleAddScope">
                    Add Scope
                </n-button>
            </n-space>
        </n-spin>
    </n-modal>
    
    <update-vuln-analysis-modal
        v-model:show="showUpdateAnalysisModal"
        :analysis-record="selectedAnalysisRecord"
        @updated="onAnalysisUpdated"
    />
    
    <create-vuln-analysis-modal
        v-model:show="showCreateAnalysisModal"
        :finding-row="findingRowData"
        :org-uuid="orgUuid"
        :release-uuid="releaseUuid"
        :branch-uuid="branchUuid"
        :component-uuid="componentUuid"
        :component-name="componentName"
        :component-type="componentType"
        :branch-name="branchName"
        :release-version="releaseVersion"
        :artifact-view-only="artifactViewOnly"
        :available-scopes-only="availableScopes"
        @created="onAnalysisCreated"
    />
</template>

<script lang="ts">
export default {
    name: 'ViewVulnAnalysisModal'
}
</script>

<script lang="ts" setup>
import { ref, computed, watch, h } from 'vue'
import { NModal, NSpin, NDataTable, NTag, NSpace, NButton, NIcon, NTooltip, useNotification, DataTableColumns, NA } from 'naive-ui'
import { RouterLink } from 'vue-router'
import gql from 'graphql-tag'
import graphqlClient from '@/utils/graphql'
import CreateVulnAnalysisModal from './CreateVulnAnalysisModal.vue'
import UpdateVulnAnalysisModal from './UpdateVulnAnalysisModal.vue'
import { Edit } from '@vicons/tabler'
import { useStore } from 'vuex'
import { Info20Regular } from '@vicons/fluent'
import commonFunctions from '@/utils/commonFunctions'

interface Props {
    show: boolean
    orgUuid: string
    location: string
    findingId: string
    findingType: string
    findingAliases?: any[]
    severity?: string
    releaseUuid?: string
    branchUuid?: string
    componentUuid?: string
    componentName?: string
    componentType?: string
    branchName?: string
    releaseVersion?: string
    artifactViewOnly?: boolean
}

const props = withDefaults(defineProps<Props>(), {
    findingAliases: () => [],
    severity: '',
    releaseUuid: '',
    branchUuid: '',
    componentUuid: '',
    componentName: '',
    componentType: '',
    branchName: '',
    releaseVersion: '',
    artifactViewOnly: false
})

const emit = defineEmits<{
    'update:show': [value: boolean]
    'analysis-changed': []
}>()

const store = useStore()
const notification = useNotification()

const myorg = computed(() => store.state.org)
const featureSetLabel = computed(() => myorg.value?.featureSetLabel || 'Feature Set')
const loading = ref(false)
const analysisRecords = ref<any[]>([])
const showCreateAnalysisModal = ref(false)
const showUpdateAnalysisModal = ref(false)
const selectedAnalysisRecord = ref<any>(null)

const isVisible = computed({
    get: () => props.show,
    set: (value: boolean) => emit('update:show', value)
})

const modalTitle = computed(() => {
    return `Vulnerability Analysis Records - ${props.findingType}: ${props.findingId}, location: ${props.location}`
})

// Helper function to check if a specific scope with UUID exists in analysis records
const hasScopeWithUuid = (scope: string, scopeUuid: string): boolean => {
    return analysisRecords.value.some(record => 
        record.scope === scope && record.scopeUuid === scopeUuid
    )
}

// Compute available scopes based on context and existing records
const availableScopes = computed(() => {
    const scopes: string[] = []
    
    // Check if ORG scope with current org UUID exists
    if (!hasScopeWithUuid('ORG', props.orgUuid)) {
        scopes.push('ORG')
    }
    
    if (!props.artifactViewOnly) {
        // Check if COMPONENT scope with current component UUID exists
        if (props.componentUuid && !hasScopeWithUuid('COMPONENT', props.componentUuid)) {
            scopes.push('COMPONENT')
        }
        // Check if BRANCH scope with current branch UUID exists
        if (props.branchUuid && !hasScopeWithUuid('BRANCH', props.branchUuid)) {
            scopes.push('BRANCH')
        }
        // Check if RELEASE scope with current release UUID exists
        if (props.releaseUuid && !hasScopeWithUuid('RELEASE', props.releaseUuid)) {
            scopes.push('RELEASE')
        }
    }
    
    return scopes
})

const hasAvailableScopes = computed(() => {
    return availableScopes.value.length > 0
})

// Create finding row data for the CreateVulnAnalysisModal
const findingRowData = computed(() => {
    return {
        id: props.findingId,
        type: props.findingType,
        purl: props.location,
        location: props.location,
        aliases: props.findingAliases,
        severity: props.severity
    }
})

const handleAddScope = () => {
    showCreateAnalysisModal.value = true
}

const handleEditAnalysis = (record: any) => {
    selectedAnalysisRecord.value = record
    showUpdateAnalysisModal.value = true
}

const onAnalysisCreated = async () => {
    // Refresh the analysis records
    await fetchAnalysisRecords()
    emit('analysis-changed')
}

const onAnalysisUpdated = async () => {
    // Refresh the analysis records
    await fetchAnalysisRecords()
    emit('analysis-changed')
}

const columns: DataTableColumns<any> = [
    {
        title: 'Scope',
        key: 'scope',
        width: 120,
        render: (row: any) => {
            // Determine scope label based on component type
            let scopeLabel = row.scope
            if (row.componentDetails?.type === 'PRODUCT') {
                if (row.scope === 'COMPONENT') {
                    scopeLabel = 'PRODUCT'
                } else if (row.scope === 'BRANCH') {
                    scopeLabel = featureSetLabel.value.toUpperCase()
                }
            }
            return h(NTag, { type: 'info', size: 'small' }, { default: () => scopeLabel })
        }
    },
    {
        title: 'Scope Details',
        key: 'scopeUuid',
        minWidth: 200,
        render: (row: any) => {
            // Determine display based on scope type
            if (row.scope === 'ORG') {
                return h('span', { style: 'color: #666;' }, 'Organization-wide')
            }
            
            if (row.scope === 'COMPONENT' && row.componentDetails) {
                // Product+Component scope -> display as "Product" if type is PRODUCT
                const routeName = row.componentDetails.type === 'PRODUCT' ? 'ProductsOfOrg' : 'ComponentsOfOrg'
                const orgUuid = myorg.value?.uuid || props.orgUuid
                return h(RouterLink, {
                    to: {
                        name: routeName,
                        params: {
                            orguuid: orgUuid,
                            compuuid: row.componentDetails.uuid
                        }
                    },
                    style: 'color: #18a058; text-decoration: none;'
                }, () => row.componentDetails.name)
            }
            
            if (row.scope === 'BRANCH' && row.branchDetails) {
                // Product+Branch scope -> display as "Feature Set" if type is PRODUCT
                const componentName = row.componentDetails?.name || ''
                const branchName = row.branchDetails.name
                const displayText = componentName ? `${componentName} - ${branchName}` : branchName
                
                if (row.componentDetails) {
                    const routeName = row.componentDetails.type === 'PRODUCT' ? 'ProductsOfOrg' : 'ComponentsOfOrg'
                    const orgUuid = myorg.value?.uuid || props.orgUuid
                    return h(RouterLink, {
                        to: {
                            name: routeName,
                            params: {
                                orguuid: orgUuid,
                                compuuid: row.componentDetails.uuid,
                                branchuuid: row.branchDetails.uuid
                            }
                        },
                        style: 'color: #18a058; text-decoration: none;'
                    }, () => displayText)
                }
                return displayText
            }
            
            if (row.scope === 'RELEASE' && row.releaseDetails) {
                const componentName = row.componentDetails?.name || ''
                const releaseVersion = row.releaseDetails.version
                const displayText = componentName ? `${componentName} - ${releaseVersion}` : releaseVersion
                
                return h(RouterLink, {
                    to: {
                        name: 'ReleaseView',
                        params: {
                            uuid: row.releaseDetails.uuid
                        }
                    },
                    style: 'color: #18a058; text-decoration: none;'
                }, () => displayText)
            }
            
            // Fallback to scopeUuid if no details available
            return row.scopeUuid || '-'
        }
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
        minWidth: 250,
        render: (row: any) => {
            if (!row.analysisHistory || row.analysisHistory.length === 0) {
                return '-'
            }
            
            return h(NSpace, { vertical: true, size: 'small' }, {
                default: () => [...row.analysisHistory].reverse().map((history: any, index: number) => {
                    const stateColors: any = {
                        EXPLOITABLE: 'error',
                        IN_TRIAGE: 'warning',
                        FALSE_POSITIVE: 'success',
                        NOT_AFFECTED: 'info'
                    }
                    
                    const dateStr = history.createdDate 
                        ? new Date(history.createdDate).toLocaleString('en-CA', { hour12: false })
                        : 'Unknown'
                    
                    // Build tooltip content
                    const tooltipLines: string[] = []
                    if (history.justification) {
                        tooltipLines.push(`Justification: ${history.justification}`)
                    }
                    if (history.severity) {
                        tooltipLines.push(`Severity: ${history.severity}`)
                    }
                    if (history.details) {
                        tooltipLines.push(`Details: ${history.details}`)
                    }
                    const tooltipContent = tooltipLines.length > 0 ? tooltipLines.join('\n') : 'No additional information'
                    
                    const tag = h(NTag, { 
                        type: stateColors[history.state] || 'default', 
                        size: 'tiny',
                        style: 'margin-right: 4px;'
                    }, { default: () => history.state })
                    
                    const dateSpan = h('span', { style: 'font-size: 12px;' }, dateStr)
                    
                    const infoIcon = h(NTooltip, {
                        trigger: 'hover'
                    }, {
                        trigger: () => h(NIcon, {
                            style: 'margin-left: 6px; cursor: pointer;',
                            size: 14
                        }, () => h(Info20Regular)),
                        default: () => tooltipContent
                    })
                    
                    return h('div', { 
                        key: index, 
                        style: 'display: flex; align-items: center; font-size: 12px;' 
                    }, [tag, dateSpan, infoIcon])
                })
            })
        }
    },
    {
        title: 'Actions',
        key: 'actions',
        width: 80,
        render: (row: any) => {
            const editIcon = h(NIcon, {
                title: 'Edit Analysis',
                class: 'icons clickable',
                size: 25,
                onClick: () => handleEditAnalysis(row)
            }, () => h(Edit))
            
            return h('div', {}, [editIcon])
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
                        severity
                        analysisHistory {
                            state
                            justification
                            severity
                            details
                            createdDate
                        }
                        releaseDetails {
                            uuid
                            version
                        }
                        branchDetails {
                            uuid
                            name
                        }
                        componentDetails {
                            uuid
                            name
                            type
                        }
                    }
                }
            `,
            variables: {
                org: props.orgUuid,
                location: props.location,
                findingId: props.findingId,
                findingType: props.findingType
            },
            fetchPolicy: 'no-cache'
        })
        
        analysisRecords.value = response.data.getVulnAnalysisByLocationAndFinding || []
    } catch (error: any) {
        console.error('Error fetching vulnerability analysis records:', error)
        const errorMessage = commonFunctions.extractGraphQLErrorMessage(error)
        notification.error({
            content: `Failed to fetch vulnerability analysis records: ${errorMessage}`,
            meta: 'Error',
            duration: 5000
        })
        analysisRecords.value = []
    } finally {
        loading.value = false
    }
}
</script>
